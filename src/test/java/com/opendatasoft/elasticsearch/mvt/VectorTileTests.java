package com.opendatasoft.elasticsearch.mvt;

import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.test.ESTestCase;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;

public class VectorTileTests extends ESTestCase {

    public void testEmptyTileHoldsItsLayers() {
        VectorTile tile = new VectorTile(4096);
        tile.addLayer("clusters");
        tile.addLayer("pois");

        Map<String, VectorTileDecoder.Layer> layers = decode(tile);
        assertEquals(List.of("clusters", "pois"), List.copyOf(layers.keySet()));
        for (VectorTileDecoder.Layer layer : layers.values()) {
            assertEquals(2, layer.version());
            assertEquals(4096, layer.extent());
            assertEquals(0, layer.features().size());
        }
    }

    public void testPointFeatureRoundTrip() {
        VectorTile tile = new VectorTile(4096);
        VectorTile.Layer clusters = tile.addLayer("clusters");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("cluster", true);
        properties.put("point_count", 42L);
        properties.put("point_count_abbreviated", "42");
        properties.put("expansion_zoom", 11);
        properties.put("density", 1.5);
        properties.put("ignored", null);
        clusters.addPoint(1234, 2345, 77L, properties);

        VectorTileDecoder.Layer layer = decode(tile).get("clusters");
        assertEquals(1, layer.features().size());
        VectorTileDecoder.Feature feature = layer.features().get(0);

        assertEquals(Long.valueOf(77), feature.id());
        assertEquals(1234, feature.x());
        assertEquals(2345, feature.y());
        assertEquals(Boolean.TRUE, feature.properties().get("cluster"));
        assertEquals(42L, feature.properties().get("point_count"));
        assertEquals("42", feature.properties().get("point_count_abbreviated"));
        // Integers are widened to the vector tile int value type.
        assertEquals(11L, feature.properties().get("expansion_zoom"));
        assertEquals(1.5, (Double) feature.properties().get("density"), 0d);
        assertThat(feature.properties(), not(hasKey("ignored")));
    }

    public void testFeaturesWithoutIdAndWithoutProperties() {
        VectorTile tile = new VectorTile(512);
        VectorTile.Layer pois = tile.addLayer("pois");
        pois.addPoint(0, 0, null, Map.of());
        pois.addPoint(512, 512, null, Map.of());

        VectorTileDecoder.Layer layer = decode(tile).get("pois");
        assertEquals(512, layer.extent());
        assertEquals(2, layer.features().size());
        assertNull(layer.features().get(0).id());
        assertEquals(0, layer.features().get(0).x());
        assertEquals(512, layer.features().get(1).x());
        assertEquals(512, layer.features().get(1).y());
    }

    public void testKeysAndValuesAreShared() {
        VectorTile tile = new VectorTile(4096);
        VectorTile.Layer pois = tile.addLayer("pois");
        for (int i = 0; i < 5; i++) {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("cluster", false);
            properties.put("category", "cafe");
            pois.addPoint(i, i, (long) i, properties);
        }

        BytesReference bytes = tile.toBytesReference();
        VectorTileDecoder.Layer layer = VectorTileDecoder.decode(BytesReference.toBytes(bytes)).get("pois");
        assertEquals(5, layer.features().size());
        for (VectorTileDecoder.Feature feature : layer.features()) {
            assertEquals("cafe", feature.properties().get("category"));
            assertEquals(Boolean.FALSE, feature.properties().get("cluster"));
        }
        // Two keys and two values are enough to describe the five features.
        assertThat(
            layer.features().stream().map(f -> f.properties().keySet()).distinct().toList(),
            contains(Set.of("cluster", "category"))
        );
    }

    public void testUnsupportedValueTypesFallBackToStrings() {
        VectorTile tile = new VectorTile(4096);
        VectorTile.Layer pois = tile.addLayer("pois");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("tags", List.of("a", "b"));
        pois.addPoint(1, 1, null, properties);

        VectorTileDecoder.Feature feature = decode(tile).get("pois").features().get(0);
        assertEquals("[a, b]", feature.properties().get("tags"));
    }

    public void testExtentMustBePositive() {
        IllegalArgumentException error = expectThrows(IllegalArgumentException.class, () -> new VectorTile(0));
        assertTrue(error.getMessage().contains("[mvt_extent]"));
    }

    private static Map<String, VectorTileDecoder.Layer> decode(VectorTile tile) {
        return VectorTileDecoder.decode(BytesReference.toBytes(tile.toBytesReference()));
    }
}
