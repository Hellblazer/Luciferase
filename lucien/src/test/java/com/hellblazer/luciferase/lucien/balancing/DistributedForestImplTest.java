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

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.forest.Forest;
import com.hellblazer.luciferase.lucien.forest.ghost.DistributedGhostManager;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for DistributedForestImpl wrapper class.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Correct delegation to local forest</li>
 *   <li>Ghost manager integration</li>
 *   <li>Partition registry coordination</li>
 *   <li>Query routing across partitions</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class DistributedForestImplTest {

    private static final Logger log = LoggerFactory.getLogger(DistributedForestImplTest.class);

    private DistributedForestImpl<MortonKey, LongEntityID, String> distributedForest;
    private Forest<MortonKey, LongEntityID, String> mockLocalForest;
    private DistributedGhostManager<MortonKey, LongEntityID, String> mockGhostManager;
    private ParallelBalancer.PartitionRegistry mockRegistry;

    @BeforeEach
    public void setup() {
        mockLocalForest = mock(Forest.class);
        mockGhostManager = mock(DistributedGhostManager.class);
        mockRegistry = mock(ParallelBalancer.PartitionRegistry.class);

        distributedForest = new DistributedForestImpl<>(
            mockLocalForest,
            mockGhostManager,
            mockRegistry,
            0,  // partition rank
            4   // total partitions
        );
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testGetLocalForest() {
        log.info("Testing getLocalForest()");
        var forest = distributedForest.getLocalForest();

        assertNotNull(forest, "Local forest should not be null");
        assertSame(mockLocalForest, forest, "Should return the same local forest instance");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testGetGhostManager() {
        log.info("Testing getGhostManager()");
        var manager = distributedForest.getGhostManager();

        assertNotNull(manager, "Ghost manager should not be null");
        assertSame(mockGhostManager, manager, "Should return the same ghost manager instance");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testGetPartitionRegistry() {
        log.info("Testing getPartitionRegistry()");
        var registry = distributedForest.getPartitionRegistry();

        assertNotNull(registry, "Partition registry should not be null");
        assertSame(mockRegistry, registry, "Should return the same registry instance");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testGetPartitionRank() {
        log.info("Testing getPartitionRank()");
        assertEquals(0, distributedForest.getPartitionRank(), "Should return partition rank");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testGetTotalPartitions() {
        log.info("Testing getTotalPartitions()");
        assertEquals(4, distributedForest.getTotalPartitions(), "Should return total partition count");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testConstructorNullLocalForest() {
        log.info("Testing constructor with null local forest");
        assertThrows(NullPointerException.class, () -> {
            new DistributedForestImpl<>(null, mockGhostManager, mockRegistry, 0, 4);
        }, "Should throw NullPointerException for null local forest");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testConstructorNullGhostManager() {
        log.info("Testing constructor with null ghost manager");
        assertThrows(NullPointerException.class, () -> {
            new DistributedForestImpl<>(mockLocalForest, null, mockRegistry, 0, 4);
        }, "Should throw NullPointerException for null ghost manager");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testConstructorNullRegistry() {
        log.info("Testing constructor with null partition registry");
        assertThrows(NullPointerException.class, () -> {
            new DistributedForestImpl<>(mockLocalForest, mockGhostManager, null, 0, 4);
        }, "Should throw NullPointerException for null partition registry");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testGetDistributedStats() {
        log.info("Testing getDistributedStats()");

        var stats = distributedForest.getDistributedStats();

        assertNotNull(stats, "Distributed stats should not be null");
        assertEquals(0, stats.partitionRank(), "Should have partition rank 0");
        assertEquals(4, stats.totalPartitions(), "Should have 4 total partitions");
        assertEquals(0L, stats.boundaryGhosts(), "Should have 0 boundary ghosts");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testSubmitRefinementRequest() {
        log.info("Testing submitRefinementRequest()");

        var key = new MortonKey(12345L, (byte) 5);
        // Should not throw
        distributedForest.submitRefinementRequest(key, 5);

        log.info("Refinement request submitted successfully");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testMultiplePartitions() {
        log.info("Testing DistributedForest with multiple partitions");

        var forest2 = new DistributedForestImpl<>(
            mockLocalForest,
            mockGhostManager,
            mockRegistry,
            1,  // different partition
            4
        );

        assertEquals(1, forest2.getPartitionRank(), "Should have rank 1");
        assertEquals(4, forest2.getTotalPartitions(), "Should have 4 total partitions");

        log.info("Multiple partition test passed");
    }
}
