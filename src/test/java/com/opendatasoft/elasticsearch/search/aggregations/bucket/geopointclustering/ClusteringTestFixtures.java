package com.opendatasoft.elasticsearch.search.aggregations.bucket.geopointclustering;

import org.elasticsearch.common.geo.GeoPoint;
import org.elasticsearch.geometry.utils.Geohash;
import org.elasticsearch.search.aggregations.InternalAggregations;

import java.util.List;

/**
 * Builds clustering results without running an aggregation, so that the code consuming them can be unit tested.
 * Lives in the aggregation package to reach its package private constructors.
 */
public final class ClusteringTestFixtures {

    private ClusteringTestFixtures() {}

    public static InternalGeoPointClustering clustering(String name, InternalGeoPointClustering.Bucket... buckets) {
        return new InternalGeoPointClustering(name, 40, 0, 10000, List.of(buckets), null);
    }

    public static InternalGeoPointClustering.Bucket bucket(long docCount, String cell, String... mergedCells) {
        return bucket(
            docCount,
            cell,
            Geohash.decodeLatitude(Geohash.longEncode(cell)),
            Geohash.decodeLongitude(Geohash.longEncode(cell)),
            InternalAggregations.EMPTY,
            mergedCells
        );
    }

    public static InternalGeoPointClustering.Bucket bucket(
        long docCount,
        String cell,
        double lat,
        double lon,
        InternalAggregations subAggregations,
        String... mergedCells
    ) {
        InternalGeoPointClustering.Bucket bucket = new InternalGeoPointClustering.Bucket(
            Geohash.longEncode(cell),
            new GeoPoint(lat, lon),
            docCount,
            subAggregations
        );
        for (String mergedCell : mergedCells) {
            bucket.geohashesList.add(Geohash.longEncode(mergedCell));
        }
        return bucket;
    }
}
