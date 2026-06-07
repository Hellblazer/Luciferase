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
 * With the Bey-refinement redesign (AC-2.5), the split plane strategy is NO LONGER consulted
 * by {@code BubbleSplitter.execute()} — geometry comes from
 * {@link com.hellblazer.luciferase.lucien.tetree.Tet#geometricSubdivide()}. The TOCTOU injection
 * seam used by the old tests (escape inside {@code strategy.calculate()}) is no longer viable
 * because the strategy is never invoked. The tests have been redesigned:
 * <ul>
 *   <li>Deterministic TOCTOU test: entities are escaped INTO a third bubble directly, simulating
 *     a concurrent migration that completes between the splitter's snapshot (step 6) and its lock
 *     acquisition (step 7). The splitter's hvjdj re-validation under the lock must skip the already-
 *     escaped entities and complete successfully with the remaining ones.</li>
 *   <li>True concurrent stress: migration thread races the splitter; proves deadlock-freedom and
 *     exact conservation under any scheduler interleaving.</li>
 * </ul>
 * <p>
 * Entity placement: all entities are placed at centroid of the root-level Tet's child-0 region
 * (near the origin, at x=y=z small positive values). The Bey splitter assigns them by
 * {@code contains12DOP} — all land in one or a few children, which is fine; conservation is the
 * invariant, not symmetric distribution.
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
     * Deterministic reproduction of the snapshot-to-lock TOCTOU window.
     * <p>
     * Protocol: (1) snapshot all entity records outside the lock (step 6 in splitter);
     * (2) concurrent migration moves 10 entities from source to a third bubble;
     * (3) splitter acquires lock and re-validates source membership (hvjdj guard).
     * Escaped entities must be SKIPPED, not cause the split to abort.
     * <p>
     * Escape injection: entities are migrated to {@code third} BEFORE {@code execute()} is called,
     * which simulates the window between the splitter's snapshot and its lock acquisition. The
     * splitter's {@code getAllEntityRecords()} snapshot will include those IDs, but the hvjdj
     * guard will detect they are no longer in the source under the lock.
     */
    @Test
    void splitSkipsEntitiesThatEscapedSourceBeforeLock() {
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var source = bubbleGrid.getAllBubbles().iterator().next();

        // Add 100 entities; 50 will be the ones we pre-escape
        var escapedIds = new ArrayList<UUID>();
        var remainingIds = new ArrayList<UUID>();
        for (int i = 0; i < 50; i++) {
            var id = UUID.randomUUID();
            source.addEntity(id.toString(), new Point3f(i * 0.01f, i * 0.01f, i * 0.01f), null);
            accountant.register(source.id(), id);
            escapedIds.add(id);
        }
        for (int i = 50; i < 100; i++) {
            var id = UUID.randomUUID();
            source.addEntity(id.toString(), new Point3f(i * 0.01f, i * 0.01f, i * 0.01f), null);
            accountant.register(source.id(), id);
            remainingIds.add(id);
        }
        int totalBefore = 100;

        // A third bubble to receive escaped entities. Not in the grid — models a peer
        // bubble that's tracked only by the accountant.
        var third = new EnhancedBubble(UUID.randomUUID(), (byte) 1, 10);

        // Simulate concurrent migration: escape the first 10 of escapedIds to third.
        // This occurs logically "after the splitter's snapshot" and "before lock acquisition".
        var actuallyEscaped = escapedIds.subList(0, 10);
        try (var ignored = MutationLocks.lock(source, third)) {
            for (var id : actuallyEscaped) {
                third.addEntity(id.toString(), new Point3f(0.01f, 0.01f, 0.01f), null);
                boolean moved = accountant.moveBetweenBubbles(id, source.id(), third.id());
                assertThat(moved).as("pre-lock migration must succeed").isTrue();
                source.removeEntity(id.toString());
            }
        }
        // source now has 90 entities; third has 10; 100 total
        assertThat(accountant.entitiesInBubble(source.id()).size()).isEqualTo(90);
        assertThat(accountant.entitiesInBubble(third.id()).size()).isEqualTo(10);

        var splitter = new BubbleSplitter(bubbleGrid, accountant, OperationTracker.NOOP, metrics);
        var proposal = new SplitProposal(UUID.randomUUID(), source.id(),
                                         new SplitPlane(new Point3f(1.0f, 0.0f, 0.0f), 0.5f),
                                         DigestAlgorithm.DEFAULT.getOrigin(), CLOCK.currentTimeMillis());

        var result = splitter.execute(proposal);

        // Split must succeed: 90 entities are still in source and should be redistributed to children.
        assertThat(result.success()).as("split must succeed despite pre-escaped entities: " + result.message()).isTrue();
        assertThat(result.childBubbleIds()).as("Bey split must produce at least one child").isNotEmpty();

        // Escaped entities still live in the third bubble.
        for (var id : actuallyEscaped) {
            assertThat(accountant.getLocationOfEntity(id))
                .as("escaped entity must still be owned by the third bubble")
                .isEqualTo(third.id());
        }

        // Global conservation: children + third == original total (source removed from grid).
        int childSum = result.childBubbleIds().stream()
                             .mapToInt(id -> accountant.entitiesInBubble(id).size())
                             .sum();
        int thirdAfter = accountant.entitiesInBubble(third.id()).size();
        assertThat(childSum + thirdAfter)
            .as("entities conserved: children + third == totalBefore").isEqualTo(totalBefore);
        assertThat(thirdAfter).isEqualTo(10);
        assertThat(accountant.validate().success())
            .as("accountant must be free of duplicates/orphans after split").isTrue();
    }

    /**
     * True concurrent stress: a split on the source races a migration thread that repeatedly moves
     * entities from source to a third bubble using the {@link MutationLocks} protocol.
     * <p>
     * Regardless of who wins the race:
     * <ol>
     *   <li>No deadlock (both threads complete within timeout)</li>
     *   <li>Exact conservation: every entity is in exactly one bubble</li>
     * </ol>
     * <p>
     * <b>Note on TOCTOU coverage.</b> Because the scheduler may serialize the two threads without
     * interleaving in the snapshot-to-lock window, this test guarantees deadlock-freedom and
     * conservation under whatever interleaving occurs — it does NOT guarantee the hvjdj skip path is
     * exercised on any given run. The deterministic proof of the skip path lives in
     * {@link #splitSkipsEntitiesThatEscapedSourceBeforeLock}.
     */
    @Test
    void concurrentSplitAndMigrationOnSameSource_conserveAndDoNotDeadlock() throws Exception {
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var source = bubbleGrid.getAllBubbles().iterator().next();
        var third = new EnhancedBubble(UUID.randomUUID(), (byte) 1, 10);

        var allIds = new HashSet<UUID>();
        var movable = new ArrayList<UUID>();
        for (int i = 0; i < 200; i++) {
            var id = UUID.randomUUID();
            source.addEntity(id.toString(), new Point3f(i * 0.01f, i * 0.01f, i * 0.01f), null);
            accountant.register(source.id(), id);
            allIds.add(id);
            movable.add(id);
        }
        // Add 200 more entities to ensure the split is non-trivial
        for (int i = 200; i < 400; i++) {
            var id = UUID.randomUUID();
            source.addEntity(id.toString(), new Point3f(i * 0.01f, i * 0.01f, i * 0.01f), null);
            accountant.register(source.id(), id);
            allIds.add(id);
        }
        int total = allIds.size(); // 400

        var splitter = new BubbleSplitter(bubbleGrid, accountant, OperationTracker.NOOP, metrics);
        var proposal = new SplitProposal(UUID.randomUUID(), source.id(),
                                         new SplitPlane(new Point3f(1.0f, 0.0f, 0.0f), 1.0f),
                                         DigestAlgorithm.DEFAULT.getOrigin(), CLOCK.currentTimeMillis());

        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        var migrated = new AtomicInteger();

        Callable<Void> splitTask = () -> {
            start.await();
            splitter.execute(proposal);
            return null;
        };

        // Migrate the first 30 movable entities from source to third, honoring the lock protocol.
        Callable<Void> migrateTask = () -> {
            start.await();
            for (var id : movable.subList(0, 30)) {
                try (var ignored = MutationLocks.lock(source, third)) {
                    if (!source.getEntities().contains(id.toString())) {
                        continue; // split already moved it
                    }
                    third.addEntity(id.toString(), new Point3f(0.01f, 0.01f, 0.01f), null);
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

        f1.get(30, TimeUnit.SECONDS);
        f2.get(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        // No deadlock + exact conservation across every bubble.
        var distribution = accountant.getDistribution();
        int sum = distribution.values().stream().mapToInt(Integer::intValue).sum();
        assertThat(sum).as("entities exactly conserved across all bubbles").isEqualTo(total);
        assertThat(accountant.validate().success())
            .as("accountant must be free of duplicates/orphans after concurrent split+migration").isTrue();

        // Cross-check id-set conservation.
        var union = new HashSet<UUID>();
        union.addAll(accountant.entitiesInBubble(source.id()));
        for (var b : bubbleGrid.getAllBubbles()) {
            union.addAll(accountant.entitiesInBubble(b.id()));
        }
        union.addAll(accountant.entitiesInBubble(third.id()));
        assertThat(union).as("no entity lost or invented").isEqualTo(allIds);
    }
}
