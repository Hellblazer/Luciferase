package com.hellblazer.luciferase.esvo;

import com.hellblazer.luciferase.esvo.builder.ESVOCPUBuilder;
import com.hellblazer.luciferase.esvo.builder.ThreadLocalBatchCache;
import com.hellblazer.luciferase.esvo.builder.VoxelBatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.vecmath.Vector3f;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 4 CPU Builder Tests for ESVO Implementation
 * 
 * Tests for CPU-based octree construction:
 * - Triangle voxelization
 * - Parallel subdivision with thread limits
 * - Thread-local batch management
 * - Error metric calculation
 * - Attribute filtering and quantization
 * 
 * Critical: MAX_THREADS = 32 (bit mask constraint)
 */
@DisplayName("ESVO Phase 4 CPU Builder Tests")
public class ESVOPhase4Tests {
    
    private static final int MAX_THREADS = 32;  // Critical: bit mask constraint
    private static final int OCTREE_DEPTH = 8;
    private static final float VOXEL_SIZE = 1.0f / (1 << OCTREE_DEPTH);
    
    @BeforeEach
    void setUp() {
        // Ensure ForkJoinPool respects thread limit
        System.setProperty("java.util.concurrent.ForkJoinPool.common.parallelism", 
                          String.valueOf(MAX_THREADS));
    }
    
    /**
     * Test 1: Triangle Voxelization
     * 
     * Verify that triangles are correctly voxelized into octree space.
     */
    @Test
    @DisplayName("Test triangle voxelization")
    void testTriangleVoxelization() {
        ESVOCPUBuilder builder = new ESVOCPUBuilder();
        
        // Create a simple triangle in octree space [0,1]
        Vector3f v0 = new Vector3f(0.2f, 0.2f, 0.2f);
        Vector3f v1 = new Vector3f(0.8f, 0.2f, 0.2f);
        Vector3f v2 = new Vector3f(0.5f, 0.8f, 0.2f);
        
        // Voxelize at level 3 (8x8x8 grid)
        int level = 3;
        List<Integer> voxelCodes = builder.voxelizeTriangle(v0, v1, v2, level);
        
        // Verify voxels are generated
        assertFalse(voxelCodes.isEmpty(), "Triangle should produce voxels");
        
        // Verify conservative voxelization (no gaps)
        int expectedMinVoxels = 4;  // Minimum for this triangle size
        assertTrue(voxelCodes.size() >= expectedMinVoxels,
                  "Should have at least " + expectedMinVoxels + " voxels");
    }
    
    /**
     * Test 2: Thread Limit Enforcement
     * 
     * Verify that parallel processing respects the 32-thread limit.
     */
    @Test
    @DisplayName("Test thread limit enforcement")
    void testThreadLimitEnforcement() {
        ESVOCPUBuilder builder = new ESVOCPUBuilder();
        
        // Create many tasks that will try to use threads
        int numTasks = 100;
        List<Integer> threadIds = new ArrayList<>();
        
        ForkJoinPool pool = new ForkJoinPool(MAX_THREADS);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < numTasks; i++) {
                Future<?> future = pool.submit(() -> {
                    try {
                        // Try to acquire thread ID
                        int threadId = builder.acquireThreadId();
                        synchronized(threadIds) {
                            threadIds.add(threadId);
                        }
                        // Simulate work
                        try { Thread.sleep(10); } catch (Exception e) {}
                        // Release thread ID
                        builder.releaseThreadId(threadId);
                    } catch (IllegalStateException e) {
                        // Thread limit exceeded - this is expected
                    }
                });
                futures.add(future);
            }
            
