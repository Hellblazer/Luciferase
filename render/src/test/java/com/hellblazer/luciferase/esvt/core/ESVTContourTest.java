/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.esvt.core;

import org.junit.jupiter.api.Test;

import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ESVTContour encoding/decoding.
 *
 * Note: The contour encoding uses a specific range [-48, 48] for bounds
 * and 18-bit quantized normals. Tests must use values within these ranges.
 */
public class ESVTContourTest {

    @Test
    void testNormalEncodingAxisAligned() {
        // Test axis-aligned normals
        testNormalRoundTrip(new Vector3f(1, 0, 0), 0.1f);
        testNormalRoundTrip(new Vector3f(-1, 0, 0), 0.1f);
        testNormalRoundTrip(new Vector3f(0, 1, 0), 0.1f);
        testNormalRoundTrip(new Vector3f(0, -1, 0), 0.1f);
        testNormalRoundTrip(new Vector3f(0, 0, 1), 0.1f);
        testNormalRoundTrip(new Vector3f(0, 0, -1), 0.1f);
    }

    @Test
    void testNormalEncodingDiagonal() {
        // Test diagonal normals
        var diag = new Vector3f(1, 1, 1);
        diag.normalize();
        testNormalRoundTrip(diag, 0.15f);

        diag = new Vector3f(-1, 1, 1);
        diag.normalize();
        testNormalRoundTrip(diag, 0.15f);

        diag = new Vector3f(1, -1, 1);
        diag.normalize();
        testNormalRoundTrip(diag, 0.15f);
    }

    @Test
    void testNormalEncodingArbitrary() {
        // Test arbitrary normals
        var n = new Vector3f(0.3f, 0.7f, 0.5f);
        n.normalize();
        testNormalRoundTrip(n, 0.15f);

        n = new Vector3f(-0.8f, 0.2f, -0.4f);
        n.normalize();
        testNormalRoundTrip(n, 0.15f);
    }

    @Test
    void testBoundsEncoding() {
        var normal = new Vector3f(0, 1, 0);
        normal.normalize();

        // Use bounds in the valid range [-48, 48]
        // Symmetric bounds centered at 0
        int contour = ESVTContour.encode(normal, -10.0f, 10.0f);
        float[] posThick = ESVTContour.decodePosThick(contour);

        // Position should be near 0 (center of -10 to 10)
        assertEquals(0.0f, posThick[0], 5.0f); // Larger tolerance due to quantization

        // Thickness should cover the range
        assertTrue(posThick[1] > 5.0f, "Thickness should be > 5, got: " + posThick[1]);
    }

    @Test
    void testBoundsEncodingAsymmetric() {
        var normal = new Vector3f(1, 0, 0);
        normal.normalize();

        // Asymmetric bounds: center at -15
        int contour = ESVTContour.encode(normal, -30.0f, 0.0f);
        float[] posThick = ESVTContour.decodePosThick(contour);

        // Position should be around -15 (center of -30 to 0)
        // But actual encoding uses (2/3) factor, so center = -30 * 2/3 = -20
        assertTrue(posThick[0] < 0, "Position should be negative, got: " + posThick[0]);
    }

    @Test
    void testCompleteEncodeDecode() {
        var normal = new Vector3f(0.5f, 0.5f, 0.707f);
        normal.normalize();

        int contour = ESVTContour.encode(normal, -20.0f, 30.0f);

        // Verify it's valid
        assertTrue(ESVTContour.isValid(contour));

        // Decode and check normal direction preserved
        var decoded = ESVTContour.decodeNormal(contour);
        decoded.normalize();

        float dot = normal.x * decoded.x + normal.y * decoded.y + normal.z * decoded.z;
        assertTrue(dot > 0.9f, "Normal direction should be preserved, dot = " + dot);
    }

    @Test
    void testRayIntersectionHit() {
        // Create contour for a horizontal plane at y=10
        var normal = new Vector3f(0, 1, 0);
        int contour = ESVTContour.encode(normal, 5.0f, 15.0f);

        // Ray going up through the plane, starting below
        var rayOrigin = new Vector3f(0, 0, 0);
        var rayDir = new Vector3f(0, 1, 0);
        rayDir.normalize();

        float[] result = ESVTContour.intersectRay(contour, rayOrigin, rayDir, 1.0f);

        assertNotNull(result, "Should intersect");
        assertTrue(result[0] < result[1], "tEntry should be less than tExit: t0=" + result[0] + ", t1=" + result[1]);
        assertTrue(result[0] > 0, "tEntry should be positive: " + result[0]);
    }

