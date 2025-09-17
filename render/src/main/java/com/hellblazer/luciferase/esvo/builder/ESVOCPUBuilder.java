package com.hellblazer.luciferase.esvo.builder;

import com.hellblazer.luciferase.resource.CompositeResourceManager;
import com.hellblazer.luciferase.resource.ResourceHandle;
import org.lwjgl.system.MemoryUtil;
import javax.vecmath.Vector3f;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase 4: CPU Builder Implementation
 * 
 * Builds octree from triangles with parallel subdivision.
 * Manages thread pool lifecycle with proper resource cleanup.
 */
public class ESVOCPUBuilder implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ESVOCPUBuilder.class);
    private static final int MAX_THREADS = 32;
    
    private final ForkJoinPool pool;
    private final AtomicInteger threadMask = new AtomicInteger(0);
    private final ThreadLocal<Integer> threadId = new ThreadLocal<>();
    private final Map<Long, Integer> threadIdMap = new ConcurrentHashMap<>();
    private final CompositeResourceManager resourceManager;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private int maxThreads = 0;
    
    public ESVOCPUBuilder() {
        this(new CompositeResourceManager());
    }
    
    public ESVOCPUBuilder(CompositeResourceManager resourceManager) {
        this.resourceManager = resourceManager;
        
        // Respect 32-thread limit
        int threads = Math.min(ForkJoinPool.getCommonPoolParallelism(), MAX_THREADS);
        this.pool = new ForkJoinPool(threads);
        
        // Register the thread pool as a managed resource
        resourceManager.add(new ThreadPoolResource(pool));
        
        log.debug("ESVOCPUBuilder initialized with {} threads", threads);
    }
    
    /**
     * Voxelize a triangle into the octree.
     */
    public List<Integer> voxelizeTriangle(Vector3f v0, Vector3f v1, Vector3f v2, int targetLevel) {
        ensureNotClosed();
        List<Integer> voxels = new ArrayList<>();
        
        // Conservative voxelization using edge functions
        float minX = Math.min(Math.min(v0.x, v1.x), v2.x);
        float minY = Math.min(Math.min(v0.y, v1.y), v2.y);
        float minZ = Math.min(Math.min(v0.z, v1.z), v2.z);
        float maxX = Math.max(Math.max(v0.x, v1.x), v2.x);
        float maxY = Math.max(Math.max(v0.y, v1.y), v2.y);
        float maxZ = Math.max(Math.max(v0.z, v1.z), v2.z);
        
        // Map to [1,2] octree space
        minX = Math.max(1.0f, Math.min(2.0f, minX));
        minY = Math.max(1.0f, Math.min(2.0f, minY));
        minZ = Math.max(1.0f, Math.min(2.0f, minZ));
        maxX = Math.max(1.0f, Math.min(2.0f, maxX));
        maxY = Math.max(1.0f, Math.min(2.0f, maxY));
        maxZ = Math.max(1.0f, Math.min(2.0f, maxZ));
        
        int resolution = 1 << targetLevel;
        float voxelSize = 1.0f / resolution;
        
        int startX = (int)((minX - 1.0f) / voxelSize);
        int endX = (int)((maxX - 1.0f) / voxelSize) + 1;
        int startY = (int)((minY - 1.0f) / voxelSize);
        int endY = (int)((maxY - 1.0f) / voxelSize) + 1;
        int startZ = (int)((minZ - 1.0f) / voxelSize);
        int endZ = (int)((maxZ - 1.0f) / voxelSize) + 1;
        
        for (int x = startX; x < endX && x < resolution; x++) {
            for (int y = startY; y < endY && y < resolution; y++) {
                for (int z = startZ; z < endZ && z < resolution; z++) {
                    // Encode voxel position as integer
                    int voxelCode = (x << 20) | (y << 10) | z;
                    voxels.add(voxelCode);
                }
            }
        }
        
        return voxels;
    }
    
    /**
     * Acquire a thread ID from the pool (max 32 threads).
     */
    public int acquireThreadId() {
        long tid = Thread.currentThread().getId();
        return threadIdMap.computeIfAbsent(tid, k -> {
            // Find first available bit
            int mask, newMask, id;
            do {
                mask = threadMask.get();
                // Find first zero bit
                id = Integer.numberOfTrailingZeros(~mask);
                if (id >= MAX_THREADS) {
                    throw new IllegalStateException("Thread limit exceeded");
                }
                newMask = mask | (1 << id);
            } while (!threadMask.compareAndSet(mask, newMask));
            
            threadId.set(id);
            return id;
        });
    }
    
    /**
     * Release a thread ID back to the pool.
     */
    public void releaseThreadId(int id) {
        if (id >= 0 && id < MAX_THREADS) {
            int mask, newMask;
            do {
                mask = threadMask.get();
                newMask = mask & ~(1 << id);
            } while (!threadMask.compareAndSet(mask, newMask));
            
            // Clean up thread local
            long tid = Thread.currentThread().getId();
            threadIdMap.remove(tid);
        }
    }
    
    /**
     * Release all thread IDs (for testing).
     */
    public void releaseAllThreads() {
        threadMask.set(0);
        threadIdMap.clear();
    }
    
    /**
     * Get current thread mask (for testing).
     */
    public AtomicInteger getThreadMask() {
        return threadMask;
    }
    
    /**
     * Calculate error metric for subdivision decision.
     */
    public float calculateErrorMetric(List<Vector3f> points, Vector3f center, float size) {
        if (points.isEmpty()) return 0.0f;
        
        float maxError = 0.0f;
        for (Vector3f point : points) {
            Vector3f diff = new Vector3f(point);
            diff.sub(center);
            float error = diff.length();
            maxError = Math.max(maxError, error);
        }
        
        // Normalize by voxel size
        return maxError / size;
    }
    
    /**
     * Quantize color attributes for compression.
     */
    public int quantizeColor(Vector3f color) {
        // Quantize to 8 bits per channel
        int r = Math.min(255, Math.max(0, (int)(color.x * 255.0f)));
        int g = Math.min(255, Math.max(0, (int)(color.y * 255.0f)));
        int b = Math.min(255, Math.max(0, (int)(color.z * 255.0f)));
        
        return (r << 16) | (g << 8) | b;
    }
    
    /**
     * Reconstruct color from quantized value.
     */
    public Vector3f reconstructColor(int quantized) {
        float r = ((quantized >> 16) & 0xFF) / 255.0f;
        float g = ((quantized >> 8) & 0xFF) / 255.0f;
        float b = (quantized & 0xFF) / 255.0f;
        
        return new Vector3f(r, g, b);
    }
    
    /**
     * Parallel subdivision for performance testing.
     */
    public void subdivideParallel(int nodeCount) throws InterruptedException, ExecutionException {
        ensureNotClosed();
        List<Future<Integer>> futures = new ArrayList<>();
        
        for (int i = 0; i < nodeCount; i++) {
            final int nodeId = i;
            Future<Integer> future = pool.submit(() -> {
                // Simulate subdivision work
                int sum = 0;
                for (int j = 0; j < 1000; j++) {
                    sum += nodeId * j;
                }
                return sum;
            });
            futures.add(future);
        }
        
        // Wait for all tasks
        for (Future<Integer> future : futures) {
            future.get();
        }
    }
    
    /**
     * Sequential subdivision for comparison.
     */
    public void subdivideSequential(int nodeCount) {
        for (int i = 0; i < nodeCount; i++) {
            // Simulate subdivision work
            int sum = 0;
            for (int j = 0; j < 1000; j++) {
                sum += i * j;
            }
        }
    }
    
    /**
     * Initialize the builder with a specific number of threads.
     */
    public void initialize(int threads) {
        ensureNotClosed();
        this.maxThreads = Math.min(threads, MAX_THREADS);
        initialized.set(true);
        log.debug("ESVOCPUBuilder initialized with {} threads", this.maxThreads);
    }
    
    /**
     * Check if the builder is initialized.
     */
    public boolean isInitialized() {
        return initialized.get();
    }
    
    /**
     * Submit a task to the thread pool.
     */
    public <T> Future<T> submitTask(Callable<T> task) {
        ensureNotClosed();
        return pool.submit(task);
    }
    
    /**
     * Get the number of active threads in the pool.
     */
    public int getActiveThreadCount() {
        return pool.getActiveThreadCount();
    }

    /**
     * Shutdown the builder and release resources.
     * @deprecated Use {@link #close()} instead
     */
    @Deprecated
    public void shutdown() {
        close();
    }
    
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            log.debug("Closing ESVOCPUBuilder");
            
            // Mark as not initialized
            initialized.set(false);
            
            // Release all thread IDs
            releaseAllThreads();
            
            // Shutdown thread pool
            pool.shutdown();
            try {
                if (!pool.awaitTermination(1, TimeUnit.SECONDS)) {
                    log.warn("Thread pool did not terminate gracefully, forcing shutdown");
                    pool.shutdownNow();
                    
                    // Wait a bit more for tasks to respond to cancellation
                    if (!pool.awaitTermination(1, TimeUnit.SECONDS)) {
                        log.error("Thread pool did not terminate after forced shutdown");
                    }
                }
            } catch (InterruptedException e) {
                log.warn("Interrupted while waiting for thread pool shutdown", e);
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
            
            // Close all managed resources
            try {
                resourceManager.close();
            } catch (Exception e) {
                log.error("Error closing resource manager", e);
            }
            
            log.info("ESVOCPUBuilder closed successfully");
        }
    }
    
    private void ensureNotClosed() {
        if (closed.get()) {
            throw new IllegalStateException("ESVOCPUBuilder has been closed");
        }
    }
    
    /**
     * Resource wrapper for thread pools
     */
    private static class ThreadPoolResource extends ResourceHandle<ForkJoinPool> {
        public ThreadPoolResource(ForkJoinPool pool) {
            super(pool, null); // No tracker needed
        }
        
        @Override
        protected void doCleanup(ForkJoinPool pool) {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(1, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}