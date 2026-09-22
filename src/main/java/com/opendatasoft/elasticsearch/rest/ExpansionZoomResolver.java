package com.opendatasoft.elasticsearch.rest;

import com.opendatasoft.elasticsearch.search.aggregations.bucket.geopointclustering.InternalGeoPointClustering;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes the expansion zoom of clusters, the equivalent of supercluster's {@code getClusterExpansionZoom()}: the
 * first zoom level at which a cluster breaks into several clusters.
 * <p>
 * Clusters are matched across zoom levels through their geohash cells. The clustering precision grows with the zoom
 * level, so the cells of a cluster at a given zoom are always contained in the cells of its parent cluster at a lower
 * zoom: truncating a cell to the parent precision gives the parent cell.
 */
public final class ExpansionZoomResolver {

    private ExpansionZoomResolver() {
        throw new AssertionError("No instances intended");
    }

    /**
     * @param parents the clusters to compute an expansion zoom for
     * @param zooms   the zoom levels {@code levels} have been computed at, in ascending order
     * @param levels  the same clustering, run at each of the {@code zooms} levels
     * @return the expansion zoom of each parent cluster, keyed by cluster hash. Clusters that do not break within the
     *         requested zoom levels are mapped to the deepest zoom level that was looked at, which still moves the map
     *         closer to the split.
     */
    public static Map<Long, Integer> resolve(
        List<InternalGeoPointClustering.Bucket> parents,
        int[] zooms,
        List<InternalGeoPointClustering> levels
    ) {
        Map<Long, Integer> expansionZooms = new HashMap<>();
        if (parents.isEmpty() || zooms.length == 0) {
            return expansionZooms;
        }

        Map<String, Long> cellToParent = new HashMap<>();
        int parentPrecision = 0;
        for (InternalGeoPointClustering.Bucket parent : parents) {
            for (String cell : parent.getGeohashGrids()) {
                cellToParent.put(cell, parent.hashAsLong());
                parentPrecision = cell.length();
            }
        }

        for (int level = 0; level < zooms.length && level < levels.size(); level++) {
            InternalGeoPointClustering clustering = levels.get(level);
            if (clustering == null) {
                continue;
            }

            Map<Long, Integer> childCounts = new HashMap<>();
            for (InternalGeoPointClustering.Bucket child : clustering.getBuckets()) {
                Long parent = parentOf(child, cellToParent, parentPrecision);
                if (parent != null) {
                    childCounts.merge(parent, 1, Integer::sum);
                }
            }

            for (Map.Entry<Long, Integer> childCount : childCounts.entrySet()) {
                if (childCount.getValue() >= 2) {
                    expansionZooms.putIfAbsent(childCount.getKey(), zooms[level]);
                }
            }
        }

        int deepestZoom = zooms[zooms.length - 1];
        for (InternalGeoPointClustering.Bucket parent : parents) {
            expansionZooms.putIfAbsent(parent.hashAsLong(), deepestZoom);
        }
        return expansionZooms;
    }

    /**
     * Finds the parent cluster a cluster of a deeper zoom level belongs to. A cluster may hold cells coming from
     * several parents when cells have been merged, in that case the parent holding most of its cells wins.
     */
    private static Long parentOf(InternalGeoPointClustering.Bucket child, Map<String, Long> cellToParent, int parentPrecision) {
        Map<Long, Integer> candidates = new HashMap<>();
        for (String cell : child.getGeohashGrids()) {
            if (cell.length() < parentPrecision) {
                continue;
            }
            Long parent = cellToParent.get(cell.substring(0, parentPrecision));
            if (parent != null) {
                candidates.merge(parent, 1, Integer::sum);
            }
        }

        Long best = null;
        int bestCount = 0;
        for (Map.Entry<Long, Integer> candidate : candidates.entrySet()) {
            // Ties are broken on the cluster hash, to keep the result stable from one tile request to the next.
            if (candidate.getValue() > bestCount || (best != null && candidate.getValue() == bestCount && candidate.getKey() < best)) {
                best = candidate.getKey();
                bestCount = candidate.getValue();
            }
        }
        return best;
    }
}
