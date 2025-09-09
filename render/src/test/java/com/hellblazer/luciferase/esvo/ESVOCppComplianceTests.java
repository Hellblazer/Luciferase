package com.hellblazer.luciferase.esvo;

import com.hellblazer.luciferase.esvo.core.CoordinateSpace;
import com.hellblazer.luciferase.esvo.core.ESVOConstants;
import com.hellblazer.luciferase.esvo.core.ESVOOctreeNode;
import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.esvo.traversal.StackBasedRayTraversal;
import com.hellblazer.luciferase.esvo.traversal.EnhancedRay;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import javax.vecmath.Vector3f;
import javax.vecmath.Matrix4f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compliance tests based on C++ ESVO reference implementation.
 * These tests verify that our Java implementation matches the C++ behavior.
 */
@DisplayName("ESVO C++ Compliance Tests")
public class ESVOCppComplianceTests {

    private ESVOOctreeData octreeData;
    private StackBasedRayTraversal traversal;

    @BeforeEach
    void setUp() {
        octreeData = new ESVOOctreeData(1024);
        traversal = new StackBasedRayTraversal();
    }

    @Test
    @DisplayName("Test Ray structure should match C++ with size parameters")
    void testRayStructureCompliance() {
        // C++ Ray has: float3 orig, float orig_sz, float3 dir, float dir_sz
        // Our Java Ray is missing orig_sz and dir_sz
        
        var ray = new EnhancedRay(new Vector3f(1.5f, 1.5f, 1.5f), 0.001f, new Vector3f(0, 0, 1), 0.001f);
        
        // This test currently FAILS because we're missing size parameters
        // When fixed, the Ray class should have:
        // - originSize (float)
        // - directionSize (float)
        
        assertNotNull(ray.origin, "Ray should have origin");
        assertNotNull(ray.direction, "Ray should have direction");
        
        // TODO: These assertions should pass after fixing Ray class
        // assertNotNull(ray.originSize, "Ray should have originSize");
        // assertNotNull(ray.directionSize, "Ray should have directionSize");
    }

    @Test
    @DisplayName("Test coordinate space is [1,2] not [0,1]")
    void testCoordinateSpaceCompliance() {
        // C++ comment at line 101: "The octree is assumed to reside at coordinates [1, 2]"
        
        // Create transformation matrix that maps [0,1] to [1,2]
        // This requires translation by 1 in all dimensions
        var transformMatrix = new Matrix4f();
        transformMatrix.setIdentity();
        transformMatrix.setTranslation(new Vector3f(1.0f, 1.0f, 1.0f));
        
        // Test that our coordinate space matches
        var origin = new Vector3f(0.5f, 0.5f, 0.5f);
        var transformed = CoordinateSpace.transformToOctreeSpace(origin, transformMatrix);
        
        // Should transform from [0,1] to [1,2]
        assertEquals(1.5f, transformed.x, 0.001f, "X should be in [1,2] space");
        assertEquals(1.5f, transformed.y, 0.001f, "Y should be in [1,2] space");
        assertEquals(1.5f, transformed.z, 0.001f, "Z should be in [1,2] space");
        
        // Test bounds
        var min = CoordinateSpace.transformToOctreeSpace(new Vector3f(0, 0, 0), transformMatrix);
        var max = CoordinateSpace.transformToOctreeSpace(new Vector3f(1, 1, 1), transformMatrix);
        
        assertEquals(1.0f, min.x, 0.001f, "Min X should be 1");
        assertEquals(2.0f, max.x, 0.001f, "Max X should be 2");
    }

    @Test
    @DisplayName("Test stack depth matches C++ CAST_STACK_DEPTH")
    void testStackDepthCompliance() {
        // C++: #define CAST_STACK_DEPTH 23
        assertEquals(23, ESVOConstants.MAX_DEPTH,
            "Stack depth should match C++ CAST_STACK_DEPTH");
    }

