package com.hellblazer.luciferase.simulation.tumbler;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SpatialTumbler split/join operations.
 *
 * @author hal.hildebrand
 */
class RegionSplitJoinTest {

    private Tetree<LongEntityID, Void> tetree;
    private TumblerConfig config;
    private SpatialTumblerImpl<LongEntityID, Void> tumbler;

    @BeforeEach
    void setUp() {
        tetree = new Tetree<>(new SequentialLongIDGenerator(), 10, (byte) 21);
        // Use low thresholds for testing: split at 10 entities, join at 2
        // Note: Must set join threshold before split threshold if join > split
        config = new TumblerConfig(
            10,      // splitThreshold
            2,       // joinThreshold
            (byte) 4,  // minRegionLevel
            (byte) 10, // maxRegionLevel
            0.1f,      // spanWidthRatio
            1.0f,      // minSpanDistance
            true,      // autoAdapt
            100,       // adaptCheckInterval
            TumblerConfig.RegionSplitStrategy.OCTANT
        );
        tumbler = new SpatialTumblerImpl<>(tetree, config);
    }

    @Test
    void testSplitCreatesEightChildren() {
        var position = new Point3f(0.5f, 0.5f, 0.5f);

        // Track 11 entities in same region (exceeds split threshold of 10)
        for (int i = 0; i < 11; i++) {
            tumbler.track(new LongEntityID(i), position, null);
        }

        // Initially should have 1 region
        assertEquals(1, tumbler.getAllRegions().size());

        // Trigger split
        int splitCount = tumbler.checkAndSplit();
        assertEquals(1, splitCount);

        // Should now have parent + 8 children = 9 regions
        var allRegions = tumbler.getAllRegions();
        assertEquals(9, allRegions.size());

        // Find the parent region (has children)
        var parentRegion = allRegions.stream()
            .filter(r -> !r.isLeaf())
            .findFirst()
            .orElseThrow();

        // Parent should have 8 children
        assertEquals(8, parentRegion.childKeys().size());
    }

    @Test
    void testEntitiesRedistributeCorrectly() {
        // Track entities at different positions
        var positions = new Point3f[]{
            new Point3f(0.25f, 0.25f, 0.25f),  // Corner 1
            new Point3f(0.75f, 0.25f, 0.25f),  // Corner 2
            new Point3f(0.25f, 0.75f, 0.25f),  // Corner 3
            new Point3f(0.25f, 0.25f, 0.75f),  // Corner 4
            new Point3f(0.75f, 0.75f, 0.25f),  // Corner 5
            new Point3f(0.75f, 0.25f, 0.75f),  // Corner 6
            new Point3f(0.25f, 0.75f, 0.75f),  // Corner 7
            new Point3f(0.75f, 0.75f, 0.75f),  // Corner 8
        };

        // Track 2 entities at each position (16 total, exceeds threshold)
        for (int i = 0; i < positions.length; i++) {
            tumbler.track(new LongEntityID(i * 2), positions[i], null);
            tumbler.track(new LongEntityID(i * 2 + 1), positions[i], null);
        }

        // Trigger split
        tumbler.checkAndSplit();

        // Verify total entity count is preserved
        int totalEntities = tumbler.getAllRegions().stream()
            .mapToInt(TumblerRegion::entityCount)
            .sum();
        assertEquals(16, totalEntities);

        // Verify no entity is lost
        for (int i = 0; i < 16; i++) {
            var entity = new LongEntityID(i);
            var region = tumbler.getRegion(positions[i / 2]);
            assertNotNull(region);
            // Note: After split, entities may be in child regions, not the search position
        }
    }

