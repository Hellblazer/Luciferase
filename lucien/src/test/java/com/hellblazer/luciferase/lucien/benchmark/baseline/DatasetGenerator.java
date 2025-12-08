package com.hellblazer.luciferase.lucien.benchmark.baseline;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;

import javax.vecmath.Point3f;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generate standard reproducible datasets for benchmarking
 * <p>
 * Bead 0.4: Establish Baseline Dataset Library
 * <p>
 * Creates sparse voxel datasets with:
 * - Seeded random for reproducibility
 * - Configurable grid size and sparsity
 * - Binary format optimized for fast loading
 * <p>
 * File Format (.dataset):
 * - Header (40 bytes):
 *   - Magic: "LCFD" (4 bytes) - LuCiFerase Dataset
 *   - Version: 1 (4 bytes)
 *   - Entity count (4 bytes)
 *   - Grid size (4 bytes) - e.g., 1024 for 1024³
 *   - Sparsity percent (4 bytes float) - e.g., 1.0 for 1%, 0.125 for 0.125%
 *   - Seed (8 bytes)
 *   - Reserved (8 bytes)
 * - Entities (variable):
 *   - ID (8 bytes long)
 *   - Position (12 bytes: x, y, z floats)
 *   - Data length (4 bytes int)
 *   - Data (variable UTF-8 string bytes)
 */
public class DatasetGenerator {
    
    private static final int MAGIC_NUMBER = 0x4C434644; // "LCFD" in ASCII
    private static final int VERSION = 1;
    private static final int HEADER_SIZE = 40;
    
    /**
     * Dataset specification
     */
    public static class DatasetSpec {
        public final String name;
        public final int gridSize;
        public final double sparsityPercent;
        public final long seed;
        
        public DatasetSpec(String name, int gridSize, double sparsityPercent, long seed) {
            this.name = name;
            this.gridSize = gridSize;
            this.sparsityPercent = sparsityPercent;
            this.seed = seed;
        }
        
        /**
         * Calculate total voxels in grid
         */
        public long totalVoxels() {
            return (long) gridSize * gridSize * gridSize;
        }
        
        /**
         * Calculate number of filled voxels based on sparsity
         */
        public int filledVoxels() {
            return (int) (totalVoxels() * sparsityPercent / 100.0);
        }
        
        /**
         * Estimate file size in bytes
         */
        public long estimatedSizeBytes() {
            // Header + entities (8 + 12 + 4 + ~10 bytes avg data per entity)
            return HEADER_SIZE + (long) filledVoxels() * (8 + 12 + 4 + 10);
        }
        
        public String estimatedSizeMB() {
            return String.format("%.2f MB", estimatedSizeBytes() / (1024.0 * 1024.0));
        }
    }
    
    /**
     * Standard dataset specifications for Epic 0
     * Note: Percentages represent sparse sampling rates, not dense grid fills
     * Sized for practical benchmarking within reasonable memory limits
     * Grid volume scales as size³, so sparsity must scale inversely to maintain entity counts
     */
    public static final DatasetSpec SMALL_SPARSE_10 = new DatasetSpec(
        "small_sparse_10", 1024, 1.0, 12345L  // ~10.7M entities, ~364MB (DONE)
    );
    
    public static final DatasetSpec SMALL_SPARSE_50 = new DatasetSpec(
        "small_sparse_50", 1024, 3.0, 12345L  // ~32.2M entities, ~1.1GB
    );
    
    public static final DatasetSpec MEDIUM_SPARSE_10 = new DatasetSpec(
        "medium_sparse_10", 2048, 0.125, 67890L  // ~10.7M entities, ~364MB (same count, larger grid)
    );
    
    public static final DatasetSpec MEDIUM_SPARSE_50 = new DatasetSpec(
        "medium_sparse_50", 2048, 0.375, 67890L  // ~32.2M entities, ~1.1GB (same count, larger grid)
    );
    
    // Deferred to Epic 4
    public static final DatasetSpec LARGE_SPARSE_10 = new DatasetSpec(
        "large_sparse_10", 4096, 10, 11111L
    );
    
    /**
     * All baseline datasets (Epic 0)
     * All datasets sized to fit in 6GB heap with reasonable generation time
     */
    public static final DatasetSpec[] BASELINE_DATASETS = {
        SMALL_SPARSE_10,
        SMALL_SPARSE_50,
        MEDIUM_SPARSE_10,
        MEDIUM_SPARSE_50
    };
    
