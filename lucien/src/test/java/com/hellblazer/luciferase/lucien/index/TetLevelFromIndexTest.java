package com.hellblazer.luciferase.lucien.index;

import com.hellblazer.luciferase.lucien.Tet;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Comprehensive tests for tetLevelFromIndex and round-trip Tet <-> index transformations
 *
 * @author hal.hildebrand
 */
public class TetLevelFromIndexTest {

    @Test
    public void testComprehensiveRoundTrip() {
        System.out.println("=== Testing Comprehensive Round-Trip: Tet -> index -> Tet ===");

        int failures = 0;
        int successes = 0;
        List<String> failureDetails = new ArrayList<>();

        // Test all levels from 0 to a reasonable maximum
        for (byte level = 0; level <= 8; level++) {
            System.out.printf("Testing level %d:%n", level);

            // Calculate the range of indices for this level
            long startIndex = level == 0 ? 0 : (1L << (3 * (level - 1)));
            long endIndex = (1L << (3 * level));

            // For higher levels, test a representative sample to avoid excessive runtime
            long maxTestsPerLevel = Math.min(endIndex - startIndex, level <= 4 ? (endIndex - startIndex) : 1000);
            long step = Math.max(1, (endIndex - startIndex) / maxTestsPerLevel);

            for (long index = startIndex; index < endIndex; index += step) {
                try {
                    // Step 1: Calculate level from index
                    byte calculatedLevel = Tet.tetLevelFromIndex(index);

                    if (calculatedLevel != level) {
                        failures++;
                        String error = String.format("Level calculation error: index %d expected level %d, got %d",
                                                     index, level, calculatedLevel);
                        failureDetails.add(error);
                        continue;
                    }

                    // Step 2: Create Tet from index (using calculated level)
                    Tet originalTet = Tet.tetrahedron(index, calculatedLevel);

                    // Step 3: Calculate index from Tet
                    long reconstructedIndex = originalTet.index();

                    // Step 4: Verify round-trip consistency
                    if (reconstructedIndex != index) {
                        failures++;
                        String error = String.format(
                        "Round-trip failure: index %d -> tet(%d,%d,%d,level=%d,type=%d) -> index %d", index,
                        originalTet.x(), originalTet.y(), originalTet.z(), originalTet.l(), originalTet.type(),
                        reconstructedIndex);
                        failureDetails.add(error);
                    } else {
                        successes++;
                    }

                    // Step 5: Test the single-parameter tetrahedron method
                    Tet autoLevelTet = Tet.tetrahedron(index);  // This should use tetLevelFromIndex internally
                    long autoLevelIndex = autoLevelTet.index();

                    if (autoLevelIndex != index) {
                        failures++;
                        String error = String.format(
                        "Auto-level round-trip failure: index %d -> tet(auto-level) -> index %d", index,
                        autoLevelIndex);
                        failureDetails.add(error);
                    }

                    // Verify both methods produce identical results
                    if (!originalTet.equals(autoLevelTet)) {
                        failures++;
                        String error = String.format(
                        "Method inconsistency: index %d produces different tets with explicit vs auto level", index);
                        failureDetails.add(error);
                    }

                } catch (Exception e) {
                    failures++;
                    failureDetails.add(String.format("Exception for index %d: %s", index, e.getMessage()));
                }
            }

            System.out.printf("  Level %d: tested %d indices%n", level, (int) maxTestsPerLevel);
        }

        System.out.printf("Results: %d successes, %d failures%n", successes, failures);

        if (failures > 0) {
            System.out.println("Failure details:");
            for (int i = 0; i < Math.min(10, failureDetails.size()); i++) {
                System.out.println("  " + failureDetails.get(i));
            }
            if (failureDetails.size() > 10) {
                System.out.printf("  ... and %d more failures%n", failureDetails.size() - 10);
            }
        }

        assertEquals(0, failures, "Round-trip tests should not fail");
        System.out.println("✅ Comprehensive round-trip test passed!");
    }

