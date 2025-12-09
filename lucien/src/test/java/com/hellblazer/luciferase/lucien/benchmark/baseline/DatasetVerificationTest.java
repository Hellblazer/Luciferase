package com.hellblazer.luciferase.lucien.benchmark.baseline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verification tests for baseline datasets
 * <p>
 * Bead 0.4: Establish Baseline Dataset Library
 * <p>
 * Tests:
 * - Dataset file existence
 * - Dataset loading/reading
 * - Data integrity
 * - Seeded random reproducibility
 */
class DatasetVerificationTest {
    
    private static final Path DATASET_DIR = Path.of("src/test/resources/datasets/baseline");
    
    @Test
    @DisplayName("Verify all baseline dataset files exist")
    void testDatasetFilesExist() {
        // Skip if dataset directory doesn't exist (datasets excluded from git via .gitignore)
        assumeTrue(Files.exists(DATASET_DIR), "Dataset directory not found - datasets are generated locally and excluded from git");
        
        // Verify all 4 baseline datasets exist
        Path smallSparse10 = DATASET_DIR.resolve("small_sparse_10.dataset");
        Path smallSparse50 = DATASET_DIR.resolve("small_sparse_50.dataset");
        Path mediumSparse10 = DATASET_DIR.resolve("medium_sparse_10.dataset");
        Path mediumSparse50 = DATASET_DIR.resolve("medium_sparse_50.dataset");
        
        assertTrue(Files.exists(smallSparse10), "small_sparse_10.dataset should exist");
        assertTrue(Files.exists(smallSparse50), "small_sparse_50.dataset should exist");
        assertTrue(Files.exists(mediumSparse10), "medium_sparse_10.dataset should exist");
        assertTrue(Files.exists(mediumSparse50), "medium_sparse_50.dataset should exist");
        
        // Verify files are not empty
        assertDoesNotThrow(() -> {
            assertTrue(Files.size(smallSparse10) > 0, "small_sparse_10.dataset should not be empty");
            assertTrue(Files.size(smallSparse50) > 0, "small_sparse_50.dataset should not be empty");
            assertTrue(Files.size(mediumSparse10) > 0, "medium_sparse_10.dataset should not be empty");
            assertTrue(Files.size(mediumSparse50) > 0, "medium_sparse_50.dataset should not be empty");
        });
    }
    
    @Test
    @DisplayName("Verify small_sparse_10 dataset integrity")
    void testSmallSparse10Integrity() throws IOException {
        Path datasetPath = DATASET_DIR.resolve("small_sparse_10.dataset");
        assumeTrue(Files.exists(datasetPath), "Dataset file not found - generate locally with DatasetGenerator");
        List<DatasetGenerator.Entity> entities = DatasetGenerator.readDataset(datasetPath);
        
        // Verify entity count matches spec
        assertEquals(10_737_418, entities.size(), "small_sparse_10 should have ~10.7M entities");
        
        // Verify grid bounds for sample entities
        for (int i = 0; i < Math.min(1000, entities.size()); i += 100) {
            DatasetGenerator.Entity entity = entities.get(i);
            assertTrue(entity.position.x >= 0 && entity.position.x < 1024, "x coordinate within grid");
            assertTrue(entity.position.y >= 0 && entity.position.y < 1024, "y coordinate within grid");
            assertTrue(entity.position.z >= 0 && entity.position.z < 1024, "z coordinate within grid");
        }
        
        // Verify entity IDs are sequential
        for (int i = 0; i < Math.min(1000, entities.size()); i++) {
            assertEquals(i, entities.get(i).id, "Entity IDs should be sequential");
        }
    }
    
    @Test
    @DisplayName("Verify small_sparse_50 dataset integrity")
    void testSmallSparse50Integrity() throws IOException {
        Path datasetPath = DATASET_DIR.resolve("small_sparse_50.dataset");
        assumeTrue(Files.exists(datasetPath), "Dataset file not found - generate locally with DatasetGenerator");
        List<DatasetGenerator.Entity> entities = DatasetGenerator.readDataset(datasetPath);
        
        // Verify entity count matches spec
        assertEquals(32_212_254, entities.size(), "small_sparse_50 should have ~32.2M entities");
        
        // Verify grid bounds for sample entities
        for (int i = 0; i < Math.min(1000, entities.size()); i += 100) {
            DatasetGenerator.Entity entity = entities.get(i);
            assertTrue(entity.position.x >= 0 && entity.position.x < 1024, "x coordinate within grid");
            assertTrue(entity.position.y >= 0 && entity.position.y < 1024, "y coordinate within grid");
            assertTrue(entity.position.z >= 0 && entity.position.z < 1024, "z coordinate within grid");
        }
    }
    
    @Test
    @DisplayName("Verify medium_sparse_10 dataset integrity")
    void testMediumSparse10Integrity() throws IOException {
        Path datasetPath = DATASET_DIR.resolve("medium_sparse_10.dataset");
        assumeTrue(Files.exists(datasetPath), "Dataset file not found - generate locally with DatasetGenerator");
        List<DatasetGenerator.Entity> entities = DatasetGenerator.readDataset(datasetPath);
        
        // Verify entity count matches spec
        assertEquals(10_737_418, entities.size(), "medium_sparse_10 should have ~10.7M entities");
        
        // Verify grid bounds for sample entities (larger grid: 2048³)
        for (int i = 0; i < Math.min(1000, entities.size()); i += 100) {
            DatasetGenerator.Entity entity = entities.get(i);
            assertTrue(entity.position.x >= 0 && entity.position.x < 2048, "x coordinate within grid");
            assertTrue(entity.position.y >= 0 && entity.position.y < 2048, "y coordinate within grid");
            assertTrue(entity.position.z >= 0 && entity.position.z < 2048, "z coordinate within grid");
        }
    }
    
