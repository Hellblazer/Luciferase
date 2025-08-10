package com.hellblazer.luciferase.render.voxel.pipeline;

import org.junit.jupiter.api.Test;
import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for triangle-box intersection using Separating Axis Theorem.
 */
class TriangleBoxIntersectionTest {
    
    @Test
    void testTriangleFullyInsideBox() {
        // Triangle completely inside box
        var v0 = new Point3f(-0.5f, -0.5f, 0.0f);
        var v1 = new Point3f(0.5f, -0.5f, 0.0f);
        var v2 = new Point3f(0.0f, 0.5f, 0.0f);
        
        var boxCenter = new Point3f(0, 0, 0);
        var boxHalfSize = new Vector3f(1, 1, 1);
        
        assertTrue(TriangleBoxIntersection.intersects(v0, v1, v2, boxCenter, boxHalfSize));
    }
    
    @Test
    void testTriangleFullyOutsideBox() {
        // Triangle completely outside box
        var v0 = new Point3f(2.0f, 2.0f, 2.0f);
        var v1 = new Point3f(3.0f, 2.0f, 2.0f);
        var v2 = new Point3f(2.5f, 3.0f, 2.0f);
        
        var boxCenter = new Point3f(0, 0, 0);
        var boxHalfSize = new Vector3f(1, 1, 1);
        
        assertFalse(TriangleBoxIntersection.intersects(v0, v1, v2, boxCenter, boxHalfSize));
    }
    
    @Test
    void testTrianglePartiallyIntersectsBox() {
        // Triangle clips through box
        var v0 = new Point3f(-2.0f, 0.0f, 0.0f);
        var v1 = new Point3f(2.0f, 0.0f, 0.0f);
        var v2 = new Point3f(0.0f, 2.0f, 0.0f);
        
        var boxCenter = new Point3f(0, 0, 0);
        var boxHalfSize = new Vector3f(1, 1, 1);
        
        assertTrue(TriangleBoxIntersection.intersects(v0, v1, v2, boxCenter, boxHalfSize));
    }
    
    @Test
    void testTriangleEdgeTouchesBox() {
        // Triangle edge touches box edge
        var v0 = new Point3f(-1.0f, -1.0f, 1.0f);
        var v1 = new Point3f(1.0f, -1.0f, 1.0f);
        var v2 = new Point3f(0.0f, 1.0f, 1.0f);
        
        var boxCenter = new Point3f(0, 0, 0);
        var boxHalfSize = new Vector3f(1, 1, 1);
        
        assertTrue(TriangleBoxIntersection.intersects(v0, v1, v2, boxCenter, boxHalfSize));
    }
    
    @Test
    void testDegenerateTriangle() {
        // Degenerate triangle (all vertices collinear)
        var v0 = new Point3f(0.0f, 0.0f, 0.0f);
        var v1 = new Point3f(1.0f, 0.0f, 0.0f);
        var v2 = new Point3f(0.5f, 0.0f, 0.0f);
        
        var boxCenter = new Point3f(0, 0, 0);
        var boxHalfSize = new Vector3f(1, 1, 1);
        
        assertTrue(TriangleBoxIntersection.intersects(v0, v1, v2, boxCenter, boxHalfSize));
    }
    
    @Test
    void testLargeTriangleSmallBox() {
        // Large triangle, small box at center
        var v0 = new Point3f(-10.0f, -10.0f, 0.0f);
        var v1 = new Point3f(10.0f, -10.0f, 0.0f);
        var v2 = new Point3f(0.0f, 10.0f, 0.0f);
        
        var boxCenter = new Point3f(0, 0, 0);
        var boxHalfSize = new Vector3f(0.1f, 0.1f, 0.1f);
        
        assertTrue(TriangleBoxIntersection.intersects(v0, v1, v2, boxCenter, boxHalfSize));
    }
    
