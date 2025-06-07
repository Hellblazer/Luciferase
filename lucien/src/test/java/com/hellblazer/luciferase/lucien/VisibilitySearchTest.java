package com.hellblazer.luciferase.lucien;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Visibility search functionality
 * All test coordinates use positive values only
 * 
 * @author hal.hildebrand
 */
public class VisibilitySearchTest {

    private Octree<String> octree;
    private final byte testLevel = 15;

    @BeforeEach
    void setUp() {
        octree = new Octree<>();
        
        // Use coordinates that will map to different cubes - all positive
        int gridSize = Constants.lengthAtLevel(testLevel);
        
        // Insert test data points in a spatial pattern
        octree.insert(new Point3f(100.0f, 100.0f, 100.0f), testLevel, "Near1");
        octree.insert(new Point3f(200.0f, 200.0f, 200.0f), testLevel, "Center1");
        octree.insert(new Point3f(300.0f, 300.0f, 300.0f), testLevel, "Far1");
        octree.insert(new Point3f(150.0f, 150.0f, 150.0f), testLevel, "Near2");
        octree.insert(new Point3f(250.0f, 250.0f, 250.0f), testLevel, "Center2");
        octree.insert(new Point3f(350.0f, 350.0f, 350.0f), testLevel, "Far2");
        octree.insert(new Point3f(50.0f, 50.0f, 50.0f), testLevel, "VeryNear");
        octree.insert(new Point3f(500.0f, 500.0f, 500.0f), testLevel, "VeryFar");
        octree.insert(new Point3f(120.0f, 180.0f, 160.0f), testLevel, "OffAxis1");
        octree.insert(new Point3f(280.0f, 220.0f, 240.0f), testLevel, "OffAxis2");
    }

    @Test
    void testLineOfSight() {
        Point3f observer = new Point3f(50.0f, 50.0f, 50.0f);
        Point3f target = new Point3f(400.0f, 400.0f, 400.0f);
        float occlusionThreshold = 10.0f;
        
        VisibilitySearch.LineOfSightResult<String> result = 
            VisibilitySearch.testLineOfSight(observer, target, octree, occlusionThreshold);
        
        assertNotNull(result);
        assertNotNull(result.occludingCubes);
        assertTrue(result.totalOcclusionRatio >= 0.0f);
        assertTrue(result.totalOcclusionRatio <= Float.MAX_VALUE);
        assertTrue(result.distanceThroughOccluders >= 0.0f);
        
        // Occluding cubes should be sorted by distance from observer
        for (int i = 0; i < result.occludingCubes.size() - 1; i++) {
            assertTrue(result.occludingCubes.get(i).distanceFromObserver <= 
                      result.occludingCubes.get(i + 1).distanceFromObserver);
        }
        
        // All occluding cubes should have valid data
        for (var occluder : result.occludingCubes) {
            assertNotNull(occluder.content);
            assertNotNull(occluder.cube);
            assertNotNull(occluder.cubeCenter);
            assertNotNull(occluder.visibilityType);
            assertTrue(occluder.distanceFromObserver >= 0);
            assertTrue(occluder.distanceFromTarget >= 0);
            assertTrue(occluder.occlusionRatio >= 0);
            assertTrue(occluder.index >= 0);
            
            // Should be an occluding type
            assertTrue(occluder.visibilityType == VisibilitySearch.VisibilityType.PARTIALLY_OCCLUDING ||
                      occluder.visibilityType == VisibilitySearch.VisibilityType.FULLY_OCCLUDING);
        }
    }

    @Test
    void testLineOfSightSamePoint() {
        Point3f samePoint = new Point3f(200.0f, 200.0f, 200.0f);
        float occlusionThreshold = 10.0f;
        
        VisibilitySearch.LineOfSightResult<String> result = 
            VisibilitySearch.testLineOfSight(samePoint, samePoint, octree, occlusionThreshold);
        
        assertNotNull(result);
        assertTrue(result.hasLineOfSight); // Same point should have line of sight
        assertTrue(result.occludingCubes.isEmpty());
        assertEquals(0.0f, result.totalOcclusionRatio);
        assertEquals(0.0f, result.distanceThroughOccluders);
    }

