/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.simulation.distributed.integration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates the fixes for PerformanceStabilityTest failures.
 * <p>
 * Bead: Luciferase-x8ei
 *
 * @author hal.hildebrand
 */
class PerformanceStabilityTestValidation {

    @Test
    void testHeapMonitorWithStabilization() throws InterruptedException {
        // Given: HeapMonitor with warmup phase
        var heapMonitor = new HeapMonitor();

        // Warmup: Allocate and release some objects
        for (int i = 0; i < 10; i++) {
            var temp = new byte[1024 * 1024]; // 1MB allocation
        }
        System.gc();
        Thread.sleep(200);

        // When: Monitor stable heap
        heapMonitor.start(100);
        Thread.sleep(2000); // 2 seconds of stable monitoring
        heapMonitor.stop();

        // Then: Growth rate should be low (no significant leak)
        var growthRate = heapMonitor.getGrowthRate();
        assertFalse(heapMonitor.hasLeak(1_000_000),
            "Stable heap should not show leak, growth rate: " + growthRate + " bytes/sec");

        // Verify we collected enough samples
        assertTrue(heapMonitor.getSnapshots().size() >= 15,
            "Should have collected 15+ snapshots over 2 seconds");
    }

    @Test
    void testHeapMonitorCollectsSamples() throws InterruptedException {
        // Given: HeapMonitor running for a duration
        var heapMonitor = new HeapMonitor();

        heapMonitor.start(50); // Sample every 50ms
        Thread.sleep(1000); // Run for 1 second
        heapMonitor.stop();

        // Then: Should collect multiple samples
        var snapshots = heapMonitor.getSnapshots();
        assertTrue(snapshots.size() >= 15,
            "Should collect 15+ samples over 1 second with 50ms interval");

        // Growth rate calculation should complete without error (can be any value including 0)
        var growthRate = heapMonitor.getGrowthRate();
        // Just verify it doesn't throw - actual value depends on GC behavior
    }

    @Test
    void testEntityRetentionValidatorWithLongerInterval() throws InterruptedException {
        // Given: Mock accountant with stable entity count
        var mockAccountant = new MockEntityAccountant(100);
        var validator = new EntityRetentionValidator(mockAccountant, 100);

        // When: Run validation with longer interval
        validator.startPeriodicValidation(200);
        Thread.sleep(1000);
        validator.stop();

        // Then: Should have no violations
        assertEquals(0, validator.getViolationCount(),
            "No violations with stable entity count");
        assertTrue(validator.getCheckCount() >= 4,
            "Should have performed multiple checks");
    }

    @Test
    void testEntityRetentionValidatorDetectsViolation() {
        // Given: Mock accountant with incorrect entity count
        var mockAccountant = new MockEntityAccountant(50); // Expected 100, has 50
        var validator = new EntityRetentionValidator(mockAccountant, 100);

        // When: Validate once
        var result = validator.validateOnce();

        // Then: Should detect violation
        assertFalse(result.valid(), "Should detect entity count mismatch");
        assertEquals(1, validator.getViolationCount(),
            "Should increment violation count");
    }

    /**
     * Mock EntityAccountant for testing.
     */
    private static class MockEntityAccountant extends EntityAccountant {
        private final int entityCount;

        MockEntityAccountant(int entityCount) {
            this.entityCount = entityCount;
        }

        @Override
        public java.util.Map<java.util.UUID, Integer> getDistribution() {
            return java.util.Map.of(java.util.UUID.randomUUID(), entityCount);
        }

        @Override
        public ValidationResult validate() {
            return new ValidationResult(true, 0, java.util.List.of());
        }
    }
}
