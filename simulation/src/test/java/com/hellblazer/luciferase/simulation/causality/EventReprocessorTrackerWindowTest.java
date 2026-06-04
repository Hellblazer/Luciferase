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

import com.hellblazer.luciferase.simulation.entity.StringEntityID;
import com.hellblazer.luciferase.simulation.events.EntityUpdateEvent;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for EventReprocessor:
 * <ul>
 *   <li>Luciferase-0frcy.87 — eventTracker keyed on (entityId, lamportClock) so multiple
 *       in-flight clocks per entity are tracked independently and polling one does not
 *       evict the others.</li>
 *   <li>Luciferase-0frcy.88 — withinMaxWindow is honored: events past the max lookahead are
 *       force-processed, events in the holding window are released only when causally ready.</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
class EventReprocessorTrackerWindowTest {

    private EntityUpdateEvent event(String entityId, long clock, long timestamp) {
        return new EntityUpdateEvent(new StringEntityID(entityId), new Point3f(0, 0, 0), new Point3f(1, 0, 0),
                                     timestamp, clock);
    }

    // ---- .87: multi-clock tracking per entity ----

    @Test
    void trackerRetainsAllPendingClocksForSameEntity() {
        var rp = new EventReprocessor(new EventReprocessor.Configuration(0, 1000));
        rp.queueEvent(event("e1", 1L, 0L));
        rp.queueEvent(event("e1", 2L, 0L));
        rp.queueEvent(event("e1", 3L, 0L));

        // Pre-fix: entityId-only key meant only clock 3 (last put) was tracked.
        assertTrue(rp.isPending("e1", 1L), "clock 1 tracked");
        assertTrue(rp.isPending("e1", 2L), "clock 2 tracked");
        assertTrue(rp.isPending("e1", 3L), "clock 3 tracked");
    }

    @Test
    void pollingOneClockDoesNotEvictOtherPendingClocks() {
        // min=0, max large so causal-readiness governs; clocks are contiguous so all are ready.
        var rp = new EventReprocessor(new EventReprocessor.Configuration(0, 100_000));
        rp.queueEvent(event("e1", 1L, 0L));
        rp.queueEvent(event("e1", 2L, 0L));
        rp.queueEvent(event("e1", 3L, 0L));

        var seen = new ArrayList<Long>();
        // currentTime past min window (0) but within max window: contiguous clocks are released.
        int n = rp.processReady(10L, ev -> seen.add(ev.lamportClock()));

        assertEquals(List.of(1L, 2L, 3L), seen, "all three clocks processed in order");
        assertEquals(3, n);
        assertFalse(rp.isPending("e1", 1L));
        assertFalse(rp.isPending("e1", 2L));
        assertFalse(rp.isPending("e1", 3L));
    }

    // ---- .88: holding window vs force-process ----

    @Test
    void eventsInWindowProcessedInClockOrderWithoutForceFlag() {
        // min=5, max=100. Events past the min window are processed in clock order; within the
        // [min, max) window nothing is flagged as force-processed (withinMaxWindow is true).
        var rp = new EventReprocessor(new EventReprocessor.Configuration(5, 100));
        // clock 0 then clock 2 — gap at clock 1. Both arrive at t=0.
        rp.queueEvent(event("e1", 0L, 0L));
        rp.queueEvent(event("e1", 2L, 0L));

        var seen = new ArrayList<Long>();
        // currentTime=10: age=10ms, within [min=5, max=100). Both released in clock order;
        // neither counts as force-processed because the max window was not exceeded.
        int n = rp.processReady(10L, ev -> seen.add(ev.lamportClock()));

        assertEquals(List.of(0L, 2L), seen, "events released in clock order within the window");
        assertEquals(2, n);
        assertEquals(0L, rp.getTotalForceProcessed(), "nothing force-processed inside the window");
    }

    @Test
    void eventPastMaxWindowIsForceProcessed() {
        var rp = new EventReprocessor(new EventReprocessor.Configuration(5, 100));
        rp.queueEvent(event("e1", 0L, 0L));
        rp.queueEvent(event("e1", 2L, 0L)); // gap at 1

        var seen = new ArrayList<Long>();
        // currentTime=500: age=500ms >= max=100. Both are force-eligible; the gapped clock 2
        // is force-processed instead of being held forever.
        int n = rp.processReady(500L, ev -> seen.add(ev.lamportClock()));

        assertEquals(List.of(0L, 2L), seen, "both processed once max window exceeded");
        assertEquals(2, n);
        assertEquals(2L, rp.getTotalForceProcessed(), "both events counted as force-processed past max window");
    }
}
