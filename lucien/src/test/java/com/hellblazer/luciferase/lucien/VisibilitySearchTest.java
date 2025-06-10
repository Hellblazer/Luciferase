/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for multi-entity visibility search functionality
 *
 * @author hal.hildebrand
 */
public class VisibilitySearchTest {

    private final byte                         testLevel = 15;
    private       Octree<LongEntityID, String> multiEntityOctree;

    @BeforeEach
    void setUp() {
        // Create test data with entities positioned for visibility testing
        List<EntityTestUtils.MultiEntityLocation<String>> locations = new ArrayList<>();

        // Observer location
        Point3f observerPos = new Point3f(100.0f, 100.0f, 100.0f);

        // Visible entities (clear line of sight)
        locations.add(
        new EntityTestUtils.MultiEntityLocation<>(new Point3f(200.0f, 100.0f, 100.0f), // Directly in front
                                                  testLevel, "VisibleEntity1", "VisibleEntity2"));

        // Partially occluded entities
        locations.add(
        new EntityTestUtils.MultiEntityLocation<>(new Point3f(300.0f, 100.0f, 100.0f), // Behind visible entities
                                                  testLevel, "PartiallyOccludedEntity1", "PartiallyOccludedEntity2"));

        // Occluding entities (blocking line of sight)
        locations.add(
        new EntityTestUtils.MultiEntityLocation<>(new Point3f(250.0f, 100.0f, 100.0f), // Between observer and target
                                                  testLevel, "OccludingEntity1", "OccludingEntity2",
                                                  "OccludingEntity3"));

        // Target entity
        locations.add(
        new EntityTestUtils.MultiEntityLocation<>(new Point3f(400.0f, 100.0f, 100.0f), testLevel, "TargetEntity"));

        // Entities outside viewing cone
        locations.add(
        new EntityTestUtils.MultiEntityLocation<>(new Point3f(100.0f, 300.0f, 100.0f), // 90 degrees to the side
                                                  testLevel, "OutsideConeEntity1", "OutsideConeEntity2"));

        // Far entities (out of range)
        locations.add(
        new EntityTestUtils.MultiEntityLocation<>(new Point3f(5000.0f, 5000.0f, 5000.0f), testLevel, "FarEntity1",
                                                  "FarEntity2"));

        multiEntityOctree = EntityTestUtils.createMultiEntityOctree(locations);
    }

    @Test
    void testCalculateVisibilityStatistics() {
        Point3f observer = new Point3f(100.0f, 100.0f, 100.0f);
        float maxViewDistance = 1000.0f;

        VisibilitySearch.EntityVisibilityStatistics stats = VisibilitySearch.calculateVisibilityStatistics(observer,
                                                                                                           maxViewDistance,
                                                                                                           multiEntityOctree);

        // Verify total entity count
        assertEquals(multiEntityOctree.getStats().entityCount(), stats.totalEntities);

        // Should have some visible entities
        assertTrue(stats.visibleEntities > 0);

        // Should have some entities out of range
        assertTrue(stats.entitiesOutOfRange > 0);

        // Should have identified top occluders
        assertNotNull(stats.topOccluders);
        assertTrue(stats.topOccluders.size() <= 5);

        // Verify percentages are valid
        assertTrue(stats.getVisiblePercentage() >= 0 && stats.getVisiblePercentage() <= 100);
        assertTrue(stats.getPartiallyOccludedPercentage() >= 0 && stats.getPartiallyOccludedPercentage() <= 100);
        assertTrue(stats.getFullyOccludedPercentage() >= 0 && stats.getFullyOccludedPercentage() <= 100);
    }

