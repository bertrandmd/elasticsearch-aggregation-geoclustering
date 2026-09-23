package com.opendatasoft.elasticsearch;

import com.opendatasoft.elasticsearch.mvt.VectorTileDecoder;

import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.test.rest.ESRestTestCase;
import org.junit.Before;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

/**
 * Integration tests of the clustering APIs: the vector tile endpoint and the cluster expansion zoom endpoint.
 * <p>
 * All the documents sit in Paris, which at zoom 9 belongs to the tile 259/176.
 */
public class GeoPointClusteringApiIT extends ESRestTestCase {

    private static final String MVT_CONTENT_TYPE = "application/vnd.mapbox-vector-tile";
    private static final String TILE_PATH = "/test/_geo_point_clustering/_mvt/point/9/259/176";

    private static final double[][] POINTS = {
        { 2.454929, 48.821578 },
        { 2.245858, 48.86914 },
        { 2.240358, 48.863481 },
        { 2.25292, 48.847176 },
        { 2.279111, 48.872383 },
        { 2.336267, 48.822021 },
        { 2.338677, 48.822672 },
        { 2.336643, 48.822493 },
        { 2.438465, 48.84204 },
        { 2.381554, 48.835382 },
        { 2.407744, 48.83733 },
        { 2.34521, 48.849358 },
        { 2.252938, 48.846041 },
        { 2.279715, 48.871775 },
        { 2.380629, 48.879757 } };

    @Before
    public void indexPoints() throws IOException {
        Request create = new Request("PUT", "/test");
        create.setJsonEntity("""
            {
              "settings": { "number_of_shards": 3 },
              "mappings": {
                "properties": {
                  "point": { "type": "geo_point" },
                  "name": { "type": "text" },
                  "category": { "type": "keyword" }
                }
              }
            }""");
        client().performRequest(create);

        StringBuilder bulk = new StringBuilder();
        for (int i = 0; i < POINTS.length; i++) {
            int id = i + 1;
            String category = id <= 5 ? "cafe" : "museum";
            bulk.append("{\"index\":{\"_id\":\"").append(id).append("\"}}\n");
            bulk.append("{\"point\":[")
                .append(POINTS[i][0])
                .append(",")
                .append(POINTS[i][1])
                .append("],\"name\":\"poi ")
                .append(id)
                .append("\",\"category\":\"")
                .append(category)
                .append("\"}\n");
        }
        Request bulkRequest = new Request("POST", "/test/_bulk");
        bulkRequest.addParameter("refresh", "true");
        bulkRequest.setJsonEntity(bulk.toString());
        client().performRequest(bulkRequest);
    }

    public void testClusteredTile() throws IOException {
        Request request = new Request("GET", TILE_PATH);
        request.addParameter("fields", "name,category");
        Response response = client().performRequest(request);

        assertEquals(200, response.getStatusLine().getStatusCode());
        assertEquals(MVT_CONTENT_TYPE, response.getEntity().getContentType().getValue());

        Map<String, VectorTileDecoder.Layer> layers = decode(response);
        assertEquals(Set.of("clusters", "pois"), layers.keySet());

        VectorTileDecoder.Layer clusters = layers.get("clusters");
        assertEquals(2, clusters.version());
        assertEquals(4096, clusters.extent());
        assertEquals(2, clusters.features().size());
        assertEquals(0, layers.get("pois").features().size());

        long total = 0;
        for (VectorTileDecoder.Feature cluster : clusters.features()) {
            assertEquals(Boolean.TRUE, cluster.properties().get("cluster"));
            long pointCount = (Long) cluster.properties().get("point_count");
            assertEquals(Long.toString(pointCount), cluster.properties().get("point_count_abbreviated"));
            assertThat((String) cluster.properties().get("geohash_grids"), not(emptyString()));

            long expansionZoom = (Long) cluster.properties().get("expansion_zoom");
            assertThat(expansionZoom, greaterThan(9L));
            assertThat(expansionZoom, lessThanOrEqualTo(13L));

            assertThat(cluster.x(), allOf(greaterThanOrEqualTo(0), lessThanOrEqualTo(4096)));
            assertThat(cluster.y(), allOf(greaterThanOrEqualTo(0), lessThanOrEqualTo(4096)));
            total += pointCount;
        }
        assertEquals(POINTS.length, total);
    }

    public void testClusteredTileIsFiltered() throws IOException {
        Request request = new Request("GET", TILE_PATH);
        request.addParameter("q", "category:cafe");
        request.addParameter("fields", "name");
        Map<String, VectorTileDecoder.Layer> layers = decode(client().performRequest(request));

        long total = layers.get("pois").features().size();
        for (VectorTileDecoder.Feature cluster : layers.get("clusters").features()) {
            total += (Long) cluster.properties().get("point_count");
        }
        assertEquals(5, total);
    }

