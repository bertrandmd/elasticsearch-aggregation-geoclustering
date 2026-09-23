package com.opendatasoft.elasticsearch.rest;

import com.opendatasoft.elasticsearch.mvt.WebMercator;
import com.opendatasoft.elasticsearch.search.aggregations.bucket.geopointclustering.GeoPointClusteringAggregationBuilder;
import com.opendatasoft.elasticsearch.search.aggregations.bucket.geopointclustering.GeoPointClusteringParams;

import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.common.Strings;
import org.elasticsearch.index.query.AbstractQueryBuilder;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.action.RestActions;
import org.elasticsearch.search.aggregations.metrics.GeoBoundsAggregationBuilder;
import org.elasticsearch.search.aggregations.metrics.TopHitsAggregationBuilder;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.fetch.subphase.FetchSourceContext;
import org.elasticsearch.xcontent.ObjectParser;
import org.elasticsearch.xcontent.ParseField;
import org.elasticsearch.xcontent.XContentParser;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Parameters of a clustered vector tile request.
 * <p>
 * Every parameter can be set either as a query string parameter or in the request body, so that a map client able to
 * fetch plain tile URLs only (a MapLibre or Mapbox vector source, for instance) can drive the whole clustering without
 * any intermediate service. Body values take precedence over query string ones.
 */
final class GeoPointClusteringTileRequest {

    static final String CLUSTERS_AGG = "clusters";
    static final String LEAF_AGG = "leaf";
    static final String BOUNDS_AGG = "bounds";
    static final String EXPANSION_AGG_PREFIX = "expansion_";

    private static final int MAX_MVT_EXTENT = 16384;
    private static final int MAX_EXPANSION_ZOOM_DEPTH = 8;
    private static final int MIN_EXPANSION_SIZE = 100;

    private static final ObjectParser<GeoPointClusteringTileRequest, Void> PARSER = new ObjectParser<>("geo_point_clustering_mvt");

    static {
        PARSER.declareInt((r, v) -> r.radius = v, new ParseField("radius"));
        PARSER.declareInt((r, v) -> r.extent = v, new ParseField("extent"));
        PARSER.declareDouble((r, v) -> r.ratio = v, new ParseField("ratio"));
        PARSER.declareBoolean((r, v) -> r.cluster = v, new ParseField("cluster"));
        PARSER.declareInt((r, v) -> r.clusterMaxZoom = v, new ParseField("cluster_max_zoom"));
        PARSER.declareInt((r, v) -> r.mvtExtent = v, new ParseField("mvt_extent"));
        PARSER.declareInt((r, v) -> r.buffer = v, new ParseField("buffer"));
        PARSER.declareInt((r, v) -> r.size = v, new ParseField("size"));
        PARSER.declareInt((r, v) -> r.maxHits = v, new ParseField("max_hits"));
        PARSER.declareStringArray((r, v) -> r.fields = v, new ParseField("fields"));
        PARSER.declareBoolean((r, v) -> r.includeId = v, new ParseField("include_id"));
        PARSER.declareBoolean((r, v) -> r.expansionZoom = v, new ParseField("expansion_zoom"));
        PARSER.declareInt((r, v) -> r.expansionZoomDepth = v, new ParseField("expansion_zoom_depth"));
        PARSER.declareString((r, v) -> r.clustersLayer = v, new ParseField("clusters_layer"));
        PARSER.declareString((r, v) -> r.poisLayer = v, new ParseField("pois_layer"));
        PARSER.declareObject((r, v) -> r.query = v, (p, c) -> AbstractQueryBuilder.parseTopLevelQuery(p), new ParseField("query"));
        PARSER.declareObject((r, v) -> r.runtimeMappings = v, (p, c) -> p.map(), new ParseField("runtime_mappings"));
    }

    String[] indices;
    String field;
    int zoom;
    int tileX;
    int tileY;

    int radius = GeoPointClusteringAggregationBuilder.DEFAULT_RADIUS;
    int extent = GeoPointClusteringAggregationBuilder.DEFAULT_EXTENT;
    double ratio = GeoPointClusteringAggregationBuilder.DEFAULT_RATIO;
    /** Whether documents are clustered at all. Turning it off serves every document of the tile as a point. */
    boolean cluster = true;
    int clusterMaxZoom = 16;
    int mvtExtent = 4096;
    /** Query buffer, in {@link #extent} pixels. Defaults to twice the clustering radius. */
    Integer buffer = null;
    int size = GeoPointClusteringAggregationBuilder.DEFAULT_MAX_NUM_CELLS;
    int maxHits = 10000;
    List<String> fields = Collections.emptyList();
    boolean includeId = true;
    boolean expansionZoom = true;
    int expansionZoomDepth = 4;
    String clustersLayer = "clusters";
    String poisLayer = "pois";
    QueryBuilder query = null;
    Map<String, Object> runtimeMappings = Collections.emptyMap();
    String preference = null;
    String routing = null;

