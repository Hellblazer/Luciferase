package com.dyada.performance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class OptimizedBitArrayTest {

    @Test
    @DisplayName("Basic constructor and bit operations")
    void testBasicOperations() {
        var bitArray = new OptimizedBitArray(128);
        
        // Initially all bits should be false
        assertEquals(128, bitArray.size());
        assertEquals(0, bitArray.cardinality());
        
        // Set some bits
        bitArray.set(0);
        bitArray.set(63);
        bitArray.set(127);
        
        assertEquals(3, bitArray.cardinality());
        assertTrue(bitArray.get(0));
        assertTrue(bitArray.get(63));
        assertTrue(bitArray.get(127));
        assertFalse(bitArray.get(1));
        assertFalse(bitArray.get(64));
    }

    @Test
    @DisplayName("Clear bit operations")
    void testClearOperations() {
        var bitArray = new OptimizedBitArray(64);
        
        // Set all bits
        for (int i = 0; i < 64; i++) {
            bitArray.set(i);
        }
        assertEquals(64, bitArray.cardinality());
        
        // Clear specific bits
        bitArray.clear(0);
        bitArray.clear(31);
        bitArray.clear(63);
        
        assertEquals(61, bitArray.cardinality());
        assertFalse(bitArray.get(0));
        assertFalse(bitArray.get(31));
        assertFalse(bitArray.get(63));
        assertTrue(bitArray.get(1));
        assertTrue(bitArray.get(32));
    }

    @Test
    @DisplayName("Flip bit operations")
    void testFlipOperations() {
        var bitArray = new OptimizedBitArray(32);
        
        assertFalse(bitArray.get(5));
        bitArray.flip(5);
        assertTrue(bitArray.get(5));
        bitArray.flip(5);
        assertFalse(bitArray.get(5));
        
        assertEquals(0, bitArray.cardinality());
    }

    @Test
    @DisplayName("AND bitwise operation")
    void testAndOperation() {
        var array1 = new OptimizedBitArray(64);
        var array2 = new OptimizedBitArray(64);
        
        // Set different patterns
        array1.set(0);
        array1.set(1);
        array1.set(10);
        array1.set(31);
        
        array2.set(1);
        array2.set(10);
        array2.set(20);
        array2.set(31);
        
        var result = array1.and(array2);
        
        // Result should have bits set only where both arrays had bits set
        assertFalse(result.get(0)); // Only in array1
        assertTrue(result.get(1));  // In both
        assertTrue(result.get(10)); // In both
        assertFalse(result.get(20)); // Only in array2
        assertTrue(result.get(31)); // In both
        
        assertEquals(3, result.cardinality());
    }

    @Test
    @DisplayName("OR bitwise operation")
    void testOrOperation() {
        var array1 = new OptimizedBitArray(64);
        var array2 = new OptimizedBitArray(64);
        
        array1.set(0);
        array1.set(10);
        array1.set(31);
        
        array2.set(1);
        array2.set(10);
        array2.set(32);
        
        var result = array1.or(array2);
        
        // Result should have bits set where either array had bits set
        assertTrue(result.get(0));  // From array1
        assertTrue(result.get(1));  // From array2
        assertTrue(result.get(10)); // From both
        assertTrue(result.get(31)); // From array1
        assertTrue(result.get(32)); // From array2
        
        assertEquals(5, result.cardinality());
    }

    @Test
    @DisplayName("XOR bitwise operation")
    void testXorOperation() {
        var array1 = new OptimizedBitArray(64);
        var array2 = new OptimizedBitArray(64);
        
        array1.set(0);
        array1.set(10);
        array1.set(31);
        
        array2.set(10);
        array2.set(31);
        array2.set(32);
        
        var result = array1.xor(array2);
        
        // Result should have bits set where exactly one array had bits set
        assertTrue(result.get(0));   // Only in array1
        assertFalse(result.get(10)); // In both (XOR = false)
        assertFalse(result.get(31)); // In both (XOR = false)
        assertTrue(result.get(32));  // Only in array2
        
        assertEquals(2, result.cardinality());
    }

    @Test
    @DisplayName("NOT bitwise operation")
    void testNotOperation() {
        var bitArray = new OptimizedBitArray(64);
        
        bitArray.set(0);
        bitArray.set(31);
        bitArray.set(63);
        
        var result = bitArray.not();
        
        // Result should have all bits flipped
        assertFalse(result.get(0));
        assertTrue(result.get(1));
        assertFalse(result.get(31));
        assertTrue(result.get(32));
        assertFalse(result.get(63));
        
        assertEquals(61, result.cardinality()); // 64 - 3 originally set bits
    }

    @Test
    @DisplayName("Next set bit search")
    void testNextSetBit() {
        var bitArray = new OptimizedBitArray(128);
        
        bitArray.set(5);
        bitArray.set(17);
        bitArray.set(64);
        bitArray.set(127);
        
        assertEquals(5, bitArray.nextSetBit(0));
        assertEquals(5, bitArray.nextSetBit(5));
        assertEquals(17, bitArray.nextSetBit(6));
        assertEquals(64, bitArray.nextSetBit(18));
        assertEquals(127, bitArray.nextSetBit(65));
        assertEquals(-1, bitArray.nextSetBit(128)); // No more set bits
    }

    @Test
    @DisplayName("Previous set bit search")
    void testPreviousSetBit() {
        var bitArray = new OptimizedBitArray(128);
        
        bitArray.set(5);
        bitArray.set(17);
        bitArray.set(64);
        bitArray.set(127);
        
        assertEquals(127, bitArray.previousSetBit(127));
        assertEquals(64, bitArray.previousSetBit(126));
        assertEquals(64, bitArray.previousSetBit(64));
        assertEquals(17, bitArray.previousSetBit(63));
        assertEquals(5, bitArray.previousSetBit(16));
        assertEquals(-1, bitArray.previousSetBit(4)); // No previous set bits
    }

    @Test
    @DisplayName("Empty bit array operations")
    void testEmptyBitArray() {
        var bitArray = new OptimizedBitArray(64);
        
        assertEquals(0, bitArray.cardinality());
        assertEquals(-1, bitArray.nextSetBit(0));
        assertEquals(-1, bitArray.previousSetBit(63));
        assertFalse(bitArray.get(0));
        assertFalse(bitArray.get(63));
    }

    @Test
    @DisplayName("Full bit array operations")
    void testFullBitArray() {
        var bitArray = new OptimizedBitArray(64);
        
        // Set all bits
        for (int i = 0; i < 64; i++) {
            bitArray.set(i);
        }
        
        assertEquals(64, bitArray.cardinality());
        assertEquals(0, bitArray.nextSetBit(0));
        assertEquals(63, bitArray.previousSetBit(63));
        
        var notResult = bitArray.not();
        assertEquals(0, notResult.cardinality());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 32, 63, 64, 65, 128, 256, 1024})
    @DisplayName("Various bit array sizes")
    void testVariousSizes(int size) {
        var bitArray = new OptimizedBitArray(size);
        
        assertEquals(size, bitArray.size());
        assertEquals(0, bitArray.cardinality());
        
        // Set first and last bits
        bitArray.set(0);
        if (size > 1) {
            bitArray.set(size - 1);
        }
        
        assertTrue(bitArray.get(0));
        if (size > 1) {
            assertTrue(bitArray.get(size - 1));
            assertEquals(2, bitArray.cardinality());
        } else {
            assertEquals(1, bitArray.cardinality());
        }
    }

    @Test
    @DisplayName("Word boundary operations")
    void testWordBoundaries() {
        var bitArray = new OptimizedBitArray(128);
        
        // Test operations at 64-bit word boundaries
        bitArray.set(63); // Last bit of first word
        bitArray.set(64); // First bit of second word
        
        assertTrue(bitArray.get(63));
        assertTrue(bitArray.get(64));
        assertEquals(2, bitArray.cardinality());
        
        assertEquals(63, bitArray.nextSetBit(0));
        assertEquals(64, bitArray.nextSetBit(64));
        assertEquals(64, bitArray.previousSetBit(127));
        assertEquals(63, bitArray.previousSetBit(63));
    }

    @Test
    @DisplayName("Byte array serialization")
    void testToByteArray() {
        var bitArray = new OptimizedBitArray(16);
        
        // Set specific pattern: 1010101010101010
        for (int i = 0; i < 16; i += 2) {
            bitArray.set(i);
        }
        
        byte[] bytes = bitArray.toByteArray();
        assertNotNull(bytes);
        assertTrue(bytes.length >= 2); // At least 2 bytes for 16 bits
        
        // Verify the pattern is preserved
        assertEquals(0xAA, bytes[0] & 0xFF); // 10101010 in binary
        assertEquals(0xAA, bytes[1] & 0xFF); // 10101010 in binary
    }

    @Test
    @DisplayName("Memory usage calculation")
    void testMemoryUsage() {
        var small = new OptimizedBitArray(64);
        var large = new OptimizedBitArray(1024);
        
        long smallMemory = small.memoryUsage();
        long largeMemory = large.memoryUsage();
        
        assertTrue(smallMemory > 0);
        assertTrue(largeMemory > smallMemory);
        
        // Memory should scale roughly with bit count, accounting for fixed overhead
        // small: 16 + 1*8 = 24, large: 16 + 16*8 = 144, ratio = 6
        assertTrue(largeMemory >= smallMemory * 5); // Should be at least 5x larger
    }

    @Test
    @DisplayName("Vectorized operations performance characteristics")
    void testVectorizedOperations() {
        // Create larger arrays to test vectorized paths
        var array1 = new OptimizedBitArray(1024);
        var array2 = new OptimizedBitArray(1024);
        
        // Set every 8th bit in array1
        for (int i = 0; i < 1024; i += 8) {
            array1.set(i);
        }
        
        // Set every 4th bit in array2
        for (int i = 0; i < 1024; i += 4) {
            array2.set(i);
        }
        
        // Test vectorized operations
        var andResult = array1.and(array2);
        var orResult = array1.or(array2);
        var xorResult = array1.xor(array2);
        
        // Verify results are computed correctly
        assertTrue(andResult.cardinality() > 0);
        assertTrue(orResult.cardinality() > andResult.cardinality());
        assertTrue(xorResult.cardinality() > 0);
        
        // AND should have bits set where both arrays have bits (every 8th bit)
        assertEquals(128, andResult.cardinality()); // 1024/8 = 128
        
        // OR should have bits set where either array has bits
        // Since every 8th bit is a subset of every 4th bit, OR = array2
        assertEquals(256, orResult.cardinality()); // Same as array2
    }

    @Test
    @DisplayName("Edge case: zero-sized bit array")
    void testZeroSizedArray() {
        var bitArray = new OptimizedBitArray(0);
        assertEquals(0, bitArray.size());
        assertEquals(0, bitArray.cardinality());
        assertTrue(bitArray.isEmpty());
        assertEquals("[]", bitArray.toString());
    }

    @Test
    @DisplayName("Edge case: negative size")
    void testNegativeSize() {
        // With negative bitCount, array allocation causes OutOfMemoryError due to unsigned shift
        assertThrows(OutOfMemoryError.class, () -> {
            new OptimizedBitArray(-1);
        });
    }

    @Test
    @DisplayName("Index out of bounds operations")
    void testIndexOutOfBounds() {
        var bitArray = new OptimizedBitArray(64);
        
        assertThrows(IndexOutOfBoundsException.class, () -> bitArray.get(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> bitArray.get(64));
        assertThrows(IndexOutOfBoundsException.class, () -> bitArray.set(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> bitArray.set(64));
        assertThrows(IndexOutOfBoundsException.class, () -> bitArray.clear(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> bitArray.clear(64));
        assertThrows(IndexOutOfBoundsException.class, () -> bitArray.flip(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> bitArray.flip(64));
    }

    @Test
    @DisplayName("Cardinality consistency after operations")
    void testCardinalityConsistency() {
        var bitArray = new OptimizedBitArray(128);
        
        // Set pattern and verify cardinality
        int expectedCount = 0;
        for (int i = 0; i < 128; i += 3) {
            bitArray.set(i);
            expectedCount++;
        }
        assertEquals(expectedCount, bitArray.cardinality());
        
        // Clear some bits and verify cardinality
        int clearedCount = 0;
        for (int i = 0; i < 128; i += 9) {
            if (bitArray.get(i)) {
                bitArray.clear(i);
                clearedCount++;
            }
        }
        assertEquals(expectedCount - clearedCount, bitArray.cardinality());
    }

    @Test
    @DisplayName("Bit search consistency")
    void testBitSearchConsistency() {
        var bitArray = new OptimizedBitArray(256);
        
        // Set random pattern
        int[] setBits = {3, 17, 42, 63, 64, 99, 128, 200, 255};
        for (int bit : setBits) {
            bitArray.set(bit);
        }
        
        // Verify nextSetBit finds all set bits in order
        int currentBit = 0;
        for (int expectedBit : setBits) {
            int foundBit = bitArray.nextSetBit(currentBit);
            assertEquals(expectedBit, foundBit);
            currentBit = foundBit + 1;
        }
        
        // Verify previousSetBit finds all set bits in reverse order
        currentBit = 255;
        for (int i = setBits.length - 1; i >= 0; i--) {
            int expectedBit = setBits[i];
            int foundBit = bitArray.previousSetBit(currentBit);
            assertEquals(expectedBit, foundBit);
            currentBit = foundBit - 1;
        }
    }
}