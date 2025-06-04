package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.TetMemoryOptimizer.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import java.util.*;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for TetMemoryOptimizer (Phase 5D)
 * Tests tetrahedral-specific memory optimizations including compact representation,
 * object pooling, GC optimization, cache eviction, and memory metrics.
 */
public class TetMemoryOptimizerTest {

    @BeforeEach
    void setUp() {
        // Clear all caches and reset metrics before each test
        TetMemoryOptimizer.clearAllCaches();
    }

    @Test
    @DisplayName("Test compact tetrahedral representation")
    void testCompactTetRepresentation() {
        // Test compact representation creation
        var compactTet = new TetCompactRepresentation.CompactTet(1000, 2000, 3000, (byte) 10, (byte) 3);
        
        // Verify packed data is correctly unpacked
        assertEquals(1000, compactTet.x(), "X coordinate should be preserved");
        assertEquals(2000, compactTet.y(), "Y coordinate should be preserved");
        assertEquals(3000, compactTet.z(), "Z coordinate should be preserved");
        assertEquals(10, compactTet.level(), "Level should be preserved");
        assertEquals(3, compactTet.type(), "Type should be preserved");
        
        // Verify memory footprint is smaller than full Tet
        assertTrue(compactTet.memoryFootprint() <= 16, "Compact representation should be ≤16 bytes");
        
        // Test conversion to full Tet
        var fullTet = compactTet.toTet();
        assertEquals(compactTet.x(), fullTet.x(), "X should match after conversion");
        assertEquals(compactTet.y(), fullTet.y(), "Y should match after conversion");
        assertEquals(compactTet.z(), fullTet.z(), "Z should match after conversion");
        assertEquals(compactTet.level(), fullTet.l(), "Level should match after conversion");
        assertEquals(compactTet.type(), fullTet.type(), "Type should match after conversion");
        
        // Test SFC index calculation
        long expectedIndex = fullTet.index();
        assertEquals(expectedIndex, compactTet.sfcIndex(), "SFC index should match");
        
        // Verify metrics were recorded
        var metrics = TetMemoryOptimizer.getGlobalMetrics();
        assertTrue(metrics.getMetricsSummary().contains("Compact Representations Created: 1"));
    }

    @Test
    @DisplayName("Test compact representation validation")
    void testCompactRepresentationValidation() {
        // Test negative coordinates
        assertThrows(IllegalArgumentException.class, () -> 
            new TetCompactRepresentation.CompactTet(-1, 0, 0, (byte) 0, (byte) 0),
            "Should reject negative X coordinate");
        
        assertThrows(IllegalArgumentException.class, () -> 
            new TetCompactRepresentation.CompactTet(0, -1, 0, (byte) 0, (byte) 0),
            "Should reject negative Y coordinate");
        
        assertThrows(IllegalArgumentException.class, () -> 
            new TetCompactRepresentation.CompactTet(0, 0, -1, (byte) 0, (byte) 0),
            "Should reject negative Z coordinate");
        
        // Test invalid type
        assertThrows(IllegalArgumentException.class, () -> 
            new TetCompactRepresentation.CompactTet(0, 0, 0, (byte) 0, (byte) 6),
            "Should reject type > 5");
        
        assertThrows(IllegalArgumentException.class, () -> 
            new TetCompactRepresentation.CompactTet(0, 0, 0, (byte) 0, (byte) -1),
            "Should reject negative type");
        
        // Test coordinate overflow (21-bit limit = 2,097,151)
        int maxCoord = (1 << 21) - 1;
        
        // This should work
        assertDoesNotThrow(() -> 
            new TetCompactRepresentation.CompactTet(maxCoord, maxCoord, maxCoord, (byte) 20, (byte) 5),
            "Should accept maximum valid coordinates");
        
        // This should fail
        assertThrows(IllegalArgumentException.class, () -> 
            new TetCompactRepresentation.CompactTet(maxCoord + 1, 0, 0, (byte) 0, (byte) 0),
            "Should reject coordinates beyond 21-bit limit");
    }

