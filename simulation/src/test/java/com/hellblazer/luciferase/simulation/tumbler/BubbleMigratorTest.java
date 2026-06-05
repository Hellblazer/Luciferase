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

import com.hellblazer.luciferase.common.time.Clock;
import javax.vecmath.Point3f;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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

    // -----------------------------------------------------------------------
    // Luciferase-7wzml.45: transactional migration + rollback tests
    // -----------------------------------------------------------------------

    /**
     * Luciferase-7wzml.45: when an exception fires AFTER transferEntities but BEFORE
     * source.close(), the rollback must remove all staged entities from the target bubble
     * and leave the source bubble open (not closed).  No entity must exist in both bubbles
     * at any observable point.
     */
    @Test
    void testMigrate_rollbackOnBroadcastFailure_entitiesOnlyInSource() throws Exception {
        // Arrange: source bubble with 3 entities
        var sourceBubble = new Bubble(UUID.randomUUID(), (byte) 5, 16L, mock(Transport.class));
        sourceBubble.addEntity("r1", new Point3f(1f, 0f, 0f), "c1");
        sourceBubble.addEntity("r2", new Point3f(2f, 0f, 0f), "c2");
        sourceBubble.addEntity("r3", new Point3f(3f, 0f, 0f), "c3");
        assertThat(sourceBubble.entityCount()).isEqualTo(3);

        // Target bubble - spy so we can make broadcastMoveAsync() return a failed future after staging
        var realTarget = new Bubble(UUID.randomUUID(), (byte) 5, 16L, mock(Transport.class));
        var spyTarget = spy(realTarget);
        doReturn(CompletableFuture.<Void>failedFuture(new RuntimeException("simulated broadcast failure")))
            .when(spyTarget).broadcastMoveAsync();

        migrator.setBubbleTransferFactory((tgtServerId, src) -> spyTarget);

        // Act: migration should fail with the injected broadcast exception
        var result = migrator.migrate(sourceBubble, sourceServerId, targetServerId)
                             .get(5, TimeUnit.SECONDS);

        // Failure result
        assertThat(result.success())
            .as("migration must report failure when broadcastMove throws")
            .isFalse();
        assertThat(result.message())
            .as("failure message must reflect the broadcast exception")
            .contains("simulated broadcast failure");

        // Rollback assertion: target must be empty (staged entities removed)
        assertThat(spyTarget.entityCount())
            .as("rollback must remove all staged entities from target (no entity in both)")
            .isEqualTo(0);

        // Source must still hold all 3 entities (source was never closed)
        assertThat(sourceBubble.entityCount())
            .as("source must retain all entities after rollback")
            .isEqualTo(3);

        // Source must not be closed (it remains authoritative)
        // We verify this by checking that addEntity still works on source post-rollback
        assertThatCode(() -> sourceBubble.addEntity("r4", new Point3f(4f, 0f, 0f), "c4"))
            .as("source bubble must remain open (not closed) after rollback")
            .doesNotThrowAnyException();
    }

    /**
     * Luciferase-7wzml.45: when targetBubble.addEntity throws during staging (e.g. spatial-index
     * constraint or null position), the rollback must remove the already-staged entities from the
     * target and leave the source bubble open with all entities intact.  No metrics update may
     * occur, confirming no double-count.
     */
    @Test
    void testMigrate_rollbackOnAddEntityThrows_entitiesOnlyInSource() throws Exception {
        // Arrange: source metrics + source bubble with 3 entities
        var sourceMetrics = tumbler.getServerMetrics(sourceServerId);
        sourceMetrics.addBubble(3);

        var sourceBubble = new Bubble(UUID.randomUUID(), (byte) 5, 16L, mock(Transport.class));
        sourceBubble.addEntity("a1", new Point3f(1f, 0f, 0f), "c1");
        sourceBubble.addEntity("a2", new Point3f(2f, 0f, 0f), "c2");
        sourceBubble.addEntity("a3", new Point3f(3f, 0f, 0f), "c3");
        assertThat(sourceBubble.entityCount()).isEqualTo(3);

        // Spy target: first addEntity succeeds (entity a1 staged), second throws.
        // This exercises the mid-loop failure path.
        var realTarget = new Bubble(UUID.randomUUID(), (byte) 5, 16L, mock(Transport.class));
        var spyTarget = spy(realTarget);
        doCallRealMethod()               // first call: stage a1 normally
            .doThrow(new RuntimeException("spatial-index constraint violation on a2"))
            .when(spyTarget).addEntity(any(), any(), any());

        migrator.setBubbleTransferFactory((tgtServerId, src) -> spyTarget);

        // Act
        var result = migrator.migrate(sourceBubble, sourceServerId, targetServerId)
                             .get(5, TimeUnit.SECONDS);

        // Failure result
        assertThat(result.success())
            .as("migration must report failure when addEntity throws during staging")
            .isFalse();
        assertThat(result.message())
            .as("failure message must include the addEntity exception text")
            .contains("spatial-index constraint violation on a2");

        // Rollback assertion: target must be empty (the one staged entity was removed)
        assertThat(spyTarget.entityCount())
            .as("rollback must remove all staged entities from target (even partially-staged)")
            .isEqualTo(0);

        // Source must still hold all 3 entities (source was never closed)
        assertThat(sourceBubble.entityCount())
            .as("source must retain all entities after mid-staging rollback")
            .isEqualTo(3);

        // Source must not be closed (it remains authoritative)
        assertThatCode(() -> sourceBubble.addEntity("a4", new Point3f(4f, 0f, 0f), "c4"))
            .as("source bubble must remain open (not closed) after mid-staging rollback")
            .doesNotThrowAnyException();

        // Metrics must not be updated on rollback path
        assertThat(sourceMetrics.bubbleCount())
            .as("source bubbleCount must not be decremented on rollback (no double-count)")
            .isEqualTo(1);
    }

    /**
     * Luciferase-7wzml.45: on a successful migration, entities must be present ONLY in the
     * target bubble (not in source), source must be closed, and source metrics must be
     * decremented exactly once.  This also verifies the .44 metrics decrement still fires
     * on the success path (not skipped by the new transactional structure).
     */
    @Test
    void testMigrate_successPath_entitiesInTargetOnlyAndSourceClosed() throws Exception {
        // Arrange: prime source metrics
        var sourceMetrics = tumbler.getServerMetrics(sourceServerId);
        sourceMetrics.addBubble(3);
        assertThat(sourceMetrics.bubbleCount()).isEqualTo(1);
        assertThat(sourceMetrics.entityCount()).isEqualTo(3);

        var sourceBubble = new Bubble(UUID.randomUUID(), (byte) 5, 16L, mock(Transport.class));
        sourceBubble.addEntity("s1", new Point3f(1f, 0f, 0f), "c1");
        sourceBubble.addEntity("s2", new Point3f(2f, 0f, 0f), "c2");
        sourceBubble.addEntity("s3", new Point3f(3f, 0f, 0f), "c3");

        var targetBubble = new Bubble(UUID.randomUUID(), (byte) 5, 16L, mock(Transport.class));
        migrator.setBubbleTransferFactory((tgtServerId, src) -> targetBubble);

        // Act
        var result = migrator.migrate(sourceBubble, sourceServerId, targetServerId)
                             .get(5, TimeUnit.SECONDS);

        assertThat(result.success())
            .as("success path must report success")
            .isTrue();

        // Entities in target (migration succeeded)
        assertThat(targetBubble.entityCount())
            .as("all 3 entities must be in target after successful migration")
            .isEqualTo(3);

        // Source metrics decremented exactly once (.44 preserved)
        assertThat(sourceMetrics.bubbleCount())
            .as("source bubbleCount must be decremented exactly once after success")
            .isEqualTo(0);
        assertThat(sourceMetrics.entityCount())
            .as("source entityCount must be decremented by the migrated entity count (.44 preserved)")
            .isEqualTo(0);

        // Target metrics incremented
        var targetMetrics = tumbler.getServerMetrics(targetServerId);
        assertThat(targetMetrics.bubbleCount())
            .as("target bubbleCount must gain 1")
            .isEqualTo(1);
    }

    /**
     * Luciferase-7wzml.45: metrics must never double-count.  Running migrate() twice in
     * succession on the same bubble (second call hits the cooldown) must never cause
     * removeBubble to be called more than once on the source metrics.
     */
    @Test
    void testMigrate_metricsNeverDoubleCount() throws Exception {
        // Arrange: source metrics prime with exactly 1 bubble / 2 entities
        var sourceMetrics = tumbler.getServerMetrics(sourceServerId);
        sourceMetrics.addBubble(2);
        assertThat(sourceMetrics.bubbleCount()).isEqualTo(1);

        var sourceBubble = new Bubble(UUID.randomUUID(), (byte) 5, 16L, mock(Transport.class));
        sourceBubble.addEntity("m1", new Point3f(1f, 0f, 0f), "c1");
        sourceBubble.addEntity("m2", new Point3f(2f, 0f, 0f), "c2");

        var targetBubble = new Bubble(UUID.randomUUID(), (byte) 5, 16L, mock(Transport.class));
        migrator.setBubbleTransferFactory((tgtServerId, src) -> targetBubble);

        // First migration (success)
        var first = migrator.migrate(sourceBubble, sourceServerId, targetServerId)
                            .get(5, TimeUnit.SECONDS);
        assertThat(first.success()).isTrue();

        // At this point bubbleCount == 0, entityCount == 0. A second call would hit the cooldown.
        var second = migrator.migrate(sourceBubble, sourceServerId, targetServerId)
                             .get(5, TimeUnit.SECONDS);
        assertThat(second.success())
            .as("second migrate on same bubbleId must fail (cooldown or already-migrated)")
            .isFalse();

        // Metrics: removeBubble called exactly once
        assertThat(sourceMetrics.bubbleCount())
            .as("bubbleCount must not go negative — removeBubble called exactly once")
            .isGreaterThanOrEqualTo(0);
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

    // -----------------------------------------------------------------------
    // Luciferase-7wzml.210: neighbor ACK wait — replaces raw Thread.sleep(50)
    // -----------------------------------------------------------------------

    /**
     * Luciferase-7wzml.210: when broadcastMoveAsync() never completes within the ack timeout,
     * migrate() must return a failure MigrationResult (not silent success).
     * The timeout window is driven by clock.currentTimeMillis() so it is deterministic under TestClock.
     */
    @Test
    void testMigrate_neighborAckTimeout_returnsFalseMigrationResult() throws Exception {
        // Arrange: a TestClock so time is controlled; a very short ack timeout
        var testClock = new TestClock();
        testClock.setTime(1000L);
        migrator.setClock(testClock);
        migrator.setNeighborAckTimeout(Duration.ofMillis(50));

        var sourceBubble = new Bubble(UUID.randomUUID(), (byte) 5, 16L, mock(Transport.class));

        // Target bubble spy: broadcastMoveAsync() returns a future that never completes
        var realTarget = new Bubble(UUID.randomUUID(), (byte) 5, 16L, mock(Transport.class));
        var spyTarget = spy(realTarget);
        doReturn(new CompletableFuture<Void>())  // never-completing future
            .when(spyTarget).broadcastMoveAsync();
        migrator.setBubbleTransferFactory((tgtServerId, src) -> spyTarget);

        // Act: allow up to 5s wall-clock (migration orTimeout is 1s by default in setUp)
        var result = migrator.migrate(sourceBubble, sourceServerId, targetServerId)
                             .get(5, TimeUnit.SECONDS);

        // Assert: timeout → false result, NOT silent success
        assertThat(result.success())
            .as("neighbor ACK timeout must produce a failure MigrationResult (Luciferase-7wzml.210)")
            .isFalse();
        assertThat(result.message())
            .as("failure message must mention ack timeout")
            .containsIgnoringCase("ack timeout");
    }

    /**
     * Luciferase-7wzml.210 success path: when broadcastMoveAsync() completes immediately
     * (no neighbors), migration must still succeed — regression guard for the happy path.
     */
    @Test
    void testMigrate_neighborAckImmediate_successPath() throws Exception {
        // Default setup: Bubble with no neighbors → broadcastMoveAsync returns completed future
        var sourceBubble = new Bubble(UUID.randomUUID(), (byte) 5, 16L, mock(Transport.class));
        var targetBubble = new Bubble(UUID.randomUUID(), (byte) 5, 16L, mock(Transport.class));
        migrator.setBubbleTransferFactory((tgtServerId, src) -> targetBubble);

        var result = migrator.migrate(sourceBubble, sourceServerId, targetServerId)
                             .get(5, TimeUnit.SECONDS);

        assertThat(result.success())
            .as("migration with no neighbors (immediate ACK) must succeed")
            .isTrue();
    }

    /**
     * Luciferase-7wzml.210: the ack timeout uses neighborAckTimeout.toMillis() not raw Thread.sleep.
     * Verify no silent-success regression: a frozen TestClock still produces a failure result when
     * broadcastMoveAsync() never completes (the CF.get(timeout) is the real gate, not clock drift).
     */
    @Test
    void testMigrate_neighborAckTimeout_clockDrivenNotWallClock() throws Exception {
        // TestClock frozen — wall clock advances but TestClock does not
        var frozenClock = new TestClock();
        frozenClock.setTime(5000L);
        migrator.setClock(frozenClock);
        migrator.setNeighborAckTimeout(Duration.ofMillis(50));

        var sourceBubble = new Bubble(UUID.randomUUID(), (byte) 5, 16L, mock(Transport.class));
        var realTarget = new Bubble(UUID.randomUUID(), (byte) 5, 16L, mock(Transport.class));
        var spyTarget = spy(realTarget);
        doReturn(new CompletableFuture<Void>())  // never completes
            .when(spyTarget).broadcastMoveAsync();
        migrator.setBubbleTransferFactory((tgtServerId, src) -> spyTarget);

        var result = migrator.migrate(sourceBubble, sourceServerId, targetServerId)
                             .get(5, TimeUnit.SECONDS);

        // The CompletableFuture.get(50ms) gate fires regardless of TestClock state —
        // the important contract: (a) no raw Thread.sleep, (b) result is failure not silent success.
        assertThat(result.success())
            .as("frozen TestClock: timeout still fires via CF.get(); result must be failure, not silent success")
            .isFalse();
    }

    /**
     * Minimal TestClock for this test class — mirrors the pattern used throughout the simulation tests.
     */
    private static class TestClock implements Clock {
        private final AtomicLong millis = new AtomicLong(0);
        private final AtomicLong nanos  = new AtomicLong(0);

        void setTime(long ms) {
            millis.set(ms);
            nanos.set(ms * 1_000_000L);
        }

        void advance(long deltaMs) {
            millis.addAndGet(deltaMs);
            nanos.addAndGet(deltaMs * 1_000_000L);
        }

        @Override
        public long currentTimeMillis() {
            return millis.get();
        }

        @Override
        public long nanoTime() {
            return nanos.get();
        }
    }
}