    /**
     * Entity data class
     */
    public static class Entity {
        public final long id;
        public final Point3f position;
        public final String data;
        
        public Entity(long id, Point3f position, String data) {
            this.id = id;
            this.position = position;
            this.data = data;
        }
    }
    
    /**
     * Generate a dataset according to specification
     */
    public static List<Entity> generateDataset(DatasetSpec spec) {
        System.out.println("Generating dataset: " + spec.name);
        System.out.println("  Grid size: " + spec.gridSize + "³");
        System.out.println("  Total voxels: " + String.format("%,d", spec.totalVoxels()));
        System.out.println("  Sparsity: " + spec.sparsityPercent + "%");
        System.out.println("  Filled voxels: " + String.format("%,d", spec.filledVoxels()));
        System.out.println("  Seed: " + spec.seed);
        System.out.println("  Estimated size: " + spec.estimatedSizeMB());
        
        Random random = new Random(spec.seed);
        List<Entity> entities = new ArrayList<>(spec.filledVoxels());
        
        // Generate entities at random positions within grid
        for (int i = 0; i < spec.filledVoxels(); i++) {
            long id = i;
            
            // Random position within grid bounds [0, gridSize)
            float x = random.nextFloat() * spec.gridSize;
            float y = random.nextFloat() * spec.gridSize;
            float z = random.nextFloat() * spec.gridSize;
            Point3f position = new Point3f(x, y, z);
            
            // Entity data includes some variety for realistic size
            String data = "Entity_" + i + "_" + spec.name.substring(0, Math.min(5, spec.name.length()));
            
            entities.add(new Entity(id, position, data));
            
            // Progress reporting for large datasets
            if ((i + 1) % 100000 == 0) {
                System.out.print(".");
                if ((i + 1) % 1000000 == 0) {
                    System.out.println(" " + String.format("%,d", i + 1));
                }
            }
        }
        
        if (spec.filledVoxels() >= 100000) {
            System.out.println();
        }
        System.out.println("Generated " + String.format("%,d", entities.size()) + " entities");
        
        return entities;
    }
    
    /**
     * Write dataset to file
     */
    public static void writeDataset(DatasetSpec spec, List<Entity> entities, Path outputPath) throws IOException {
        System.out.println("Writing dataset to: " + outputPath);
        
        try (FileOutputStream fos = new FileOutputStream(outputPath.toFile());
             BufferedOutputStream bos = new BufferedOutputStream(fos, 1024 * 1024)) {
            
            // Write header
            ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE);
            header.order(ByteOrder.LITTLE_ENDIAN);
            
            header.putInt(MAGIC_NUMBER);
            header.putInt(VERSION);
            header.putInt(entities.size());
            header.putInt(spec.gridSize);
            header.putFloat((float) spec.sparsityPercent);
            header.putLong(spec.seed);
            header.putLong(0); // reserved
            
            bos.write(header.array());
            
            // Write entities
            int entitiesWritten = 0;
            for (Entity entity : entities) {
                // Entity format: id(8) + position(12) + data_len(4) + data(variable)
                byte[] dataBytes = entity.data.getBytes("UTF-8");
                
                ByteBuffer entityBuffer = ByteBuffer.allocate(8 + 12 + 4 + dataBytes.length);
                entityBuffer.order(ByteOrder.LITTLE_ENDIAN);
                
                entityBuffer.putLong(entity.id);
                entityBuffer.putFloat(entity.position.x);
                entityBuffer.putFloat(entity.position.y);
                entityBuffer.putFloat(entity.position.z);
                entityBuffer.putInt(dataBytes.length);
                entityBuffer.put(dataBytes);
                
                bos.write(entityBuffer.array());
                entitiesWritten++;
                
                // Progress reporting
                if (entitiesWritten % 100000 == 0) {
                    System.out.print(".");
                    if (entitiesWritten % 1000000 == 0) {
                        System.out.println(" " + String.format("%,d", entitiesWritten));
                    }
                }
            }
            
            if (entities.size() >= 100000) {
                System.out.println();
            }
        }
        
