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

package com.hellblazer.luciferase.simulation.causality;

import com.hellblazer.luciferase.simulation.delos.mock.MockFirefliesView;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit Tests for FirefliesViewMonitor (Phase 7C.4)
 *
 * Tests view stability detection, member tracking, metrics,
 * and stability transitions with configurable thresholds.
 *
 * @author hal.hildebrand
 */
class FirefliesViewMonitorTest {

    /**
     * Test: Monitor initializes with empty view.
     */
    @Test
    void testInitialization() {
        var view = new MockFirefliesView<String>();
        var monitor = new FirefliesViewMonitor(view);

        assertEquals(0, monitor.getMemberCount(), "Should start with no members");
        assertTrue(monitor.isViewStable(), "Should be stable initially (no changes)");
        assertEquals(0L, monitor.getTotalViewChanges(), "Should have no changes");
        assertEquals(0L, monitor.getTotalMembersJoined(), "Should have no joins");
        assertEquals(0L, monitor.getTotalMembersLeft(), "Should have no leaves");
    }

    /**
     * Test: View becomes stable after threshold ticks.
     */
    @Test
    void testViewStability() {
        var view = new MockFirefliesView<String>();
        var monitor = new FirefliesViewMonitor(view, 10);

        // Add member
        view.addMember("bubble1");

        assertEquals(1, monitor.getMemberCount(), "Should have 1 member");
        assertFalse(monitor.isViewStable(), "Should not be stable immediately after change");
        assertEquals(1L, monitor.getTotalViewChanges(), "Should have 1 change");

        // Advance ticks to threshold
        for (int i = 1; i <= 10; i++) {
            monitor.onTick(i);
            if (i < 10) {
                assertFalse(monitor.isViewStable(), "Should not be stable before threshold");
            }
        }

        // At tick 10, should be stable
        assertTrue(monitor.isViewStable(), "Should be stable at threshold");
        assertEquals(1L, monitor.getTimesStable(), "Should have 1 stability event");
    }

    /**
     * Test: View change resets stability.
     */
    @Test
    void testViewChangeResetsStability() {
        var view = new MockFirefliesView<String>();
        var monitor = new FirefliesViewMonitor(view, 10);

        // Add member and advance to stable
        view.addMember("bubble1");
        for (int i = 1; i <= 10; i++) {
            monitor.onTick(i);
        }
        assertTrue(monitor.isViewStable(), "Should be stable");

        // Add another member
        monitor.onTick(11);
        view.addMember("bubble2");

        assertFalse(monitor.isViewStable(), "Should become unstable on change");
        assertEquals(2, monitor.getMemberCount(), "Should have 2 members");
        assertEquals(2L, monitor.getTotalViewChanges(), "Should have 2 changes");
    }

    /**
     * Test: Multiple members can join.
     */
    @Test
    void testMultipleMembersJoin() {
        var view = new MockFirefliesView<String>();
        var monitor = new FirefliesViewMonitor(view);

        view.addMember("bubble1");
        view.addMember("bubble2");
        view.addMember("bubble3");

        assertEquals(3, monitor.getMemberCount(), "Should have 3 members");
        assertEquals(3L, monitor.getTotalMembersJoined(), "Should have 3 joins");
        assertEquals(3L, monitor.getTotalViewChanges(), "Should have 3 changes");
    }

    /**
     * Test: Member leaves are tracked.
     */
    @Test
    void testMemberLeaves() {
        var view = new MockFirefliesView<String>();
        var monitor = new FirefliesViewMonitor(view);

        view.addMember("bubble1");
        view.addMember("bubble2");
        assertEquals(2, monitor.getMemberCount());

        view.removeMember("bubble1");

        assertEquals(1, monitor.getMemberCount(), "Should have 1 member");
        assertEquals(1L, monitor.getTotalMembersLeft(), "Should have 1 leave");
        assertEquals(3L, monitor.getTotalViewChanges(), "Should have 3 total changes");
    }

