/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.topology;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.bubble.MutationLocks;
import com.hellblazer.luciferase.simulation.bubble.TetreeBubbleGrid;
import com.hellblazer.luciferase.simulation.distributed.integration.EntityAccountant;
import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency regression for {@link BubbleSplitter#execute} against the one coherent mutation-lock
 * protocol (Luciferase-n7io1) and the pre-lock-snapshot TOCTOU fix (Luciferase-hvjdj).
 * <p>
 * The C1 fix of n7io1 added {@code MutationLocks.lock(sourceBubble, newBubble)} around the split move
 * loop, but the pre-existing {@code BubbleMutationLockConcurrencyTest} only exercises
 * {@code BubbleLifecycle.transferEntities} + an inline manual locker — never {@code BubbleSplitter.execute}.
 * These tests close that gap and additionally validate the hvjdj behaviour: the split snapshot / split
 * plane / partition / key allocation all run BEFORE the lock is acquired, so a concurrent migration may
 * move entities out of the source during that window. The splitter must SKIP those escaped entities
 * rather than aborting the whole split, and must never lose or duplicate an entity.
 *
 * @author hal.hildebrand
 */
class BubbleSplitConcurrencyTest {

    private static final TestClock CLOCK = new TestClock(1_000L);

    private TetreeBubbleGrid bubbleGrid;
    private EntityAccountant accountant;
    private TopologyMetrics  metrics;

    @BeforeEach
    void setUp() {
        bubbleGrid = new TetreeBubbleGrid((byte) 2);
        accountant = new EntityAccountant();
        metrics = new TopologyMetrics();
    }

    /**
     * Deterministic reproduction of the snapshot-&gt;lock TOCTOU window. A custom split-plane strategy
     * migrates a known subset of the positive-side entities out of the source — into a third bubble, via
     * the {@link MutationLocks} protocol — at the exact moment the splitter calls {@code strategy.calculate}
     * (after the snapshot, before the move-loop lock). The split must still succeed, must skip the escaped
     * entities (they belong to the third bubble now, not the new split bubble), and must conserve every
     * entity exactly once across source + new + third.
     */
    @Test
    void splitSkipsEntitiesThatEscapedSourceDuringSnapshotToLockWindow() {
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var source = bubbleGrid.getAllBubbles().iterator().next();

        // Positive side (x=10) -> partitioned to the new bubble. Negative side (x=1) -> stays.
        var positiveIds = new ArrayList<UUID>();
        for (int i = 0; i < 50; i++) {
            var id = UUID.randomUUID();
            source.addEntity(id.toString(), new Point3f(10.0f, 5.0f, 5.0f), null);
            accountant.register(source.id(), id);
            positiveIds.add(id);
        }
        for (int i = 0; i < 50; i++) {
            var id = UUID.randomUUID();
            source.addEntity(id.toString(), new Point3f(1.0f, 5.0f, 5.0f), null);
            accountant.register(source.id(), id);
        }

        int totalBefore = accountant.entitiesInBubble(source.id()).size();
        assertThat(totalBefore).isEqualTo(100);

        // The bubble entities will escape into here during the TOCTOU window. 'third' is intentionally
        // NOT registered in bubbleGrid — it models an external migrator's peer bubble that the splitter's
        // grid doesn't know about; EntityAccountant tracks bubble->entity mappings independently of the
        // grid, so moveBetweenBubbles + entitiesInBubble(third.id()) work without grid membership.
        var third = new EnhancedBubble(UUID.randomUUID(), (byte) 1, 10);
        var escaped = new HashSet<>(positiveIds.subList(0, 10)); // 10 of the 50 to-move entities escape

        // Injection seam: strategy.calculate runs AFTER the splitter's snapshot, BEFORE its move-loop lock.
        SplitPlaneStrategy escapingStrategy = (bounds, snapshot) -> {
            // Concurrent-migration analogue: move the escaped entities source -> third under the protocol.
            try (var ignored = MutationLocks.lock(source, third)) {
                for (var id : escaped) {
                    third.addEntity(id.toString(), new Point3f(10.0f, 5.0f, 5.0f), null);
                    boolean moved = accountant.moveBetweenBubbles(id, source.id(), third.id());
                    assertThat(moved).as("pre-lock migration of escaped entity must succeed").isTrue();
                    source.removeEntity(id.toString());
                }
            }
            // Then return the plane the split intended: x=5.5 separates x=10 (move) from x=1 (stay).
            return new SplitPlane(new Point3f(1.0f, 0.0f, 0.0f), 5.5f);
        };

        var splitter = new BubbleSplitter(bubbleGrid, accountant, OperationTracker.NOOP, metrics, escapingStrategy);
        var proposal = new SplitProposal(UUID.randomUUID(), source.id(),
                                         new SplitPlane(new Point3f(1.0f, 0.0f, 0.0f), 5.5f),
                                         DigestAlgorithm.DEFAULT.getOrigin(), CLOCK.currentTimeMillis());

        var result = splitter.execute(proposal);

        // The split must NOT abort just because 10 entities escaped — it moves the 40 that remained.
        assertThat(result.success()).as("split must succeed despite escaped entities: " + result.message()).isTrue();
        var newBubbleId = result.newBubbleId();

        // Escaped entities live in the third bubble, never in the new split bubble.
        var inNew = accountant.entitiesInBubble(newBubbleId);
        for (var id : escaped) {
            assertThat(accountant.getLocationOfEntity(id))
                .as("escaped entity must be owned by the third (migration) bubble")
                .isEqualTo(third.id());
            assertThat(inNew).as("escaped entity must NOT be in the new split bubble").doesNotContain(id);
        }

        // The new split bubble got exactly the 40 non-escaped positive-side entities.
        assertThat(inNew).hasSize(40);

        // Global conservation: source + new + third == original, no duplicates.
        int sourceAfter = accountant.entitiesInBubble(source.id()).size();
        int newAfter    = inNew.size();
        int thirdAfter  = accountant.entitiesInBubble(third.id()).size();
        assertThat(sourceAfter + newAfter + thirdAfter)
            .as("entities conserved across source + new + third").isEqualTo(totalBefore);
        assertThat(thirdAfter).isEqualTo(10);
        assertThat(accountant.validate().success())
            .as("accountant must be free of duplicates/orphans after split").isTrue();
    }

    /**
     * True concurrent stress: a split on the source races a migration thread that repeatedly moves
     * entities source -&gt; third using the {@link MutationLocks} protocol. Whether the migration wins or
     * loses the snapshot-&gt;lock window, the run must (1) complete without deadlock and (2) conserve every
     * entity exactly once. This exercises the split-vs-migration lock composition that {@code n7io1}
     * established but never directly tested.
     * <p>
     * <b>What this proves vs. what it does not.</b> Because both threads start together but the split's
     * pre-lock section (snapshot + strategy + key search + grid insert) is substantial sequential work
     * while the migrate body is trivial, the scheduler MAY serialize the two without ever interleaving
     * inside the contested snapshot-&gt;lock window. This test therefore guarantees deadlock-freedom and
     * exact conservation <i>under whatever interleaving the scheduler produces</i> — it does NOT
     * guarantee the escape/skip window is hit on any given run. The deterministic proof that the skip
     * path is correct lives in {@link #splitSkipsEntitiesThatEscapedSourceDuringSnapshotToLockWindow},
     * which pins the exact interleaving via the strategy injection seam. The two tests are complementary.
     */
    @Test
    void concurrentSplitAndMigrationOnSameSource_conserveAndDoNotDeadlock() throws Exception {
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var source = bubbleGrid.getAllBubbles().iterator().next();
        var third = new EnhancedBubble(UUID.randomUUID(), (byte) 1, 10);

        var allIds = new HashSet<UUID>();
        var movable = new ArrayList<UUID>(); // positive-side, eligible for both split and migration
        for (int i = 0; i < 200; i++) {
            var id = UUID.randomUUID();
            source.addEntity(id.toString(), new Point3f(10.0f, 5.0f, 5.0f), null);
            accountant.register(source.id(), id);
            allIds.add(id);
            movable.add(id);
        }
        for (int i = 0; i < 200; i++) { // negative side keeps the split plane non-degenerate
            var id = UUID.randomUUID();
            source.addEntity(id.toString(), new Point3f(1.0f, 5.0f, 5.0f), null);
            accountant.register(source.id(), id);
            allIds.add(id);
        }
        int total = allIds.size();

        var splitter = new BubbleSplitter(bubbleGrid, accountant, OperationTracker.NOOP, metrics,
                                          SplitPlaneStrategies.xAxis());
        var proposal = new SplitProposal(UUID.randomUUID(), source.id(),
                                         new SplitPlane(new Point3f(1.0f, 0.0f, 0.0f), 5.5f),
                                         DigestAlgorithm.DEFAULT.getOrigin(), CLOCK.currentTimeMillis());

        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        var migrated = new AtomicInteger();

        Callable<Void> splitTask = () -> {
            start.await();
            splitter.execute(proposal);
            return null;
        };

        // Migrate the first 30 movable entities out of source into third, honoring the lock protocol.
        Callable<Void> migrateTask = () -> {
            start.await();
            for (var id : movable.subList(0, 30)) {
                try (var ignored = MutationLocks.lock(source, third)) {
                    if (!source.getEntities().contains(id.toString())) {
                        continue; // split already moved it; respect single-ownership
                    }
                    third.addEntity(id.toString(), new Point3f(10.0f, 5.0f, 5.0f), null);
                    if (accountant.moveBetweenBubbles(id, source.id(), third.id())) {
                        source.removeEntity(id.toString());
                        migrated.incrementAndGet();
                    } else {
                        third.removeEntity(id.toString());
                    }
                }
            }
            return null;
        };

        var f1 = pool.submit(splitTask);
        var f2 = pool.submit(migrateTask);
        start.countDown();

        // Completion within the timeout proves split + migration compose deadlock-free (both acquire the
        // source/other pair in ascending UUID order via MutationLocks).
        f1.get(30, TimeUnit.SECONDS);
        f2.get(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        // No deadlock + exact conservation across every bubble, regardless of who won the race.
        var distribution = accountant.getDistribution();
        int sum = distribution.values().stream().mapToInt(Integer::intValue).sum();
        assertThat(sum).as("entities exactly conserved across all bubbles").isEqualTo(total);
        assertThat(accountant.validate().success())
            .as("accountant must be free of duplicates/orphans after concurrent split+migration").isTrue();

        // Cross-check id-set conservation across the three bubbles that could hold entities.
        var union = new HashSet<UUID>();
        union.addAll(accountant.entitiesInBubble(source.id()));
        for (var b : bubbleGrid.getAllBubbles()) {
            union.addAll(accountant.entitiesInBubble(b.id()));
        }
        union.addAll(accountant.entitiesInBubble(third.id()));
        assertThat(union).as("no entity lost or invented").isEqualTo(allIds);
    }
}
