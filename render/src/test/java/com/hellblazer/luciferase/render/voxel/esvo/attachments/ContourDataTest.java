package com.hellblazer.luciferase.render.voxel.esvo.attachments;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Contour Data Tests")
class ContourDataTest {
    
    private static final float EPSILON = 0.001f;
    
    @Test
    @DisplayName("Should normalize input normal vector")
    void testNormalization() {
        // Non-normalized vector
        var contour = new ContourData(3, 4, 0, 5.0f);
        
        // Should be normalized (3-4-5 triangle)
        assertEquals(0.6f, contour.getNormalX(), EPSILON);
        assertEquals(0.8f, contour.getNormalY(), EPSILON);
        assertEquals(0.0f, contour.getNormalZ(), EPSILON);
        assertEquals(5.0f, contour.getDistance(), EPSILON);
    }
    
    @Test
    @DisplayName("Should handle zero normal vector")
    void testZeroNormal() {
        var contour = new ContourData(0, 0, 0, 1.0f);
        
        // Should default to up vector
        assertEquals(0.0f, contour.getNormalX(), EPSILON);
        assertEquals(0.0f, contour.getNormalY(), EPSILON);
        assertEquals(1.0f, contour.getNormalZ(), EPSILON);
    }
    
    @Test
    @DisplayName("Should serialize and deserialize correctly")
    void testSerialization() {
        var original = new ContourData(1, 0, 0, 3.5f);
        
        byte[] bytes = original.toBytes();
        assertEquals(16, bytes.length);
        
        var restored = ContourData.fromBytes(bytes);
        assertEquals(original.getNormalX(), restored.getNormalX(), EPSILON);
        assertEquals(original.getNormalY(), restored.getNormalY(), EPSILON);
        assertEquals(original.getNormalZ(), restored.getNormalZ(), EPSILON);
        assertEquals(original.getDistance(), restored.getDistance(), EPSILON);
    }
    
    @Test
    @DisplayName("Should read and write to memory segment")
    void testMemorySegmentIO() {
        try (var arena = Arena.ofConfined()) {
            var memory = arena.allocate(ContourData.CONTOUR_SIZE);
            
            var original = new ContourData(0, 1, 0, 2.5f);
            original.writeTo(memory, 0);
            
            var restored = ContourData.readFrom(memory, 0);
            assertEquals(original.getNormalX(), restored.getNormalX(), EPSILON);
            assertEquals(original.getNormalY(), restored.getNormalY(), EPSILON);
            assertEquals(original.getNormalZ(), restored.getNormalZ(), EPSILON);
            assertEquals(original.getDistance(), restored.getDistance(), EPSILON);
        }
    }
    
    @Test
    @DisplayName("Should interpolate between contours")
    void testInterpolation() {
        var c1 = new ContourData(1, 0, 0, 1.0f);
        var c2 = new ContourData(0, 1, 0, 3.0f);
        
        // Interpolate halfway
        var mid = ContourData.interpolate(c1, c2, 0.5f);
        
        // Should be normalized blend of normals
        float expectedLen = (float)Math.sqrt(0.5 * 0.5 + 0.5 * 0.5);
        assertEquals(0.5f / expectedLen, mid.getNormalX(), EPSILON);
        assertEquals(0.5f / expectedLen, mid.getNormalY(), EPSILON);
        assertEquals(0.0f, mid.getNormalZ(), EPSILON);
        assertEquals(2.0f, mid.getDistance(), EPSILON); // Average of 1 and 3
        
        // Test extremes
        var atC1 = ContourData.interpolate(c1, c2, 0.0f);
        assertEquals(c1.getNormalX(), atC1.getNormalX(), EPSILON);
        
        var atC2 = ContourData.interpolate(c1, c2, 1.0f);
        assertEquals(c2.getNormalX(), atC2.getNormalX(), EPSILON);
    }
    
    @Test
    @DisplayName("Should average multiple contours")
    void testAveraging() {
        var contours = new ContourData[] {
            new ContourData(1, 0, 0, 1.0f),
            new ContourData(0, 1, 0, 2.0f),
            new ContourData(0, 0, 1, 3.0f),
            new ContourData(-1, 0, 0, 4.0f)
        };
        
        var avg = ContourData.average(contours);
        
        // Average should be normalized
        assertTrue(avg.isValid());
        assertEquals(2.5f, avg.getDistance(), EPSILON); // Average of 1,2,3,4
    }
    
    @Test
    @DisplayName("Should handle empty average")
    void testEmptyAverage() {
        var avg = ContourData.average(new ContourData[0]);
        
        // Should return default up vector
        assertEquals(0.0f, avg.getNormalX(), EPSILON);
        assertEquals(0.0f, avg.getNormalY(), EPSILON);
        assertEquals(1.0f, avg.getNormalZ(), EPSILON);
        assertEquals(0.0f, avg.getDistance(), EPSILON);
    }
    
    @Test
    @DisplayName("Should validate contour data")
    void testValidation() {
        // Valid contour
        var valid = new ContourData(0, 0, 1, 1.0f);
        assertTrue(valid.isValid());
        
        // Invalid distance
        var invalid = new ContourData(0, 0, 1, -1.0f);
        assertFalse(invalid.isValid());
    }
    
    @Test
    @DisplayName("Should calculate intersection point")
    void testIntersectionPoint() {
        var contour = new ContourData(0, 0, 1, 5.0f);
        
        float[] origin = {0, 0, 0};
        float[] direction = {1, 0, 0}; // Ray along X axis
        
        float[] intersection = contour.getIntersectionPoint(
            origin[0], origin[1], origin[2],
            direction[0], direction[1], direction[2]
        );
        
        assertEquals(5.0f, intersection[0], EPSILON); // 5 units along X
        assertEquals(0.0f, intersection[1], EPSILON);
        assertEquals(0.0f, intersection[2], EPSILON);
    }
    
    @Test
    @DisplayName("Should create from intersection data")
    void testFromIntersection() {
        var contour = ContourData.fromIntersection(
            0, 0, 0,      // Ray origin
            1, 0, 0,      // Ray direction
            10.0f,        // Hit distance
            0, 1, 0       // Surface normal
        );
        
        assertEquals(0.0f, contour.getNormalX(), EPSILON);
        assertEquals(1.0f, contour.getNormalY(), EPSILON);
        assertEquals(0.0f, contour.getNormalZ(), EPSILON);
        assertEquals(10.0f, contour.getDistance(), EPSILON);
    }
}