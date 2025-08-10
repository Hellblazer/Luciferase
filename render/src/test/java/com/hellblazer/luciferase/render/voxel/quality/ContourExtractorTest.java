package com.hellblazer.luciferase.render.voxel.quality;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for ContourExtractor.
 * Validates contour extraction, encoding, and error metrics.
 */
public class ContourExtractorTest {
    
    private ContourExtractor extractor;
    private static final float EPSILON = 1e-5f;
    
    @BeforeEach
    public void setUp() {
        extractor = new ContourExtractor();
    }
    
    @Test
    public void testSingleTriangleContour() {
        // Create a single triangle
        var triangles = new ArrayList<ContourExtractor.Triangle>();
        triangles.add(new ContourExtractor.Triangle(
            new Point3f(0, 0, 0),
            new Point3f(1, 0, 0),
            new Point3f(0, 1, 0)
        ));
        
        // Create voxel containing the triangle
        var voxel = new ContourExtractor.AABB(
            new Point3f(0.5f, 0.5f, 0),
            new Vector3f(1, 1, 1)
        );
        
        // Extract contour
        var contour = extractor.extractContour(triangles, voxel);
        
        assertNotNull(contour, "Contour should be extracted for single triangle");
        
        // The normal should be along Z axis for a triangle in XY plane
        assertEquals(0, contour.normal.x, EPSILON);
        assertEquals(0, contour.normal.y, EPSILON);
        assertEquals(1, Math.abs(contour.normal.z), EPSILON);
    }
    
    @Test
    public void testMultipleCoplanarTriangles() {
        // Create multiple triangles in the same plane
        var triangles = new ArrayList<ContourExtractor.Triangle>();
        triangles.add(new ContourExtractor.Triangle(
            new Point3f(0, 0, 0),
            new Point3f(1, 0, 0),
            new Point3f(0, 1, 0)
        ));
        triangles.add(new ContourExtractor.Triangle(
            new Point3f(1, 0, 0),
            new Point3f(1, 1, 0),
            new Point3f(0, 1, 0)
        ));
        
        var voxel = new ContourExtractor.AABB(
            new Point3f(0.5f, 0.5f, 0),
            new Vector3f(1, 1, 1)
        );
        
        var contour = extractor.extractContour(triangles, voxel);
        
        assertNotNull(contour);
        
        // Should extract a single dominant plane along Z
        assertEquals(0, contour.normal.x, EPSILON);
        assertEquals(0, contour.normal.y, EPSILON);
        assertEquals(1, Math.abs(contour.normal.z), EPSILON);
    }
    
    @Test
    public void testNonCoplanarTriangles() {
        // Create triangles at different angles
        var triangles = new ArrayList<ContourExtractor.Triangle>();
        
        // Triangle in XY plane
        triangles.add(new ContourExtractor.Triangle(
            new Point3f(0, 0, 0),
            new Point3f(1, 0, 0),
            new Point3f(0, 1, 0)
        ));
        
        // Triangle in XZ plane
        triangles.add(new ContourExtractor.Triangle(
            new Point3f(0, 0, 0),
            new Point3f(1, 0, 0),
            new Point3f(0, 0, 1)
        ));
        
        var voxel = new ContourExtractor.AABB(
            new Point3f(0.5f, 0.5f, 0.5f),
            new Vector3f(1, 1, 1)
        );
        
        var contour = extractor.extractContour(triangles, voxel);
        
        assertNotNull(contour);
        
        // The dominant plane should be a weighted average
        // Both triangles contribute, so normal should be between their normals
        assertTrue(contour.normal.lengthSquared() > 0.9f, "Normal should be normalized");
    }
    
    @Test
    public void testContourEncoding() {
        // Test encoding and decoding of contour data
        var normal = new Vector3f(0.577f, 0.577f, 0.577f); // Normalized (1,1,1)
        float position = 0.5f;
        float thickness = 0.25f;
        
        int encoded = extractor.encodeContour(normal, position, thickness);
        
        // Verify encoding is within 32 bits
        assertTrue(encoded != 0, "Encoded value should be non-zero");
        
        // Decode and verify
        var decoded = ContourExtractor.decodeContour(encoded);
        
        assertNotNull(decoded);
        
        // Check normal is approximately preserved (accounting for quantization)
        assertEquals(normal.x, decoded.normal.x, 0.01f);
        assertEquals(normal.y, decoded.normal.y, 0.01f);
        assertEquals(normal.z, decoded.normal.z, 0.01f);
        
        // Check thickness is preserved (quantized to 4 levels)
        assertEquals(thickness, decoded.thickness, 0.34f); // 1/3 precision
    }
    
    @Test
    public void testEncodingEdgeCases() {
        // Test encoding with extreme normal values
        var testNormals = new Vector3f[] {
            new Vector3f(1, 0, 0),   // Along X
            new Vector3f(0, 1, 0),   // Along Y
            new Vector3f(0, 0, 1),   // Along Z
            new Vector3f(-1, 0, 0),  // Negative X
            new Vector3f(0, -1, 0),  // Negative Y
            new Vector3f(0, 0, -1),  // Negative Z
        };
        
        for (var normal : testNormals) {
            int encoded = extractor.encodeContour(normal, 0, 0.5f);
            var decoded = ContourExtractor.decodeContour(encoded);
            
            // Check dot product is close to 1 (same direction)
            float dot = Math.abs(normal.dot(decoded.normal));
            assertTrue(dot > 0.99f, "Normal should be preserved for " + normal);
        }
    }
    
