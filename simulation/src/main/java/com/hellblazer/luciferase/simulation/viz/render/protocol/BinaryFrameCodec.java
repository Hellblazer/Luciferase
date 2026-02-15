/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.viz.render.protocol;

import com.hellblazer.luciferase.simulation.viz.render.RegionBuilder;

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
 *   5    |  1   | lod            | LOD level (0-15)
 *   6    |  1   | level          | Region octree level (0-21)
 *   7    |  1   | reserved       | Reserved (0x00)
 *   8    |  8   | mortonCode     | Morton-encoded region coordinates
 *  16    |  4   | buildVersion   | Build timestamp/version
 *  20    |  4   | dataSize       | Payload size in bytes
 *  24    |  N   | payload        | ESVO/ESVT binary data
 * </pre>
 * <p>
 * All multi-byte fields use little-endian byte order.
 * <p>
 * Thread-safe: stateless utility class.
 *
 * @author hal.hildebrand
 */
public final class BinaryFrameCodec {

    private BinaryFrameCodec() {
        // Prevent instantiation
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
        buffer.put(5, (byte) region.lodLevel());          // byte 5: lod
        buffer.put(6, (byte) region.regionId().level());  // byte 6: region level
        buffer.put(7, (byte) 0);                          // byte 7: reserved
        buffer.putLong(8, region.regionId().mortonCode()); // bytes 8-15: morton code
        buffer.putInt(16, (int) region.buildTimeNs());    // bytes 16-19: build version
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
        buffer.put(5, (byte) region.lodLevel());          // byte 5: lod
        buffer.put(6, (byte) region.regionId().level());  // byte 6: region level
        buffer.put(7, (byte) 0);                          // byte 7: reserved
        buffer.putLong(8, region.regionId().mortonCode()); // bytes 8-15: morton code
        buffer.putInt(16, (int) region.buildTimeNs());    // bytes 16-19: build version
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
            var lod = buffer.get(originalPos + 5);
            var level = buffer.get(originalPos + 6);
            // byte 7 is reserved, skip
            var mortonCode = buffer.getLong(originalPos + 8);
            var buildVersion = buffer.getInt(originalPos + 16);
            var dataSize = buffer.getInt(originalPos + 20);

            return new FrameHeader(magic, format, lod, level, mortonCode, buildVersion, dataSize);
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
     * @param magic Magic number (0x45535652)
     * @param format Format code (0x01=ESVO, 0x02=ESVT)
     * @param lod LOD level (0-15)
     * @param level Region octree level (0-21)
     * @param mortonCode Morton-encoded region coordinates
     * @param buildVersion Build timestamp/version
     * @param dataSize Payload size in bytes
     */
    public record FrameHeader(
        int magic,
        byte format,
        byte lod,
        byte level,
        long mortonCode,
        int buildVersion,
        int dataSize
    ) {}
}
