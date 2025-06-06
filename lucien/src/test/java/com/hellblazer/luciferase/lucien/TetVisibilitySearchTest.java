package com.hellblazer.luciferase.lucien;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;
import static com.hellblazer.luciferase.lucien.TetrahedralSearchBase.SimplexAggregationStrategy;

/**
 * Unit tests for Tetrahedral Visibility search functionality
 * All test coordinates use positive values only to maintain tetrahedral constraints
 * 
 * @author hal.hildebrand
 */
public class TetVisibilitySearchTest {

    private Tetree<String> tetree;
    private final byte testLevel = 15;

    @BeforeEach
    void setUp() {
        tetree = new Tetree<>(new TreeMap<>());
        
        // Create a more complex spatial arrangement for visibility testing
        // Use coordinates that will map to different tetrahedra - all positive
        
        // Central cluster
        tetree.insert(new Point3f(200.0f, 200.0f, 200.0f), testLevel, "Center");
        tetree.insert(new Point3f(210.0f, 210.0f, 210.0f), testLevel, "CenterNear");
        
        // Line of occluders between observer and target
        tetree.insert(new Point3f(150.0f, 150.0f, 150.0f), testLevel, "Occluder1");
        tetree.insert(new Point3f(175.0f, 175.0f, 175.0f), testLevel, "Occluder2");
        tetree.insert(new Point3f(225.0f, 225.0f, 225.0f), testLevel, "Occluder3");
        
        // Visible targets from different angles
        tetree.insert(new Point3f(300.0f, 200.0f, 200.0f), testLevel, "EastTarget");
        tetree.insert(new Point3f(200.0f, 300.0f, 200.0f), testLevel, "NorthTarget");
        tetree.insert(new Point3f(200.0f, 200.0f, 300.0f), testLevel, "UpTarget");
        
        // Distant targets
        tetree.insert(new Point3f(400.0f, 400.0f, 400.0f), testLevel, "FarTarget");
        tetree.insert(new Point3f(500.0f, 500.0f, 500.0f), testLevel, "VeryFarTarget");
        
        // Off-axis targets
        tetree.insert(new Point3f(180.0f, 220.0f, 180.0f), testLevel, "OffAxis1");
        tetree.insert(new Point3f(220.0f, 180.0f, 220.0f), testLevel, "OffAxis2");
    }

