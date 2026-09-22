package com.opendatasoft.elasticsearch.rest;

import com.opendatasoft.elasticsearch.mvt.VectorTileDecoder;
import com.opendatasoft.elasticsearch.search.aggregations.bucket.geopointclustering.ClusteringTestFixtures;
import com.opendatasoft.elasticsearch.search.aggregations.bucket.geopointclustering.InternalGeoPointClustering;

import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TotalHits;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.document.DocumentField;
import org.elasticsearch.common.lucene.search.TopDocsAndMaxScore;
import org.elasticsearch.geometry.utils.Geohash;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.aggregations.InternalAggregations;
import org.elasticsearch.search.aggregations.metrics.InternalTopHits;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.rest.FakeRestRequest;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;

/**
 * Tests the mapping of a clustering result onto a vector tile: cluster and point features, tile ownership and
 * expansion zoom.
 */
public class RestGeoPointClusteringTileActionTests extends ESTestCase {

    /** Paris, at zoom 9, sits in the tile 259/176. */
    private static final int ZOOM = 9;
    private static final int TILE_X = 259;
    private static final int TILE_Y = 176;

    public void testClustersAndSinglePointsGoToTheirOwnLayer() throws IOException {
        InternalGeoPointClustering.Bucket cluster = ClusteringTestFixtures.bucket(
            12,
            "u09t",
            48.85,
            2.35,
            InternalAggregations.EMPTY,
            "u09w"
        );
        InternalGeoPointClustering.Bucket singlePoint = ClusteringTestFixtures.bucket(
            1,
            "u09v",
            48.86,
            2.4,
            InternalAggregations.from(List.of(topHits("42", "{\"name\":\"a poi\",\"category\":\"cafe\"}"))),
            new String[0]
        );

        Map<String, VectorTileDecoder.Layer> layers = tile(
            Map.of("fields", "name,category", "expansion_zoom", "false"),
            InternalAggregations.from(List.of(ClusteringTestFixtures.clustering("clusters", cluster, singlePoint)))
        );

        VectorTileDecoder.Layer clusters = layers.get("clusters");
        assertEquals(1, clusters.features().size());
        Map<String, Object> properties = clusters.features().get(0).properties();
        assertEquals(Boolean.TRUE, properties.get("cluster"));
        assertEquals(12L, properties.get("point_count"));
        assertEquals("12", properties.get("point_count_abbreviated"));
        assertEquals("u09t,u09w", properties.get("geohash_grids"));
        assertNull("no expansion zoom was asked for", properties.get("expansion_zoom"));
        assertNull("clusters have no document id", clusters.features().get(0).id());

        VectorTileDecoder.Layer pois = layers.get("pois");
        assertEquals(1, pois.features().size());
        VectorTileDecoder.Feature poi = pois.features().get(0);
        assertEquals(Long.valueOf(42), poi.id());
        assertEquals(Boolean.FALSE, poi.properties().get("cluster"));
        assertEquals("42", poi.properties().get("id"));
        assertEquals("a poi", poi.properties().get("name"));
        assertEquals("cafe", poi.properties().get("category"));
    }

    public void testClustersOutsideOfTheTileAreDropped() throws IOException {
        // The aggregation runs on a buffered area: a cluster centered on the neighbouring tile is rendered there.
        InternalGeoPointClustering.Bucket inside = ClusteringTestFixtures.bucket(2, "u09t", 48.85, 2.35, InternalAggregations.EMPTY);
        InternalGeoPointClustering.Bucket outside = ClusteringTestFixtures.bucket(3, "u09m", 48.85, 3.2, InternalAggregations.EMPTY);

        Map<String, VectorTileDecoder.Layer> layers = tile(
            Map.of("expansion_zoom", "false"),
            InternalAggregations.from(List.of(ClusteringTestFixtures.clustering("clusters", inside, outside)))
        );

        assertEquals(1, layers.get("clusters").features().size());
        assertEquals(2L, layers.get("clusters").features().get(0).properties().get("point_count"));
    }

