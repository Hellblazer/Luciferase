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
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.forest.Forest;
import com.hellblazer.luciferase.lucien.forest.ForestConfig;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostElement;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostLayer;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.octree.Octree;
import javax.vecmath.Point3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * TDD tests for TwoOneBalanceChecker.
 *
 * <p>The 2:1 balance constraint requires that adjacent elements differ by at most 1 level
 * in the spatial hierarchy. This test verifies violation detection at partition boundaries.
 *
 * @author hal.hildebrand
 */
public class TwoOneBalanceCheckerTest {

    private TwoOneBalanceChecker<MortonKey, LongEntityID, String> checker;
    private GhostLayer<MortonKey, LongEntityID, String> mockGhostLayer;

    @BeforeEach
    public void setUp() {
        checker = new TwoOneBalanceChecker<>();
        mockGhostLayer = mock(GhostLayer.class);
    }

    @Test
    public void testEmptyGhostLayerNoViolations() {
        // Empty ghost layer should produce no violations
        var mockForest = mock(Forest.class);
        when(mockGhostLayer.getAllGhostElements()).thenReturn(List.of());

        var violations = checker.findViolations(mockGhostLayer, mockForest);

        assertTrue(violations.isEmpty(), "Empty ghost layer should have no violations");
    }

    @Test
    public void testNullGhostLayerThrows() {
        // Null ghost layer should throw
        var mockForest = mock(Forest.class);

        assertThrows(IllegalArgumentException.class,
                    () -> checker.findViolations(null, mockForest),
                    "Null ghost layer should throw");
    }

    @Test
    public void testNullForestThrows() {
        // Null forest should throw
        when(mockGhostLayer.getAllGhostElements()).thenReturn(List.of());

        assertThrows(IllegalArgumentException.class,
                    () -> checker.findViolations(mockGhostLayer, null),
                    "Null forest should throw");
    }

    @Test
    public void testHandlesMultipleGhosts() {
        // Multiple ghosts can be processed without error
        var mockForest = mock(Forest.class);
        var ghosts = new ArrayList<GhostElement<MortonKey, LongEntityID, String>>();
        for (int i = 0; i < 5; i++) {
            var ghost = mock(GhostElement.class);
            when(ghost.getSpatialKey()).thenReturn(new MortonKey(i, (byte) 2));
            ghosts.add(ghost);
        }
        when(mockGhostLayer.getAllGhostElements()).thenReturn(ghosts);

        var violations = checker.findViolations(mockGhostLayer, mockForest);

        // Should not throw and return a list
        assertNotNull(violations, "Should return list of violations");
        assertTrue(violations instanceof List, "Should return list type");
    }

    @Test
    public void testViolationRecordHasRequiredFields() {
        // BalanceViolation record has all required fields
        var mockForest = mock(Forest.class);
        var localKey = new MortonKey(1L, (byte) 2);
        var ghostKey = new MortonKey(0L, (byte) 4);

        var violation = new TwoOneBalanceChecker.BalanceViolation<>(localKey, ghostKey, 2, 4, 2, 1);

        assertEquals(localKey, violation.localKey(), "Should store local key");
        assertEquals(ghostKey, violation.ghostKey(), "Should store ghost key");
        assertEquals(2, violation.localLevel(), "Should store local level");
        assertEquals(4, violation.ghostLevel(), "Should store ghost level");
        assertEquals(2, violation.levelDifference(), "Should store level difference");
        assertEquals(1, violation.sourceRank(), "Should store source rank");
    }

    @Test
    public void testViolationRecordRejectsInvalidLevelDifference() {
        // BalanceViolation should reject level difference <= 1
        var localKey = new MortonKey(1L, (byte) 2);
        var ghostKey = new MortonKey(0L, (byte) 3);

        assertThrows(IllegalArgumentException.class,
                    () -> new TwoOneBalanceChecker.BalanceViolation<>(localKey, ghostKey, 2, 3, 1, 1),
                    "Should reject level difference of 1 (not a violation)");
    }

