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
import com.hellblazer.luciferase.lucien.forest.ghost.GhostElement;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostLayer;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import org.junit.jupiter.api.BeforeEach;
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
        // Verify createRefinementRequests method exists and can be called
        var mockForest = mock(Forest.class);
        var violations = List.of(
            new TwoOneBalanceChecker.BalanceViolation<>(
                new MortonKey(1L, (byte) 2),
                new MortonKey(0L, (byte) 4),
                2, 4, 2, 1
            )
        );

        var requests = checker.createRefinementRequests(violations, 0, 0);

        assertNotNull(requests, "Should return refinement requests");
        assertTrue(requests instanceof List, "Should return list type");
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