    static GeoPointClusteringTileRequest parse(RestRequest restRequest) throws IOException {
        GeoPointClusteringTileRequest request = new GeoPointClusteringTileRequest();

        request.indices = Strings.splitStringByCommaToArray(restRequest.param("index"));
        request.field = restRequest.param("field");
        request.zoom = restRequest.paramAsInt("z", 0);
        request.tileX = restRequest.paramAsInt("x", 0);
        request.tileY = restRequest.paramAsInt("y", 0);

        request.radius = restRequest.paramAsInt("radius", request.radius);
        request.extent = restRequest.paramAsInt("extent", request.extent);
        request.ratio = restRequest.paramAsDouble("ratio", request.ratio);
        request.cluster = restRequest.paramAsBoolean("cluster", request.cluster);
        request.clusterMaxZoom = restRequest.paramAsInt("cluster_max_zoom", request.clusterMaxZoom);
        request.mvtExtent = restRequest.paramAsInt("mvt_extent", request.mvtExtent);
        if (restRequest.hasParam("buffer")) {
            request.buffer = restRequest.paramAsInt("buffer", 0);
        }
        request.size = restRequest.paramAsInt("size", request.size);
        request.maxHits = restRequest.paramAsInt("max_hits", request.maxHits);
        request.fields = List.of(restRequest.paramAsStringArray("fields", Strings.EMPTY_ARRAY));
        request.includeId = restRequest.paramAsBoolean("include_id", request.includeId);
        request.expansionZoom = restRequest.paramAsBoolean("expansion_zoom", request.expansionZoom);
        request.expansionZoomDepth = restRequest.paramAsInt("expansion_zoom_depth", request.expansionZoomDepth);
        request.clustersLayer = restRequest.param("clusters_layer", request.clustersLayer);
        request.poisLayer = restRequest.param("pois_layer", request.poisLayer);
        request.preference = restRequest.param("preference");
        request.routing = restRequest.param("routing");

        // Standard URI search parameters: q, df, analyzer, analyze_wildcard, lenient and default_operator.
        request.query = RestActions.urlParamsToQueryBuilder(restRequest);

        if (restRequest.hasContentOrSourceParam()) {
            try (XContentParser parser = restRequest.contentOrSourceParamParser()) {
                PARSER.parse(parser, request, null);
            }
        }

        request.validate();
        return request;
    }

    private void validate() {
        if (field == null || field.isEmpty()) {
            throw new IllegalArgumentException("[field] is required");
        }
        GeoPointClusteringParams.checkZoom(zoom);
        long tiles = 1L << zoom;
        if (tileX < 0 || tileX >= tiles || tileY < 0 || tileY >= tiles) {
            throw new IllegalArgumentException("[x] and [y] must be between 0 and " + (tiles - 1) + " at zoom " + zoom);
        }
        if (radius <= 0) {
            throw new IllegalArgumentException("[radius] must be greater than 0, found [" + radius + "]");
        }
        if (extent <= 0) {
            throw new IllegalArgumentException("[extent] must be greater than 0, found [" + extent + "]");
        }
        if (mvtExtent <= 0 || mvtExtent > MAX_MVT_EXTENT) {
            throw new IllegalArgumentException("[mvt_extent] must be between 1 and " + MAX_MVT_EXTENT + ", found [" + mvtExtent + "]");
        }
        if (buffer != null && buffer < 0) {
            throw new IllegalArgumentException("[buffer] must be greater than or equal to 0, found [" + buffer + "]");
        }
        if (size <= 0) {
            throw new IllegalArgumentException("[size] must be greater than 0, found [" + size + "]");
        }
        if (maxHits <= 0) {
            throw new IllegalArgumentException("[max_hits] must be greater than 0, found [" + maxHits + "]");
        }
        if (clusterMaxZoom < 0 || clusterMaxZoom > GeoPointClusteringParams.MAX_ZOOM) {
            throw new IllegalArgumentException(
                "[cluster_max_zoom] must be between 0 and " + GeoPointClusteringParams.MAX_ZOOM + ", found [" + clusterMaxZoom + "]"
            );
        }
        if (expansionZoomDepth < 0 || expansionZoomDepth > MAX_EXPANSION_ZOOM_DEPTH) {
            throw new IllegalArgumentException(
                "[expansion_zoom_depth] must be between 0 and " + MAX_EXPANSION_ZOOM_DEPTH + ", found [" + expansionZoomDepth + "]"
            );
        }
        if (clustersLayer.equals(poisLayer)) {
            throw new IllegalArgumentException("[clusters_layer] and [pois_layer] must be different");
        }
    }