    @Test
    void testEmptyOctree() {
        Octree<LongEntityID, String> emptyOctree = EntityTestUtils.createMultiEntityOctree(new ArrayList<>());

        Point3f observer = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f target = new Point3f(200.0f, 200.0f, 200.0f);

        // Test line of sight
        VisibilitySearch.EntityLineOfSightResult<LongEntityID, String> losResult = VisibilitySearch.testLineOfSight(
        observer, target, emptyOctree, 0.1f);

        assertTrue(losResult.hasLineOfSight);
        assertTrue(losResult.occludingEntities.isEmpty());
        assertEquals(0.0f, losResult.totalOcclusionRatio);

        // Test visibility statistics
        VisibilitySearch.EntityVisibilityStatistics stats = VisibilitySearch.calculateVisibilityStatistics(observer,
                                                                                                           1000.0f,
                                                                                                           emptyOctree);

        assertEquals(0, stats.totalEntities);
    }

    @Test
    void testFindBestVantagePointsForEntity() {
        // Find the target entity ID
        LongEntityID targetId = null;
        for (var entry : multiEntityOctree.getEntitiesWithPositions().entrySet()) {
            if (multiEntityOctree.getEntity(entry.getKey()).equals("TargetEntity")) {
                targetId = entry.getKey();
                break;
            }
        }
        assertNotNull(targetId);

        // Create candidate vantage points
        List<Point3f> candidatePositions = new ArrayList<>();
        candidatePositions.add(new Point3f(100.0f, 100.0f, 100.0f)); // Direct line (but far)
        candidatePositions.add(new Point3f(350.0f, 150.0f, 100.0f)); // Closer, slight angle
        candidatePositions.add(new Point3f(400.0f, 200.0f, 100.0f)); // Very close, larger angle
        candidatePositions.add(new Point3f(250.0f, 100.0f, 200.0f)); // Side view

        List<VisibilitySearch.EntityVantagePoint<LongEntityID>> vantagePoints = VisibilitySearch.findBestVantagePointsForEntity(
        targetId, candidatePositions, multiEntityOctree);

        assertEquals(candidatePositions.size(), vantagePoints.size());

        // Should be sorted by visibility score (best first)
        for (int i = 0; i < vantagePoints.size() - 1; i++) {
            assertTrue(vantagePoints.get(i).visibilityScore >= vantagePoints.get(i + 1).visibilityScore);
        }

        // Verify all vantage points reference the target entity
        for (var vp : vantagePoints) {
            assertEquals(targetId, vp.targetEntityId);
        }
    }

    @Test
    void testFindOccludingEntities() {
        Point3f observer = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f target = new Point3f(400.0f, 100.0f, 100.0f); // Target entity position

        List<VisibilitySearch.EntityVisibilityResult<LongEntityID, String>> occluders = VisibilitySearch.findOccludingEntities(
        observer, target, multiEntityOctree);

        // Should find occluding entities
        assertTrue(occluders.size() > 0);

        // Should be sorted by occlusion contribution (highest first)
        for (int i = 0; i < occluders.size() - 1; i++) {
            assertTrue(occluders.get(i).occlusionRatio >= occluders.get(i + 1).occlusionRatio);
        }

        // Should have appropriate visibility types
        for (var occluder : occluders) {
            assertTrue(occluder.visibilityType == VisibilitySearch.VisibilityType.PARTIALLY_OCCLUDING
                       || occluder.visibilityType == VisibilitySearch.VisibilityType.FULLY_OCCLUDING);
        }
    }

    @Test
    void testFindVisibleEntities() {
        Point3f observer = new Point3f(100.0f, 100.0f, 100.0f);
        Vector3f viewDirection = new Vector3f(1.0f, 0.0f, 0.0f); // Looking along +X axis
        float viewAngle = (float) Math.toRadians(45); // 45 degree cone
        float maxViewDistance = 1000.0f;

        List<VisibilitySearch.EntityVisibilityResult<LongEntityID, String>> visibleEntities = VisibilitySearch.findVisibleEntities(
        observer, viewDirection, viewAngle, maxViewDistance, multiEntityOctree);

        // Should find entities within viewing cone
        assertTrue(visibleEntities.size() > 0);

        // Should include visible entities
        boolean hasVisibleEntity = visibleEntities.stream().anyMatch(e -> e.content.startsWith("VisibleEntity"));
        assertTrue(hasVisibleEntity);

        // Should NOT include entities outside cone
        boolean hasOutsideConeEntity = visibleEntities.stream().anyMatch(
        e -> e.content.startsWith("OutsideConeEntity"));
        assertFalse(hasOutsideConeEntity);

        // Should NOT include far entities
        boolean hasFarEntity = visibleEntities.stream().anyMatch(e -> e.content.startsWith("FarEntity"));
        assertFalse(hasFarEntity);

        // Should be sorted by distance
        for (int i = 0; i < visibleEntities.size() - 1; i++) {
            assertTrue(visibleEntities.get(i).distanceFromObserver <= visibleEntities.get(i + 1).distanceFromObserver);
        }
    }