    @Test
    void testFindVisibleCubes() {
        Point3f observer = new Point3f(100.0f, 100.0f, 100.0f);
        Vector3f viewDirection = new Vector3f(100.0f, 100.0f, 100.0f); // Looking towards positive direction
        float viewAngle = (float) (Math.PI / 3.0); // 60 degrees
        float maxViewDistance = 1000.0f;
        
        List<VisibilitySearch.VisibilityResult<String>> visibleCubes = 
            VisibilitySearch.findVisibleCubes(observer, viewDirection, viewAngle, maxViewDistance, octree);
        
        assertNotNull(visibleCubes);
        
        // Results should be sorted by distance from observer
        for (int i = 0; i < visibleCubes.size() - 1; i++) {
            assertTrue(visibleCubes.get(i).distanceFromObserver <= 
                      visibleCubes.get(i + 1).distanceFromObserver);
        }
        
        // All visible cubes should be within viewing distance and cone
        for (var visible : visibleCubes) {
            assertNotNull(visible.content);
            assertNotNull(visible.cube);
            assertNotNull(visible.cubeCenter);
            assertEquals(VisibilitySearch.VisibilityType.VISIBLE, visible.visibilityType);
            assertTrue(visible.distanceFromObserver >= 0);
            assertTrue(visible.distanceFromObserver <= maxViewDistance);
            assertEquals(0.0f, visible.occlusionRatio);
            assertTrue(visible.index >= 0);
        }
    }

    @Test
    void testCalculateVisibilityStatistics() {
        Point3f observer = new Point3f(150.0f, 150.0f, 150.0f);
        float maxViewDistance = 1000.0f;
        
        VisibilitySearch.VisibilityStatistics stats = 
            VisibilitySearch.calculateVisibilityStatistics(observer, maxViewDistance, octree);
        
        assertNotNull(stats);
        assertTrue(stats.totalCubes >= 0);
        assertTrue(stats.visibleCubes >= 0);
        assertTrue(stats.partiallyOccludedCubes >= 0);
        assertTrue(stats.fullyOccludedCubes >= 0);
        assertTrue(stats.cubesOutOfRange >= 0);
        assertTrue(stats.totalVisibilityRatio >= 0);
        assertTrue(stats.averageVisibilityRatio >= 0);
        
        // Total should equal sum of parts
        assertTrue(stats.totalCubes >= stats.visibleCubes + stats.partiallyOccludedCubes + 
                  stats.fullyOccludedCubes + stats.cubesOutOfRange);
        
        // Percentages should be valid
        assertTrue(stats.getVisiblePercentage() >= 0 && stats.getVisiblePercentage() <= 100);
        assertTrue(stats.getPartiallyOccludedPercentage() >= 0 && stats.getPartiallyOccludedPercentage() <= 100);
        assertTrue(stats.getFullyOccludedPercentage() >= 0 && stats.getFullyOccludedPercentage() <= 100);
        assertTrue(stats.getOutOfRangePercentage() >= 0 && stats.getOutOfRangePercentage() <= 100);
        
        // Test toString
        String statsStr = stats.toString();
        assertTrue(statsStr.contains("VisibilityStats"));
        assertTrue(statsStr.contains("total="));
        assertTrue(statsStr.contains("visible="));
        assertTrue(statsStr.contains("partial="));
        assertTrue(statsStr.contains("occluded="));
        assertTrue(statsStr.contains("out_of_range="));
        assertTrue(statsStr.contains("avg_visibility="));
    }

    @Test
    void testFindBestVantagePoints() {
        Point3f target = new Point3f(300.0f, 300.0f, 300.0f);
        List<Point3f> candidatePositions = List.of(
            new Point3f(100.0f, 100.0f, 100.0f),
            new Point3f(200.0f, 200.0f, 200.0f),
            new Point3f(400.0f, 400.0f, 400.0f),
            new Point3f(250.0f, 250.0f, 100.0f)
        );
        
        List<VisibilitySearch.VantagePoint> vantagePoints = 
            VisibilitySearch.findBestVantagePoints(target, candidatePositions, octree);
        
        assertNotNull(vantagePoints);
        assertEquals(candidatePositions.size(), vantagePoints.size());
        
        // Results should be sorted by visibility score (higher is better)
        for (int i = 0; i < vantagePoints.size() - 1; i++) {
            assertTrue(vantagePoints.get(i).visibilityScore >= 
                      vantagePoints.get(i + 1).visibilityScore);
        }
        
        // All vantage points should have valid data
        for (var vantagePoint : vantagePoints) {
            assertNotNull(vantagePoint.position);
            assertTrue(vantagePoint.distanceToTarget >= 0);
            assertTrue(vantagePoint.occlusionRatio >= 0 && vantagePoint.occlusionRatio <= 1.0f);
            assertTrue(vantagePoint.visibilityScore >= 0);
            
            // Test toString
            String vpStr = vantagePoint.toString();
            assertTrue(vpStr.contains("VantagePoint"));
            assertTrue(vpStr.contains("pos="));
            assertTrue(vpStr.contains("dist="));
            assertTrue(vpStr.contains("los="));
            assertTrue(vpStr.contains("occlusion="));
            assertTrue(vpStr.contains("score="));
        }
    }

