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
package com.hellblazer.luciferase.simulation.topology;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.luciferase.simulation.bubble.TetreeBubbleGrid;
import com.hellblazer.luciferase.simulation.consensus.committee.ViewCommitteeConsensus;
import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import com.hellblazer.luciferase.simulation.distributed.integration.EntityAccountant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.vecmath.Point3f;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Tests for TopologyConsensusCoordinator cooldown, pre-validation, and clock integration.
 *
 * @author hal.hildebrand
 */
class TopologyConsensusCoordinatorTest {

    private TetreeBubbleGrid bubbleGrid;
    private EntityAccountant accountant;
    private TopologyConsensusCoordinator coordinator;
    private TestClock testClock;
    private ViewCommitteeConsensus mockConsensus;

    @BeforeEach
    void setUp() {
        bubbleGrid = new TetreeBubbleGrid((byte) 2);
        accountant = new EntityAccountant();
        coordinator = new TopologyConsensusCoordinator(bubbleGrid, 10_000L); // 10 second cooldown for testing

        testClock = new TestClock();
        testClock.setMillis(1000L);
        coordinator.setClock(testClock);

        mockConsensus = Mockito.mock(ViewCommitteeConsensus.class);
        coordinator.setConsensusProtocol(mockConsensus);

        // Mock consensus to always approve
        when(mockConsensus.requestConsensus(any())).thenReturn(CompletableFuture.completedFuture(true));
    }

    @Test
    void testCooldownEnforcementRejectsRapidChanges() {
        // Create bubble with >5000 entities for valid split
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntities(bubble, 5100);

        var proposal = createSplitProposal(bubble.id());

        // First proposal should pass cooldown check
        assertTrue(coordinator.canProposeTopologyChange(proposal),
                  "First proposal should pass cooldown check");

        // Request consensus (approved)
        var result = coordinator.requestConsensus(proposal).join();
        assertTrue(result, "First proposal should be approved");

        // Advance time by only 5 seconds (less than 10 second cooldown)
        testClock.setMillis(6000L);

        // Second proposal should be rejected by cooldown
        var proposal2 = createSplitProposal(bubble.id());
        assertFalse(coordinator.canProposeTopologyChange(proposal2),
                   "Second proposal should be rejected by cooldown");

        var result2 = coordinator.requestConsensus(proposal2).join();
        assertFalse(result2, "Second proposal should be rejected");
    }

    @Test
    void testCooldownElapsedAllowsNewProposal() {
        // Create bubble with >5000 entities
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntities(bubble, 5100);

        var proposal = createSplitProposal(bubble.id());

        // First proposal
        coordinator.requestConsensus(proposal).join();

        // Advance time beyond cooldown (10 seconds)
        testClock.setMillis(12000L);

        // Second proposal should now pass cooldown
        var proposal2 = createSplitProposal(bubble.id());
        assertTrue(coordinator.canProposeTopologyChange(proposal2),
                  "Proposal should pass after cooldown elapsed");

        var result2 = coordinator.requestConsensus(proposal2).join();
        assertTrue(result2, "Proposal should be approved after cooldown");
    }

    @Test
    void testPreValidationRejectsByzantineProposal() {
        // Create bubble with only 1000 entities (below split threshold)
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntities(bubble, 1000);

        var proposal = createSplitProposal(bubble.id());

        // Proposal should be rejected by pre-validation (below threshold)
        var result = coordinator.requestConsensus(proposal).join();
        assertFalse(result, "Byzantine proposal (below threshold) should be rejected");
    }

    @Test
    void testClockInjection() {
        testClock.setMillis(5000L);

        // Create bubble and first proposal
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntities(bubble, 5100);

        var proposal = createSplitProposal(bubble.id());
        coordinator.requestConsensus(proposal).join();

        // Check remaining cooldown uses injected clock
        testClock.setMillis(8000L); // 3 seconds after approval
        long remaining = coordinator.getRemainingCooldown(bubble.id());
        assertEquals(7000L, remaining, "Remaining cooldown should use injected clock");
    }

    @Test
    void testAffectedBubblesForSplit() {
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntities(bubble, 5100);

        var proposal = createSplitProposal(bubble.id());
        coordinator.requestConsensus(proposal).join();

        // Only source bubble should be in cooldown
        assertTrue(coordinator.getRemainingCooldown(bubble.id()) > 0,
                  "Source bubble should be in cooldown");
    }

    @Test
    void testAffectedBubblesForMerge() {
        // Create 2 bubbles with low entity counts
        bubbleGrid.createBubbles(2, (byte) 1, 10);
        var bubbles = bubbleGrid.getAllBubbles().stream().toList();
        var bubble1 = bubbles.get(0);
        var bubble2 = bubbles.get(1);

        addEntities(bubble1, 300);
        addEntities(bubble2, 300);

        // Only test if bubbles are actually neighbors
        var neighbors = bubbleGrid.getNeighbors(bubble1.id());
        if (neighbors.contains(bubble2.id())) {
            var proposal = new MergeProposal(
                UUID.randomUUID(),
                bubble1.id(),
                bubble2.id(),
                DigestAlgorithm.DEFAULT.getOrigin(),
                testClock.currentTimeMillis()
            );

            coordinator.requestConsensus(proposal).join();

            // Both bubbles should be in cooldown
            assertTrue(coordinator.getRemainingCooldown(bubble1.id()) > 0,
                      "Bubble1 should be in cooldown");
            assertTrue(coordinator.getRemainingCooldown(bubble2.id()) > 0,
                      "Bubble2 should be in cooldown");
        }
    }

