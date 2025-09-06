package com.hellblazer.luciferase.esvo;

import com.hellblazer.luciferase.esvo.core.CoordinateSpace;
import com.hellblazer.luciferase.esvo.core.OctreeNode;
import com.hellblazer.luciferase.esvo.traversal.BasicRayTraversal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;

import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Phase 1 Tests: Basic Ray Traversal for Single-Level Octree
 * 
 * Requirements from roadmap:
 * - Test against simple 1-level octree
 * - Performance target: >100 FPS for single level  
 * - Ray generation in octree space [1,2]
 * - Child index calculation with octant mirroring
 * - Debug visualization (octant colors)
 * 
 * These tests MUST pass before proceeding to Phase 2.
 */
@DisplayName("ESVO Phase 1: Basic Ray Traversal Tests")
public class ESVOPhase1Tests {
    
    private BasicRayTraversal.SimpleOctree testOctree;
    private static final float EPSILON = 1e-6f;
    
    // Performance test constants
    private static final int PERFORMANCE_RAY_COUNT = 100_000;
    private static final double MIN_RAYS_PER_SECOND = 1_000_000; // >100 FPS @ 256x256 = ~6.5M rays/sec
    
    @BeforeEach
    void setUp() {
        // Create a simple single-level octree for testing
        // Valid mask = 0b10101010 (children 1, 3, 5, 7 have geometry)
        // Non-leaf mask = 0 (all children are leaves)
        byte validMask = (byte) 0xAA; // 0b10101010
        byte nonLeafMask = 0; // All leaves
        int childPointer = 0; // No children data needed for Phase 1
        
        OctreeNode rootNode = new OctreeNode(nonLeafMask, validMask, false, childPointer, (byte)0, 0);
        testOctree = new BasicRayTraversal.SimpleOctree(rootNode);
    }
    
    @Test
    @DisplayName("Test ray generation in octree space [1,2]")
    void testRayGeneration() {
        Vector3f cameraPos = new Vector3f(0.5f, 1.5f, 1.5f);
        Vector3f cameraDir = new Vector3f(1.0f, 0.0f, 0.0f);
        float fov = (float) Math.PI / 4; // 45 degrees
        
        // Generate ray for center pixel
        BasicRayTraversal.Ray ray = BasicRayTraversal.generateRay(
            128, 128, 256, 256, cameraPos, cameraDir, fov);
        
        // Verify ray is properly oriented toward octree
        assertNotNull(ray, "Ray should be generated");
        assertNotNull(ray.origin, "Ray origin should not be null");
        assertNotNull(ray.direction, "Ray direction should not be null");
        
        // Ray should start outside octree (x < 1.0) and point into it (dx > 0)
        assertTrue(ray.origin.x < CoordinateSpace.OCTREE_MIN, 
                  "Ray should start outside octree bounds");
        assertTrue(ray.direction.x > 0, 
                  "Ray should point toward octree");
        
        // Direction should be normalized
        float length = ray.direction.length();
        assertEquals(1.0f, length, EPSILON, "Ray direction should be normalized");
        
        // Ray should intersect octree bounds [1,2]
        float[] intersection = CoordinateSpace.calculateOctreeIntersection(ray.origin, ray.direction);
        assertNotNull(intersection, "Ray should intersect octree bounds");
        assertTrue(intersection[0] >= 0, "Entry t should be non-negative");
        assertTrue(intersection[1] > intersection[0], "Exit t should be greater than entry t");
    }
    