    @Test
    public void testConsistencyWithExplicitLevel() {
        System.out.println("=== Testing Consistency: tetrahedron(index) vs tetrahedron(index, level) ===");

        int failures = 0;

        for (byte level = 0; level <= 6; level++) {
            long startIndex = level == 0 ? 0 : (1L << (3 * (level - 1)));
            long endIndex = Math.min(1L << (3 * level), startIndex + 1000); // Limit for performance

            for (long index = startIndex; index < endIndex; index++) {
                // Method 1: Auto-calculate level
                Tet autoTet = Tet.tetrahedron(index);

                // Method 2: Explicit level
                Tet explicitTet = Tet.tetrahedron(index, level);

                // They should be identical
                if (!autoTet.equals(explicitTet)) {
                    failures++;
                    System.out.printf("Inconsistency for index %d: auto=%s, explicit=%s%n", index, autoTet,
                                      explicitTet);
                }

                // Both should round-trip correctly
                assertEquals(index, autoTet.index(), "Auto method round-trip failed for index " + index);
                assertEquals(index, explicitTet.index(), "Explicit method round-trip failed for index " + index);
            }
        }

        assertEquals(0, failures, "Methods should be consistent");
        System.out.println("✅ Method consistency verified!");
    }

    @Test
    public void testEdgeCasesAndBoundaries() {
        System.out.println("=== Testing Edge Cases and Boundaries ===");

        // Test specific edge cases
        long[] edgeCases = { 0,          // Root
                             1, 7,       // Level 1 boundaries
                             8, 63,      // Level 2 boundaries
                             64, 511,    // Level 3 boundaries
                             512, 4095,  // Level 4 boundaries
                             4096, 32767, // Level 5 boundaries
        };

        for (long index : edgeCases) {
            byte level = Tet.tetLevelFromIndex(index);

            // Test round-trip with calculated level
            Tet tet = Tet.tetrahedron(index, level);
            long reconstructed = tet.index();
            assertEquals(index, reconstructed, "Edge case round-trip failed for index " + index);

            // Test single-parameter method
            Tet autoTet = Tet.tetrahedron(index);
            long autoReconstructed = autoTet.index();
            assertEquals(index, autoReconstructed, "Auto-level edge case failed for index " + index);

            // Verify both methods produce identical results
            assertEquals(tet, autoTet, "Methods should produce identical results for index " + index);

            System.out.printf("✓ Edge case index %d (level %d) passed%n", index, level);
        }

        System.out.println("✅ Edge cases passed!");
    }

    @Test
    public void testLevelCalculationFormula() {
        System.out.println("=== Testing Level Calculation Formula ===");

        // Verify the mathematical formula: level = ceil(log2(index+1) / 3)
        // For tetrahedral SFC with 8 children per level (3 bits per level)

        for (byte expectedLevel = 0; expectedLevel <= 20; expectedLevel++) {
            long startIndex = expectedLevel == 0 ? 0 : (1L << (3 * (expectedLevel - 1)));
            long endIndex = (1L << (3 * expectedLevel)) - 1;

            // Test start of range
            if (startIndex >= 0) {
                byte calculatedLevel = Tet.tetLevelFromIndex(startIndex);
                assertEquals(expectedLevel, calculatedLevel,
                             String.format("Formula error at start of level %d: index %d", expectedLevel, startIndex));
            }

            // Test end of range
            if (endIndex > startIndex && endIndex < Long.MAX_VALUE) {
                byte calculatedLevel = Tet.tetLevelFromIndex(endIndex);
                assertEquals(expectedLevel, calculatedLevel,
                             String.format("Formula error at end of level %d: index %d", expectedLevel, endIndex));
            }

            // Test middle of range
            if (endIndex > startIndex) {
                long midIndex = (startIndex + endIndex) / 2;
                byte calculatedLevel = Tet.tetLevelFromIndex(midIndex);
                assertEquals(expectedLevel, calculatedLevel,
                             String.format("Formula error at middle of level %d: index %d", expectedLevel, midIndex));
            }

            System.out.printf("✓ Level %d: indices [%d, %d] correctly calculated%n", expectedLevel, startIndex,
                              endIndex);
        }

        System.out.println("✅ Level calculation formula verified!");
    }

