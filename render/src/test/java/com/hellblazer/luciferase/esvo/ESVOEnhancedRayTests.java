package com.hellblazer.luciferase.esvo;

import com.hellblazer.luciferase.esvo.traversal.EnhancedRay;
import com.hellblazer.luciferase.esvo.traversal.StackBasedRayTraversal;
import com.hellblazer.luciferase.esvo.traversal.BasicRayTraversal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the enhanced ray traversal functionality with C++ compliance
 */
@DisplayName("ESVO Enhanced Ray Tests")
public class ESVOEnhancedRayTests {

    @Test
    @DisplayName("Test EnhancedRay creation with size parameters")
    void testEnhancedRayCreation() {
        var origin = new Vector3f(1.5f, 1.5f, 1.5f);
        var direction = new Vector3f(1, 0, 0);
        float originSize = 0.1f;
        float directionSize = 0.05f;
        
        var ray = new EnhancedRay(origin, originSize, direction, directionSize);
        
        assertEquals(1.5f, ray.origin.x, 0.001f);
        assertEquals(0.1f, ray.originSize, 0.001f);
        assertEquals(1.0f, ray.direction.x, 0.001f); // Should be normalized
        assertEquals(0.05f, ray.directionSize, 0.001f);
    }

    @Test
    @DisplayName("Test size-based termination condition")
    void testSizeBasedTermination() {
        var ray = new EnhancedRay(
            new Vector3f(1.5f, 1.5f, 1.5f), 0.05f, // originSize
            new Vector3f(1, 0, 0), 0.1f             // directionSize
        );
        
        // Test cases from C++: tc_max * ray.dir_sz + ray_orig_sz >= scale_exp2
        float tcMax1 = 0.4f;
        float scaleExp2_1 = 0.1f; // Should terminate: 0.4 * 0.1 + 0.05 = 0.09 < 0.1 (no termination)
        assertFalse(ray.shouldTerminate(tcMax1, scaleExp2_1));
        
        float tcMax2 = 0.6f; 
        float scaleExp2_2 = 0.1f; // Should terminate: 0.6 * 0.1 + 0.05 = 0.11 > 0.1 (terminate)
        assertTrue(ray.shouldTerminate(tcMax2, scaleExp2_2));
    }

    @Test
    @DisplayName("Test octant mask calculation matches C++")
    void testOctantMaskCalculation() {
        // Test case: (+X, -Y, +Z) direction should give mask 2
        var ray = new EnhancedRay(
            new Vector3f(1.5f, 1.5f, 1.5f), 0.0f,
            new Vector3f(1, -1, 1), 0.0f
        );
        
        int mask = ray.calculateOctantMask();
        assertEquals(2, mask, "Octant mask should be 2 for (+X, -Y, +Z)");
        
        // Test all negative direction (should be 7)
        var rayAllNeg = new EnhancedRay(
            new Vector3f(1.5f, 1.5f, 1.5f), 0.0f,
            new Vector3f(-1, -1, -1), 0.0f
        );
        
        int maskAllNeg = rayAllNeg.calculateOctantMask();
        assertEquals(7, maskAllNeg, "Octant mask should be 7 for all negative");
    }

    @Test
    @DisplayName("Test octant mirroring implementation")
    void testOctantMirroring() {
        var ray = new EnhancedRay(
            new Vector3f(1.2f, 1.8f, 1.3f), 0.0f,
            new Vector3f(1, -1, 1), 0.0f
        );
        
        int octantMask = 3; // Flip X and Y
        var mirrored = ray.applyOctantMirroring(octantMask);
        
        // X should be mirrored: 3.0 - 1.2 = 1.8
        assertEquals(1.8f, mirrored.origin.x, 0.001f);
        // Y should be mirrored: 3.0 - 1.8 = 1.2  
        assertEquals(1.2f, mirrored.origin.y, 0.001f);
        // Z should not be mirrored
        assertEquals(1.3f, mirrored.origin.z, 0.001f);
        
        // Direction should also be mirrored
        assertEquals(-1.0f, mirrored.direction.x, 0.001f);
        assertEquals(1.0f, mirrored.direction.y, 0.001f);
        assertEquals(1.0f, mirrored.direction.z, 0.001f);
    }

    @Test
    @DisplayName("Test coordinate space transformation")
    void testCoordinateSpaceTransformation() {
        // Ray in [0,1] space
        var worldRay = new EnhancedRay(
            new Vector3f(0.5f, 0.5f, 0.5f), 0.1f,
            new Vector3f(1, 0, 0), 0.05f
        );
        
        var octreeRay = EnhancedRay.transformToOctreeSpace(worldRay);
        
        // Should be transformed to [1,2] space
        assertEquals(1.5f, octreeRay.origin.x, 0.001f);
        assertEquals(1.5f, octreeRay.origin.y, 0.001f);
        assertEquals(1.5f, octreeRay.origin.z, 0.001f);
        
        // Size parameters should be preserved
        assertEquals(0.1f, octreeRay.originSize, 0.001f);
        assertEquals(0.05f, octreeRay.directionSize, 0.001f);
        
        // Direction should be unchanged (only origin transforms)
        assertEquals(1.0f, octreeRay.direction.x, 0.001f);
        assertEquals(0.0f, octreeRay.direction.y, 0.001f);
        assertEquals(0.0f, octreeRay.direction.z, 0.001f);
    }

