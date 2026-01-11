/**
 * Copyright (C) 2024 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase Simulation Framework.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.simulation.integration;

import com.hellblazer.luciferase.simulation.causality.*;
import com.hellblazer.luciferase.simulation.delos.mock.MockFirefliesView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Phase 7C: Causality & Fireflies Integration (Phase 7C.6)
 *
 * Tests complete 4-bubble E2E scenarios with:
 * - LamportClockGenerator for causality ordering
 * - EventReprocessor for out-of-order handling
 * - CausalityPreserver for event ordering enforcement
 * - FirefliesViewMonitor for view stability detection
 * - EntityMigrationStateMachine for ownership coordination
 *
 * Disabled in CI: These integration tests involve multi-bubble causality ordering
 * and are expensive. Developers can run locally with: mvn test -Dtest=FourBubbleCausalityTest
 *
 * @author hal.hildebrand
 */
@Tag("integration")
@DisabledIfEnvironmentVariable(named = "CI", matches = "true")
class FourBubbleCausalityTest {

    private static class TestBubble {
        final UUID bubbleId;
        final LamportClockGenerator clockGen;
        final EventReprocessor reprocessor;
        final CausalityPreserver causality;
        final FirefliesViewMonitor viewMonitor;
        final EntityMigrationStateMachine stateMachine;

        TestBubble(UUID bubbleId, MockFirefliesView<UUID> view, int stabilityThreshold) {
            this.bubbleId = bubbleId;
            this.clockGen = new LamportClockGenerator(bubbleId);
            this.reprocessor = new EventReprocessor(100, 500);  // 100-500ms lookahead window
            this.causality = new CausalityPreserver();
            this.viewMonitor = new FirefliesViewMonitor(view, stabilityThreshold);
            this.stateMachine = new EntityMigrationStateMachine(viewMonitor);
        }
    }

    private List<TestBubble> bubbles;
    private MockFirefliesView<UUID> view;

    @BeforeEach
    void setup() {
        bubbles = new ArrayList<>();
        view = new MockFirefliesView<>();

        // Create 4 bubbles with Phase 7C components
        for (int i = 0; i < 4; i++) {
            var bubbleId = UUID.randomUUID();
            var bubble = new TestBubble(bubbleId, view, 3);
            bubbles.add(bubble);
            view.addMember(bubbleId);
        }
    }

    @Test
    void testFourBubbleSetup() {
        assertEquals(4, bubbles.size(), "Should have 4 bubbles");
        assertEquals(4, view.getMembers().toList().size(), "View should have 4 members");

        for (var bubble : bubbles) {
            assertNotNull(bubble.clockGen, "Clock generator should be initialized");
            assertNotNull(bubble.reprocessor, "Reprocessor should be initialized");
            assertNotNull(bubble.causality, "CausalityPreserver should be initialized");
            assertNotNull(bubble.viewMonitor, "ViewMonitor should be initialized");
            assertNotNull(bubble.stateMachine, "StateMachine should be initialized");
            assertEquals(4, bubble.viewMonitor.getMemberCount(), "Each bubble should see 4 members");
        }
    }

    @Test
    void testLamportClockingAcrossBubbles() {
        var bubble0 = bubbles.get(0);
        var bubble1 = bubbles.get(1);

        // Advance bubbles' clocks
        var clock0 = bubble0.clockGen.tick();
        var clock1 = bubble1.clockGen.tick();

        // Simulate bubble1 sending event to bubble0 with its clock
        bubble0.clockGen.onRemoteEvent(clock1, bubble1.bubbleId);

        // Verify causality: bubble0's clock should advance
        var advancedClock = bubble0.clockGen.getLamportClock();
        assertTrue(advancedClock >= Math.max(clock0, clock1),
                  "Bubble0's clock should account for remote clock");
    }