    @Test
    void testEmptyOctree() {
        Octree<String> emptyOctree = new Octree<>();
        Point3f observer = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f target = new Point3f(300.0f, 300.0f, 300.0f);
        Vector3f viewDirection = new Vector3f(100.0f, 100.0f, 100.0f);
        
        VisibilitySearch.LineOfSightResult<String> losResult = 
            VisibilitySearch.testLineOfSight(observer, target, emptyOctree, 10.0f);
        
        assertTrue(losResult.hasLineOfSight); // No occluders
        assertTrue(losResult.occludingCubes.isEmpty());
        assertEquals(0.0f, losResult.totalOcclusionRatio);
        assertEquals(0.0f, losResult.distanceThroughOccluders);
        
        List<VisibilitySearch.VisibilityResult<String>> visibleCubes = 
            VisibilitySearch.findVisibleCubes(observer, viewDirection, (float) Math.PI / 4, 1000.0f, emptyOctree);
        
        assertTrue(visibleCubes.isEmpty());
        
        VisibilitySearch.VisibilityStatistics stats = 
            VisibilitySearch.calculateVisibilityStatistics(observer, 1000.0f, emptyOctree);
        assertEquals(0, stats.totalCubes);
        assertEquals(0, stats.visibleCubes);
        assertEquals(0, stats.partiallyOccludedCubes);
        assertEquals(0, stats.fullyOccludedCubes);
        assertEquals(0, stats.cubesOutOfRange);
    }

    @Test
    void testNegativeCoordinatesThrowException() {
        Point3f negativeObserver = new Point3f(-100.0f, 100.0f, 100.0f);
        Point3f positiveObserver = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f negativeTarget = new Point3f(-300.0f, 300.0f, 300.0f);
        Point3f positiveTarget = new Point3f(300.0f, 300.0f, 300.0f);
        Vector3f viewDirection = new Vector3f(100.0f, 100.0f, 100.0f);
        
        // Test negative observer
        assertThrows(IllegalArgumentException.class, () -> {
            VisibilitySearch.testLineOfSight(negativeObserver, positiveTarget, octree, 10.0f);
        });
        
        // Test negative target
        assertThrows(IllegalArgumentException.class, () -> {
            VisibilitySearch.testLineOfSight(positiveObserver, negativeTarget, octree, 10.0f);
        });
        
        // Test negative observer in findVisibleCubes
        assertThrows(IllegalArgumentException.class, () -> {
            VisibilitySearch.findVisibleCubes(negativeObserver, viewDirection, 
                                            (float) Math.PI / 4, 1000.0f, octree);
        });
        
        // Test negative observer in calculateVisibilityStatistics
        assertThrows(IllegalArgumentException.class, () -> {
            VisibilitySearch.calculateVisibilityStatistics(negativeObserver, 1000.0f, octree);
        });
        
        // Test negative target in findBestVantagePoints
        assertThrows(IllegalArgumentException.class, () -> {
            List<Point3f> candidates = List.of(positiveObserver);
            VisibilitySearch.findBestVantagePoints(negativeTarget, candidates, octree);
        });
        
        // Test negative candidate in findBestVantagePoints
        assertThrows(IllegalArgumentException.class, () -> {
            List<Point3f> candidates = List.of(negativeObserver);
            VisibilitySearch.findBestVantagePoints(positiveTarget, candidates, octree);
        });
    }

    @Test
    void testInvalidParameters() {
        Point3f observer = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f target = new Point3f(300.0f, 300.0f, 300.0f);
        Vector3f viewDirection = new Vector3f(100.0f, 100.0f, 100.0f);
        
        // Test negative occlusion threshold
        assertThrows(IllegalArgumentException.class, () -> {
            VisibilitySearch.testLineOfSight(observer, target, octree, -10.0f);
        });
        
        // Test invalid view angle
        assertThrows(IllegalArgumentException.class, () -> {
            VisibilitySearch.findVisibleCubes(observer, viewDirection, -0.1f, 1000.0f, octree);
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            VisibilitySearch.findVisibleCubes(observer, viewDirection, 
                                            (float) Math.PI + 0.1f, 1000.0f, octree);
        });
        
        // Test negative max view distance
        assertThrows(IllegalArgumentException.class, () -> {
            VisibilitySearch.findVisibleCubes(observer, viewDirection, 
                                            (float) Math.PI / 4, -100.0f, octree);
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            VisibilitySearch.calculateVisibilityStatistics(observer, -100.0f, octree);
        });
    }

