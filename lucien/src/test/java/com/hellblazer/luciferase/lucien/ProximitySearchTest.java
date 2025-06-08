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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for multi-entity proximity search functionality
 *
 * @author hal.hildebrand
 */
public class ProximitySearchTest {

    private final byte testLevel = 15;
    private OctreeWithEntities<LongEntityID, String> multiEntityOctree;

    @BeforeEach
    void setUp() {
        // Create test data with entities at various distances from query points
        List<EntityTestUtils.MultiEntityLocation<String>> locations = new ArrayList<>();
        
        // Very close entities (< 100 units)
        locations.add(new EntityTestUtils.MultiEntityLocation<>(
            new Point3f(50.0f, 50.0f, 50.0f),
            testLevel,
            "VeryCloseEntity1", "VeryCloseEntity2"
        ));
        
        // Close entities (100-500 units)
        locations.add(new EntityTestUtils.MultiEntityLocation<>(
            new Point3f(300.0f, 300.0f, 300.0f),
            testLevel,
            "CloseEntity1", "CloseEntity2", "CloseEntity3"
        ));
        
        // Moderate distance entities (500-1000 units)
        locations.add(new EntityTestUtils.MultiEntityLocation<>(
            new Point3f(700.0f, 700.0f, 700.0f),
            testLevel,
            "ModerateEntity1", "ModerateEntity2"
        ));
        
        // Far entities (1000-5000 units)
        locations.add(new EntityTestUtils.MultiEntityLocation<>(
            new Point3f(2000.0f, 2000.0f, 2000.0f),
            testLevel,
            "FarEntity1"
        ));
        
        // Very far entities (> 5000 units)
        locations.add(new EntityTestUtils.MultiEntityLocation<>(
            new Point3f(6000.0f, 6000.0f, 6000.0f),
            testLevel,
            "VeryFarEntity1", "VeryFarEntity2"
        ));
        
        // Multiple entities at exact same location
        locations.add(new EntityTestUtils.MultiEntityLocation<>(
            new Point3f(1000.0f, 1000.0f, 1000.0f),
            testLevel,
            "SameLocation1", "SameLocation2", "SameLocation3"
        ));
        
        multiEntityOctree = EntityTestUtils.createMultiEntityOctree(locations);
    }

    @Test
    void testFindEntitiesWithinDistanceRange() {
        Point3f queryPoint = new Point3f(0.0f, 0.0f, 0.0f);
        
        // Test very close range
        ProximitySearch.DistanceRange veryCloseRange = 
            new ProximitySearch.DistanceRange(0.0f, 100.0f, 
                ProximitySearch.ProximityType.VERY_CLOSE);
        
        List<ProximitySearch.EntityProximityResult<LongEntityID, String>> veryCloseResults =
            ProximitySearch.findEntitiesWithinDistanceRange(queryPoint, veryCloseRange, multiEntityOctree);
        
        // Should find 2 very close entities
        assertEquals(2, veryCloseResults.size());
        assertTrue(veryCloseResults.stream().allMatch(r -> r.content.startsWith("VeryCloseEntity")));
        
        // Test moderate range
        ProximitySearch.DistanceRange moderateRange = 
            new ProximitySearch.DistanceRange(500.0f, 1000.0f, 
                ProximitySearch.ProximityType.MODERATE);
        
        List<ProximitySearch.EntityProximityResult<LongEntityID, String>> moderateResults =
            ProximitySearch.findEntitiesWithinDistanceRange(queryPoint, moderateRange, multiEntityOctree);
        
        // Should find moderate entities
        assertTrue(moderateResults.size() > 0);
        
        // Verify sorting by distance
        for (int i = 0; i < moderateResults.size() - 1; i++) {
            assertTrue(moderateResults.get(i).distanceToQuery <= moderateResults.get(i + 1).distanceToQuery);
        }
    }