    public void testSinglePointClusterIsReturnedAsPoi() throws IOException {
        // A clustered zoom where the first point stands alone in its tile: it is rendered as a point, not as a
        // cluster of one, and carries the requested source fields.
        Request request = new Request("GET", "/test/_geo_point_clustering/_mvt/point/16/33214/22555");
        request.addParameter("fields", "name,category");
        Map<String, VectorTileDecoder.Layer> layers = decode(client().performRequest(request));

        assertEquals(0, layers.get("clusters").features().size());
        VectorTileDecoder.Layer pois = layers.get("pois");
        assertEquals(1, pois.features().size());
        VectorTileDecoder.Feature poi = pois.features().get(0);
        assertEquals(Boolean.FALSE, poi.properties().get("cluster"));
        assertEquals(Long.valueOf(1), poi.id());
        assertEquals("1", poi.properties().get("id"));
        assertEquals("poi 1", poi.properties().get("name"));
        assertEquals("cafe", poi.properties().get("category"));
    }

    public void testRawTileAboveClusterMaxZoom() throws IOException {
        Request request = new Request("GET", TILE_PATH);
        request.addParameter("cluster_max_zoom", "8");
        request.addParameter("fields", "name");
        Map<String, VectorTileDecoder.Layer> layers = decode(client().performRequest(request));

        assertEquals(0, layers.get("clusters").features().size());
        VectorTileDecoder.Layer pois = layers.get("pois");
        assertEquals(POINTS.length, pois.features().size());
        for (VectorTileDecoder.Feature poi : pois.features()) {
            assertNotNull(poi.id());
            assertThat(poi.id(), allOf(greaterThanOrEqualTo(1L), lessThanOrEqualTo((long) POINTS.length)));
            assertThat((String) poi.properties().get("name"), startsWith("poi "));
        }
    }

    public void testClusteringCanBeTurnedOff() throws IOException {
        // A zoom that would normally cluster: with cluster=false every document comes back as a point.
        Request request = new Request("GET", TILE_PATH);
        request.addParameter("cluster", "false");
        request.addParameter("fields", "name");
        Map<String, VectorTileDecoder.Layer> layers = decode(client().performRequest(request));

        assertEquals(0, layers.get("clusters").features().size());
        assertEquals(POINTS.length, layers.get("pois").features().size());
    }

    public void testExpansionZoomEndpoint() throws IOException {
        Request tileRequest = new Request("GET", TILE_PATH);
        Map<String, VectorTileDecoder.Layer> layers = decode(client().performRequest(tileRequest));
        VectorTileDecoder.Feature cluster = layers.get("clusters").features().get(0);
        long pointCount = (Long) cluster.properties().get("point_count");

        Request expansion = new Request("GET", "/test/_geo_point_clustering/_expansion/point");
        expansion.addParameter("zoom", "9");
        expansion.addParameter("cells", (String) cluster.properties().get("geohash_grids"));
        Map<String, Object> body = entityAsMap(client().performRequest(expansion));

        assertThat((Integer) body.get("zoom"), greaterThan(9));
        assertThat((Integer) body.get("bucket_count"), greaterThanOrEqualTo(2));
        assertEquals(pointCount, ((Number) body.get("doc_count")).longValue());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> clusters = (List<Map<String, Object>>) body.get("clusters");
        assertEquals(((Integer) body.get("bucket_count")).intValue(), clusters.size());
        assertNotNull(clusters.get(0).get("centroid"));
    }

    public void testInvalidTileCoordinates() {
        ResponseException zoomError = expectThrows(
            ResponseException.class,
            () -> client().performRequest(new Request("GET", "/test/_geo_point_clustering/_mvt/point/26/0/0"))
        );
        assertEquals(400, zoomError.getResponse().getStatusLine().getStatusCode());

        ResponseException tileError = expectThrows(
            ResponseException.class,
            () -> client().performRequest(new Request("GET", "/test/_geo_point_clustering/_mvt/point/2/9/0"))
        );
        assertEquals(400, tileError.getResponse().getStatusLine().getStatusCode());
    }

    public void testExpansionRequiresCells() {
        Request request = new Request("GET", "/test/_geo_point_clustering/_expansion/point");
        request.addParameter("zoom", "9");
        ResponseException error = expectThrows(ResponseException.class, () -> client().performRequest(request));
        assertEquals(400, error.getResponse().getStatusLine().getStatusCode());
    }

    private static Map<String, VectorTileDecoder.Layer> decode(Response response) throws IOException {
        return VectorTileDecoder.decode(EntityUtils.toByteArray(response.getEntity()));
    }
}