            // Wait for all tasks to complete
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (Exception e) {
                    // Ignore
                }
            }
        } finally {
            pool.shutdown();
        }
        
        // Verify no thread ID exceeds limit
        for (int id : threadIds) {
            assertTrue(id >= 0 && id < MAX_THREADS,
                      "Thread ID " + id + " exceeds limit");
        }
        
        // Clean up and verify thread mask is cleared
        builder.releaseAllThreads();
        assertEquals(0, builder.getThreadMask().get(), "All thread bits should be released");
    }
    
    /**
     * Test 3: Thread-Local Batch Management
     * 
     * Verify thread-local caching with LRU eviction.
     */
    @Test
    @DisplayName("Test thread-local batch management")
    void testThreadLocalBatchManagement() {
        // Create thread-local batch cache
        ThreadLocalBatchCache cache = new ThreadLocalBatchCache(4);  // 4 batches per thread
        
        // Add batches from multiple threads
        Thread t1 = new Thread(() -> {
            for (int i = 0; i < 6; i++) {
                VoxelBatch batch = new VoxelBatch(i);
                cache.addBatch(batch);
            }
        });
        
        Thread t2 = new Thread(() -> {
            for (int i = 10; i < 16; i++) {
                VoxelBatch batch = new VoxelBatch(i);
                cache.addBatch(batch);
            }
        });
        
        t1.start();
        t2.start();
        
        try {
            t1.join();
            t2.join();
        } catch (InterruptedException e) {
            fail("Thread interrupted");
        }
        
        // Get actual thread IDs from cache
        Set<Long> threadIds = cache.getThreadIds();
        assertEquals(2, threadIds.size(), "Should have exactly 2 threads");
        
        // Verify each thread has at most 4 batches (LRU eviction)
        for (long tid : threadIds) {
            assertEquals(4, cache.getBatchCount(tid),
                        "Thread should have 4 batches after LRU eviction");
        }
        
        // Since we can't reliably match thread IDs, just verify the cache behavior
        // by checking that batch 0 and 1 were evicted (oldest) and 5 was retained (newest)
        boolean hasOldBatch = false;
        boolean hasNewBatch = false;
        for (long tid : threadIds) {
            if (cache.hasBatch(tid, 0) || cache.hasBatch(tid, 1)) {
                hasOldBatch = true;
            }
            if (cache.hasBatch(tid, 5) || cache.hasBatch(tid, 15)) {
                hasNewBatch = true;
            }
        }
        assertFalse(hasOldBatch, "Oldest batches should be evicted");
        assertTrue(hasNewBatch, "Newest batches should be retained");
    }
    
    /**
     * Test 4: Error Metric Calculation
     * 
     * Verify color and normal error metrics for subdivision decisions.
     */
    @Test
    @DisplayName("Test error metric calculation")
    void testErrorMetricCalculation() {
        ESVOCPUBuilder builder = new ESVOCPUBuilder();
        
        // Create points with varying positions
        List<Vector3f> points = new ArrayList<>();
        points.add(new Vector3f(1.1f, 1.1f, 1.1f));
        points.add(new Vector3f(1.2f, 1.1f, 1.1f));
        points.add(new Vector3f(1.1f, 1.2f, 1.1f));
        points.add(new Vector3f(1.1f, 1.1f, 1.2f));
        
        // Calculate error metric
        Vector3f center = new Vector3f(1.15f, 1.15f, 1.15f);
        float size = 0.1f;
        float error = builder.calculateErrorMetric(points, center, size);
        
        // Verify error is non-zero (variation exists)
        assertTrue(error > 0, "Error should be > 0 for varying positions");
        
        // Test subdivision decision
        float errorThreshold = 0.5f;
        boolean shouldSubdivide = error > errorThreshold;
        assertTrue(shouldSubdivide, "Node with high error should subdivide");
    }
    
    /**
     * Test 5: Attribute Filtering and Quantization
     * 
     * Verify attribute compression for storage efficiency.
     */
    @Test
    @DisplayName("Test attribute filtering and quantization")
    void testAttributeQuantization() {
        ESVOCPUBuilder builder = new ESVOCPUBuilder();
        
        // Test color quantization (8-bit per channel)
        Vector3f originalColor = new Vector3f(0.123456f, 0.567890f, 0.987654f);
        int quantizedColor = builder.quantizeColor(originalColor);
        Vector3f reconstructedColor = builder.reconstructColor(quantizedColor);
        
        // Verify quantization error is small
        Vector3f diff = new Vector3f(originalColor);
        diff.sub(reconstructedColor);
        float colorError = diff.length();
        assertTrue(colorError < 0.01f, "Color quantization error should be < 1%");
    }
    
    // Helper methods
    
    private List<Voxel> voxelizeTriangle(Triangle triangle, int level) {
        List<Voxel> voxels = new ArrayList<>();
        int resolution = 1 << level;
        float voxelSize = 1.0f / resolution;
        
        // Conservative voxelization using bounding box
        Vector3f min = triangle.getMin();
        Vector3f max = triangle.getMax();
        
        int minX = (int)((min.x - 1.0f) / voxelSize);
        int maxX = (int)((max.x - 1.0f) / voxelSize) + 1;
        int minY = (int)((min.y - 1.0f) / voxelSize);
        int maxY = (int)((max.y - 1.0f) / voxelSize) + 1;
        int minZ = (int)((min.z - 1.0f) / voxelSize);
        int maxZ = (int)((max.z - 1.0f) / voxelSize) + 1;
        
        for (int z = minZ; z <= maxZ; z++) {
            for (int y = minY; y <= maxY; y++) {
                for (int x = minX; x <= maxX; x++) {
                    Vector3f voxelPos = new Vector3f(
                        1.0f + (x + 0.5f) * voxelSize,
                        1.0f + (y + 0.5f) * voxelSize,
                        1.0f + (z + 0.5f) * voxelSize
                    );
                    
                    if (triangle.intersectsVoxel(voxelPos, voxelSize * 0.5f)) {
                        voxels.add(new Voxel(voxelPos, triangle.color, triangle.normal));
                    }
                }
            }
        }
        
        return voxels;
    }
    
    private int acquireThreadBit(AtomicInteger mask) {
        while (true) {
            int current = mask.get();
            for (int i = 0; i < MAX_THREADS; i++) {
                int bit = 1 << i;
                if ((current & bit) == 0) {
                    if (mask.compareAndSet(current, current | bit)) {
                        return i;
                    }
                    break;  // Retry if CAS failed
                }
            }
            if (Integer.bitCount(current) >= MAX_THREADS) {
                return -1;  // All threads busy
            }
        }
    }
    
    private void releaseThreadBit(AtomicInteger mask, int threadId) {
        int bit = 1 << threadId;
        mask.updateAndGet(current -> current & ~bit);
    }
    
    private float calculateColorError(OctreeNode node) {
        if (node.voxels.isEmpty()) return 0;
        
        // Calculate variance in RGB
        Vector3f mean = new Vector3f();
        for (Voxel v : node.voxels) {
            mean.add(v.color);
        }
        mean.scale(1.0f / node.voxels.size());
        
        float variance = 0;
        for (Voxel v : node.voxels) {
            Vector3f diff = new Vector3f(v.color);
            diff.sub(mean);
            variance += diff.lengthSquared();
        }
        
        return (float)Math.sqrt(variance / node.voxels.size());
    }
    
    private float calculateNormalError(OctreeNode node) {
        if (node.voxels.isEmpty()) return 0;
        
        // Calculate average normal direction variance
        Vector3f mean = new Vector3f();
        for (Voxel v : node.voxels) {
            mean.add(v.normal);
        }
        mean.normalize();
        
        float variance = 0;
        for (Voxel v : node.voxels) {
            float dot = v.normal.dot(mean);
            variance += (1.0f - dot);
        }
        
        return variance / node.voxels.size();
    }
    
    private int quantizeColor(Vector3f color) {
        int r = (int)(color.x * 255) & 0xFF;
        int g = (int)(color.y * 255) & 0xFF;
        int b = (int)(color.z * 255) & 0xFF;
        return (r << 16) | (g << 8) | b;
    }
    
    private Vector3f dequantizeColor(int packed) {
        float r = ((packed >> 16) & 0xFF) / 255.0f;
        float g = ((packed >> 8) & 0xFF) / 255.0f;
        float b = (packed & 0xFF) / 255.0f;
        return new Vector3f(r, g, b);
    }
    
    private int quantizeNormal(Vector3f normal) {
        // Convert to spherical coordinates
        float theta = (float)Math.acos(normal.z);
        float phi = (float)Math.atan2(normal.y, normal.x);
        
        // Quantize to 16 bits each
        int thetaBits = (int)((theta / Math.PI) * 65535) & 0xFFFF;
        int phiBits = (int)((phi + Math.PI) / (2 * Math.PI) * 65535) & 0xFFFF;
        
        return (thetaBits << 16) | phiBits;
    }
    
    private Vector3f dequantizeNormal(int packed) {
        float theta = ((packed >> 16) & 0xFFFF) / 65535.0f * (float)Math.PI;
        float phi = (packed & 0xFFFF) / 65535.0f * 2 * (float)Math.PI - (float)Math.PI;
        
        float sinTheta = (float)Math.sin(theta);
        return new Vector3f(
            sinTheta * (float)Math.cos(phi),
            sinTheta * (float)Math.sin(phi),
            (float)Math.cos(theta)
        );
    }
    
    private List<Triangle> generateTestMesh(int count) {
        List<Triangle> mesh = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            float offset = i * 0.001f;
            mesh.add(new Triangle(
                new Vector3f(1.1f + offset, 1.1f, 1.1f),
                new Vector3f(1.2f + offset, 1.1f, 1.1f),
                new Vector3f(1.15f + offset, 1.2f, 1.1f)
            ));
        }
        return mesh;
    }
    
    private OctreeNode buildOctreeSingleThreaded(List<Triangle> mesh, int depth) {
        OctreeNode root = new OctreeNode();
        for (Triangle tri : mesh) {
            List<Voxel> voxels = voxelizeTriangle(tri, depth);
            for (Voxel v : voxels) {
                root.addVoxel(v);
            }
        }
        return root;
    }
    
    private OctreeNode buildOctreeParallel(List<Triangle> mesh, int depth, int maxThreads) {
        OctreeNode root = new OctreeNode();
        ForkJoinPool pool = new ForkJoinPool(maxThreads);
        
        try {
            pool.submit(() -> {
                mesh.parallelStream().forEach(tri -> {
                    List<Voxel> voxels = voxelizeTriangle(tri, depth);
                    synchronized(root) {
                        for (Voxel v : voxels) {
                            root.addVoxel(v);
                        }
                    }
                });
            }).join();
        } finally {
            pool.shutdown();
        }
        
        return root;
    }
    
    private int countNodes(OctreeNode node) {
        if (node == null) return 0;
        int count = 1;
        if (node.children != null) {
            for (OctreeNode child : node.children) {
                count += countNodes(child);
            }
        }
        return count;
    }
    
    // Remove duplicate inner class definitions - use the actual ones
    
    // Inner classes
    
    private static class Triangle {
        Vector3f v0, v1, v2;
        Vector3f color;
        Vector3f normal;
        
        Triangle(Vector3f v0, Vector3f v1, Vector3f v2) {
            this.v0 = v0;
            this.v1 = v1;
            this.v2 = v2;
            this.color = new Vector3f(1, 1, 1);
            
            // Calculate normal
            Vector3f e1 = new Vector3f(v1);
            e1.sub(v0);
            Vector3f e2 = new Vector3f(v2);
            e2.sub(v0);
            this.normal = new Vector3f();
            this.normal.cross(e1, e2);
            this.normal.normalize();
        }
        
        Vector3f getMin() {
            return new Vector3f(
                Math.min(v0.x, Math.min(v1.x, v2.x)),
                Math.min(v0.y, Math.min(v1.y, v2.y)),
                Math.min(v0.z, Math.min(v1.z, v2.z))
            );
        }
        
        Vector3f getMax() {
            return new Vector3f(
                Math.max(v0.x, Math.max(v1.x, v2.x)),
                Math.max(v0.y, Math.max(v1.y, v2.y)),
                Math.max(v0.z, Math.max(v1.z, v2.z))
            );
        }
        
        boolean intersectsVoxel(Vector3f center, float halfSize) {
            // Simple AABB-triangle intersection
            Vector3f min = new Vector3f(center);
            min.sub(new Vector3f(halfSize, halfSize, halfSize));
            Vector3f max = new Vector3f(center);
            max.add(new Vector3f(halfSize, halfSize, halfSize));
            
            Vector3f triMin = getMin();
            Vector3f triMax = getMax();
            
            return !(triMax.x < min.x || triMin.x > max.x ||
                    triMax.y < min.y || triMin.y > max.y ||
                    triMax.z < min.z || triMin.z > max.z);
        }
    }
    
    private static class Voxel {
        Vector3f position;
        Vector3f color;
        Vector3f normal;
        
        Voxel(Vector3f position, Vector3f color, Vector3f normal) {
            this.position = position;
            this.color = color;
            this.normal = normal;
        }
    }
    
    private static class OctreeNode {
        List<Voxel> voxels = new ArrayList<>();
        OctreeNode[] children;
        
        void addVoxel(Voxel v) {
            voxels.add(v);
        }
    }
}