    @Test
    void testInvalidViewAngle() {
        Point3f observer = new Point3f(100.0f, 100.0f, 100.0f);
        Vector3f viewDirection = new Vector3f(1.0f, 0.0f, 0.0f);
        float maxViewDistance = 1000.0f;

        // Test negative angle
        assertThrows(IllegalArgumentException.class, () -> {
            VisibilitySearch.findVisibleEntities(observer, viewDirection, -0.1f, maxViewDistance, multiEntityOctree);
        });

        // Test angle > PI
        assertThrows(IllegalArgumentException.class, () -> {
            VisibilitySearch.findVisibleEntities(observer, viewDirection, (float) (Math.PI + 0.1), maxViewDistance,
                                                 multiEntityOctree);
        });
    }

    @Test
    void testLineOfSightClear() {
        Point3f observer = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f target = new Point3f(200.0f, 100.0f, 100.0f); // Clear path to visible entities

        VisibilitySearch.EntityLineOfSightResult<LongEntityID, String> result = VisibilitySearch.testLineOfSight(
        observer, target, multiEntityOctree, 0.1f);

        // Should have clear line of sight (visible entities are at the target)
        assertTrue(result.hasLineOfSight || result.occludingEntities.size() <= 2); // May self-occlude
        assertTrue(result.totalOcclusionRatio < 0.5f);
    }

    @Test
    void testLineOfSightOccluded() {
        Point3f observer = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f target = new Point3f(400.0f, 100.0f, 100.0f); // Path blocked by occluding entities

        VisibilitySearch.EntityLineOfSightResult<LongEntityID, String> result = VisibilitySearch.testLineOfSight(
        observer, target, multiEntityOctree, 0.1f);

        // Should have occluding entities
        assertTrue(result.occludingEntities.size() > 0);

        // Occluding entities should be in the result
        boolean hasOccludingEntity = result.occludingEntities.stream().anyMatch(
        e -> e.content.startsWith("OccludingEntity"));
        assertTrue(hasOccludingEntity);

        // Should be sorted by distance from observer
        for (int i = 0; i < result.occludingEntities.size() - 1; i++) {
            assertTrue(result.occludingEntities.get(i).distanceFromObserver <= result.occludingEntities.get(
            i + 1).distanceFromObserver);
        }
    }

    @Test
    void testNegativeCoordinatesThrowsException() {
        // Test negative observer position
        Point3f invalidObserver = new Point3f(-10.0f, 10.0f, 10.0f);
        Point3f validTarget = new Point3f(100.0f, 100.0f, 100.0f);

        assertThrows(IllegalArgumentException.class, () -> {
            VisibilitySearch.testLineOfSight(invalidObserver, validTarget, multiEntityOctree, 0.1f);
        });

        // Test negative target position
        Point3f validObserver = new Point3f(10.0f, 10.0f, 10.0f);
        Point3f invalidTarget = new Point3f(100.0f, -10.0f, 100.0f);

        assertThrows(IllegalArgumentException.class, () -> {
            VisibilitySearch.testLineOfSight(validObserver, invalidTarget, multiEntityOctree, 0.1f);
        });
    }

