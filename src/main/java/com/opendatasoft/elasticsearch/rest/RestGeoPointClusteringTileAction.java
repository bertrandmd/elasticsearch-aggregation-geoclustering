package com.opendatasoft.elasticsearch.rest;

import com.opendatasoft.elasticsearch.mvt.VectorTile;
import com.opendatasoft.elasticsearch.mvt.WebMercator;
import com.opendatasoft.elasticsearch.search.aggregations.bucket.geopointclustering.InternalGeoPointClustering;

import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.internal.node.NodeClient;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.document.DocumentField;
import org.elasticsearch.common.geo.GeoPoint;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestResponse;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.rest.action.RestResponseListener;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.aggregations.InternalAggregations;
import org.elasticsearch.search.aggregations.metrics.InternalTopHits;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.rest.RestRequest.Method.GET;
import static org.elasticsearch.rest.RestRequest.Method.POST;

/**
 * Serves clustered Mapbox Vector Tiles, ready to be consumed by a map client.
 * <p>
 * A tile holds two layers:
 * <ul>
 *     <li>{@code clusters}: one point per cluster, carrying its {@code point_count} and its {@code expansion_zoom}</li>
 *     <li>{@code pois}: the documents that are alone in their cluster, carrying the requested source fields</li>
 * </ul>
 * Above {@code cluster_max_zoom}, clustering is turned off and all the documents of the tile go to the {@code pois}
 * layer.
 */
public class RestGeoPointClusteringTileAction extends BaseRestHandler {

    public static final String MVT_CONTENT_TYPE = "application/vnd.mapbox-vector-tile";

    static final String PATH = "/{index}/_geo_point_clustering/_mvt/{field}/{z}/{x}/{y}";

    @Override
    public String getName() {
        return "geo_point_clustering_mvt_action";
    }

    @Override
    public List<Route> routes() {
        return List.of(new Route(GET, PATH), new Route(POST, PATH));
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest restRequest, NodeClient client) throws IOException {
        GeoPointClusteringTileRequest request = GeoPointClusteringTileRequest.parse(restRequest);
        return channel -> client.search(request.toSearchRequest(), new RestResponseListener<>(channel) {
            @Override
            public RestResponse buildResponse(SearchResponse searchResponse) {
                BytesReference tile = buildTile(request, searchResponse.getAggregations(), searchResponse.getHits());
                return new RestResponse(RestStatus.OK, MVT_CONTENT_TYPE, tile);
            }
        });
    }

    static BytesReference buildTile(GeoPointClusteringTileRequest request, InternalAggregations aggregations, SearchHits hits) {
        VectorTile tile = new VectorTile(request.mvtExtent);
        VectorTile.Layer clustersLayer = tile.addLayer(request.clustersLayer);
        VectorTile.Layer poisLayer = tile.addLayer(request.poisLayer);

        if (request.isClustered()) {
            addClusteredFeatures(request, aggregations, clustersLayer, poisLayer);
        } else {
            addRawFeatures(request, hits, poisLayer);
        }

        return tile.toBytesReference();
    }