    @Test
    @DisplayName("Test single-level octree traversal")
    void testSingleLevelTraversal() {
        // Test ray that hits octant 1 (which has geometry per our valid mask 0xAA)
        Vector3f rayOrigin = new Vector3f(0.5f, 1.25f, 1.25f); // Left of octree, lower quadrant
        Vector3f rayDirection = new Vector3f(1.0f, 0.0f, 0.0f); // Point right
        BasicRayTraversal.Ray ray = new BasicRayTraversal.Ray(rayOrigin, rayDirection);
        
        BasicRayTraversal.TraversalResult result = BasicRayTraversal.traverse(ray, testOctree);
        
        // Should hit geometry in octant 1
        assertTrue(result.hit, "Ray should hit geometry");
        assertTrue(result.t > 0, "Hit distance should be positive");
        assertNotNull(result.hitPoint, "Hit point should be provided");
        
        // Hit point should be in octree space [1,2]
        assertTrue(CoordinateSpace.isInOctreeSpace(result.hitPoint), 
                  "Hit point should be in octree space");
        
        // Should hit octant 1 (binary: x>=center, y<center, z<center = 001 = 1)
        assertEquals(1, result.octant, "Should hit octant 1");
    }
    
    @Test
    @DisplayName("Test octant calculation for all 8 octants")
    void testOctantCalculation() {
        Vector3f center = testOctree.getCenter(); // (1.5, 1.5, 1.5)
        
        // Test all 8 octant combinations
        // Note: Since rays pass through multiple octants, we test what the FIRST
        // octant with geometry is. Octants 0,2,4,6 are empty, so rays through
        // them will continue to octants 1,3,5,7 which have geometry.
        testOctantHit(new Vector3f(1.25f, 1.25f, 1.25f), 0, 1); // Ray through 0, hits 1
        testOctantHit(new Vector3f(1.75f, 1.25f, 1.25f), 1, 1); // Ray through 1, hits 1
        testOctantHit(new Vector3f(1.25f, 1.75f, 1.25f), 2, 3); // Ray through 2, hits 3
        testOctantHit(new Vector3f(1.75f, 1.75f, 1.25f), 3, 3); // Ray through 3, hits 3
        testOctantHit(new Vector3f(1.25f, 1.25f, 1.75f), 4, 5); // Ray through 4, hits 5
        testOctantHit(new Vector3f(1.75f, 1.25f, 1.75f), 5, 5); // Ray through 5, hits 5
        testOctantHit(new Vector3f(1.25f, 1.75f, 1.75f), 6, 7); // Ray through 6, hits 7
        testOctantHit(new Vector3f(1.75f, 1.75f, 1.75f), 7, 7); // Ray through 7, hits 7
    }
    
    private void testOctantHit(Vector3f targetPoint, int targetOctant, int expectedHitOctant) {
        // Create ray that passes through the target point in the specified octant
        Vector3f rayOrigin = new Vector3f(0.5f, targetPoint.y, targetPoint.z);
        Vector3f rayDirection = new Vector3f(1.0f, 0.0f, 0.0f);
        BasicRayTraversal.Ray ray = new BasicRayTraversal.Ray(rayOrigin, rayDirection);
        
        BasicRayTraversal.TraversalResult result = BasicRayTraversal.traverse(ray, testOctree);
        
        // Check if ANY octant along the ray should have geometry
        // The ray continues through octants, so empty octants are skipped
        boolean targetHasGeometry = (0xAA & (1 << targetOctant)) != 0;
        
        if (targetHasGeometry) {
            // If target octant has geometry, it should be hit
            assertTrue(result.hit, "Ray should hit octant " + targetOctant);
            assertEquals(targetOctant, result.octant, "Should hit target octant");
        } else {
            // If target octant is empty, ray continues to next octant with geometry
            assertTrue(result.hit, "Ray should hit next octant with geometry");
            assertEquals(expectedHitOctant, result.octant, 
                        "Ray through empty octant " + targetOctant + 
                        " should hit octant " + expectedHitOctant);
        }
    }
    