    @Test
    @DisplayName("Test compact grid cell representation")
    void testCompactGridCell() {
        var gridCell = new TetCompactRepresentation.CompactTetGridCell(5, 10, 15, (byte) 8);
        
        // Verify all 6 types are created
        var allTypes = gridCell.getAllTypes();
        assertEquals(6, allTypes.length, "Should have exactly 6 tetrahedral types");
        
        // Verify each type
        for (byte type = 0; type < 6; type++) {
            var compactTet = gridCell.getType(type);
            assertNotNull(compactTet, "Type " + type + " should not be null");
            assertEquals(type, compactTet.type(), "Type should match");
            assertEquals(8, compactTet.level(), "Level should match grid cell level");
            
            // Verify coordinates are based on grid position
            int expectedLength = Constants.lengthAtLevel((byte) 8);
            assertEquals(5 * expectedLength, compactTet.x(), "X should be grid-based");
            assertEquals(10 * expectedLength, compactTet.y(), "Y should be grid-based");
            assertEquals(15 * expectedLength, compactTet.z(), "Z should be grid-based");
        }
        
        // Test invalid type access
        assertThrows(IllegalArgumentException.class, () -> 
            gridCell.getType((byte) 6), "Should reject invalid type");
        
        // Verify memory footprint
        assertTrue(gridCell.memoryFootprint() <= 128, "Grid cell should be memory efficient");
        
        // Verify metrics
        var metrics = TetMemoryOptimizer.getGlobalMetrics();
        assertTrue(metrics.getMetricsSummary().contains("Grid Cells Created: 1"));
    }

    @Test
    @DisplayName("Test lazy coordinate cache")
    void testLazyCoordinateCache() {
        var cache = new TetCompactRepresentation.LazyTetCoordinateCache();
        var compactTet = new TetCompactRepresentation.CompactTet(1000, 1000, 1000, (byte) 10, (byte) 2);
        
        // First access should miss cache
        var coords1 = cache.getCoordinates(compactTet);
        assertNotNull(coords1, "Coordinates should not be null");
        assertEquals(4, coords1.length, "Should have 4 vertices");
        assertTrue(cache.getCacheHitRate() < 1.0, "First access should miss cache");
        
        // Second access should hit cache
        var coords2 = cache.getCoordinates(compactTet);
        assertSame(coords1, coords2, "Second access should return cached result");
        assertTrue(cache.getCacheHitRate() > 0.0, "Should have cache hits");
        
        // Test cache clearing
        cache.clearCache();
        assertEquals(0.0, cache.getCacheHitRate(), "Cache hit rate should reset");
        
        // Access after clear should miss again
        var coords3 = cache.getCoordinates(compactTet);
        assertNotNull(coords3, "Coordinates should still be available");
    }

    @Test
    @DisplayName("Test memory pool for Point3f objects")
    void testMemoryPoolPoint3f() {
        var pool = TetMemoryOptimizer.getGlobalPool();
        
        // Get object from empty pool (should create new)
        var point1 = pool.getPoint3f();
        assertNotNull(point1, "Should get valid Point3f");
        assertEquals(0f, point1.x, "Should be reset to zero");
        assertEquals(0f, point1.y, "Should be reset to zero");
        assertEquals(0f, point1.z, "Should be reset to zero");
        
        // Modify and return to pool
        point1.set(10f, 20f, 30f);
        pool.returnPoint3f(point1);
        
        // Get again (should reuse from pool)
        var point2 = pool.getPoint3f();
        assertSame(point1, point2, "Should reuse pooled object");
        assertEquals(0f, point2.x, "Should be reset when retrieved from pool");
        
        // Test pooled operation
        var result = pool.withPooledPoint3f(() -> {
            var p = pool.getPoint3f();
            p.set(5f, 5f, 5f);
            return p.x + p.y + p.z;
        });
        assertEquals(15f, result, "Pooled operation should work correctly");
        
        // Verify pool statistics
        var stats = pool.getPoolStatistics();
        assertNotNull(stats, "Pool statistics should be available");
        assertTrue(stats.contains("Pool Hit Rate"), "Should report hit rate");
        assertTrue(stats.contains("Point3f Pool Size"), "Should report pool size");
    }

    @Test
    @DisplayName("Test memory pool for Point3i objects")
    void testMemoryPoolPoint3i() {
        var pool = TetMemoryOptimizer.getGlobalPool();
        
        // Test Point3i pooling
        var point1 = pool.getPoint3i();
        assertNotNull(point1, "Should get valid Point3i");
        assertEquals(0, point1.x, "Should be reset to zero");
        
        point1.set(100, 200, 300);
        pool.returnPoint3i(point1);
        
        var point2 = pool.getPoint3i();
        assertSame(point1, point2, "Should reuse pooled Point3i");
        assertEquals(0, point2.x, "Should be reset when retrieved");
    }

