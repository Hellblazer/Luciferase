package com.dyada.core.bitarray;

import com.dyada.TestBase;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BitArray Tests")
class BitArrayTest extends TestBase {
    
    @Test
    @DisplayName("Create empty BitArray")
    void testCreateEmpty() {
        var bitArray = BitArray.of(10);
        
        assertEquals(10, bitArray.size());
        assertEquals(0, bitArray.count());
        
        for (int i = 0; i < 10; i++) {
            assertFalse(bitArray.get(i));
        }
    }
    
    @Test
    @DisplayName("Set and get bits")
    void testSetAndGet() {
        var bitArray = BitArray.of(8);
        
        // Set some bits
        bitArray = bitArray.set(0, true);
        bitArray = bitArray.set(3, true);
        bitArray = bitArray.set(7, true);
        
        assertTrue(bitArray.get(0));
        assertFalse(bitArray.get(1));
        assertFalse(bitArray.get(2));
        assertTrue(bitArray.get(3));
        assertFalse(bitArray.get(4));
        assertFalse(bitArray.get(5));
        assertFalse(bitArray.get(6));
        assertTrue(bitArray.get(7));
        
        assertEquals(3, bitArray.count());
    }
    
    @Test
    @DisplayName("Set bit to false")
    void testSetFalse() {
        var bitArray = BitArray.of(5);
        bitArray = bitArray.set(2, true);
        
        assertTrue(bitArray.get(2));
        assertEquals(1, bitArray.count());
        
        bitArray = bitArray.set(2, false);
        
        assertFalse(bitArray.get(2));
        assertEquals(0, bitArray.count());
    }
    
    @Test
    @DisplayName("Flip bits")
    void testFlip() {
        var bitArray = BitArray.of(4);
        bitArray = bitArray.set(1, true);
        
        // Flip bit 1 (true -> false)
        bitArray = bitArray.flip(1);
        assertFalse(bitArray.get(1));
        
        // Flip bit 2 (false -> true)
        bitArray = bitArray.flip(2);
        assertTrue(bitArray.get(2));
        
        assertEquals(1, bitArray.count());
    }
    
    @Test
    @DisplayName("Clear all bits")
    void testClear() {
        var bitArray = BitArray.of(6);
        bitArray = bitArray.set(1, true);
        bitArray = bitArray.set(3, true);
        bitArray = bitArray.set(5, true);
        
        assertEquals(3, bitArray.count());
        
        bitArray = bitArray.clear();
        
        assertEquals(0, bitArray.count());
        for (int i = 0; i < 6; i++) {
            assertFalse(bitArray.get(i));
        }
    }
    
    @Test
    @DisplayName("Bitwise AND operation")
    void testAnd() {
        var array1 = BitArray.of(4);
        array1 = array1.set(0, true);
        array1 = array1.set(1, true);
        array1 = array1.set(2, false);
        array1 = array1.set(3, true);
        
        var array2 = BitArray.of(4);
        array2 = array2.set(0, true);
        array2 = array2.set(1, false);
        array2 = array2.set(2, true);
        array2 = array2.set(3, true);
        
        var result = array1.and(array2);
        
        assertTrue(result.get(0));  // true & true = true
        assertFalse(result.get(1)); // true & false = false
        assertFalse(result.get(2)); // false & true = false
        assertTrue(result.get(3));  // true & true = true
        
        assertEquals(2, result.count());
    }
    
    @Test
    @DisplayName("Bitwise OR operation")
    void testOr() {
        var array1 = BitArray.of(4);
        array1 = array1.set(0, true);
        array1 = array1.set(1, false);
        array1 = array1.set(2, true);
        array1 = array1.set(3, false);
        
        var array2 = BitArray.of(4);
        array2 = array2.set(0, false);
        array2 = array2.set(1, true);
        array2 = array2.set(2, true);
        array2 = array2.set(3, false);
        
        var result = array1.or(array2);
        
        assertTrue(result.get(0));  // true | false = true
        assertTrue(result.get(1));  // false | true = true
        assertTrue(result.get(2));  // true | true = true
        assertFalse(result.get(3)); // false | false = false
        
        assertEquals(3, result.count());
    }
    
    @Test
    @DisplayName("Bitwise XOR operation")
    void testXor() {
        var array1 = BitArray.of(4);
        array1 = array1.set(0, true);
        array1 = array1.set(1, true);
        array1 = array1.set(2, false);
        array1 = array1.set(3, false);
        
        var array2 = BitArray.of(4);
        array2 = array2.set(0, true);
        array2 = array2.set(1, false);
        array2 = array2.set(2, true);
        array2 = array2.set(3, false);
        
        var result = array1.xor(array2);
        
        assertFalse(result.get(0)); // true ^ true = false
        assertTrue(result.get(1));  // true ^ false = true
        assertTrue(result.get(2));  // false ^ true = true
        assertFalse(result.get(3)); // false ^ false = false
        
        assertEquals(2, result.count());
    }
    
