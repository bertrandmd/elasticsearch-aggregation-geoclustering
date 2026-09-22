package com.opendatasoft.elasticsearch.search.aggregations.bucket.geopointclustering;

import org.elasticsearch.xcontent.ParseField;

/**
 * Encapsulates relevant parameter defaults and validations for the geo point clustering aggregation.
 */
public final class GeoPointClusteringParams {

    /** Highest zoom level the aggregation accepts. */
    public static final int MAX_ZOOM = 25;

    /* recognized field names in JSON */
    static final ParseField FIELD_SIZE = new ParseField("size");
    static final ParseField FIELD_SHARD_SIZE = new ParseField("shard_size");
    static final ParseField FIELD_ZOOM = new ParseField("zoom");
    static final ParseField FIELD_EXTENT = new ParseField("extent");
    static final ParseField FIELD_RADIUS = new ParseField("radius");
    static final ParseField FIELD_RATIO = new ParseField("ratio");

    public static int checkZoom(int zoom) {
        if ((zoom < 0) || (zoom > MAX_ZOOM)) {
            throw new IllegalArgumentException("Invalid geohash aggregation zoom of " + zoom + ". Must be between 0 and " + MAX_ZOOM + ".");
        }
        return zoom;
    }

    private GeoPointClusteringParams() {
        throw new AssertionError("No instances intended");
    }
}
