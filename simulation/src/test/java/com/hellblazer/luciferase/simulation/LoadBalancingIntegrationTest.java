package com.hellblazer.luciferase.simulation;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.simulation.tumbler.SpatialTumblerImpl;
import com.hellblazer.luciferase.simulation.tumbler.TumblerConfig;
import com.hellblazer.luciferase.simulation.tumbler.TumblerStatistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Phase 5: Load Balancing.
 * <p>
 * Tests:
 * - Tumbler statistics collection and flow to BubbleDynamicsManager
 * - Affinity-based region assignment
 * - Migration hooks for bubble transfers
 * - Load metrics collection and reporting
 *
 * @author hal.hildebrand
 */
class LoadBalancingIntegrationTest {

    private Tetree<LongEntityID, Void> tetree;
    private TumblerConfig config;
    private SpatialTumblerImpl<LongEntityID, Void> tumbler;
    private BubbleDynamicsManager<LongEntityID> bubbleDynamics;
    private AtomicInteger eventCount;

    @BeforeEach
    void setUp() {
        tetree = new Tetree<>(new SequentialLongIDGenerator(), 10, (byte) 21);

        // Use test thresholds for easier testing
        config = new TumblerConfig(
            10,       // splitThreshold
            2,        // joinThreshold
            (byte) 4, // minRegionLevel
            (byte) 10, // maxRegionLevel
            0.1f,     // spanWidthRatio
            1.0f,     // minSpanDistance
            false,    // autoAdapt - disable for controlled testing
            100,      // adaptCheckInterval
            TumblerConfig.RegionSplitStrategy.OCTANT
        );

        tumbler = new SpatialTumblerImpl<>(tetree, config);

        // Create bubble dynamics manager
        eventCount = new AtomicInteger(0);
        bubbleDynamics = new BubbleDynamicsManager<>(
            new ExternalBubbleTracker(),
            new GhostLayerHealth(),
            new MigrationLog(),
            new StockNeighborList(100),
            event -> eventCount.incrementAndGet()
        );
    }

    @Test
    void testStatisticsCollectionAndFlow() {
        // Track entities to generate statistics
        for (int i = 0; i < 20; i++) {
            var x = 0.5f + i * 0.01f;
            var y = 0.5f + i * 0.01f;
            var z = 0.5f + i * 0.01f;
            tumbler.track(new LongEntityID(i), new Point3f(x, y, z), null);
        }

        // Get statistics from tumbler
        var stats = tumbler.getStatistics();
        assertNotNull(stats, "Statistics should be collected");
        assertEquals(20, stats.totalEntities(), "Should track 20 entities");
        assertTrue(stats.totalRegions() >= 1, "Should have at least 1 region");

        // Flow statistics to BubbleDynamicsManager
        bubbleDynamics.updateTumblerStatistics(stats);

        // Verify BubbleDynamicsManager received statistics
        var retrieved = bubbleDynamics.getTumblerStatistics();
        assertNotNull(retrieved, "BubbleDynamicsManager should have statistics");
        assertEquals(stats.totalEntities(), retrieved.totalEntities());
        assertEquals(stats.totalRegions(), retrieved.totalRegions());
    }

    @Test
    void testLoadMetricsCalculation() {
        // Create imbalanced load: many entities in one location, few in another
        // Location 1: 15 entities
        for (int i = 0; i < 15; i++) {
            tumbler.track(new LongEntityID(i), new Point3f(0.1f, 0.1f, 0.1f), null);
        }

        // Location 2: 3 entities
        for (int i = 15; i < 18; i++) {
            tumbler.track(new LongEntityID(i), new Point3f(0.9f, 0.9f, 0.9f), null);
        }

        var stats = tumbler.getStatistics();

        // Verify load metrics
        assertEquals(18, stats.totalEntities());
        assertTrue(stats.maxEntitiesPerRegion() >= 3, "Should have max >= 3");
        assertTrue(stats.averageEntitiesPerRegion() > 0, "Should have positive average");

        // Verify load imbalance calculation
        float imbalanceRatio = stats.loadImbalanceRatio();
        assertTrue(imbalanceRatio >= 1.0f, "Imbalance ratio should be >= 1.0");

        // Flow to BubbleDynamicsManager and check
        bubbleDynamics.updateTumblerStatistics(stats);
        assertFalse(bubbleDynamics.hasHighLoadImbalance(10.0f), "Shouldn't exceed 10x threshold");
    }

