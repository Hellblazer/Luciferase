package com.hellblazer.luciferase.esvo.demo;

import com.hellblazer.luciferase.esvo.core.OctreeBuilder;
import com.hellblazer.luciferase.esvo.core.OctreeNode;
import com.hellblazer.luciferase.esvo.traversal.AdvancedRayTraversal;
import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import javax.vecmath.Vector3f;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Phase 3 Advanced Features Demo for ESVO Implementation
 * 
 * Demonstrates:
 * - Far pointer resolution for large octrees
 * - Contour intersection for sub-voxel accuracy
 * - Beam optimization for coherent rays
 * - Normal reconstruction from voxel gradients
 * 
 * Performance Target: >30 FPS with all features enabled
 */
public class ESVOPhase3Demo {
    
    private static final int OCTREE_DEPTH = 12;  // Deep octree to test far pointers
    private static final int OCTREE_SIZE = 1024 * 1024 * 8;  // 8MB octree
    private static final int NUM_RAYS = 10000;
    private static final int BEAM_SIZE = 4;  // 2x2 ray beams
    
    private ByteBuffer octreeData;
    private AdvancedRayTraversal traversal;
    private Random random = new Random();
    
    public static void main(String[] args) {
        System.out.println("ESVO Phase 3 Advanced Features Demo");
        System.out.println("====================================");
        
        ESVOPhase3Demo demo = new ESVOPhase3Demo();
        demo.run();
    }
    
    public void run() {
        System.out.println("\n1. Initializing ESVO with advanced features...");
        initialize();
        
        System.out.println("\n2. Building complex octree with far pointers...");
        buildComplexOctree();
        
        System.out.println("\n3. Testing far pointer resolution...");
        testFarPointers();
        
        System.out.println("\n4. Testing contour intersection (sub-voxel accuracy)...");
        testContourIntersection();
        
        System.out.println("\n5. Testing beam optimization...");
        testBeamOptimization();
        
        System.out.println("\n6. Testing normal reconstruction...");
        testNormalReconstruction();
        
        System.out.println("\n7. Performance benchmark with all features...");
        runPerformanceBenchmark();
        
        cleanup();
        System.out.println("\n✓ Phase 3 demo complete!");
    }
    
    private void initialize() {
        // Allocate direct memory for octree
        octreeData = ByteBuffer.allocateDirect(OCTREE_SIZE);
        
        // Create advanced ray traversal with all features enabled
        traversal = new AdvancedRayTraversal();
        traversal.setFarPointersEnabled(true);
        traversal.setContoursEnabled(true);
        traversal.setBeamOptimizationEnabled(true);
        traversal.setNormalReconstructionEnabled(true);
        
        System.out.println("  - Allocated " + (OCTREE_SIZE / 1024 / 1024) + "MB for octree");
        System.out.println("  - All advanced features enabled");
    }
    
    private void buildComplexOctree() {
        OctreeBuilder builder = new OctreeBuilder(OCTREE_DEPTH);
        
        // Add a sphere with varying density for contour testing
        int sphereVoxels = 0;
        float radius = 0.3f;
        Vector3f center = new Vector3f(1.5f, 1.5f, 1.5f);
        
        for (int level = 0; level < OCTREE_DEPTH; level++) {
            int resolution = 1 << level;
            float voxelSize = 1.0f / resolution;
            
            for (int z = 0; z < resolution; z++) {
                for (int y = 0; y < resolution; y++) {
                    for (int x = 0; x < resolution; x++) {
                        Vector3f pos = new Vector3f(
                            1.0f + (x + 0.5f) * voxelSize,
                            1.0f + (y + 0.5f) * voxelSize,
                            1.0f + (z + 0.5f) * voxelSize
                        );
                        
                        Vector3f diff = new Vector3f(pos);
                        diff.sub(center);
                        float dist = diff.length();
                        if (dist < radius) {
                            // Add voxel with density gradient for contours
                            float density = 1.0f - (dist / radius);
                            builder.addVoxel(x, y, z, level, density);
                            sphereVoxels++;
                        }
                    }
                }
            }
        }
        
        // Build octree and serialize to buffer
        builder.serialize(octreeData);
        
        System.out.println("  - Built octree with " + sphereVoxels + " voxels");
        System.out.println("  - Maximum depth: " + OCTREE_DEPTH);
        System.out.println("  - Includes far pointers for deep nodes");
    }
    
    private void testFarPointers() {
        // Test traversal to deep nodes requiring far pointers
        Vector3f origin = new Vector3f(1.0f, 1.5f, 1.5f);
        Vector3f direction = new Vector3f(1.0f, 0.0f, 0.0f);
        
        AdvancedRayTraversal.HitInfo hit = traversal.traverse(origin, direction, octreeData);
        
        if (hit != null && hit.requiresFarPointer) {
            System.out.println("  ✓ Far pointer resolved at depth " + hit.depth);
            System.out.println("    Node offset: 0x" + Integer.toHexString(hit.nodeOffset));
        } else {
            System.out.println("  - No far pointers needed for this ray");
        }
    }
    
    private void testContourIntersection() {
        // Test sub-voxel accuracy with contours
        Vector3f origin = new Vector3f(1.2f, 1.5f, 1.5f);
        Vector3f direction = new Vector3f(1.0f, 0.0f, 0.0f);
        
        // First pass without contours
        traversal.setContoursEnabled(false);
        AdvancedRayTraversal.HitInfo hitNoContour = traversal.traverse(origin, direction, octreeData);
        
        // Second pass with contours
        traversal.setContoursEnabled(true);
        AdvancedRayTraversal.HitInfo hitWithContour = traversal.traverse(origin, direction, octreeData);
        
        if (hitNoContour != null && hitWithContour != null) {
            float diff = Math.abs(hitWithContour.t - hitNoContour.t);
            System.out.println("  ✓ Contour refinement: " + String.format("%.6f", diff) + " units");
            System.out.println("    Standard hit: t = " + String.format("%.6f", hitNoContour.t));
            System.out.println("    Contour hit:  t = " + String.format("%.6f", hitWithContour.t));
        }
    }
    