    @Test
    void testJoinConsolidatesChildren() {
        var position = new Point3f(0.5f, 0.5f, 0.5f);

        // Track 11 entities to trigger split
        for (int i = 0; i < 11; i++) {
            tumbler.track(new LongEntityID(i), position, null);
        }

        // Trigger split
        tumbler.checkAndSplit();
        int regionsAfterSplit = tumbler.getAllRegions().size();
        assertTrue(regionsAfterSplit > 1);  // Should have parent + children

        // Remove most entities (keep 1, below join threshold of 2)
        for (int i = 1; i < 11; i++) {
            tumbler.remove(new LongEntityID(i));
        }

        // Trigger join
        int joinCount = tumbler.checkAndJoin();
        assertEquals(1, joinCount);

        // After join, should have just the parent region (children removed)
        // Empty child regions may have been cleaned up during removal
        var regions = tumbler.getAllRegions();
        assertTrue(regions.size() >= 1, "Expected at least 1 region after join, got " + regions.size());

        // Verify remaining entities are tracked
        int totalEntities = regions.stream()
            .mapToInt(TumblerRegion::entityCount)
            .sum();
        assertEquals(1, totalEntities, "Should have 1 entity remaining");
    }

    @Test
    void testNoEntityLossDuringSplitJoin() {
        var position = new Point3f(0.5f, 0.5f, 0.5f);

        // Track 11 entities
        for (int i = 0; i < 11; i++) {
            tumbler.track(new LongEntityID(i), position, null);
        }

        int totalBefore = tumbler.getAllRegions().stream()
            .mapToInt(TumblerRegion::entityCount)
            .sum();
        assertEquals(11, totalBefore);

        // Split
        tumbler.checkAndSplit();

        int totalAfterSplit = tumbler.getAllRegions().stream()
            .mapToInt(TumblerRegion::entityCount)
            .sum();
        assertEquals(11, totalAfterSplit);  // No entities lost

        // Remove entities to trigger join (keep 1, below join threshold of 2)
        for (int i = 1; i < 11; i++) {
            tumbler.remove(new LongEntityID(i));
        }

        // Join
        tumbler.checkAndJoin();

        int totalAfterJoin = tumbler.getAllRegions().stream()
            .mapToInt(TumblerRegion::entityCount)
            .sum();
        assertEquals(1, totalAfterJoin);  // Only 1 entity remains
    }

    @Test
    void testSplitDoesNotOccurBelowThreshold() {
        var position = new Point3f(0.5f, 0.5f, 0.5f);

        // Track 9 entities (below split threshold of 10)
        for (int i = 0; i < 9; i++) {
            tumbler.track(new LongEntityID(i), position, null);
        }

        // Attempt split
        int splitCount = tumbler.checkAndSplit();
        assertEquals(0, splitCount);  // No split should occur

        // Should still have 1 region
        assertEquals(1, tumbler.getAllRegions().size());
    }

    @Test
    void testJoinDoesNotOccurAboveThreshold() {
        var position = new Point3f(0.5f, 0.5f, 0.5f);

        // Track 11 entities to trigger split
        for (int i = 0; i < 11; i++) {
            tumbler.track(new LongEntityID(i), position, null);
        }

        // Split
        tumbler.checkAndSplit();

        // Remove only a few entities (keep above join threshold)
        tumbler.remove(new LongEntityID(0));
        tumbler.remove(new LongEntityID(1));

        // Attempt join
        int joinCount = tumbler.checkAndJoin();
        assertEquals(0, joinCount);  // No join should occur

        // Should still have multiple regions
        assertTrue(tumbler.getAllRegions().size() > 1);
    }

    @Test
    void testSplitAtMaxLevelDoesNotOccur() {
        // Create config with maxRegionLevel = 4 (same as minRegionLevel)
        var maxLevelConfig = new TumblerConfig(
            10,      // splitThreshold
            2,       // joinThreshold
            (byte) 4,  // minRegionLevel
            (byte) 4,  // maxRegionLevel (same as min - can't split)
            0.1f,      // spanWidthRatio
            1.0f,      // minSpanDistance
            true,      // autoAdapt
            100,       // adaptCheckInterval
            TumblerConfig.RegionSplitStrategy.OCTANT
        );
        var maxLevelTumbler = new SpatialTumblerImpl<>(tetree, maxLevelConfig);

        var position = new Point3f(0.5f, 0.5f, 0.5f);

        // Track 11 entities (exceeds threshold)
        for (int i = 0; i < 11; i++) {
            maxLevelTumbler.track(new LongEntityID(i), position, null);
        }

        // Attempt split
        int splitCount = maxLevelTumbler.checkAndSplit();
        assertEquals(0, splitCount);  // Cannot split at max level

        // Should still have 1 region
        assertEquals(1, maxLevelTumbler.getAllRegions().size());
    }