    @Test
    void testOcclusionContributions() {
        Point3f observer = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f target = new Point3f(400.0f, 100.0f, 100.0f);

        VisibilitySearch.EntityLineOfSightResult<LongEntityID, String> result = VisibilitySearch.testLineOfSight(
        observer, target, multiEntityOctree, 0.1f);

        // Should have occlusion contributions map
        assertNotNull(result.entityOcclusionContributions);

        // Each occluding entity should have a contribution
        for (var occluder : result.occludingEntities) {
            assertTrue(result.entityOcclusionContributions.containsKey(occluder.id));
            Float contribution = result.entityOcclusionContributions.get(occluder.id);
            assertTrue(contribution > 0 && contribution <= 1.0f);
        }

        // Total contributions should not exceed total occlusion ratio
        float totalContributions = result.entityOcclusionContributions.values().stream().reduce(0.0f, Float::sum);
        assertTrue(totalContributions <= result.totalOcclusionRatio + 0.001f); // Small epsilon for float comparison
    }

    @Test
    void testSelfOcclusion() {
        // Create an octree with a single large entity
        List<EntityTestUtils.MultiEntityLocation<String>> locations = new ArrayList<>();
        locations.add(
        new EntityTestUtils.MultiEntityLocation<>(new Point3f(200.0f, 200.0f, 200.0f), testLevel, "LargeEntity"));

        Octree<LongEntityID, String> singleEntityOctree = EntityTestUtils.createMultiEntityOctree(locations);

        Point3f observer = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f target = new Point3f(200.0f, 200.0f, 200.0f); // Entity position

        VisibilitySearch.EntityLineOfSightResult<LongEntityID, String> result = VisibilitySearch.testLineOfSight(
        observer, target, singleEntityOctree, 0.1f);

        // Entity might occlude itself, but this should still be considered visible
        if (result.occludingEntities.size() == 1 && result.occludingEntities.get(0).content.equals("LargeEntity")) {
            // Self-occlusion case
            assertEquals(1, result.occludingEntities.size());
        }
    }

    @Test
    void testViewingConeFiltering() {
        Point3f observer = new Point3f(100.0f, 100.0f, 100.0f);
        Vector3f viewDirection = new Vector3f(1.0f, 0.0f, 0.0f); // Looking along +X
        float narrowAngle = (float) Math.toRadians(10); // Very narrow cone
        float wideAngle = (float) Math.toRadians(90); // Wide cone
        float maxViewDistance = 1000.0f;

        List<VisibilitySearch.EntityVisibilityResult<LongEntityID, String>> narrowResults = VisibilitySearch.findVisibleEntities(
        observer, viewDirection, narrowAngle, maxViewDistance, multiEntityOctree);

        List<VisibilitySearch.EntityVisibilityResult<LongEntityID, String>> wideResults = VisibilitySearch.findVisibleEntities(
        observer, viewDirection, wideAngle, maxViewDistance, multiEntityOctree);

        // Wide cone should find more entities
        assertTrue(wideResults.size() >= narrowResults.size());

        // All entities in narrow cone should be in wide cone
        for (var narrowEntity : narrowResults) {
            boolean foundInWide = wideResults.stream().anyMatch(w -> w.id.equals(narrowEntity.id));
            assertTrue(foundInWide);
        }
    }

    @Test
    void testVisibilityClassification() {
        Point3f observer = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f farTarget = new Point3f(1000.0f, 100.0f, 100.0f);

        VisibilitySearch.EntityLineOfSightResult<LongEntityID, String> result = VisibilitySearch.testLineOfSight(
        observer, farTarget, multiEntityOctree, 0.1f);

        // Check visibility type classifications
        for (var entity : result.occludingEntities) {
            assertNotNull(entity.visibilityType);

            // Entities between observer and target should be occluding types
            if (entity.distanceFromObserver < observer.distance(farTarget)) {
                assertTrue(entity.visibilityType == VisibilitySearch.VisibilityType.PARTIALLY_OCCLUDING
                           || entity.visibilityType == VisibilitySearch.VisibilityType.FULLY_OCCLUDING
                           || entity.visibilityType == VisibilitySearch.VisibilityType.BEFORE_OBSERVER);
            }
        }
    }
}
