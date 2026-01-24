/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.lucien.balancing;

import com.hellblazer.luciferase.lucien.balancing.fault.FaultHandler;
import com.hellblazer.luciferase.lucien.balancing.fault.SimpleFaultHandler;
import com.hellblazer.luciferase.lucien.balancing.fault.FaultConfiguration;
import com.hellblazer.luciferase.lucien.forest.ghost.DistributedGhostManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * TDD tests for GhostSync fault detection wiring (P4.1.3).
 *
 * <p>Validates that GhostSyncFaultAdapter properly integrates ghost sync operations with
 * fault detection, routing sync failures to SimpleFaultHandler and maintaining rank ↔ UUID mappings.
 *
 * <p><b>Test Strategy</b>:
 * <ol>
 *   <li>GhostSyncFaultAdapter registers as callback in DistributedGhostManager</li>
 *   <li>On sync success: adapter marks partition healthy in fault handler</li>
 *   <li>On sync failure: adapter reports fault to handler for state transition</li>
 *   <li>Rank ↔ UUID mappings populated and used for partition identification</li>
 * </ol>
 *
 * @author hal.hildebrand
 */
public class P413GhostSyncFaultWiringTest {

    @Mock
    private DistributedGhostManager<?, ?, ?> mockGhostManager;

    private SimpleFaultHandler faultHandler;
    private GhostSyncFaultAdapter adapter;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        // Create fault handler for testing
        var config = FaultConfiguration.defaultConfig();
        faultHandler = new SimpleFaultHandler(config);
        faultHandler.start();

        // Create adapter
        adapter = new GhostSyncFaultAdapter(faultHandler);
    }

    /**
     * Test 1: GhostSyncFaultAdapter registers callback in ghost manager.
     *
     * <p>Verifies that adapter can be registered as a callback for ghost sync events.
     */
    @Test
    void testCallbackRegistration() {
        // Given: Adapter ready to register
        assertNotNull(adapter, "Adapter should be created");

        // When: Register adapter as callback
        adapter.registerWithGhostManager(mockGhostManager);

        // Then: Verify registration was called
        verify(mockGhostManager).registerSyncCallback(adapter);
    }

    /**
     * Test 2: On sync success, adapter marks partition healthy in fault handler.
     *
     * <p>Verifies that successful ghost sync operations mark partition as healthy.
     */
    @Test
    void testOnSyncSuccess() {
        // Given: Partition ID and rank mapping
        var partitionId = UUID.randomUUID();
        var rank = 1;
        adapter.mapRankToPartition(rank, partitionId);

        // First mark as suspected to verify recovery
        faultHandler.reportSyncFailure(partitionId);
        var initialStatus = faultHandler.checkHealth(partitionId);
        assertTrue(initialStatus != null, "Partition should be tracked");

        // When: Call onSyncSuccess
        adapter.onSyncSuccess(rank);

        // Then: Partition should be marked healthy
        var finalStatus = faultHandler.checkHealth(partitionId);
        // Status should be HEALTHY after sync success
        assertEquals("HEALTHY", finalStatus.toString().split("\\[")[0], "Partition should be healthy");
    }

    /**
     * Test 3: On sync failure, adapter reports fault to handler.
     *
     * <p>Verifies that ghost sync failures are properly reported to fault handler.
     */
    @Test
    void testOnSyncFailure() {
        // Given: Partition ID and rank mapping
        var partitionId = UUID.randomUUID();
        var rank = 2;
        adapter.mapRankToPartition(rank, partitionId);

        // When: Call onSyncFailure
        adapter.onSyncFailure(rank, new RuntimeException("Network error"));

        // Then: Fault should be reported to handler
        var status = faultHandler.checkHealth(partitionId);
        assertNotNull(status, "Partition should be tracked after sync failure");
        // Status should indicate fault (SUSPECTED or FAILED)
        assertTrue(!status.toString().contains("HEALTHY"), "Partition should not be healthy after sync failure");
    }

    /**
     * Test 4: Rank ↔ UUID mappings are maintained and used correctly.
     *
     * <p>Verifies that adapter properly maps ranks to partition UUIDs for identification.
     */
    @Test
    void testRankUUIDMappingPopulation() {
        // Given: Multiple partition mappings
        var partitionId1 = UUID.randomUUID();
        var partitionId2 = UUID.randomUUID();
        var partitionId3 = UUID.randomUUID();

        // When: Add multiple mappings
        adapter.mapRankToPartition(1, partitionId1);
        adapter.mapRankToPartition(2, partitionId2);
        adapter.mapRankToPartition(3, partitionId3);

        // Then: Verify mappings work correctly
        assertEquals(partitionId1, adapter.getPartitionIdForRank(1), "Rank 1 should map to partitionId1");
        assertEquals(partitionId2, adapter.getPartitionIdForRank(2), "Rank 2 should map to partitionId2");
        assertEquals(partitionId3, adapter.getPartitionIdForRank(3), "Rank 3 should map to partitionId3");
    }

    /**
     * Test 5: Integration test - Complete sync failure handling flow.
     *
     * <p>Verifies end-to-end fault detection flow from sync failure through handler notification.
     */
    @Test
    void testCompleteSyncFailureFlow() {
        // Given: Adapter with fault handler and partition mapping
        var partitionId = UUID.randomUUID();
        var rank = 1;
        adapter.mapRankToPartition(rank, partitionId);

        // Track failure notifications
        var failureCount = new AtomicInteger(0);
        faultHandler.subscribeToChanges(event -> {
            if (partitionId.equals(event.partitionId())) {
                failureCount.incrementAndGet();
            }
        });

        // When: Sync operation fails
        adapter.onSyncFailure(rank, new Exception("Sync failure"));

        // Then: Handler should have been notified
        assertTrue(failureCount.get() > 0, "Should notify on state transition");

        // And: Partition should be in a fault state
        var status = faultHandler.checkHealth(partitionId);
        assertNotNull(status, "Partition should be tracked");
    }
}
