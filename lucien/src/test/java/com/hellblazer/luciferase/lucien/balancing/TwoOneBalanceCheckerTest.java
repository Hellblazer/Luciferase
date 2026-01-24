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
import com.hellblazer.luciferase.lucien.forest.ForestConfig;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostBoundaryDetector;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostElement;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostLayer;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
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
    private Forest<MortonKey, LongEntityID, String> mockForest;
    private GhostLayer<MortonKey, LongEntityID, String> mockGhostLayer;

    @BeforeEach
    public void setUp() {
        checker = new TwoOneBalanceChecker<>();
        mockForest = new Forest<>(ForestConfig.defaultConfig());
        mockGhostLayer = mock(GhostLayer.class);
    }

    @Test
    public void testEmptyGhostLayerNoViolations() {
        // Empty ghost layer should produce no violations
        when(mockGhostLayer.getAllGhostElements()).thenReturn(List.of());

        var violations = checker.findViolations(mockGhostLayer, mockForest);

        assertTrue(violations.isEmpty(), "Empty ghost layer should have no violations");
    }

    @Test
    public void testNullGhostLayerThrows() {
        // Null ghost layer should throw
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
    public void testIdentifiesLevelDifference2AsViolation() {
        // Create ghost at level 3 and local neighbor at level 1 (difference = 2)
        var ghostKey = new MortonKey(0L, (byte) 3);
        var ghostElement = createGhostElement(ghostKey, 1);
        when(mockGhostLayer.getAllGhostElements()).thenReturn(List.of(ghostElement));

        // Mock forest to contain a neighbor at level 1
        var localKey = new MortonKey(1L, (byte) 1);
        when(mockForest.containsKey(localKey)).thenReturn(true);

        var violations = checker.findViolations(mockGhostLayer, mockForest);

        // Should identify as violation since level difference is 2
        assertFalse(violations.isEmpty(), "Level difference of 2 should be violation");
    }

    @Test
    public void testIdentifiesLevelDifference3AsViolation() {
        // Level difference of 3 is definitely a violation
        var ghostKey = new MortonKey(0L, (byte) 4);
        var ghostElement = createGhostElement(ghostKey, 1);
        when(mockGhostLayer.getAllGhostElements()).thenReturn(List.of(ghostElement));

        // Local neighbor at level 1
        var localKey = new MortonKey(1L, (byte) 1);
        when(mockForest.containsKey(localKey)).thenReturn(true);

        var violations = checker.findViolations(mockGhostLayer, mockForest);

        assertFalse(violations.isEmpty(), "Level difference of 3 should be violation");
        assertTrue(violations.stream().anyMatch(v -> v.levelDifference() == 3),
                  "Should record level difference = 3");
    }

    @Test
    public void testLevelDifference1IsNotViolation() {
        // Level difference of exactly 1 should NOT be a violation
        var ghostKey = new MortonKey(0L, (byte) 3);
        var ghostElement = createGhostElement(ghostKey, 1);
        when(mockGhostLayer.getAllGhostElements()).thenReturn(List.of(ghostElement));

        // Local neighbor at level 2 (difference = 1)
        var localKey = new MortonKey(1L, (byte) 2);
        when(mockForest.containsKey(localKey)).thenReturn(true);

        var violations = checker.findViolations(mockGhostLayer, mockForest);

        // Should NOT record this as violation (2:1 invariant allows difference of 1)
        assertTrue(violations.stream().noneMatch(v -> v.ghostKey().equals(ghostKey)),
                  "Level difference of 1 should not be violation");
    }

    @Test
    public void testSameLevelIsNotViolation() {
        // Same level (difference = 0) should not be violation
        var ghostKey = new MortonKey(0L, (byte) 3);
        var ghostElement = createGhostElement(ghostKey, 1);
        when(mockGhostLayer.getAllGhostElements()).thenReturn(List.of(ghostElement));

        // Local neighbor at same level
        var localKey = new MortonKey(1L, (byte) 3);
        when(mockForest.containsKey(localKey)).thenReturn(true);

        var violations = checker.findViolations(mockGhostLayer, mockForest);

        assertTrue(violations.isEmpty(), "Same level should not be violation");
    }

    @Test
    public void testViolationRecordsSourceRank() {
        // Verify that violations record the source rank of ghost
        var ghostKey = new MortonKey(0L, (byte) 4);
        var ghostElement = createGhostElement(ghostKey, 2);  // ownerRank = 2
        when(mockGhostLayer.getAllGhostElements()).thenReturn(List.of(ghostElement));

        var localKey = new MortonKey(1L, (byte) 1);
        when(mockForest.containsKey(localKey)).thenReturn(true);

        var violations = checker.findViolations(mockGhostLayer, mockForest);

        assertFalse(violations.isEmpty());
        assertTrue(violations.stream().anyMatch(v -> v.sourceRank() == 2),
                  "Violation should record source rank = 2");
    }

    @Test
    public void testMultipleGhostsMultipleViolations() {
        // Multiple ghosts can contribute multiple violations
        var ghost1 = createGhostElement(new MortonKey(0L, (byte) 4), 1);
        var ghost2 = createGhostElement(new MortonKey(1000L, (byte) 5), 2);
        when(mockGhostLayer.getAllGhostElements()).thenReturn(List.of(ghost1, ghost2));

        // Mock forest neighbors
        when(mockForest.containsKey(any())).thenReturn(true);

        var violations = checker.findViolations(mockGhostLayer, mockForest);

        // Should have violations from both ghosts
        assertTrue(violations.size() >= 2, "Multiple ghosts should produce multiple violations");
    }

    @Test
    public void testViolationRecordsLevelDifference() {
        // BalanceViolation should record the exact level difference
        var ghostKey = new MortonKey(0L, (byte) 5);
        var ghostElement = createGhostElement(ghostKey, 1);
        when(mockGhostLayer.getAllGhostElements()).thenReturn(List.of(ghostElement));

        var localKey = new MortonKey(1L, (byte) 2);
        when(mockForest.containsKey(localKey)).thenReturn(true);

        var violations = checker.findViolations(mockGhostLayer, mockForest);

        assertTrue(violations.stream().anyMatch(v -> v.levelDifference() == 3),
                  "Should record levelDifference = 3 (5 - 2)");
    }

    @Test
    public void testLocalNeedsRefinementDecision() {
        // BalanceViolation.localNeedsRefinement() indicates which side needs work
        var ghostKey = new MortonKey(0L, (byte) 4);
        var ghostElement = createGhostElement(ghostKey, 1);
        when(mockGhostLayer.getAllGhostElements()).thenReturn(List.of(ghostElement));

        var localKey = new MortonKey(1L, (byte) 2);
        when(mockForest.containsKey(localKey)).thenReturn(true);

        var violations = checker.findViolations(mockGhostLayer, mockForest);

        assertTrue(violations.stream().anyMatch(v -> !v.localNeedsRefinement()),
                  "When local is lower level, local needs refinement (localNeedsRefinement=true)");
    }

    @Test
    public void testPerformanceWith1000Elements() {
        // Performance test: process 1000 ghost elements in < 10ms
        var ghosts = new java.util.ArrayList<GhostElement<MortonKey, LongEntityID, String>>();
        for (int i = 0; i < 1000; i++) {
            ghosts.add(createGhostElement(new MortonKey(i, (byte) 2), i % 8));
        }
        when(mockGhostLayer.getAllGhostElements()).thenReturn(ghosts);
        when(mockForest.containsKey(any())).thenReturn(false);  // No local neighbors

        long start = System.currentTimeMillis();
        var violations = checker.findViolations(mockGhostLayer, mockForest);
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed < 10, "Should process 1000 elements in < 10ms, took " + elapsed + "ms");
        assertTrue(violations.isEmpty(), "No violations with no local neighbors");
    }

    // Helper to create mock GhostElement
    private GhostElement<MortonKey, LongEntityID, String> createGhostElement(MortonKey key, int ownerRank) {
        return new GhostElement<>(key, new LongEntityID(key.morton()), new Point3f(0, 0, 0),
                                 ownerRank, 0L);
    }
}
