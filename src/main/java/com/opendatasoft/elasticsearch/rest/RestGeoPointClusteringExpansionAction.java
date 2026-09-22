package com.opendatasoft.elasticsearch.rest;

import com.opendatasoft.elasticsearch.search.aggregations.bucket.geopointclustering.GeoPointClusteringAggregationBuilder;
import com.opendatasoft.elasticsearch.search.aggregations.bucket.geopointclustering.GeoPointClusteringParams;
import com.opendatasoft.elasticsearch.search.aggregations.bucket.geopointclustering.InternalGeoPointClustering;

import org.elasticsearch.action.search.MultiSearchRequest;
import org.elasticsearch.action.search.MultiSearchResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.client.internal.node.NodeClient;
import org.elasticsearch.common.Strings;
import org.elasticsearch.geometry.Rectangle;
import org.elasticsearch.geometry.utils.Geohash;
import org.elasticsearch.index.query.AbstractQueryBuilder;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestResponse;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.rest.action.RestActions;
import org.elasticsearch.rest.action.RestResponseListener;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.xcontent.ObjectParser;
import org.elasticsearch.xcontent.ParseField;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import static org.elasticsearch.rest.RestRequest.Method.GET;
import static org.elasticsearch.rest.RestRequest.Method.POST;

/**
 * Server side equivalent of supercluster's {@code getClusterExpansionZoom()}: given the geohash cells of a cluster,
 * returns the first zoom level at which it breaks into several clusters, along with those clusters.
 * <p>
 * Tiles already carry an {@code expansion_zoom} property, but it is only looked up a few zoom levels ahead. This
 * endpoint gives the exact answer, typically on a cluster click.
 */
public class RestGeoPointClusteringExpansionAction extends BaseRestHandler {

    static final String PATH = "/{index}/_geo_point_clustering/_expansion/{field}";

    private static final int DEFAULT_MAX_ZOOM = 18;
    private static final int MAX_CELLS = 1024;

    private static final ObjectParser<ExpansionRequest, Void> PARSER = new ObjectParser<>("geo_point_clustering_expansion");

    static {
        PARSER.declareInt((r, v) -> r.zoom = v, new ParseField("zoom"));
        PARSER.declareStringArray((r, v) -> r.cells = v, new ParseField("cells"));
        PARSER.declareInt((r, v) -> r.radius = v, new ParseField("radius"));
        PARSER.declareInt((r, v) -> r.extent = v, new ParseField("extent"));
        PARSER.declareDouble((r, v) -> r.ratio = v, new ParseField("ratio"));
        PARSER.declareInt((r, v) -> r.maxZoom = v, new ParseField("max_zoom"));
        PARSER.declareObject((r, v) -> r.query = v, (p, c) -> AbstractQueryBuilder.parseTopLevelQuery(p), new ParseField("query"));
    }

    @Override
    public String getName() {
        return "geo_point_clustering_expansion_action";
    }

    @Override
    public List<Route> routes() {
        return List.of(new Route(GET, PATH), new Route(POST, PATH));
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest restRequest, NodeClient client) throws IOException {
        ExpansionRequest request = ExpansionRequest.parse(restRequest);
        MultiSearchRequest multiSearchRequest = request.toMultiSearchRequest();

        return channel -> client.multiSearch(multiSearchRequest, new RestResponseListener<>(channel) {
            @Override
            public RestResponse buildResponse(MultiSearchResponse multiSearchResponse) throws Exception {
                return new RestResponse(RestStatus.OK, request.buildResponse(channel.newBuilder(), multiSearchResponse));
            }
        });
    }

    /** Parameters of an expansion zoom lookup, readable both from the query string and from the request body. */
    private static final class ExpansionRequest {

        private static final String CLUSTERS_AGG = "clusters";

        private String[] indices;
        private String field;
        private int zoom = -1;
        private List<String> cells = Collections.emptyList();
        private int radius = GeoPointClusteringAggregationBuilder.DEFAULT_RADIUS;
        private int extent = GeoPointClusteringAggregationBuilder.DEFAULT_EXTENT;
        private double ratio = GeoPointClusteringAggregationBuilder.DEFAULT_RATIO;
        private int maxZoom = DEFAULT_MAX_ZOOM;
        private QueryBuilder query = null;

