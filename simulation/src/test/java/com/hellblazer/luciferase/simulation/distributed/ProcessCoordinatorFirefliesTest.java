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

import com.hellblazer.luciferase.simulation.delos.MembershipView;
import com.hellblazer.luciferase.simulation.von.LocalServerTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ProcessCoordinator Fireflies integration (Phase 4.1.4-4.1.5).
 * <p>
 * Test coverage:
 * - View change handling
 * - Automatic failure detection via view changes
 * - Coordinator selection via ring ordering
 * - Multiple view changes
 * - Empty view edge cases
 * <p>
 * Replaces manual heartbeat monitoring tests with Fireflies view change tests.
 *
 * @author hal.hildebrand
 */
class ProcessCoordinatorFirefliesTest {

    private LocalServerTransport.Registry registry;
    private ProcessCoordinator coordinator;
    private MockMembershipView<UUID> mockView;
    private UUID coordinatorId;

    @BeforeEach
    void setUp() throws Exception {
        registry = LocalServerTransport.Registry.create();
        coordinatorId = UUID.randomUUID();
        var transport = registry.register(coordinatorId);

        mockView = new MockMembershipView<>();
        coordinator = new ProcessCoordinator(transport, mockView);
        coordinator.start();
    }

    @AfterEach
    void tearDown() {
        if (coordinator != null) {
            coordinator.stop();
        }
        if (registry != null) {
            registry.close();
        }
    }

    // ========== View Change Handling Tests ==========

    @Test
    void viewChangeNotificationIsReceived() throws Exception {
        // Register a process
        var processId = UUID.randomUUID();
        coordinator.registerProcess(processId, List.of(UUID.randomUUID()));
        assertNotNull(coordinator.getRegistry().getProcess(processId));

        // Simulate view change: process leaves
        var change = new MembershipView.ViewChange<>(
            List.of(),  // No joins
            List.of(processId)  // Process left
        );
        mockView.simulateViewChange(change);

        // Give ProcessCoordinator time to handle view change
        Thread.sleep(100);

        // Verify process was unregistered
        assertNull(coordinator.getRegistry().getProcess(processId),
                  "Process should be automatically unregistered when it leaves the view");
    }

    @Test
    void viewChangeWithMultipleFailures() throws Exception {
        // Register three processes
        var process1 = UUID.randomUUID();
        var process2 = UUID.randomUUID();
        var process3 = UUID.randomUUID();

        coordinator.registerProcess(process1, List.of(UUID.randomUUID()));
        coordinator.registerProcess(process2, List.of(UUID.randomUUID()));
        coordinator.registerProcess(process3, List.of(UUID.randomUUID()));

        assertEquals(3, coordinator.getRegistry().getAllProcesses().size());

        // Simulate view change: two processes leave
        var change = new MembershipView.ViewChange<>(
            List.of(),  // No joins
            List.of(process1, process2)  // Two processes left
        );
        mockView.simulateViewChange(change);

        // Give time to process
        Thread.sleep(100);

        // Verify both processes were unregistered
        assertNull(coordinator.getRegistry().getProcess(process1));
        assertNull(coordinator.getRegistry().getProcess(process2));
        assertNotNull(coordinator.getRegistry().getProcess(process3));
        assertEquals(1, coordinator.getRegistry().getAllProcesses().size());
    }

    @Test
    void viewChangeWithJoinsDoesNotAffectRegistry() throws Exception {
        // Register a process
        var process1 = UUID.randomUUID();
        coordinator.registerProcess(process1, List.of(UUID.randomUUID()));

        // Simulate view change: new member joins (not yet registered with coordinator)
        var newMember = UUID.randomUUID();
        var change = new MembershipView.ViewChange<>(
            List.of(newMember),  // New member joined
            List.of()  // No leaves
        );
        mockView.simulateViewChange(change);

        Thread.sleep(100);

        // Verify existing process still registered
        assertNotNull(coordinator.getRegistry().getProcess(process1));
        // New member not auto-registered (must call registerProcess explicitly)
        assertNull(coordinator.getRegistry().getProcess(newMember));
    }

    // ========== Failure Detection Tests ==========

