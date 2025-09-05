package com.dyada.performance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for ParallelDyAdaOperations functionality.
 * Tests parallel processing, Morton encoding/decoding, spatial partitioning,
 * and performance-critical bulk operations.
 */
class ParallelDyAdaOperationsTest {
    
    private static final int SMALL_SIZE = 10;
    private static final int LARGE_SIZE = 5000; // Above PARALLEL_THRESHOLD
    
    @BeforeEach
    void setUp() {
        // Ensure clean state before each test
        System.gc();
    }
    
    @AfterEach
    void tearDown() {
        // Clean up any resources
        System.gc();
    }
    
    @Test
    @DisplayName("Parallel Morton 2D encoding for small arrays")
    void testParallelMorton2DSmallArray() {
        int[] xCoords = {0, 1, 2, 3, 4};
        int[] yCoords = {0, 1, 2, 3, 4};
        
        long[] result = ParallelDyAdaOperations.encodeMorton2DParallel(xCoords, yCoords);
        
        assertEquals(5, result.length);
        
        // Verify each encoding is correct
        for (int i = 0; i < xCoords.length; i++) {
            assertEquals(MortonOptimizer.encode2D(xCoords[i], yCoords[i]), result[i]);
        }
    }
    
    @Test
    @DisplayName("Parallel Morton 2D encoding for large arrays")
    void testParallelMorton2DLargeArray() {
        int[] xCoords = new int[LARGE_SIZE];
        int[] yCoords = new int[LARGE_SIZE];
        
        var random = ThreadLocalRandom.current();
        for (int i = 0; i < LARGE_SIZE; i++) {
            xCoords[i] = random.nextInt(1000);
            yCoords[i] = random.nextInt(1000);
        }
        
        long[] parallel = ParallelDyAdaOperations.encodeMorton2DParallel(xCoords, yCoords);
        
        assertEquals(LARGE_SIZE, parallel.length);
        
        // Verify against sequential encoding for subset
        for (int i = 0; i < Math.min(100, LARGE_SIZE); i++) {
            assertEquals(MortonOptimizer.encode2D(xCoords[i], yCoords[i]), parallel[i]);
        }
    }
    
    @Test
    @DisplayName("Parallel Morton 3D encoding")
    void testParallelMorton3D() {
        int[] xCoords = {0, 1, 2, 3};
        int[] yCoords = {0, 1, 2, 3};
        int[] zCoords = {0, 1, 2, 3};
        
        long[] result = ParallelDyAdaOperations.encodeMorton3DParallel(xCoords, yCoords, zCoords);
        
        assertEquals(4, result.length);
        
        for (int i = 0; i < xCoords.length; i++) {
            assertEquals(MortonOptimizer.encode3D(xCoords[i], yCoords[i], zCoords[i]), result[i]);
        }
    }
    
    @Test
    @DisplayName("Parallel Morton 2D decoding")
    void testParallelMorton2DDecoding() {
        long[] mortonCodes = {0L, 1L, 2L, 3L, 5L};
        int[] xCoords = new int[5];
        int[] yCoords = new int[5];
        
        ParallelDyAdaOperations.decodeMorton2DParallel(mortonCodes, xCoords, yCoords);
        
        for (int i = 0; i < mortonCodes.length; i++) {
            assertEquals(MortonOptimizer.decodeX2D(mortonCodes[i]), xCoords[i]);
            assertEquals(MortonOptimizer.decodeY2D(mortonCodes[i]), yCoords[i]);
        }
    }
    
    @Test
    @DisplayName("Parallel BitArray AND operation")
    void testParallelBitwiseAnd() {
        var bitArray1 = new OptimizedBitArray(100);
        var bitArray2 = new OptimizedBitArray(100);
        var bitArray3 = new OptimizedBitArray(100);
        
        // Set some bits
        bitArray1.set(10);
        bitArray1.set(20);
        bitArray1.set(30);
        
        bitArray2.set(10);
        bitArray2.set(25);
        bitArray2.set(30);
        
        bitArray3.set(10);
        bitArray3.set(30);
        bitArray3.set(40);
        
        var bitArrays = List.of(bitArray1, bitArray2, bitArray3);
        var result = ParallelDyAdaOperations.parallelBitwiseAnd(bitArrays);
        
        // Only bits set in all arrays should remain
        assertTrue(result.get(10));
        assertTrue(result.get(30));
        assertFalse(result.get(20));
        assertFalse(result.get(25));
        assertFalse(result.get(40));
    }
    