    private static void addClusteredFeatures(
        GeoPointClusteringTileRequest request,
        InternalAggregations aggregations,
        VectorTile.Layer clustersLayer,
        VectorTile.Layer poisLayer
    ) {
        InternalGeoPointClustering clustering = aggregations == null ? null : aggregations.get(GeoPointClusteringTileRequest.CLUSTERS_AGG);
        if (clustering == null) {
            return;
        }

        // The aggregation runs on a buffered area, so that clusters are not cut by the tile borders. Each cluster is
        // then rendered by the single tile owning its centroid, which keeps neighbouring tiles free of duplicates.
        List<InternalGeoPointClustering.Bucket> ownedClusters = new ArrayList<>();
        for (InternalGeoPointClustering.Bucket cluster : clustering.getBuckets()) {
            GeoPoint centroid = cluster.getCentroid();
            if (WebMercator.insideTile(centroid.getLon(), centroid.getLat(), request.zoom, request.tileX, request.tileY)) {
                ownedClusters.add(cluster);
            }
        }

        int[] expansionZooms = request.expansionZooms();
        Map<Long, Integer> expansionZoomByCluster = ExpansionZoomResolver.resolve(
            ownedClusters,
            expansionZooms,
            expansionLevels(aggregations, expansionZooms)
        );

        for (InternalGeoPointClustering.Bucket cluster : ownedClusters) {
            GeoPoint centroid = cluster.getCentroid();
            int[] coordinates = tileCoordinates(request, centroid.getLon(), centroid.getLat());

            if (cluster.getDocCount() == 1) {
                InternalTopHits leaf = cluster.getAggregations().get(GeoPointClusteringTileRequest.LEAF_AGG);
                SearchHit hit = leaf == null || leaf.getHits().getHits().length == 0 ? null : leaf.getHits().getHits()[0];
                poisLayer.addPoint(coordinates[0], coordinates[1], featureId(request, hit), poiProperties(request, hit));
                continue;
            }

            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("cluster", true);
            properties.put("point_count", cluster.getDocCount());
            properties.put("point_count_abbreviated", abbreviate(cluster.getDocCount()));
            properties.put("geohash_grids", String.join(",", cluster.getGeohashGrids()));
            Integer expansionZoom = expansionZoomByCluster.get(cluster.hashAsLong());
            if (expansionZoom != null) {
                properties.put("expansion_zoom", expansionZoom);
            }
            clustersLayer.addPoint(coordinates[0], coordinates[1], null, properties);
        }
    }

    private static void addRawFeatures(GeoPointClusteringTileRequest request, SearchHits hits, VectorTile.Layer poisLayer) {
        for (SearchHit hit : hits) {
            GeoPoint location = location(hit, request.field);
            if (location == null) {
                continue;
            }
            // The bounding box query includes the tile borders, keep the points this tile owns only.
            if (WebMercator.insideTile(location.getLon(), location.getLat(), request.zoom, request.tileX, request.tileY) == false) {
                continue;
            }
            int[] coordinates = tileCoordinates(request, location.getLon(), location.getLat());
            poisLayer.addPoint(coordinates[0], coordinates[1], featureId(request, hit), poiProperties(request, hit));
        }
    }

    private static List<InternalGeoPointClustering> expansionLevels(InternalAggregations aggregations, int[] expansionZooms) {
        List<InternalGeoPointClustering> levels = new ArrayList<>(expansionZooms.length);
        for (int expansionZoom : expansionZooms) {
            levels.add(aggregations.get(GeoPointClusteringTileRequest.EXPANSION_AGG_PREFIX + expansionZoom));
        }
        return levels;
    }

    private static Map<String, Object> poiProperties(GeoPointClusteringTileRequest request, SearchHit hit) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("cluster", false);
        if (hit == null) {
            return properties;
        }
        if (request.includeId) {
            properties.put("id", hit.getId());
        }
        Map<String, Object> source = hit.getSourceAsMap();
        if (source != null) {
            for (String field : request.fields) {
                Object value = XContentMapValues.extractValue(field, source);
                if (value != null) {
                    properties.put(field, value);
                }
            }
        }
        return properties;
    }

    /**
     * Vector tile feature ids are unsigned integers, so only numeric document ids can be carried over. Other ids are
     * exposed as the {@code id} property.
     */
    private static Long featureId(GeoPointClusteringTileRequest request, SearchHit hit) {
        if (hit == null || request.includeId == false) {
            return null;
        }
        try {
            long id = Long.parseLong(hit.getId());
            return id < 0 ? null : id;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static GeoPoint location(SearchHit hit, String field) {
        DocumentField documentField = hit.field(field);
        if (documentField == null || documentField.getValues().isEmpty()) {
            return null;
        }
        Object value = documentField.getValues().get(0);
        return value == null ? null : new GeoPoint(value.toString());
    }

    private static int[] tileCoordinates(GeoPointClusteringTileRequest request, double lon, double lat) {
        return WebMercator.tileCoordinates(lon, lat, request.zoom, request.tileX, request.tileY, request.mvtExtent);
    }

    /** Same abbreviation rules as supercluster, so that map styles written for it can be reused as is. */
    private static String abbreviate(long count) {
        if (count >= 10000) {
            return Math.round(count / 1000d) + "k";
        }
        if (count >= 1000) {
            return (Math.round(count / 100d) / 10d) + "k";
        }
        return Long.toString(count);
    }
}
