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

package com.hellblazer.luciferase.lucien.balancing.fault;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for FaultAwarePartitionRegistry decorator.
 * Validates barrier timeout detection and fault reporting.
 */
class FaultAwarePartitionRegistryTest {

    private SimpleFaultHandler faultHandler;
    private MockPartitionRegistry mockRegistry;
    private FaultAwarePartitionRegistry decorated;

    /**
     * Mock implementation of PartitionRegistry for testing.
     */
    static class MockPartitionRegistry implements FaultAwarePartitionRegistry.PartitionRegistry {
        private volatile boolean shouldFail = false;
        private volatile long delayMs = 0;

        @Override
        public void barrier() throws InterruptedException {
            if (delayMs > 0) {
                Thread.sleep(delayMs);
            }
            if (shouldFail) {
                throw new InterruptedException("Simulated failure");
            }
        }

        void setDelay(long ms) {
            this.delayMs = ms;
        }

        void setFail(boolean fail) {
            this.shouldFail = fail;
        }
    }

    @BeforeEach
    void setUp() {
        faultHandler = new SimpleFaultHandler(FaultConfiguration.defaultConfig());
        faultHandler.start();

        mockRegistry = new MockPartitionRegistry();
        decorated = new FaultAwarePartitionRegistry(mockRegistry, faultHandler, 1000);
    }

    /**
     * T1: Test barrier succeeds when within timeout.
     */
    @Test
    void testBarrier_SucceedsWithinTimeout() {
        assertDoesNotThrow(() -> decorated.barrier(),
                           "Barrier should succeed within timeout");
    }

    /**
     * T2: Test barrier fails on timeout.
     */
    @Test
    void testBarrier_FailsOnTimeout() {
        mockRegistry.setDelay(2000);  // 2 seconds, timeout is 1 second

        assertThrows(TimeoutException.class, () -> decorated.barrier(),
                     "Barrier should timeout");
    }

    /**
     * T3: Test barrier fails immediately on error.
     */
    @Test
    void testBarrier_FailsOnError() {
        mockRegistry.setFail(true);

        assertThrows(InterruptedException.class, () -> decorated.barrier(),
                     "Barrier should fail on error");
    }

    /**
     * T4: Test multiple barrier calls.
     */
    @Test
    void testBarrier_MultipleCalls() {
        assertDoesNotThrow(() -> {
            decorated.barrier();
            decorated.barrier();
            decorated.barrier();
        }, "Multiple barrier calls should succeed");
    }

    /**
     * T5: Test null timeout validation.
     */
    @Test
    void testConstructor_NullDelegate() {
        assertThrows(NullPointerException.class,
                     () -> new FaultAwarePartitionRegistry(null, faultHandler, 1000),
                     "Should reject null delegate");
    }

    /**
     * T6: Test null handler validation.
     */
    @Test
    void testConstructor_NullHandler() {
        assertThrows(NullPointerException.class,
                     () -> new FaultAwarePartitionRegistry(mockRegistry, null, 1000),
                     "Should reject null handler");
    }
}
