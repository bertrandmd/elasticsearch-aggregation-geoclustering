package com.opendatasoft.elasticsearch.mvt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal Mapbox Vector Tile reader, used by the tests to assert what the clustering API actually returns.
 */
public final class VectorTileDecoder {

    public record Feature(Long id, int x, int y, Map<String, Object> properties) {}

    public record Layer(String name, int version, int extent, List<Feature> features) {}

    private VectorTileDecoder() {}

    public static Map<String, Layer> decode(byte[] tile) {
        Map<String, Layer> layers = new LinkedHashMap<>();
        Reader reader = new Reader(tile, 0, tile.length);
        while (reader.hasNext()) {
            long tag = reader.varint();
            if (field(tag) == 3 && wireType(tag) == 2) {
                Layer layer = decodeLayer(reader.lengthDelimited());
                layers.put(layer.name(), layer);
            } else {
                reader.skip(wireType(tag));
            }
        }
        return layers;
    }

    private static Layer decodeLayer(byte[] bytes) {
        Reader reader = new Reader(bytes, 0, bytes.length);
        String name = null;
        int version = 0;
        int extent = 4096;
        List<byte[]> rawFeatures = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        List<Object> values = new ArrayList<>();

        while (reader.hasNext()) {
            long tag = reader.varint();
            switch (field(tag)) {
                case 1 -> name = new String(reader.lengthDelimited(), java.nio.charset.StandardCharsets.UTF_8);
                case 2 -> rawFeatures.add(reader.lengthDelimited());
                case 3 -> keys.add(new String(reader.lengthDelimited(), java.nio.charset.StandardCharsets.UTF_8));
                case 4 -> values.add(decodeValue(reader.lengthDelimited()));
                case 5 -> extent = (int) reader.varint();
                case 15 -> version = (int) reader.varint();
                default -> reader.skip(wireType(tag));
            }
        }

        List<Feature> features = new ArrayList<>(rawFeatures.size());
        for (byte[] rawFeature : rawFeatures) {
            features.add(decodeFeature(rawFeature, keys, values));
        }
        return new Layer(name, version, extent, features);
    }

    private static Object decodeValue(byte[] bytes) {
        Reader reader = new Reader(bytes, 0, bytes.length);
        long tag = reader.varint();
        return switch (field(tag)) {
            case 1 -> new String(reader.lengthDelimited(), java.nio.charset.StandardCharsets.UTF_8);
            case 3 -> Double.longBitsToDouble(reader.fixed64());
            case 4, 5 -> reader.varint();
            case 6 -> {
                long zigzag = reader.varint();
                yield (zigzag >>> 1) ^ -(zigzag & 1);
            }
            case 7 -> reader.varint() != 0;
            default -> throw new IllegalStateException("unsupported vector tile value field " + field(tag));
        };
    }

    private static Feature decodeFeature(byte[] bytes, List<String> keys, List<Object> values) {
        Reader reader = new Reader(bytes, 0, bytes.length);
        Long id = null;
        List<Integer> tags = new ArrayList<>();
        List<Integer> geometry = new ArrayList<>();
        int geometryType = 0;

        while (reader.hasNext()) {
            long tag = reader.varint();
            switch (field(tag)) {
                case 1 -> id = reader.varint();
                case 2 -> tags.addAll(reader.packedUInt32());
                case 3 -> geometryType = (int) reader.varint();
                case 4 -> geometry.addAll(reader.packedUInt32());
                default -> reader.skip(wireType(tag));
            }
        }

        if (geometryType != 1) {
            throw new IllegalStateException("only point features are expected, found geometry type " + geometryType);
        }
        if (geometry.size() != 3 || geometry.get(0) != 9) {
            throw new IllegalStateException("expected a single MoveTo command, found " + geometry);
        }

        Map<String, Object> properties = new HashMap<>();
        for (int i = 0; i + 1 < tags.size(); i += 2) {
            properties.put(keys.get(tags.get(i)), values.get(tags.get(i + 1)));
        }
        return new Feature(id, zigZagDecode(geometry.get(1)), zigZagDecode(geometry.get(2)), properties);
    }

    private static int zigZagDecode(int value) {
        return (value >>> 1) ^ -(value & 1);
    }

    private static int field(long tag) {
        return (int) (tag >>> 3);
    }

    private static int wireType(long tag) {
        return (int) (tag & 0x7);
    }

    private static final class Reader {
        private final byte[] buffer;
        private int position;
        private final int end;

        Reader(byte[] buffer, int position, int end) {
            this.buffer = buffer;
            this.position = position;
            this.end = end;
        }

        boolean hasNext() {
            return position < end;
        }

        long varint() {
            long result = 0;
            int shift = 0;
            while (true) {
                byte b = buffer[position++];
                result |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return result;
                }
                shift += 7;
            }
        }

        long fixed64() {
            long result = 0;
            for (int i = 0; i < 8; i++) {
                result |= (long) (buffer[position++] & 0xFF) << (8 * i);
            }
            return result;
        }

        byte[] lengthDelimited() {
            int length = (int) varint();
            byte[] bytes = Arrays.copyOfRange(buffer, position, position + length);
            position += length;
            return bytes;
        }

        List<Integer> packedUInt32() {
            byte[] bytes = lengthDelimited();
            Reader packed = new Reader(bytes, 0, bytes.length);
            List<Integer> values = new ArrayList<>();
            while (packed.hasNext()) {
                values.add((int) packed.varint());
            }
            return values;
        }

        void skip(int wireType) {
            switch (wireType) {
                case 0 -> varint();
                case 1 -> position += 8;
                case 2 -> position += (int) varint();
                case 5 -> position += 4;
                default -> throw new IllegalStateException("unsupported wire type " + wireType);
            }
        }
    }
}