    @Test
    @DisplayName("Parallel cardinality calculation")
    void testParallelCardinality() {
        var bitArray1 = new OptimizedBitArray(50);
        var bitArray2 = new OptimizedBitArray(50);
        
        // Set different numbers of bits
        bitArray1.set(0);
        bitArray1.set(1);
        bitArray1.set(2);
        
        bitArray2.set(0);
        bitArray2.set(10);
        bitArray2.set(20);
        bitArray2.set(30);
        bitArray2.set(40);
        
        var bitArrays = List.of(bitArray1, bitArray2);
        int[] cardinalities = ParallelDyAdaOperations.parallelCardinality(bitArrays);
        
        assertEquals(2, cardinalities.length);
        assertEquals(3, cardinalities[0]);
        assertEquals(5, cardinalities[1]);
    }
    
    @Test
    @DisplayName("Parallel range query")
    void testParallelRangeQuery() {
        long startMorton = 0L;
        long endMorton = 100L;
        
        // Find even Morton codes
        var results = ParallelDyAdaOperations.executeParallelRangeQuery(
            startMorton, endMorton, morton -> morton % 2 == 0);
        
        // Verify all results are even and in range
        for (long morton : results) {
            assertTrue(morton >= startMorton && morton <= endMorton);
            assertEquals(0, morton % 2);
        }
        
        // Should have 51 even numbers (0, 2, 4, ..., 100)
        assertEquals(51, results.size());
    }
    
    @Test
    @DisplayName("Parallel transformation")
    void testParallelTransform() {
        var input = List.of(1, 2, 3, 4, 5);
        var result = ParallelDyAdaOperations.parallelTransform(input, x -> x * x);
        
        assertEquals(List.of(1, 4, 9, 16, 25), result);
    }
    
    @Test
    @DisplayName("Error handling for mismatched array lengths")
    void testErrorHandlingMismatchedArrays() {
        int[] xCoords = {1, 2, 3};
        int[] yCoords = {1, 2}; // Different length
        
        assertThrows(IllegalArgumentException.class, 
            () -> ParallelDyAdaOperations.encodeMorton2DParallel(xCoords, yCoords));
    }
    
    @Test
    @DisplayName("Error handling for empty BitArray list")
    void testErrorHandlingEmptyBitArrayList() {
        var emptyList = List.<OptimizedBitArray>of();
        
        assertThrows(IllegalArgumentException.class, 
            () -> ParallelDyAdaOperations.parallelBitwiseAnd(emptyList));
    }
    
    @Test
    @DisplayName("Error handling for different sized BitArrays")
    void testErrorHandlingDifferentSizedBitArrays() {
        var bitArray1 = new OptimizedBitArray(50);
        var bitArray2 = new OptimizedBitArray(100); // Different size
        
        var bitArrays = List.of(bitArray1, bitArray2);
        
        assertThrows(IllegalArgumentException.class, 
            () -> ParallelDyAdaOperations.parallelBitwiseAnd(bitArrays));
    }
    
    @ParameterizedTest
    @ValueSource(ints = {100, 1000, 5000})
    @DisplayName("Performance scaling with different array sizes")
    void testPerformanceScaling(int size) {
        var random = ThreadLocalRandom.current();
        int[] xCoords = new int[size];
        int[] yCoords = new int[size];
        
        for (int i = 0; i < size; i++) {
            xCoords[i] = random.nextInt(1000);
            yCoords[i] = random.nextInt(1000);
        }
        
        long startTime = System.nanoTime();
        long[] result = ParallelDyAdaOperations.encodeMorton2DParallel(xCoords, yCoords);
        long endTime = System.nanoTime();
        
        assertEquals(size, result.length);
        
        // Performance should be reasonable (less than 1 second for all sizes)
        long durationMs = (endTime - startTime) / 1_000_000;
        assertTrue(durationMs < 1000, "Operation took too long: " + durationMs + "ms");
    }
    
    @Test
    @DisplayName("Pool statistics retrieval")
    void testPoolStats() {
        String stats = ParallelDyAdaOperations.getPoolStats();
        
        assertNotNull(stats);
        assertTrue(stats.contains("ForkJoinPool Stats"));
        assertTrue(stats.contains("threads"));
        assertTrue(stats.contains("active"));
    }
}