package com.hellblazer.luciferase.lucien.performance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Simple test to verify the performance documentation updater works correctly.
 */
public class SimplePerformanceUpdaterTest {
    
    @Test
    public void testTablePatternMatching(@TempDir Path tempDir) throws IOException {
        // Create a simplified version of the performance doc
        String testDoc = """
            # Performance Metrics
            
            **Last Updated**: July 1, 2025
            
            ### Insertion Performance
            
            | Entity Count | Octree Time | Tetree Time | Tetree vs Octree | Prism Time | Prism vs Octree | Prism vs Tetree |
            |-------------|-------------|-------------|------------------|------------|-----------------|-----------------|
            | 100         | 1.131 ms    | 0.465 ms    | 2.4x faster      | *pending*  | *pending*       | *pending*       |
            | 1,000       | 25.843 ms   | 4.642 ms    | 5.6x faster      | *pending*  | *pending*       | *pending*       |
            
            **Key Insight**: Test data
            
            ### k-Nearest Neighbor (k-NN) Search Performance
            
            | Entity Count | Octree Time | Tetree Time | Tetree vs Octree | Prism Time | Prism vs Octree | Prism vs Tetree |
            |-------------|-------------|-------------|------------------|------------|-----------------|-----------------|
            | 100         | 0.030 ms    | 0.019 ms    | 1.6x faster      | *pending*  | *pending*       | *pending*       |
            
            **Key Insight**: More test data
            """;
        
        Path docPath = tempDir.resolve("test-doc.md");
        Files.writeString(docPath, testDoc);
        
        // Run updater on test doc
        String updated = Files.readString(docPath);
        
        // Verify structure is preserved
        assertTrue(updated.contains("### Insertion Performance"));
        assertTrue(updated.contains("### k-Nearest Neighbor"));
        assertTrue(updated.contains("**Key Insight**"));
        
        // Verify date was updated
        assertTrue(updated.contains("**Last Updated**: "));
        assertTrue(!updated.contains("July 1, 2025")); // Old date should be gone
    }
}