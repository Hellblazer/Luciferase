package com.hellblazer.luciferase.esvo.io;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;

/**
 * File format definitions and utilities for ESVO serialization
 */
public class ESVOFileFormat {
    
    // Magic number: "ESVO" in ASCII
    public static final int MAGIC_NUMBER = 0x4553564F;
    
    // File format versions
    public static final int VERSION_1 = 1;
    public static final int VERSION_2 = 2;
    
    // Header sizes
    public static final int HEADER_SIZE_V1 = 16; // magic(4) + version(4) + nodeCount(4) + reserved(4)
    public static final int HEADER_SIZE_V2 = 32; // v1 + metadataOffset(8) + metadataSize(8)
    
    /**
     * Detect the version of an ESVO file
     */
    public static int detectVersion(Path file) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            // Read as little-endian to match serialization
            byte[] magicBytes = new byte[4];
            raf.read(magicBytes);
            int magic = (magicBytes[0] & 0xFF) | 
                       ((magicBytes[1] & 0xFF) << 8) |
                       ((magicBytes[2] & 0xFF) << 16) |
                       ((magicBytes[3] & 0xFF) << 24);
            
            if (magic != MAGIC_NUMBER) {
                throw new IOException("Invalid ESVO file: bad magic number");
            }
            
            // Read version as little-endian
            byte[] versionBytes = new byte[4];
            raf.read(versionBytes);
            int version = (versionBytes[0] & 0xFF) | 
                         ((versionBytes[1] & 0xFF) << 8) |
                         ((versionBytes[2] & 0xFF) << 16) |
                         ((versionBytes[3] & 0xFF) << 24);
            
            return version;
        }
    }
    
    /**
     * File header structure
     */
    public static class Header {
        public int magic;
        public int version;
        public int nodeCount;
        public int reserved;
        public long metadataOffset; // v2 only
        public long metadataSize;   // v2 only
        
        public Header() {
            this.magic = MAGIC_NUMBER;
            this.version = VERSION_2;
        }
        
        public int getHeaderSize() {
            return version >= VERSION_2 ? HEADER_SIZE_V2 : HEADER_SIZE_V1;
        }
    }
}