    @Test
    void testCoverageComputationLegacy() {
        // Triangle fully covers box - test legacy sampling method
        var v0 = new Point3f(-2.0f, -2.0f, 0.0f);
        var v1 = new Point3f(2.0f, -2.0f, 0.0f);
        var v2 = new Point3f(0.0f, 2.0f, 0.0f);
        
        var boxCenter = new Point3f(0, 0, 0);
        var boxHalfSize = new Vector3f(0.5f, 0.5f, 0.5f);
        
        float coverage = TriangleBoxIntersection.computeCoverageSampled(
            v0, v1, v2, boxCenter, boxHalfSize, 2
        );
        
        assertTrue(coverage > 0.0f);
        assertTrue(coverage <= 1.0f);
    }
    
    @Test
    void testESVOTriangleClipping() {
        // Test ESVO-style triangle clipping for precise coverage
        var v0 = new Point3f(-1.0f, -1.0f, 0.0f);
        var v1 = new Point3f(1.0f, -1.0f, 0.0f);
        var v2 = new Point3f(0.0f, 1.0f, 0.0f);
        
        var boxCenter = new Point3f(0, 0, 0);
        var boxHalfSize = new Vector3f(0.5f, 0.5f, 0.5f);
        
        // Test new clipping-based coverage
        float clippedCoverage = TriangleBoxIntersection.computeCoverage(
            v0, v1, v2, boxCenter, boxHalfSize
        );
        
        // Test legacy sampling-based coverage
        float sampledCoverage = TriangleBoxIntersection.computeCoverageSampled(
            v0, v1, v2, boxCenter, boxHalfSize, 4
        );
        
        // Both should be positive and <= 1
        assertTrue(clippedCoverage > 0.0f);
        assertTrue(clippedCoverage <= 1.0f);
        assertTrue(sampledCoverage > 0.0f);
        assertTrue(sampledCoverage <= 1.0f);
        
        // Clipped method should be at least as accurate as sampling
        // (allowing for some variation due to sampling vs geometric precision)
        assertTrue(Math.abs(clippedCoverage - sampledCoverage) <= 0.5f);
    }
    
    @Test
    void testTriangleClippingBoundaryCase() {
        // Triangle exactly at box boundary
        var v0 = new Point3f(-0.5f, -0.5f, 0.0f);
        var v1 = new Point3f(0.5f, -0.5f, 0.0f);
        var v2 = new Point3f(0.0f, 0.5f, 0.0f);
        
        var boxCenter = new Point3f(0, 0, 0);
        var boxHalfSize = new Vector3f(0.5f, 0.5f, 0.5f);
        
        float coverage = TriangleBoxIntersection.computeCoverage(
            v0, v1, v2, boxCenter, boxHalfSize
        );
        
        // Should have significant coverage since triangle fits within box bounds
        // Triangle area is 0.5, box face area is 1.0, so coverage should be 0.5
        assertTrue(coverage >= 0.5f);
        assertTrue(coverage <= 1.0f);
    }
    
    @Test
    void testBarycentricCoordinates() {
        // Test barycentric coordinate handling
        var v0 = new Point3f(0.0f, 0.0f, 0.0f);
        var v1 = new Point3f(1.0f, 0.0f, 0.0f);
        var v2 = new Point3f(0.0f, 1.0f, 0.0f);
        
        var boxCenter = new Point3f(0.25f, 0.25f, 0.0f);
        var boxHalfSize = new Vector3f(0.1f, 0.1f, 0.1f);
        
        var clippedVertices = new java.util.ArrayList<TriangleBoxIntersection.BarycentricCoord>();
        int numClipped = TriangleBoxIntersection.clipTriangleToBox(
            clippedVertices, v0, v1, v2, boxCenter, boxHalfSize
        );
        
        // Should produce a clipped polygon
        assertTrue(numClipped >= 3);
        assertTrue(numClipped <= 8); // Maximum from clipping a triangle against 6 planes
        
        // All barycentric coordinates should be valid (sum to 1, all non-negative)
        for (var coord : clippedVertices) {
            assertTrue(coord.u >= -0.01f); // Small epsilon for numerical precision
            assertTrue(coord.v >= -0.01f);
            assertTrue(coord.w >= -0.01f);
            float sum = coord.u + coord.v + coord.w;
            assertEquals(1.0f, sum, 0.01f);
        }
    }
    
