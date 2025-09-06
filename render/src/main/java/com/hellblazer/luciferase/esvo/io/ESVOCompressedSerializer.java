package com.hellblazer.luciferase.esvo.io;

import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;

import java.io.IOException;
import java.io.FileOutputStream;
import java.io.BufferedOutputStream;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

/**
 * Compressed serializer for ESVO octree data using GZIP
 */
public class ESVOCompressedSerializer {
    
    private final ESVOSerializer serializer;
    
    public ESVOCompressedSerializer() {
        this.serializer = new ESVOSerializer();
    }
    
    /**
     * Serialize octree data to compressed file
     */
    public void serialize(ESVOOctreeData octree, Path outputFile) throws IOException {
        // Create a temporary file for uncompressed data
        Path tempFile = outputFile.getParent().resolve(outputFile.getFileName() + ".tmp");
        
        try {
            // Serialize to temporary file
            serializer.serialize(octree, tempFile);
            
            // Compress the temporary file
            try (FileOutputStream fos = new FileOutputStream(outputFile.toFile());
                 BufferedOutputStream bos = new BufferedOutputStream(fos);
                 GZIPOutputStream gzos = new GZIPOutputStream(bos)) {
                
                byte[] buffer = new byte[8192];
                try (java.io.FileInputStream fis = new java.io.FileInputStream(tempFile.toFile());
                     java.io.BufferedInputStream bis = new java.io.BufferedInputStream(fis)) {
                    
                    int bytesRead;
                    while ((bytesRead = bis.read(buffer)) != -1) {
                        gzos.write(buffer, 0, bytesRead);
                    }
                }
            }
        } finally {
            // Clean up temporary file
            tempFile.toFile().delete();
        }
    }
}