    @Test
    @DisplayName("Test epsilon handling for small directions")
    void testEpsilonHandling() {
        float epsilon = (float)Math.pow(2, -23);
        
        // Create ray with very small direction components
        var ray = new EnhancedRay(
            new Vector3f(1.5f, 1.5f, 1.5f), 0.0f,
            new Vector3f(epsilon / 2, epsilon / 2, 1), 0.0f
        );
        
        // Small components should be adjusted to epsilon
        assertEquals(epsilon, Math.abs(ray.direction.x), 1e-10f);
        assertEquals(epsilon, Math.abs(ray.direction.y), 1e-10f);
        
        // Large component should be normalized but not adjusted
        assertTrue(Math.abs(ray.direction.z) > epsilon);
    }

    @Test
    @DisplayName("Test enhanced traversal with termination")
    void testEnhancedTraversalWithTermination() {
        // Create octree for testing
        var octree = new StackBasedRayTraversal.MultiLevelOctree(3);
        
        // Create ray with size parameters that should cause early termination
        var ray = new EnhancedRay(
            new Vector3f(0.5f, 1.5f, 1.5f), 0.2f, // Large origin size
            new Vector3f(1, 0, 0), 0.1f            // Direction toward octree
        );
        
        var result = StackBasedRayTraversal.traverseEnhanced(ray, octree);
        
        assertNotNull(result, "Should get a valid result");
        // Exact behavior depends on octree structure and size parameters
    }

    @Test
    @DisplayName("Test basic enhanced traversal")
    void testBasicEnhancedTraversal() {
        // Create simple octree
        var octree = new BasicRayTraversal.SimpleOctree(
            new Vector3f(1.5f, 1.5f, 1.5f), 1.0f
        );
        octree.setGeometry(0, true); // Set geometry in octant 0
        
        // Create ray that should hit octant 0
        var ray = new EnhancedRay(
            new Vector3f(0.5f, 1.5f, 1.5f), 0.01f,
            new Vector3f(1, 0, 0), 0.005f
        );
        
        var result = BasicRayTraversal.traverseEnhanced(ray, octree);
        
        assertTrue(result.hit(), "Ray should hit the octree");
        assertEquals(0, result.octant(), "Should hit octant 0");
        assertNotNull(result.hitPoint(), "Should have hit point");
    }

    @Test
    @DisplayName("Test enhanced ray generation with pixel size")
    void testEnhancedRayGeneration() {
        var cameraPos = new Vector3f(0, 0, 0);
        var cameraDir = new Vector3f(0, 0, -1);
        float fov = (float) Math.PI / 4; // 45 degrees
        float pixelSize = 0.001f;
        
        var ray = BasicRayTraversal.generateEnhancedRay(
            320, 240, // Center pixel
            640, 480, // Screen size
            cameraPos, cameraDir, fov, pixelSize
        );
        
        assertNotNull(ray, "Should generate valid ray");
        assertEquals(pixelSize, ray.originSize, 0.001f, "Origin size should match pixel size");
        assertTrue(ray.directionSize > 0, "Direction size should be positive");
        
        // Ray should point roughly forward (after coordinate transformation)
        assertTrue(ray.direction.length() > 0.9f, "Direction should be normalized");
    }

    @Test
    @DisplayName("Test conversion from legacy Ray to EnhancedRay")
    void testLegacyRayConversion() {
        var legacyRay = new StackBasedRayTraversal.Ray(
            new Vector3f(1.5f, 1.5f, 1.5f),
            new Vector3f(1, 0, 0)
        );
        
        var enhancedRay = legacyRay.toEnhancedRay();
        
        assertEquals(legacyRay.origin.x, enhancedRay.origin.x, 0.001f);
        assertEquals(legacyRay.direction.x, enhancedRay.direction.x, 0.001f);
        assertEquals(0.0f, enhancedRay.originSize, 0.001f, "Default size should be 0");
        assertEquals(0.0f, enhancedRay.directionSize, 0.001f, "Default size should be 0");
    }

    @Test
    @DisplayName("Test ray point calculation")
    void testRayPointCalculation() {
        var ray = new EnhancedRay(
            new Vector3f(1.0f, 1.0f, 1.0f), 0.0f,
            new Vector3f(1, 0, 0), 0.0f
        );
        
        var point = ray.pointAt(0.5f);
        
        assertEquals(1.5f, point.x, 0.001f, "Point should be at origin + t * direction");
        assertEquals(1.0f, point.y, 0.001f);
        assertEquals(1.0f, point.z, 0.001f);
    }

    @Test
    @DisplayName("Test ray string representation")
    void testRayStringRepresentation() {
        var ray = new EnhancedRay(
            new Vector3f(1.5f, 1.5f, 1.5f), 0.1f,
            new Vector3f(1, 0, 0), 0.05f
        );
        
        String str = ray.toString();
        
        assertNotNull(str);
        assertTrue(str.contains("EnhancedRay"), "Should identify as EnhancedRay");
        assertTrue(str.contains("1.500"), "Should contain origin coordinates");
        assertTrue(str.contains("0.100"), "Should contain origin size");
        assertTrue(str.contains("0.050"), "Should contain direction size");
    }
}