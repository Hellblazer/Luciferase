package com.dyada.core.linearization;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LinearRange Tests")
class LinearRangeTest {

    @Test
    @DisplayName("Create valid ranges")
    void testCreateValidRanges() {
        var range1 = new LinearRange(0, 10);
        assertEquals(0, range1.start());
        assertEquals(10, range1.end());
        assertEquals(11, range1.size());
        
        var range2 = LinearRange.of(5);
        assertEquals(5, range2.start());
        assertEquals(5, range2.end());
        assertEquals(1, range2.size());
        assertTrue(range2.isSingleton());
        
        var range3 = LinearRange.of(3, 7);
        assertEquals(3, range3.start());
        assertEquals(7, range3.end());
        assertEquals(5, range3.size());
        assertFalse(range3.isSingleton());
    }
    
    @Test
    @DisplayName("Reject invalid ranges")
    void testCreateInvalidRanges() {
        // Negative start
        assertThrows(IllegalArgumentException.class, 
            () -> new LinearRange(-1, 5));
        
        // End before start
        assertThrows(IllegalArgumentException.class, 
            () -> new LinearRange(5, 3));
        
        // Negative single index
        assertThrows(IllegalArgumentException.class, 
            () -> LinearRange.of(-1));
    }
    
    @Test
    @DisplayName("Range containment")
    void testContainment() {
        var range = new LinearRange(5, 15);
        
        // Index containment
        assertTrue(range.contains(5));
        assertTrue(range.contains(10));
        assertTrue(range.contains(15));
        assertFalse(range.contains(4));
        assertFalse(range.contains(16));
        
        // Range containment
        var inner = new LinearRange(7, 12);
        var outer = new LinearRange(3, 20);
        var disjoint = new LinearRange(20, 25);
        
        assertTrue(range.contains(inner));
        assertFalse(range.contains(outer));
        assertFalse(range.contains(disjoint));
        
        assertTrue(outer.contains(range));
        assertFalse(inner.contains(range));
    }
    
    @Test
    @DisplayName("Range overlap")
    void testOverlap() {
        var range1 = new LinearRange(5, 15);
        var range2 = new LinearRange(10, 20); // Overlaps
        var range3 = new LinearRange(20, 25); // Adjacent, no overlap
        var range4 = new LinearRange(25, 30); // Disjoint
        
        assertTrue(range1.overlaps(range2));
        assertTrue(range2.overlaps(range1)); // Symmetric
        assertFalse(range1.overlaps(range3));
        assertFalse(range1.overlaps(range4));
        
        // Self overlap
        assertTrue(range1.overlaps(range1));
    }
    
    @Test
    @DisplayName("Range intersection")
    void testIntersection() {
        var range1 = new LinearRange(5, 15);
        var range2 = new LinearRange(10, 20);
        var range3 = new LinearRange(20, 25);
        
        // Normal intersection
        var intersection12 = range1.intersect(range2);
        assertNotNull(intersection12);
        assertEquals(10, intersection12.start());
        assertEquals(15, intersection12.end());
        
        // No intersection
        var intersection13 = range1.intersect(range3);
        assertNull(intersection13);
        
        // Self intersection
        var selfIntersection = range1.intersect(range1);
        assertNotNull(selfIntersection);
        assertEquals(range1, selfIntersection);
    }
    
    @Test
    @DisplayName("Range union")
    void testUnion() {
        var range1 = new LinearRange(5, 15);
        var range2 = new LinearRange(10, 20); // Overlapping
        var range3 = new LinearRange(16, 25); // Adjacent
        var range4 = new LinearRange(30, 35); // Disjoint
        
        // Overlapping union
        var union12 = range1.union(range2);
        assertNotNull(union12);
        assertEquals(5, union12.start());
        assertEquals(20, union12.end());
        
        // Adjacent union
        var union13 = range1.union(range3);
        assertNotNull(union13);
        assertEquals(5, union13.start());
        assertEquals(25, union13.end());
        
        // Disjoint union
        var union14 = range1.union(range4);
        assertNull(union14);
    }
    
    @Test
    @DisplayName("Range adjacency")
    void testAdjacency() {
        var range1 = new LinearRange(5, 15);
        var range2 = new LinearRange(16, 20); // Adjacent after
        var range3 = new LinearRange(1, 4);   // Adjacent before
        var range4 = new LinearRange(10, 20); // Overlapping
        var range5 = new LinearRange(20, 25); // Disjoint
        
        assertTrue(range1.isAdjacentTo(range2));
        assertTrue(range2.isAdjacentTo(range1)); // Symmetric
        assertTrue(range1.isAdjacentTo(range3));
        assertFalse(range1.isAdjacentTo(range4)); // Overlapping
        assertFalse(range1.isAdjacentTo(range5)); // Disjoint
    }
    
    @Test
    @DisplayName("Range expansion")
    void testExpansion() {
        var range = new LinearRange(10, 20);
        
        var expanded = range.expand(5);
        assertEquals(5, expanded.start());
        assertEquals(25, expanded.end());
        
        // Expansion that would go negative
        var expandedLarge = range.expand(15);
        assertEquals(0, expandedLarge.start()); // Clamped to 0
        assertEquals(35, expandedLarge.end());
        
        // Zero expansion
        var expandedZero = range.expand(0);
        assertEquals(range, expandedZero);
        
        // Negative expansion should fail
        assertThrows(IllegalArgumentException.class, 
            () -> range.expand(-1));
    }
    
