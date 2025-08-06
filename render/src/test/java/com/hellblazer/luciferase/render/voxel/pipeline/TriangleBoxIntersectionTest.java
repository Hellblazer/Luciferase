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
    void testCoverageComputation() {
        // Triangle fully covers box
        var v0 = new Point3f(-2.0f, -2.0f, 0.0f);
        var v1 = new Point3f(2.0f, -2.0f, 0.0f);
        var v2 = new Point3f(0.0f, 2.0f, 0.0f);
        
        var boxCenter = new Point3f(0, 0, 0);
        var boxHalfSize = new Vector3f(0.5f, 0.5f, 0.5f);
        
        float coverage = TriangleBoxIntersection.computeCoverage(
            v0, v1, v2, boxCenter, boxHalfSize, 2
        );
        
        assertTrue(coverage > 0.0f);
        assertTrue(coverage <= 1.0f);
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
            v0, v1, v2, boxCenter, boxHalfSize, 2
        );
        
        assertEquals(0.0f, coverage);
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