/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.pyramid;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.HybridElement;
import com.hellblazer.luciferase.lucien.tetree.Tet;

/**
 * Encoder from a hybrid SFC element to its {@link PyramidKey}: {@link #encode(Pyramid)} (RDR-010 pi1.4
 * Phase A, bead Luciferase-8zv) and {@link #encode(Tet)} for a pyramid-rooted tet leaf — shallowest
 * (RDR-010 pi1.5 Phase A, bead Luciferase-uqik) or deep (RDR-010 cjwr Phase B). Both are the inverse of
 * the canonical decoders on {@link PyramidIndex}
 * ({@link PyramidIndex#pyramidFromKey(PyramidKey)} / {@link PyramidIndex#elementFromKey(PyramidKey)}).
 *
 * <p>The encoder walks the pyramid's {@link Pyramid#parent()} chain to the root, collecting at each
 * refinement step the cube-id (from the anchor's level bit) and the element type, then assembles the
 * key via {@link PyramidKey#fromLevels(byte, int[], int[])}. It is the same coarse-dominant bit layout
 * the decoder consumes, so {@code pyramidFromKey(encode(p)).equals(p)} for every valid SFC pyramid.
 *
 * <p><b>Fail-safe contract.</b> Not every {@code (anchor, level, type)} triple is a reachable element
 * of the pyramid SFC tree (unlike Morton, where every cube cell is valid — pyramid coverage is
 * {@code N(ℓ) = 2·8^ℓ − 6^ℓ}). A non-SFC candidate either trips the {@code "Unreachable pyramid"}
 * {@link IllegalStateException} in {@link Pyramid#parent()} during the walk, or assembles a key that
 * does not decode back to the original. Both outcomes return {@code null} rather than throwing or
 * emitting a bogus key: the internal round-trip self-check ({@code decode(key).equals(p)}) is the
 * safety net. Callers (the neighbor enumerator, {@code addNeighboringNodes}) rely on {@code null} to
 * filter geometric candidates down to genuine SFC elements.
 *
 * <p>Encoder only — this class deliberately does <em>not</em> re-home the decoder
 * ({@code pyramidFromKey} stays on {@link PyramidIndex}); the decoder dedup is tracked separately as
 * bead Luciferase-3y1.
 *
 * @author hal.hildebrand
 */
final class PyramidKeyCodec {

    private PyramidKeyCodec() {
    }

    /**
     * Encode a pyramid to its key, or {@code null} if the pyramid is not a reachable SFC element.
     *
     * @param p the pyramid element (the level-0 type-6 root encodes to {@link PyramidKey#getRoot()};
     *          a level-0 type-7 pyramid is not a distinct SFC element and encodes to {@code null})
     * @return the round-trip-verified key, or {@code null} for a non-SFC / unreachable pyramid
     */
    static PyramidKey encode(Pyramid p) {
        if (p.minTetLevel() != Pyramid.NO_TET_ANCESTOR) {
            // The encoder is defined only for pure-pyramid SFC elements. A hybrid-path pyramid
            // (reached via a tet ancestor) can share geometric identity (x,y,z,level,type) with a
            // pure-pyramid cell — and Pyramid.equals is minTetLevel-blind — so the round-trip
            // self-check would pass against the pure-pyramid reconstruction and silently emit the
            // wrong element's key. Reject up front rather than returning a wrong key.
            return null;
        }
        byte level = p.level();
        if (level == 0) {
            // The SFC root is the virtual type-6 cover (pyramidFromKey returns type 6 at level 0).
            // Route through the same round-trip guard so a type-7 level-0 pyramid — not a distinct
            // SFC element — fails to null rather than aliasing onto the root key.
            PyramidKey rootKey = PyramidKey.getRoot();
            Pyramid decodedRoot = PyramidIndex.pyramidFromKey(rootKey);
            return (decodedRoot != null && decodedRoot.equals(p)) ? rootKey : null;
        }
        // coordBits/typeBits are indexed by refinement step 1..level (index 0 unused; root has no bits).
        int[] coordBits = new int[level + 1];
        int[] typeBits = new int[level + 1];
        Pyramid cur = p;
        try {
            for (int l = level; l >= 1; l--) {
                int h = Constants.lengthAtLevel((byte) l);
                int cubeId = ((cur.x() & h) != 0 ? 1 : 0)
                           | ((cur.y() & h) != 0 ? 2 : 0)
                           | ((cur.z() & h) != 0 ? 4 : 0);
                coordBits[l] = cubeId;
                typeBits[l] = cur.type();
                if (l > 1) {
                    // Stop at l == 1: cur is then the level-1 element whose bits we just took, and
                    // its parent is the bit-less root. May throw "Unreachable pyramid" for a non-SFC
                    // candidate, which the surrounding catch converts to null.
                    cur = cur.parent();
                }
            }
            PyramidKey key = PyramidKey.fromLevels(level, coordBits, typeBits);
            // Round-trip self-check: the decoder is the single source of truth for SFC validity.
            Pyramid decoded = PyramidIndex.pyramidFromKey(key);
            if (decoded == null || !decoded.equals(p)) {
                return null;
            }
            return key;
        } catch (IllegalStateException | IllegalArgumentException | IndexOutOfBoundsException e) {
            // Non-SFC candidate (unreachable parent step, or malformed level/bits). Fail-safe to null.
            return null;
        }
    }

