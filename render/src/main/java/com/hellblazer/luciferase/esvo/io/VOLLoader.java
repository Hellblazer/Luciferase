/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.esvo.io;

import com.hellblazer.luciferase.geometry.Point3i;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.InflaterInputStream;

/**
 * Loader for VOL (Volume) format files used by the VolGallery project.
 * VOL format is Version 3 with ASCII header and zlib-compressed unsigned char voxel data.
 *
 * <p>Format specification:
 * <pre>
 * ASCII Header (key: value pairs):
 *   Center-X: [float]     - Center X coordinate
 *   Center-Y: [float]     - Center Y coordinate
 *   Center-Z: [float]     - Center Z coordinate
 *   X: [int]              - Voxel grid X dimension
 *   Y: [int]              - Voxel grid Y dimension
 *   Z: [int]              - Voxel grid Z dimension
 *   Voxel-Size: [float]   - Size of each voxel
 *   Alpha-Color: [int]    - Alpha/background value (0 = transparent)
 *   Voxel-Endian: [int]   - Voxel byte order
 *   Int-Endian: [string]  - Integer endianness (e.g., "0123")
 *   Version: [int]        - Format version (3)
 *   .                     - End of header marker
 *
 * Binary Data:
 *   zlib-compressed unsigned char values (0-255)
 *   Non-zero values indicate occupied voxels
 * </pre>
 *
 * @author hal.hildebrand
 * @see <a href="https://github.com/dcoeurjo/VolGallery">VolGallery on GitHub</a>
 */
public class VOLLoader {
    private static final Logger log = LoggerFactory.getLogger(VOLLoader.class);

    /**
     * VOL file header information.
     */
    public record VOLHeader(
        float centerX, float centerY, float centerZ,
        int dimX, int dimY, int dimZ,
        float voxelSize,
        int alphaColor,
        int voxelEndian,
        String intEndian,
        int version
    ) {
        public int totalVoxels() {
            return dimX * dimY * dimZ;
        }
    }

    /**
     * Result of loading a VOL file.
     */
    public record VOLData(
        VOLHeader header,
        List<Point3i> voxels,
        byte[] rawData
    ) {
        public int occupiedCount() {
            return voxels.size();
        }

        public float occupancyRatio() {
            return (float) voxels.size() / header.totalVoxels();
        }
    }