        long actualSize = Files.size(outputPath);
        System.out.println("Written " + String.format("%,d", entities.size()) + " entities");
        System.out.println("Actual file size: " + String.format("%.2f MB", actualSize / (1024.0 * 1024.0)));
    }
    
    /**
     * Read dataset from file
     */
    public static List<Entity> readDataset(Path inputPath) throws IOException {
        System.out.println("Reading dataset from: " + inputPath);
        
        try (FileInputStream fis = new FileInputStream(inputPath.toFile());
             BufferedInputStream bis = new BufferedInputStream(fis, 1024 * 1024)) {
            
            // Read header
            byte[] headerBytes = new byte[HEADER_SIZE];
            int headerRead = bis.read(headerBytes);
            if (headerRead != HEADER_SIZE) {
                throw new IOException("Failed to read complete header");
            }
            
            ByteBuffer header = ByteBuffer.wrap(headerBytes);
            header.order(ByteOrder.LITTLE_ENDIAN);
            
            int magic = header.getInt();
            if (magic != MAGIC_NUMBER) {
                throw new IOException("Invalid file format: bad magic number");
            }
            
            int version = header.getInt();
            if (version != VERSION) {
                throw new IOException("Unsupported dataset version: " + version);
            }
            
            int entityCount = header.getInt();
            int gridSize = header.getInt();
            float sparsity = header.getFloat();
            long seed = header.getLong();
            
            System.out.println("  Grid size: " + gridSize + "³");
            System.out.println("  Sparsity: " + sparsity + "%");
            System.out.println("  Entity count: " + String.format("%,d", entityCount));
            System.out.println("  Seed: " + seed);
            
            // Read entities
            List<Entity> entities = new ArrayList<>(entityCount);
            
            for (int i = 0; i < entityCount; i++) {
                // Read fixed-size portion
                byte[] entityHeaderBytes = new byte[8 + 12 + 4];
                int read = bis.read(entityHeaderBytes);
                if (read != entityHeaderBytes.length) {
                    throw new IOException("Failed to read entity " + i + " header");
                }
                
                ByteBuffer entityHeader = ByteBuffer.wrap(entityHeaderBytes);
                entityHeader.order(ByteOrder.LITTLE_ENDIAN);
                
                long id = entityHeader.getLong();
                float x = entityHeader.getFloat();
                float y = entityHeader.getFloat();
                float z = entityHeader.getFloat();
                int dataLength = entityHeader.getInt();
                
                // Read variable-size data
                byte[] dataBytes = new byte[dataLength];
                int dataRead = bis.read(dataBytes);
                if (dataRead != dataLength) {
                    throw new IOException("Failed to read entity " + i + " data");
                }
                
                String data = new String(dataBytes, "UTF-8");
                entities.add(new Entity(id, new Point3f(x, y, z), data));
                
                // Progress reporting
                if ((i + 1) % 100000 == 0) {
                    System.out.print(".");
                    if ((i + 1) % 1000000 == 0) {
                        System.out.println(" " + String.format("%,d", i + 1));
                    }
                }
            }
            
            if (entityCount >= 100000) {
                System.out.println();
            }
            System.out.println("Read " + String.format("%,d", entities.size()) + " entities");
            
            return entities;
        }
    }
    
    /**
     * Generate all baseline datasets
     */
    public static void generateAllBaseline(Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        
        System.out.println("=== GENERATING BASELINE DATASETS ===");
        System.out.println("Output directory: " + outputDir);
        System.out.println();
        
        for (DatasetSpec spec : BASELINE_DATASETS) {
            Path outputPath = outputDir.resolve(spec.name + ".dataset");
            
            if (Files.exists(outputPath)) {
                System.out.println("Skipping " + spec.name + " (already exists)");
                System.out.println();
                continue;
            }
            
            long startTime = System.currentTimeMillis();
            
            List<Entity> entities = generateDataset(spec);
            writeDataset(spec, entities, outputPath);
            
            long elapsedMs = System.currentTimeMillis() - startTime;
            System.out.println("Completed in " + String.format("%.2f", elapsedMs / 1000.0) + " seconds");
            System.out.println();
        }
        
        System.out.println("=== BASELINE DATASET GENERATION COMPLETE ===");
    }
    
    /**
     * Main method for standalone dataset generation
     */
    public static void main(String[] args) throws IOException {
        Path outputDir;
        
        if (args.length > 0) {
            outputDir = Path.of(args[0]);
        } else {
            // Default to lucien/src/test/resources/datasets/baseline/
            outputDir = Path.of("lucien/src/test/resources/datasets/baseline");
        }
        
        generateAllBaseline(outputDir);
    }
}
