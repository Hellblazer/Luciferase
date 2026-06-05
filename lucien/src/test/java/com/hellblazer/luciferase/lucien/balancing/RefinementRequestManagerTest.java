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
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for RefinementRequestManager with injected Clock for deterministic RTT assertions.
 * Bead: Luciferase-7wzml.106
 */
class RefinementRequestManagerTest {

    private RefinementRequestManager manager;
    private TestClock clock;

    @BeforeEach
    void setUp() {
        clock = new TestClock(1000L);
        manager = new RefinementRequestManager();
        manager.setClock(clock);
    }

    @Test
    void buildRequestStampsClockTime() {
        clock.setTime(5000L);
        var request = manager.<MortonKey>buildRequest(1, 2, List.of(), 3);
        assertEquals(5000L, request.timestamp(), "buildRequest must stamp clock.currentTimeMillis()");
    }

    @Test
    void trackRequestThenResponseComputesDeterministicRtt() {
        // Build and track a request at t=1000
        clock.setTime(1000L);
        var request = manager.<MortonKey>buildRequest(0, 1, List.of(), 0);
        manager.trackRequest(request, clock.currentTimeMillis());

        // Advance clock by 250 ms, then record the response
        clock.setTime(1250L);
        var response = new RefinementResponse<MortonKey, LongEntityID, String>(0, 1, 0L, 1, List.of(), false, 1250L);
        manager.trackResponse(response);

        assertEquals(1, manager.getTotalResponses());
        assertEquals(250L, manager.getAverageRoundTripTime(),
                     "RTT must equal clock delta (250 ms), not wall-clock elapsed");
    }

    @Test
    void responseTimestampUsesClockNotSystemTime() {
        // Two clock reads inside trackResponse both use the injected clock
        clock.setTime(2000L);
        var request = manager.<MortonKey>buildRequest(3, 7, List.of(), 1);
        manager.trackRequest(request, clock.currentTimeMillis());

        clock.setTime(2100L);
        var response = new RefinementResponse<MortonKey, LongEntityID, String>(3, 0, 0L, 7, List.of(), false, 2100L);
        manager.trackResponse(response);

        assertEquals(100L, manager.getAverageRoundTripTime());
    }

    @Test
    void clearResetsMetrics() {
        clock.setTime(1000L);
        var request = manager.<MortonKey>buildRequest(0, 1, List.of(), 0);
        manager.trackRequest(request, clock.currentTimeMillis());
        clock.setTime(1500L);
        var response = new RefinementResponse<MortonKey, LongEntityID, String>(0, 1, 0L, 1, List.of(), false, 1500L);
        manager.trackResponse(response);

        manager.clear();

        assertEquals(0L, manager.getTotalRequests());
        assertEquals(0L, manager.getTotalResponses());
        assertEquals(0L, manager.getAverageRoundTripTime());
    }
}