    @Test
    void testFindEntitiesInProximityBands() {
        Point3f queryPoint = new Point3f(0.0f, 0.0f, 0.0f);
        
        // Define multiple distance ranges
        List<ProximitySearch.DistanceRange> ranges = new ArrayList<>();
        ranges.add(new ProximitySearch.DistanceRange(0.0f, 100.0f, 
            ProximitySearch.ProximityType.VERY_CLOSE));
        ranges.add(new ProximitySearch.DistanceRange(100.0f, 500.0f, 
            ProximitySearch.ProximityType.CLOSE));
        ranges.add(new ProximitySearch.DistanceRange(500.0f, 1000.0f, 
            ProximitySearch.ProximityType.MODERATE));
        
        Map<ProximitySearch.DistanceRange, 
            List<ProximitySearch.EntityProximityResult<LongEntityID, String>>> bandResults =
            ProximitySearch.findEntitiesInProximityBands(queryPoint, ranges, multiEntityOctree);
        
        assertEquals(3, bandResults.size());
        
        // Verify each band has appropriate entities
        List<ProximitySearch.EntityProximityResult<LongEntityID, String>> veryCloseResults = 
            bandResults.get(ranges.get(0));
        assertTrue(veryCloseResults.stream().allMatch(r -> r.distanceToQuery <= 100.0f));
        
        List<ProximitySearch.EntityProximityResult<LongEntityID, String>> closeResults = 
            bandResults.get(ranges.get(1));
        assertTrue(closeResults.stream().allMatch(r -> 
            r.distanceToQuery > 100.0f && r.distanceToQuery <= 500.0f));
    }

    @Test
    void testFindNClosestEntities() {
        Point3f queryPoint = new Point3f(0.0f, 0.0f, 0.0f);
        int n = 5;
        
        List<ProximitySearch.EntityProximityResult<LongEntityID, String>> closestEntities =
            ProximitySearch.findNClosestEntities(queryPoint, n, multiEntityOctree);
        
        // Should return exactly 5 entities
        assertEquals(n, closestEntities.size());
        
        // Should be sorted by distance
        for (int i = 0; i < closestEntities.size() - 1; i++) {
            assertTrue(closestEntities.get(i).distanceToQuery <= closestEntities.get(i + 1).distanceToQuery);
        }
        
        // First entities should be the very close ones
        assertTrue(closestEntities.get(0).content.startsWith("VeryCloseEntity"));
        assertTrue(closestEntities.get(1).content.startsWith("VeryCloseEntity"));
    }

    @Test
    void testFindEntitiesNearAnyPoint() {
        List<Point3f> queryPoints = new ArrayList<>();
        queryPoints.add(new Point3f(0.0f, 0.0f, 0.0f));
        queryPoints.add(new Point3f(2000.0f, 2000.0f, 2000.0f));
        float maxDistance = 200.0f;
        
        List<ProximitySearch.EntityProximityResult<LongEntityID, String>> nearAnyResults =
            ProximitySearch.findEntitiesNearAnyPoint(queryPoints, maxDistance, multiEntityOctree);
        
        // Should find entities near either query point
        assertTrue(nearAnyResults.size() > 0);
        
        // Should include very close entities (near first point) and far entities (near second point)
        boolean hasVeryClose = nearAnyResults.stream().anyMatch(r -> r.content.startsWith("VeryCloseEntity"));
        boolean hasFar = nearAnyResults.stream().anyMatch(r -> r.content.startsWith("FarEntity"));
        assertTrue(hasVeryClose || hasFar);
        
        // All results should be within maxDistance of at least one query point
        assertTrue(nearAnyResults.stream().allMatch(r -> r.minDistanceToQuery <= maxDistance));
    }

    @Test
    void testFindEntitiesNearAllPoints() {
        // Create query points that form a small region
        List<Point3f> queryPoints = new ArrayList<>();
        queryPoints.add(new Point3f(990.0f, 990.0f, 990.0f));
        queryPoints.add(new Point3f(1010.0f, 1010.0f, 1010.0f));
        float maxDistance = 50.0f;
        
        List<ProximitySearch.EntityProximityResult<LongEntityID, String>> nearAllResults =
            ProximitySearch.findEntitiesNearAllPoints(queryPoints, maxDistance, multiEntityOctree);
        
        // Should only find entities near all query points
        assertTrue(nearAllResults.size() > 0);
        
        // Should find the entities at (1000, 1000, 1000)
        assertTrue(nearAllResults.stream().anyMatch(r -> r.content.startsWith("SameLocation")));
    }