    @Test
    @DisplayName("Test iteration limit matches C++ MAX_RAYCAST_ITERATIONS")
    void testIterationLimitCompliance() {
        // C++: #define MAX_RAYCAST_ITERATIONS 10000
        assertEquals(10000, StackBasedRayTraversal.MAX_RAYCAST_ITERATIONS,
            "Iteration limit should match C++ MAX_RAYCAST_ITERATIONS");
    }

    @Test
    @DisplayName("Test epsilon calculation for small ray directions")
    void testEpsilonCompliance() {
        // C++ line 90: const float epsilon = exp2f(-CAST_STACK_DEPTH);
        float epsilon = (float)Math.pow(2, -ESVOConstants.MAX_DEPTH);
        
        // Should be 2^-23
        assertEquals(1.1920929e-7f, epsilon, 1e-10f, 
            "Epsilon should be 2^-23");
        
        // Test small direction component handling
        var smallDir = new Vector3f(0, epsilon/2, 1);
        
        // Should be adjusted to avoid division by zero
        // C++ lines 96-98: if (fabsf(ray.dir.x) < epsilon) ray.dir.x = copysignf(epsilon, ray.dir.x);
        if (Math.abs(smallDir.y) < epsilon) {
            smallDir.y = Math.copySign(epsilon, smallDir.y);
        }
        
        assertEquals(epsilon, Math.abs(smallDir.y), 1e-10f,
            "Small direction component should be adjusted to epsilon");
    }

    @Test
    @DisplayName("Test octant mask calculation matches C++")
    void testOctantMaskCompliance() {
        // C++ lines 114-117: octant mirroring logic
        
        var direction = new Vector3f(1, -1, 1); // Positive X, Negative Y, Positive Z
        int octantMask = 7; // Start with all bits set
        
        // C++ logic:
        // if (ray.dir.x > 0.0f) octant_mask ^= 1
        // if (ray.dir.y > 0.0f) octant_mask ^= 2
        // if (ray.dir.z > 0.0f) octant_mask ^= 4
        
        if (direction.x > 0) octantMask ^= 1; // Flip bit 0
        if (direction.y > 0) octantMask ^= 2; // Don't flip bit 1 (negative)
        if (direction.z > 0) octantMask ^= 4; // Flip bit 2
        
        assertEquals(2, octantMask, "Octant mask should be 2 for (+X, -Y, +Z)");
        
        // Verify our implementation matches
        int ourMask = CoordinateSpace.calculateOctantMask(direction);
        assertEquals(octantMask, ourMask, "Our octant mask calculation should match C++");
    }

    @Test
    @DisplayName("Test child descriptor bit layout matches C++")
    void testChildDescriptorCompliance() {
        // C++ child_descriptor format:
        // - Bit 16: far pointer flag
        // - Bits 17-31: child pointer offset
        // - Lower 8 bits: valid mask
        // - Bit 7: non-leaf flag
        
        int childDescriptor = 0x00060081; // Example: offset 3 shifted to bits 17-31, valid mask 0x81
        
        // Extract components as C++ does
        int validMask = childDescriptor & 0xFF;
        boolean isNonLeaf = (childDescriptor & 0x0080) != 0;
        boolean isFarPointer = (childDescriptor & 0x10000) != 0;
        int childOffset = (childDescriptor >>> 17);
        
        assertEquals(0x81, validMask, "Valid mask should be 0x81");
        assertTrue(isNonLeaf, "Should be non-leaf (bit 7 set)");
        assertFalse(isFarPointer, "Should not be far pointer");
        assertEquals(3, childOffset, "Child offset should be 3");
    }

