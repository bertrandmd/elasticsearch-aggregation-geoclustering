package com.opendatasoft.elasticsearch.rest;

import com.opendatasoft.elasticsearch.search.aggregations.bucket.geopointclustering.GeoPointClusteringAggregationBuilder;

import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.GeoBoundingBoxQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryStringQueryBuilder;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.search.SearchModule;
import org.elasticsearch.search.aggregations.AggregationBuilder;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.rest.FakeRestRequest;
import org.elasticsearch.xcontent.NamedXContentRegistry;
import org.elasticsearch.xcontent.XContentType;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.instanceOf;

public class GeoPointClusteringTileRequestTests extends ESTestCase {

    @Override
    protected NamedXContentRegistry xContentRegistry() {
        return new NamedXContentRegistry(new SearchModule(Settings.EMPTY, List.of()).getNamedXContents());
    }

    public void testDefaults() throws IOException {
        GeoPointClusteringTileRequest request = parse(Map.of());

        assertArrayEquals(new String[] { "test" }, request.indices);
        assertEquals("point", request.field);
        assertEquals(9, request.zoom);
        assertEquals(259, request.tileX);
        assertEquals(176, request.tileY);
        assertEquals(GeoPointClusteringAggregationBuilder.DEFAULT_RADIUS, request.radius);
        assertEquals(GeoPointClusteringAggregationBuilder.DEFAULT_EXTENT, request.extent);
        assertEquals(16, request.clusterMaxZoom);
        assertEquals(4096, request.mvtExtent);
        assertTrue(request.isClustered());
        assertArrayEquals(new int[] { 10, 11, 12, 13 }, request.expansionZooms());
    }

    public void testClusteredSearchRequest() throws IOException {
        GeoPointClusteringTileRequest request = parse(Map.of("fields", "name,category", "radius", "60", "extent", "512"));
        SearchSourceBuilder source = request.toSearchRequest().source();

        assertEquals(0, source.size());
        assertThat(aggregationNames(source), contains("clusters", "expansion_10", "expansion_11", "expansion_12", "expansion_13"));

        GeoPointClusteringAggregationBuilder clusters = aggregation(source, "clusters");
        assertEquals(9, clusters.zoom());
        assertEquals(
            Set.of("leaf", "bounds"),
            clusters.getSubAggregations().stream().map(AggregationBuilder::getName).collect(Collectors.toSet())
        );
        assertEquals(11, aggregation(source, "expansion_11").zoom());

        GeoBoundingBoxQueryBuilder boundingBox = boundingBox(source);
        assertEquals("point", boundingBox.fieldName());
        // The queried area is buffered by 2 * radius pixels, it is wider than the tile itself.
        assertTrue(boundingBox.topLeft().getLon() < -180 + 360.0 * 259 / 512);
        assertTrue(boundingBox.bottomRight().getLon() > -180 + 360.0 * 260 / 512);
    }

    public void testRawSearchRequestAboveClusterMaxZoom() throws IOException {
        GeoPointClusteringTileRequest request = parse(Map.of("cluster_max_zoom", "8", "max_hits", "500", "fields", "name"));
        assertFalse(request.isClustered());
        assertEquals(0, request.expansionZooms().length);

        SearchSourceBuilder source = request.toSearchRequest().source();
        assertEquals(500, source.size());
        assertNull(source.aggregations());
        assertEquals(1, source.docValueFields().size());
        assertEquals("point", source.docValueFields().get(0).field);
        assertArrayEquals(new String[] { "name" }, source.fetchSource().includes());
        assertEquals(1, source.sorts().size());

        // A raw tile is not buffered, its bounding box is the tile itself.
        GeoBoundingBoxQueryBuilder boundingBox = boundingBox(source);
        assertEquals(-180 + 360.0 * 259 / 512, boundingBox.topLeft().getLon(), 1e-9);
        assertEquals(-180 + 360.0 * 260 / 512, boundingBox.bottomRight().getLon(), 1e-9);
    }

    public void testClusteringCanBeTurnedOffAltogether() throws IOException {
        GeoPointClusteringTileRequest request = parse(Map.of("cluster", "false", "max_hits", "2000", "fields", "name"));
        assertFalse(request.isClustered());
        assertEquals(0, request.expansionZooms().length);

        // Same request as above cluster_max_zoom: every document of the tile, as points.
        SearchSourceBuilder source = request.toSearchRequest().source();
        assertNull(source.aggregations());
        assertEquals(2000, source.size());
        assertEquals("point", source.docValueFields().get(0).field);

        // The queried area is the tile itself, clusters are what needs a buffered one.
        GeoBoundingBoxQueryBuilder boundingBox = boundingBox(source);
        assertEquals(-180 + 360.0 * 259 / 512, boundingBox.topLeft().getLon(), 1e-9);
        assertEquals(-180 + 360.0 * 260 / 512, boundingBox.bottomRight().getLon(), 1e-9);
    }

    public void testQueryStringParameter() throws IOException {
        GeoPointClusteringTileRequest request = parse(Map.of("q", "category:cafe"));
        BoolQueryBuilder query = (BoolQueryBuilder) request.toSearchRequest().source().query();

        assertEquals(2, query.filter().size());
        assertThat(query.filter().get(0), instanceOf(QueryStringQueryBuilder.class));
        assertEquals("category:cafe", ((QueryStringQueryBuilder) query.filter().get(0)).queryString());
    }

