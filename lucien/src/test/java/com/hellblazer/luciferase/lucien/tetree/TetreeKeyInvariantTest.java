package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.geometry.MortonCurve;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Key-invariant contract tests for {@link TetreeKey} and its implementations
 * ({@link CompactTetreeKey}, {@link ExtendedTetreeKey}, {@link LazyTetreeKey}).
 *
 * <p>Originally the RED-first specification (bead Luciferase-o988) that gated the encoding-flip
 * keystone (bead Luciferase-tkvb). The TM-index now packs the <em>coarsest</em> level at the
 * most-significant tuple and the finest (leaf) level at the LSB tuple (coarsest-at-MSB consecutive
 * layout, matching {@code PyramidKey}; see {@link Tet#tmIndex()}). tkvb landed the flip, so the
 * {@code parent()} round-trip ({@link #parentRoundTrip_allLevels()}) and level-21 decode round-trip
 * ({@link #decodeRoundTrip_L21()}) invariants now hold and their tests are enabled.
 *
 * <p>The cross-implementation equals/hashCode symmetry tests ({@link #equalsSymmetry_crossImpl()},
 * {@link #equalsSymmetry_lazyVsConcrete()}) are now enabled: bead Luciferase-567m made equals/hashCode
 * uniform across {@link CompactTetreeKey}/{@link ExtendedTetreeKey}/{@link LazyTetreeKey} by hoisting
 * them into {@link TetreeKey} as {@code final} methods over {@code (level, lowBits, highBits)}. The
 * other tests lock in the encoding contract (decode round-trip L1-20, parent round-trip,
 * equals/compareTo consistency for concrete keys, distinct-key ordering) as regression guards.
 *
 * <p>Ground truth for the parent/round-trip invariants is the geometric/topological {@link Tet}
 * (its {@link Tet#parent()} and {@link Tet#tmIndex()} are independently validated against the t8code
 * dtet oracle by {@link T8codeDtetOracleTest}); these tests only assert that the pure bit-level
 * {@code TetreeKey} operations agree with that ground truth.
 */
class TetreeKeyInvariantTest {

    private static final byte MAX_LEVEL = MortonCurve.MAX_REFINEMENT_LEVEL; // 21

    /** Deterministically descend a child chain from root to {@code targetLevel}. */
    private static Tet descend(Random rnd, byte targetLevel) {
        var tet = new Tet(0, 0, 0, (byte) 0, (byte) 0);
        for (int lvl = 0; lvl < targetLevel; lvl++) {
            tet = tet.child(rnd.nextInt(8));
        }
        return tet;
    }

    // ===================================================================================
    // Invariant 1 — parent round-trip: tet.parent().tmIndex() == tet.tmIndex().parent()
    // ===================================================================================

    /**
     * RED until Luciferase-tkvb. The key-level {@code parent()} must equal the ground-truth parent
     * key (encode the parent Tet) at every level 1..21. Currently violated at level &ge; 2 because
     * {@code parent()} strips the coarsest tuple instead of the finest.
     */
    @Test
    void parentRoundTrip_allLevels() {
        var rnd = new Random(0xB0BACAFEL);
        // Note: level 1 passes today (parent is root, all-zero bits); the breakage starts at level 2.
        // After Luciferase-tkvb ALL levels 1..21 must pass.
        for (byte target = 1; target <= MAX_LEVEL; target++) {
            for (int sample = 0; sample < 64; sample++) {
                assertParentRoundTrip(descend(rnd, target), target);
            }
        }
        // Structural boundary pinning: the random walk above may not deterministically hit the
        // compact<->extended boundary (L10->L11) or the last standard-extended level (L20). Pin all
        // 6 tet types at a fixed anchor for those levels so the encoding flip cannot pass these tests
        // while silently regressing a boundary case.
        for (byte target : new byte[] { 10, 11, 20 }) {
            for (byte type = 0; type < 6; type++) {
                assertParentRoundTrip(new Tet(0, 0, 0, target, type), target);
            }
        }
    }

    private static void assertParentRoundTrip(Tet tet, byte target) {
        var groundTruth = tet.parent().tmIndex();       // build parent Tet, then encode
        var viaKey = tet.tmIndex().parent();             // encode, then key.parent()
        assertEquals(groundTruth, viaKey,
                     "parent key mismatch at level " + target + " for " + tet
                     + ": ground-truth=" + groundTruth + " key.parent()=" + viaKey);
        assertEquals((byte) (target - 1), viaKey.getLevel(),
                     "parent level must be one coarser at level " + target);
    }

    // ===================================================================================
    // Invariant 2 — decode round-trip: Tet.tetrahedron(tet.tmIndex()) == tet
    //   (extends the oracle sweep past level 5 into the L11-21 ExtendedTetreeKey split)
    // ===================================================================================

    /**
     * GREEN regression guard. {@code Tet -> tmIndex -> Tet} must round-trip for levels 1..20,
     * exercising both the single-long {@link CompactTetreeKey} range (L1-10) and the dual-long
     * {@link ExtendedTetreeKey} split (L11-20). The existing oracle sweep
     * ({@link T8codeDtetOracleTest}) only reaches level 5; this carries it to L20.
     */
    @Test
    void decodeRoundTrip_L1toL20() {
        var rnd = new Random(0x5EED1234L);
        for (byte target = 1; target <= 20; target++) {
            for (int sample = 0; sample < 200; sample++) {
                var tet = descend(rnd, target);
                var decoded = Tet.tetrahedron(tet.tmIndex());
                assertEquals(tet, decoded,
                             "tmIndex decode round-trip failed at level " + target + " for " + tet);
            }
        }
    }

    /**
     * RED until Luciferase-tkvb. Level 21 (maximum refinement) uses split-bit packing that does not
     * currently round-trip {@code Tet -> tmIndex -> Tet}. Same encoding flip that fixes parent()
     * fixes the level-21 split.
     */
    @Test
    void decodeRoundTrip_L21() {
        var rnd = new Random(0x21212121L);
        for (int sample = 0; sample < 256; sample++) {
            var tet = descend(rnd, MAX_LEVEL);
            var decoded = Tet.tetrahedron(tet.tmIndex());
            assertEquals(tet, decoded, "level-21 tmIndex decode round-trip failed for " + tet);
        }
    }

    // ===================================================================================
    // Invariant 3 — equals <-> compareTo consistency
    // ===================================================================================

    /**
     * GREEN regression guard. For concrete keys ({@link CompactTetreeKey} at L1-10 and
     * {@link ExtendedTetreeKey} at L11-20), {@code a.equals(b)} must agree with
     * {@code a.compareTo(b) == 0}, equals must be symmetric, and equal keys must share a hashCode.
     */
    @Test
    void equalsCompareToConsistency_concreteKeys() {
        var rnd = new Random(0xC0FFEEL);
        var keys = new java.util.ArrayList<TetreeKey<?>>();
        for (byte target = 1; target <= 20; target++) {
            for (int sample = 0; sample < 40; sample++) {
                keys.add(descend(rnd, target).tmIndex());
            }
        }
        for (var a : keys) {
            // re-derive an independent but equal instance to exercise the equal branch
            var aCopy = TetreeKey.create(a.getLevel(), a.getLowBits(), a.getHighBits());
            assertTrue(a.equals(aCopy), "equal-bits keys must be equal: " + a);
            assertTrue(aCopy.equals(a), "equals must be symmetric for equal-bits keys: " + a);
            assertEquals(0, a.compareTo(aCopy), "compareTo must be 0 for equal keys: " + a);
            assertEquals(a.hashCode(), aCopy.hashCode(), "equal keys must share hashCode: " + a);

            for (var b : keys) {
                boolean eq = a.equals(b);
                boolean cmpZero = a.compareTo(b) == 0;
                assertEquals(cmpZero, eq,
                             "equals/compareTo disagreement: a=" + a + " b=" + b
                             + " equals=" + eq + " compareTo==0=" + cmpZero);
                // symmetry of equals across the whole sample
                assertEquals(eq, b.equals(a), "equals must be symmetric: a=" + a + " b=" + b);
                // antisymmetry of compareTo
                assertEquals(Integer.signum(a.compareTo(b)), -Integer.signum(b.compareTo(a)),
                             "compareTo must be antisymmetric: a=" + a + " b=" + b);
            }
        }
    }

    /**
     * RED until Luciferase-567m. Two concrete keys with identical (level, bits) but different runtime
     * classes must be mutually equal and share a hashCode. {@link ExtendedTetreeKey} extends
     * {@link CompactTetreeKey}, and each {@code equals} uses an {@code instanceof} keyed to its own
     * class: {@code compact.equals(extended)} is true but {@code extended.equals(compact)} is false,
     * an {@link Object#equals} symmetry violation that also diverges from {@code compareTo == 0}.
     * The fix (567m) is to compare on (level, lowBits, highBits) uniformly across all implementations.
     */
    @Test
    void equalsSymmetry_crossImpl() {
        // Same level (10), same bits, different runtime classes: Compact vs Extended-with-zero-high.
        long bits = 0x1234567L;
        var compact = new CompactTetreeKey((byte) 10, bits);
        var extended = new ExtendedTetreeKey((byte) 10, bits, 0L);
        assertEquals(0, compact.compareTo(extended), "compareTo must agree (0) for equal-bits keys");
        assertEquals(0, extended.compareTo(compact), "compareTo must agree (0) for equal-bits keys");
        assertTrue(compact.equals(extended), "compact.equals(extended) for equal-bits keys");
        assertTrue(extended.equals(compact), "extended.equals(compact) for equal-bits keys");
        assertEquals(compact.hashCode(), extended.hashCode(), "equal-bits keys must share hashCode");
    }

    /**
     * RED until Luciferase-567m. {@link LazyTetreeKey} and the concrete key it resolves to must be
     * mutually equal for the same {@link Tet}. Today {@code lazy.equals(concrete)} is true but
     * {@code concrete.equals(lazy)} is false (same {@code instanceof}-keyed equals root cause as
     * {@link #equalsSymmetry_crossImpl()}). Additionally {@code LazyTetreeKey.hashCode()} returns a
     * Tet-coordinate polynomial rather than the tmIndex-based hash the concrete keys use, so equal
     * lazy/concrete keys have different hash codes — 567m must align hashCode, not just equals.
     */
    @Test
    void equalsSymmetry_lazyVsConcrete() {
        var rnd = new Random(0x1A2B3CL);
        for (byte target = 1; target <= 20; target++) {
            for (int sample = 0; sample < 40; sample++) {
                var tet = descend(rnd, target);
                var lazy = new LazyTetreeKey(tet);
                var concrete = tet.tmIndex();
                assertTrue(lazy.equals(concrete), "lazy.equals(concrete) at level " + target + " for " + tet);
                assertTrue(concrete.equals(lazy), "concrete.equals(lazy) at level " + target + " for " + tet);
                assertEquals(0, lazy.compareTo(concrete), "lazy.compareTo(concrete)==0 for " + tet);
                assertEquals(0, concrete.compareTo(lazy), "concrete.compareTo(lazy)==0 for " + tet);
                assertEquals(lazy.hashCode(), concrete.hashCode(),
                             "equal lazy/concrete keys must share hashCode for " + tet);
            }
        }
    }

    /**
     * GREEN regression guard. Distinct sibling tets must produce keys that are unequal and order
     * strictly (no two distinct cells collide), and ordering must be a strict total order on the
     * sample (irreflexive distinctness).
     */
    @Test
    void distinctTetsProduceDistinctOrderedKeys() {
        // Sibling-set check at the root: all 8 children must map to distinct, strictly-ordered keys.
        var root = new Tet(0, 0, 0, (byte) 0, (byte) 0);
        var siblingKeys = new java.util.ArrayList<TetreeKey<?>>();
        for (int i = 0; i < 8; i++) {
            siblingKeys.add(root.child(i).tmIndex());
        }
        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 8; j++) {
                var keyI = siblingKeys.get(i);
                var keyJ = siblingKeys.get(j);
                if (i == j) {
                    assertEquals(0, keyI.compareTo(keyJ), "same child must compare equal");
                } else {
                    assertFalse(keyI.equals(keyJ), "distinct children must not be equal");
                    assertFalse(keyI.compareTo(keyJ) == 0, "distinct children must not compare equal");
                }
            }
        }

        // No-collision check across a deeper sample spanning the compact (<=10) and extended (>10)
        // ranges: distinct Tets at distinct levels must never collide on a single key.
        var rnd = new Random(0xD15C0L);
        var seen = new java.util.HashMap<TetreeKey<?>, Tet>();
        for (byte target : new byte[] { 5, 10, 11, 15, 20 }) {
            for (int sample = 0; sample < 100; sample++) {
                var tet = descend(rnd, target);
                var key = tet.tmIndex();
                var prior = seen.putIfAbsent(key, tet);
                // A collision is only a defect when the colliding Tets differ.
                assertTrue(prior == null || prior.equals(tet),
                           "distinct Tets collided on key " + key + ": " + prior + " vs " + tet);
            }
        }
    }

    /**
     * Regression guard for the {@code getNextKey} signed-compare bug (Luciferase-tkvb). The SFC
     * "next key" (exclusive {@code subMap} upper bound) must increment the 128-bit
     * {@code (highBits, lowBits)} value as <em>unsigned</em>. The original code used
     * {@code lowBits < Long.MAX_VALUE}, which at {@code lowBits == -1L} (all ones) wrongly returned
     * {@code (0, highBits)} — dropping the carry into the high word and silently truncating
     * k-NN/range scans over the upper half of the curve.
     */
    @Test
    void getNextKey_unsignedCarryAtAllOnesLowBits() {
        // lowBits all-ones must carry into highBits (the case the signed compare got wrong).
        var atLowMax = TetreeKey.create((byte) 21, -1L, 5L);
        var next = TetreeKey.getNextKey(atLowMax);
        assertEquals(TetreeKey.create((byte) 21, 0L, 6L), next,
                     "all-ones lowBits must carry into highBits");
        assertTrue(next.compareTo(atLowMax) > 0, "next key must be strictly greater");

        // A lowBits value that is negative-as-signed but NOT all-ones must increment in place.
        var midHigh = TetreeKey.create((byte) 21, 0x8000_0000_0000_0000L, 5L);
        var nextMid = TetreeKey.getNextKey(midHigh);
        assertEquals(TetreeKey.create((byte) 21, 0x8000_0000_0000_0001L, 5L), nextMid,
                     "negative-as-signed lowBits must still increment in place");
        assertTrue(nextMid.compareTo(midHigh) > 0, "next key must be strictly greater");

        // Ordinary case: plain low-bit increment.
        var ordinary = TetreeKey.create((byte) 11, 0x123L, 0L);
        assertEquals(TetreeKey.create((byte) 11, 0x124L, 0L), TetreeKey.getNextKey(ordinary),
                     "ordinary increment must add 1 to lowBits");
    }
}
