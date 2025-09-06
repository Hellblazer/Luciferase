package com.hellblazer.luciferase.esvo.io;

import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;

import java.io.IOException;
import java.io.FileInputStream;
import java.io.BufferedInputStream;
import java.io.FileOutputStream;
import java.io.BufferedOutputStream;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

/**
 * Compressed deserializer for ESVO octree data using GZIP
 */
public class ESVOCompressedDeserializer {
    
    private final ESVODeserializer deserializer;
    
    public ESVOCompressedDeserializer() {
        this.deserializer = new ESVODeserializer();
    }
    
    /**
     * Deserialize octree data from compressed file
     */
    public ESVOOctreeData deserialize(Path inputFile) throws IOException {
        // Create a temporary file for decompressed data
        Path tempFile = inputFile.getParent().resolve(inputFile.getFileName() + ".decompressed.tmp");
        
        try {
            // Decompress to temporary file
            try (FileInputStream fis = new FileInputStream(inputFile.toFile());
                 BufferedInputStream bis = new BufferedInputStream(fis);
                 GZIPInputStream gzis = new GZIPInputStream(bis);
                 FileOutputStream fos = new FileOutputStream(tempFile.toFile());
                 BufferedOutputStream bos = new BufferedOutputStream(fos)) {
                
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = gzis.read(buffer)) != -1) {
                    bos.write(buffer, 0, bytesRead);
                }
            }
            
            // Deserialize from temporary file
            return deserializer.deserialize(tempFile);
            
        } finally {
            // Clean up temporary file
            tempFile.toFile().delete();
        }
    }
}