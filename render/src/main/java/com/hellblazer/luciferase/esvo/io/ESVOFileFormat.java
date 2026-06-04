package com.hellblazer.luciferase.esvo.io;

import com.hellblazer.luciferase.sparse.io.SparseVoxelIOUtils;

import java.io.IOException;
import java.nio.file.Path;

/**
 * File format definitions and utilities for ESVO serialization.
 *
 * <h2>Version history</h2>
 * <ul>
 *   <li>VERSION_1 (16 B header): dense node array only — obsolete</li>
 *   <li>VERSION_2 (32 B header): adds optional metadata section — obsolete</li>
 *   <li>VERSION_3 (40 B header): sparse (index,node) pairs + farPointers section
 *       + validated allocation — current</li>
 * </ul>
 *
 * <h2>VERSION_3 on-disk layout</h2>
 * <pre>
 * Header (40 bytes, little-endian):
 *   magic          int32   0x4553564F ("ESVO")
 *   version        int32   3
 *   nodeCount      int32   actual stored node count (sparse count, NOT max-index+1)
 *   reserved       int32   0
 *   metadataOffset int64   byte offset of metadata section (0 = absent)
 *   metadataSize   int64   metadata section length in bytes (0 = absent)
 *   farPtrCount    int32   number of far-pointer entries (0 = no far pointers)
 *   reserved2      int32   0
 *
 * Node section (nodeCount * 12 bytes):
 *   For each stored node, in ascending index order:
 *     nodeIndex      int32   sparse index in the octree map
 *     childDescriptor int32  raw child descriptor word
 *     contourDescriptor int32 raw contour descriptor word
 *
 * FarPointer section (farPtrCount * 4 bytes, immediately after node section):
 *   farPointers[0..farPtrCount-1]  int32 each entry
 *
 * Metadata section (metadataSize bytes at metadataOffset, v2-compatible):
 *   Java-serialized ESVOMetadata object
 * </pre>
 */
public class ESVOFileFormat {

    // Magic number: "ESVO" in ASCII
    public static final int MAGIC_NUMBER = 0x4553564F;

    // File format versions
    public static final int VERSION_1 = 1;
    public static final int VERSION_2 = 2;
    /**
     * VERSION_3: sparse (index,node) pairs + farPointers section.
     * This is the current write version.
     */
    public static final int VERSION_3 = 3;

    // Header sizes in bytes
    public static final int HEADER_SIZE_V1 = 16; // magic(4)+version(4)+nodeCount(4)+reserved(4)
    public static final int HEADER_SIZE_V2 = 32; // v1+metadataOffset(8)+metadataSize(8)
    /** VERSION_3 header: v2 + farPtrCount(4) + reserved2(4) = 40 bytes */
    public static final int HEADER_SIZE_V3 = 40;

    /** Maximum sane node count accepted from an untrusted file (16 M nodes ~ 192 MB). */
    public static final int MAX_SAFE_NODE_COUNT = 16_000_000;

    /** Maximum sane far-pointer count accepted from an untrusted file. */
    public static final int MAX_SAFE_FAR_PTR_COUNT = 16_000_000;

    /**
     * Detect the version of an ESVO file
     */
    public static int detectVersion(Path file) throws IOException {
        return SparseVoxelIOUtils.detectVersion(file, MAGIC_NUMBER);
    }

    /**
     * File header structure.  Fields present in all versions: magic, version,
     * nodeCount, reserved.  V2+ adds metadataOffset/metadataSize.
     * V3+ adds farPtrCount and reserved2.
     */
    public static class Header {
        public int magic;
        public int version;
        /** Sparse count (V3+) or max-index+1 (V1/V2 legacy). */
        public int nodeCount;
        public int reserved;
        public long metadataOffset; // v2+
        public long metadataSize;   // v2+
        public int farPtrCount;     // v3+
        public int reserved2;       // v3+

        public Header() {
            this.magic = MAGIC_NUMBER;
            this.version = VERSION_3;
        }

        public int getHeaderSize() {
            if (version >= VERSION_3) return HEADER_SIZE_V3;
            if (version >= VERSION_2) return HEADER_SIZE_V2;
            return HEADER_SIZE_V1;
        }
    }
}