    public void testExpansionZoomIsAddedToClusters() throws IOException {
        InternalGeoPointClustering.Bucket cluster = ClusteringTestFixtures.bucket(9, "u09t", 48.85, 2.35, InternalAggregations.EMPTY);

        Map<String, VectorTileDecoder.Layer> layers = tile(
            Map.of("expansion_zoom_depth", "2"),
            InternalAggregations.from(
                List.of(
                    ClusteringTestFixtures.clustering("clusters", cluster),
                    // Still one cluster at zoom 10, two at zoom 11.
                    ClusteringTestFixtures.clustering("expansion_10", ClusteringTestFixtures.bucket(9, "u09tx")),
                    ClusteringTestFixtures.clustering(
                        "expansion_11",
                        ClusteringTestFixtures.bucket(5, "u09tx"),
                        ClusteringTestFixtures.bucket(4, "u09tz")
                    )
                )
            )
        );

        assertEquals(11L, layers.get("clusters").features().get(0).properties().get("expansion_zoom"));
    }

    public void testPointCountAbbreviation() throws IOException {
        InternalGeoPointClustering.Bucket small = ClusteringTestFixtures.bucket(999, "u09t", 48.85, 2.35, InternalAggregations.EMPTY);
        InternalGeoPointClustering.Bucket thousands = ClusteringTestFixtures.bucket(1240, "u09v", 48.86, 2.36, InternalAggregations.EMPTY);
        InternalGeoPointClustering.Bucket many = ClusteringTestFixtures.bucket(23500, "u09w", 48.87, 2.37, InternalAggregations.EMPTY);

        Map<String, VectorTileDecoder.Layer> layers = tile(
            Map.of("expansion_zoom", "false"),
            InternalAggregations.from(List.of(ClusteringTestFixtures.clustering("clusters", small, thousands, many)))
        );

        List<VectorTileDecoder.Feature> features = layers.get("clusters").features();
        assertEquals("999", features.get(0).properties().get("point_count_abbreviated"));
        assertEquals("1.2k", features.get(1).properties().get("point_count_abbreviated"));
        assertEquals("24k", features.get(2).properties().get("point_count_abbreviated"));
    }

    public void testRawTileRendersDocumentsAsPoints() throws IOException {
        SearchHit inside = SearchHit.unpooled(0, "1");
        inside.setDocumentField("point", new DocumentField("point", List.of("48.85,2.35")));
        inside.sourceRef(new BytesArray("{\"name\":\"a poi\"}"));

        SearchHit outside = SearchHit.unpooled(1, "2");
        outside.setDocumentField("point", new DocumentField("point", List.of("48.85,3.2")));
        outside.sourceRef(new BytesArray("{\"name\":\"another poi\"}"));

        SearchHits hits = SearchHits.unpooled(new SearchHit[] { inside, outside }, new TotalHits(2, TotalHits.Relation.EQUAL_TO), 1f);

        GeoPointClusteringTileRequest request = request(Map.of("cluster_max_zoom", "8", "fields", "name"));
        Map<String, VectorTileDecoder.Layer> layers = decode(
            RestGeoPointClusteringTileAction.buildTile(request, InternalAggregations.EMPTY, hits)
        );

        assertEquals(0, layers.get("clusters").features().size());
        assertEquals(1, layers.get("pois").features().size());
        VectorTileDecoder.Feature poi = layers.get("pois").features().get(0);
        assertEquals(Long.valueOf(1), poi.id());
        assertEquals("a poi", poi.properties().get("name"));
    }