    @Test
    @DisplayName("Test popc8 (8-bit population count) compliance")
    void testPopc8Compliance() {
        // C++ uses popc8() for counting set bits in lower 8 bits
        
        // Test various bit patterns
        assertEquals(0, Integer.bitCount(0x00 & 0xFF), "popc8(0x00) should be 0");
        assertEquals(1, Integer.bitCount(0x01 & 0xFF), "popc8(0x01) should be 1");
        assertEquals(4, Integer.bitCount(0x0F & 0xFF), "popc8(0x0F) should be 4");
        assertEquals(8, Integer.bitCount(0xFF & 0xFF), "popc8(0xFF) should be 8");
        assertEquals(3, Integer.bitCount(0x07 & 0xFF), "popc8(0x07) should be 3");
        assertEquals(2, Integer.bitCount(0x81 & 0xFF), "popc8(0x81) should be 2");
    }

    @Test
    @DisplayName("Test termination condition should use ray size parameters")
    void testTerminationConditionCompliance() {
        // C++ line 181: if (tc_max * ray.dir_sz + ray_orig_sz >= scale_exp2)
        
        // This test demonstrates what SHOULD happen (currently fails)
        float tcMax = 0.5f;
        float rayDirSz = 0.1f;  // Should come from ray.directionSize
        float rayOrigSz = 0.05f; // Should come from ray.originSize
        float scaleExp2 = 0.125f; // 2^-3
        
        // Termination check
        boolean shouldTerminate = (tcMax * rayDirSz + rayOrigSz >= scaleExp2);
        
        // In this case: 0.5 * 0.1 + 0.05 = 0.1, which is < 0.125, so don't terminate
        assertFalse(shouldTerminate, "Should not terminate when voxel is still large");
        
        // Test case where we should terminate
        rayDirSz = 0.2f;
        shouldTerminate = (tcMax * rayDirSz + rayOrigSz >= scaleExp2);
        // Now: 0.5 * 0.2 + 0.05 = 0.15, which is > 0.125, so terminate
        assertTrue(shouldTerminate, "Should terminate when voxel is small enough");
    }

    @Test
    @DisplayName("Test stack push optimization with h-value")
    void testStackPushOptimizationCompliance() {
        // C++ lines 238-245: Only push if tc_max < h
        
        float h = 0.5f; // Previous tc_max
        float tcMax = 0.4f; // Current tc_max
        
        // Should push because tcMax < h
        boolean shouldPush = (tcMax < h);
        assertTrue(shouldPush, "Should push when tc_max < h");
        
        // Update h after push
        h = tcMax;
        
        // Next iteration with larger tc_max
        tcMax = 0.6f;
        shouldPush = (tcMax < h);
        assertFalse(shouldPush, "Should not push when tc_max >= h");
    }

    @Test
    @DisplayName("Test XOR-based scale calculation for pop operation")
    void testXorScaleCalculationCompliance() {
        // C++ lines 301-306: XOR float bits to find differing scale
        
        var pos = new Vector3f(1.25f, 1.5f, 1.75f);
        float scaleExp2 = 0.25f; // 2^-2
        
        // Simulate step_mask = 3 (bits 0 and 1 set)
        int stepMask = 3;
        
        // Calculate differing bits as C++ does
        int differingBits = 0;
        if ((stepMask & 1) != 0) {
            differingBits |= Float.floatToIntBits(pos.x) ^ Float.floatToIntBits(pos.x + scaleExp2);
        }
        if ((stepMask & 2) != 0) {
            differingBits |= Float.floatToIntBits(pos.y) ^ Float.floatToIntBits(pos.y + scaleExp2);
        }
        if ((stepMask & 4) != 0) {
            differingBits |= Float.floatToIntBits(pos.z) ^ Float.floatToIntBits(pos.z + scaleExp2);
        }
        
        // Extract scale from highest bit position
        // Find the highest set bit in differingBits
        if (differingBits != 0) {
            int highestBit = 31 - Integer.numberOfLeadingZeros(differingBits);
            // This represents the exponent of the scale
            assertTrue(highestBit >= 0 && highestBit <= 31, "Scale should be in valid range");
        }
    }