    /**
     * Load a VOL file from the classpath.
     *
     * @param resourcePath Resource path (e.g., "/voxels/bunny-64.vol")
     * @return VOLData containing header and occupied voxel positions
     * @throws IOException If the file cannot be read or parsed
     */
    public VOLData loadResource(String resourcePath) throws IOException {
        try (var stream = getClass().getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new FileNotFoundException("Resource not found: " + resourcePath);
            }
            return load(stream, resourcePath);
        }
    }

    /**
     * Load a VOL file from a file path.
     *
     * @param filePath Path to the VOL file
     * @return VOLData containing header and occupied voxel positions
     * @throws IOException If the file cannot be read or parsed
     */
    public VOLData loadFile(String filePath) throws IOException {
        try (var stream = new FileInputStream(filePath)) {
            return load(stream, filePath);
        }
    }

    /**
     * Load a VOL file from an input stream.
     *
     * @param inputStream Input stream containing VOL data
     * @param sourceName Name for logging (file path or resource name)
     * @return VOLData containing header and occupied voxel positions
     * @throws IOException If the stream cannot be read or parsed
     */
    public VOLData load(InputStream inputStream, String sourceName) throws IOException {
        var bufferedStream = new BufferedInputStream(inputStream);

        // Parse ASCII header
        var header = parseHeader(bufferedStream, sourceName);
        log.debug("Loaded VOL header from {}: {}x{}x{}, version {}",
                 sourceName, header.dimX, header.dimY, header.dimZ, header.version);

        // Read and decompress binary data
        var rawData = decompressData(bufferedStream, header.totalVoxels(), sourceName);

        // Extract occupied voxel positions
        var voxels = extractVoxels(rawData, header);

        log.info("Loaded {} ({}x{}x{}): {} occupied voxels ({} % occupancy)",
                sourceName, header.dimX, header.dimY, header.dimZ,
                voxels.size(), String.format("%.1f", (float) voxels.size() / header.totalVoxels() * 100));

        return new VOLData(header, voxels, rawData);
    }

    /**
     * Parse the ASCII header from a VOL stream.
     */
    private VOLHeader parseHeader(BufferedInputStream stream, String sourceName) throws IOException {
        float centerX = 0, centerY = 0, centerZ = 0;
        int dimX = 0, dimY = 0, dimZ = 0;
        float voxelSize = 1.0f;
        int alphaColor = 0;
        int voxelEndian = 0;
        var intEndian = "0123";
        int version = 0;

        var lineBuilder = new StringBuilder();

        // Read lines until we hit the "." marker
        while (true) {
            int b = stream.read();
            if (b == -1) {
                throw new IOException("Unexpected end of file in header: " + sourceName);
            }

            char c = (char) b;
            if (c == '\n') {
                String line = lineBuilder.toString().trim();
                lineBuilder.setLength(0);

                if (line.equals(".")) {
                    // End of header
                    break;
                }

                if (line.isEmpty()) {
                    continue;
                }

                // Parse key: value pair
                int colonIndex = line.indexOf(':');
                if (colonIndex > 0) {
                    var key = line.substring(0, colonIndex).trim();
                    var value = line.substring(colonIndex + 1).trim();

                    switch (key) {
                        case "Center-X" -> centerX = Float.parseFloat(value);
                        case "Center-Y" -> centerY = Float.parseFloat(value);
                        case "Center-Z" -> centerZ = Float.parseFloat(value);
                        case "X" -> dimX = Integer.parseInt(value);
                        case "Y" -> dimY = Integer.parseInt(value);
                        case "Z" -> dimZ = Integer.parseInt(value);
                        case "Voxel-Size" -> voxelSize = Float.parseFloat(value);
                        case "Alpha-Color" -> alphaColor = Integer.parseInt(value);
                        case "Voxel-Endian" -> voxelEndian = Integer.parseInt(value);
                        case "Int-Endian" -> intEndian = value;
                        case "Version" -> version = Integer.parseInt(value);
                        default -> log.trace("Unknown header key: {}", key);
                    }
                }
            } else {
                lineBuilder.append(c);
            }
        }

        if (dimX <= 0 || dimY <= 0 || dimZ <= 0) {
            throw new IOException("Invalid dimensions in VOL header: " + dimX + "x" + dimY + "x" + dimZ);
        }

        return new VOLHeader(centerX, centerY, centerZ, dimX, dimY, dimZ,
                            voxelSize, alphaColor, voxelEndian, intEndian, version);
    }

    /**
     * Decompress the zlib-compressed voxel data.
     */
    private byte[] decompressData(BufferedInputStream stream, int expectedSize, String sourceName) throws IOException {
        var inflater = new InflaterInputStream(stream);
        var buffer = new byte[expectedSize];
        int totalRead = 0;

        while (totalRead < expectedSize) {
            int read = inflater.read(buffer, totalRead, expectedSize - totalRead);
            if (read == -1) {
                break;
            }
            totalRead += read;
        }

        if (totalRead != expectedSize) {
            log.warn("VOL data size mismatch in {}: expected {} bytes, got {}",
                    sourceName, expectedSize, totalRead);
        }

        return buffer;
    }

    /**
     * Extract occupied voxel positions from raw voxel data.
     * Non-zero values (different from alphaColor) indicate occupied voxels.
     */
    private List<Point3i> extractVoxels(byte[] rawData, VOLHeader header) {
        var voxels = new ArrayList<Point3i>();
        int alphaValue = header.alphaColor;

        int index = 0;
        for (int z = 0; z < header.dimZ; z++) {
            for (int y = 0; y < header.dimY; y++) {
                for (int x = 0; x < header.dimX; x++) {
                    int value = rawData[index++] & 0xFF; // Unsigned byte
                    if (value != alphaValue) {
                        voxels.add(new Point3i(x, y, z));
                    }
                }
            }
        }

        return voxels;
    }

    /**
     * Get a scaled list of voxels for a specific target resolution.
     * Useful when the source data is higher resolution than needed.
     *
     * @param data VOL data to scale
     * @param targetResolution Target grid resolution
     * @return Scaled voxel positions
     */
    public List<Point3i> scaleVoxels(VOLData data, int targetResolution) {
        var header = data.header();
        int maxDim = Math.max(header.dimX, Math.max(header.dimY, header.dimZ));

        if (maxDim <= targetResolution) {
            // No scaling needed
            return new ArrayList<>(data.voxels());
        }

        float scale = (float) targetResolution / maxDim;
        var scaledVoxels = new ArrayList<Point3i>();
        var seen = new java.util.HashSet<Long>();

        for (var voxel : data.voxels()) {
            int sx = (int) (voxel.x * scale);
            int sy = (int) (voxel.y * scale);
            int sz = (int) (voxel.z * scale);

            // Clamp to target bounds
            sx = Math.min(sx, targetResolution - 1);
            sy = Math.min(sy, targetResolution - 1);
            sz = Math.min(sz, targetResolution - 1);

            // Deduplicate
            long key = ((long) sx << 40) | ((long) sy << 20) | sz;
            if (seen.add(key)) {
                scaledVoxels.add(new Point3i(sx, sy, sz));
            }
        }

        log.debug("Scaled {} voxels from {}³ to {}³: {} unique voxels",
                 data.voxels().size(), maxDim, targetResolution, scaledVoxels.size());

        return scaledVoxels;
    }
}