    public void testCustomLayerNamesAndExtent() throws IOException {
        InternalGeoPointClustering.Bucket cluster = ClusteringTestFixtures.bucket(4, "u09t", 48.85, 2.35, InternalAggregations.EMPTY);
        Map<String, VectorTileDecoder.Layer> layers = tile(
            Map.of("clusters_layer", "amas", "pois_layer", "points", "mvt_extent", "512", "expansion_zoom", "false"),
            InternalAggregations.from(List.of(ClusteringTestFixtures.clustering("clusters", cluster)))
        );

        assertEquals(List.of("amas", "points"), List.copyOf(layers.keySet()));
        assertEquals(512, layers.get("amas").extent());
        assertThat(layers.get("amas").features().get(0).x(), org.hamcrest.Matchers.lessThanOrEqualTo(512));
    }

    public void testNonNumericDocumentIdsAreExposedAsAProperty() throws IOException {
        InternalGeoPointClustering.Bucket singlePoint = ClusteringTestFixtures.bucket(
            1,
            "u09v",
            48.86,
            2.4,
            InternalAggregations.from(List.of(topHits("abc-1", "{}")))
        );

        Map<String, VectorTileDecoder.Layer> layers = tile(
            Map.of("expansion_zoom", "false"),
            InternalAggregations.from(List.of(ClusteringTestFixtures.clustering("clusters", singlePoint)))
        );

        VectorTileDecoder.Feature poi = layers.get("pois").features().get(0);
        assertNull(poi.id());
        assertEquals("abc-1", poi.properties().get("id"));
    }

    public void testGeohashGridsPropertyFeedsTheExpansionEndpoint() throws IOException {
        InternalGeoPointClustering.Bucket cluster = ClusteringTestFixtures.bucket(
            7,
            "u09t",
            48.85,
            2.35,
            InternalAggregations.EMPTY,
            "u09w",
            "u09v"
        );
        Map<String, VectorTileDecoder.Layer> layers = tile(
            Map.of("expansion_zoom", "false"),
            InternalAggregations.from(List.of(ClusteringTestFixtures.clustering("clusters", cluster)))
        );

        String cells = (String) layers.get("clusters").features().get(0).properties().get("geohash_grids");
        assertThat(cells, containsString(","));
        for (String cell : cells.split(",")) {
            assertEquals(cell, Geohash.stringEncode(Geohash.longEncode(cell)));
        }
    }

    private Map<String, VectorTileDecoder.Layer> tile(Map<String, String> parameters, InternalAggregations aggregations)
        throws IOException {
        return decode(
            RestGeoPointClusteringTileAction.buildTile(
                request(parameters),
                aggregations,
                SearchHits.empty(new TotalHits(0, TotalHits.Relation.EQUAL_TO), 0f)
            )
        );
    }

    private static Map<String, VectorTileDecoder.Layer> decode(BytesReference tile) {
        return VectorTileDecoder.decode(BytesReference.toBytes(tile));
    }

    private GeoPointClusteringTileRequest request(Map<String, String> parameters) throws IOException {
        Map<String, String> params = new HashMap<>(
            Map.of(
                "index",
                "test",
                "field",
                "point",
                "z",
                Integer.toString(ZOOM),
                "x",
                Integer.toString(TILE_X),
                "y",
                Integer.toString(TILE_Y)
            )
        );
        params.putAll(parameters);
        return GeoPointClusteringTileRequest.parse(
            new FakeRestRequest.Builder(xContentRegistry()).withMethod(RestRequest.Method.GET).withParams(params).build()
        );
    }

    private static InternalTopHits topHits(String id, String source) {
        SearchHit hit = SearchHit.unpooled(0, id);
        hit.sourceRef(new BytesArray(source));
        SearchHits hits = SearchHits.unpooled(new SearchHit[] { hit }, new TotalHits(1, TotalHits.Relation.EQUAL_TO), 1f);
        TopDocsAndMaxScore topDocs = new TopDocsAndMaxScore(
            new TopDocs(new TotalHits(1, TotalHits.Relation.EQUAL_TO), new ScoreDoc[] { new ScoreDoc(0, 1f) }),
            1f
        );
        return new InternalTopHits(GeoPointClusteringTileRequest.LEAF_AGG, 0, 1, topDocs, hits, null);
    }
}