    @Test
    void testRemainingCooldownCalculation() {
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntities(bubble, 5100);

        testClock.setMillis(1000L);
        var proposal = createSplitProposal(bubble.id());
        coordinator.requestConsensus(proposal).join();

        // Check remaining at various points
        testClock.setMillis(4000L); // 3 seconds after
        assertEquals(7000L, coordinator.getRemainingCooldown(bubble.id()),
                    "Remaining should be 7 seconds");

        testClock.setMillis(8000L); // 7 seconds after
        assertEquals(3000L, coordinator.getRemainingCooldown(bubble.id()),
                    "Remaining should be 3 seconds");

        testClock.setMillis(12000L); // 11 seconds after (past cooldown)
        assertEquals(0L, coordinator.getRemainingCooldown(bubble.id()),
                    "Remaining should be 0 after cooldown elapsed");
    }

    @Test
    void testResetClearsCooldownState() {
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntities(bubble, 5100);

        var proposal = createSplitProposal(bubble.id());
        coordinator.requestConsensus(proposal).join();

        // Verify cooldown active
        assertTrue(coordinator.getRemainingCooldown(bubble.id()) > 0,
                  "Cooldown should be active");

        // Reset
        coordinator.reset();

        // Cooldown should be cleared
        assertEquals(0L, coordinator.getRemainingCooldown(bubble.id()),
                    "Cooldown should be cleared after reset");
    }

    @Test
    void testConsensusProtocolRequired() {
        var coordinator2 = new TopologyConsensusCoordinator(bubbleGrid);
        coordinator2.setClock(testClock);
        // Don't set consensus protocol

        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntities(bubble, 5100);

        var proposal = createSplitProposal(bubble.id());

        assertThrows(IllegalStateException.class, () -> {
            coordinator2.requestConsensus(proposal);
        }, "Should throw IllegalStateException when consensus protocol not set");
    }

    @Test
    void testDefaultCooldownPeriod() {
        var coordinator2 = new TopologyConsensusCoordinator(bubbleGrid);

        // Should use DEFAULT_COOLDOWN_MS (30 seconds)
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntities(bubble, 5100);

        coordinator2.setClock(testClock);
        coordinator2.setConsensusProtocol(mockConsensus);

        testClock.setMillis(1000L);
        var proposal = createSplitProposal(bubble.id());
        coordinator2.requestConsensus(proposal).join();

        // Check remaining cooldown is ~30 seconds
        testClock.setMillis(2000L); // 1 second after
        long remaining = coordinator2.getRemainingCooldown(bubble.id());
        assertEquals(29000L, remaining, "Default cooldown should be 30 seconds");
    }

    @Test
    void testNegativeCooldownThrows() {
        assertThrows(IllegalArgumentException.class, () -> {
            new TopologyConsensusCoordinator(bubbleGrid, -1000L);
        }, "Should reject negative cooldown");
    }

    @Test
    void testNullBubbleGridThrows() {
        assertThrows(NullPointerException.class, () -> {
            new TopologyConsensusCoordinator(null);
        }, "Should reject null bubble grid");
    }

    @Test
    void testNullProposalThrows() {
        assertThrows(NullPointerException.class, () -> {
            coordinator.requestConsensus(null);
        }, "Should reject null proposal");
    }

    // Helper methods

    private void addEntities(com.hellblazer.luciferase.simulation.bubble.EnhancedBubble bubble, int count) {
        for (int i = 0; i < count; i++) {
            var entityId = UUID.randomUUID();
            bubble.addEntity(
                entityId.toString(),
                new Point3f(i * 0.01f, i * 0.01f, i * 0.01f),
                null
            );
            accountant.register(bubble.id(), entityId);
        }
    }

    private SplitProposal createSplitProposal(UUID bubbleId) {
        var bubble = bubbleGrid.getBubbleById(bubbleId);
        var centroid = bubble.bounds().centroid();
        var splitPlane = new SplitPlane(
            new Point3f(1.0f, 0.0f, 0.0f),
            (float) centroid.getX()
        );

        return new SplitProposal(
            UUID.randomUUID(),
            bubbleId,
            splitPlane,
            DigestAlgorithm.DEFAULT.getOrigin(),
            testClock.currentTimeMillis()
        );
    }

    /**
     * Test clock for deterministic time control.
     */
    private static class TestClock implements Clock {
        private long millis;

        public void setMillis(long millis) {
            this.millis = millis;
        }

        @Override
        public long currentTimeMillis() {
            return millis;
        }

        @Override
        public long nanoTime() {
            return millis * 1_000_000L;
        }
    }
}