    @Test
    void testBasicLineOfSight() {
        Point3f observer = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f target = new Point3f(300.0f, 200.0f, 200.0f); // EastTarget
        
        TetVisibilitySearch.TetLineOfSightResult<String> result = 
            TetVisibilitySearch.testLineOfSight(observer, target, tetree, 1.0, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
        
        assertNotNull(result);
        assertNotNull(result.occludingTetrahedra);
        assertTrue(result.totalOcclusionRatio >= 0.0f && result.totalOcclusionRatio <= 1.0f);
        assertTrue(result.distanceThroughOccluders >= 0.0f);
        
        // All occluding tetrahedra should be sorted by distance from observer
        for (int i = 0; i < result.occludingTetrahedra.size() - 1; i++) {
            assertTrue(result.occludingTetrahedra.get(i).distanceFromObserver <= 
                      result.occludingTetrahedra.get(i + 1).distanceFromObserver);
        }
    }

    @Test
    void testLineOfSightWithOcclusion() {
        Point3f observer = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f target = new Point3f(250.0f, 250.0f, 250.0f); // Through occluders
        
        TetVisibilitySearch.TetLineOfSightResult<String> result = 
            TetVisibilitySearch.testLineOfSight(observer, target, tetree, 0.1, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
        
        assertNotNull(result);
        
        // Should detect some occlusion due to positioned occluders
        if (!result.occludingTetrahedra.isEmpty()) {
            assertTrue(result.totalOcclusionRatio > 0.0f);
            assertTrue(result.distanceThroughOccluders > 0.0f);
            
            // Verify occluding tetrahedra have valid data
            for (var occluder : result.occludingTetrahedra) {
                assertNotNull(occluder.content);
                assertNotNull(occluder.tetrahedron);
                assertNotNull(occluder.tetrahedronCenter);
                assertTrue(occluder.distanceFromObserver >= 0);
                assertTrue(occluder.distanceFromTarget >= 0);
                assertNotNull(occluder.visibilityType);
                assertTrue(occluder.occlusionRatio >= 0.0f && occluder.occlusionRatio <= 1.0f);
                
                // Occluding tetrahedra should be between observer and target
                assertTrue(occluder.visibilityType == TetVisibilitySearch.VisibilityType.PARTIALLY_OCCLUDING ||
                          occluder.visibilityType == TetVisibilitySearch.VisibilityType.FULLY_OCCLUDING);
            }
        }
    }

    @Test
    void testLineOfSightSamePosition() {
        Point3f position = new Point3f(200.0f, 200.0f, 200.0f);
        
        TetVisibilitySearch.TetLineOfSightResult<String> result = 
            TetVisibilitySearch.testLineOfSight(position, position, tetree, 1.0, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
        
        assertNotNull(result);
        assertTrue(result.hasLineOfSight); // Same position should always have line of sight
        assertTrue(result.occludingTetrahedra.isEmpty());
        assertEquals(0.0f, result.totalOcclusionRatio);
        assertEquals(0.0f, result.distanceThroughOccluders);
    }

    @Test
    void testFindVisibleTetrahedra() {
        Point3f observer = new Point3f(100.0f, 100.0f, 100.0f);
        Vector3f viewDirection = new Vector3f(1.0f, 1.0f, 1.0f); // Looking towards positive diagonal
        float viewAngle = (float) Math.PI / 4.0f; // 45 degrees
        float maxDistance = 500.0f;
        
        List<TetVisibilitySearch.TetVisibilityResult<String>> visibleTetrahedra = 
            TetVisibilitySearch.findVisibleTetrahedra(observer, viewDirection, viewAngle, maxDistance, tetree, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
        
        assertNotNull(visibleTetrahedra);
        
        // All visible tetrahedra should be within viewing distance
        for (var visible : visibleTetrahedra) {
            assertTrue(visible.distanceFromObserver <= maxDistance);
            assertTrue(visible.distanceFromObserver >= 0);
            assertNotNull(visible.content);
            assertNotNull(visible.tetrahedron);
            assertNotNull(visible.tetrahedronCenter);
            assertEquals(TetVisibilitySearch.VisibilityType.VISIBLE, visible.visibilityType);
            assertEquals(0.0f, visible.occlusionRatio);
            
            // Verify positive coordinates
            assertTrue(visible.tetrahedronCenter.x >= 0);
            assertTrue(visible.tetrahedronCenter.y >= 0);
            assertTrue(visible.tetrahedronCenter.z >= 0);
        }
        
        // Results should be sorted by distance
        for (int i = 0; i < visibleTetrahedra.size() - 1; i++) {
            assertTrue(visibleTetrahedra.get(i).distanceFromObserver <= 
                      visibleTetrahedra.get(i + 1).distanceFromObserver);
        }
    }

    @Test
    void testVisibleTetrahedraNarrowCone() {
        Point3f observer = new Point3f(100.0f, 100.0f, 100.0f);
        Vector3f viewDirection = new Vector3f(1.0f, 0.0f, 0.0f); // Looking east
        float viewAngle = (float) Math.PI / 12.0f; // Very narrow 15 degrees
        float maxDistance = 400.0f;
        
        List<TetVisibilitySearch.TetVisibilityResult<String>> visibleTetrahedra = 
            TetVisibilitySearch.findVisibleTetrahedra(observer, viewDirection, viewAngle, maxDistance, tetree, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
        
        assertNotNull(visibleTetrahedra);
        
        // With narrow cone, should find fewer tetrahedra
        // EastTarget should potentially be visible, others might not be in the cone
        for (var visible : visibleTetrahedra) {
            assertTrue(visible.distanceFromObserver <= maxDistance);
            
            // Verify that the tetrahedron is roughly in the viewing direction
            Vector3f toTet = new Vector3f(
                visible.tetrahedronCenter.x - observer.x,
                visible.tetrahedronCenter.y - observer.y,
                visible.tetrahedronCenter.z - observer.z
            );
            toTet.normalize();
            
            float angle = viewDirection.angle(toTet);
            assertTrue(angle <= viewAngle * 1.1f); // Allow small tolerance for tetrahedron size
        }
    }

    @Test
    void testCalculateVisibilityStatistics() {
        Point3f observer = new Point3f(100.0f, 100.0f, 100.0f);
        float maxViewDistance = 600.0f;
        
        TetVisibilitySearch.TetVisibilityStatistics stats = 
            TetVisibilitySearch.calculateVisibilityStatistics(observer, maxViewDistance, tetree, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
        
        assertNotNull(stats);
        assertTrue(stats.totalTetrahedra >= 0);
        assertTrue(stats.visibleTetrahedra >= 0);
        assertTrue(stats.partiallyOccludedTetrahedra >= 0);
        assertTrue(stats.fullyOccludedTetrahedra >= 0);
        assertTrue(stats.tetrahedraOutOfRange >= 0);
        assertTrue(stats.totalVisibilityRatio >= 0.0f);
        assertTrue(stats.averageVisibilityRatio >= 0.0f);
        
        // Total should equal sum of all categories
        long inRange = stats.totalTetrahedra - stats.tetrahedraOutOfRange;
        assertEquals(inRange, stats.visibleTetrahedra + stats.partiallyOccludedTetrahedra + stats.fullyOccludedTetrahedra);
        
        // Percentages should be valid
        assertTrue(stats.getVisiblePercentage() >= 0 && stats.getVisiblePercentage() <= 100);
        assertTrue(stats.getPartiallyOccludedPercentage() >= 0 && stats.getPartiallyOccludedPercentage() <= 100);
        assertTrue(stats.getFullyOccludedPercentage() >= 0 && stats.getFullyOccludedPercentage() <= 100);
        assertTrue(stats.getOutOfRangePercentage() >= 0 && stats.getOutOfRangePercentage() <= 100);
        
        // Test toString
        String statsStr = stats.toString();
        assertTrue(statsStr.contains("TetVisibilityStats"));
        assertTrue(statsStr.contains("total="));
        assertTrue(statsStr.contains("visible="));
        assertTrue(statsStr.contains("partial="));
        assertTrue(statsStr.contains("occluded="));
        assertTrue(statsStr.contains("out_of_range="));
    }

    @Test
    void testFindBestVantagePoints() {
        Point3f target = new Point3f(200.0f, 200.0f, 200.0f);
        
        List<Point3f> candidatePositions = Arrays.asList(
            new Point3f(150.0f, 150.0f, 150.0f), // Close
            new Point3f(100.0f, 100.0f, 100.0f), // Clear line of sight
            new Point3f(300.0f, 300.0f, 300.0f), // Further away
            new Point3f(120.0f, 200.0f, 200.0f), // Different angle
            new Point3f(200.0f, 120.0f, 200.0f), // Another angle
            new Point3f(180.0f, 180.0f, 160.0f)  // Potentially occluded
        );
        
        List<TetVisibilitySearch.TetVantagePoint> vantagePoints = 
            TetVisibilitySearch.findBestVantagePoints(target, candidatePositions, tetree, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
        
        assertNotNull(vantagePoints);
        assertEquals(candidatePositions.size(), vantagePoints.size());
        
        // Should be sorted by visibility score (higher is better)
        for (int i = 0; i < vantagePoints.size() - 1; i++) {
            assertTrue(vantagePoints.get(i).visibilityScore >= vantagePoints.get(i + 1).visibilityScore);
        }
        
        // Verify all vantage points have valid data
        for (var vantagePoint : vantagePoints) {
            assertNotNull(vantagePoint.position);
            assertTrue(vantagePoint.distanceToTarget >= 0);
            assertTrue(vantagePoint.occlusionRatio >= 0.0f && vantagePoint.occlusionRatio <= 1.0f);
            assertTrue(vantagePoint.visibilityScore >= 0.0f);
            
            // Verify positive coordinates
            assertTrue(vantagePoint.position.x >= 0);
            assertTrue(vantagePoint.position.y >= 0);
            assertTrue(vantagePoint.position.z >= 0);
            
            // Test toString
            String vpStr = vantagePoint.toString();
            assertTrue(vpStr.contains("TetVantagePoint"));
            assertTrue(vpStr.contains("pos="));
            assertTrue(vpStr.contains("dist="));
            assertTrue(vpStr.contains("los="));
        }
    }

    @Test
    void testNegativeCoordinatesThrowException() {
        Point3f validPos = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f negativePos = new Point3f(-100.0f, 100.0f, 100.0f);
        Vector3f viewDirection = new Vector3f(1.0f, 0.0f, 0.0f);
        
        // testLineOfSight with negative observer
        assertThrows(IllegalArgumentException.class, () -> {
            TetVisibilitySearch.testLineOfSight(negativePos, validPos, tetree, 1.0, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
        });
        
        // testLineOfSight with negative target
        assertThrows(IllegalArgumentException.class, () -> {
            TetVisibilitySearch.testLineOfSight(validPos, negativePos, tetree, 1.0, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
        });
        
        // findVisibleTetrahedra with negative observer
        assertThrows(IllegalArgumentException.class, () -> {
            TetVisibilitySearch.findVisibleTetrahedra(negativePos, viewDirection, (float) Math.PI / 4.0f, 500.0f, tetree, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
        });
        
        // calculateVisibilityStatistics with negative observer
        assertThrows(IllegalArgumentException.class, () -> {
            TetVisibilitySearch.calculateVisibilityStatistics(negativePos, 500.0f, tetree, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
        });
        
        // findBestVantagePoints with negative target
        assertThrows(IllegalArgumentException.class, () -> {
            TetVisibilitySearch.findBestVantagePoints(negativePos, Arrays.asList(validPos), tetree, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
        });
        
        // findBestVantagePoints with negative candidate position
        assertThrows(IllegalArgumentException.class, () -> {
            TetVisibilitySearch.findBestVantagePoints(validPos, Arrays.asList(negativePos), tetree, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
        });
    }

    @Test
    void testInvalidParametersThrowException() {
        Point3f validPos = new Point3f(100.0f, 100.0f, 100.0f);
        Vector3f viewDirection = new Vector3f(1.0f, 0.0f, 0.0f);
        
        // Negative occlusion threshold
        assertThrows(IllegalArgumentException.class, () -> {
            TetVisibilitySearch.testLineOfSight(validPos, validPos, tetree, -1.0, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
        });
        
        // Invalid view angle
        assertThrows(IllegalArgumentException.class, () -> {
            TetVisibilitySearch.findVisibleTetrahedra(validPos, viewDirection, -0.1f, 500.0f, tetree, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            TetVisibilitySearch.findVisibleTetrahedra(validPos, viewDirection, (float) Math.PI + 0.1f, 500.0f, tetree, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
        });
        
        // Negative max view distance
        assertThrows(IllegalArgumentException.class, () -> {
            TetVisibilitySearch.findVisibleTetrahedra(validPos, viewDirection, (float) Math.PI / 4.0f, -100.0f, tetree, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            TetVisibilitySearch.calculateVisibilityStatistics(validPos, -100.0f, tetree, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
        });
    }

    @Test
    void testEmptyTetree() {
        Tetree<String> emptyTetree = new Tetree<>(new TreeMap<>());
        Point3f observer = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f target = new Point3f(200.0f, 200.0f, 200.0f);
        Vector3f viewDirection = new Vector3f(1.0f, 0.0f, 0.0f);
        
        // Line of sight in empty tetree
        TetVisibilitySearch.TetLineOfSightResult<String> losResult = 
            TetVisibilitySearch.testLineOfSight(observer, target, emptyTetree, 1.0, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
        
        assertTrue(losResult.hasLineOfSight);
        assertTrue(losResult.occludingTetrahedra.isEmpty());
        assertEquals(0.0f, losResult.totalOcclusionRatio);
        assertEquals(0.0f, losResult.distanceThroughOccluders);
        
        // Visible tetrahedra in empty tetree
        List<TetVisibilitySearch.TetVisibilityResult<String>> visibleTetrahedra = 
            TetVisibilitySearch.findVisibleTetrahedra(observer, viewDirection, (float) Math.PI / 4.0f, 500.0f, emptyTetree, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
        
        assertTrue(visibleTetrahedra.isEmpty());
        
        // Visibility statistics in empty tetree
        TetVisibilitySearch.TetVisibilityStatistics stats = 
            TetVisibilitySearch.calculateVisibilityStatistics(observer, 500.0f, emptyTetree, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
        
        assertEquals(0, stats.totalTetrahedra);
        assertEquals(0, stats.visibleTetrahedra);
        assertEquals(0, stats.partiallyOccludedTetrahedra);
        assertEquals(0, stats.fullyOccludedTetrahedra);
        assertEquals(0, stats.tetrahedraOutOfRange);
        assertEquals(0.0f, stats.totalVisibilityRatio);
        assertEquals(0.0f, stats.averageVisibilityRatio);
        
        // Best vantage points in empty tetree
        List<TetVisibilitySearch.TetVantagePoint> vantagePoints = 
            TetVisibilitySearch.findBestVantagePoints(target, Arrays.asList(observer), emptyTetree, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
        
        assertEquals(1, vantagePoints.size());
        assertTrue(vantagePoints.get(0).hasLineOfSight);
        assertEquals(0.0f, vantagePoints.get(0).occlusionRatio);
    }

    @Test
    void testSimplexAggregationStrategies() {
        Point3f observer = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f target = new Point3f(250.0f, 250.0f, 250.0f);
        
        // Test different aggregation strategies
        TetVisibilitySearch.TetLineOfSightResult<String> representativeResult = 
            TetVisibilitySearch.testLineOfSight(observer, target, tetree, 1.0, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
        
        TetVisibilitySearch.TetLineOfSightResult<String> allResult = 
            TetVisibilitySearch.testLineOfSight(observer, target, tetree, 1.0, SimplexAggregationStrategy.ALL_SIMPLICIES);
        
        assertNotNull(representativeResult);
        assertNotNull(allResult);
        
        // ALL_SIMPLICIES should potentially find more occluders
        assertTrue(allResult.occludingTetrahedra.size() >= representativeResult.occludingTetrahedra.size());
        
        // Both should maintain valid occlusion ratios
        assertTrue(representativeResult.totalOcclusionRatio >= 0.0f && representativeResult.totalOcclusionRatio <= 1.0f);
        assertTrue(allResult.totalOcclusionRatio >= 0.0f && allResult.totalOcclusionRatio <= 1.0f);
    }

    @Test
    void testVisibilityTypes() {
        Point3f observer = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f target = new Point3f(300.0f, 300.0f, 300.0f);
        
        TetVisibilitySearch.TetLineOfSightResult<String> result = 
            TetVisibilitySearch.testLineOfSight(observer, target, tetree, 0.1, SimplexAggregationStrategy.ALL_SIMPLICIES);
        
        // Check that visibility types are properly classified
        for (var occluder : result.occludingTetrahedra) {
            TetVisibilitySearch.VisibilityType type = occluder.visibilityType;
            
            // Should be one of the occluding types
            assertTrue(type == TetVisibilitySearch.VisibilityType.PARTIALLY_OCCLUDING ||
                      type == TetVisibilitySearch.VisibilityType.FULLY_OCCLUDING ||
                      type == TetVisibilitySearch.VisibilityType.BEFORE_OBSERVER ||
                      type == TetVisibilitySearch.VisibilityType.BEHIND_TARGET);
            
            // If it's an occluding type, occlusion ratio should be > 0
            if (type == TetVisibilitySearch.VisibilityType.PARTIALLY_OCCLUDING ||
                type == TetVisibilitySearch.VisibilityType.FULLY_OCCLUDING) {
                assertTrue(occluder.occlusionRatio > 0.0f);
            }
        }
    }

    @Test
    void testDistanceCalculations() {
        Point3f observer = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f target = new Point3f(200.0f, 200.0f, 200.0f);
        
        TetVisibilitySearch.TetLineOfSightResult<String> result = 
            TetVisibilitySearch.testLineOfSight(observer, target, tetree, 1.0, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
        
        float observerTargetDistance = (float) Math.sqrt(
            Math.pow(target.x - observer.x, 2) + 
            Math.pow(target.y - observer.y, 2) + 
            Math.pow(target.z - observer.z, 2)
        );
        
        // Verify distance calculations for each occluding tetrahedron
        for (var occluder : result.occludingTetrahedra) {
            Point3f tetCenter = occluder.tetrahedronCenter;
            
            // Calculate expected distances
            float expectedDistanceFromObserver = (float) Math.sqrt(
                Math.pow(tetCenter.x - observer.x, 2) + 
                Math.pow(tetCenter.y - observer.y, 2) + 
                Math.pow(tetCenter.z - observer.z, 2)
            );
            
            float expectedDistanceFromTarget = (float) Math.sqrt(
                Math.pow(tetCenter.x - target.x, 2) + 
                Math.pow(tetCenter.y - target.y, 2) + 
                Math.pow(tetCenter.z - target.z, 2)
            );
            
            // Verify distances are calculated correctly
            assertEquals(expectedDistanceFromObserver, occluder.distanceFromObserver, 0.001f);
            assertEquals(expectedDistanceFromTarget, occluder.distanceFromTarget, 0.001f);
            
            // Occluding tetrahedra should be between observer and target (roughly)
            assertTrue(occluder.distanceFromObserver < observerTargetDistance);
        }
    }
}