    @Test
    @DisplayName("Test memory pool for arrays")
    void testMemoryPoolArrays() {
        var pool = TetMemoryOptimizer.getGlobalPool();
        
        // Test float array pooling
        var floatArray1 = pool.getFloatArray(4);
        assertEquals(4, floatArray1.length, "Should get array of requested size");
        
        floatArray1[0] = 1.5f;
        pool.returnFloatArray(floatArray1);
        
        var floatArray2 = pool.getFloatArray(4);
        assertSame(floatArray1, floatArray2, "Should reuse pooled float array");
        assertEquals(0f, floatArray2[0], "Should be reset when retrieved");
        
        // Test int array pooling
        var intArray1 = pool.getIntArray(4);
        assertEquals(4, intArray1.length, "Should get int array of requested size");
        
        intArray1[0] = 42;
        pool.returnIntArray(intArray1);
        
        var intArray2 = pool.getIntArray(4);
        assertSame(intArray1, intArray2, "Should reuse pooled int array");
        assertEquals(0, intArray2[0], "Should be reset when retrieved");
    }

    @Test
    @DisplayName("Test GC optimization")
    void testGCOptimization() {
        var gcOptimizer = TetMemoryOptimizer.getGCOptimizer();
        
        // Test batch processing
        var tetIndices = LongStream.range(0, 100).boxed().toList();
        
        var results = gcOptimizer.executeWithGCOptimization(tetIndices, index -> {
            try {
                var tet = Tet.tetrahedron(index);
                return tet.l(); // Return level
            } catch (Exception e) {
                return null;
            }
        });
        
        assertNotNull(results, "Results should not be null");
        assertFalse(results.isEmpty(), "Should have some results");
        
        // Verify all results are valid levels
        for (var level : results) {
            assertNotNull(level, "Level should not be null");
            assertTrue(level >= 0, "Level should be non-negative");
        }
        
        // Test GC statistics
        var gcStats = gcOptimizer.getGCStatistics();
        assertNotNull(gcStats, "GC statistics should be available");
        assertTrue(gcStats.contains("Optimized Operations"), "Should report optimized operations");
        assertTrue(gcStats.contains("Memory Usage"), "Should report memory usage");
        
        // Test disabling GC optimization
        gcOptimizer.setGCOptimizationEnabled(false);
        var resultsWithoutGC = gcOptimizer.executeWithGCOptimization(tetIndices.subList(0, 10), 
            index -> index * 2);
        assertEquals(10, resultsWithoutGC.size(), "Should process all indices without GC optimization");
        
        // Re-enable for other tests
        gcOptimizer.setGCOptimizationEnabled(true);
    }

    @Test
    @DisplayName("Test cache eviction strategy")
    void testCacheEvictionStrategy() {
        var cacheEviction = TetMemoryOptimizer.getCacheEviction();
        
        // Test cache get or compute
        var value1 = cacheEviction.getOrCompute(42L, () -> "test_value_42", String.class);
        assertEquals("test_value_42", value1, "Should compute and return value");
        
        // Second access should hit cache
        var value2 = cacheEviction.getOrCompute(42L, () -> "different_value", String.class);
        assertEquals("test_value_42", value2, "Should return cached value");
        
        // Test with different type should miss cache
        var value3 = cacheEviction.getOrCompute(42L, () -> 123, Integer.class);
        assertEquals(123, value3, "Should compute new value for different type");
        
        // Test SFC locality eviction
        cacheEviction.evictBySFCLocality(50L, 5);
        
        // Value at index 42 should be evicted (distance = |42-50| = 8 > 5)
        var value4 = cacheEviction.getOrCompute(42L, () -> "new_value_42", String.class);
        assertEquals("new_value_42", value4, "Should compute new value after locality eviction");
        
        // Test cache statistics
        var cacheStats = cacheEviction.getCacheStatistics();
        assertNotNull(cacheStats, "Cache statistics should be available");
        assertTrue(cacheStats.contains("Cache Size"), "Should report cache size");
        assertTrue(cacheStats.contains("Hit Rate"), "Should report hit rate");
        
        // Test cache clearing
        cacheEviction.clearCache();
        var cacheStatsAfterClear = cacheEviction.getCacheStatistics();
        assertTrue(cacheStatsAfterClear.contains("Cache Size: 0"), "Cache should be empty after clear");
    }