    @Test
    void testGetProximityStatistics() {
        Point3f queryPoint = new Point3f(500.0f, 500.0f, 500.0f);
        
        ProximitySearch.ProximityStatistics stats = 
            ProximitySearch.getProximityStatistics(queryPoint, multiEntityOctree);
        
        // Verify total entity count
        assertEquals(multiEntityOctree.getStats().entityCount, stats.totalEntities);
        
        // Verify statistics are calculated
        assertTrue(stats.averageDistance > 0);
        assertTrue(stats.minDistance >= 0);
        assertTrue(stats.maxDistance > stats.minDistance);
        
        // Verify percentages add up to ~100%
        double totalPercentage = stats.getVeryClosePercentage() + 
                                stats.getClosePercentage() + 
                                stats.getModeratePercentage() + 
                                stats.getFarPercentage();
        assertEquals(100.0, totalPercentage, 0.1);
    }

    @Test
    void testFindEntitiesWithProximityChange() {
        Point3f fromPoint = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f toPoint = new Point3f(1000.0f, 1000.0f, 1000.0f);
        
        List<ProximitySearch.ProximityChangeResult<LongEntityID, String>> changes =
            ProximitySearch.findEntitiesWithProximityChange(fromPoint, toPoint, multiEntityOctree);
        
        // Should find entities whose proximity classification changed
        assertTrue(changes.size() > 0);
        
        // Verify all results have different proximity types
        for (var change : changes) {
            assertNotEquals(change.typeFrom, change.typeTo);
        }
        
        // Should be sorted by magnitude of distance change
        for (int i = 0; i < changes.size() - 1; i++) {
            float change1 = Math.abs(changes.get(i).getDistanceChange());
            float change2 = Math.abs(changes.get(i + 1).getDistanceChange());
            assertTrue(change1 <= change2);
        }
    }

    @Test
    void testProximityTypeClassification() {
        Point3f queryPoint = new Point3f(0.0f, 0.0f, 0.0f);
        
        // Get all entities and verify their proximity classifications
        List<ProximitySearch.EntityProximityResult<LongEntityID, String>> allResults =
            ProximitySearch.findNClosestEntities(queryPoint, Integer.MAX_VALUE, multiEntityOctree);
        
        for (var result : allResults) {
            if (result.content.startsWith("VeryCloseEntity")) {
                assertEquals(ProximitySearch.ProximityType.VERY_CLOSE, result.proximityType);
            } else if (result.content.startsWith("CloseEntity")) {
                assertEquals(ProximitySearch.ProximityType.CLOSE, result.proximityType);
            } else if (result.content.startsWith("ModerateEntity")) {
                assertEquals(ProximitySearch.ProximityType.MODERATE, result.proximityType);
            } else if (result.content.startsWith("FarEntity")) {
                assertEquals(ProximitySearch.ProximityType.FAR, result.proximityType);
            } else if (result.content.startsWith("VeryFarEntity")) {
                assertEquals(ProximitySearch.ProximityType.VERY_FAR, result.proximityType);
            }
        }
    }

    @Test
    void testDistanceRangeValidation() {
        // Test negative distances
        assertThrows(IllegalArgumentException.class, () -> {
            new ProximitySearch.DistanceRange(-10.0f, 100.0f, 
                ProximitySearch.ProximityType.CLOSE);
        });
        
        // Test max < min
        assertThrows(IllegalArgumentException.class, () -> {
            new ProximitySearch.DistanceRange(100.0f, 50.0f, 
                ProximitySearch.ProximityType.CLOSE);
        });
        
        // Test valid range
        ProximitySearch.DistanceRange validRange = 
            new ProximitySearch.DistanceRange(10.0f, 100.0f, 
                ProximitySearch.ProximityType.CLOSE);
        
        assertTrue(validRange.contains(50.0f));
        assertFalse(validRange.contains(5.0f));
        assertFalse(validRange.contains(150.0f));
    }