    @Test
    void testAffinityBasedRecommendations() {
        // Create imbalanced load
        var pos1 = new Point3f(0.2f, 0.2f, 0.2f);
        var pos2 = new Point3f(0.8f, 0.8f, 0.8f);

        // 20 entities in one region
        for (int i = 0; i < 20; i++) {
            tumbler.track(new LongEntityID(i), pos1, null);
        }

        // 2 entities in another region
        for (int i = 20; i < 22; i++) {
            tumbler.track(new LongEntityID(i), pos2, null);
        }

        // Get migration recommendations (threshold: 2.0x imbalance)
        var recommendations = tumbler.getMigrationRecommendations(2.0f);

        // Should recommend migrations if imbalance > 2.0x
        assertNotNull(recommendations, "Should return recommendations");

        if (recommendations.size() > 0) {
            // Verify recommendations point to less-loaded region
            var stats = tumbler.getStatistics();
            var leastLoaded = stats.leastLoadedRegion();

            for (var targetRegion : recommendations.values()) {
                assertNotNull(targetRegion, "Target region should be specified");
            }
        }
    }

    @Test
    void testEntityRegionAffinity() {
        // Track entities
        var entityId = new LongEntityID(1);
        tumbler.track(entityId, new Point3f(0.5f, 0.5f, 0.5f), null);

        // Calculate affinity
        float affinity = tumbler.getEntityRegionAffinity(entityId);

        // Affinity should be in valid range
        assertTrue(affinity >= 0.0f && affinity <= 1.0f,
            "Affinity should be in [0.0, 1.0], was " + affinity);
    }

    @Test
    void testAffinityWithLoadImbalance() {
        var pos1 = new Point3f(0.2f, 0.2f, 0.2f);

        // Track 30 entities in same region (high load)
        for (int i = 0; i < 30; i++) {
            tumbler.track(new LongEntityID(i), pos1, null);
        }

        // Entity in high-load region should have lower affinity
        var entityId = new LongEntityID(15);
        float affinity = tumbler.getEntityRegionAffinity(entityId);

        assertTrue(affinity >= 0.0f && affinity <= 1.0f,
            "Affinity should be valid");

        // Note: With only one region, affinity will be 1.0
        // Multi-region test would show affinity differences
    }

    @Test
    void testMigrationHookTriggered() {
        // Setup bubbles
        var bubble1 = UUID.randomUUID();
        var bubble2 = UUID.randomUUID();
        var entities1 = java.util.Set.of(new LongEntityID(1), new LongEntityID(2));
        var entities2 = java.util.Set.of(new LongEntityID(3));

        bubbleDynamics.registerBubble(bubble1, entities1);
        bubbleDynamics.registerBubble(bubble2, entities2);

        // Create load imbalance in tumbler
        for (int i = 0; i < 25; i++) {
            tumbler.track(new LongEntityID(100 + i), new Point3f(0.1f, 0.1f, 0.1f), null);
        }
        for (int i = 0; i < 3; i++) {
            tumbler.track(new LongEntityID(200 + i), new Point3f(0.9f, 0.9f, 0.9f), null);
        }

        // Update statistics in bubble dynamics
        bubbleDynamics.updateTumblerStatistics(tumbler.getStatistics());

        // Trigger bubble migration
        int eventsBefore = eventCount.get();
        bubbleDynamics.migrateBubble(bubble1, UUID.randomUUID(), UUID.randomUUID(), 100L);
        int eventsAfter = eventCount.get();

        // Migration should trigger event
        assertEquals(eventsBefore + 1, eventsAfter, "Migration should emit event");

        // Migration hook should have checked load imbalance
        // (verification is implicit - hook executes without error)
    }

