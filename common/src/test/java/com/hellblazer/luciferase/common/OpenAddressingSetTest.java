/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.hellblazer.luciferase.common;

import org.junit.jupiter.api.Test;

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
}