    @Test
    void testNegativeCoordinatesThrowsException() {
        // Test negative query point
        Point3f invalidQuery = new Point3f(-10.0f, 10.0f, 10.0f);
        ProximitySearch.DistanceRange range = 
            new ProximitySearch.DistanceRange(0.0f, 100.0f, 
                ProximitySearch.ProximityType.CLOSE);
        
        assertThrows(IllegalArgumentException.class, () -> {
            ProximitySearch.findEntitiesWithinDistanceRange(invalidQuery, range, multiEntityOctree);
        });
        
        // Test negative query points in list
        List<Point3f> queryPoints = new ArrayList<>();
        queryPoints.add(new Point3f(10.0f, 10.0f, 10.0f));
        queryPoints.add(new Point3f(-10.0f, 10.0f, 10.0f));
        
        assertThrows(IllegalArgumentException.class, () -> {
            ProximitySearch.findEntitiesNearAnyPoint(queryPoints, 100.0f, multiEntityOctree);
        });
    }

    @Test
    void testEmptyOctree() {
        OctreeWithEntities<LongEntityID, String> emptyOctree = 
            EntityTestUtils.createMultiEntityOctree(new ArrayList<>());
        
        Point3f queryPoint = new Point3f(100.0f, 100.0f, 100.0f);
        ProximitySearch.DistanceRange range = 
            new ProximitySearch.DistanceRange(0.0f, 1000.0f, 
                ProximitySearch.ProximityType.CLOSE);
        
        List<ProximitySearch.EntityProximityResult<LongEntityID, String>> results =
            ProximitySearch.findEntitiesWithinDistanceRange(queryPoint, range, emptyOctree);
        
        assertTrue(results.isEmpty());
        
        // Test statistics on empty octree
        ProximitySearch.ProximityStatistics stats = 
            ProximitySearch.getProximityStatistics(queryPoint, emptyOctree);
        
        assertEquals(0, stats.totalEntities);
        assertEquals(0.0, stats.averageDistance);
    }

    @Test
    void testProximityChangeAnalysis() {
        Point3f fromPoint = new Point3f(50.0f, 50.0f, 50.0f);
        Point3f toPoint = new Point3f(3000.0f, 3000.0f, 3000.0f);
        
        List<ProximitySearch.ProximityChangeResult<LongEntityID, String>> changes =
            ProximitySearch.findEntitiesWithProximityChange(fromPoint, toPoint, multiEntityOctree);
        
        // Find entities that got closer vs farther
        long gettingCloser = changes.stream().filter(c -> c.isGettingCloser()).count();
        long gettingFarther = changes.stream().filter(c -> !c.isGettingCloser()).count();
        
        // Moving from (50,50,50) to (3000,3000,3000) should make most entities get closer
        // except the very far ones at (6000,6000,6000)
        assertTrue(gettingCloser > 0);
        assertTrue(gettingFarther > 0);
    }

    @Test
    void testInvalidNValue() {
        Point3f queryPoint = new Point3f(100.0f, 100.0f, 100.0f);
        
        assertThrows(IllegalArgumentException.class, () -> {
            ProximitySearch.findNClosestEntities(queryPoint, 0, multiEntityOctree);
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            ProximitySearch.findNClosestEntities(queryPoint, -5, multiEntityOctree);
        });
    }

    @Test
    void testInvalidMaxDistance() {
        List<Point3f> queryPoints = new ArrayList<>();
        queryPoints.add(new Point3f(100.0f, 100.0f, 100.0f));
        
        assertThrows(IllegalArgumentException.class, () -> {
            ProximitySearch.findEntitiesNearAnyPoint(queryPoints, -10.0f, multiEntityOctree);
        });
    }
}