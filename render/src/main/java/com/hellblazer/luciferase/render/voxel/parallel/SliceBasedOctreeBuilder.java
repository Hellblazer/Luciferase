package com.hellblazer.luciferase.render.voxel.parallel;

import com.hellblazer.luciferase.render.voxel.core.EnhancedVoxelOctreeNode;
import com.hellblazer.luciferase.render.voxel.quality.QualityController;
import com.hellblazer.luciferase.render.voxel.quality.QualityController.VoxelData;
import com.hellblazer.luciferase.render.voxel.quality.QualityController.QualityMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Vector3f;
import javax.vecmath.Color3f;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Multi-threaded slice-based octree builder for ESVO-style voxelization.
 * 
 * Implements parallel octree construction using:
 * - Slice-based decomposition for better cache locality
 * - Quality-driven subdivision using error metrics
 * - Work-stealing for load balancing
 * - Memory pool management for reduced GC pressure
 * 
 * Based on NVIDIA ESVO architecture with adaptive quality control.
 */
public class SliceBasedOctreeBuilder implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(SliceBasedOctreeBuilder.class);
    
    // Configuration
    private final QualityController qualityController;
    private final int threadPoolSize;
    private final int maxSlicesPerTask;
    private final int minVoxelsPerSlice;
    
    // Threading infrastructure
    private final ForkJoinPool executorService;
    private final ExecutorCompletionService<SliceResult> completionService;
    
    // Performance tracking
    private final AtomicLong totalProcessedVoxels = new AtomicLong(0);
    private final AtomicLong totalNodesCreated = new AtomicLong(0);
    private final AtomicLong totalSubdivisions = new AtomicLong(0);
    
    // Memory management
    private final ObjectPool<VoxelData> voxelDataPool;
    private final ObjectPool<SliceTask> taskPool;
    
    public SliceBasedOctreeBuilder(QualityMetrics qualityMetrics) {
        this(qualityMetrics, Runtime.getRuntime().availableProcessors(), 64, 1000);
    }
    
    public SliceBasedOctreeBuilder(QualityMetrics qualityMetrics, 
                                  int threadPoolSize,
                                  int maxSlicesPerTask,
                                  int minVoxelsPerSlice) {
        this.qualityController = new QualityController(qualityMetrics);
        this.threadPoolSize = threadPoolSize;
        this.maxSlicesPerTask = maxSlicesPerTask;
        this.minVoxelsPerSlice = minVoxelsPerSlice;
        
        // Create work-stealing thread pool for better load balancing
        this.executorService = new ForkJoinPool(threadPoolSize, 
            ForkJoinPool.defaultForkJoinWorkerThreadFactory,
            null, true);
        this.completionService = new ExecutorCompletionService<>(executorService);
        
        // Initialize object pools for memory management
        this.voxelDataPool = new ObjectPool<>(() -> new VoxelData(1.0f), 1000);
        this.taskPool = new ObjectPool<>(SliceTask::new, threadPoolSize * 2);
        
        log.info("Initialized SliceBasedOctreeBuilder with {} threads, {} max slices per task", 
                threadPoolSize, maxSlicesPerTask);
    }
    
    /**
     * Build octree from dense voxel grid using parallel slice processing.
     */
    public EnhancedVoxelOctreeNode buildOctree(int[] denseGrid,
                                              float[][] voxelColors,
                                              int gridSize,
                                              float[] boundsMin,
                                              float[] boundsMax) {
        long startTime = System.nanoTime();
        resetStatistics();
        
        log.debug("Starting parallel octree build for {}^3 grid", gridSize);
        
        // Create root node
        var root = new EnhancedVoxelOctreeNode(boundsMin, boundsMax, 0, 0);
        
        // Calculate octree depth
        int maxDepth = (int) Math.ceil(Math.log(gridSize) / Math.log(2));
        
        // Decompose grid into slices for parallel processing
        var sliceTasks = createSliceTasks(denseGrid, voxelColors, gridSize, 
                                         boundsMin, boundsMax, maxDepth);
        
        // Submit all slice tasks
        for (var task : sliceTasks) {
            completionService.submit(task);
        }
        
        // Collect results and build octree structure
        var sliceResults = new ArrayList<SliceResult>();
        for (int i = 0; i < sliceTasks.size(); i++) {
            try {
                var result = completionService.take().get();
                sliceResults.add(result);
            } catch (InterruptedException | ExecutionException e) {
                log.error("Failed to complete slice task", e);
                Thread.currentThread().interrupt();
                return root;
            }
        }
        
        // Merge slice results into final octree
        mergeSliceResults(root, sliceResults, gridSize, boundsMin, boundsMax);
        
        // Clean up tasks
        for (var task : sliceTasks) {
            taskPool.release(task);
        }
        
        long buildTime = System.nanoTime() - startTime;
        logBuildStatistics(buildTime, gridSize);
        
        return root;
    }
    
    /**
     * Create slice tasks for parallel processing.
     */
    private List<SliceTask> createSliceTasks(int[] denseGrid,
                                           float[][] voxelColors,
                                           int gridSize,
                                           float[] boundsMin,
                                           float[] boundsMax,
                                           int maxDepth) {
        var tasks = new ArrayList<SliceTask>();
        
        // Determine slice axis based on grid characteristics
        // For now, slice along Z-axis for better memory locality
        int slicesPerTask = Math.min(maxSlicesPerTask, 
                           Math.max(1, gridSize / threadPoolSize));
        
        for (int startZ = 0; startZ < gridSize; startZ += slicesPerTask) {
            int endZ = Math.min(startZ + slicesPerTask, gridSize);
            
            var task = taskPool.acquire();
            task.initialize(denseGrid, voxelColors, gridSize,
                          boundsMin, boundsMax, maxDepth,
                          startZ, endZ, qualityController, voxelDataPool);
            tasks.add(task);
        }
        
        log.debug("Created {} slice tasks, {} slices per task", tasks.size(), slicesPerTask);
        return tasks;
    }
    
    /**
     * Merge slice results into final octree structure.
     */
    private void mergeSliceResults(EnhancedVoxelOctreeNode root,
                                  List<SliceResult> sliceResults,
                                  int gridSize,
                                  float[] boundsMin,
                                  float[] boundsMax) {
        log.debug("Merging {} slice results", sliceResults.size());
        
        // Process all voxels from slice results
        for (var result : sliceResults) {
            for (var voxel : result.processedVoxels) {
                // Insert voxel into octree with quality-driven subdivision
                insertVoxelWithQuality(root, voxel.position, voxel.color, 
                                     voxel.qualityData, gridSize);
            }
        }
        
        // Compute average colors for internal nodes
        root.computeAverageColors();
        
        // Update statistics
        totalNodesCreated.addAndGet(root.getNodeCount());
    }
    
    /**
     * Insert voxel with quality-driven subdivision.
     */
    private void insertVoxelWithQuality(EnhancedVoxelOctreeNode node,
                                       float[] position,
                                       int color,
                                       VoxelData qualityData,
                                       int gridSize) {
        // Check if subdivision is needed based on quality metrics
        var qualityAnalysis = qualityController.analyzeQuality(qualityData);
        
        if (qualityAnalysis.needsSubdivision) {
            totalSubdivisions.incrementAndGet();
            
            // Calculate subdivision depth based on error severity
            int subdivisionDepth = qualityController.suggestSubdivisionDepth(qualityData);
            int targetDepth = Math.min(node.getDepth() + subdivisionDepth,
                                     (int) Math.ceil(Math.log(gridSize) / Math.log(2)));
            
            // Insert with target depth
            node.insertVoxel(position, color, targetDepth);
        } else {
            // Insert at current depth
            node.insertVoxel(position, color, node.getDepth() + 1);
        }
        
        totalProcessedVoxels.incrementAndGet();
    }
    
    /**
     * Reset performance statistics.
     */
    private void resetStatistics() {
        totalProcessedVoxels.set(0);
        totalNodesCreated.set(0);
        totalSubdivisions.set(0);
    }
    
    /**
     * Log build performance statistics.
     */
    private void logBuildStatistics(long buildTime, int gridSize) {
        double buildTimeMs = buildTime / 1_000_000.0;
        long voxelsProcessed = totalProcessedVoxels.get();
        long nodesCreated = totalNodesCreated.get();
        long subdivisions = totalSubdivisions.get();
        
        double voxelsPerMs = voxelsProcessed / buildTimeMs;
        double subdivisionRate = subdivisions / (double) voxelsProcessed * 100.0;
        
        log.info("Octree build completed in {:.2f}ms:", buildTimeMs);
        log.info("  Grid size: {}^3 ({} total voxels)", gridSize, gridSize * gridSize * gridSize);
        log.info("  Voxels processed: {} ({:.1f} voxels/ms)", voxelsProcessed, voxelsPerMs);
        log.info("  Nodes created: {}", nodesCreated);
        log.info("  Quality subdivisions: {} ({:.1f}%)", subdivisions, subdivisionRate);
        log.info("  Threads used: {}", threadPoolSize);
    }
    
    /**
     * Get current build statistics.
     */
    public BuildStatistics getStatistics() {
        return new BuildStatistics(
            totalProcessedVoxels.get(),
            totalNodesCreated.get(),
            totalSubdivisions.get(),
            threadPoolSize
        );
    }
    
    /**
     * Shutdown the builder and release resources.
     */
    public void shutdown() {
        log.info("Shutting down SliceBasedOctreeBuilder");
        executorService.shutdown();
        
        try {
            if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("Executor did not terminate gracefully, forcing shutdown");
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            log.warn("Interrupted while waiting for executor termination");
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Implementation of AutoCloseable interface - calls shutdown().
     */
    @Override
    public void close() {
        shutdown();
    }
    
    /**
     * Build statistics container.
     */
    public static class BuildStatistics {
        public final long voxelsProcessed;
        public final long nodesCreated;
        public final long qualitySubdivisions;
        public final int threadsUsed;
        
        public BuildStatistics(long voxelsProcessed, long nodesCreated, 
                             long qualitySubdivisions, int threadsUsed) {
            this.voxelsProcessed = voxelsProcessed;
            this.nodesCreated = nodesCreated;
            this.qualitySubdivisions = qualitySubdivisions;
            this.threadsUsed = threadsUsed;
        }
        
        @Override
        public String toString() {
            return String.format("BuildStats{voxels=%d, nodes=%d, subdivisions=%d, threads=%d}",
                               voxelsProcessed, nodesCreated, qualitySubdivisions, threadsUsed);
        }
    }
    
    /**
     * Simple object pool for memory management.
     */
    public static class ObjectPool<T> {
        private final ConcurrentLinkedQueue<T> pool = new ConcurrentLinkedQueue<>();
        private final java.util.function.Supplier<T> factory;
        private final int maxSize;
        private final AtomicLong created = new AtomicLong(0);
        
        public ObjectPool(java.util.function.Supplier<T> factory, int maxSize) {
            this.factory = factory;
            this.maxSize = maxSize;
        }
        
        public T acquire() {
            T obj = pool.poll();
            if (obj == null) {
                obj = factory.get();
                created.incrementAndGet();
            }
            return obj;
        }
        
        public void release(T obj) {
            if (pool.size() < maxSize) {
                pool.offer(obj);
            }
        }
        
        public long getCreatedCount() { return created.get(); }
        public int getPooledCount() { return pool.size(); }
    }
}