    @Test
    void unregisteredProcessInViewChangeIsIgnored() throws Exception {
        // Simulate view change for a process that was never registered
        var unknownProcess = UUID.randomUUID();
        var change = new MembershipView.ViewChange<>(
            List.of(),  // No joins
            List.of(unknownProcess)  // Unknown process "left"
        );

        // Should not throw exception
        assertDoesNotThrow(() -> {
            mockView.simulateViewChange(change);
            Thread.sleep(100);
        });
    }

    @Test
    void viewChangePreservesOtherProcesses() throws Exception {
        // Register multiple processes
        var survivor1 = UUID.randomUUID();
        var survivor2 = UUID.randomUUID();
        var failed = UUID.randomUUID();

        coordinator.registerProcess(survivor1, List.of(UUID.randomUUID()));
        coordinator.registerProcess(survivor2, List.of(UUID.randomUUID()));
        coordinator.registerProcess(failed, List.of(UUID.randomUUID()));

        // Simulate failure of one process
        var change = new MembershipView.ViewChange<>(
            List.of(),  // No joins
            List.of(failed)  // One process failed
        );
        mockView.simulateViewChange(change);
        Thread.sleep(100);

        // Verify survivors remain, failed is gone
        assertNotNull(coordinator.getRegistry().getProcess(survivor1));
        assertNotNull(coordinator.getRegistry().getProcess(survivor2));
        assertNull(coordinator.getRegistry().getProcess(failed));
    }

    // ========== Coordinator Selection (Ring Ordering) Tests ==========

    @Test
    void coordinatorSelectionUsesRingOrdering() throws Exception {
        // Create three process IDs
        var process1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var process2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
        var process3 = UUID.fromString("00000000-0000-0000-0000-000000000003");

        // Simulate view with all three members
        // Note: We need to populate the view monitor's members
        // This requires the view to return these members
        mockView.simulateViewChange(new MembershipView.ViewChange<>(
            List.of(process1, process2, process3),
            List.of()
        ));
        Thread.sleep(100);

        // Get coordinator - should be process1 (first in sorted order)
        var viewMonitor = coordinator.getViewMonitor();
        assertNotNull(viewMonitor);

        var members = viewMonitor.getCurrentMembers();
        assertEquals(3, members.size());

        // Coordinator should be first UUID in sorted order
        var selectedCoordinator = coordinator.getCoordinator();
        assertEquals(process1, selectedCoordinator,
                    "Coordinator should be first UUID in sorted view");
    }

    @Test
    void coordinatorChangesWhenFirstMemberLeaves() throws Exception {
        var process1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var process2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
        var process3 = UUID.fromString("00000000-0000-0000-0000-000000000003");

        // Initial view: all three members
        mockView.simulateViewChange(new MembershipView.ViewChange<>(
            List.of(process1, process2, process3),
            List.of()
        ));
        Thread.sleep(100);

        assertEquals(process1, coordinator.getCoordinator(),
                    "Initial coordinator should be process1");

        // Process1 leaves
        mockView.simulateViewChange(new MembershipView.ViewChange<>(
            List.of(),
            List.of(process1)
        ));
        Thread.sleep(100);

        // Coordinator should now be process2 (next in sorted order)
        assertEquals(process2, coordinator.getCoordinator(),
                    "Coordinator should change to process2 after process1 leaves");
    }

    @Test
    void isCoordinatorReturnsTrueForFirstMember() throws Exception {
        // Use the coordinator's own process ID
        var process2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
        var process3 = UUID.fromString("00000000-0000-0000-0000-000000000003");

        // View has this coordinator's ID plus others
        // Coordinator ID is larger than process1 in string comparison
        var process1 = UUID.fromString("00000000-0000-0000-0000-000000000001");

        // Create coordinator with process1 ID (will be coordinator)
        var transport1 = registry.register(process1);
        var view1 = new MockMembershipView<UUID>();
        var coordinator1 = new ProcessCoordinator(transport1, view1);
        coordinator1.start();

        try {
            // Populate view
            view1.simulateViewChange(new MembershipView.ViewChange<>(
                List.of(process1, process2, process3),
                List.of()
            ));
            Thread.sleep(100);

            // Process1 should be coordinator
            assertTrue(coordinator1.isCoordinator(),
                      "Process1 should be coordinator (first in sorted order)");
        } finally {
            coordinator1.stop();
        }
    }

