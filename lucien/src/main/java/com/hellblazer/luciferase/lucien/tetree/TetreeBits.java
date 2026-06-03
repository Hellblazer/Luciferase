package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.Constants;

/**
 * Efficient bitwise operations for tetrahedral indices based on t8code algorithms.
 *
 * This class provides low-level bit manipulation utilities for working with tetrahedral space-filling curve indices and
 * coordinates. All methods are static for maximum performance.
 *
 * @author hal.hildebrand
 */
public final class TetreeBits {

    // Private constructor to prevent instantiation
    private TetreeBits() {
        throw new AssertionError("TetreeBits is a utility class");
    }

    /**
     * Find the level of the lowest common ancestor of two tetrahedra. Based on t8code's t8_dtri_nearest_common_ancestor
     * algorithm.
     *
     * <p>Pyramid-rooted tetrahedra ({@code minTetLevel != }{@link Tet#NO_TET_ANCESTOR}) are not
     * supported: the type comparison below relies on {@link Tet#computeType(byte)}, whose ancestor
     * walk is undefined above the tet/pyramid boundary (deferred to Luciferase-q3p). This method
     * rejects such inputs up front with an attributable {@link IllegalArgumentException} rather than
     * letting the failure surface as a deep, unattributed throw from {@code computeType}.</p>
     *
     * @param tet1 first tetrahedron
     * @param tet2 second tetrahedron
     * @return level of lowest common ancestor
     * @throws IllegalArgumentException if either tetrahedron is pyramid-rooted
     */
    public static byte lowestCommonAncestorLevel(Tet tet1, Tet tet2) {
        requirePureTetree(tet1, "tet1");
        requirePureTetree(tet2, "tet2");

        // Find the level of the NCA using XOR to find differing bits
        int exclorx = tet1.x() ^ tet2.x();
        int exclory = tet1.y() ^ tet2.y();
        int exclorz = tet1.z() ^ tet2.z();

        // Combine all differences
        int maxclor = exclorx | exclory | exclorz;

        if (maxclor == 0) {
            // Same coordinates - they share an ancestor at their minimum level
            return (byte) Math.min(tet1.l(), tet2.l());
        }

        // Find the highest set bit position (SC_LOG2_32 in t8code)
        int maxlevel = 31 - Integer.numberOfLeadingZeros(maxclor) + 1;

        // The level of the NCA cube surrounding tet1 and tet2
        // This matches t8code: c_level = SC_MIN(T8_DTRI_MAXLEVEL - maxlevel, SC_MIN(t1->level, t2->level))
        byte c_level = (byte) Math.min(Constants.getMaxRefinementLevel() - maxlevel, Math.min(tet1.l(), tet2.l()));

        // Check if the types are different at this level (t8code parity)
        // If t1 and t2 have different types at c_level, we need to decrease the level
        // until they have the same type
        byte r_level = c_level;
        byte t1_type_at_l = tet1.computeType(c_level);
        byte t2_type_at_l = tet2.computeType(c_level);

        while (t1_type_at_l != t2_type_at_l && r_level > 0) {
            r_level--;
            t1_type_at_l = tet1.computeType(r_level);
            t2_type_at_l = tet2.computeType(r_level);
        }

        assert r_level >= 0 : "Failed to find common ancestor level";
        return r_level;
    }

    /**
     * Guard against pyramid-rooted tetrahedra reaching {@link Tet#computeType(byte)} unattributed.
     * The deep tet/pyramid path is infrastructure-only (RDR-012) and ancestor-type resolution above
     * the boundary is deferred to Luciferase-q3p; fail loud here, naming the operation.
     */
    private static void requirePureTetree(Tet tet, String arg) {
        if (tet.minTetLevel() != Tet.NO_TET_ANCESTOR) {
            throw new IllegalArgumentException(
            "TetreeBits.lowestCommonAncestorLevel does not support pyramid-rooted tetrahedra: " + arg
            + " has minTetLevel=" + tet.minTetLevel() + " (!= NO_TET_ANCESTOR). Pyramid ancestor-type "
            + "resolution is deferred to Luciferase-q3p.");
        }
    }
}
