/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.tumbler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for BubbleMigrator - bubble migration protocol.
 * These tests focus on the core migration logic without VonBubble dependencies.
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
        var serverBubbles = new HashMap<UUID, List<com.hellblazer.luciferase.simulation.von.VonBubble>>();

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
}