    @Test
    @DisplayName("Verify medium_sparse_50 dataset integrity")
    void testMediumSparse50Integrity() throws IOException {
        Path datasetPath = DATASET_DIR.resolve("medium_sparse_50.dataset");
        assumeTrue(Files.exists(datasetPath), "Dataset file not found - generate locally with DatasetGenerator");
        List<DatasetGenerator.Entity> entities = DatasetGenerator.readDataset(datasetPath);
        
        // Verify entity count matches spec
        assertEquals(32_212_254, entities.size(), "medium_sparse_50 should have ~32.2M entities");
        
        // Verify grid bounds for sample entities (larger grid: 2048³)
        for (int i = 0; i < Math.min(1000, entities.size()); i += 100) {
            DatasetGenerator.Entity entity = entities.get(i);
            assertTrue(entity.position.x >= 0 && entity.position.x < 2048, "x coordinate within grid");
            assertTrue(entity.position.y >= 0 && entity.position.y < 2048, "y coordinate within grid");
            assertTrue(entity.position.z >= 0 && entity.position.z < 2048, "z coordinate within grid");
        }
    }
    
    @Test
    @DisplayName("Verify seeded random reproducibility")
    void testSeededRandomReproducibility() {
        // Generate datasets in memory with same seed
        List<DatasetGenerator.Entity> entities1 = DatasetGenerator.generateDataset(
            new DatasetGenerator.DatasetSpec("test", 128, 1.0, 99999L)
        );
        List<DatasetGenerator.Entity> entities2 = DatasetGenerator.generateDataset(
            new DatasetGenerator.DatasetSpec("test", 128, 1.0, 99999L)
        );
        
        // Verify same number of entities
        assertEquals(entities1.size(), entities2.size(), "Same seed should produce same entity count");
        
        // Verify positions are identical
        for (int i = 0; i < entities1.size(); i++) {
            DatasetGenerator.Entity e1 = entities1.get(i);
            DatasetGenerator.Entity e2 = entities2.get(i);
            
            assertEquals(e1.id, e2.id, "Entity IDs should match");
            assertEquals(e1.position.x, e2.position.x, 0.0001f, "X coordinates should match");
            assertEquals(e1.position.y, e2.position.y, 0.0001f, "Y coordinates should match");
            assertEquals(e1.position.z, e2.position.z, 0.0001f, "Z coordinates should match");
        }
    }
    
    @Test
    @DisplayName("Verify different seeds produce different datasets")
    void testDifferentSeedsProduceDifferentData() {
        // Generate datasets with different seeds
        List<DatasetGenerator.Entity> entities1 = DatasetGenerator.generateDataset(
            new DatasetGenerator.DatasetSpec("test1", 128, 1.0, 11111L)
        );
        List<DatasetGenerator.Entity> entities2 = DatasetGenerator.generateDataset(
            new DatasetGenerator.DatasetSpec("test2", 128, 1.0, 22222L)
        );
        
        // Verify same count (same grid size and sparsity)
        assertEquals(entities1.size(), entities2.size(), "Same specs should produce same entity count");
        
        // Verify positions are different for at least some entities
        int differentCount = 0;
        for (int i = 0; i < entities1.size(); i++) {
            DatasetGenerator.Entity e1 = entities1.get(i);
            DatasetGenerator.Entity e2 = entities2.get(i);
            
            if (Math.abs(e1.position.x - e2.position.x) > 0.0001f ||
                Math.abs(e1.position.y - e2.position.y) > 0.0001f ||
                Math.abs(e1.position.z - e2.position.z) > 0.0001f) {
                differentCount++;
            }
        }
        
        // At least 99% of positions should be different with different seeds
        assertTrue(differentCount > entities1.size() * 0.99, 
            "Different seeds should produce different positions (found " + differentCount + " different out of " + entities1.size() + ")");
    }
    
    @Test
    @DisplayName("Verify write-read round trip preserves data")
    void testWriteReadRoundTrip() throws IOException {
        // Generate small dataset
        DatasetGenerator.DatasetSpec spec = new DatasetGenerator.DatasetSpec("roundtrip_test", 64, 1.0, 55555L);
        List<DatasetGenerator.Entity> originalEntities = DatasetGenerator.generateDataset(spec);
        
        // Write to temp file
        Path tempFile = Files.createTempFile("dataset_test_", ".dataset");
        try {
            DatasetGenerator.writeDataset(spec, originalEntities, tempFile);
            
            // Read back
            List<DatasetGenerator.Entity> readEntities = DatasetGenerator.readDataset(tempFile);
            
            // Verify identical
            assertEquals(originalEntities.size(), readEntities.size(), "Entity count should match");
            
            for (int i = 0; i < originalEntities.size(); i++) {
                DatasetGenerator.Entity orig = originalEntities.get(i);
                DatasetGenerator.Entity read = readEntities.get(i);
                
                assertEquals(orig.id, read.id, "Entity ID should match at index " + i);
                assertEquals(orig.position.x, read.position.x, 0.0001f, "X coordinate should match at index " + i);
                assertEquals(orig.position.y, read.position.y, 0.0001f, "Y coordinate should match at index " + i);
                assertEquals(orig.position.z, read.position.z, 0.0001f, "Z coordinate should match at index " + i);
                assertEquals(orig.data, read.data, "Data string should match at index " + i);
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }
}