    @Test
    void testNoCoverage() {
        // Triangle doesn't intersect box
        var v0 = new Point3f(5.0f, 5.0f, 5.0f);
        var v1 = new Point3f(6.0f, 5.0f, 5.0f);
        var v2 = new Point3f(5.5f, 6.0f, 5.0f);
        
        var boxCenter = new Point3f(0, 0, 0);
        var boxHalfSize = new Vector3f(1, 1, 1);
        
        float coverage = TriangleBoxIntersection.computeCoverage(
            v0, v1, v2, boxCenter, boxHalfSize
        );
        
        assertEquals(0.0f, coverage);
    }
    
    @Test
    void testFullCoverage() {
        // Large triangle completely covering small box
        var v0 = new Point3f(-5.0f, -5.0f, 0.0f);
        var v1 = new Point3f(5.0f, -5.0f, 0.0f);
        var v2 = new Point3f(0.0f, 5.0f, 0.0f);
        
        var boxCenter = new Point3f(0, 0, 0);
        var boxHalfSize = new Vector3f(0.1f, 0.1f, 0.1f);
        
        float coverage = TriangleBoxIntersection.computeCoverage(
            v0, v1, v2, boxCenter, boxHalfSize
        );
        
        // Should be close to full coverage
        assertTrue(coverage > 0.8f);
        assertTrue(coverage <= 1.0f);
    }
    
    @Test
    void testOrthogonalTriangles() {
        // Test triangles aligned with coordinate axes
        
        // XY plane triangle
        var v0 = new Point3f(0.0f, 0.0f, 0.0f);
        var v1 = new Point3f(1.0f, 0.0f, 0.0f);
        var v2 = new Point3f(0.0f, 1.0f, 0.0f);
        
        var boxCenter = new Point3f(0.5f, 0.5f, 0.5f);
        var boxHalfSize = new Vector3f(0.5f, 0.5f, 0.5f);
        
        assertTrue(TriangleBoxIntersection.intersects(v0, v1, v2, boxCenter, boxHalfSize));
        
        // YZ plane triangle
        v0 = new Point3f(0.0f, 0.0f, 0.0f);
        v1 = new Point3f(0.0f, 1.0f, 0.0f);
        v2 = new Point3f(0.0f, 0.0f, 1.0f);
        
        assertTrue(TriangleBoxIntersection.intersects(v0, v1, v2, boxCenter, boxHalfSize));
        
        // XZ plane triangle
        v0 = new Point3f(0.0f, 0.0f, 0.0f);
        v1 = new Point3f(1.0f, 0.0f, 0.0f);
        v2 = new Point3f(0.0f, 0.0f, 1.0f);
        
        assertTrue(TriangleBoxIntersection.intersects(v0, v1, v2, boxCenter, boxHalfSize));
    }
    
    @Test
    void testBoxCornerCases() {
        var boxCenter = new Point3f(0, 0, 0);
        var boxHalfSize = new Vector3f(1, 1, 1);
        
        // Triangle touches box corner
        var v0 = new Point3f(1.0f, 1.0f, 1.0f);
        var v1 = new Point3f(1.5f, 1.0f, 1.0f);
        var v2 = new Point3f(1.0f, 1.5f, 1.0f);
        
        assertTrue(TriangleBoxIntersection.intersects(v0, v1, v2, boxCenter, boxHalfSize));
        
        // Triangle passes through box diagonal
        v0 = new Point3f(-2.0f, -2.0f, -2.0f);
        v1 = new Point3f(2.0f, 2.0f, 2.0f);
        v2 = new Point3f(-2.0f, 2.0f, 0.0f);
        
        assertTrue(TriangleBoxIntersection.intersects(v0, v1, v2, boxCenter, boxHalfSize));
    }
}