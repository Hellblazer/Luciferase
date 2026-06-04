/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.tumbler;

import com.hellblazer.luciferase.simulation.von.Bubble;
import com.hellblazer.luciferase.simulation.von.Transport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for BubbleMigrator - bubble migration protocol.
 * These tests focus on the core migration logic without Bubble dependencies.
 */
class BubbleMigratorTest {

    private static final double TARGET_FRAME_MS = 16.0;

    private SpatialTumbler tumbler;
    private BubbleMigrator migrator;
    private UUID sourceServerId;
    private UUID targetServerId;

    @BeforeEach
    void setUp() {
        tumbler = new SpatialTumbler((byte) 5, TARGET_FRAME_MS);
        migrator = new BubbleMigrator(tumbler, Duration.ofSeconds(1), Duration.ofMillis(100), 5);

        sourceServerId = UUID.randomUUID();
        targetServerId = UUID.randomUUID();

        tumbler.registerServer(sourceServerId);
        tumbler.registerServer(targetServerId);
    }

    @Test
    void testMigrator_noFactoryConfigured() {
        // Without a factory, migration should fail gracefully
        assertThat(migrator.inFlightCount()).isEqualTo(0);
    }

    @Test
    void testMigrator_cleanupCooldowns() {
        // Should not throw
        migrator.cleanupCooldowns();
    }

    @Test
    void testMigrationResult_record() {
        var bubbleId = UUID.randomUUID();
        var targetId = UUID.randomUUID();

        var result = new BubbleMigrator.MigrationResult(
            bubbleId, targetId, true, "Success", 150
        );

        assertThat(result.bubbleId()).isEqualTo(bubbleId);
        assertThat(result.targetServerId()).isEqualTo(targetId);
        assertThat(result.success()).isTrue();
        assertThat(result.message()).isEqualTo("Success");
        assertThat(result.durationMs()).isEqualTo(150);
    }

    @Test
    void testMigrationResult_failure() {
        var bubbleId = UUID.randomUUID();
        var targetId = UUID.randomUUID();

        var result = new BubbleMigrator.MigrationResult(
            bubbleId, targetId, false, "Timeout", 0
        );

        assertThat(result.success()).isFalse();
        assertThat(result.message()).isEqualTo("Timeout");
    }

    @Test
    void testRunMigrationCycle_emptyBubbleMap() {
        // Empty bubble map should return 0 migrations
        int initiated = migrator.runMigrationCycle(Map.of());
        assertThat(initiated).isEqualTo(0);
    }

    @Test
    void testRunMigrationCycle_noOverload() {
        // Set up servers with balanced load
        var sourceMetrics = tumbler.getServerMetrics(sourceServerId);
        var targetMetrics = tumbler.getServerMetrics(targetServerId);

        sourceMetrics.recordFrameTime(14.0);
        targetMetrics.recordFrameTime(14.0);

        // Add to same region
        var region = tumbler.getRegion(new Point3f(50.0f, 50.0f, 50.0f));
        region.addServer(sourceServerId, sourceMetrics);
        region.addServer(targetServerId, targetMetrics);

        // Empty bubble map (no actual bubbles to migrate)
        var serverBubbles = new HashMap<UUID, List<com.hellblazer.luciferase.simulation.von.Bubble>>();

        int initiated = migrator.runMigrationCycle(serverBubbles);

        // No migrations - balanced and no bubbles
        assertThat(initiated).isEqualTo(0);
    }

    @Test
    void testMigrationCandidate_loadDelta() {
        var candidate = new SpatialTumbler.MigrationCandidate(
            12345L,
            sourceServerId,
            targetServerId,
            1.5,  // 150% source utilization
            0.3   // 30% target utilization
        );

        assertThat(candidate.loadDelta()).isCloseTo(1.2, within(0.01));
        assertThat(candidate.sourceServer()).isEqualTo(sourceServerId);
        assertThat(candidate.targetServer()).isEqualTo(targetServerId);
    }

    @Test
    void testTumbler_findMigrationCandidates_balanced() {
        var sourceMetrics = tumbler.getServerMetrics(sourceServerId);
        var targetMetrics = tumbler.getServerMetrics(targetServerId);

        // Similar loads - no migration needed
        for (int i = 0; i < 10; i++) {
            sourceMetrics.recordFrameTime(15.0);
            targetMetrics.recordFrameTime(14.0);
        }

        var region = tumbler.getRegion(new Point3f(50.0f, 50.0f, 50.0f));
        region.addServer(sourceServerId, sourceMetrics);
        region.addServer(targetServerId, targetMetrics);

        var candidates = tumbler.findMigrationCandidates();
        assertThat(candidates).isEmpty();
    }

