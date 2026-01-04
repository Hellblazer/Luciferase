package com.hellblazer.luciferase.simulation;

import org.junit.jupiter.api.Test;

import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DeterministicMath - cross-platform deterministic math operations.
 * <p>
 * DeterministicMath ensures identical simulation results across different platforms
 * and runs by using:
 * - StrictMath wrappers (IEEE 754 guarantees)
 * - Binary reduction tree for stable floating-point accumulation
 * <p>
 * Critical for distributed simulation where all nodes must compute identical results
 * from the same input.
 *
 * @author hal.hildebrand
 */
class DeterministicMathTest {

    private static final float EPSILON = 1e-6f;

    @Test
    void testSqrt() {
        assertEquals(2.0f, DeterministicMath.sqrt(4.0f), EPSILON);
        assertEquals(3.0f, DeterministicMath.sqrt(9.0f), EPSILON);
        assertEquals(0.0f, DeterministicMath.sqrt(0.0f), EPSILON);
    }

    @Test
    void testSin() {
        assertEquals(0.0f, DeterministicMath.sin(0.0f), EPSILON);
        assertEquals(1.0f, DeterministicMath.sin((float) (Math.PI / 2)), EPSILON);
        assertEquals(0.0f, DeterministicMath.sin((float) Math.PI), EPSILON);
    }

    @Test
    void testCos() {
        assertEquals(1.0f, DeterministicMath.cos(0.0f), EPSILON);
        assertEquals(0.0f, DeterministicMath.cos((float) (Math.PI / 2)), EPSILON);
        assertEquals(-1.0f, DeterministicMath.cos((float) Math.PI), EPSILON);
    }

    @Test
    void testAtan2() {
        assertEquals(0.0f, DeterministicMath.atan2(0.0f, 1.0f), EPSILON);
        assertEquals((float) (Math.PI / 2), DeterministicMath.atan2(1.0f, 0.0f), EPSILON);
        assertEquals((float) (Math.PI / 4), DeterministicMath.atan2(1.0f, 1.0f), EPSILON);
    }

    @Test
    void testAbs() {
        assertEquals(5.0f, DeterministicMath.abs(5.0f), EPSILON);
        assertEquals(5.0f, DeterministicMath.abs(-5.0f), EPSILON);
        assertEquals(0.0f, DeterministicMath.abs(0.0f), EPSILON);
    }

    @Test
    void testMin() {
        assertEquals(1.0f, DeterministicMath.min(1.0f, 2.0f), EPSILON);
        assertEquals(-5.0f, DeterministicMath.min(-5.0f, 3.0f), EPSILON);
        assertEquals(0.0f, DeterministicMath.min(0.0f, 0.0f), EPSILON);
    }

    @Test
    void testMax() {
        assertEquals(2.0f, DeterministicMath.max(1.0f, 2.0f), EPSILON);
        assertEquals(3.0f, DeterministicMath.max(-5.0f, 3.0f), EPSILON);
        assertEquals(0.0f, DeterministicMath.max(0.0f, 0.0f), EPSILON);
    }

    @Test
    void testStableSum_EmptyArray() {
        float[] forces = {};
        assertEquals(0.0f, DeterministicMath.stableSum(forces), EPSILON);
    }

    @Test
    void testStableSum_SingleElement() {
        float[] forces = {5.0f};
        assertEquals(5.0f, DeterministicMath.stableSum(forces), EPSILON);
    }

    @Test
    void testStableSum_TwoElements() {
        float[] forces = {3.0f, 7.0f};
        assertEquals(10.0f, DeterministicMath.stableSum(forces), EPSILON);
    }

