/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.geometry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Point3i integer 3D point class.
 * 
 * @author hal.hildebrand
 */
public class Point3iTest {
    
    @Test
    public void testConstruction() {
        var point = new Point3i(1, 2, 3);
        assertEquals(1, point.x);
        assertEquals(2, point.y);
        assertEquals(3, point.z);
    }
    
    @Test
    public void testOrigin() {
        var origin = Point3i.origin();
        assertEquals(0, origin.x);
        assertEquals(0, origin.y);
        assertEquals(0, origin.z);
    }
    
    @Test
    public void testUniform() {
        var uniform = Point3i.uniform(5);
        assertEquals(5, uniform.x);
        assertEquals(5, uniform.y);
        assertEquals(5, uniform.z);
    }
    
    @Test
    public void testAdd() {
        var p1 = new Point3i(1, 2, 3);
        var p2 = new Point3i(4, 5, 6);
        var result = p1.add(p2);
        
        assertEquals(5, result.x);
        assertEquals(7, result.y);
        assertEquals(9, result.z);
        
        // Original points unchanged
        assertEquals(1, p1.x);
        assertEquals(4, p2.x);
    }
    
    @Test
    public void testSubtract() {
        var p1 = new Point3i(10, 20, 30);
        var p2 = new Point3i(3, 5, 7);
        var result = p1.subtract(p2);
        
        assertEquals(7, result.x);
        assertEquals(15, result.y);
        assertEquals(23, result.z);
    }
    
    @Test
    public void testMultiply() {
        var point = new Point3i(2, 3, 4);
        var result = point.multiply(3);
        
        assertEquals(6, result.x);
        assertEquals(9, result.y);
        assertEquals(12, result.z);
    }
    
    @Test
    public void testManhattanDistance() {
        var p1 = new Point3i(0, 0, 0);
        var p2 = new Point3i(3, 4, 5);
        
        assertEquals(12, p1.manhattanDistance(p2));
        assertEquals(12, p2.manhattanDistance(p1));
    }
    
    @Test
    public void testDistanceSquared() {
        var p1 = new Point3i(0, 0, 0);
        var p2 = new Point3i(3, 4, 0);
        
        // 3^2 + 4^2 = 9 + 16 = 25
        assertEquals(25, p1.distanceSquared(p2));
        assertEquals(25, p2.distanceSquared(p1));
    }
    
    @Test
    public void testDistanceSquaredWith3D() {
        var p1 = new Point3i(1, 2, 3);
        var p2 = new Point3i(4, 6, 8);
        
        // (4-1)^2 + (6-2)^2 + (8-3)^2 = 9 + 16 + 25 = 50
        assertEquals(50, p1.distanceSquared(p2));
    }
    
    @Test
    public void testIsWithinBounds() {
        var point = new Point3i(5, 5, 5);
        var min = new Point3i(0, 0, 0);
        var max = new Point3i(10, 10, 10);
        
        assertTrue(point.isWithinBounds(min, max));
        
        // On boundary (min inclusive, max exclusive)
        assertTrue(new Point3i(0, 0, 0).isWithinBounds(min, max));
        assertFalse(new Point3i(10, 5, 5).isWithinBounds(min, max));
        
        // Outside bounds
        assertFalse(new Point3i(-1, 5, 5).isWithinBounds(min, max));
        assertFalse(new Point3i(5, 11, 5).isWithinBounds(min, max));
    }
    
    @Test
    public void testEquals() {
        var p1 = new Point3i(1, 2, 3);
        var p2 = new Point3i(1, 2, 3);
        var p3 = new Point3i(1, 2, 4);
        
        assertEquals(p1, p2);
        assertNotEquals(p1, p3);
        assertEquals(p1, p1); // Same reference
        assertNotEquals(p1, null);
        assertNotEquals(p1, "not a point");
    }
    
    @Test
    public void testHashCode() {
        var p1 = new Point3i(1, 2, 3);
        var p2 = new Point3i(1, 2, 3);
        var p3 = new Point3i(3, 2, 1);
        
        // Equal points have same hash code
        assertEquals(p1.hashCode(), p2.hashCode());
        
        // Different points likely have different hash codes
        assertNotEquals(p1.hashCode(), p3.hashCode());
    }
    
    @Test
    public void testToString() {
        var point = new Point3i(1, 2, 3);
        var str = point.toString();
        
        assertTrue(str.contains("1"));
        assertTrue(str.contains("2"));
        assertTrue(str.contains("3"));
        assertTrue(str.contains("Point3i"));
    }
    
    @Test
    public void testToArray() {
        var point = new Point3i(7, 8, 9);
        var array = point.toArray();
        
        assertEquals(3, array.length);
        assertEquals(7, array[0]);
        assertEquals(8, array[1]);
        assertEquals(9, array[2]);
    }
    
    @Test
    public void testFromArray() {
        var array = new int[] { 10, 20, 30 };
        var point = Point3i.fromArray(array);
        
        assertEquals(10, point.x);
        assertEquals(20, point.y);
        assertEquals(30, point.z);
    }
    
    @Test
    public void testFromArrayWithExtraElements() {
        var array = new int[] { 1, 2, 3, 4, 5 };
        var point = Point3i.fromArray(array);
        
        // Should only use first 3 elements
        assertEquals(1, point.x);
        assertEquals(2, point.y);
        assertEquals(3, point.z);
    }
    
    @Test
    public void testFromArrayTooShort() {
        var array = new int[] { 1, 2 };
        
        assertThrows(IllegalArgumentException.class, () -> {
            Point3i.fromArray(array);
        });
    }
    
    @Test
    public void testImmutability() {
        var p1 = new Point3i(1, 2, 3);
        var p2 = p1.add(new Point3i(10, 10, 10));
        
        // Original unchanged
        assertEquals(1, p1.x);
        assertEquals(2, p1.y);
        assertEquals(3, p1.z);
        
        // New point has new values
        assertEquals(11, p2.x);
        assertEquals(12, p2.y);
        assertEquals(13, p2.z);
    }
    
    @Test
    public void testNegativeCoordinates() {
        var point = new Point3i(-5, -10, -15);
        assertEquals(-5, point.x);
        assertEquals(-10, point.y);
        assertEquals(-15, point.z);
        
        var result = point.add(new Point3i(10, 10, 10));
        assertEquals(5, result.x);
        assertEquals(0, result.y);
        assertEquals(-5, result.z);
    }
}