    @Test
    @DisplayName("Test octant mirroring optimization")
    void testOctantMirroring() {
        // Test ray with positive direction (will be mirrored)
        Vector3f rayOrigin = new Vector3f(0.5f, 1.5f, 1.5f);
        Vector3f rayDirection = new Vector3f(1.0f, 1.0f, 1.0f); // All positive
        BasicRayTraversal.Ray ray = new BasicRayTraversal.Ray(rayOrigin, rayDirection);
        
        // Calculate octant mask for mirroring
        int octantMask = CoordinateSpace.calculateOctantMask(rayDirection);
        assertEquals(0, octantMask, "All positive directions should give mask 0 (flip all bits)");
        
        // Apply mirroring
        Vector3f mirroredOrigin = CoordinateSpace.applyOctantMirroringToOrigin(rayOrigin, octantMask);
        Vector3f mirroredDirection = CoordinateSpace.applyOctantMirroring(rayDirection, octantMask);
        
        // All direction components should now be negative or zero
        assertTrue(mirroredDirection.x <= 0, "Mirrored X direction should be negative");
        assertTrue(mirroredDirection.y <= 0, "Mirrored Y direction should be negative");
        assertTrue(mirroredDirection.z <= 0, "Mirrored Z direction should be negative");
        
        // Origin should be mirrored around octree center
        float expectedX = 3.0f - rayOrigin.x; // Mirror around 1.5: 3.0 - 0.5 = 2.5
        assertEquals(expectedX, mirroredOrigin.x, EPSILON, "Origin X should be mirrored");
    }
    
    @Test
    @DisplayName("Test octant debug colors")
    void testOctantDebugColors() {
        // Test that all octants have unique colors
        int[] colors = new int[8];
        for (int i = 0; i < 8; i++) {
            colors[i] = BasicRayTraversal.getOctantDebugColor(i);
            assertTrue(colors[i] != 0, "Octant " + i + " should have non-zero color");
        }
        
        // Verify all colors are unique
        for (int i = 0; i < 8; i++) {
            for (int j = i + 1; j < 8; j++) {
                assertNotEquals(colors[i], colors[j], 
                               "Octants " + i + " and " + j + " should have different colors");
            }
        }
        
        // Test special case (no hit)
        assertEquals(0x000000, BasicRayTraversal.getOctantDebugColor(-1), 
                    "No hit should return black");
    }
    
    @Test
    @DisplayName("Test ray-octree intersection edge cases")  
    void testRayOctreeIntersectionEdgeCases() {
        // Test ray that misses octree entirely
        Vector3f rayOrigin = new Vector3f(0.5f, 0.5f, 0.5f); // Below octree
        Vector3f rayDirection = new Vector3f(0.0f, -1.0f, 0.0f); // Point down (away)
        BasicRayTraversal.Ray ray = new BasicRayTraversal.Ray(rayOrigin, rayDirection);
        
        BasicRayTraversal.TraversalResult result = BasicRayTraversal.traverse(ray, testOctree);
        assertFalse(result.hit, "Ray pointing away from octree should not hit");
        
        // Test ray that starts inside octree
        rayOrigin = new Vector3f(1.5f, 1.5f, 1.5f); // Center of octree
        rayDirection = new Vector3f(1.0f, 0.0f, 0.0f); // Point right
        ray = new BasicRayTraversal.Ray(rayOrigin, rayDirection);
        
        result = BasicRayTraversal.traverse(ray, testOctree);
        // Should hit if the center octant has geometry (depends on implementation)
        
        // Test ray that grazes octree edge
        rayOrigin = new Vector3f(0.5f, 1.0f, 1.5f); // Aligned with octree edge
        rayDirection = new Vector3f(1.0f, 0.0f, 0.0f);
        ray = new BasicRayTraversal.Ray(rayOrigin, rayDirection);
        
        result = BasicRayTraversal.traverse(ray, testOctree);
        // Should handle edge case gracefully (may or may not hit depending on precision)
    }
    