    /**
     * Test: Ticks since last change is accurate.
     */
    @Test
    void testTicksSinceLastChange() {
        var view = new MockFirefliesView<String>();
        var monitor = new FirefliesViewMonitor(view);

        view.addMember("bubble1");

        monitor.onTick(0);
        assertEquals(0, monitor.getTicksSinceLastChange(), "Should have 0 ticks");

        monitor.onTick(5);
        assertEquals(5, monitor.getTicksSinceLastChange(), "Should have 5 ticks");

        monitor.onTick(10);
        assertEquals(10, monitor.getTicksSinceLastChange(), "Should have 10 ticks");

        view.addMember("bubble2");

        monitor.onTick(11);
        assertEquals(1, monitor.getTicksSinceLastChange(), "Should have 1 tick since change");
    }

    /**
     * Test: Custom stability threshold is respected.
     */
    @Test
    void testCustomThreshold() {
        var view = new MockFirefliesView<String>();
        var monitor = new FirefliesViewMonitor(view, 5);  // 5 tick threshold

        view.addMember("bubble1");

        // Advance 4 ticks
        for (int i = 1; i <= 4; i++) {
            monitor.onTick(i);
            assertFalse(monitor.isViewStable(), "Not stable at tick " + i);
        }

        // At 5 ticks, should be stable
        monitor.onTick(5);
        assertTrue(monitor.isViewStable(), "Should be stable at threshold");
    }

    /**
     * Test: Configuration is accessible.
     */
    @Test
    void testConfiguration() {
        var view = new MockFirefliesView<String>();
        var monitor = new FirefliesViewMonitor(view, 15);

        var config = monitor.getConfiguration();
        assertEquals(15, config.stabilityThresholdTicks, "Should have 15 tick threshold");
    }

    /**
     * Test: Current members snapshot is immutable.
     */
    @Test
    void testCurrentMembersImmutable() {
        var view = new MockFirefliesView<String>();
        var monitor = new FirefliesViewMonitor(view);

        view.addMember("bubble1");
        var members = monitor.getCurrentMembers();

        assertThrows(UnsupportedOperationException.class,
                    () -> members.add("bubble2"),
                    "Should return unmodifiable set");
    }

    /**
     * Test: Health status requires stability and members.
     */
    @Test
    void testHealthStatus() {
        var view = new MockFirefliesView<String>();
        var monitor = new FirefliesViewMonitor(view, 10);

        // No members, unstable
        assertFalse(monitor.isHealthy(), "Should not be healthy with no members");

        // Add member, but not yet stable
        view.addMember("bubble1");
        assertFalse(monitor.isHealthy(), "Should not be healthy (unstable)");

        // Advance to stable
        for (int i = 1; i <= 10; i++) {
            monitor.onTick(i);
        }
        assertTrue(monitor.isHealthy(), "Should be healthy (stable + members)");

        // Remove member
        view.removeMember("bubble1");
        assertFalse(monitor.isHealthy(), "Should not be healthy (no members)");
    }

    /**
     * Test: Metrics accumulation.
     */
    @Test
    void testMetrics() {
        var view = new MockFirefliesView<String>();
        var monitor = new FirefliesViewMonitor(view, 10);

        view.addMember("b1");
        view.addMember("b2");
        for (int i = 1; i <= 10; i++) monitor.onTick(i);

        view.addMember("b3");
        view.removeMember("b1");
        for (int i = 11; i <= 20; i++) monitor.onTick(i);

        assertEquals(4L, monitor.getTotalViewChanges(), "Should have 4 changes");
        assertEquals(3L, monitor.getTotalMembersJoined(), "Should have 3 joins");
        assertEquals(1L, monitor.getTotalMembersLeft(), "Should have 1 leave");
        assertEquals(2L, monitor.getTimesStable(), "Should be stable twice");
    }

    /**
     * Test: Reset clears all state and metrics.
     */
    @Test
    void testReset() {
        var view = new MockFirefliesView<String>();
        var monitor = new FirefliesViewMonitor(view);

        view.addMember("bubble1");
        view.addMember("bubble2");

        assertEquals(2, monitor.getMemberCount());
        assertEquals(2L, monitor.getTotalViewChanges());

        monitor.reset();

        assertEquals(0, monitor.getMemberCount(), "Members should be cleared");
        assertEquals(0L, monitor.getTotalViewChanges(), "Changes should be reset");
        assertEquals(0L, monitor.getTotalMembersJoined(), "Joins should be reset");
        assertEquals(0L, monitor.getTotalMembersLeft(), "Leaves should be reset");
        assertTrue(monitor.isViewStable(), "Should be stable after reset");
    }