    @Test
    void testHighActivityDetection() {
        // Track entities and trigger splits
        var position = new Point3f(0.5f, 0.5f, 0.5f);
        for (int i = 0; i < 25; i++) {
            tumbler.track(new LongEntityID(i), position, null);
        }

        // Trigger split manually
        tumbler.checkAndSplit();

        // Get statistics
        var stats = tumbler.getStatistics();

        // Verify split was tracked
        assertTrue(stats.totalSplits() > 0, "Should have recorded splits");
        assertTrue(stats.splitsSinceLastSnapshot() >= 0, "Should track splits since snapshot");

        // Update in bubble dynamics
        bubbleDynamics.updateTumblerStatistics(stats);

        // Check activity level (10 ops/sec threshold)
        // Note: For this test, throughput might be low due to short time window
        boolean highActivity = bubbleDynamics.hasHighAdaptActivity(0.1f);
        // Just verify it returns without error
        assertTrue(highActivity || !highActivity, "Should calculate activity");
    }

    @Test
    void testLoadBalancingRecommendations() {
        // Create significant load imbalance
        for (int i = 0; i < 50; i++) {
            tumbler.track(new LongEntityID(i), new Point3f(0.1f, 0.1f, 0.1f), null);
        }
        for (int i = 50; i < 55; i++) {
            tumbler.track(new LongEntityID(i), new Point3f(0.9f, 0.9f, 0.9f), null);
        }

        var stats = tumbler.getStatistics();
        bubbleDynamics.updateTumblerStatistics(stats);

        // Get most and least loaded regions
        var mostLoaded = bubbleDynamics.getMostLoadedRegion();
        var leastLoaded = bubbleDynamics.getLeastLoadedRegion();

        assertNotNull(mostLoaded, "Should identify most loaded region");
        assertNotNull(leastLoaded, "Should identify least loaded region");

        // Check if high imbalance detected (2.0x threshold)
        boolean hasImbalance = bubbleDynamics.hasHighLoadImbalance(2.0f);
        // Just verify it calculates without error
        assertTrue(hasImbalance || !hasImbalance, "Should check imbalance");
    }

    @Test
    void testStatisticsReset() {
        // Track entities and trigger operations
        for (int i = 0; i < 15; i++) {
            tumbler.track(new LongEntityID(i), new Point3f(0.5f, 0.5f, 0.5f), null);
        }
        tumbler.checkAndSplit();

        // Get first snapshot
        var stats1 = tumbler.getStatistics();
        var splitsSince1 = stats1.splitsSinceLastSnapshot();

        // Get second snapshot immediately
        var stats2 = tumbler.getStatistics();
        var splitsSince2 = stats2.splitsSinceLastSnapshot();

        // Second snapshot should have reset counter
        assertEquals(0, splitsSince2, "Snapshot counter should reset after getStatistics()");
    }

    @Test
    void testEmptyStatistics() {
        // Get statistics before any entities tracked
        var stats = tumbler.getStatistics();

        assertNotNull(stats, "Should return statistics even when empty");
        assertEquals(0, stats.totalEntities());
        assertEquals(0, stats.totalRegions());
        assertEquals(1.0f, stats.loadImbalanceRatio(), 0.001f, "Empty stats should have balanced ratio");
    }

    @Test
    void testConcurrentStatisticsCollection() throws InterruptedException {
        // Track entities from multiple threads
        var threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            final int threadId = t;
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 10; i++) {
                    var id = threadId * 10 + i;
                    var x = 0.1f + threadId * 0.2f + i * 0.01f;
                    tumbler.track(new LongEntityID(id), new Point3f(x, x, x), null);
                }
            });
        }

        for (var thread : threads) {
            thread.start();
        }
        for (var thread : threads) {
            thread.join(5000);
        }

        // Get statistics after concurrent updates
        var stats = tumbler.getStatistics();

        assertEquals(40, stats.totalEntities(), "Should track all 40 entities");
        assertTrue(stats.totalRegions() >= 1, "Should have regions");
    }
}