    @Test
    @DisplayName("Test Phase 1 performance target: >100 FPS single level")
    void testPhase1PerformanceTarget() {
        // Skip performance tests in CI environment
        assumeFalse(isRunningInCI(), "Skipping performance test in CI environment");
        
        // Measure traversal performance
        double raysPerSecond = BasicRayTraversal.measureTraversalPerformance(
            testOctree, PERFORMANCE_RAY_COUNT);
        
        System.out.printf("Phase 1 Performance: %.0f rays/second (%.1f M rays/sec)%n", 
                         raysPerSecond, raysPerSecond / 1_000_000.0);
        
        // Phase 1 target: >100 FPS for single level
        // At 256x256 resolution = 65,536 rays per frame
        // 100 FPS * 65,536 = 6,553,600 rays/second minimum
        assertTrue(raysPerSecond >= MIN_RAYS_PER_SECOND, 
                  String.format("Performance target not met: %.0f rays/sec < %.0f rays/sec required", 
                               raysPerSecond, MIN_RAYS_PER_SECOND));
        
        // Also test FPS calculation
        double fps = raysPerSecond / (256 * 256); // Rays per frame at 256x256
        System.out.printf("Equivalent FPS at 256x256: %.1f FPS%n", fps);
        assertTrue(fps >= 100.0, "Should achieve >100 FPS at 256x256 resolution");
    }
    
    @Test
    @DisplayName("Test coordinate space validation in traversal")
    void testCoordinateSpaceValidation() {
        // Test that traversal properly validates octree coordinate space [1,2]
        Vector3f center = testOctree.getCenter();
        assertEquals(1.5f, center.x, EPSILON, "Octree center X should be 1.5");
        assertEquals(1.5f, center.y, EPSILON, "Octree center Y should be 1.5");  
        assertEquals(1.5f, center.z, EPSILON, "Octree center Z should be 1.5");
        
        float halfSize = testOctree.getHalfSize();
        assertEquals(0.5f, halfSize, EPSILON, "Octree half-size should be 0.5");
        
        // Test octree bounds
        Vector3f minBounds = new Vector3f(center);
        minBounds.sub(new Vector3f(halfSize, halfSize, halfSize));
        Vector3f maxBounds = new Vector3f(center);
        maxBounds.add(new Vector3f(halfSize, halfSize, halfSize));
        
        assertEquals(CoordinateSpace.OCTREE_MIN, minBounds.x, EPSILON, "Min X bound should be 1.0");
        assertEquals(CoordinateSpace.OCTREE_MAX, maxBounds.x, EPSILON, "Max X bound should be 2.0");
    }
    
    @Test
    @DisplayName("Test ray t-parameter calculations")
    void testRayTParameterCalculations() {
        Vector3f rayOrigin = new Vector3f(0.5f, 1.5f, 1.5f);
        Vector3f rayDirection = new Vector3f(1.0f, 0.0f, 0.0f);
        BasicRayTraversal.Ray ray = new BasicRayTraversal.Ray(rayOrigin, rayDirection);
        
        // Test pointAt method
        Vector3f point1 = ray.pointAt(0.0f);
        assertEquals(rayOrigin.x, point1.x, EPSILON, "Point at t=0 should be origin");
        
        Vector3f point2 = ray.pointAt(1.0f);
        assertEquals(rayOrigin.x + rayDirection.x, point2.x, EPSILON, 
                    "Point at t=1 should be origin + direction");
        
        // Test intersection calculation
        float[] intersection = CoordinateSpace.calculateOctreeIntersection(rayOrigin, rayDirection);
        assertNotNull(intersection, "Should calculate intersection");
        
        Vector3f entryPoint = ray.pointAt(intersection[0]);
        Vector3f exitPoint = ray.pointAt(intersection[1]);
        
        // Entry point should be on octree boundary
        assertEquals(CoordinateSpace.OCTREE_MIN, entryPoint.x, EPSILON, 
                    "Entry point should be on left face of octree");
        assertEquals(CoordinateSpace.OCTREE_MAX, exitPoint.x, EPSILON,
                    "Exit point should be on right face of octree");
    }
    
    /**
     * Check if running in CI environment
     */
    private static boolean isRunningInCI() {
        return "true".equals(System.getenv("CI")) || "true".equals(System.getProperty("CI")) || "true".equals(
                System.getProperty("CONTINUOUS_INTEGRATION"));
    }
}