    /**
     * Test: Estimated convergence time scales with cluster size.
     */
    @Test
    void testEstimatedConvergenceTicks() {
        var view = new MockFirefliesView<String>();
        var monitor = new FirefliesViewMonitor(view);

        // No members
        assertEquals(0, monitor.estimatedConvergenceTicks(), "1 member (estimated 0)");

        // 4 members
        view.addMember("b1");
        view.addMember("b2");
        view.addMember("b3");
        view.addMember("b4");
        var convergence4 = monitor.estimatedConvergenceTicks();
        assertTrue(convergence4 > 0, "4 members should have positive convergence");

        // 8 members
        view.addMember("b5");
        view.addMember("b6");
        view.addMember("b7");
        view.addMember("b8");
        var convergence8 = monitor.estimatedConvergenceTicks();
        assertTrue(convergence8 >= convergence4, "8 members should have >= convergence than 4");
    }

    /**
     * Test: toString includes state information.
     */
    @Test
    void testToString() {
        var view = new MockFirefliesView<String>();
        var monitor = new FirefliesViewMonitor(view);
        view.addMember("bubble1");

        var str = monitor.toString();
        assertNotNull(str);
        assertTrue(str.contains("FirefliesViewMonitor"), "Should contain class name");
        assertTrue(str.contains("members="), "Should show member count");
        assertTrue(str.contains("stable="), "Should show stability");
    }

    /**
     * Test: Initial state is stable (no changes yet).
     */
    @Test
    void testInitiallyStable() {
        var view = new MockFirefliesView<String>();
        var monitor = new FirefliesViewMonitor(view, 10);

        assertTrue(monitor.isViewStable(), "Should be stable initially");
        assertEquals(0L, monitor.getTotalViewChanges(), "No changes yet");
        assertEquals(0L, monitor.getTicksSinceLastChange(), "0 ticks passed");
    }

    /**
     * Test: Stable state transitions tracked correctly.
     */
    @Test
    void testStableTransitions() {
        var view = new MockFirefliesView<String>();
        var monitor = new FirefliesViewMonitor(view, 5);

        assertEquals(0L, monitor.getTimesStable(), "Should start at 0");

        // Add member and stabilize
        view.addMember("b1");
        for (int i = 1; i <= 5; i++) monitor.onTick(i);
        assertEquals(1L, monitor.getTimesStable(), "Should transition to stable once");

        // Change view and restabilize
        monitor.onTick(6);
        view.addMember("b2");
        for (int i = 7; i <= 11; i++) monitor.onTick(i);
        assertEquals(2L, monitor.getTimesStable(), "Should transition twice");
    }

    /**
     * Test: Null view throws exception.
     */
    @Test
    void testNullViewThrows() {
        assertThrows(NullPointerException.class,
                    () -> new FirefliesViewMonitor(null),
                    "Should throw on null view");
    }

    /**
     * Test: View with many members.
     */
    @Test
    void testManyMembers() {
        var view = new MockFirefliesView<String>();
        var monitor = new FirefliesViewMonitor(view);

        for (int i = 0; i < 100; i++) {
            view.addMember("bubble" + i);
        }

        assertEquals(100, monitor.getMemberCount(), "Should have 100 members");
        assertEquals(100L, monitor.getTotalMembersJoined(), "Should have 100 joins");
    }

    /**
     * Test: Member tracking with many additions and removals.
     */
    @Test
    void testManyChanges() {
        var view = new MockFirefliesView<String>();
        var monitor = new FirefliesViewMonitor(view);

        // Add 5 members
        for (int i = 1; i <= 5; i++) {
            view.addMember("b" + i);
        }

        // Remove 3 members
        for (int i = 1; i <= 3; i++) {
            view.removeMember("b" + i);
        }

        assertEquals(2, monitor.getMemberCount(), "Should have 2 members left");
        assertEquals(5L, monitor.getTotalMembersJoined(), "Should have 5 joins");
        assertEquals(3L, monitor.getTotalMembersLeft(), "Should have 3 leaves");
        assertEquals(8L, monitor.getTotalViewChanges(), "Should have 8 changes");
    }
}