    /**
     * Encode a pyramid-rooted tet leaf to its tet-leaf {@link PyramidKey}, or {@code null} if the tet is
     * not a reachable element of the pyramid SFC (RDR-010 pi1.5 Phase A, bead Luciferase-uqik; deep tets
     * RDR-010 cjwr Phase B). This is the bridge that lets {@code PyramidNeighborDetector} surface a
     * pyramid's tet neighbors (and a tet leaf's own neighbors) as keys.
     *
     * <p><b>Full depth.</b> Both a shallowest tet ({@code minTetLevel == level}, parent is a pyramid) and
     * a deep pyramid-rooted tet ({@code minTetLevel < level}, a tet-of-tet refinement below the boundary)
     * are encodable: the parent walk follows {@link Tet#parentElement()} through the tetrahedral branch
     * and then the pyramid chain to the root. A pure-Tetree tet ({@code minTetLevel == -1}) has no
     * pyramidal ancestor and returns {@code null}; so does the level-0 root (a pyramid, never a tet).
     *
     * <p><b>Fail-safe contract.</b> Mirrors {@link #encode(Pyramid)}: the parent walk may throw on a
     * non-SFC candidate; that is caught and funneled to {@code null}. The internal round-trip self-check
     * ({@code elementFromKey(key)} must recover the same tet geometry) is the single source of truth for
     * validity, so a co-consistent bit error never emits a bogus key.
     *
     * @param t the tetrahedron element
     * @return the round-trip-verified tet-leaf key, or {@code null} for a pure-Tetree / non-SFC tet
     */
    static PyramidKey encode(Tet t) {
        byte level = t.level();
        if (level < 1) {
            return null; // the level-0 root is the pyramid cover, not a tet
        }
        if (t.minTetLevel() == Tet.NO_TET_ANCESTOR) {
            // A pure-Tetree tet has no pyramidal ancestor — it is not an element of the pyramid SFC.
            return null;
        }
        // coordBits/typeBits indexed by refinement step 1..level (index 0 unused; root has no bits).
        int[] coordBits = new int[level + 1];
        int[] typeBits = new int[level + 1];
        int hLeaf = Constants.lengthAtLevel(level);
        coordBits[level] = ((t.x() & hLeaf) != 0 ? 1 : 0)
                         | ((t.y() & hLeaf) != 0 ? 2 : 0)
                         | ((t.z() & hLeaf) != 0 ? 4 : 0);
        typeBits[level] = t.type();
        try {
            // Walk the ancestor chain to the root collecting coarser bits. Through the tetrahedral branch
            // (levels > minTetLevel) the parent is a Tet; at the boundary Tet.parentElement returns the
            // birthing Pyramid, and above that the pure pyramid chain continues to the root.
            HybridElement cur = t.parentElement();
            for (int l = level - 1; l >= 1; l--) {
                int h = Constants.lengthAtLevel((byte) l);
                coordBits[l] = ((cur.x() & h) != 0 ? 1 : 0)
                             | ((cur.y() & h) != 0 ? 2 : 0)
                             | ((cur.z() & h) != 0 ? 4 : 0);
                typeBits[l] = cur.type();
                if (l > 1) {
                    cur = (cur instanceof Tet ct) ? ct.parentElement() : ((Pyramid) cur).parent();
                }
            }
            PyramidKey key = PyramidKey.fromLevels(level, coordBits, typeBits);
            // Round-trip self-check against the leaf-aware decoder: the decoded leaf must be a tet of
            // the same geometric identity (x,y,z,level,type) AND the same minTetLevel. The minTetLevel
            // check matters when a caller probes a geometric position with a candidate minTetLevel it does
            // not actually have (the enumerate-and-filter in PyramidNeighborDetector.allShapeNeighbors,
            // RDR-010 Luciferase-2l04): the decoder derives the true minTetLevel from the path, so a
            // mismatch means the candidate is not the real SFC element at that cell — reject it.
            HybridElement decoded = PyramidIndex.elementFromKey(key);
            if (decoded instanceof Tet dt && dt.x() == t.x() && dt.y() == t.y() && dt.z() == t.z()
                && dt.level() == t.level() && dt.type() == t.type() && dt.minTetLevel() == t.minTetLevel()) {
                return key;
            }
            return null;
        } catch (IllegalStateException | IllegalArgumentException | IndexOutOfBoundsException e) {
            return null;
        }
    }
}