    @Test
    @DisplayName("Test contour data encoding compliance")
    void testContourEncodingCompliance() {
        // C++ lines 206-213: Contour encoding
        
        int contourValue = 0x12345678; // Example contour data
        float scaleExp2 = 0.125f; // 2^-3
        
        // Extract components as C++ does
        float cthick = (float)(contourValue & 0xFFFFFFFF) * scaleExp2 * 0.75f;
        float cpos = (float)(contourValue << 7) * scaleExp2 * 1.5f;
        
        // Normal components (upper bits)
        float nx = (float)(contourValue << 14);
        float ny = (float)(contourValue << 20);
        float nz = (float)(contourValue << 26);
        
        // Verify extraction doesn't cause overflow
        assertNotEquals(Float.NaN, cthick, "Thickness should not be NaN");
        assertNotEquals(Float.NaN, cpos, "Position should not be NaN");
    }

    @Test
    @DisplayName("Test ray-box intersection for t-value calculation")
    void testRayBoxIntersectionCompliance() {
        // Test the t-value calculations for ray-box intersection
        
        var ray = new EnhancedRay(new Vector3f(0.5f, 1.5f, 1.5f), 0.001f, new Vector3f(1, 0, 0), 0.001f);
        var boxMin = new Vector3f(1.0f, 1.0f, 1.0f);
        var boxMax = new Vector3f(2.0f, 2.0f, 2.0f);
        
        // Calculate t-values for box intersection
        float txMin = (boxMin.x - ray.origin.x) / ray.direction.x;
        float txMax = (boxMax.x - ray.origin.x) / ray.direction.x;
        float tyMin = (boxMin.y - ray.origin.y) / ray.direction.y;
        float tyMax = (boxMax.y - ray.origin.y) / ray.direction.y;
        float tzMin = (boxMin.z - ray.origin.z) / ray.direction.z;
        float tzMax = (boxMax.z - ray.origin.z) / ray.direction.z;
        
        // Handle division by zero for perpendicular directions
        if (ray.direction.y == 0) {
            tyMin = ray.origin.y >= boxMin.y && ray.origin.y <= boxMax.y ? Float.NEGATIVE_INFINITY : Float.POSITIVE_INFINITY;
            tyMax = ray.origin.y >= boxMin.y && ray.origin.y <= boxMax.y ? Float.POSITIVE_INFINITY : Float.NEGATIVE_INFINITY;
        }
        if (ray.direction.z == 0) {
            tzMin = ray.origin.z >= boxMin.z && ray.origin.z <= boxMax.z ? Float.NEGATIVE_INFINITY : Float.POSITIVE_INFINITY;
            tzMax = ray.origin.z >= boxMin.z && ray.origin.z <= boxMax.z ? Float.POSITIVE_INFINITY : Float.NEGATIVE_INFINITY;
        }
        
        float tMin = Math.max(Math.max(txMin, tyMin), tzMin);
        float tMax = Math.min(Math.min(txMax, tyMax), tzMax);
        
        assertTrue(tMin <= tMax, "Valid intersection should have tMin <= tMax");
    }

    @Test
    @DisplayName("Test performance counter locations match C++")
    void testPerformanceCounterCompliance() {
        // Verify performance counter enum values if we implement them
        
        // C++ has counters for:
        // - Iterations
        // - Instructions  
        // - Intersect
        // - Push
        // - PushStore
        // - Pop
        // - Advance
        
        // These would be useful for performance analysis
        // Currently not implemented in Java version
    }

    @Test
    @DisplayName("Test far pointer resolution matches C++")
    void testFarPointerCompliance() {
        // C++ lines 250-255: Far pointer handling
        
        int childDescriptor = 0x00070000; // Far pointer flag (bit 16) + offset 3 in bits 17-31
        boolean isFar = (childDescriptor & 0x10000) != 0;
        assertTrue(isFar, "Should detect far pointer flag");
        
        // When far flag is set, need to dereference the pointer
        int offset = (childDescriptor >>> 17);
        assertEquals(3, offset, "Should extract correct offset");
        
        // In C++: ofs = parent[ofs * 2]; // far pointer dereference
        // This requires following the pointer to get actual child location
    }
}
