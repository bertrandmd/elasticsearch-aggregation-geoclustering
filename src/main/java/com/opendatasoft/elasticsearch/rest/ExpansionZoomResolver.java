package com.opendatasoft.elasticsearch.rest;

import com.opendatasoft.elasticsearch.search.aggregations.bucket.geopointclustering.InternalGeoPointClustering;

import org.elasticsearch.common.geo.GeoUtils;

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
     * Lowest zoom at which a cluster can possibly break apart, read from the span of its documents.
     * <p>
     * Clusters are merged while their parts are closer than the radius, and the radius halves at every zoom level. As
     * long as it stays wider than the whole cluster, every point is within reach of every other one and no zoom level
     * can break the cluster: the first level narrower than the span is therefore a lower bound of the expansion zoom.
     * Documents sitting on the very same spot span nothing and never break apart, which is what {@code maxZoom} is
     * returned for.
     *
     * @param spanMeters   how far apart the documents of the cluster are, at most
     * @param latitude     latitude of the cluster, the radius is corrected by it during the merge
     * @param radiusPixels clustering radius, in {@code extent} pixels
     * @param extent       tile size, in pixels, the radius is expressed in
     * @param maxZoom      deepest zoom worth returning, usually {@code cluster_max_zoom + 1}
     * @return the lowest zoom the cluster can break apart at, or 0 when the span puts no constraint on it
     */
    public static int minimumSplitZoom(double spanMeters, double latitude, double radiusPixels, int extent, int maxZoom) {
        if (spanMeters <= 0) {
            return maxZoom;
        }
        double latitudeCorrection = Math.max(Math.cos(Math.toRadians(latitude)), 1e-6);
        // radiusPixels * EARTH_EQUATOR / (extent * 2^zoom) * cos(latitude) < spanMeters
        double tiles = radiusPixels * GeoUtils.EARTH_EQUATOR * latitudeCorrection / (extent * spanMeters);
        if (tiles <= 1) {
            return 0;
        }
        return Math.min((int) Math.floor(Math.log(tiles) / Math.log(2)) + 1, maxZoom);
    }

    /**
     * @param parents   the clusters to compute an expansion zoom for
     * @param zooms     the zoom levels {@code levels} have been computed at, in ascending order
     * @param levels    the same clustering, run at each of the {@code zooms} levels
     * @param levelSize the number of buckets a level was allowed to return; a level returning that many has been
     *                  truncated, so it cannot establish that a cluster does not split
     * @return the expansion zoom of each parent cluster, keyed by cluster hash. A cluster that does not break within
     *         the requested zoom levels is mapped to the deepest zoom that was looked at, which still moves the map
     *         closer to the split. A cluster whose absence of split could not be established, because a level was
     *         truncated, is left out: better no answer than a wrong one, the caller can fall back to the expansion
     *         endpoint, which is exact.
     */
    public static Map<Long, Integer> resolve(
        List<InternalGeoPointClustering.Bucket> parents,
        int[] zooms,
        List<InternalGeoPointClustering> levels,
        int levelSize
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

        boolean truncated = false;
        for (int level = 0; level < zooms.length && level < levels.size(); level++) {
            InternalGeoPointClustering clustering = levels.get(level);
            if (clustering == null) {
                truncated = true;
                continue;
            }
            // Buckets beyond the limit are dropped, so a cluster may look unsplit although it is not. Splits that are
            // seen remain true, hence the level is still worth looking at.
            truncated |= clustering.getBuckets().size() >= levelSize;

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

        if (truncated == false) {
            int deepestZoom = zooms[zooms.length - 1];
            for (InternalGeoPointClustering.Bucket parent : parents) {
                expansionZooms.putIfAbsent(parent.hashAsLong(), deepestZoom);
            }
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