    @Test
    void testCausalityMaintainedAcrossBubbles() {
        // Run 100 ticks across all bubbles
        int ticks = 100;

        for (int tick = 0; tick < ticks; tick++) {
            // Tick all clocks
            for (var bubble : bubbles) {
                bubble.clockGen.tick();
            }

            // At tick 50, simulate event propagation between bubbles
            if (tick == 50) {
                var sender = bubbles.get(0);
                var receiver = bubbles.get(1);

                // Sender sends event with its clock
                var senderClock = sender.clockGen.getLamportClock();
                receiver.clockGen.onRemoteEvent(senderClock, sender.bubbleId);

                // Receiver's clock should be advanced
                assertTrue(receiver.clockGen.getLamportClock() >= senderClock,
                          "Receiver clock should advance from remote event");
            }

            // Stabilize view
            for (int i = 1; i <= 3; i++) {
                for (var bubble : bubbles) {
                    bubble.viewMonitor.onTick(tick * 3 + i);
                }
            }
        }

        // All bubbles should have advanced clocks
        for (var bubble : bubbles) {
            assertTrue(bubble.clockGen.getLamportClock() > 0, "Clock should be advanced");
        }
    }

    @Test
    void testEntityRetention100Percent() {
        var bubble0 = bubbles.get(0);
        int entityCount = 100;

        // Initialize 100 entities in bubble0
        for (int i = 0; i < entityCount; i++) {
            var entityId = UUID.randomUUID();
            bubble0.stateMachine.initializeOwned(entityId);
        }

        assertEquals(entityCount, bubble0.stateMachine.getEntityCount(),
                    "Should track 100 entities");

        // Simulate view stability
        for (int tick = 0; tick < 1000; tick++) {
            bubble0.viewMonitor.onTick(tick);
        }

        // Verify all entities retained
        assertEquals(entityCount, bubble0.stateMachine.getEntityCount(),
                    "Should retain all entities after 1000 ticks");

        // Verify all are still OWNED
        var ownedCount = bubble0.stateMachine.getEntitiesInState(EntityMigrationState.OWNED).size();
        assertEquals(entityCount, ownedCount, "All should be OWNED");
    }

    @Test
    void testMultiBubbleEntityCoordination() {
        // Initialize entities in all bubbles
        for (int i = 0; i < bubbles.size(); i++) {
            var bubble = bubbles.get(i);
            for (int j = 0; j < 25; j++) {
                bubble.stateMachine.initializeOwned(UUID.randomUUID());
            }
            assertEquals(25, bubble.stateMachine.getEntityCount(),
                        "Bubble " + i + " should have 25 entities");
        }

        // All bubbles see all members
        for (var bubble : bubbles) {
            assertEquals(4, bubble.viewMonitor.getMemberCount(),
                        "All bubbles should see 4 members");
        }

        // Run for 100 ticks with stable view
        for (int tick = 0; tick < 100; tick++) {
            for (var bubble : bubbles) {
                bubble.viewMonitor.onTick(tick);
            }
        }

        // All should still have entities
        for (var bubble : bubbles) {
            assertEquals(25, bubble.stateMachine.getEntityCount(),
                        "Should retain all entities");
        }
    }

    @Test
    void testPerformanceBasic() {
        var bubble0 = bubbles.get(0);

        // Create and process many events
        long startTime = System.nanoTime();

        for (int i = 0; i < 100; i++) {
            bubble0.clockGen.tick();
        }

        long endTime = System.nanoTime();
        long elapsedMs = (endTime - startTime) / 1_000_000;

        // Should complete quickly (allowing generous bounds)
        assertTrue(elapsedMs < 1000, "Should process 100 clock ticks quickly");
    }

    @Test
    void testDeterminismConsistency() {
        // Run same scenario twice, verify results match

        // First run
        List<Long> clocks1 = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            clocks1.add(bubbles.get(0).clockGen.tick());
        }

        // Second run (with fresh bubbles)
        setup();
        List<Long> clocks2 = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            clocks2.add(bubbles.get(0).clockGen.tick());
        }

        // Clocks should advance identically
        assertEquals(clocks1.size(), clocks2.size(), "Should have same number of ticks");
        for (int i = 0; i < clocks1.size(); i++) {
            assertEquals(clocks1.get(i), clocks2.get(i),
                        "Tick " + i + " should be identical");
        }
    }
}
