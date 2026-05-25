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
import com.hellblazer.luciferase.lucien.forest.ghost.GhostElement;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Inc3-C1 (RDR-007 Phase 0): pure-domain types that replace the balancing proto messages at the
 * lucien-core boundary. Validates construction, the reused {@link TwoOneBalanceChecker.BalanceViolation}
 * guard, the {@link RefinementResponse#empty()} fallback (replaces proto getDefaultInstance), defensive
 * immutability of the collection-bearing records, and the {@link BalanceExchangeException} flag round-trip.
 *
 * @author hal.hildebrand
 */
class Inc3DomainTypesTest {

    @Test
    void refinementRequestHoldsDomainKeys() {
        var k1 = new MortonKey(100L, (byte) 3);
        var k2 = new MortonKey(200L, (byte) 4);
        var req = new RefinementRequest<>(0, 0L, 1, 4, List.of(k1, k2), 12345L);

        assertEquals(0, req.requesterRank());
        assertEquals(0L, req.requesterTreeId());
        assertEquals(1, req.roundNumber());
        assertEquals(4, req.treeLevel());
        assertEquals(List.of(k1, k2), req.boundaryKeys());
        assertEquals(12345L, req.timestamp());
    }

    @Test
    void refinementResponseHoldsDomainGhostElements() {
        var ghost = new GhostElement<MortonKey, LongEntityID, String>(
            new MortonKey(100L, (byte) 3), new LongEntityID(1L), "content",
            new Point3f(1, 2, 3), 1, 0L);
        var resp = new RefinementResponse<>(0, 1, 0L, 1, List.of(ghost), true, 999L);

        assertEquals(1, resp.ghostElements().size());
        assertSame(ghost, resp.ghostElements().get(0));
        assertTrue(resp.needsFurtherRefinement());
        assertEquals(1, resp.responderRank());
    }

    @Test
    void refinementResponseEmptyFactoryMatchesProtoDefaults() {
        RefinementResponse<MortonKey, LongEntityID, String> empty = RefinementResponse.empty();

        // Mirrors proto RefinementResponse.getDefaultInstance() used as the coordinator fallback.
        assertFalse(empty.needsFurtherRefinement());
        assertTrue(empty.ghostElements().isEmpty());
        assertEquals(0, empty.requesterRank());
        assertEquals(0, empty.responderRank());
        assertEquals(0L, empty.responderTreeId());
        assertEquals(0, empty.roundNumber());
        assertEquals(0L, empty.timestamp());
    }

    @Test
    void violationBatchHoldsDomainViolations() {
        var local = new MortonKey(10L, (byte) 5);
        var ghost = new MortonKey(20L, (byte) 2);
        var violation = new TwoOneBalanceChecker.BalanceViolation<>(local, ghost, 5, 2, 3, 7);
        var batch = new ViolationBatch<>(0, 1, 2, List.of(violation), 555L);

        assertEquals(1, batch.violations().size());
        assertEquals(violation, batch.violations().get(0));
        assertEquals(7, batch.violations().get(0).sourceRank());
        assertEquals(2, batch.roundNumber());
    }

    @Test
    void balanceViolationCtorGuardRejectsNonViolation() {
        var k = new MortonKey(1L, (byte) 1);
        assertThrows(IllegalArgumentException.class,
            () -> new TwoOneBalanceChecker.BalanceViolation<>(k, k, 1, 1, 1, 0),
            "levelDifference <= 1 must be rejected by the reused domain violation record");
    }

    @Test
    void balanceExchangeExceptionFlagsRoundTrip() {
        var transientEx = new BalanceExchangeException("unavailable", true, false);
        assertTrue(transientEx.isTransient());
        assertFalse(transientEx.isTimeout());

        var timeoutEx = new BalanceExchangeException("deadline", false, true);
        assertFalse(timeoutEx.isTransient());
        assertTrue(timeoutEx.isTimeout());

        var cause = new RuntimeException("boom");
        var withCause = new BalanceExchangeException("wrapped", cause, true, false);
        assertSame(cause, withCause.getCause());
        assertTrue(withCause.isTransient());
        assertFalse(withCause.isTimeout());
    }

    @Test
    void collectionBearingRecordsDefensivelyCopy() {
        var k = new MortonKey(1L, (byte) 1);
        var mutableKeys = new ArrayList<MortonKey>();
        mutableKeys.add(k);
        var req = new RefinementRequest<>(0, 0L, 0, 0, mutableKeys, 0L);
        mutableKeys.clear();
        assertEquals(1, req.boundaryKeys().size(), "RefinementRequest must defensively copy boundaryKeys");

        var ghost = new GhostElement<MortonKey, LongEntityID, String>(
            new MortonKey(2L, (byte) 1), new LongEntityID(1L), "c", new Point3f(0, 0, 0), 0, 0L);
        var mutableGhosts = new ArrayList<GhostElement<MortonKey, LongEntityID, String>>();
        mutableGhosts.add(ghost);
        var resp = new RefinementResponse<>(0, 0, 0L, 0, mutableGhosts, false, 0L);
        mutableGhosts.clear();
        assertEquals(1, resp.ghostElements().size(), "RefinementResponse must defensively copy ghostElements");
    }
}
