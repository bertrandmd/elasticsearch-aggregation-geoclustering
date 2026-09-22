package com.opendatasoft.elasticsearch.mvt;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Minimal protocol buffers writer, just enough to serialize a Mapbox Vector Tile.
 * <p>
 * Elasticsearch does not expose a protobuf library to plugins, and the vector tile format only needs a handful of wire
 * types (varint, fixed64 and length delimited), so they are written by hand here.
 * See <a href="https://protobuf.dev/programming-guides/encoding/">the protobuf encoding reference</a>.
 */
final class ProtobufWriter {

    private static final int WIRE_TYPE_VARINT = 0;
    private static final int WIRE_TYPE_FIXED_64 = 1;
    private static final int WIRE_TYPE_LENGTH_DELIMITED = 2;

    private final ByteArrayOutputStream out;

    ProtobufWriter() {
        this(64);
    }

    ProtobufWriter(int expectedSize) {
        this.out = new ByteArrayOutputStream(expectedSize);
    }

    /** Writes a varint field, which covers the protobuf int32/int64/uint32/uint64/bool/enum types. */
    void writeVarint(int fieldNumber, long value) {
        writeTag(fieldNumber, WIRE_TYPE_VARINT);
        writeVarint(value);
    }

    void writeBool(int fieldNumber, boolean value) {
        writeVarint(fieldNumber, value ? 1 : 0);
    }

    void writeDouble(int fieldNumber, double value) {
        writeTag(fieldNumber, WIRE_TYPE_FIXED_64);
        long bits = Double.doubleToLongBits(value);
        for (int i = 0; i < 8; i++) {
            out.write((int) ((bits >>> (8 * i)) & 0xFF));
        }
    }

    void writeString(int fieldNumber, String value) {
        writeLengthDelimited(fieldNumber, value.getBytes(StandardCharsets.UTF_8));
    }

    /** Writes an embedded message, which is length delimited like a string. */
    void writeMessage(int fieldNumber, byte[] message) {
        writeLengthDelimited(fieldNumber, message);
    }

    /** Writes a {@code repeated uint32 [packed=true]} field, used by the feature tags and geometry commands. */
    void writePackedUInt32(int fieldNumber, List<Integer> values) {
        if (values.isEmpty()) {
            return;
        }
        ProtobufWriter packed = new ProtobufWriter(values.size() * 2);
        for (Integer value : values) {
            packed.writeVarint(Integer.toUnsignedLong(value));
        }
        writeLengthDelimited(fieldNumber, packed.toByteArray());
    }

    byte[] toByteArray() {
        return out.toByteArray();
    }

    int size() {
        return out.size();
    }

    private void writeLengthDelimited(int fieldNumber, byte[] bytes) {
        writeTag(fieldNumber, WIRE_TYPE_LENGTH_DELIMITED);
        writeVarint(bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    private void writeTag(int fieldNumber, int wireType) {
        writeVarint(((long) fieldNumber << 3) | wireType);
    }

    /** Writes an unsigned base 128 varint. Negative longs are written as their unsigned 64 bits representation. */
    private void writeVarint(long value) {
        while (true) {
            if ((value & ~0x7FL) == 0) {
                out.write((int) value);
                return;
            }
            out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
    }
}