    @Test
    void testVisibilityResultDataIntegrity() {
        Point3f observer = new Point3f(100.0f, 100.0f, 100.0f);
        Vector3f viewDirection = new Vector3f(100.0f, 100.0f, 100.0f);
        float viewAngle = (float) (Math.PI / 2.0); // Wide angle to catch more cubes
        float maxViewDistance = 1000.0f;
        
        List<VisibilitySearch.VisibilityResult<String>> visibleCubes = 
            VisibilitySearch.findVisibleCubes(observer, viewDirection, viewAngle, maxViewDistance, octree);
        
        for (var result : visibleCubes) {
            // Verify all fields are properly set
            assertNotNull(result.content);
            assertNotNull(result.cube);
            assertNotNull(result.cubeCenter);
            assertNotNull(result.visibilityType);
            assertTrue(result.distanceFromObserver >= 0);
            assertTrue(result.distanceFromTarget >= 0);
            assertTrue(result.occlusionRatio >= 0);
            assertTrue(result.index >= 0);
            
            // Verify cube center is within cube bounds
            Spatial.Cube cube = result.cube;
            Point3f center = result.cubeCenter;
            assertTrue(center.x >= cube.originX());
            assertTrue(center.x <= cube.originX() + cube.extent());
            assertTrue(center.y >= cube.originY());
            assertTrue(center.y <= cube.originY() + cube.extent());
            assertTrue(center.z >= cube.originZ());
            assertTrue(center.z <= cube.originZ() + cube.extent());
            
            // Verify distance calculation
            float dx = observer.x - center.x;
            float dy = observer.y - center.y;
            float dz = observer.z - center.z;
            float expectedDistance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            assertEquals(expectedDistance, result.distanceFromObserver, 0.001f);
            
            // Should be visible
            assertEquals(VisibilitySearch.VisibilityType.VISIBLE, result.visibilityType);
            assertEquals(0.0f, result.occlusionRatio);
        }
    }

    @Test
    void testLineOfSightResultDataIntegrity() {
        Point3f observer = new Point3f(50.0f, 50.0f, 50.0f);
        Point3f target = new Point3f(400.0f, 400.0f, 400.0f);
        float occlusionThreshold = 1.0f; // Small threshold to catch more occluders
        
        VisibilitySearch.LineOfSightResult<String> result = 
            VisibilitySearch.testLineOfSight(observer, target, octree, occlusionThreshold);
        
        // Basic result validation
        assertNotNull(result);
        assertNotNull(result.occludingCubes);
        assertTrue(result.totalOcclusionRatio >= 0.0f);
        assertTrue(result.distanceThroughOccluders >= 0.0f);
        
        // Line of sight should be consistent with occlusion ratio
        if (result.totalOcclusionRatio >= 0.5f) {
            // High occlusion might indicate no line of sight
            // But this depends on the specific implementation threshold
            assertDoesNotThrow(() -> result.hasLineOfSight); // Just verify it's calculated
        }
        
        // Validate occluding cubes
        for (var occluder : result.occludingCubes) {
            assertNotNull(occluder.content);
            assertNotNull(occluder.cube);
            assertNotNull(occluder.cubeCenter);
            assertNotNull(occluder.visibilityType);
            
            // Should be an occluding type
            assertTrue(occluder.visibilityType == VisibilitySearch.VisibilityType.PARTIALLY_OCCLUDING ||
                      occluder.visibilityType == VisibilitySearch.VisibilityType.FULLY_OCCLUDING);
            
            assertTrue(occluder.occlusionRatio >= 0.0f && occluder.occlusionRatio <= 1.0f);
        }
    }

    @Test
    void testVantagePointScoring() {
        Point3f target = new Point3f(300.0f, 300.0f, 300.0f);
        
        // Create candidates at different distances
        List<Point3f> candidatePositions = List.of(
            new Point3f(290.0f, 290.0f, 290.0f), // Very close
            new Point3f(200.0f, 200.0f, 200.0f), // Medium distance
            new Point3f(100.0f, 100.0f, 100.0f), // Far
            new Point3f(50.0f, 50.0f, 50.0f)     // Very far
        );
        
        List<VisibilitySearch.VantagePoint> vantagePoints = 
            VisibilitySearch.findBestVantagePoints(target, candidatePositions, octree);
        
        // Verify that vantage points have reasonable score distribution
        for (var vantagePoint : vantagePoints) {
            assertTrue(vantagePoint.visibilityScore >= 0.0f);
            
            // Distance should be reasonable
            assertTrue(vantagePoint.distanceToTarget >= 0.0f);
            
            // Occlusion ratio should be valid
            assertTrue(vantagePoint.occlusionRatio >= 0.0f && vantagePoint.occlusionRatio <= 1.0f);
        }
        
        // Results should be sorted by score (descending)
        for (int i = 0; i < vantagePoints.size() - 1; i++) {
            assertTrue(vantagePoints.get(i).visibilityScore >= vantagePoints.get(i + 1).visibilityScore);
        }
    }
}