    @Test
    public void testNegativeAndInvalidIndices() {
        System.out.println("=== Testing Negative and Invalid Indices ===");

        // Test that negative indices are handled correctly
        // The current implementation uses Long.numberOfLeadingZeros which doesn't handle negatives well

        long[] invalidIndices = { -1, -5, -100, Long.MIN_VALUE };

        for (long index : invalidIndices) {
            try {
                byte level = Tet.tetLevelFromIndex(index);
                System.out.printf("Index %d -> level %d (should this be valid?)%n", index, level);

                // If tetLevelFromIndex doesn't throw, verify tetrahedron construction fails gracefully
                assertThrows(Exception.class, () -> Tet.tetrahedron(index),
                             "Negative index " + index + " should not create valid tetrahedron");

            } catch (Exception e) {
                System.out.printf("Index %d correctly threw exception: %s%n", index, e.getClass().getSimpleName());
            }
        }

        System.out.println("✅ Invalid index handling tested");
    }

    @Test
    public void testRandomizedRoundTrip() {
        System.out.println("=== Testing Randomized Round-Trip ===");

        Random random = new Random(0x12345);
        int numTests = 10000;
        int failures = 0;

        for (int i = 0; i < numTests; i++) {
            // Generate random index within reasonable bounds
            byte randomLevel = (byte) random.nextInt(22); // Levels 0-21
            long maxIndex = 1L << (3 * randomLevel);
            long minIndex = randomLevel == 0 ? 0 : (1L << (3 * (randomLevel - 1)));
            long range = maxIndex - minIndex;
            long randomIndex = minIndex + Math.abs(random.nextLong()) % range;

            try {
                // Test round-trip consistency
                byte calculatedLevel = Tet.tetLevelFromIndex(randomIndex);
                assertEquals(randomLevel, calculatedLevel,
                             "Level calculation mismatch for random index " + randomIndex);

                Tet tet = Tet.tetrahedron(randomIndex);
                long reconstructed = tet.index();

                if (reconstructed != randomIndex) {
                    failures++;
                    System.out.printf("Random round-trip failure %d: index %d -> index %d%n", failures, randomIndex,
                                      reconstructed);
                }

            } catch (Exception e) {
                failures++;
                System.out.printf("Random test exception %d: index %d, error %s%n", failures, randomIndex,
                                  e.getMessage());
            }
        }

        System.out.printf("Random test results: %d/%d passed%n", numTests - failures, numTests);
        assertEquals(0, failures, "Randomized round-trip tests should not fail");
        System.out.println("✅ Randomized round-trip test passed!");
    }

    @Test
    public void testTetLevelFromIndexAlgorithm() {
        System.out.println("=== Testing tetLevelFromIndex Algorithm ===");

        // Test the mathematical properties of the level calculation

        // Level 0: index 0 only
        assertEquals(0, Tet.tetLevelFromIndex(0), "Index 0 should be level 0");

        // Level 1: indices 1-7 (uses 3 bits, range [1, 2^3-1])
        for (long i = 1; i <= 7; i++) {
            assertEquals(1, Tet.tetLevelFromIndex(i), "Index " + i + " should be level 1");
        }

        // Level 2: indices 8-63 (uses 6 bits, range [2^3, 2^6-1])
        for (long i = 8; i <= 63; i++) {
            assertEquals(2, Tet.tetLevelFromIndex(i), "Index " + i + " should be level 2");
        }

        // Level 3: indices 64-511 (uses 9 bits, range [2^6, 2^9-1])
        for (long i = 64; i <= 511; i++) {
            assertEquals(3, Tet.tetLevelFromIndex(i), "Index " + i + " should be level 3");
        }

        // Level 4: indices 512-4095 (uses 12 bits, range [2^9, 2^12-1])
        for (long i = 512; i <= 4095; i++) {
            assertEquals(4, Tet.tetLevelFromIndex(i), "Index " + i + " should be level 4");
        }

        // Test boundary conditions more precisely
        long[] levelBoundaries = { 0, 1, 8, 64, 512, 4096, 32768, 262144, 2097152, 16777216, 134217728 };
        byte[] expectedLevels = { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 };

        for (int i = 0; i < levelBoundaries.length; i++) {
            long index = levelBoundaries[i];
            byte expectedLevel = expectedLevels[i];
            byte actualLevel = Tet.tetLevelFromIndex(index);
            assertEquals(expectedLevel, actualLevel,
                         "Boundary index " + index + " should be level " + expectedLevel + " but got " + actualLevel);
        }

        System.out.println("✅ Level calculation algorithm verified");
    }
}
