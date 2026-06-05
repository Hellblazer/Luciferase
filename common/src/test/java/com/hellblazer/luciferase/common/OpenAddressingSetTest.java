/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.hellblazer.luciferase.common;

import org.junit.jupiter.api.Test;

import java.util.ConcurrentModificationException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for {@link OpenAddressingSet} and {@link OaHashSet}.
 *
 * Covers the operator-precedence bug in the probe-step expression (every
 * collision caused {@code index} to grow unbounded → AIOOBE) and the
 * post-{@link OpenAddressingSet#clear()} iterator NPE.
 */
class OpenAddressingSetTest {

    /** Key whose hashCode() is fully controlled by the caller — forces collisions. */
    private record FixedHash(int hash, int id) {
        @Override
        public int hashCode() {
            return hash;
        }
    }

    @Test
    void addAndContainsSurvivesCollisions() {
        var set = new OaHashSet<FixedHash>(4);
        // All these keys hash to the same value → maximum probe-chain pressure.
        for (int i = 0; i < 50; i++) {
            set.add(new FixedHash(0xdead_beef, i));
        }
        assertEquals(50, set.size());
        for (int i = 0; i < 50; i++) {
            assertTrue(set.contains(new FixedHash(0xdead_beef, i)), "missing key id=" + i);
        }
        assertFalse(set.contains(new FixedHash(0xdead_beef, 999)));
    }

    @Test
    void removeUnderCollisionPressureLeavesRemainingKeysReachable() {
        var set = new OaHashSet<FixedHash>(4);
        for (int i = 0; i < 32; i++) {
            set.add(new FixedHash(0x1234_5678, i));
        }
        for (int i = 0; i < 32; i += 2) {
            assertTrue(set.remove(new FixedHash(0x1234_5678, i)), "remove failed for id=" + i);
        }
        for (int i = 1; i < 32; i += 2) {
            assertTrue(set.contains(new FixedHash(0x1234_5678, i)),
                       "odd id=" + i + " should still be present after removing evens");
        }
        for (int i = 0; i < 32; i += 2) {
            assertFalse(set.contains(new FixedHash(0x1234_5678, i)),
                        "even id=" + i + " should be gone");
        }
    }

    @Test
    void iteratorDoesNotNPEAfterClear() {
        var set = new OaHashSet<Integer>();
        set.add(1);
        set.add(2);
        set.clear();
        // Iterator on a cleared set must be safely empty, not NPE.
        Iterator<Integer> it = set.iterator();
        assertFalse(it.hasNext());
        assertThrows(NoSuchElementException.class, it::next);
    }

    @Test
    void clearThenAddIsFunctional() {
        var set = new OaHashSet<Integer>();
        for (int i = 0; i < 100; i++) {
            set.add(i);
        }
        set.clear();
        assertEquals(0, set.size());
        set.add(42);
        assertEquals(1, set.size());
        assertTrue(set.contains(42));
    }

    /**
     * Regression: iterator must throw CME (not silently skip/duplicate/NPE)
     * when a structural modification (add triggering rehash) occurs after the
     * iterator is created. This is the core bug in Luciferase-7wzml.110.
     */
    @Test
    void iteratorFailsFastOnStructuralModificationViaAdd() {
        var set = new OaHashSet<Integer>();
        // Pre-fill just below rehash threshold so the very next add triggers rehash.
        // Initial capacity 4, threshold 0.75 → rehash at size >= 3.
        set.add(1);
        set.add(2);
        // Iterator captured BEFORE the structural modification.
        Iterator<Integer> it = set.iterator();
        assertTrue(it.hasNext(), "iterator should see elements before modification");
        // Structural modification: add triggers rehash → modCount bumped.
        set.add(3);
        // Both hasNext() and next() must now throw CME, not silently iterate stale data.
        assertThrows(ConcurrentModificationException.class, it::hasNext,
                     "hasNext() must throw CME after structural add");
    }

    @Test
    void iteratorFailsFastOnStructuralModificationViaRemove() {
        var set = new OaHashSet<Integer>();
        set.add(10);
        set.add(20);
        set.add(30);
        Iterator<Integer> it = set.iterator();
        // Advance one step before modifying.
        assertTrue(it.hasNext());
        it.next();
        // Remove a different element → structural modification.
        assertTrue(set.remove(20));
        assertThrows(ConcurrentModificationException.class, it::next,
                     "next() must throw CME after structural remove");
    }

    @Test
    void iteratorFailsFastOnClear() {
        var set = new OaHashSet<Integer>();
        set.add(1);
        set.add(2);
        Iterator<Integer> it = set.iterator();
        set.clear();
        assertThrows(ConcurrentModificationException.class, it::hasNext,
                     "hasNext() must throw CME after clear()");
    }

    @Test
    void parityWithHashSetUnderRandomLoad() {
        var rng = new Random(0xC0FFEEL);
        var reference = new HashSet<Integer>();
        var actual = new OaHashSet<Integer>();
        for (int i = 0; i < 10_000; i++) {
            int v = rng.nextInt(1_000);
            if (rng.nextBoolean()) {
                assertEquals(reference.add(v), actual.add(v));
            } else {
                assertEquals(reference.remove(v), actual.remove(v));
            }
        }
        assertEquals(reference.size(), actual.size());
        for (Integer v : reference) {
            assertTrue(actual.contains(v), "missing " + v);
        }
        Set<Integer> drained = new HashSet<>();
        actual.iterator().forEachRemaining(drained::add);
        assertEquals(reference, drained);
    }

    /**
     * Bead Luciferase-7wzml.56 — rehash trigger must count tombstones.
     *
     * <p>The defect: {@code add()} gated rehash on the live {@code size} count only.
     * A churn pattern (add unique key, remove it) accumulates tombstone (DELETED)
     * slots without increasing {@code size}, so rehash never fires even when the
     * table has nearly no null slots left — degrading {@code contains()} misses
     * to O(capacity).</p>
     *
     * <p>The fix: gate rehash on {@code occupied} (= live + tombstones). After any
     * {@code add()} completes, {@code occupied} must be strictly less than
     * {@code table.length} — i.e. the table can never be fully saturated because
     * rehash fires at {@code occupied >= table.length * THRESHOLD (0.75)}.</p>
     *
     * <p>Secondary correctness check: after 500 churn cycles all original live
     * keys must still be reachable and an absent-key {@code contains()} must
     * terminate and return {@code false}.</p>
     */
    @Test
    void tombstoneAccumulationTriggersRehash() {
        var set = new OaHashSet<Integer>(4);

        // Seed a baseline of permanent live entries (never removed).
        final int LIVE_COUNT = 2;
        for (int i = 0; i < LIVE_COUNT; i++) {
            set.add(1000 + i);
        }

        // Churn: each cycle adds a unique transient key then removes it,
        // leaving a tombstone. Unique keys maximise tombstone accumulation by
        // landing at different slots rather than immediately recycling the same
        // deleted slot on the next insert.
        final int CYCLES = 500;
        for (int i = 0; i < CYCLES; i++) {
            set.add(2000 + i);
            set.remove(2000 + i);
        }

        // Correctness: all permanent live keys must still be reachable.
        for (int i = 0; i < LIVE_COUNT; i++) {
            assertTrue(set.contains(1000 + i), "live key " + (1000 + i) + " must survive churn");
        }
        // Absent-key lookup must terminate (not O(n) infinite-like behaviour).
        assertFalse(set.contains(99999), "absent key must not be found");
        assertEquals(LIVE_COUNT, set.size());

        // Core invariant: occupied (live + tombstones) must always be < table.length
        // after add() completes, because the rehash threshold is 0.75 * capacity.
        // If the old size-only gate was still in place, occupied would keep growing
        // toward table.length with no rehash, eventually saturating the table.
        assertTrue(set.occupied < set.table.length,
                   "occupied (" + set.occupied + ") must be < table.length (" + set.table.length
                   + ") — tombstones must trigger rehash before saturation");
    }

    /**
     * Bead Luciferase-7wzml.112 — clear() must retain capacity.
     *
     * After {@code clear()}, the backing table should be null-filled (all
     * slots null) rather than discarded and later re-initialized to capacity 1.
     * Verifies: (1) size==0 after clear, (2) capacity unchanged (table length
     * preserved), (3) add/contains/iterate work normally after clear.
     */
    @Test
    void clearRetainsCapacity() {
        var set = new OaHashSet<Integer>(64);
        for (int i = 0; i < 40; i++) {
            set.add(i);
        }
        int capacityBeforeClear = set.table.length;
        assertTrue(capacityBeforeClear >= 64, "table must have been grown to at least 64");

        set.clear();

        assertEquals(0, set.size(), "size must be 0 after clear");
        // After the fix, table must still be allocated (not null) and keep its capacity.
        org.junit.jupiter.api.Assertions.assertNotNull(set.table,
                                                        "table must not be null after clear — capacity should be retained");
        assertEquals(capacityBeforeClear, set.table.length,
                     "table length must be unchanged after clear");

        // All slots must be null (no stale entries, no tombstones).
        for (Object slot : set.table) {
            org.junit.jupiter.api.Assertions.assertNull(slot, "all slots must be null after clear");
        }

        // add/contains/iterate after clear must work.
        set.add(99);
        assertTrue(set.contains(99));
        assertEquals(1, set.size());
        var drained = new HashSet<Integer>();
        set.iterator().forEachRemaining(drained::add);
        assertEquals(Set.of(99), drained);
    }
}
