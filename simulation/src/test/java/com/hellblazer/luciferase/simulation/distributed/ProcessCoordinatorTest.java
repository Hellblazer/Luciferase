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

import com.hellblazer.luciferase.simulation.transport.ProcessAddress;
import com.hellblazer.luciferase.simulation.transport.SocketTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ProcessCoordinator - centralized topology authority.
 * <p>
 * Test coverage:
 * - Constructor and initialization
 * - Process registration/unregistration
 * - Heartbeat monitoring
 * - Topology updates
 * - Registry queries
 * - Lifecycle (start/stop)
 *
 * @author hal.hildebrand
 */
class ProcessCoordinatorTest {

    private ProcessCoordinator coordinator;
    private SocketTransport transport;
    private ProcessAddress coordinatorAddress;

    @BeforeEach
    void setUp() throws IOException {
        // Use dynamic port for testing (0 = let OS assign)
        coordinatorAddress = ProcessAddress.localhost("coordinator", 0);
        transport = new SocketTransport(coordinatorAddress);
        transport.listenOn(coordinatorAddress);

        // Create coordinator (uses transport for communication)
        coordinator = new ProcessCoordinator(transport);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (coordinator != null) {
            coordinator.stop();
        }
        if (transport != null) {
            transport.closeAll();
        }
    }

    @Test
    void coordinatorInitializesSuccessfully() {
        assertNotNull(coordinator);
        assertNotNull(coordinator.getRegistry());
        assertFalse(coordinator.isRunning());
    }

    @Test
    void coordinatorStartsAndStops() throws Exception {
        coordinator.start();
        assertTrue(coordinator.isRunning());

        coordinator.stop();
        assertFalse(coordinator.isRunning());
    }

    @Test
    void registerProcessAddsToRegistry() throws Exception {
        var processId = UUID.randomUUID();
        var bubbles = List.of(UUID.randomUUID(), UUID.randomUUID());

        coordinator.registerProcess(processId, bubbles);

        var metadata = coordinator.getRegistry().getProcess(processId);
        assertNotNull(metadata);
        assertEquals(processId, metadata.processId());
        assertEquals(bubbles, metadata.bubbles());
    }

    @Test
    void unregisterProcessRemovesFromRegistry() throws Exception {
        var processId = UUID.randomUUID();
        var bubbles = List.of(UUID.randomUUID());

        coordinator.registerProcess(processId, bubbles);
        assertNotNull(coordinator.getRegistry().getProcess(processId));

        coordinator.unregisterProcess(processId);
        assertNull(coordinator.getRegistry().getProcess(processId));
    }

    @Test
    void getAllBubblesReturnsAllRegisteredBubbles() throws Exception {
        var process1 = UUID.randomUUID();
        var process2 = UUID.randomUUID();
        var bubbles1 = List.of(UUID.randomUUID(), UUID.randomUUID());
        var bubbles2 = List.of(UUID.randomUUID());

        coordinator.registerProcess(process1, bubbles1);
        coordinator.registerProcess(process2, bubbles2);

        var allBubbles = coordinator.getRegistry().getAllBubbles();
        assertEquals(3, allBubbles.size());
        assertTrue(allBubbles.containsAll(bubbles1));
        assertTrue(allBubbles.containsAll(bubbles2));
    }

    @Test
    void getAllProcessesReturnsAllActiveProcesses() throws Exception {
        var process1 = UUID.randomUUID();
        var process2 = UUID.randomUUID();

        coordinator.registerProcess(process1, List.of(UUID.randomUUID()));
        coordinator.registerProcess(process2, List.of(UUID.randomUUID()));

        var allProcesses = coordinator.getRegistry().getAllProcesses();
        assertEquals(2, allProcesses.size());
        assertTrue(allProcesses.contains(process1));
        assertTrue(allProcesses.contains(process2));
    }

    @Test
    void heartbeatUpdatesTouchesTimestamp() throws Exception {
        var processId = UUID.randomUUID();
        coordinator.registerProcess(processId, List.of(UUID.randomUUID()));

        var before = coordinator.getRegistry().getProcess(processId).lastHeartbeat();
        Thread.sleep(10); // Ensure time difference

        coordinator.processHeartbeatAck(processId);

        var after = coordinator.getRegistry().getProcess(processId).lastHeartbeat();
        assertTrue(after > before, "Heartbeat timestamp should be updated");
    }

    @Test
    void isAliveReturnsTrueForRecentHeartbeat() throws Exception {
        var processId = UUID.randomUUID();
        coordinator.registerProcess(processId, List.of(UUID.randomUUID()));

        // Fresh registration should be alive
        assertTrue(coordinator.getRegistry().isAlive(processId));
    }

    @Test
    void isAliveReturnsFalseAfterTimeout() throws Exception {
        var processId = UUID.randomUUID();
        coordinator.registerProcess(processId, List.of(UUID.randomUUID()));

        // Wait longer than heartbeat timeout (3000ms)
        Thread.sleep(3100);

        assertFalse(coordinator.getRegistry().isAlive(processId),
                    "Process should be considered dead after heartbeat timeout");
    }

    @Test
    void duplicateRegistrationUpdatesExisting() throws Exception {
        var processId = UUID.randomUUID();
        var bubbles1 = List.of(UUID.randomUUID());
        var bubbles2 = List.of(UUID.randomUUID(), UUID.randomUUID());

        coordinator.registerProcess(processId, bubbles1);
        coordinator.registerProcess(processId, bubbles2);

        var metadata = coordinator.getRegistry().getProcess(processId);
        assertEquals(bubbles2, metadata.bubbles(), "Should use latest bubble list");
    }

    @Test
    void broadcastTopologyUpdateNotifiesAllProcesses() throws Exception {
        // This test validates that broadcast API exists
        // Full integration testing will be in Phase 6B5
        var topology = coordinator.getRegistry().getAllBubbles();

        assertDoesNotThrow(() -> coordinator.broadcastTopologyUpdate(topology));
    }
}
