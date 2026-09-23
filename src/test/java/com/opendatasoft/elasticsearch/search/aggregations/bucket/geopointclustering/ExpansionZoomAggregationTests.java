package com.opendatasoft.elasticsearch.search.aggregations.bucket.geopointclustering;

import com.opendatasoft.elasticsearch.plugin.GeoPointClusteringAggregationPlugin;
import com.opendatasoft.elasticsearch.rest.ExpansionZoomResolver;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.LatLonDocValuesField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.index.RandomIndexWriter;
import org.elasticsearch.geometry.utils.Geohash;
import org.elasticsearch.index.mapper.GeoPointFieldMapper;
import org.elasticsearch.plugins.SearchPlugin;
import org.elasticsearch.search.aggregations.AggregatorTestCase;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

/**
 * Runs the real aggregation over an in memory index and checks the expansion zoom the tiles expose against the
 * clusters the documents actually form at that zoom.
 */
public class ExpansionZoomAggregationTests extends AggregatorTestCase {

    private static final String FIELD = "location";
    private static final int RADIUS = 80;
    private static final int EXTENT = 512;
    private static final int DEPTH = 8;

    @Override
    protected List<SearchPlugin> getSearchPlugins() {
        return List.of(new GeoPointClusteringAggregationPlugin());
    }

    public void testExpansionZoomAgainstDocumentMembership() throws IOException {
        List<double[]> points = points();
        int exact = 0;
        int overshoot = 0;

        try (Directory directory = newDirectory()) {
            try (RandomIndexWriter writer = new RandomIndexWriter(random(), directory)) {
                for (double[] point : points) {
                    Document document = new Document();
                    document.add(new LatLonDocValuesField(FIELD, point[1], point[0]));
                    writer.addDocument(document);
                }
            }

            try (IndexReader reader = DirectoryReader.open(directory)) {
                for (int zoom : new int[] { 10, 11, 12, 13, 14 }) {
                    InternalGeoPointClustering parents = cluster(reader, zoom);
                    int[] zooms = new int[DEPTH];
                    List<InternalGeoPointClustering> levels = new ArrayList<>();
                    for (int i = 0; i < DEPTH; i++) {
                        zooms[i] = zoom + i + 1;
                        levels.add(cluster(reader, zooms[i]));
                    }

                    List<InternalGeoPointClustering.Bucket> clusters = parents.getBuckets()
                        .stream()
                        .filter(bucket -> bucket.getDocCount() > 1)
                        .toList();
                    Map<Long, Integer> resolved = ExpansionZoomResolver.resolve(clusters, zooms, levels, Integer.MAX_VALUE);

                    for (InternalGeoPointClustering.Bucket parent : clusters) {
                        Set<Integer> documents = documentsOf(points, parent);
                        Integer expansionZoom = resolved.get(parent.hashAsLong());
                        assertNotNull("no expansion zoom for a cluster of the tile", expansionZoom);

                        Integer actualSplit = null;
                        for (int i = 0; i < zooms.length; i++) {
                            if (clusterCount(points, levels.get(i), documents) >= 2) {
                                actualSplit = zooms[i];
                                break;
                            }
                        }

                        int splitAtResolved = clusterCount(points, levels.get(expansionZoom - zoom - 1), documents);

                        if (actualSplit != null) {
                            if (actualSplit.intValue() == expansionZoom) {
                                exact++;
                            } else {
                                overshoot++;
                            }

                            // The value must land on a zoom where the cluster is really broken apart, otherwise
                            // clicking it does nothing.
                            assertThat(
                                "expansion_zoom " + expansionZoom + " does not split a cluster of " + parent.getDocCount() + " documents",
                                splitAtResolved,
                                greaterThanOrEqualTo(2)
                            );
                        } else {
                            assertEquals(
                                "unsplit clusters report the deepest zoom looked at",
                                zooms[zooms.length - 1],
                                (int) expansionZoom
                            );
                        }
                    }
                }
            }
        }

        // The reported zoom is the one the documents really split at, bar the rare cluster whose child is captured by
        // a neighbour at the next level, which only ever pushes the answer one level deeper.
        assertThat("no cluster was checked", exact + overshoot, greaterThan(20));
        assertThat("too many approximate expansion zooms", overshoot, lessThanOrEqualTo((exact + overshoot) / 10));
    }

    private InternalGeoPointClustering cluster(IndexReader reader, int zoom) throws IOException {
        GeoPointClusteringAggregationBuilder aggregation = new GeoPointClusteringAggregationBuilder("clusters").field(FIELD)
            .zoom(zoom)
            .radius(RADIUS)
            .extent(EXTENT);
        return searchAndReduce(reader, new AggTestConfig(aggregation, new GeoPointFieldMapper.GeoPointFieldType(FIELD)));
    }

    /** Documents held by a cluster: those whose geohash cell, at the precision of the cluster, is one of its cells. */
    private static Set<Integer> documentsOf(List<double[]> points, InternalGeoPointClustering.Bucket cluster) {
        Set<String> cells = new HashSet<>(cluster.getGeohashGrids());
        int precision = cells.iterator().next().length();
        Set<Integer> documents = new HashSet<>();
        for (int i = 0; i < points.size(); i++) {
            if (cells.contains(Geohash.stringEncode(points.get(i)[0], points.get(i)[1], precision))) {
                documents.add(i);
            }
        }
        return documents;
    }

    /** How many clusters of a deeper zoom level the documents of a cluster are spread over. */
    private static int clusterCount(List<double[]> points, InternalGeoPointClustering level, Set<Integer> documents) {
        Map<String, Integer> cellToCluster = new HashMap<>();
        List<InternalGeoPointClustering.Bucket> buckets = level.getBuckets();
        int precision = 0;
        for (int i = 0; i < buckets.size(); i++) {
            for (String cell : buckets.get(i).getGeohashGrids()) {
                cellToCluster.put(cell, i);
                precision = cell.length();
            }
        }

        Set<Integer> found = new HashSet<>();
        for (int document : documents) {
            Integer cluster = cellToCluster.get(Geohash.stringEncode(points.get(document)[0], points.get(document)[1], precision));
            if (cluster != null) {
                found.add(cluster);
            }
        }
        return found.size();
    }

    /** Brussels-like data: dense downtown groups, a tight duplicate group and scattered points. */
    private static List<double[]> points() {
        Random random = new Random(7);
        List<double[]> points = new ArrayList<>();
        double[][] groups = {
            { 4.3517, 50.8466, 60, 0.004 },
            { 4.39, 50.84, 40, 0.001 },
            { 4.33, 50.87, 25, 0.0003 },
            { 4.41, 50.86, 15, 0.02 } };
        for (double[] group : groups) {
            for (int i = 0; i < group[2]; i++) {
                points.add(new double[] { group[0] + random.nextGaussian() * group[3], group[1] + random.nextGaussian() * group[3] });
            }
        }
        // Exact duplicates: they can only be told apart above cluster_max_zoom.
        for (int i = 0; i < 5; i++) {
            points.add(new double[] { 4.3600, 50.8500 });
        }
        for (int i = 0; i < 20; i++) {
            points.add(new double[] { 4.30 + random.nextDouble() * 0.15, 50.82 + random.nextDouble() * 0.08 });
        }
        return points;
    }
}