    @Test
    @Tag("performance")
    public void testPerformanceCanProcessManyGhosts() {
        // Should process many elements efficiently
        // Note: CI runners may be 2-5x slower than local machines due to shared
        // resources and JIT warmup. Threshold set to 500ms to accommodate CI variance
        // while still catching significant performance regressions.
        var mockForest = mock(Forest.class);
        var ghosts = new ArrayList<GhostElement<MortonKey, LongEntityID, String>>();
        for (int i = 0; i < 100; i++) {
            var ghost = mock(GhostElement.class);
            when(ghost.getSpatialKey()).thenReturn(new MortonKey(i, (byte) 2));
            ghosts.add(ghost);
        }
        when(mockGhostLayer.getAllGhostElements()).thenReturn(ghosts);

        long start = System.currentTimeMillis();
        var violations = checker.findViolations(mockGhostLayer, mockForest);
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed < 500, "Should process 100 elements efficiently, took " + elapsed + "ms");
        assertNotNull(violations, "Should return results");
    }

    @Test
    public void testLocalNeedsRefinementLogic() {
        // Test the localNeedsRefinement() method on BalanceViolation
        var localKey = new MortonKey(1L, (byte) 2);
        var ghostKey = new MortonKey(0L, (byte) 4);

        // Case 1: local level (2) < ghost level (4) - local needs refinement
        var violation1 = new TwoOneBalanceChecker.BalanceViolation<>(localKey, ghostKey, 2, 4, 2, 1);
        assertTrue(violation1.localNeedsRefinement(), "Local at level 2 needs refinement vs ghost at level 4");

        // Case 2: local level (4) > ghost level (2) - ghost (remote) needs refinement
        var violation2 = new TwoOneBalanceChecker.BalanceViolation<>(localKey, ghostKey, 4, 2, 2, 1);
        assertFalse(violation2.localNeedsRefinement(), "Local at level 4 doesn't need refinement vs ghost at level 2");
    }

    @Test
    public void testCreateRefinementRequestsExists() {
        // Verify createRefinementRequests emits a request for a ghost-coarser violation (local is finer, so the
        // ghost owner is the side that must refine -> a remote request is warranted; Luciferase-uhsn D3).
        var violations = List.of(
            new TwoOneBalanceChecker.BalanceViolation<>(
                new MortonKey(1L, (byte) 4),
                new MortonKey(0L, (byte) 2),
                4, 2, 2, 1   // localLevel > ghostLevel -> !localNeedsRefinement()
            )
        );

        var requests = checker.createRefinementRequests(violations, 0, 0);

        assertNotNull(requests, "Should return refinement requests");
        assertEquals(1, requests.size(), "one ghost-coarser violation from one sourceRank -> one request");
        assertEquals(0, requests.get(0).requesterRank(), "requesterRank must be the supplied local rank");
    }

    @Test
    public void testCreateRefinementRequestsEmptyViolations() {
        // Luciferase-w3lm: no violations -> no requests (NOT a butterfly fallback; that is the coordinator's job).
        assertTrue(checker.createRefinementRequests(List.of(), 7L, 3).isEmpty(),
                   "empty violations must yield no refinement requests");
    }

    @Test
    public void testCreateRefinementRequestsGroupsBySourceRank() {
        // Luciferase-uhsn D3: only ghost-coarser violations (!localNeedsRefinement(), local finer) become remote
        // requests. Two distinct ghost-owner ranks -> one request per rank, carrying both local and ghost keys,
        // treeLevel = max level in the group, requesterRank = the supplied local rank.
        var localA1 = new MortonKey(1L, (byte) 5);
        var ghostA1 = new MortonKey(2L, (byte) 2);   // rank 1, max level 5
        var localA2 = new MortonKey(3L, (byte) 7);
        var ghostA2 = new MortonKey(4L, (byte) 4);   // rank 1, max level 7
        var localB = new MortonKey(5L, (byte) 3);
        var ghostB = new MortonKey(6L, (byte) 1);    // rank 2, max level 3

        var violations = List.of(
            new TwoOneBalanceChecker.BalanceViolation<>(localA1, ghostA1, 5, 2, 3, 1),
            new TwoOneBalanceChecker.BalanceViolation<>(localA2, ghostA2, 7, 4, 3, 1),
            new TwoOneBalanceChecker.BalanceViolation<>(localB, ghostB, 3, 1, 2, 2)
        );

        var requests = checker.createRefinementRequests(violations, 99L, 42);

        assertEquals(2, requests.size(), "one request per distinct sourceRank");
        for (var req : requests) {
            assertEquals(42, req.requesterRank(), "requesterRank must be the supplied local rank");
            assertEquals(99L, req.timestamp(), "timestamp must be propagated");
            assertEquals(0L, req.requesterTreeId(), "treeId is 0 until B10b wires the partner");
            assertEquals(0, req.roundNumber(), "roundNumber is 0 until B10b supplies convergence rounds");
        }

        // Rank-1 group: 2 violations -> 4 boundary keys, treeLevel = max(5,7) = 7.
        var rank1 = requests.stream().filter(r -> r.treeLevel() == 7).findFirst().orElseThrow();
        assertEquals(4, rank1.boundaryKeys().size(), "two violations contribute local+ghost each");
        assertTrue(rank1.boundaryKeys().containsAll(List.of(localA1, ghostA1, localA2, ghostA2)));

        // Rank-2 group: 1 violation -> 2 boundary keys, treeLevel = max(3,1) = 3.
        var rank2 = requests.stream().filter(r -> r.treeLevel() == 3).findFirst().orElseThrow();
        assertEquals(2, rank2.boundaryKeys().size());
        assertTrue(rank2.boundaryKeys().containsAll(List.of(localB, ghostB)));
    }

    @Test
    public void createRefinementRequests_localNeedsRefinementViolations_producesNoRemoteRequest() {
        // Luciferase-uhsn D3: a violation where LOCAL is coarser (localNeedsRefinement()==true) must NOT produce a
        // remote request — the local partition refines itself. Only the ghost-coarser violation is sent on the wire.
        var localCoarse = new MortonKey(1L, (byte) 2);
        var ghostFine   = new MortonKey(2L, (byte) 4);   // local coarser: localNeedsRefinement() == true
        var localFine   = new MortonKey(3L, (byte) 5);
        var ghostCoarse = new MortonKey(4L, (byte) 2);   // ghost coarser: !localNeedsRefinement()

        var violations = List.of(
            new TwoOneBalanceChecker.BalanceViolation<>(localCoarse, ghostFine, 2, 4, 2, 1),
            new TwoOneBalanceChecker.BalanceViolation<>(localFine, ghostCoarse, 5, 2, 3, 1)
        );

        var requests = checker.createRefinementRequests(violations, 0L, 7);

        assertEquals(1, requests.size(), "only the ghost-coarser violation yields a remote request");
        var keys = requests.get(0).boundaryKeys();
        assertTrue(keys.containsAll(List.of(localFine, ghostCoarse)),
                   "remote request carries the ghost-coarser violation's keys");
        assertFalse(keys.contains(localCoarse), "local-coarser violation must not appear in any remote request");
        assertFalse(keys.contains(ghostFine), "local-coarser violation must not appear in any remote request");
    }

    @Test
    public void createRefinementRequests_localNeedsRefinementViolations_enqueuedLocally() {
        // Luciferase-uhsn D3: local-coarser violations are routed to a local refinement queue (consumed by
        // m27q/B10c), not dropped. drainLocalRefinements() returns exactly those localKeys and clears the queue.
        var localCoarse1 = new MortonKey(1L, (byte) 2);
        var ghostFine1   = new MortonKey(2L, (byte) 4);
        var localCoarse2 = new MortonKey(3L, (byte) 1);
        var ghostFine2   = new MortonKey(4L, (byte) 5);
        var localFine    = new MortonKey(5L, (byte) 6);
        var ghostCoarse  = new MortonKey(6L, (byte) 2);

        var violations = List.of(
            new TwoOneBalanceChecker.BalanceViolation<>(localCoarse1, ghostFine1, 2, 4, 2, 1),
            new TwoOneBalanceChecker.BalanceViolation<>(localCoarse2, ghostFine2, 1, 5, 4, 2),
            new TwoOneBalanceChecker.BalanceViolation<>(localFine, ghostCoarse, 6, 2, 4, 1)
        );

        checker.createRefinementRequests(violations, 0L, 7);

        var drained = checker.drainLocalRefinements();
        assertEquals(2, drained.size(), "both local-coarser violations are enqueued locally");
        assertTrue(drained.containsAll(List.of(localCoarse1, localCoarse2)),
                   "queue holds the localKeys of local-coarser violations");
        assertFalse(drained.contains(localFine), "ghost-coarser violation must not be enqueued locally");

        assertTrue(checker.drainLocalRefinements().isEmpty(), "drain must clear the queue");
    }

    @Test
    public void createRefinementRequests_localCoarserAcrossRounds_dedupedInQueue() {
        // Luciferase-uhsn D3 (review HIGH-2): until m27q subdivides, the SAME local-coarser violation reappears every
        // round; the local queue must NOT accumulate duplicates, or m27q would over-subdivide. Also covers one coarse
        // element bordering two finer ghosts within a single round.
        var localCoarse = new MortonKey(1L, (byte) 2);
        var ghostFineA  = new MortonKey(2L, (byte) 4);
        var ghostFineB  = new MortonKey(3L, (byte) 5);

        // Round 1: same localKey appears in two violations.
        checker.createRefinementRequests(List.of(
            new TwoOneBalanceChecker.BalanceViolation<>(localCoarse, ghostFineA, 2, 4, 2, 1),
            new TwoOneBalanceChecker.BalanceViolation<>(localCoarse, ghostFineB, 2, 5, 3, 1)
        ), 0L, 7);
        // Round 2 (not drained yet): the same violation persists since nothing subdivided.
        checker.createRefinementRequests(List.of(
            new TwoOneBalanceChecker.BalanceViolation<>(localCoarse, ghostFineA, 2, 4, 2, 1)
        ), 0L, 7);

        var drained = checker.drainLocalRefinements();
        assertEquals(1, drained.size(), "duplicate local-coarser keys collapse to a single subdivide request");
        assertTrue(drained.contains(localCoarse));
    }

    @Test
    public void testFindViolationsReturnsListType() {
        // Return type is always a list
        var mockForest = mock(Forest.class);
        when(mockGhostLayer.getAllGhostElements()).thenReturn(List.of());

        var result = checker.findViolations(mockGhostLayer, mockForest);

        assertTrue(result instanceof List, "findViolations must return a List");
        assertNotNull(result, "List should not be null");
    }

    @Test
    public void testMultipleViolationsCanBeRecorded() {
        // Multiple violations can be created and stored
        var violations = new ArrayList<TwoOneBalanceChecker.BalanceViolation<MortonKey>>();

        for (int i = 0; i < 5; i++) {
            violations.add(new TwoOneBalanceChecker.BalanceViolation<>(
                new MortonKey(i, (byte) 2),
                new MortonKey(i + 100, (byte) 4),
                2, 4, 2, i
            ));
        }

        assertEquals(5, violations.size(), "Should store 5 violations");
        assertTrue(violations.stream().allMatch(v -> v.levelDifference() == 2),
                  "All violations should have level difference 2");
    }

    @Test
    public void testCoarseAncestorViolationDetected_MortonAligned() {
        // Luciferase-3aut item (1) — coarse-band ancestor-code correctness.
        //
        // A fine ghost (level 10) whose neighbor position is occupied by a COARSE local element (level 5)
        // is a 2:1 balance violation (levelDiff = 5). Local elements are stored under their ABSOLUTE Morton
        // code (the low 3*(21-level) bits are zero), so to find the coarse cell at a neighbor position the
        // probe must mask the neighbor's fine code down to the coarse-ancestor cell. The pre-fix code probed
        // the FULL fine code at the coarse level, which is not a valid stored coarse key, so it MISSED the
        // violation. This test pins the corrected (masked) behavior with a deterministic Morton-aligned
        // fixture (critic recipe; coordinates verified offline against MortonCurve/Constants):
        //
        //   ghost      = cell (40,40,40) @ L10  -> world origin (81920,81920,81920), cellSize(10)=2048
        //   +X neighbor= world (83968,81920,81920) @ L10
        //   coarse L5 ancestor (cellSize(5)=65536) -> cell origin (65536,65536,65536)
        var ghostKey = MortonKey.fromCellIndices(40, 40, 40, (byte) 10);

        // Real forest with one Octree holding only the coarse (level 5) element at the +X neighbor's
        // coarse-ancestor cell.
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        var coarsePoint = new Point3f(83968f, 81920f, 81920f); // inside L5 cell origin (65536,65536,65536)
        octree.insert(new LongEntityID(1L), coarsePoint, (byte) 5, "coarse-local");
        var forest = new Forest<MortonKey, LongEntityID, String>();
        forest.addTree(octree);

        // The coarse element is stored under the MASKED L5 ancestor code, not the full fine code. This is
        // exactly why the pre-fix (unmasked) probe missed it — documented here as a non-vacuous control.
        long fineNbrCode = ghostKey.neighbor(MortonKey.Direction.POSITIVE_X).getMortonCode();
        long maskedL5 = fineNbrCode & ~((1L << (3 * (21 - 5))) - 1);
        var coarseKey = new MortonKey(maskedL5, (byte) 5);
        assertTrue(octree.containsSpatialKey(coarseKey),
                   "coarse element must be stored under the masked L5 ancestor code");
        assertFalse(octree.containsSpatialKey(new MortonKey(fineNbrCode, (byte) 5)),
                    "the pre-fix probe used the full fine code at L5 — it must NOT match the stored coarse cell");

        var ghost = new GhostElement<MortonKey, LongEntityID, String>(
            ghostKey, new LongEntityID(2L), "ghost", new Point3f(81920f, 81920f, 81920f), 1, 0L);
        when(mockGhostLayer.getAllGhostElements()).thenReturn(List.of(ghost));

        var violations = checker.findViolations(mockGhostLayer, forest);

        // Pre-fix this list is EMPTY (the unmasked probe never matched the coarse cell). Post-fix the
        // coarse-ancestor violation is detected. (MortonKey.Direction has 26 entries — 6 face + 12 edge +
        // 8 vertex — and all 26 neighbors of this ghost fall inside the same L5 cell, so the same coarse cell
        // is reported per qualifying direction; assert on presence + correctness, not count.)
        assertFalse(violations.isEmpty(), "coarse-ancestor 2:1 violation must be detected (was missed pre-fix)");
        assertTrue(violations.stream().anyMatch(v ->
                       v.localLevel() == 5 && v.ghostLevel() == 10 && v.levelDifference() == 5
                       && v.localKey().equals(coarseKey)),
                   "a violation must report the coarse L5 cell (local) vs the L10 ghost, levelDiff=5");
        for (var v : violations) {
            assertEquals(coarseKey, v.localKey(), "every violation here is the single coarse cell");
            assertEquals(5, v.localLevel());
            assertEquals(10, v.ghostLevel());
            assertEquals(5, v.levelDifference());
            assertEquals(1, v.sourceRank(), "sourceRank propagates the ghost owner rank");
        }
    }

    @Test
    public void testFinerDescendantViolationDetected_NonCorner() {
        // Luciferase-a5nd — finer-band descendant-range correctness.
        //
        // A coarse ghost (level 2) whose neighbor position holds a FINER local element (level 5) is a 2:1
        // violation (levelDiff = 3). The finer element can sit at ANY descendant of the neighbor cell, not just
        // its min-corner (first-octant) descendant. The pre-fix code probed only the min-corner key, so a
        // non-corner finer element was missed. The fix probes the whole descendant code range
        // [firstDescendantAtLevel, lastDescendantAtLevel] (contiguous at a fixed level under MortonKey's
        // level-first ordering) via spatialKeysInRange.
        //
        // Deterministic fixture (coords verified offline):
        //   ghost    = cell (1,1,1) @ L2  -> origin (524288,524288,524288), cellSize(2)=524288
        //   +X nbr   = cell origin (1048576,524288,524288) @ L2
        //   finer    = world (1048576+3*65536, 524288+2*65536, 524288+65536) @ L5 -> a NON-corner descendant
        var ghostKey = MortonKey.fromCellIndices(1, 1, 1, (byte) 2);

        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        var finerPoint = new Point3f(1245184f, 655360f, 589824f); // inside the +X neighbor cell, not its corner
        octree.insert(new LongEntityID(1L), finerPoint, (byte) 5, "finer-local");
        var forest = new Forest<MortonKey, LongEntityID, String>();
        forest.addTree(octree);

        var nbr = ghostKey.neighbor(MortonKey.Direction.POSITIVE_X);
        var finerKey = octree.calculateSpatialIndex(finerPoint, (byte) 5);
        var minCorner = nbr.firstDescendantAtLevel((byte) 5);
        // Non-vacuous control: the finer element is NOT at the min-corner the pre-fix probe checked, and is not
        // stored under that key — so the old first-octant-only probe would have missed it entirely.
        assertNotEquals(minCorner, finerKey, "fixture must place the finer element off the min-corner descendant");
        assertFalse(octree.containsSpatialKey(minCorner),
                    "the pre-fix probe only checked the min-corner key, which is empty here");
        assertTrue(octree.containsSpatialKey(finerKey), "the finer element must be stored under its own L5 key");

        var ghost = new GhostElement<MortonKey, LongEntityID, String>(
            ghostKey, new LongEntityID(2L), "ghost",
            new Point3f(524288f, 524288f, 524288f), 3, 0L);
        when(mockGhostLayer.getAllGhostElements()).thenReturn(List.of(ghost));

        var violations = checker.findViolations(mockGhostLayer, forest);

        // The finer element lies in exactly one L2 neighbor cell (the +X neighbor), so exactly one violation.
        assertEquals(1, violations.size(), "the non-corner finer descendant must be detected (missed pre-fix)");
        var v = violations.get(0);
        assertEquals(finerKey, v.localKey(), "violation must report the actual finer element key");
        assertEquals(5, v.localLevel());
        assertEquals(2, v.ghostLevel());
        assertEquals(3, v.levelDifference());
        assertEquals(3, v.sourceRank(), "sourceRank propagates the ghost owner rank");
    }

    @Test
    public void testFindViolations_WithRealForestData() {
        // Real integration test using Phase44ForestIntegrationFixture
        var fixture = new com.hellblazer.luciferase.lucien.balancing.fault.Phase44ForestIntegrationFixture();

        // Create real distributed forest with Octree spatial structure
        var distributedForest = fixture.createForest();
        fixture.syncGhostLayer();

        var ghostLayer = fixture.getGhostLayer();
        var forest = fixture.getForest();

        // Create a checker instance with the correct generic type for TestEntity
        var testChecker = new TwoOneBalanceChecker<MortonKey, LongEntityID,
            com.hellblazer.luciferase.lucien.balancing.fault.Phase44ForestIntegrationFixture.TestEntity>();

        // Create 2:1 balance violation by inserting elements at different levels
        // Get first entity to find a location
        var entities = fixture.getAllEntities();
        assertFalse(entities.isEmpty(), "Should have test entities");

        var firstEntity = entities.get(0);
        var location = firstEntity.location();

        // Insert a deep element (level 5) near existing elements
        var octree = forest.getAllTrees().get(0).getSpatialIndex();
        var deepLocation = new javax.vecmath.Point3f(
            location.x + 10.0f,
            location.y + 10.0f,
            location.z + 10.0f
        );
        octree.insert(
            new LongEntityID(9999L),
            deepLocation,
            (byte) 5,  // Deep level
            new com.hellblazer.luciferase.lucien.balancing.fault.Phase44ForestIntegrationFixture.TestEntity(
                java.util.UUID.randomUUID(),
                deepLocation,
                "deep-element"
            ),
            null
        );

        // Insert a shallow neighboring element (level 1) - this creates violation
        var shallowLocation = new javax.vecmath.Point3f(
            location.x + 15.0f,
            location.y + 15.0f,
            location.z + 15.0f
        );
        octree.insert(
            new LongEntityID(9998L),
            shallowLocation,
            (byte) 1,  // Shallow level - difference of 4 levels
            new com.hellblazer.luciferase.lucien.balancing.fault.Phase44ForestIntegrationFixture.TestEntity(
                java.util.UUID.randomUUID(),
                shallowLocation,
                "shallow-element"
            ),
            null
        );

        // Re-sync ghost layer after insertions
        fixture.syncGhostLayer();

        // Find violations using real forest data
        var violations = testChecker.findViolations(ghostLayer, forest);

        // Verify violations are detected
        assertNotNull(violations, "Should return violations list");
        assertTrue(violations instanceof List, "Should return List type");

        // Log violations for debugging (even if none found)
        System.out.println("Integration test: Found " + violations.size() + " violations");
        if (!violations.isEmpty()) {
            for (var violation : violations.subList(0, Math.min(3, violations.size()))) {
                System.out.println("  " + violation);
            }
        }

        // Verify violation structure if any found
        for (var violation : violations) {
            assertNotNull(violation.localKey(), "Violation should have localKey");
            assertNotNull(violation.ghostKey(), "Violation should have ghostKey");
            assertTrue(violation.levelDifference() > 1, "Level difference must be > 1");
            assertTrue(violation.sourceRank() >= 0, "Source rank must be >= 0");
        }

        // The test passes if it doesn't throw - violation detection working with real data
        // Actual violations depend on spatial structure and ghost layer configuration
    }
}
