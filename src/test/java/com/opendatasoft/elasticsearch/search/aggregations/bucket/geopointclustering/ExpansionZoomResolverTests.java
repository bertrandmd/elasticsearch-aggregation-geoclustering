package com.opendatasoft.elasticsearch.search.aggregations.bucket.geopointclustering;

import com.opendatasoft.elasticsearch.rest.ExpansionZoomResolver;

import org.elasticsearch.test.ESTestCase;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The resolver lives in the rest package but its fixtures need the package private bucket constructors, hence this
 * test sitting in the aggregation package.
 */
public class ExpansionZoomResolverTests extends ESTestCase {

    public void testClusterSplittingAtTheFirstLevel() {
        InternalGeoPointClustering.Bucket parent = bucket(3, "u09t");
        // Two clusters at the next zoom level, both inside the parent cell: the cluster expands right away.
        InternalGeoPointClustering level = clustering(bucket(2, "u09tx"), bucket(1, "u09tz"));

        Map<Long, Integer> expansionZooms = ExpansionZoomResolver.resolve(List.of(parent), new int[] { 10, 11 }, List.of(level, level));
        assertEquals(Integer.valueOf(10), expansionZooms.get(parent.hashAsLong()));
    }

    public void testClusterSplittingAtADeeperLevel() {
        InternalGeoPointClustering.Bucket parent = bucket(3, "u09t");
        InternalGeoPointClustering stillTogether = clustering(bucket(3, "u09tx"));
        InternalGeoPointClustering split = clustering(bucket(2, "u09tx"), bucket(1, "u09ty"));

        Map<Long, Integer> expansionZooms = ExpansionZoomResolver.resolve(
            List.of(parent),
            new int[] { 10, 11, 12 },
            Arrays.asList(stillTogether, stillTogether, split)
        );
        assertEquals(Integer.valueOf(12), expansionZooms.get(parent.hashAsLong()));
    }

    public void testClusterThatNeverSplitsFallsBackToTheDeepestZoom() {
        InternalGeoPointClustering.Bucket parent = bucket(3, "u09t");
        InternalGeoPointClustering stillTogether = clustering(bucket(3, "u09tx"));

        Map<Long, Integer> expansionZooms = ExpansionZoomResolver.resolve(
            List.of(parent),
            new int[] { 10, 11, 12 },
            Arrays.asList(stillTogether, stillTogether, stillTogether)
        );
        assertEquals(Integer.valueOf(12), expansionZooms.get(parent.hashAsLong()));
    }

    public void testClustersAreMatchedThroughTheirOwnCellsOnly() {
        InternalGeoPointClustering.Bucket left = bucket(5, "u09t");
        InternalGeoPointClustering.Bucket right = bucket(4, "u09w");
        // Only the right cluster breaks into two at zoom 10.
        InternalGeoPointClustering level = clustering(bucket(5, "u09tx"), bucket(2, "u09w5"), bucket(2, "u09w7"));

        Map<Long, Integer> expansionZooms = ExpansionZoomResolver.resolve(
            List.of(left, right),
            new int[] { 10, 11 },
            List.of(level, level)
        );
        assertEquals(Integer.valueOf(11), expansionZooms.get(left.hashAsLong()));
        assertEquals(Integer.valueOf(10), expansionZooms.get(right.hashAsLong()));
    }

    public void testMergedCellsAreMatchedToTheirMainParent() {
        InternalGeoPointClustering.Bucket parent = bucket(6, "u09t", "u09w");
        // The deeper zoom kept the merge, its cells belong to the same parent: no split yet.
        InternalGeoPointClustering merged = clustering(bucket(6, "u09tx", "u09w5"));
        // Then it breaks apart.
        InternalGeoPointClustering split = clustering(bucket(4, "u09tx"), bucket(2, "u09w5"));

        Map<Long, Integer> expansionZooms = ExpansionZoomResolver.resolve(
            List.of(parent),
            new int[] { 10, 11 },
            Arrays.asList(merged, split)
        );
        assertEquals(Integer.valueOf(11), expansionZooms.get(parent.hashAsLong()));
    }

    public void testSamePrecisionOnBothZoomLevels() {
        // Zoom levels sharing the same geohash precision are matched on the whole cell.
        InternalGeoPointClustering.Bucket parent = bucket(3, "u09tx", "u09tz");
        InternalGeoPointClustering split = clustering(bucket(2, "u09tx"), bucket(1, "u09tz"));

        Map<Long, Integer> expansionZooms = ExpansionZoomResolver.resolve(List.of(parent), new int[] { 10 }, List.of(split));
        assertEquals(Integer.valueOf(10), expansionZooms.get(parent.hashAsLong()));
    }

    public void testNoZoomLevelToLookAt() {
        InternalGeoPointClustering.Bucket parent = bucket(3, "u09t");
        assertEquals(Collections.emptyMap(), ExpansionZoomResolver.resolve(List.of(parent), new int[0], List.of()));
        assertEquals(Collections.emptyMap(), ExpansionZoomResolver.resolve(List.of(), new int[] { 10 }, List.of()));
    }

    private static InternalGeoPointClustering clustering(InternalGeoPointClustering.Bucket... buckets) {
        return ClusteringTestFixtures.clustering("clusters", buckets);
    }

    private static InternalGeoPointClustering.Bucket bucket(long docCount, String cell, String... mergedCells) {
        return ClusteringTestFixtures.bucket(docCount, cell, mergedCells);
    }
}