        static ExpansionRequest parse(RestRequest restRequest) throws IOException {
            ExpansionRequest request = new ExpansionRequest();
            request.indices = Strings.splitStringByCommaToArray(restRequest.param("index"));
            request.field = restRequest.param("field");
            request.zoom = restRequest.paramAsInt("zoom", request.zoom);
            request.cells = List.of(restRequest.paramAsStringArray("cells", Strings.EMPTY_ARRAY));
            request.radius = restRequest.paramAsInt("radius", request.radius);
            request.extent = restRequest.paramAsInt("extent", request.extent);
            request.ratio = restRequest.paramAsDouble("ratio", request.ratio);
            request.maxZoom = restRequest.paramAsInt("max_zoom", request.maxZoom);

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
            if (cells.isEmpty()) {
                throw new IllegalArgumentException("[cells] is required, it holds the geohash_grids of the cluster to expand");
            }
            if (cells.size() > MAX_CELLS) {
                throw new IllegalArgumentException("[cells] must hold at most " + MAX_CELLS + " cells, found [" + cells.size() + "]");
            }
            if (maxZoom <= zoom || maxZoom > GeoPointClusteringParams.MAX_ZOOM) {
                throw new IllegalArgumentException(
                    "[max_zoom] must be greater than [zoom] and at most " + GeoPointClusteringParams.MAX_ZOOM + ", found [" + maxZoom + "]"
                );
            }
            if (radius <= 0) {
                throw new IllegalArgumentException("[radius] must be greater than 0, found [" + radius + "]");
            }
            if (extent <= 0) {
                throw new IllegalArgumentException("[extent] must be greater than 0, found [" + extent + "]");
            }
        }

        /** One search per candidate zoom level, restricted to the members of the cluster being expanded. */
        MultiSearchRequest toMultiSearchRequest() {
            BoolQueryBuilder membersQuery = QueryBuilders.boolQuery();
            if (query != null) {
                membersQuery.filter(query);
            }
            membersQuery.filter(cellsQuery());

            MultiSearchRequest multiSearchRequest = new MultiSearchRequest();
            for (int candidateZoom = zoom + 1; candidateZoom <= maxZoom; candidateZoom++) {
                SearchSourceBuilder source = new SearchSourceBuilder().size(0)
                    .trackTotalHits(false)
                    .query(membersQuery)
                    .aggregation(
                        new GeoPointClusteringAggregationBuilder(CLUSTERS_AGG).field(field)
                            .zoom(candidateZoom)
                            .radius(radius)
                            .extent(extent)
                            .ratio(ratio)
                    );
                multiSearchRequest.add(new SearchRequest(indices, source));
            }
            return multiSearchRequest;
        }

        /** Matches the documents held by the geohash cells of the cluster: a geohash cell is a bounding box. */
        private QueryBuilder cellsQuery() {
            BoolQueryBuilder cellsQuery = QueryBuilders.boolQuery().minimumShouldMatch(1);
            for (String cell : cells) {
                Rectangle boundingBox = Geohash.toBoundingBox(cell);
                cellsQuery.should(
                    QueryBuilders.geoBoundingBoxQuery(field)
                        .setCorners(boundingBox.getMaxLat(), boundingBox.getMinLon(), boundingBox.getMinLat(), boundingBox.getMaxLon())
                );
            }
            return cellsQuery;
        }

        XContentBuilder buildResponse(XContentBuilder builder, MultiSearchResponse multiSearchResponse) throws Exception {
            MultiSearchResponse.Item[] items = multiSearchResponse.getResponses();

            InternalGeoPointClustering expansion = null;
            int expansionZoom = maxZoom;
            for (int i = 0; i < items.length; i++) {
                if (items[i].isFailure()) {
                    // Let the failure bubble up with its own status, it is the same search the tile was built with.
                    throw items[i].getFailure();
                }
                InternalGeoPointClustering clustering = items[i].getResponse().getAggregations().get(CLUSTERS_AGG);
                if (clustering == null) {
                    continue;
                }
                expansion = clustering;
                expansionZoom = zoom + 1 + i;
                // The cluster is expanded as soon as it breaks into several clusters, or is down to a single document.
                List<InternalGeoPointClustering.Bucket> buckets = clustering.getBuckets();
                if (buckets.size() > 1 || buckets.isEmpty() || buckets.get(0).getDocCount() <= 1) {
                    break;
                }
            }

            List<InternalGeoPointClustering.Bucket> expansionBuckets = expansion == null ? Collections.emptyList() : expansion.getBuckets();
            long docCount = 0;
            for (InternalGeoPointClustering.Bucket bucket : expansionBuckets) {
                docCount += bucket.getDocCount();
            }

            builder.startObject();
            builder.field("zoom", expansionZoom);
            builder.field("bucket_count", expansionBuckets.size());
            builder.field("doc_count", docCount);
            builder.startArray("clusters");
            for (InternalGeoPointClustering.Bucket bucket : expansionBuckets) {
                builder.startObject();
                builder.field("geohash_grids", bucket.getGeohashGrids());
                builder.field("doc_count", bucket.getDocCount());
                builder.field("centroid", bucket.getCentroid());
                builder.endObject();
            }
            builder.endArray();
            builder.endObject();
            return builder;
        }
    }
}