    @Test
    void testTumbler_findMigrationCandidates_imbalanced() {
        var sourceMetrics = tumbler.getServerMetrics(sourceServerId);
        var targetMetrics = tumbler.getServerMetrics(targetServerId);

        // High imbalance
        for (int i = 0; i < 20; i++) {
            sourceMetrics.recordFrameTime(28.0);  // Overloaded
            targetMetrics.recordFrameTime(6.0);   // Underloaded
        }

        var region = tumbler.getRegion(new Point3f(50.0f, 50.0f, 50.0f));
        region.addServer(sourceServerId, sourceMetrics);
        region.addServer(targetServerId, targetMetrics);

        var candidates = tumbler.findMigrationCandidates();
        assertThat(candidates).hasSize(1);

        var candidate = candidates.get(0);
        assertThat(candidate.sourceServer()).isEqualTo(sourceServerId);
        assertThat(candidate.targetServer()).isEqualTo(targetServerId);
        assertThat(candidate.loadDelta()).isGreaterThan(0.5);
    }

    @Test
    void testMigrationDurationCalculation() {
        // Test that duration calculation works correctly
        long startNanos = System.nanoTime();
        // Simulate some work
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;

        // Should be at least 10ms
        assertThat(durationMs).isGreaterThanOrEqualTo(10L);
        // Should be less than 1 second
        assertThat(durationMs).isLessThan(1000L);
    }

    @Test
    void testServerMetricsUpdate() {
        var metrics = tumbler.getServerMetrics(sourceServerId);

        // Record bubble additions
        metrics.addBubble(50);
        assertThat(metrics.bubbleCount()).isEqualTo(1);
        assertThat(metrics.entityCount()).isEqualTo(50);

        // Record bubble removals
        metrics.removeBubble(50);
        assertThat(metrics.bubbleCount()).isEqualTo(0);
        assertThat(metrics.entityCount()).isEqualTo(0);
    }

    /**
     * Luciferase-7wzml.44: sourceServerId must be non-null and resolve a real ServerMetrics.
     * Before the fix, getServerForBubble() unconditionally returned null so source metrics
     * were never decremented.
     */
    @Test
    void testMigrate_sourceServerIdResolvesRealMetrics() {
        // getServerForBubble stub is deleted; sourceServerId is threaded in by the caller.
        // Verify that the sourceServerId registered in the tumbler resolves a non-null ServerMetrics.
        var sourceMetrics = tumbler.getServerMetrics(sourceServerId);
        assertThat(sourceMetrics)
            .as("sourceServerId registered with tumbler must resolve a non-null ServerMetrics")
            .isNotNull();
    }

    /**
     * Luciferase-7wzml.44: after a successful migration, source ServerMetrics bubbleCount
     * must DECREASE to 0 (verifies removeBubble is called on the source metrics).
     * entityCount is not asserted here because the source bubble has no entities at
     * migration time; see testMigrate_entityCountDecrementedAfterSuccessfulMigration for
     * the non-vacuous entityCount assertion.
     */
    @Test
    void testMigrate_bubbleCountDecrementedAfterSuccessfulMigration() throws Exception {
        // Arrange: prime source metrics with one bubble carrying 10 entities
        var sourceMetrics = tumbler.getServerMetrics(sourceServerId);
        sourceMetrics.addBubble(10);
        assertThat(sourceMetrics.bubbleCount()).isEqualTo(1);
        assertThat(sourceMetrics.entityCount()).isEqualTo(10);

        // Create source and target bubbles (no entities in sourceBubble)
        var sourceBubble = new Bubble(UUID.randomUUID(), (byte) 5, 16L, mock(Transport.class));
        var targetBubble = new Bubble(UUID.randomUUID(), (byte) 5, 16L, mock(Transport.class));
        migrator.setBubbleTransferFactory((tgtServerId, src) -> targetBubble);

        // Act
        var result = migrator.migrate(sourceBubble, sourceServerId, targetServerId)
                             .get(5, TimeUnit.SECONDS);

        assertThat(result.success())
            .as("migration must succeed to exercise metrics decrement path")
            .isTrue();

        // Assert: source bubbleCount DECREASED from 1 to 0 (removeBubble was called)
        assertThat(sourceMetrics.bubbleCount())
            .as("source ServerMetrics bubbleCount must reach 0 after migration (Luciferase-7wzml.44)")
            .isEqualTo(0);
    }

