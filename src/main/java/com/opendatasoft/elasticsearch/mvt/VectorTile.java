package com.opendatasoft.elasticsearch.mvt;

import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.bytes.BytesReference;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a <a href="https://github.com/mapbox/vector-tile-spec/tree/master/2.1">Mapbox Vector Tile</a> (version 2.1)
 * containing point layers.
 * <p>
 * Only point geometries are supported, which is all the clustering API needs: clusters and single points are both
 * rendered as points by the map client.
 */
public final class VectorTile {

    /** Tile.layers field number. */
    private static final int TILE_LAYERS = 3;

    /** Layer field numbers. */
    private static final int LAYER_NAME = 1;
    private static final int LAYER_FEATURES = 2;
    private static final int LAYER_KEYS = 3;
    private static final int LAYER_VALUES = 4;
    private static final int LAYER_EXTENT = 5;
    private static final int LAYER_VERSION = 15;

    /** Feature field numbers. */
    private static final int FEATURE_ID = 1;
    private static final int FEATURE_TAGS = 2;
    private static final int FEATURE_TYPE = 3;
    private static final int FEATURE_GEOMETRY = 4;

    /** Value field numbers. */
    private static final int VALUE_STRING = 1;
    private static final int VALUE_DOUBLE = 3;
    private static final int VALUE_INT = 4;
    private static final int VALUE_BOOL = 7;

    private static final int SPEC_VERSION = 2;
    private static final int GEOMETRY_TYPE_POINT = 1;

    /** MoveTo command with a repeat count of 1, see the vector tile geometry encoding. */
    private static final int MOVE_TO_COMMAND = (1 & 0x7) | (1 << 3);

    private final int extent;
    private final List<Layer> layers = new ArrayList<>();

    public VectorTile(int extent) {
        if (extent <= 0) {
            throw new IllegalArgumentException("[mvt_extent] must be greater than 0, found [" + extent + "]");
        }
        this.extent = extent;
    }

    public Layer addLayer(String name) {
        Layer layer = new Layer(name, extent);
        layers.add(layer);
        return layer;
    }

    public BytesReference toBytesReference() {
        ProtobufWriter tile = new ProtobufWriter(1024);
        for (Layer layer : layers) {
            tile.writeMessage(TILE_LAYERS, layer.encode());
        }
        return new BytesArray(tile.toByteArray());
    }

    /** A vector tile layer, holding point features sharing the same keys and values dictionaries. */
    public static final class Layer {

        private final String name;
        private final int extent;
        private final List<String> keys = new ArrayList<>();
        private final Map<String, Integer> keyIds = new HashMap<>();
        private final List<Object> values = new ArrayList<>();
        private final Map<Object, Integer> valueIds = new HashMap<>();
        private final List<byte[]> features = new ArrayList<>();

        private Layer(String name, int extent) {
            this.name = name;
            this.extent = extent;
        }

        /**
         * Adds a point feature to this layer.
         *
         * @param x          tile relative x coordinate, between 0 and the tile extent
         * @param y          tile relative y coordinate, between 0 and the tile extent
         * @param id         optional feature id, exposed as {@code feature.id} by map clients
         * @param properties feature properties; null values are skipped, other values are converted to the closest
         *                   vector tile value type
         */
        public void addPoint(int x, int y, Long id, Map<String, Object> properties) {
            ProtobufWriter feature = new ProtobufWriter(32 + properties.size() * 4);
            if (id != null) {
                feature.writeVarint(FEATURE_ID, id);
            }

            List<Integer> tags = new ArrayList<>(properties.size() * 2);
            for (Map.Entry<String, Object> property : properties.entrySet()) {
                if (property.getValue() == null) {
                    continue;
                }
                tags.add(keyId(property.getKey()));
                tags.add(valueId(property.getValue()));
            }
            feature.writePackedUInt32(FEATURE_TAGS, tags);
            feature.writeVarint(FEATURE_TYPE, GEOMETRY_TYPE_POINT);
            // A single MoveTo, with coordinates relative to the (0, 0) cursor a feature starts with.
            feature.writePackedUInt32(FEATURE_GEOMETRY, List.of(MOVE_TO_COMMAND, zigZag(x), zigZag(y)));

            features.add(feature.toByteArray());
        }

        public int featureCount() {
            return features.size();
        }

        private byte[] encode() {
            ProtobufWriter layer = new ProtobufWriter(512);
            layer.writeVarint(LAYER_VERSION, SPEC_VERSION);
            layer.writeString(LAYER_NAME, name);
            for (byte[] feature : features) {
                layer.writeMessage(LAYER_FEATURES, feature);
            }
            for (String key : keys) {
                layer.writeString(LAYER_KEYS, key);
            }
            for (Object value : values) {
                layer.writeMessage(LAYER_VALUES, encodeValue(value));
            }
            layer.writeVarint(LAYER_EXTENT, extent);
            return layer.toByteArray();
        }

        private int keyId(String key) {
            Integer id = keyIds.get(key);
            if (id == null) {
                id = keys.size();
                keys.add(key);
                keyIds.put(key, id);
            }
            return id;
        }

        private int valueId(Object rawValue) {
            Object value = normalizeValue(rawValue);
            Integer id = valueIds.get(value);
            if (id == null) {
                id = values.size();
                values.add(value);
                valueIds.put(value, id);
            }
            return id;
        }

        /** Maps a java value to one of the value types the vector tile format supports. */
        private static Object normalizeValue(Object value) {
            if (value instanceof String || value instanceof Boolean || value instanceof Long || value instanceof Double) {
                return value;
            }
            if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
                return ((Number) value).longValue();
            }
            if (value instanceof Float) {
                return ((Number) value).doubleValue();
            }
            if (value instanceof Number) {
                return ((Number) value).doubleValue();
            }
            // Arrays, objects and any other type are not representable in a vector tile, fall back to their string form.
            return String.valueOf(value);
        }

        private static byte[] encodeValue(Object value) {
            ProtobufWriter encoded = new ProtobufWriter(16);
            if (value instanceof String string) {
                encoded.writeString(VALUE_STRING, string);
            } else if (value instanceof Boolean bool) {
                encoded.writeBool(VALUE_BOOL, bool);
            } else if (value instanceof Long number) {
                encoded.writeVarint(VALUE_INT, number);
            } else {
                encoded.writeDouble(VALUE_DOUBLE, (Double) value);
            }
            return encoded.toByteArray();
        }

        /** Geometry command parameters are zig zag encoded, so that small negative deltas stay small. */
        private static int zigZag(int value) {
            return (value << 1) ^ (value >> 31);
        }
    }
}
