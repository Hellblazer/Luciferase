/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.viz.render.protocol;

import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.simulation.viz.render.RegionBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Binary frame encoder/decoder for ESVO/ESVT WebSocket frames.
 * <p>
 * Frame format (24-byte header + N-byte payload):
 * <pre>
 * Offset | Size | Field          | Description
 * -------|------|----------------|----------------------------------
 *   0    |  4   | magic          | 0x45535652 ("ESVR" little-endian)
 *   4    |  1   | format         | 0x01=ESVO, 0x02=ESVT
 *   5    |  1   | keyType        | 0x01=MortonKey, 0x02=TetreeKey
 *   6    |  1   | level          | Region octree/tetree level (0-21)
 *   7    |  1   | reserved       | Reserved (0x00)
 *   8    |  8   | key            | Spatial key (Morton code or TetreeKey low bits)
 *  16    |  4   | buildVersion   | Build version counter (LOW 32 bits; see note)
 *  20    |  4   | dataSize       | Payload size in bytes
 *  24    |  N   | payload        | ESVO/ESVT binary data
 * </pre>
 * <p>
 * <b>buildVersion truncation (Luciferase-0frcy.73):</b> {@code buildVersion} is a
 * {@code long} counter on the source records but the wire format reserves only 4
 * bytes, so the encoder writes the low 32 bits (via {@link #buildVersionToWire(long)},
 * which logs a warning once the counter exceeds {@link Integer#MAX_VALUE}). The
 * counter is monotonic and wraps after ~4.29e9 builds; consumers MUST compare wire
 * build versions with {@link #compareBuildVersions(int, int)} (unsigned) rather than
 * signed {@code <}/{@code >} so the wrap is handled predictably. Extending the header
 * to carry a full {@code long} requires a protocol version bump and is deferred.
 * <p>
 * All multi-byte fields use little-endian byte order.
 * <p>
 * Thread-safe: stateless utility class.
 *
 * @author hal.hildebrand
 */
public final class BinaryFrameCodec {

    private static final Logger log = LoggerFactory.getLogger(BinaryFrameCodec.class);

    private BinaryFrameCodec() {
        // Prevent instantiation
    }

    /**
     * Truncate a 64-bit build-version counter to the 32-bit wire field, warning once
     * the counter has grown past what the wire field can represent (Luciferase-0frcy.73).
     * <p>
     * The wire format reserves only 4 bytes for {@code buildVersion}. The low 32 bits
     * are written; the counter is monotonic so wrap-around is predictable as long as
     * consumers compare with {@link #compareBuildVersions(int, int)}. Once the source
     * counter exceeds {@link Integer#MAX_VALUE} the high bits are silently dropped, so
     * a warning is emitted to make the schema trap observable in logs.
     *
     * @param buildVersion the 64-bit source build version
     * @return the low 32 bits, as an int, for {@code putInt}
     */
    static int buildVersionToWire(long buildVersion) {
        if (buildVersion > Integer.MAX_VALUE || buildVersion < 0) {
            log.warn("buildVersion {} exceeds 32-bit wire field; truncating to low 32 bits {} "
                     + "(consumers must use compareBuildVersions for unsigned ordering)",
                     buildVersion, buildVersion & 0xFFFF_FFFFL);
        }
        return (int) buildVersion;
    }

    /**
     * Compare two wire build-version values using unsigned semantics so the 32-bit
     * counter wrap is handled predictably (Luciferase-0frcy.73). Returns a negative,
     * zero, or positive int if {@code a} is older than, equal to, or newer than
     * {@code b} respectively.
     */
    public static int compareBuildVersions(int a, int b) {
        return Integer.compareUnsigned(a, b);
    }

    /**
     * Encode a BuiltRegion into a binary WebSocket frame.
     *
     * @param region Built region to encode
     * @return ByteBuffer containing encoded frame (position=0, limit=frameSize)
     */
    public static ByteBuffer encode(RegionBuilder.BuiltRegion region) {
        var data = region.serializedData();
        var buffer = ByteBuffer.allocate(ProtocolConstants.FRAME_HEADER_SIZE + data.length);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        // Write header
        buffer.putInt(0, ProtocolConstants.FRAME_MAGIC);  // bytes 0-3: magic
        buffer.put(4, formatCode(region.type()));         // byte 4: format
        buffer.put(5, ProtocolConstants.KEY_TYPE_MORTON);  // byte 5: keyType (RegionId is always Morton)
        buffer.put(6, (byte) region.regionId().level());  // byte 6: region level
        buffer.put(7, (byte) 0);                          // byte 7: reserved
        buffer.putLong(8, region.regionId().mortonCode()); // bytes 8-15: morton code
        buffer.putInt(16, buildVersionToWire(region.buildVersion())); // bytes 16-19: build version (low 32 bits, see class doc)
        buffer.putInt(20, data.length);                   // bytes 20-23: data size

        // Write payload
        buffer.position(ProtocolConstants.FRAME_HEADER_SIZE);
        buffer.put(data);

        // Reset position for reading
        buffer.position(0);
        return buffer;
    }

    /**
     * Encode a BuiltRegion into a pre-allocated ByteBuffer (Luciferase-8db0).
     * <p>
     * Uses pooled buffer to reduce GC pressure. Buffer must have sufficient capacity.
     *
     * @param region Built region to encode
     * @param buffer Pre-allocated buffer (must have capacity >= FRAME_HEADER_SIZE + data.length)
     * @return The same ByteBuffer with encoded frame (position=0, limit=frameSize)
     * @throws IllegalArgumentException if buffer capacity insufficient
     */
    public static ByteBuffer encode(RegionBuilder.BuiltRegion region, ByteBuffer buffer) {
        var data = region.serializedData();
        int requiredSize = ProtocolConstants.FRAME_HEADER_SIZE + data.length;

        if (buffer.capacity() < requiredSize) {
            throw new IllegalArgumentException(
                String.format("Buffer capacity %d insufficient for frame size %d",
                    buffer.capacity(), requiredSize));
        }

        buffer.clear();
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        // Write header
        buffer.putInt(0, ProtocolConstants.FRAME_MAGIC);  // bytes 0-3: magic
        buffer.put(4, formatCode(region.type()));         // byte 4: format
        buffer.put(5, ProtocolConstants.KEY_TYPE_MORTON);  // byte 5: keyType (RegionId is always Morton)
        buffer.put(6, (byte) region.regionId().level());  // byte 6: region level
        buffer.put(7, (byte) 0);                          // byte 7: reserved
        buffer.putLong(8, region.regionId().mortonCode()); // bytes 8-15: morton code
        buffer.putInt(16, buildVersionToWire(region.buildVersion())); // bytes 16-19: build version (low 32 bits, see class doc)
        buffer.putInt(20, data.length);                   // bytes 20-23: data size

        // Write payload
        buffer.position(ProtocolConstants.FRAME_HEADER_SIZE);
        buffer.put(data);

        // Set limit to actual frame size and reset position
        buffer.limit(requiredSize);
        buffer.position(0);
        return buffer;
    }

    /**
     * Decode the header of a binary WebSocket frame.
     *
     * @param buffer Buffer containing frame data (position will be preserved)
     * @return FrameHeader record, or null if invalid
     */
    public static FrameHeader decodeHeader(ByteBuffer buffer) {
        // Validate buffer size
        if (buffer.remaining() < ProtocolConstants.FRAME_HEADER_SIZE) {
            return null;
        }

        // Save original position
        var originalPos = buffer.position();
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        try {
            // Read and validate magic
            var magic = buffer.getInt(originalPos + 0);
            if (magic != ProtocolConstants.FRAME_MAGIC) {
                return null;
            }

            // Read header fields
            var format = buffer.get(originalPos + 4);
            var keyType = buffer.get(originalPos + 5);
            var level = buffer.get(originalPos + 6);
            // byte 7 is reserved, skip
            var key = buffer.getLong(originalPos + 8);
            var buildVersion = buffer.getInt(originalPos + 16);
            var dataSize = buffer.getInt(originalPos + 20);

            // Luciferase-7wzml.208: guard against malformed / malicious payloads — a crafted
            // frame could set dataSize to an arbitrarily large value, causing downstream
            // callers to allocate oversized buffers or read past the end of the supplied data.
            if (dataSize < 0) {
                throw new IllegalArgumentException(
                    "Malformed frame: dataSize is negative (" + dataSize + ")");
            }
            int available = buffer.remaining() - ProtocolConstants.FRAME_HEADER_SIZE;
            if (dataSize > available) {
                throw new IllegalArgumentException(
                    "Malformed frame: dataSize " + dataSize + " exceeds available payload bytes " + available);
            }

            return new FrameHeader(magic, format, keyType, level, key, buildVersion, dataSize);
        } finally {
            // Restore original position (don't mutate input buffer)
            buffer.position(originalPos);
        }
    }

    /**
     * Extract payload data from a binary WebSocket frame.
     *
     * @param buffer Buffer containing complete frame (header + payload)
     * @return Payload bytes
     */
    public static byte[] extractPayload(ByteBuffer buffer) {
        var originalPos = buffer.position();
        try {
            // Skip header, read remaining bytes
            buffer.position(originalPos + ProtocolConstants.FRAME_HEADER_SIZE);
            var payload = new byte[buffer.remaining()];
            buffer.get(payload);
            return payload;
        } finally {
            buffer.position(originalPos);
        }
    }

    /**
     * Encode a frame using a SpatialKey directly.
     * <p>
     * MortonKey  uses {@code getMortonCode()} as the key long value.
     * TetreeKey  uses {@code getLowBits()} as the key long value (CompactTetreeKey wire representation).
     *
     * @param key          the spatial key to encode
     * @param type         the build type (ESVO or ESVT)
     * @param buildVersion the build version counter
     * @param data         the payload bytes
     * @return ByteBuffer containing encoded frame (position=0, limit=frameSize)
     */
    public static ByteBuffer encodeWithKey(SpatialKey<?> key, RegionBuilder.BuildType type,
                                           long buildVersion, byte[] data) {
        var buffer = ByteBuffer.allocate(ProtocolConstants.FRAME_HEADER_SIZE + data.length);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        writeKeyHeader(buffer, key, type, buildVersion, data.length);
        buffer.position(ProtocolConstants.FRAME_HEADER_SIZE);
        buffer.put(data);
        buffer.position(0);
        return buffer;
    }

    /**
     * Write header fields derived from a SpatialKey into the buffer using absolute-position puts.
     */
    private static void writeKeyHeader(ByteBuffer buf, SpatialKey<?> key,
                                       RegionBuilder.BuildType type, long buildVersion, int dataSize) {
        buf.putInt(0, ProtocolConstants.FRAME_MAGIC);
        buf.put(4, formatCode(type));
        buf.put(5, keyTypeByte(key));
        buf.put(6, key.getLevel());
        buf.put(7, (byte) 0);
        buf.putLong(8, keyLong(key));
        buf.putInt(16, buildVersionToWire(buildVersion));
        buf.putInt(20, dataSize);
    }

    /**
     * Map a SpatialKey to its wire key_type byte.
     */
    private static byte keyTypeByte(SpatialKey<?> key) {
        return switch (key) {
            case com.hellblazer.luciferase.lucien.octree.MortonKey mk ->
                ProtocolConstants.KEY_TYPE_MORTON;
            case com.hellblazer.luciferase.lucien.tetree.TetreeKey<?> tk ->
                ProtocolConstants.KEY_TYPE_TET;
            default -> throw new IllegalArgumentException("Unknown key type: " + key.getClass());
        };
    }

    /**
     * Extract the 64-bit wire representation of a SpatialKey.
     */
    private static long keyLong(SpatialKey<?> key) {
        return switch (key) {
            case com.hellblazer.luciferase.lucien.octree.MortonKey mk -> mk.getMortonCode();
            case com.hellblazer.luciferase.lucien.tetree.CompactTetreeKey tk -> tk.getLowBits();
            default -> throw new IllegalArgumentException(
                "ExtendedTetreeKey not supported at wire level (use level 0-10 only): " + key.getClass());
        };
    }

    /**
     * Convert BuildType to format code.
     */
    private static byte formatCode(RegionBuilder.BuildType type) {
        return type == RegionBuilder.BuildType.ESVO
            ? ProtocolConstants.FORMAT_ESVO
            : ProtocolConstants.FORMAT_ESVT;
    }

    /**
     * Decoded binary frame header.
     *
     * @param magic        Magic number (0x45535652)
     * @param format       Format code (0x01=ESVO, 0x02=ESVT)
     * @param keyType      Key type byte (0x01=MortonKey, 0x02=TetreeKey)
     * @param level        Region octree/tetree level (0-21)
     * @param key          Spatial key value (Morton code or TetreeKey low bits)
     * @param buildVersion Build version counter
     * @param dataSize     Payload size in bytes
     */
    public record FrameHeader(
        int magic,
        byte format,
        byte keyType,
        byte level,
        long key,
        int buildVersion,
        int dataSize
    ) {}
}