    /** Whether this tile is served as clusters, or as raw points. */
    boolean isClustered() {
        return cluster && zoom <= clusterMaxZoom;
    }

    /** Zoom levels the expansion zoom of the clusters of this tile is looked up at, in ascending order. */
    int[] expansionZooms() {
        if (isClustered() == false || expansionZoom == false || expansionZoomDepth == 0) {
            return new int[0];
        }
        int lastZoom = Math.min(zoom + expansionZoomDepth, Math.min(clusterMaxZoom + 1, GeoPointClusteringParams.MAX_ZOOM));
        if (lastZoom <= zoom) {
            return new int[0];
        }
        int[] zooms = new int[lastZoom - zoom];
        for (int i = 0; i < zooms.length; i++) {
            zooms[i] = zoom + i + 1;
        }
        return zooms;
    }

    SearchRequest toSearchRequest() {
        SearchSourceBuilder source = new SearchSourceBuilder().trackTotalHits(false);
        if (runtimeMappings.isEmpty() == false) {
            source.runtimeMappings(runtimeMappings);
        }

        BoolQueryBuilder boolQuery = QueryBuilders.boolQuery();
        if (query != null) {
            boolQuery.filter(query);
        }
        boolQuery.filter(boundingBoxQuery());
        source.query(boolQuery);

        if (isClustered()) {
            source.size(0);
            GeoPointClusteringAggregationBuilder clusters = clusteringAggregation(CLUSTERS_AGG, zoom, size);
            TopHitsAggregationBuilder leaf = leafAggregation();
            if (leaf != null) {
                clusters.subAggregation(leaf);
            }
            if (expansionZooms().length > 0) {
                // The span of a cluster tells when it can break apart at the earliest, and tells for sure that a
                // cluster of points sitting on the same spot never will.
                clusters.subAggregation(new GeoBoundsAggregationBuilder(BOUNDS_AGG).field(field).wrapLongitude(true));
            }
            source.aggregation(clusters);
            for (int expansionZoomLevel : expansionZooms()) {
                source.aggregation(clusteringAggregation(EXPANSION_AGG_PREFIX + expansionZoomLevel, expansionZoomLevel, expansionSize()));
            }
        } else {
            source.size(maxHits);
            source.sort("_doc");
            source.docValueField(field);
            source.fetchSource(sourceContext());
        }

        SearchRequest searchRequest = new SearchRequest(indices, source);
        searchRequest.preference(preference);
        searchRequest.routing(routing);
        return searchRequest;
    }

    /**
     * Bounding box of the documents to fetch. Clustered tiles query a buffered area so that clusters straddling the
     * tile border are built from all their points, whatever tile they belong to.
     */
    private QueryBuilder boundingBoxQuery() {
        double bufferPixels = isClustered() ? (buffer != null ? buffer : 2.0 * radius) : 0;
        double[] bounds = WebMercator.tileBounds(zoom, tileX, tileY, bufferPixels, extent);
        return QueryBuilders.geoBoundingBoxQuery(field).setCorners(bounds[3], bounds[0], bounds[1], bounds[2]);
    }

    /**
     * Buckets the expansion zoom levels may return, each. Deeper levels hold exponentially more clusters, so they
     * share a budget instead of each being allowed {@link #size} buckets: a tile then never asks for much more than
     * twice {@code size} buckets, whatever the depth, and stays clear of the {@code search.max_buckets} limit. A level
     * hitting that budget is reported as truncated rather than as an absence of split.
     */
    int expansionSize() {
        int levels = expansionZooms().length;
        if (levels == 0) {
            return size;
        }
        return Math.max(MIN_EXPANSION_SIZE, size / levels);
    }

    private GeoPointClusteringAggregationBuilder clusteringAggregation(String name, int aggregationZoom, int bucketCount) {
        return new GeoPointClusteringAggregationBuilder(name).field(field)
            .zoom(aggregationZoom)
            .radius(radius)
            .extent(extent)
            .ratio(ratio)
            .size(bucketCount);
    }

    /**
     * Sub aggregation fetching the document behind a single point cluster, so that it can be rendered as a point with
     * its own properties instead of a cluster of one.
     */
    private TopHitsAggregationBuilder leafAggregation() {
        if (fields.isEmpty() && includeId == false) {
            return null;
        }
        TopHitsAggregationBuilder leaf = new TopHitsAggregationBuilder(LEAF_AGG).size(1);
        leaf.fetchSource(sourceContext());
        return leaf;
    }

    private FetchSourceContext sourceContext() {
        if (fields.isEmpty()) {
            return FetchSourceContext.DO_NOT_FETCH_SOURCE;
        }
        return FetchSourceContext.of(true, fields.toArray(Strings.EMPTY_ARRAY), null);
    }

}