    @Test
    void isCoordinatorReturnsFalseForNonFirstMember() throws Exception {
        var process1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var process2 = UUID.fromString("00000000-0000-0000-0000-000000000002");

        // Create coordinator with process2 ID (not first)
        var transport2 = registry.register(process2);
        var view2 = new MockMembershipView<UUID>();
        var coordinator2 = new ProcessCoordinator(transport2, view2);
        coordinator2.start();

        try {
            // Populate view
            view2.simulateViewChange(new MembershipView.ViewChange<>(
                List.of(process1, process2),
                List.of()
            ));
            Thread.sleep(100);

            // Process2 should NOT be coordinator
            assertFalse(coordinator2.isCoordinator(),
                       "Process2 should not be coordinator (process1 is first)");
        } finally {
            coordinator2.stop();
        }
    }

    @Test
    void emptyViewReturnsNullCoordinator() throws Exception {
        // Empty view
        var viewMonitor = coordinator.getViewMonitor();
        assertNotNull(viewMonitor);

        var members = viewMonitor.getCurrentMembers();
        assertTrue(members.isEmpty(), "View should be empty initially");

        // Coordinator should be null for empty view
        assertNull(coordinator.getCoordinator(),
                  "Coordinator should be null when view is empty");
        assertFalse(coordinator.isCoordinator(),
                   "isCoordinator should return false when view is empty");
    }

    @Test
    void coordinatorSelectionIsDeterministic() throws Exception {
        // Same view should always produce same coordinator
        var process1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var process2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
        var process3 = UUID.fromString("00000000-0000-0000-0000-000000000003");

        // Populate view (different order each time)
        mockView.simulateViewChange(new MembershipView.ViewChange<>(
            List.of(process3, process1, process2),  // Different order
            List.of()
        ));
        Thread.sleep(100);

        var coordinator1 = coordinator.getCoordinator();

        // Simulate re-joining in different order
        mockView.simulateViewChange(new MembershipView.ViewChange<>(
            List.of(),
            List.of(process1, process2, process3)
        ));
        Thread.sleep(100);

        mockView.simulateViewChange(new MembershipView.ViewChange<>(
            List.of(process2, process3, process1),  // Yet another order
            List.of()
        ));
        Thread.sleep(100);

        var coordinator2 = coordinator.getCoordinator();

        assertEquals(coordinator1, coordinator2,
                    "Coordinator selection should be deterministic regardless of join order");
        assertEquals(process1, coordinator1,
                    "Should always select first UUID in sorted order");
    }

    // ========== Edge Cases ==========

    @Test
    void viewMonitorNotInitializedBeforeStart() {
        // Create coordinator but don't start
        var transport = registry.register(UUID.randomUUID());
        var view = new MockMembershipView<UUID>();
        var coord = new ProcessCoordinator(transport, view);

        // View monitor should be null
        assertNull(coord.getViewMonitor(),
                  "View monitor should be null before start()");
        assertNull(coord.getCoordinator(),
                  "getCoordinator should return null before start()");
        assertFalse(coord.isCoordinator(),
                   "isCoordinator should return false before start()");
    }

    @Test
    void multipleViewChangesInRapidSuccession() throws Exception {
        var process1 = UUID.randomUUID();
        var process2 = UUID.randomUUID();
        var process3 = UUID.randomUUID();

        coordinator.registerProcess(process1, List.of(UUID.randomUUID()));
        coordinator.registerProcess(process2, List.of(UUID.randomUUID()));
        coordinator.registerProcess(process3, List.of(UUID.randomUUID()));

        // Rapid view changes
        mockView.simulateViewChange(new MembershipView.ViewChange<>(
            List.of(), List.of(process1)
        ));
        mockView.simulateViewChange(new MembershipView.ViewChange<>(
            List.of(), List.of(process2)
        ));
        mockView.simulateViewChange(new MembershipView.ViewChange<>(
            List.of(), List.of(process3)
        ));

        Thread.sleep(200);

        // All should be unregistered
        assertNull(coordinator.getRegistry().getProcess(process1));
        assertNull(coordinator.getRegistry().getProcess(process2));
        assertNull(coordinator.getRegistry().getProcess(process3));
        assertEquals(0, coordinator.getRegistry().getAllProcesses().size());
    }
}
