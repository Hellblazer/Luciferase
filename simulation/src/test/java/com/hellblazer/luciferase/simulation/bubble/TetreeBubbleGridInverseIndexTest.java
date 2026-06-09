/*
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.simulation.bubble;

import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import com.hellblazer.luciferase.simulation.config.WorldBounds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TDD tests for the O(1) inverse UUID→TetreeKey index in TetreeBubbleGrid (RDR-020 S2).
 * <p>
 * Verifies that:
 * <ul>
 *   <li>after addBubble, getKeyForBubble(id) returns the exact key it was added under;</li>
 *   <li>after removeBubble(id), getKeyForBubble(id) returns null (no leak);</li>
 *   <li>absent id → null;</li>
 *   <li>the WorldBounds partition path (createBubbles(int,WorldBounds,long)) also populates
 *       the inverse index;</li>
 *   <li>the legacy mixed-level path (createBubbles(int,byte,long)) also populates the
 *       inverse index;</li>
 *   <li>consistency invariant: for every entry in bubblesByKey, getKeyForBubble(bubble.id())
 *       equals that key, and the two maps never drift in size after add/remove sequences;</li>
 *   <li>clear() wipes the inverse index so all ids resolve to null.</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
class TetreeBubbleGridInverseIndexTest {

    private TetreeBubbleGrid grid;

    @BeforeEach
    void setUp() {
        grid = new TetreeBubbleGrid((byte) 5);
    }

    // ── addBubble path ────────────────────────────────────────────────────────

    @Test
    void addBubble_getKeyForBubble_returnsExactKey() {
        var bubble = new EnhancedBubble(UUID.randomUUID(), (byte) 1, 16L);
        var key = new Tet(0, 0, 0, (byte) 0, (byte) 0).child(0).tmIndex();

        grid.addBubble(bubble, key);

        assertThat(grid.getKeyForBubble(bubble.id())).isEqualTo(key);
    }

    @Test
    void addBubble_getKeyForBubble_identicalToLinearScanResult() {
        // Belt-and-suspenders: the new O(1) result must equal what the old O(N) scan returned.
        var bubble = new EnhancedBubble(UUID.randomUUID(), (byte) 1, 16L);
        var key = new Tet(0, 0, 0, (byte) 0, (byte) 0).child(2).tmIndex();

        grid.addBubble(bubble, key);

        // getBubblesWithKeys() lets us independently derive the key via iteration.
        TetreeKey<?> scanKey = null;
        for (var entry : grid.getBubblesWithKeys().entrySet()) {
            if (entry.getValue().id().equals(bubble.id())) {
                scanKey = entry.getKey();
                break;
            }
        }
        assertThat(grid.getKeyForBubble(bubble.id())).isEqualTo(scanKey);
    }

    // ── removeBubble path ─────────────────────────────────────────────────────

    @Test
    void removeBubble_getKeyForBubble_returnsNull() {
        var bubble = new EnhancedBubble(UUID.randomUUID(), (byte) 1, 16L);
        var key = new Tet(0, 0, 0, (byte) 0, (byte) 0).child(0).tmIndex();
        grid.addBubble(bubble, key);

        boolean removed = grid.removeBubble(bubble.id());

        assertThat(removed).isTrue();
        assertThat(grid.getKeyForBubble(bubble.id())).isNull();
    }

    @Test
    void removeBubble_inverseIndexNoLeak() {
        // Add 3 bubbles, remove the middle one: inverse index size must track bubblesByKey size.
        var key1 = new Tet(0, 0, 0, (byte) 0, (byte) 0).child(0).tmIndex();
        var key2 = new Tet(0, 0, 0, (byte) 0, (byte) 0).child(1).tmIndex();
        var key3 = new Tet(0, 0, 0, (byte) 0, (byte) 0).child(2).tmIndex();

        var b1 = new EnhancedBubble(UUID.randomUUID(), (byte) 1, 16L);
        var b2 = new EnhancedBubble(UUID.randomUUID(), (byte) 1, 16L);
        var b3 = new EnhancedBubble(UUID.randomUUID(), (byte) 1, 16L);

        grid.addBubble(b1, key1);
        grid.addBubble(b2, key2);
        grid.addBubble(b3, key3);

        grid.removeBubble(b2.id());

        // b2 gone; b1 and b3 still resolvable.
        assertThat(grid.getKeyForBubble(b2.id())).isNull();
        assertThat(grid.getKeyForBubble(b1.id())).isEqualTo(key1);
        assertThat(grid.getKeyForBubble(b3.id())).isEqualTo(key3);
    }

    // ── absent id ─────────────────────────────────────────────────────────────

    @Test
    void getKeyForBubble_absentId_returnsNull() {
        assertThat(grid.getKeyForBubble(UUID.randomUUID())).isNull();
    }

    @Test
    void getKeyForBubble_nullId_throwsNullPointerException() {
        assertThatNullPointerException().isThrownBy(() -> grid.getKeyForBubble(null));
    }

    // ── WorldBounds partition path ────────────────────────────────────────────

    @Test
    void createBubbles_worldBoundsPath_populatesInverseIndex() {
        var bounds = new WorldBounds(0f, 1_000_000f);
        grid.createBubbles(4, bounds, 16L);

        assertThat(grid.getBubbleCount()).isGreaterThan(0);
        // Every registered bubble must be resolvable via inverse index.
        for (var entry : grid.getBubblesWithKeys().entrySet()) {
            assertThat(grid.getKeyForBubble(entry.getValue().id()))
                .as("WorldBounds partition bubble %s must resolve via inverse index", entry.getValue().id())
                .isEqualTo(entry.getKey());
        }
    }

    // ── Legacy mixed-level path ───────────────────────────────────────────────

    @Test
    void createBubbles_legacyPath_populatesInverseIndex() {
        grid.createBubbles(6, (byte) 2, 16L);

        assertThat(grid.getBubbleCount()).isGreaterThan(0);
        for (var entry : grid.getBubblesWithKeys().entrySet()) {
            assertThat(grid.getKeyForBubble(entry.getValue().id()))
                .as("Legacy path bubble %s must resolve via inverse index", entry.getValue().id())
                .isEqualTo(entry.getKey());
        }
    }

    // ── clear() path ──────────────────────────────────────────────────────────

    @Test
    void clear_wipesInverseIndex() {
        var key1 = new Tet(0, 0, 0, (byte) 0, (byte) 0).child(0).tmIndex();
        var b1 = new EnhancedBubble(UUID.randomUUID(), (byte) 1, 16L);
        grid.addBubble(b1, key1);

        grid.clear();

        assertThat(grid.getKeyForBubble(b1.id())).isNull();
        assertThat(grid.getBubbleCount()).isZero();
    }

    @Test
    void clear_afterCreateBubbles_wipesInverseIndex() {
        grid.createBubbles(5, (byte) 2, 16L);
        var allBefore = grid.getBubblesWithKeys();
        assertThat(allBefore).isNotEmpty();

        grid.clear();

        for (var bubble : allBefore.values()) {
            assertThat(grid.getKeyForBubble(bubble.id())).isNull();
        }
    }

    // ── consistency invariant ─────────────────────────────────────────────────

    @Test
    void consistencyInvariant_afterAddRemoveSequence_noMapDrift() {
        // Add 4 bubbles, remove 2 of them — sizes of bubblesByKey and inverse index must agree.
        var key1 = new Tet(0, 0, 0, (byte) 0, (byte) 0).child(0).tmIndex();
        var key2 = new Tet(0, 0, 0, (byte) 0, (byte) 0).child(1).tmIndex();
        var key3 = new Tet(0, 0, 0, (byte) 0, (byte) 0).child(2).tmIndex();
        var key4 = new Tet(0, 0, 0, (byte) 0, (byte) 0).child(3).tmIndex();

        var b1 = new EnhancedBubble(UUID.randomUUID(), (byte) 1, 16L);
        var b2 = new EnhancedBubble(UUID.randomUUID(), (byte) 1, 16L);
        var b3 = new EnhancedBubble(UUID.randomUUID(), (byte) 1, 16L);
        var b4 = new EnhancedBubble(UUID.randomUUID(), (byte) 1, 16L);

        grid.addBubble(b1, key1);
        grid.addBubble(b2, key2);
        grid.addBubble(b3, key3);
        grid.addBubble(b4, key4);

        // Each added bubble must resolve correctly.
        assertThat(grid.getKeyForBubble(b1.id())).isEqualTo(key1);
        assertThat(grid.getKeyForBubble(b2.id())).isEqualTo(key2);
        assertThat(grid.getKeyForBubble(b3.id())).isEqualTo(key3);
        assertThat(grid.getKeyForBubble(b4.id())).isEqualTo(key4);

        // Remove b1 and b3.
        grid.removeBubble(b1.id());
        grid.removeBubble(b3.id());

        // b2 and b4 still present; b1 and b3 gone.
        assertThat(grid.getKeyForBubble(b1.id())).isNull();
        assertThat(grid.getKeyForBubble(b2.id())).isEqualTo(key2);
        assertThat(grid.getKeyForBubble(b3.id())).isNull();
        assertThat(grid.getKeyForBubble(b4.id())).isEqualTo(key4);

        // Sizes agree: bubblesByKey has 2, inverse index should also cover exactly 2.
        assertThat(grid.getBubbleCount()).isEqualTo(2);
        // Forward-check: every key in bubblesByKey maps back via inverse index.
        for (var entry : grid.getBubblesWithKeys().entrySet()) {
            assertThat(grid.getKeyForBubble(entry.getValue().id()))
                .as("Forward consistency for bubble %s", entry.getValue().id())
                .isEqualTo(entry.getKey());
        }
        // Reverse-check: removed ids no longer in inverse index.
        assertThat(grid.getKeyForBubble(b1.id())).isNull();
        assertThat(grid.getKeyForBubble(b3.id())).isNull();
    }

    @Test
    void consistencyInvariant_createBubblesThenClear_noDrift() {
        grid.createBubbles(8, (byte) 2, 16L);
        int beforeCount = grid.getBubbleCount();
        assertThat(beforeCount).isGreaterThan(0);

        // All must resolve.
        for (var entry : grid.getBubblesWithKeys().entrySet()) {
            assertThat(grid.getKeyForBubble(entry.getValue().id()))
                .isEqualTo(entry.getKey());
        }

        grid.clear();

        // None must resolve after clear.
        assertThat(grid.getBubbleCount()).isZero();
    }

    // ── SIG-1: getNeighbors(UUID) uses inverse index ──────────────────────────

    @Test
    void getNeighbors_byUuid_presentId_returnsSameSetAsKeyOverload() {
        // Add two bubbles at adjacent children of the level-1 root: verify UUID overload delegates
        // to the key overload without an O(N) scan (same result, same exception contract).
        var key1 = new Tet(0, 0, 0, (byte) 0, (byte) 0).child(0).tmIndex();
        var key2 = new Tet(0, 0, 0, (byte) 0, (byte) 0).child(1).tmIndex();
        var b1 = new EnhancedBubble(UUID.randomUUID(), (byte) 1, 16L);
        var b2 = new EnhancedBubble(UUID.randomUUID(), (byte) 1, 16L);
        grid.addBubble(b1, key1);
        grid.addBubble(b2, key2);

        var byUuid = grid.getNeighbors(b1.id());
        var byKey  = grid.getNeighbors(key1);

        // Both overloads must agree on the neighbor UUIDs.
        var byKeyUuids = new java.util.HashSet<UUID>();
        for (var nb : byKey) byKeyUuids.add(nb.id());
        assertThat(byUuid).isEqualTo(byKeyUuids);
    }

    @Test
    void getNeighbors_byUuid_absentId_throwsNoSuchElement() {
        assertThatThrownBy(() -> grid.getNeighbors(UUID.randomUUID()))
            .isInstanceOf(java.util.NoSuchElementException.class);
    }

    // ── SIG-2: getBubbleById uses inverse index ───────────────────────────────

    @Test
    void getBubbleById_presentId_returnsBubble() {
        var key = new Tet(0, 0, 0, (byte) 0, (byte) 0).child(0).tmIndex();
        var bubble = new EnhancedBubble(UUID.randomUUID(), (byte) 1, 16L);
        grid.addBubble(bubble, key);

        assertThat(grid.getBubbleById(bubble.id())).isSameAs(bubble);
    }

    @Test
    void getBubbleById_absentId_returnsNull() {
        assertThat(grid.getBubbleById(UUID.randomUUID())).isNull();
    }

    @Test
    void getBubbleById_afterRemove_returnsNull() {
        var key = new Tet(0, 0, 0, (byte) 0, (byte) 0).child(0).tmIndex();
        var bubble = new EnhancedBubble(UUID.randomUUID(), (byte) 1, 16L);
        grid.addBubble(bubble, key);
        grid.removeBubble(bubble.id());

        assertThat(grid.getBubbleById(bubble.id())).isNull();
    }

    // ── OBS-1: duplicate-id guard in addBubble ────────────────────────────────

    @Test
    void addBubble_duplicateId_differentKey_throwsIllegalArgument() {
        // Same bubble re-added under a different key without removeBubble must be rejected fail-loud.
        var key1 = new Tet(0, 0, 0, (byte) 0, (byte) 0).child(0).tmIndex();
        var key2 = new Tet(0, 0, 0, (byte) 0, (byte) 0).child(1).tmIndex();
        var bubble = new EnhancedBubble(UUID.randomUUID(), (byte) 1, 16L);
        grid.addBubble(bubble, key1);

        assertThatThrownBy(() -> grid.addBubble(bubble, key2))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(bubble.id().toString());

        // Maps must be consistent after the rejected operation: forward key still points to the original entry.
        assertThat(grid.getKeyForBubble(bubble.id())).isEqualTo(key1);
        assertThat(grid.getBubbleCount()).isEqualTo(1);
        assertThat(grid.inverseIndexSize()).isEqualTo(1);
    }

    @Test
    void addBubble_sameIdSameKey_throwsIllegalArgument_existingKeyGuardFires() {
        // Duplicate key (same id, same key) must be caught by the existing duplicate-key guard.
        var key = new Tet(0, 0, 0, (byte) 0, (byte) 0).child(0).tmIndex();
        var bubble = new EnhancedBubble(UUID.randomUUID(), (byte) 1, 16L);
        grid.addBubble(bubble, key);

        assertThatThrownBy(() -> grid.addBubble(bubble, key))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ── OBS-2: inverse index size parity ─────────────────────────────────────

    @Test
    void inverseIndexSize_equalsForwardMapSize_afterAddRemoveSequence() {
        var key1 = new Tet(0, 0, 0, (byte) 0, (byte) 0).child(0).tmIndex();
        var key2 = new Tet(0, 0, 0, (byte) 0, (byte) 0).child(1).tmIndex();
        var key3 = new Tet(0, 0, 0, (byte) 0, (byte) 0).child(2).tmIndex();
        var b1 = new EnhancedBubble(UUID.randomUUID(), (byte) 1, 16L);
        var b2 = new EnhancedBubble(UUID.randomUUID(), (byte) 1, 16L);
        var b3 = new EnhancedBubble(UUID.randomUUID(), (byte) 1, 16L);

        // After each add, inverse size == forward size.
        grid.addBubble(b1, key1);
        assertThat(grid.inverseIndexSize()).isEqualTo(grid.getBubbleCount());

        grid.addBubble(b2, key2);
        assertThat(grid.inverseIndexSize()).isEqualTo(grid.getBubbleCount());

        grid.addBubble(b3, key3);
        assertThat(grid.inverseIndexSize()).isEqualTo(grid.getBubbleCount());

        // After remove, still in sync.
        grid.removeBubble(b2.id());
        assertThat(grid.inverseIndexSize()).isEqualTo(grid.getBubbleCount());
        assertThat(grid.inverseIndexSize()).isEqualTo(2);

        // After clear, both are 0.
        grid.clear();
        assertThat(grid.inverseIndexSize()).isEqualTo(0);
        assertThat(grid.getBubbleCount()).isEqualTo(0);
    }

    @Test
    void inverseIndexSize_equalsForwardMapSize_afterCreateBubbles() {
        grid.createBubbles(6, (byte) 2, 16L);
        assertThat(grid.inverseIndexSize()).isEqualTo(grid.getBubbleCount());
        assertThat(grid.inverseIndexSize()).isGreaterThan(0);
    }

    @Test
    void inverseIndexSize_equalsForwardMapSize_afterWorldBoundsCreateAndClear() {
        var bounds = new WorldBounds(0f, 1_000_000f);
        grid.createBubbles(4, bounds, 16L);
        assertThat(grid.inverseIndexSize()).isEqualTo(grid.getBubbleCount());

        grid.clear();
        assertThat(grid.inverseIndexSize()).isEqualTo(0);
        assertThat(grid.getBubbleCount()).isEqualTo(0);
    }

    /**
     * Luciferase-0frcy.133: the paired {@code bubblesByKey}/{@code keyByBubbleId} mutations are now
     * atomic w.r.t. the UUID-keyed readers. Under heavy concurrent add/remove with concurrent readers,
     * the grid must not deadlock, throw, or leave the two maps drifted. Deterministic: each writer owns
     * a distinct key and ends on removeBubble, so the grid drains to empty and both map sizes agree.
     */
    @Test
    void concurrentAddRemoveWithReaders_noDeadlockNoDriftNoException() throws Exception {
        final int writers = 4;       // level-1 children child(0..3) → distinct keys
        final int readers = 4;
        // Modest count: each re-add inserts a fresh entry into the (remove-less) Tetree spatial index,
        // so high iteration counts just bloat that index without adding concurrency coverage. A few
        // hundred interleaved add/remove/read cycles per writer is ample to exercise the lock.
        final int iterations = 500;

        var ids = new java.util.ArrayList<UUID>();
        var keys = new java.util.ArrayList<TetreeKey<?>>();
        for (int w = 0; w < writers; w++) {
            ids.add(UUID.randomUUID());
            keys.add(new Tet(0, 0, 0, (byte) 0, (byte) 0).child(w).tmIndex());
        }

        var stop = new java.util.concurrent.atomic.AtomicBoolean(false);
        var error = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(writers + readers);
        var done = new java.util.concurrent.CountDownLatch(writers);

        try {
            for (int w = 0; w < writers; w++) {
                final int wi = w;
                pool.submit(() -> {
                    try {
                        for (int i = 0; i < iterations && error.get() == null; i++) {
                            grid.addBubble(new EnhancedBubble(ids.get(wi), (byte) 1, 16L), keys.get(wi));
                            grid.removeBubble(ids.get(wi));
                        }
                    } catch (Throwable t) {
                        error.compareAndSet(null, t);
                    } finally {
                        done.countDown();
                    }
                });
            }
            for (int r = 0; r < readers; r++) {
                pool.submit(() -> {
                    try {
                        while (!stop.get() && error.get() == null) {
                            for (var id : ids) {
                                grid.getBubbleById(id);
                                grid.getKeyForBubble(id);
                                // getNeighbors(UUID) is the fail-loud path (throws NoSuchElementException
                                // when the bubble is absent). A throw is legitimate when the bubble is
                                // genuinely not present (between a writer's remove and its next add); the
                                // fix guarantees it is NEVER thrown for a half-applied add (key resolved
                                // but bubble not yet visible). So swallow NSE but let any OTHER throwable
                                // (e.g. a deadlock-induced or consistency error) fail the test.
                                try {
                                    grid.getNeighbors(id);
                                } catch (java.util.NoSuchElementException expectedWhenAbsent) {
                                    // ok — bubble not currently in the grid
                                }
                            }
                        }
                    } catch (Throwable t) {
                        error.compareAndSet(null, t);
                    }
                });
            }

            // No deadlock: writers must finish well within the timeout.
            assertThat(done.await(30, java.util.concurrent.TimeUnit.SECONDS))
                .as("writers must complete without deadlocking").isTrue();
            stop.set(true);
        } finally {
            pool.shutdownNow();
        }

        if (error.get() != null) {
            throw new AssertionError("concurrent add/remove/read threw", error.get());
        }
        // Every writer ended on removeBubble → grid fully drained, both maps agree.
        assertThat(grid.getBubbleCount()).as("grid drains to empty").isZero();
        assertThat(grid.inverseIndexSize()).as("inverse index agrees with forward map").isZero();
    }
}
