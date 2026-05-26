/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.lucien.prism;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RDR-009 Phase 2 (Luciferase-4ky): pins the prism consecutive index and the
 * {@link PrismKey#compareTo(PrismKey)} ordering to the tetrahedral-Morton SFC. The prism index
 * interleaves the triangle's 2-bit local index with the line's 1-bit z-Morton digit per level
 * (3 bits/level), so the eight Morton children are contiguous: {@code I(child_i) = I(prism)*8 + i}.
 * {@code compareTo} orders by that index (level tie-break) — the ConcurrentSkipListMap storage
 * order that range queries walk — replacing the prior field-lexicographic order that was
 * independent of the SFC.
 *
 * @author hal.hildebrand
 */
class PrismKeyTmSfcTest {

    @Test
    @DisplayName("root prism index is 0")
    void rootIndexZero() {
        assertEquals(0L, PrismKey.createRoot().consecutiveIndex());
    }

    @Test
    @DisplayName("prism children occupy the contiguous block I(prism)*8 + {0..7}")
    void childrenAreContiguous() {
        var seeds = sampleKeys(3);
        for (var prism : seeds) {
            if (prism.getLevel() >= Triangle.MAX_LEVEL) {
                continue;
            }
            long base = prism.consecutiveIndex();
            var seen = new HashSet<Long>();
            for (int i = 0; i < PrismKey.CHILDREN; i++) {
                long ci = prism.child(i).consecutiveIndex();
                assertEquals(base * 8 + i, ci, String.format("child %d of %s", i, prism));
                seen.add(ci);
            }
            assertEquals(8, seen.size(), "8 distinct child indices");
        }
    }

    @Test
    @DisplayName("compareTo order == consecutiveIndex order for same-level keys")
    void compareToMatchesIndexSameLevel() {
        // All level-3 prisms reachable by refinement, sorted by compareTo, must be sorted by index.
        var keys = levelKeys(3);
        keys.sort(PrismKey::compareTo);
        for (int i = 1; i < keys.size(); i++) {
            assertTrue(keys.get(i - 1).consecutiveIndex() < keys.get(i).consecutiveIndex(),
                "compareTo order must agree with strictly-increasing consecutiveIndex at a fixed level");
        }
        // Index is a dense bijection to [0, 8^3) at level 3.
        var indices = new HashSet<Long>();
        for (var k : keys) {
            indices.add(k.consecutiveIndex());
        }
        assertEquals(512, keys.size(), "8^3 level-3 prisms");
        assertEquals(512, indices.size(), "all level-3 indices distinct");
    }

    @Test
    @DisplayName("compareTo is a total order consistent with equals across mixed levels")
    void compareToConsistentWithEquals() {
        var keys = new ArrayList<PrismKey>();
        for (int lvl = 0; lvl <= 4; lvl++) {
            keys.addAll(levelKeys(lvl).subList(0, Math.min(8, (int) Math.pow(8, lvl))));
        }
        for (int i = 0; i < keys.size(); i++) {
            var a = keys.get(i);
            assertEquals(0, a.compareTo(a), "reflexive");
            for (int j = i + 1; j < keys.size(); j++) {
                var b = keys.get(j);
                int ab = a.compareTo(b);
                int ba = b.compareTo(a);
                if (a.equals(b)) {
                    assertEquals(0, ab, "equal keys compareTo 0");
                } else {
                    assertNotEquals(0, ab, "distinct keys must not compareTo 0: " + a + " vs " + b);
                    assertEquals(Integer.signum(ab), -Integer.signum(ba), "antisymmetric");
                }
            }
        }
    }

    @Test
    @DisplayName("MAX_LEVEL prism index is non-negative and siblings are distinct, ordered")
    void maxLevelNoOverflow() {
        var prism = PrismKey.createRoot();
        // Descend to MAX_LEVEL via a fixed child path.
        for (int lvl = 0; lvl < Triangle.MAX_LEVEL; lvl++) {
            prism = prism.child((lvl * 5 + 3) % PrismKey.CHILDREN);
        }
        assertEquals(Triangle.MAX_LEVEL, prism.getLevel());
        assertTrue(prism.consecutiveIndex() >= 0, "level-21 prism index must be non-negative");

        var parent = prism.parent();
        long prev = -1;
        var seen = new HashSet<Long>();
        for (int i = 0; i < PrismKey.CHILDREN; i++) {
            long ci = parent.child(i).consecutiveIndex();
            assertTrue(ci >= 0, "MAX_LEVEL sibling index non-negative");
            assertTrue(seen.add(ci), "siblings distinct");
            assertTrue(ci > prev, "sibling indices strictly increasing with child index");
            prev = ci;
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────

    /** All prism keys at exactly {@code level}, reached by full refinement from the root. */
    private static List<PrismKey> levelKeys(int level) {
        var frontier = new ArrayList<PrismKey>();
        frontier.add(PrismKey.createRoot());
        for (int d = 0; d < level; d++) {
            var next = new ArrayList<PrismKey>();
            for (var k : frontier) {
                for (int i = 0; i < PrismKey.CHILDREN; i++) {
                    next.add(k.child(i));
                }
            }
            frontier = next;
        }
        return frontier;
    }

    /** A few representative prism keys at the given level. */
    private static List<PrismKey> sampleKeys(int level) {
        var all = levelKeys(level);
        var out = new ArrayList<PrismKey>();
        out.add(all.get(0));
        out.add(all.get(all.size() / 3));
        out.add(all.get(all.size() / 2));
        out.add(all.get(all.size() - 1));
        return out;
    }
}