    public void testBodyOverridesQueryStringParameters() throws IOException {
        GeoPointClusteringTileRequest request = parse(Map.of("radius", "60", "expansion_zoom_depth", "4"), """
            {
              "radius": 80,
              "expansion_zoom_depth": 1,
              "fields": ["name"],
              "include_id": false,
              "clusters_layer": "amas",
              "query": { "term": { "category": "cafe" } }
            }""");

        assertEquals(80, request.radius);
        assertEquals("amas", request.clustersLayer);
        assertEquals(List.of("name"), request.fields);
        assertFalse(request.includeId);
        assertArrayEquals(new int[] { 10 }, request.expansionZooms());

        BoolQueryBuilder query = (BoolQueryBuilder) request.toSearchRequest().source().query();
        assertThat(
            query.filter().stream().map(QueryBuilder::getName).collect(Collectors.toList()),
            containsInAnyOrder("term", "geo_bounding_box")
        );
    }

    public void testExpansionZoomIsCappedByClusterMaxZoom() throws IOException {
        GeoPointClusteringTileRequest request = parse(Map.of("cluster_max_zoom", "10"));
        assertArrayEquals(new int[] { 10, 11 }, request.expansionZooms());

        assertEquals(0, parse(Map.of("expansion_zoom", "false")).expansionZooms().length);
        assertEquals(0, parse(Map.of("expansion_zoom_depth", "0")).expansionZooms().length);
    }

    public void testExpansionLevelsShareABucketBudget() throws IOException {
        // Deeper levels hold exponentially more clusters: they share the budget of one level instead of each being
        // allowed `size` buckets, which keeps a tile clear of search.max_buckets whatever the depth.
        GeoPointClusteringTileRequest deep = parse(Map.of("size", "10000", "expansion_zoom_depth", "8"));
        assertEquals(8, deep.expansionZooms().length);
        assertEquals(1250, deep.expansionSize());

        GeoPointClusteringTileRequest shallow = parse(Map.of("size", "10000", "expansion_zoom_depth", "1"));
        assertEquals(10000, shallow.expansionSize());

        SearchSourceBuilder source = deep.toSearchRequest().source();
        assertEquals(10000, aggregation(source, "clusters").size());
        assertEquals(1250, aggregation(source, "expansion_17").size());
    }

    public void testSubAggregationsAreSkippedWhenNothingNeedsThem() throws IOException {
        GeoPointClusteringTileRequest request = parse(Map.of("include_id", "false", "expansion_zoom", "false"));
        GeoPointClusteringAggregationBuilder clusters = aggregation(request.toSearchRequest().source(), "clusters");
        assertThat(clusters.getSubAggregations(), empty());

        // The bounds are only read to answer the expansion zoom.
        GeoPointClusteringTileRequest withoutExpansion = parse(Map.of("expansion_zoom", "false"));
        assertEquals(
            List.of("leaf"),
            aggregation(withoutExpansion.toSearchRequest().source(), "clusters").getSubAggregations()
                .stream()
                .map(AggregationBuilder::getName)
                .toList()
        );
    }

    public void testPreferenceAndRoutingArePassedThrough() throws IOException {
        SearchRequest searchRequest = parse(Map.of("preference", "_local", "routing", "paris")).toSearchRequest();
        assertEquals("_local", searchRequest.preference());
        assertEquals("paris", searchRequest.routing());
    }

    public void testInvalidParameters() {
        assertFailure(Map.of("z", "26"), "Must be between 0 and 25");
        assertFailure(Map.of("x", "512"), "[x] and [y] must be between 0 and 511");
        assertFailure(Map.of("radius", "0"), "[radius] must be greater than 0");
        assertFailure(Map.of("mvt_extent", "0"), "[mvt_extent] must be between 1 and 16384");
        assertFailure(Map.of("expansion_zoom_depth", "9"), "[expansion_zoom_depth] must be between 0 and 8");
        assertFailure(Map.of("pois_layer", "clusters"), "[clusters_layer] and [pois_layer] must be different");
    }

    private void assertFailure(Map<String, String> parameters, String expectedMessage) {
        IllegalArgumentException error = expectThrows(IllegalArgumentException.class, () -> parse(parameters));
        assertThat(error.getMessage(), containsString(expectedMessage));
    }

    private GeoPointClusteringTileRequest parse(Map<String, String> parameters) throws IOException {
        return parse(parameters, null);
    }

    private GeoPointClusteringTileRequest parse(Map<String, String> parameters, String body) throws IOException {
        Map<String, String> params = new HashMap<>(Map.of("index", "test", "field", "point", "z", "9", "x", "259", "y", "176"));
        params.putAll(parameters);

        FakeRestRequest.Builder builder = new FakeRestRequest.Builder(xContentRegistry()).withMethod(RestRequest.Method.GET)
            .withPath("/test/_geo_point_clustering/_mvt/point/9/259/176")
            .withParams(params);
        if (body != null) {
            builder.withContent(new BytesArray(body), XContentType.JSON);
        }
        return GeoPointClusteringTileRequest.parse(builder.build());
    }

    private static List<String> aggregationNames(SearchSourceBuilder source) {
        return source.aggregations().getAggregatorFactories().stream().map(AggregationBuilder::getName).collect(Collectors.toList());
    }

    private static GeoPointClusteringAggregationBuilder aggregation(SearchSourceBuilder source, String name) {
        return source.aggregations()
            .getAggregatorFactories()
            .stream()
            .filter(aggregation -> aggregation.getName().equals(name))
            .map(GeoPointClusteringAggregationBuilder.class::cast)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no [" + name + "] aggregation"));
    }

    private static GeoBoundingBoxQueryBuilder boundingBox(SearchSourceBuilder source) {
        BoolQueryBuilder query = (BoolQueryBuilder) source.query();
        return query.filter()
            .stream()
            .filter(GeoBoundingBoxQueryBuilder.class::isInstance)
            .map(GeoBoundingBoxQueryBuilder.class::cast)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no geo_bounding_box filter"));
    }
}
