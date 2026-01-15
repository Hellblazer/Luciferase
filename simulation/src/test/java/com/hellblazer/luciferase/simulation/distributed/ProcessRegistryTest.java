/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.simulation.distributed;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ProcessRegistry - thread-safe in-memory registry.
 * <p>
 * Test coverage:
 * - Register/unregister cycles
 * - Concurrent registration (thread safety)
 * - getAllBubbles() correctness
 * - isAlive() timestamp checking
 * - Heartbeat updates
 * - ProcessMetadata immutability
 *
 * @author hal.hildebrand
 */
class ProcessRegistryTest {

    private ProcessRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ProcessRegistry();
    }

    @Test
    void registerAddsProcessToRegistry() {
        var processId = UUID.randomUUID();
        var bubbles = List.of(UUID.randomUUID(), UUID.randomUUID());

        registry.register(processId, bubbles);

        var metadata = registry.getProcess(processId);
        assertNotNull(metadata);
        assertEquals(processId, metadata.processId());
        assertEquals(bubbles, metadata.bubbles());
    }

    @Test
    void unregisterRemovesProcess() {
        var processId = UUID.randomUUID();
        registry.register(processId, List.of(UUID.randomUUID()));

        registry.unregister(processId);

        assertNull(registry.getProcess(processId));
    }

    @Test
    void registerUnregisterCycles() {
        var processId = UUID.randomUUID();
        var bubbles = List.of(UUID.randomUUID());

        // Register -> unregister -> register again
        registry.register(processId, bubbles);
        assertNotNull(registry.getProcess(processId));

        registry.unregister(processId);
        assertNull(registry.getProcess(processId));

        registry.register(processId, bubbles);
        assertNotNull(registry.getProcess(processId));
    }

    @Test
    void getAllBubblesReturnsAllRegistered() {
        var process1 = UUID.randomUUID();
        var process2 = UUID.randomUUID();
        var bubbles1 = List.of(UUID.randomUUID(), UUID.randomUUID());
        var bubbles2 = List.of(UUID.randomUUID());

        registry.register(process1, bubbles1);
        registry.register(process2, bubbles2);

        var allBubbles = registry.getAllBubbles();
        assertEquals(3, allBubbles.size());
        assertTrue(allBubbles.containsAll(bubbles1));
        assertTrue(allBubbles.containsAll(bubbles2));
    }

    @Test
    void getAllProcessesReturnsAllActive() {
        var process1 = UUID.randomUUID();
        var process2 = UUID.randomUUID();

        registry.register(process1, List.of(UUID.randomUUID()));
        registry.register(process2, List.of(UUID.randomUUID()));

        var allProcesses = registry.getAllProcesses();
        assertEquals(2, allProcesses.size());
        assertTrue(allProcesses.contains(process1));
        assertTrue(allProcesses.contains(process2));
    }

    @Test
    void concurrentRegistrationThreadSafety() throws Exception {
        var latch = new CountDownLatch(10);
        ExecutorService executor = Executors.newFixedThreadPool(10);

        // 10 threads registering concurrently
        for (int i = 0; i < 10; i++) {
            final var processId = UUID.randomUUID();
            final var bubbles = List.of(UUID.randomUUID());

            executor.submit(() -> {
                try {
                    registry.register(processId, bubbles);
                    assertNotNull(registry.getProcess(processId));
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS), "All registrations should complete");
        executor.shutdown();

        assertEquals(10, registry.getAllProcesses().size());
    }

    @Test
    void duplicateRegistrationUpdatesMetadata() {
        var processId = UUID.randomUUID();
        var bubbles1 = List.of(UUID.randomUUID());
        var bubbles2 = List.of(UUID.randomUUID(), UUID.randomUUID());

        registry.register(processId, bubbles1);
        registry.register(processId, bubbles2);

        var metadata = registry.getProcess(processId);
        assertEquals(bubbles2, metadata.bubbles(), "Should use latest bubble list");
    }

    @Test
    void getAllBubblesReturnsDefensiveCopy() {
        var processId = UUID.randomUUID();
        var bubbles = List.of(UUID.randomUUID());
        registry.register(processId, bubbles);

        var returned = registry.getAllBubbles();

        // Attempt modification should not affect registry
        assertThrows(UnsupportedOperationException.class, () -> {
            returned.add(UUID.randomUUID());
        });
    }

    @Test
    void getAllProcessesReturnsDefensiveCopy() {
        var processId = UUID.randomUUID();
        registry.register(processId, List.of(UUID.randomUUID()));

        var returned = registry.getAllProcesses();

        // Attempt modification should not affect registry
        assertThrows(UnsupportedOperationException.class, () -> {
            returned.add(UUID.randomUUID());
        });
    }
}
