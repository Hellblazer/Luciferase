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

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for GhostSyncCallback (P2.3).
 * Validates ghost sync success/failure notifications and rank-to-UUID routing.
 */
class GhostSyncCallbackTest {

    private GhostSyncFaultAdapter adapter;
    private SimpleFaultHandler faultHandler;
    private UUID partitionId1;
    private UUID partitionId2;

    @BeforeEach
    void setUp() {
        faultHandler = new SimpleFaultHandler(FaultConfiguration.defaultConfig());
        faultHandler.start();

        adapter = new GhostSyncFaultAdapter(faultHandler);

        partitionId1 = UUID.randomUUID();
        partitionId2 = UUID.randomUUID();

        adapter.registerPartitionRank(1, partitionId1);
        adapter.registerPartitionRank(2, partitionId2);
    }

    /**
     * T1: Test sync success marks partition healthy.
     * onSyncSuccess should call markHealthy on the fault handler.
     */
    @Test
    void testOnSyncSuccess_MarksHealthy() {
        // Initially register partition
        faultHandler.markHealthy(partitionId1);

        // Simulate sync failure then success
        adapter.onSyncFailure(1, new Exception("temporary failure"));
        var statusBeforeSuccess = faultHandler.checkHealth(partitionId1);

        // Call sync success
        adapter.onSyncSuccess(1);

        // Should be healthy again
        var statusAfterSuccess = faultHandler.checkHealth(partitionId1);
        assertEquals(PartitionStatus.HEALTHY, statusAfterSuccess,
                     "Partition should be healthy after sync success");
    }

    /**
     * T2: Test sync failure reports to fault handler.
     * onSyncFailure should call reportSyncFailure.
     */
    @Test
    void testOnSyncFailure_ReportsSyncFailure() {
        faultHandler.markHealthy(partitionId1);

        var statusBefore = faultHandler.checkHealth(partitionId1);
        assertEquals(PartitionStatus.HEALTHY, statusBefore);

        // Call sync failure
        adapter.onSyncFailure(1, new Exception("test failure"));

        var statusAfter = faultHandler.checkHealth(partitionId1);
        assertTrue(statusAfter == PartitionStatus.SUSPECTED || statusAfter == PartitionStatus.FAILED,
                   "Partition should be SUSPECTED or FAILED after sync failure");
    }

    /**
     * T3: Test unknown rank is ignored gracefully.
     * Callback should not throw for unknown ranks.
     */
    @Test
    void testUnknownRank_Ignored() {
        // Call with rank that's not registered
        assertDoesNotThrow(() -> adapter.onSyncSuccess(99));
        assertDoesNotThrow(() -> adapter.onSyncFailure(99, new Exception("error")));
    }

    /**
     * T4: Test multiple partitions are routed correctly.
     * Different ranks should route to different partitions.
     */
    @Test
    void testMultiplePartitions_CorrectRouting() {
        faultHandler.markHealthy(partitionId1);
        faultHandler.markHealthy(partitionId2);

        // Fail partition 1, succeed on partition 2
        adapter.onSyncFailure(1, new Exception("failure"));
        adapter.onSyncSuccess(2);

        var status1 = faultHandler.checkHealth(partitionId1);
        var status2 = faultHandler.checkHealth(partitionId2);

        assertTrue(status1 == PartitionStatus.SUSPECTED || status1 == PartitionStatus.FAILED,
                   "Partition 1 should be SUSPECTED or FAILED");
        assertEquals(PartitionStatus.HEALTHY, status2,
                     "Partition 2 should be HEALTHY");
    }
}