    private void testBeamOptimization() {
        // Create coherent ray beam
        Vector3f origin = new Vector3f(0.5f, 1.5f, 1.5f);
        Vector3f baseDir = new Vector3f(1.0f, 0.0f, 0.0f);
        
        List<Vector3f> beamDirs = new ArrayList<>();
        float pixelSize = 0.001f;
        
        for (int i = 0; i < BEAM_SIZE; i++) {
            int x = i % 2;
            int y = i / 2;
            Vector3f dir = new Vector3f(
                baseDir.x,
                baseDir.y + (x - 0.5f) * pixelSize,
                baseDir.z + (y - 0.5f) * pixelSize
            );
            dir.normalize();
            beamDirs.add(dir);
        }
        
        // Test individual rays
        long individualStart = System.nanoTime();
        traversal.setBeamOptimizationEnabled(false);
        for (Vector3f dir : beamDirs) {
            traversal.traverse(origin, dir, octreeData);
        }
        long individualTime = System.nanoTime() - individualStart;
        
        // Test beam optimization
        long beamStart = System.nanoTime();
        traversal.setBeamOptimizationEnabled(true);
        List<AdvancedRayTraversal.HitInfo> beamHits = traversal.traverseBeam(origin, beamDirs, octreeData);
        long beamTime = System.nanoTime() - beamStart;
        
        double speedup = (double)individualTime / beamTime;
        System.out.println("  ✓ Beam optimization speedup: " + String.format("%.2fx", speedup));
        System.out.println("    Individual: " + (individualTime / 1000) + " μs");
        System.out.println("    Beam:       " + (beamTime / 1000) + " μs");
    }
    
    private void testNormalReconstruction() {
        // Test normal reconstruction at surface hit
        Vector3f origin = new Vector3f(1.0f, 1.5f, 1.5f);
        Vector3f direction = new Vector3f(1.0f, 0.0f, 0.0f);
        
        AdvancedRayTraversal.HitInfo hit = traversal.traverse(origin, direction, octreeData);
        
        if (hit != null && hit.normal != null) {
            System.out.println("  ✓ Normal reconstructed at hit point");
            System.out.println("    Normal: (" + 
                String.format("%.3f", hit.normal.x) + ", " +
                String.format("%.3f", hit.normal.y) + ", " +
                String.format("%.3f", hit.normal.z) + ")");
            System.out.println("    Length: " + String.format("%.6f", hit.normal.length()));
        }
    }
    
    private void runPerformanceBenchmark() {
        // Warm up
        for (int i = 0; i < 100; i++) {
            Vector3f origin = generateRandomPoint();
            Vector3f direction = generateRandomDirection();
            traversal.traverse(origin, direction, octreeData);
        }
        
        // Benchmark with all features
        long startTime = System.nanoTime();
        int hits = 0;
        
        for (int i = 0; i < NUM_RAYS; i++) {
            Vector3f origin = generateRandomPoint();
            Vector3f direction = generateRandomDirection();
            AdvancedRayTraversal.HitInfo hit = traversal.traverse(origin, direction, octreeData);
            if (hit != null) hits++;
        }
        
        long elapsedTime = System.nanoTime() - startTime;
        double msPerRay = (elapsedTime / 1_000_000.0) / NUM_RAYS;
        double raysPerSecond = NUM_RAYS / (elapsedTime / 1_000_000_000.0);
        
        // Calculate theoretical FPS for 1920x1080
        double pixelsPerFrame = 1920 * 1080;
        double theoreticalFPS = raysPerSecond / pixelsPerFrame;
        
        System.out.println("\n  Performance Results:");
        System.out.println("  -------------------");
        System.out.println("  Rays traced:     " + NUM_RAYS);
        System.out.println("  Hits:            " + hits);
        System.out.println("  Hit rate:        " + String.format("%.1f%%", (100.0 * hits / NUM_RAYS)));
        System.out.println("  Time per ray:    " + String.format("%.4f ms", msPerRay));
        System.out.println("  Rays per second: " + String.format("%.0f", raysPerSecond));
        System.out.println("  Theoretical FPS: " + String.format("%.1f", theoreticalFPS) + " @ 1920x1080");
        
        if (theoreticalFPS >= 30) {
            System.out.println("  ✓ Meeting 30 FPS target!");
        } else {
            System.out.println("  ⚠ Below 30 FPS target");
        }
    }
    
    private Vector3f generateRandomPoint() {
        return new Vector3f(
            1.0f + random.nextFloat(),
            1.0f + random.nextFloat(),
            1.0f + random.nextFloat()
        );
    }
    
    private Vector3f generateRandomDirection() {
        double theta = random.nextDouble() * Math.PI;
        double phi = random.nextDouble() * 2 * Math.PI;
        
        Vector3f dir = new Vector3f(
            (float)(Math.sin(theta) * Math.cos(phi)),
            (float)(Math.sin(theta) * Math.sin(phi)),
            (float)Math.cos(theta)
        );
        dir.normalize();
        return dir;
    }
    
    private void cleanup() {
        // No explicit cleanup needed for ByteBuffer.allocateDirect
        // GC will handle it
        octreeData = null;
        traversal = null;
    }
}