    @Test
    @DisplayName("Bitwise NOT operation")
    void testNot() {
        var bitArray = BitArray.of(4);
        bitArray = bitArray.set(0, true);
        bitArray = bitArray.set(2, true);
        
        var result = bitArray.not();
        
        assertFalse(result.get(0)); // !true = false
        assertTrue(result.get(1));  // !false = true
        assertFalse(result.get(2)); // !true = false
        assertTrue(result.get(3));  // !false = true
        
        assertEquals(2, result.count());
    }
    
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 8, 64, 65, 128, 129})
    @DisplayName("Different sizes work correctly")
    void testDifferentSizes(int size) {
        var bitArray = BitArray.of(size);
        
        assertEquals(size, bitArray.size());
        assertEquals(0, bitArray.count());
        
        if (size > 0) {
            bitArray = bitArray.set(0, true);
            assertEquals(1, bitArray.count());
            assertTrue(bitArray.get(0));
            
            if (size > 1) {
                bitArray = bitArray.set(size - 1, true);
                assertEquals(2, bitArray.count());
                assertTrue(bitArray.get(size - 1));
            }
        }
    }
    
    @Test
    @DisplayName("Invalid index access throws exception")
    void testInvalidIndexAccess() {
        var bitArray = BitArray.of(5);
        
        assertThrows(IndexOutOfBoundsException.class, () -> bitArray.get(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> bitArray.get(5));
        assertThrows(IndexOutOfBoundsException.class, () -> bitArray.set(-1, true));
        assertThrows(IndexOutOfBoundsException.class, () -> bitArray.set(5, true));
        assertThrows(IndexOutOfBoundsException.class, () -> bitArray.flip(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> bitArray.flip(5));
    }
    
    @Test
    @DisplayName("Operations on different sizes throw exception")
    void testDifferentSizeOperations() {
        var array1 = BitArray.of(5);
        var array2 = BitArray.of(8);
        
        assertThrows(IllegalArgumentException.class, () -> array1.and(array2));
        assertThrows(IllegalArgumentException.class, () -> array1.or(array2));
        assertThrows(IllegalArgumentException.class, () -> array1.xor(array2));
    }
    
    @Test
    @DisplayName("Zero size BitArray")
    void testZeroSize() {
        var bitArray = BitArray.of(0);
        
        assertEquals(0, bitArray.size());
        assertEquals(0, bitArray.count());
        
        // Operations should work on empty arrays
        assertEquals(bitArray, bitArray.clear());
        assertEquals(bitArray, bitArray.not());
        assertEquals(bitArray, bitArray.and(BitArray.of(0)));
        assertEquals(bitArray, bitArray.or(BitArray.of(0)));
        assertEquals(bitArray, bitArray.xor(BitArray.of(0)));
    }
    
    @Test
    @DisplayName("Negative size throws exception")
    void testNegativeSize() {
        assertThrows(IllegalArgumentException.class, () -> BitArray.of(-1));
        assertThrows(IllegalArgumentException.class, () -> BitArray.of(-10));
    }
    
    @Test
    @DisplayName("Equality and hashCode")
    void testEqualityAndHashCode() {
        var array1 = BitArray.of(5);
        array1 = array1.set(1, true);
        array1 = array1.set(3, true);
        
        var array2 = BitArray.of(5);
        array2 = array2.set(1, true);
        array2 = array2.set(3, true);
        
        var array3 = BitArray.of(5);
        array3 = array3.set(1, true);
        array3 = array3.set(4, true);
        
        assertEquals(array1, array2);
        assertEquals(array1.hashCode(), array2.hashCode());
        assertNotEquals(array1, array3);
        assertNotEquals(array1.hashCode(), array3.hashCode());
    }
    
    @Test
    @DisplayName("Immutability")
    void testImmutability() {
        var original = BitArray.of(3);
        original = original.set(1, true);
        
        var modified = original.set(0, true);
        
        // Original should be unchanged
        assertFalse(original.get(0));
        assertTrue(original.get(1));
        assertEquals(1, original.count());
        
        // Modified should have both bits set
        assertTrue(modified.get(0));
        assertTrue(modified.get(1));
        assertEquals(2, modified.count());
    }
}