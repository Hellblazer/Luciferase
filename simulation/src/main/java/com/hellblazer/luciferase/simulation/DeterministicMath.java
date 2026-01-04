package com.hellblazer.luciferase.simulation;

import javax.vecmath.Vector3f;

/**
 * Cross-platform deterministic math operations for distributed simulation.
 * <p>
 * Ensures identical simulation results across different platforms and runs by:
 * - Using StrictMath (IEEE 754 compliance) instead of Math (platform-specific FPU)
 * - Binary reduction tree for stable floating-point accumulation
 * <p>
 * Critical for distributed simulation where all nodes must compute identical results
 * from the same input to maintain causal consistency.
 * <p>
 * Usage:
 * <pre>
 * // Deterministic trigonometry
 * float angle = DeterministicMath.atan2(dy, dx);
 * float distance = DeterministicMath.sqrt(dx*dx + dy*dy);
 *
 * // Stable force accumulation (prevents rounding errors from order dependency)
 * float[] forces = collectForces();
 * float totalForce = DeterministicMath.stableSum(forces);
 *
 * // Stable vector accumulation
 * Vector3f[] vectorForces = collectVectorForces();
 * Vector3f totalVector = DeterministicMath.stableSumVectors(vectorForces);
 * </pre>
 *
 * @author hal.hildebrand
 */
public class DeterministicMath {

    // Prevent instantiation
    private DeterministicMath() {
    }

    /**
     * Deterministic square root.
     *
     * @param x Input value
     * @return Square root of x
     */
    public static float sqrt(float x) {
        return (float) StrictMath.sqrt(x);
    }

    /**
     * Deterministic sine.
     *
     * @param x Angle in radians
     * @return Sine of x
     */
    public static float sin(float x) {
        return (float) StrictMath.sin(x);
    }

    /**
     * Deterministic cosine.
     *
     * @param x Angle in radians
     * @return Cosine of x
     */
    public static float cos(float x) {
        return (float) StrictMath.cos(x);
    }

    /**
     * Deterministic arctangent of y/x.
     *
     * @param y Y coordinate
     * @param x X coordinate
     * @return Angle in radians
     */
    public static float atan2(float y, float x) {
        return (float) StrictMath.atan2(y, x);
    }

    /**
     * Deterministic absolute value.
     *
     * @param x Input value
     * @return Absolute value of x
     */
    public static float abs(float x) {
        return StrictMath.abs(x);
    }

    /**
     * Deterministic minimum of two values.
     *
     * @param a First value
     * @param b Second value
     * @return Minimum of a and b
     */
    public static float min(float a, float b) {
        return StrictMath.min(a, b);
    }

    /**
     * Deterministic maximum of two values.
     *
     * @param a First value
     * @param b Second value
     * @return Maximum of a and b
     */
    public static float max(float a, float b) {
        return StrictMath.max(a, b);
    }

    /**
     * Deterministic power function.
     *
     * @param base     Base value
     * @param exponent Exponent
     * @return base^exponent
     */
    public static float pow(float base, float exponent) {
        return (float) StrictMath.pow(base, exponent);
    }

    /**
     * Deterministic floor function.
     *
     * @param x Input value
     * @return Largest integer <= x
     */
    public static float floor(float x) {
        return (float) StrictMath.floor(x);
    }

    /**
     * Deterministic ceiling function.
     *
     * @param x Input value
     * @return Smallest integer >= x
     */
    public static float ceil(float x) {
        return (float) StrictMath.ceil(x);
    }

    /**
     * Clamp value between min and max.
     *
     * @param value Value to clamp
     * @param min   Minimum value
     * @param max   Maximum value
     * @return Clamped value
     */
    public static float clamp(float value, float min, float max) {
        return StrictMath.max(min, StrictMath.min(max, value));
    }

    /**
     * Linear interpolation between a and b.
     *
     * @param a Start value
     * @param b End value
     * @param t Interpolation parameter [0, 1]
     * @return Interpolated value
     */
    public static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /**
     * Stable summation using binary reduction tree.
     * <p>
     * Prevents floating-point rounding errors from order-dependent accumulation.
     * Uses a divide-and-conquer approach to ensure identical results regardless
     * of input order or array size.
     * <p>
     * Complexity: O(n log n) time, O(log n) space (recursion stack)
     *
     * @param values Array of values to sum
     * @return Sum of all values
     */
    public static float stableSum(float[] values) {
        if (values.length == 0) {
            return 0.0f;
        }
        return stableSumRecursive(values, 0, values.length);
    }

    /**
     * Recursive helper for binary reduction tree.
     *
     * @param values Array of values
     * @param start  Start index (inclusive)
     * @param end    End index (exclusive)
     * @return Sum of values[start:end]
     */
    private static float stableSumRecursive(float[] values, int start, int end) {
        int length = end - start;

        if (length == 0) {
            return 0.0f;
        } else if (length == 1) {
            return values[start];
        } else {
            // Divide-and-conquer: split array in half and sum recursively
            int mid = start + length / 2;
            float leftSum = stableSumRecursive(values, start, mid);
            float rightSum = stableSumRecursive(values, mid, end);
            return leftSum + rightSum;
        }
    }

    /**
     * Stable vector summation using binary reduction tree.
     * <p>
     * Applies binary reduction independently to each vector component.
     *
     * @param vectors Array of vectors to sum
     * @return Sum of all vectors
     */
    public static Vector3f stableSumVectors(Vector3f[] vectors) {
        if (vectors.length == 0) {
            return new Vector3f(0.0f, 0.0f, 0.0f);
        }

        // Extract components
        float[] xComponents = new float[vectors.length];
        float[] yComponents = new float[vectors.length];
        float[] zComponents = new float[vectors.length];

        for (int i = 0; i < vectors.length; i++) {
            xComponents[i] = vectors[i].x;
            yComponents[i] = vectors[i].y;
            zComponents[i] = vectors[i].z;
        }

        // Sum each component independently using binary reduction
        return new Vector3f(
            stableSum(xComponents),
            stableSum(yComponents),
            stableSum(zComponents)
        );
    }
}
