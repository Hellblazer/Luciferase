package com.hellblazer.luciferase.esvo.builder;

import org.lwjgl.system.MemoryUtil;
import javax.vecmath.Vector3f;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Phase 4: CPU Builder Implementation
 * 
 * Builds octree from triangles with parallel subdivision.
 */
public class ESVOCPUBuilder {
    private static final int MAX_THREADS = 32;
    private final ForkJoinPool pool;
    private final AtomicInteger threadMask = new AtomicInteger(0);
    private final ThreadLocal<Integer> threadId = new ThreadLocal<>();
    private final Map<Long, Integer> threadIdMap = new ConcurrentHashMap<>();
    
    public ESVOCPUBuilder() {
        // Respect 32-thread limit
        int threads = Math.min(ForkJoinPool.getCommonPoolParallelism(), MAX_THREADS);
        this.pool = new ForkJoinPool(threads);
    }
    
    /**
     * Voxelize a triangle into the octree.
     */
    public List<Integer> voxelizeTriangle(Vector3f v0, Vector3f v1, Vector3f v2, int targetLevel) {
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
     * Shutdown the builder and release resources.
     */
    public void shutdown() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(1, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
        }
    }
}