    @Test
    @DisplayName("Range contraction")
    void testContraction() {
        var range = new LinearRange(10, 20);
        
        var contracted = range.contract(2);
        assertNotNull(contracted);
        assertEquals(12, contracted.start());
        assertEquals(18, contracted.end());
        
        // Contraction that makes range invalid
        var contractedTooMuch = range.contract(6);
        assertNull(contractedTooMuch);
        
        // Zero contraction
        var contractedZero = range.contract(0);
        assertEquals(range, contractedZero);
        
        // Negative contraction should fail
        assertThrows(IllegalArgumentException.class, 
            () -> range.contract(-1));
    }
    
    @Test
    @DisplayName("Range splitting")
    void testSplitting() {
        var range = new LinearRange(10, 20);
        
        // Split in middle
        var splitMiddle = range.split(15);
        assertEquals(2, splitMiddle.length);
        assertEquals(new LinearRange(10, 14), splitMiddle[0]);
        assertEquals(new LinearRange(15, 20), splitMiddle[1]);
        
        // Split at start
        var splitStart = range.split(10);
        assertEquals(1, splitStart.length);
        assertEquals(range, splitStart[0]);
        
        // Split at end
        var splitEnd = range.split(20);
        assertEquals(1, splitEnd.length);
        assertEquals(range, splitEnd[0]);
        
        // Split outside range
        var splitOutside = range.split(25);
        assertEquals(1, splitOutside.length);
        assertEquals(range, splitOutside[0]);
    }
    
    @Test
    @DisplayName("Range properties")
    void testRangeProperties() {
        var singleton = LinearRange.of(42);
        var regular = new LinearRange(10, 20);
        
        // Singleton properties
        assertTrue(singleton.isSingleton());
        assertFalse(singleton.isEmpty());
        assertEquals(1, singleton.size());
        
        // Regular range properties
        assertFalse(regular.isSingleton());
        assertFalse(regular.isEmpty());
        assertEquals(11, regular.size());
    }
    
    @Test
    @DisplayName("Range string representation")
    void testStringRepresentation() {
        var singleton = LinearRange.of(42);
        var range = new LinearRange(10, 20);
        
        var singletonStr = singleton.toString();
        assertTrue(singletonStr.contains("42"));
        assertFalse(singletonStr.contains(".."));
        
        var rangeStr = range.toString();
        assertTrue(rangeStr.contains("10"));
        assertTrue(rangeStr.contains("20"));
        assertTrue(rangeStr.contains(".."));
        assertTrue(rangeStr.contains("size=11"));
        
        var detailedStr = range.toDetailedString();
        assertTrue(detailedStr.contains("start=10"));
        assertTrue(detailedStr.contains("end=20"));
        assertTrue(detailedStr.contains("size=11"));
        assertTrue(detailedStr.contains("singleton=false"));
    }
    
    @Test
    @DisplayName("Range equality and hashing")
    void testEqualityAndHashing() {
        var range1 = new LinearRange(10, 20);
        var range2 = new LinearRange(10, 20);
        var range3 = new LinearRange(10, 21);
        
        assertEquals(range1, range2);
        assertNotEquals(range1, range3);
        assertEquals(range1.hashCode(), range2.hashCode());
        
        // Test with factory methods
        var singleton1 = LinearRange.of(42);
        var singleton2 = LinearRange.of(42);
        var singleton3 = LinearRange.of(43);
        
        assertEquals(singleton1, singleton2);
        assertNotEquals(singleton1, singleton3);
    }
    
    @Test
    @DisplayName("Null parameter handling")
    void testNullParameterHandling() {
        var range = new LinearRange(10, 20);
        
        assertThrows(NullPointerException.class, 
            () -> range.contains((LinearRange) null));
        assertThrows(NullPointerException.class, 
            () -> range.overlaps(null));
        assertThrows(NullPointerException.class, 
            () -> range.intersect(null));
        assertThrows(NullPointerException.class, 
            () -> range.union(null));
        assertThrows(NullPointerException.class, 
            () -> range.isAdjacentTo(null));
    }
    
    @Test
    @DisplayName("Edge case ranges")
    void testEdgeCaseRanges() {
        // Zero-start range
        var zeroStart = new LinearRange(0, 5);
        assertEquals(0, zeroStart.start());
        assertEquals(6, zeroStart.size());
        
        // Large range
        var largeRange = new LinearRange(Long.MAX_VALUE - 10, Long.MAX_VALUE);
        assertEquals(11, largeRange.size());
        assertTrue(largeRange.contains(Long.MAX_VALUE));
        
        // Single point at zero
        var zeroSingleton = LinearRange.of(0);
        assertTrue(zeroSingleton.isSingleton());
        assertTrue(zeroSingleton.contains(0));
        assertFalse(zeroSingleton.contains(1));
    }
}