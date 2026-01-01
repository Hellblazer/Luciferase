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
package com.hellblazer.luciferase.esvt.io;

import com.hellblazer.luciferase.sparse.io.SparseVoxelIOUtils;

import java.io.IOException;
import java.nio.file.Path;

/**
 * File format definitions and utilities for ESVT serialization.
 *
 * <p>ESVT files store tetrahedral sparse voxel data structures optimized
 * for GPU ray traversal.
 *
 * <p><b>File Layout V1:</b>
 * <pre>
 * [Header: 32 bytes]
 *   magic(4) + version(4) + nodeCount(4) + contourCount(4) +
 *   farPtrCount(4) + rootType(4) + maxDepth(4) + reserved(4)
 * [Nodes: nodeCount * 8 bytes]
 * [Contours: contourCount * 4 bytes]
 * [FarPointers: farPtrCount * 4 bytes]
 * </pre>
 *
 * <p><b>File Layout V2:</b>
 * <pre>
 * [Header: 64 bytes]
 *   V1 header + metadataOffset(8) + metadataSize(8) +
 *   leafCount(4) + internalCount(4) + gridResolution(4) + voxelCoordsCount(4)
 * [Nodes]
 * [Contours]
 * [FarPointers]
 * [VoxelCoords: voxelCoordsCount * 4 bytes]
 * [Metadata: serialized ESVTMetadata]
 * </pre>
 *
 * @author hal.hildebrand
 */
public class ESVTFileFormat {

    // Magic number: "ESVT" in ASCII (little-endian)
    public static final int MAGIC_NUMBER = 0x54565345; // 'E','S','V','T'

    // File format versions
    public static final int VERSION_1 = 1;
    public static final int VERSION_2 = 2;
    public static final int CURRENT_VERSION = VERSION_2;

    // Header sizes
    public static final int HEADER_SIZE_V1 = 32;
    public static final int HEADER_SIZE_V2 = 64;

    // Node size (ESVTNodeUnified is 8 bytes)
    public static final int NODE_SIZE_BYTES = 8;

    private ESVTFileFormat() {
        // Utility class
    }

    /**
     * Detect the version of an ESVT file.
     *
     * @param file Path to the ESVT file
     * @return File format version
     * @throws IOException if file cannot be read or has invalid magic
     */
    public static int detectVersion(Path file) throws IOException {
        return SparseVoxelIOUtils.detectVersion(file, MAGIC_NUMBER);
    }

    /**
     * Validate that a file is a valid ESVT file.
     *
     * @param file Path to validate
     * @return true if file has valid ESVT magic number
     */
    public static boolean isValidESVTFile(Path file) {
        try {
            detectVersion(file);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * File header structure for ESVT files.
     */
    public static class Header {
        public int magic = MAGIC_NUMBER;
        public int version = CURRENT_VERSION;
        public int nodeCount;
        public int contourCount;
        public int farPtrCount;
        public int rootType;
        public int maxDepth;
        public int reserved;

        // V2 fields
        public long metadataOffset;
        public long metadataSize;
        public int leafCount;
        public int internalCount;
        public int gridResolution;
        public int voxelCoordsCount;

        public Header() {
        }

        public Header(int version) {
            this.version = version;
        }

        /**
         * Get header size based on version.
         */
        public int getHeaderSize() {
            return version >= VERSION_2 ? HEADER_SIZE_V2 : HEADER_SIZE_V1;
        }

        /**
         * Calculate offset where node data starts.
         */
        public long getNodesOffset() {
            return getHeaderSize();
        }

        /**
         * Calculate offset where contour data starts.
         */
        public long getContoursOffset() {
            return getNodesOffset() + (long) nodeCount * NODE_SIZE_BYTES;
        }

        /**
         * Calculate offset where far pointer data starts.
         */
        public long getFarPointersOffset() {
            return getContoursOffset() + (long) contourCount * 4;
        }

        /**
         * Calculate offset where voxel coords start (V2 only).
         */
        public long getVoxelCoordsOffset() {
            return getFarPointersOffset() + (long) farPtrCount * 4;
        }

        /**
         * Calculate total data size (excluding metadata).
         */
        public long getDataSize() {
            long size = getHeaderSize();
            size += (long) nodeCount * NODE_SIZE_BYTES;
            size += (long) contourCount * 4;
            size += (long) farPtrCount * 4;
            if (version >= VERSION_2) {
                size += (long) voxelCoordsCount * 4;
            }
            return size;
        }

        @Override
        public String toString() {
            return String.format("ESVTHeader[v%d, nodes=%d, contours=%d, farPtrs=%d, rootType=%d, depth=%d]",
                version, nodeCount, contourCount, farPtrCount, rootType, maxDepth);
        }
    }
}