    @Test
    void testStableSum_MultipleElements() {
        float[] forces = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f};
        assertEquals(15.0f, DeterministicMath.stableSum(forces), EPSILON);
    }

    @Test
    void testStableSum_OrderIndependent() {
        // Binary reduction should produce same result regardless of input order
        // (within floating-point precision limits)
        float[] forces1 = {1.0f, 2.0f, 3.0f, 4.0f};
        float[] forces2 = {4.0f, 3.0f, 2.0f, 1.0f};

        float sum1 = DeterministicMath.stableSum(forces1);
        float sum2 = DeterministicMath.stableSum(forces2);

        // Should be exactly equal due to binary reduction tree
        assertEquals(sum1, sum2, 0.0f,
                    "Binary reduction should produce identical results regardless of order");
    }

    @Test
    void testStableSum_NegativeValues() {
        float[] forces = {-1.0f, -2.0f, -3.0f};
        assertEquals(-6.0f, DeterministicMath.stableSum(forces), EPSILON);
    }

    @Test
    void testStableSum_MixedValues() {
        float[] forces = {5.0f, -3.0f, 2.0f, -1.0f};
        assertEquals(3.0f, DeterministicMath.stableSum(forces), EPSILON);
    }

    @Test
    void testStableSum_LargeArray() {
        // Test with power-of-2 size (optimal for binary reduction)
        float[] forces = new float[1024];
        for (int i = 0; i < forces.length; i++) {
            forces[i] = 1.0f;
        }

        assertEquals(1024.0f, DeterministicMath.stableSum(forces), EPSILON);
    }

    @Test
    void testStableSum_NonPowerOfTwo() {
        // Test with non-power-of-2 size
        float[] forces = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 7.0f};
        assertEquals(28.0f, DeterministicMath.stableSum(forces), EPSILON);
    }

    @Test
    void testStableSumVectors_Empty() {
        Vector3f[] forces = {};
        var result = DeterministicMath.stableSumVectors(forces);

        assertEquals(0.0f, result.x, EPSILON);
        assertEquals(0.0f, result.y, EPSILON);
        assertEquals(0.0f, result.z, EPSILON);
    }

    @Test
    void testStableSumVectors_Single() {
        Vector3f[] forces = {new Vector3f(1.0f, 2.0f, 3.0f)};
        var result = DeterministicMath.stableSumVectors(forces);

        assertEquals(1.0f, result.x, EPSILON);
        assertEquals(2.0f, result.y, EPSILON);
        assertEquals(3.0f, result.z, EPSILON);
    }

    @Test
    void testStableSumVectors_Multiple() {
        Vector3f[] forces = {
            new Vector3f(1.0f, 0.0f, 0.0f),
            new Vector3f(0.0f, 2.0f, 0.0f),
            new Vector3f(0.0f, 0.0f, 3.0f),
            new Vector3f(1.0f, 1.0f, 1.0f)
        };
        var result = DeterministicMath.stableSumVectors(forces);

        assertEquals(2.0f, result.x, EPSILON);
        assertEquals(3.0f, result.y, EPSILON);
        assertEquals(4.0f, result.z, EPSILON);
    }

    @Test
    void testStableSumVectors_OrderIndependent() {
        Vector3f[] forces1 = {
            new Vector3f(1.0f, 2.0f, 3.0f),
            new Vector3f(4.0f, 5.0f, 6.0f)
        };
        Vector3f[] forces2 = {
            new Vector3f(4.0f, 5.0f, 6.0f),
            new Vector3f(1.0f, 2.0f, 3.0f)
        };

        var result1 = DeterministicMath.stableSumVectors(forces1);
        var result2 = DeterministicMath.stableSumVectors(forces2);

        // Should produce identical results (binary reduction)
        assertEquals(result1.x, result2.x, 0.0f);
        assertEquals(result1.y, result2.y, 0.0f);
        assertEquals(result1.z, result2.z, 0.0f);
    }

    @Test
    void testStableSumVectors_NegativeValues() {
        Vector3f[] forces = {
            new Vector3f(5.0f, -3.0f, 2.0f),
            new Vector3f(-2.0f, 1.0f, -1.0f)
        };
        var result = DeterministicMath.stableSumVectors(forces);

        assertEquals(3.0f, result.x, EPSILON);
        assertEquals(-2.0f, result.y, EPSILON);
        assertEquals(1.0f, result.z, EPSILON);
    }

    @Test
    void testDeterminismAcrossMultipleCalls() {
        // Verify that repeated calls with same input produce identical results
        float[] forces = {0.1f, 0.2f, 0.3f, 0.4f, 0.5f};

        float result1 = DeterministicMath.stableSum(forces);
        float result2 = DeterministicMath.stableSum(forces);
        float result3 = DeterministicMath.stableSum(forces);

        // Should be bit-for-bit identical
        assertEquals(result1, result2, 0.0f);
        assertEquals(result2, result3, 0.0f);
    }

    @Test
    void testStrictMathUsage() {
        // Verify that StrictMath is used (not Math) by checking precision
        // StrictMath.sin uses specific algorithm, Math.sin may use hardware FPU
        float angle = 0.123456f;

        float strictResult = (float) StrictMath.sin(angle);
        float deterministicResult = DeterministicMath.sin(angle);

        // Should use StrictMath internally
        assertEquals(strictResult, deterministicResult, 0.0f,
                    "DeterministicMath should use StrictMath for cross-platform consistency");
    }

    @Test
    void testClamp() {
        assertEquals(5.0f, DeterministicMath.clamp(5.0f, 0.0f, 10.0f), EPSILON);
        assertEquals(0.0f, DeterministicMath.clamp(-5.0f, 0.0f, 10.0f), EPSILON);
        assertEquals(10.0f, DeterministicMath.clamp(15.0f, 0.0f, 10.0f), EPSILON);
    }

    @Test
    void testLerp() {
        assertEquals(0.0f, DeterministicMath.lerp(0.0f, 10.0f, 0.0f), EPSILON);
        assertEquals(10.0f, DeterministicMath.lerp(0.0f, 10.0f, 1.0f), EPSILON);
        assertEquals(5.0f, DeterministicMath.lerp(0.0f, 10.0f, 0.5f), EPSILON);
        assertEquals(2.5f, DeterministicMath.lerp(0.0f, 10.0f, 0.25f), EPSILON);
    }

    @Test
    void testPow() {
        assertEquals(8.0f, DeterministicMath.pow(2.0f, 3.0f), EPSILON);
        assertEquals(1.0f, DeterministicMath.pow(5.0f, 0.0f), EPSILON);
        assertEquals(25.0f, DeterministicMath.pow(5.0f, 2.0f), EPSILON);
    }

    @Test
    void testFloor() {
        assertEquals(5.0f, DeterministicMath.floor(5.7f), EPSILON);
        assertEquals(-6.0f, DeterministicMath.floor(-5.3f), EPSILON);
        assertEquals(0.0f, DeterministicMath.floor(0.9f), EPSILON);
    }

    @Test
    void testCeil() {
        assertEquals(6.0f, DeterministicMath.ceil(5.1f), EPSILON);
        assertEquals(-5.0f, DeterministicMath.ceil(-5.9f), EPSILON);
        assertEquals(1.0f, DeterministicMath.ceil(0.1f), EPSILON);
    }
}