    @Test
    @DisplayName("Test memory metrics collection")
    void testMemoryMetrics() {
        var metrics = TetMemoryOptimizer.getGlobalMetrics();
        
        // Create some objects to generate metrics
        var compactTet = new TetCompactRepresentation.CompactTet(500, 600, 700, (byte) 5, (byte) 1);
        var gridCell = new TetCompactRepresentation.CompactTetGridCell(1, 2, 3, (byte) 4);
        
        // Trigger some cache operations
        var cacheEviction = TetMemoryOptimizer.getCacheEviction();
        cacheEviction.getOrCompute(100L, () -> "test", String.class);
        cacheEviction.getOrCompute(100L, () -> "test", String.class); // Should hit cache
        
        // Get metrics summary
        var summary = metrics.getMetricsSummary();
        assertNotNull(summary, "Metrics summary should not be null");
        
        // Verify it contains expected information
        assertTrue(summary.contains("Compact Representations Created"), "Should report compact creations");
        assertTrue(summary.contains("Grid Cells Created"), "Should report grid cell creations");
        assertTrue(summary.contains("Cache Hit Rate"), "Should report cache hit rate");
        assertTrue(summary.contains("JVM Memory Usage"), "Should report JVM memory usage");
        
        // Verify cache hit rate calculation
        assertTrue(metrics.getCacheHitRate() >= 0.0, "Cache hit rate should be non-negative");
        assertTrue(metrics.getCacheHitRate() <= 1.0, "Cache hit rate should not exceed 100%");
        
        // Test metrics reset
        metrics.reset();
        var resetSummary = metrics.getMetricsSummary();
        assertTrue(resetSummary.contains("Compact Representations Created: 0"), 
            "Should reset compact creations");
        assertTrue(resetSummary.contains("Cache Hit Rate: 0.00%"), 
            "Should reset cache hit rate");
    }

    @Test
    @DisplayName("Test compact representation equality and hashing")
    void testCompactRepresentationEquality() {
        var tet1 = new TetCompactRepresentation.CompactTet(1000, 2000, 3000, (byte) 10, (byte) 2);
        var tet2 = new TetCompactRepresentation.CompactTet(1000, 2000, 3000, (byte) 10, (byte) 2);
        var tet3 = new TetCompactRepresentation.CompactTet(1000, 2000, 3000, (byte) 10, (byte) 3);
        
        // Test equality
        assertEquals(tet1, tet2, "Identical tetrahedra should be equal");
        assertNotEquals(tet1, tet3, "Different tetrahedra should not be equal");
        
        // Test hash codes
        assertEquals(tet1.hashCode(), tet2.hashCode(), "Equal objects should have equal hash codes");
        
        // Test toString
        var string = tet1.toString();
        assertNotNull(string, "toString should not return null");
        assertTrue(string.contains("CompactTet"), "toString should identify the class");
        assertTrue(string.contains("1000"), "toString should include coordinates");
    }

    @Test
    @DisplayName("Test memory optimization integration")
    void testMemoryOptimizationIntegration() {
        // Create a collection of tetrahedra for testing
        var tetrahedra = new ArrayList<Tet>();
        for (int i = 0; i < 50; i++) {
            try {
                tetrahedra.add(Tet.tetrahedron(i));
            } catch (Exception e) {
                // Skip invalid indices
            }
        }
        
        // Test compact representation creation
        var compactRepresentation = TetMemoryOptimizer.createCompactRepresentation(tetrahedra);
        assertEquals(tetrahedra.size(), compactRepresentation.size(), 
            "Should create compact representation for all tetrahedra");
        
        // Test memory-optimized execution
        var tetIndices = LongStream.range(0, 20).boxed().toList();
        var results = TetMemoryOptimizer.executeMemoryOptimized(tetIndices, index -> {
            try {
                var tet = Tet.tetrahedron(index);
                return tet.type();
            } catch (Exception e) {
                return null;
            }
        });
        
        assertNotNull(results, "Memory-optimized execution should return results");
        assertFalse(results.isEmpty(), "Should have some results");
        
        // Verify all results are valid types
        for (var type : results) {
            assertNotNull(type, "Type should not be null");
            assertTrue(type >= 0 && type <= 5, "Type should be in valid range [0, 5]");
        }
    }