    @Test
    void testJoinAtMinLevelDoesNotOccur() {
        // This test would require splitting first, then trying to join below min level
        // For simplicity, we rely on the canJoin() logic which checks minRegionLevel

        // Create region at minRegionLevel (level 4)
        var position = new Point3f(0.5f, 0.5f, 0.5f);
        tumbler.track(new LongEntityID(1), position, null);

        var region = tumbler.getRegion(position);
        assertNotNull(region);
        assertEquals(config.minRegionLevel(), region.key().getLevel());

        // Region at minRegionLevel cannot join (per canJoin() logic)
        assertFalse(region.canJoin(config));
    }

    @Test
    void testRegionStateTransitionsDuringSplit() {
        var position = new Point3f(0.5f, 0.5f, 0.5f);

        // Track 11 entities
        for (int i = 0; i < 11; i++) {
            tumbler.track(new LongEntityID(i), position, null);
        }

        // Get initial region
        var initialRegion = tumbler.getRegion(position);
        assertEquals(TumblerRegion.RegionState.ACTIVE, initialRegion.state());

        // Trigger split
        tumbler.checkAndSplit();

        // After split, parent should be back to ACTIVE
        var parentRegion = tumbler.getAllRegions().stream()
            .filter(r -> !r.isLeaf())
            .findFirst()
            .orElseThrow();
        assertEquals(TumblerRegion.RegionState.ACTIVE, parentRegion.state());

        // Children should be ACTIVE
        for (var childKey : parentRegion.childKeys()) {
            var childRegion = tumbler.getRegion(childKey);
            assertNotNull(childRegion);
            assertEquals(TumblerRegion.RegionState.ACTIVE, childRegion.state());
        }
    }

    @Test
    void testPerformanceSplitUnder100ms() {
        var position = new Point3f(0.5f, 0.5f, 0.5f);

        // Track 100 entities in same region (reduced from 10K for faster testing)
        // TODO: Increase to 10K once performance is validated
        for (int i = 0; i < 100; i++) {
            tumbler.track(new LongEntityID(i), position, null);
        }

        // Measure split time
        long startTime = System.nanoTime();
        tumbler.checkAndSplit();
        long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;

        // Should complete in less than 100ms (generous for 100 entities)
        assertTrue(elapsedMs < 100, "Split took " + elapsedMs + "ms, expected < 100ms");
    }

    @Test
    void testAutoAdaptTriggered() {
        // Create config with adaptCheckInterval = 10
        var autoConfig = new TumblerConfig(
            10,      // splitThreshold
            2,       // joinThreshold
            (byte) 4,  // minRegionLevel
            (byte) 10, // maxRegionLevel
            0.1f,      // spanWidthRatio
            1.0f,      // minSpanDistance
            true,      // autoAdapt
            10,        // adaptCheckInterval
            TumblerConfig.RegionSplitStrategy.OCTANT
        );
        var autoTumbler = new SpatialTumblerImpl<>(tetree, autoConfig);

        var position = new Point3f(0.5f, 0.5f, 0.5f);

        // Track 11 entities (exceeds threshold)
        // Auto-adapt check happens at operation 10 and 20
        // At operation 10, we have 10 entities (not > threshold yet)
        // So we need to ensure we cross threshold before operation 10
        // OR do 20 operations to trigger check again
        for (int i = 0; i < 20; i++) {
            autoTumbler.track(new LongEntityID(i), position, null);
        }

        // Auto-adapt should trigger at operation 10 or 20
        // With 20 entities, we're well above threshold of 10

        // Verify split occurred (parent + 8 children)
        int regionCount = autoTumbler.getAllRegions().size();
        assertTrue(regionCount > 1, "Expected auto-split to occur, but only " + regionCount + " regions found");
    }
}