    /**
     * Luciferase-7wzml.44: after a successful migration of a NON-EMPTY bubble, source
     * ServerMetrics entityCount must GENUINELY DECREASE by the number of migrated entities.
     * This is the non-vacuous acceptance criterion for bead .44 — the old empty-bubble
     * fixture left entityCount unchanged and masked the bug.
     */
    @Test
    void testMigrate_entityCountDecrementedAfterSuccessfulMigration() throws Exception {
        // Arrange: prime source metrics with one bubble carrying 7 entities
        var sourceMetrics = tumbler.getServerMetrics(sourceServerId);
        sourceMetrics.addBubble(7);
        assertThat(sourceMetrics.entityCount()).isEqualTo(7);

        // Create source bubble with 3 actual entities so sourceBubble.entityCount() == 3
        var sourceBubble = new Bubble(UUID.randomUUID(), (byte) 5, 16L, mock(Transport.class));
        sourceBubble.addEntity("e1", new Point3f(1f, 0f, 0f), "content1");
        sourceBubble.addEntity("e2", new Point3f(2f, 0f, 0f), "content2");
        sourceBubble.addEntity("e3", new Point3f(3f, 0f, 0f), "content3");
        assertThat(sourceBubble.entityCount()).isEqualTo(3);

        var targetBubble = new Bubble(UUID.randomUUID(), (byte) 5, 16L, mock(Transport.class));
        migrator.setBubbleTransferFactory((tgtServerId, src) -> targetBubble);

        // Act
        var result = migrator.migrate(sourceBubble, sourceServerId, targetServerId)
                             .get(5, TimeUnit.SECONDS);

        assertThat(result.success())
            .as("migration must succeed to exercise entity-count decrement path")
            .isTrue();

        // Assert: sourceMetrics.entityCount() decreased by the 3 entities that were in sourceBubble.
        // removeBubble(sourceBubble.entityCount()) = removeBubble(3): 7 - 3 = 4.
        assertThat(sourceMetrics.entityCount())
            .as("source ServerMetrics entityCount must decrease by migrated entity count (Luciferase-7wzml.44)")
            .isEqualTo(4);
    }

    /**
     * Luciferase-7wzml.44: source utilization picture (bubbleCount) drops to 0 post-migration.
     * This is the acceptance criterion "source utilization DROPS" — before the fix, bubbleCount
     * on the source server grew monotonically since removeBubble was never called.
     */
    @Test
    void testMigrate_sourceUtilizationPictureDropsPostMigration() throws Exception {
        // Arrange: prime source metrics — simulates server tracking a bubble with 5 entities
        var sourceMetrics = tumbler.getServerMetrics(sourceServerId);
        sourceMetrics.addBubble(5); // 1 bubble, 5 entities on source

        int sourceInitialBubbleCount = sourceMetrics.bubbleCount();
        assertThat(sourceInitialBubbleCount).isEqualTo(1);

        // Create bubbles with mocked transports
        var sourceBubble = new Bubble(UUID.randomUUID(), (byte) 5, 16L, mock(Transport.class));
        var targetBubble = new Bubble(UUID.randomUUID(), (byte) 5, 16L, mock(Transport.class));
        migrator.setBubbleTransferFactory((tgtId, src) -> targetBubble);

        // Act
        var result = migrator.migrate(sourceBubble, sourceServerId, targetServerId)
                             .get(5, TimeUnit.SECONDS);
        assertThat(result.success()).isTrue();

        // Assert: source bubbleCount DROPS below initial value (the key metric for load-balance decisions)
        assertThat(sourceMetrics.bubbleCount())
            .as("source server load picture (bubbleCount) must DROP after migration — " +
                "before fix it was never decremented (Luciferase-7wzml.44)")
            .isLessThan(sourceInitialBubbleCount);

        // Assert: target metrics gained a bubble
        var targetMetrics = tumbler.getServerMetrics(targetServerId);
        assertThat(targetMetrics.bubbleCount())
            .as("target server must gain a bubble after migration")
            .isEqualTo(1);
    }
}