    @Test
    @DisplayName("Test optimization summary")
    void testOptimizationSummary() {
        // Generate some activity to populate metrics
        var compactTet = new TetCompactRepresentation.CompactTet(100, 200, 300, (byte) 8, (byte) 4);
        var pool = TetMemoryOptimizer.getGlobalPool();
        var point = pool.getPoint3f();
        pool.returnPoint3f(point);
        
        var summary = TetMemoryOptimizer.getOptimizationSummary();
        
        assertNotNull(summary, "Optimization summary should not be null");
        assertTrue(summary.contains("Tetrahedral Memory Optimization Summary"), 
            "Should have proper title");
        assertTrue(summary.contains("TetMemory Metrics"), "Should include memory metrics");
        assertTrue(summary.contains("TetMemoryPool Stats"), "Should include pool statistics");
        assertTrue(summary.contains("GC Optimization Stats"), "Should include GC statistics");
        assertTrue(summary.contains("TetCache Stats"), "Should include cache statistics");
    }

    @Test
    @DisplayName("Test memory optimization under stress")
    void testMemoryOptimizationStress() {
        var pool = TetMemoryOptimizer.getGlobalPool();
        var cache = TetMemoryOptimizer.getCacheEviction();
        
        // Create many compact representations
        var compactTets = new ArrayList<TetCompactRepresentation.CompactTet>();
        for (int i = 0; i < 1000; i++) {
            int coord = i * 10;
            byte level = (byte) (i % 20);
            byte type = (byte) (i % 6);
            compactTets.add(new TetCompactRepresentation.CompactTet(coord, coord, coord, level, type));
        }
        
        // Use pool extensively
        var points = new ArrayList<Point3f>();
        for (int i = 0; i < 100; i++) {
            points.add(pool.getPoint3f());
        }
        
        // Return to pool
        for (var point : points) {
            pool.returnPoint3f(point);
        }
        
        // Use cache extensively
        for (int i = 0; i < 500; i++) {
            final int index = i; // Make effectively final for lambda
            cache.getOrCompute((long) i, () -> "value_" + index, String.class);
        }
        
        // Verify system is still functional
        var metrics = TetMemoryOptimizer.getGlobalMetrics();
        assertTrue(metrics.getMetricsSummary().contains("Compact Representations Created: 1000"), 
            "Should track all compact creations");
        
        var poolStats = pool.getPoolStatistics();
        assertTrue(poolStats.contains("Point3f Pool Size"), "Pool should still be functional");
        
        var cacheStats = cache.getCacheStatistics();
        assertTrue(cacheStats.contains("Cache Size"), "Cache should still be functional");
    }

    @Test
    @DisplayName("Test edge cases and error handling")
    void testEdgeCasesAndErrorHandling() {
        // Test with empty collections
        var emptyTetrahedra = Collections.<Tet>emptyList();
        var emptyCompactRep = TetMemoryOptimizer.createCompactRepresentation(emptyTetrahedra);
        assertTrue(emptyCompactRep.isEmpty(), "Should handle empty collection");
        
        var emptyIndices = Collections.<Long>emptyList();
        var emptyResults = TetMemoryOptimizer.executeMemoryOptimized(emptyIndices, index -> index);
        assertTrue(emptyResults.isEmpty(), "Should handle empty indices");
        
        // Test with mix of valid and invalid tetrahedral indices
        var mixedIndices = List.of(-1L, 0L, 42L, -100L, 1000L);
        var resultsWithMixed = TetMemoryOptimizer.executeMemoryOptimized(mixedIndices, index -> {
            try {
                var tet = Tet.tetrahedron(index);
                return tet.index();
            } catch (Exception e) {
                return null; // Filtered out for invalid indices
            }
        });
        
        // Verify we get results only for valid indices (negative indices are invalid)
        assertNotNull(resultsWithMixed, "Should return a valid list");
        assertEquals(3, resultsWithMixed.size(), "Should have results for 3 valid indices (0, 42, 1000)");
        
        // Verify all returned results are valid indices
        for (var result : resultsWithMixed) {
            assertNotNull(result, "Result should not be null");
            assertTrue(result >= 0, "Returned index should be non-negative: " + result);
        }
        
        // Test cache with null supplier
        var cache = TetMemoryOptimizer.getCacheEviction();
        assertThrows(Exception.class, () -> 
            cache.getOrCompute(1L, null, String.class), "Should handle null supplier");
    }
}