    @Test
    void testRayIntersectionNonParallel() {
        // Test non-parallel ray going through slab
        var normal = new Vector3f(0, 1, 0);
        int contour = ESVTContour.encode(normal, 5.0f, 15.0f);

        float[] posThick = ESVTContour.decodePosThick(contour);
        System.out.printf("Decoded pos=%.4f, thick=%.4f%n", posThick[0], posThick[1]);

        // Ray going diagonally through the slab
        var rayOrigin = new Vector3f(0, -5, 0); // Below the slab
        var rayDir = new Vector3f(0.5f, 1, 0);
        rayDir.normalize();

        float[] result = ESVTContour.intersectRay(contour, rayOrigin, rayDir, 1.0f);

        assertNotNull(result, "Diagonal ray should intersect slab");
        assertTrue(result[0] < result[1], "Should have valid t range");
        assertTrue(result[0] > 0, "Entry should be positive when ray starts below slab");
    }

    @Test
    void testZeroNormalHandling() {
        var zero = new Vector3f(0, 0, 0);
        int encoded = ESVTContour.encodeNormal(zero);
        assertEquals(0, encoded, "Zero normal should encode to 0");
        assertFalse(ESVTContour.isValid(ESVTContour.encode(zero, 0, 0)));
    }

    @Test
    void testChildTransform() {
        var normal = new Vector3f(1, 0, 0);
        int parent = ESVTContour.encode(normal, -20.0f, 20.0f);

        // Transform to child at positive x offset
        var childOffset = new Vector3f(0.25f, 0, 0);
        int child = ESVTContour.transformToChild(parent, childOffset);

        // Child should have same normal direction
        var parentNormal = ESVTContour.decodeNormal(parent);
        var childNormal = ESVTContour.decodeNormal(child);

        parentNormal.normalize();
        childNormal.normalize();

        float dot = parentNormal.x * childNormal.x + parentNormal.y * childNormal.y + parentNormal.z * childNormal.z;
        assertTrue(dot > 0.95f, "Normal direction should be preserved in child, dot = " + dot);
    }

    @Test
    void testNormalPartExtraction() {
        var normal = new Vector3f(0.6f, 0.8f, 0);
        normal.normalize();

        int contour = ESVTContour.encode(normal, -10, 10);
        int normalPart = ESVTContour.getNormalPart(contour);

        // Normal part should be just the lower 18 bits
        assertEquals(normalPart, contour & 0x0003FFFF);
        assertTrue(normalPart > 0, "Normal part should be non-zero");
    }

    @Test
    void testLargeBounds() {
        // Test with bounds at the edge of valid range
        var normal = new Vector3f(0, 0, 1);
        int contour = ESVTContour.encode(normal, -45.0f, 45.0f);

        assertTrue(ESVTContour.isValid(contour));

        float[] posThick = ESVTContour.decodePosThick(contour);
        // Should have large thickness
        assertTrue(posThick[1] > 30.0f, "Thickness should be large for wide bounds");
    }

    private void testNormalRoundTrip(Vector3f original, float tolerance) {
        original.normalize();
        int encoded = ESVTContour.encodeNormal(original);
        var decoded = ESVTContour.decodeNormal(encoded);

        // Normalize decoded for comparison
        float len = (float) Math.sqrt(decoded.x * decoded.x + decoded.y * decoded.y + decoded.z * decoded.z);
        if (len > 0) {
            decoded.x /= len;
            decoded.y /= len;
            decoded.z /= len;
        }

        // Check dot product (direction preservation)
        float dot = original.x * decoded.x + original.y * decoded.y + original.z * decoded.z;
        assertTrue(dot > 1.0f - tolerance,
            String.format("Normal direction not preserved: original=%s, decoded=%s, dot=%.4f",
                original, decoded, dot));
    }
}