    @Test
    public void testContourError() {
        // Create triangles with known normals
        var triangles = new ArrayList<ContourExtractor.Triangle>();
        
        // All triangles pointing up (Z direction)
        for (int i = 0; i < 3; i++) {
            triangles.add(new ContourExtractor.Triangle(
                new Point3f(i, 0, 0),
                new Point3f(i + 1, 0, 0),
                new Point3f(i, 1, 0)
            ));
        }
        
        // Perfect contour (aligned with triangles)
        var perfectContour = new ContourExtractor.Contour(
            new Vector3f(0, 0, 1),
            0, 0.1f, 0
        );
        
        float perfectError = extractor.evaluateContourError(perfectContour, triangles);
        assertEquals(0, perfectError, EPSILON, "Perfect alignment should have zero error");
        
        // Perpendicular contour (worst case)
        var perpContour = new ContourExtractor.Contour(
            new Vector3f(1, 0, 0),
            0, 0.1f, 0
        );
        
        float perpError = extractor.evaluateContourError(perpContour, triangles);
        assertTrue(perpError > 0.9f, "Perpendicular contour should have high error");
        
        // 45-degree contour
        var angledContour = new ContourExtractor.Contour(
            new Vector3f(0.707f, 0, 0.707f),
            0, 0.1f, 0
        );
        
        float angledError = extractor.evaluateContourError(angledContour, triangles);
        assertTrue(angledError > 0.2f && angledError < 0.4f, 
            "45-degree contour should have moderate error");
    }
    
    @Test
    public void testEmptyTriangleList() {
        var voxel = new ContourExtractor.AABB(
            new Point3f(0, 0, 0),
            new Vector3f(1, 1, 1)
        );
        
        var contour = extractor.extractContour(new ArrayList<>(), voxel);
        assertNull(contour, "Empty triangle list should return null contour");
        
        contour = extractor.extractContour(null, voxel);
        assertNull(contour, "Null triangle list should return null contour");
    }
    
    @Test
    public void testDegenerateTriangle() {
        // Create a degenerate triangle (all vertices collinear)
        var triangles = new ArrayList<ContourExtractor.Triangle>();
        triangles.add(new ContourExtractor.Triangle(
            new Point3f(0, 0, 0),
            new Point3f(1, 0, 0),
            new Point3f(2, 0, 0)  // Collinear with first two
        ));
        
        var voxel = new ContourExtractor.AABB(
            new Point3f(1, 0, 0),
            new Vector3f(1, 1, 1)
        );
        
        var contour = extractor.extractContour(triangles, voxel);
        
        // Should still extract something, even if degenerate
        // The implementation should handle this gracefully
        assertNotNull(contour, "Should handle degenerate triangles gracefully");
    }
    
    @Test
    public void testLargeTriangleSet() {
        // Test with many triangles to verify performance and stability
        var triangles = new ArrayList<ContourExtractor.Triangle>();
        
        // Create a mesh of 100 triangles
        for (int i = 0; i < 10; i++) {
            for (int j = 0; j < 10; j++) {
                triangles.add(new ContourExtractor.Triangle(
                    new Point3f(i * 0.1f, j * 0.1f, 0),
                    new Point3f((i + 1) * 0.1f, j * 0.1f, 0),
                    new Point3f(i * 0.1f, (j + 1) * 0.1f, 0)
                ));
            }
        }
        
        var voxel = new ContourExtractor.AABB(
            new Point3f(0.5f, 0.5f, 0),
            new Vector3f(1, 1, 1)
        );
        
        long startTime = System.nanoTime();
        var contour = extractor.extractContour(triangles, voxel);
        long elapsedTime = System.nanoTime() - startTime;
        
        assertNotNull(contour);
        
        // Should complete in reasonable time (< 100ms)
        assertTrue(elapsedTime < 100_000_000L, 
            "Large triangle set should process in < 100ms");
        
        // All triangles are in XY plane, so normal should be along Z
        assertEquals(0, contour.normal.x, 0.01f);
        assertEquals(0, contour.normal.y, 0.01f);
        assertEquals(1, Math.abs(contour.normal.z), 0.01f);
    }
    
    @Test
    public void testContourThickness() {
        // Create triangles at different Z levels to test thickness calculation
        var triangles = new ArrayList<ContourExtractor.Triangle>();
        
        // Triangle at Z = 0
        triangles.add(new ContourExtractor.Triangle(
            new Point3f(0, 0, 0),
            new Point3f(1, 0, 0),
            new Point3f(0, 1, 0)
        ));
        
        // Triangle at Z = 0.5
        triangles.add(new ContourExtractor.Triangle(
            new Point3f(0, 0, 0.5f),
            new Point3f(1, 0, 0.5f),
            new Point3f(0, 1, 0.5f)
        ));
        
        var voxel = new ContourExtractor.AABB(
            new Point3f(0.5f, 0.5f, 0.25f),
            new Vector3f(1, 1, 1)
        );
        
        var contour = extractor.extractContour(triangles, voxel);
        
        assertNotNull(contour);
        
        // Thickness should be non-zero due to Z separation
        assertTrue(contour.thickness > 0, "Thickness should be positive for separated triangles");
        assertTrue(contour.thickness <= 1.0f, "Thickness should be normalized to [0,1]");
    }
}