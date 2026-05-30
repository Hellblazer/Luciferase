/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU
 * Affero General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero
 * General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.
 * If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien.pyramid;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.HybridElement;
import com.hellblazer.luciferase.lucien.HybridFaceNeighbor;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostType;
import com.hellblazer.luciferase.lucien.neighbor.NeighborDetector;
import com.hellblazer.luciferase.lucien.tetree.Tet;

import javax.vecmath.Point3i;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Cross-shape (pyramid↔tet) topology neighbor detector for the pyramid SFC (RDR-010 pi1.4 same-shape /
 * pi1.5 cross-shape, beads Luciferase-mu9 / Luciferase-9e3a, Knapp 2026 §4.3-4.4). Mirrors
 * {@link com.hellblazer.luciferase.lucien.neighbor.MortonNeighborDetector}: neighbor methods return the
 * geometric same-level neighbor keys (independent of index occupancy), and boundary/owner methods
 * mirror the Morton peer.
 *
 * <p><b>Face neighbors — exact, via element navigation (pi1.5).</b> A pyramid has five faces: the
 * quadrilateral base (f4, same-shape type 6↔7) and four triangular faces (f0-f3, cross-shape — each
 * neighbors a tetrahedron). {@link #findFaceNeighbors} resolves the query key to its leaf element
 * ({@link PyramidIndex#elementFromKey}) and walks the conforming face neighbors directly via
 * {@link Pyramid#faceNeighbor(int)} (5 faces) or {@link Tet#faceNeighborElement(int)} (4 faces),
 * encoding each neighbor to a key (a pyramid key or a tet-leaf key) via {@link PyramidKeyCodec}. A
 * neighbor outside the domain or outside the SFC encodes to {@code null} and is dropped. This is the
 * reciprocity-validatable face topology (the {@link HybridFaceNeighbor#face()} reciprocal index), not a
 * shared-vertex heuristic.
 *
 * <p><b>Shallow boundary only.</b> Cross-shape descends to the shallowest pyramid↔tet boundary
 * ({@code l == minTetLevel}). A deep pyramid-rooted tet ({@code l > minTetLevel}) trips
 * {@link Tet#faceNeighborElement}'s fail-loud guard (RDR-010 Finding #16, deferred to q3p Phase E);
 * the detector <em>catches and skips</em> that face rather than propagating — a thrown exception would
 * break the BFS in {@code KnnSearcher}/{@code CollisionEngine}.
 *
 * <p><b>Edge/vertex — bounded cumulative supersets (pi1.5).</b> Edge and vertex add the same-shape
 * (pyramid↔pyramid) geometric neighbors at shared-vertex thresholds 2 and 1 (the 27-cube enumeration
 * below) on top of the cross-shape face set, preserving face ⊆ edge ⊆ vertex. <em>Exhaustive
 * cross-shape edge/vertex adjacency</em> (tet-tet edge sharing, pyramid-tet vertex fans) is a
 * registered deferral — bead Luciferase-0utt — not silent scope reduction: ghost {@code FACES}
 * exchange needs only the face set, which is exact here.
 *
 * <p><b>Same-shape enumeration (edge/vertex contribution).</b> For each of the 27 cube offsets
 * {@code (dx,dy,dz) ∈ {-1,0,+1}³} and each pyramid type {@code {6,7}}, a candidate same-level pyramid is
 * built, filtered to genuine SFC elements via {@link PyramidKeyCodec#encode} (non-SFC → {@code null}),
 * and classified by shared-vertex count against the query element (edge ≥ 2, vertex ≥ 1). The
 * shared-vertex test is valid for the conforming same-shape pyramid topology; it is deliberately NOT
 * applied to tet faces (Bey-SFC tet faces share 0-3 vertices — see CLAUDE.md face-neighbor caveat).
 *
 * @author Hal Hildebrand
 */
public final class PyramidNeighborDetector implements NeighborDetector<PyramidKey> {

    // Same-shape shared-vertex thresholds for the edge/vertex superset contribution. (Face neighbors
    // are computed exactly via element navigation, not by a shared-vertex threshold.)
    private static final int EDGE_SHARED_VERTICES   = 2;
    private static final int VERTEX_SHARED_VERTICES = 1;

    /** Retained for the pi1.5 ghost/ownership wiring; the same-shape paths here need only geometry. */
    private final PyramidIndex<?, ?> index;

    public PyramidNeighborDetector(PyramidIndex<?, ?> index) {
        this.index = Objects.requireNonNull(index, "PyramidIndex cannot be null");
    }

    @Override
    public List<PyramidKey> findFaceNeighbors(PyramidKey element) {
        // Exact conforming face topology via element navigation (pyramid: 5 faces; tet leaf: 4 faces).
        return new ArrayList<>(crossShapeFaceNeighbors(element));
    }

    @Override
    public List<PyramidKey> findEdgeNeighbors(PyramidKey element) {
        // Bounded superset: cross-shape faces ∪ same-shape edge neighbors (shared ≥ 2). Exhaustive
        // cross-shape edge adjacency is deferred (bead Luciferase-0utt).
        return unionFaceWithSameShape(element, EDGE_SHARED_VERTICES);
    }

    @Override
    public List<PyramidKey> findVertexNeighbors(PyramidKey element) {
        // Bounded superset: cross-shape faces ∪ same-shape vertex neighbors (shared ≥ 1). Exhaustive
        // cross-shape vertex adjacency is deferred (bead Luciferase-0utt).
        return unionFaceWithSameShape(element, VERTEX_SHARED_VERTICES);
    }

    /**
     * Cross-shape face set unioned with the same-shape neighbors at {@code minSharedVertices},
     * insertion-ordered and de-duplicated, preserving face ⊆ edge ⊆ vertex.
     */
    private List<PyramidKey> unionFaceWithSameShape(PyramidKey element, int minSharedVertices) {
        var union = new LinkedHashSet<>(crossShapeFaceNeighbors(element));
        union.addAll(sameShapeNeighbors(element, minSharedVertices));
        return new ArrayList<>(union);
    }

    /**
     * The exact conforming face neighbors of {@code element}'s leaf, as keys. Resolves the leaf via
     * {@link PyramidIndex#elementFromKey} (a {@link Pyramid} or a shallowest {@link Tet}) and walks
     * {@link Pyramid#faceNeighbor(int)} / {@link Tet#faceNeighborElement(int)}. A deep pyramid-rooted
     * tet's fail-loud guard is caught and that face skipped (BFS-safe; deep cross-shape deferred to q3p
     * Phase E). Out-of-domain / non-SFC neighbors encode to {@code null} and are dropped.
     */
    private Set<PyramidKey> crossShapeFaceNeighbors(PyramidKey element) {
        HybridElement self = PyramidIndex.elementFromKey(element);
        if (self == null || self.level() == 0) {
            // The level-0 root is the virtual domain cover; it has no same-level face neighbors (and
            // Pyramid.faceNeighbor on a level-0 pyramid would build an invalid level-0 element).
            return Set.of();
        }
        var out = new LinkedHashSet<PyramidKey>();
        if (self instanceof Pyramid p) {
            for (int f = 0; f < Pyramid.FACES; f++) {
                addFaceNeighbor(p.faceNeighbor(f), element, out);
            }
        } else if (self instanceof Tet t) {
            for (int f = 0; f < 4; f++) {
                HybridFaceNeighbor fn;
                try {
                    fn = t.faceNeighborElement(f);
                } catch (IllegalStateException deepTet) {
                    // Defensive: a deep pyramid-rooted tet (l > minTetLevel) trips Tet.faceNeighborElement's
                    // fail-loud guard (RDR-010 Finding #16, q3p Phase E). In practice this is unreachable
                    // via a key — encode(Tet) rejects deep tets and elementFromKey cannot reconstruct one,
                    // so `self` here is always a SHALLOW tet (l == minTetLevel). The catch guards against
                    // future change: propagating would break the BFS in KnnSearcher/CollisionEngine.
                    continue;
                }
                addFaceNeighbor(fn, element, out);
            }
        }
        return out;
    }

    /** Encode a face neighbor's element and add its key (skipping null / self / duplicates). */
    private static void addFaceNeighbor(HybridFaceNeighbor fn, PyramidKey selfKey, Set<PyramidKey> out) {
        if (fn == null) {
            return;
        }
        PyramidKey key = encodeElement(fn.element());
        if (key != null && !key.equals(selfKey)) {
            out.add(key);
        }
    }

    /** Encode a hybrid leaf element to its key via the shape-appropriate {@link PyramidKeyCodec} path. */
    private static PyramidKey encodeElement(HybridElement e) {
        if (e instanceof Pyramid p) {
            return PyramidKeyCodec.encode(p);
        }
        if (e instanceof Tet t) {
            return PyramidKeyCodec.encode(t);
        }
        return null;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Computed from the element's surrounding-cube extent. Behavior is undefined for a tet-leaf key
     * (the anchor is the enclosing pyramid's, not the physical tet's) — that case is part of the pi1.5
     * cross-shape work; callers with unknown keys should confirm the key is a pyramid first.
     */
    @Override
    public boolean isBoundaryElement(PyramidKey element, Direction direction) {
        int[] anchor = anchorOf(element);
        int len = Constants.lengthAtLevel(element.getLevel());
        return switch (direction) {
            case POSITIVE_X -> anchor[0] + len > Constants.MAX_COORD;
            case NEGATIVE_X -> anchor[0] == 0;
            case POSITIVE_Y -> anchor[1] + len > Constants.MAX_COORD;
            case NEGATIVE_Y -> anchor[1] == 0;
            case POSITIVE_Z -> anchor[2] + len > Constants.MAX_COORD;
            case NEGATIVE_Z -> anchor[2] == 0;
        };
    }

    @Override
    public Set<Direction> getBoundaryDirections(PyramidKey element) {
        var dirs = EnumSet.noneOf(Direction.class);
        for (var dir : Direction.values()) {
            if (isBoundaryElement(element, dir)) {
                dirs.add(dir);
            }
        }
        return dirs;
    }

    @Override
    public List<NeighborInfo<PyramidKey>> findNeighborsWithOwners(PyramidKey element, GhostType type) {
        var neighbors = findNeighbors(element, type);
        var result = new ArrayList<NeighborInfo<PyramidKey>>(neighbors.size());
        for (var neighbor : neighbors) {
            // Same-shape, single-tree scope: all neighbors are local. Distributed ownership resolution
            // (cross-rank, cross-tree) lands with the ghost wiring in pi1.5.
            result.add(new NeighborInfo<>(neighbor, 0, 0, true));
        }
        return result;
    }

    /**
     * Same-shape (pyramid↔pyramid) geometric enumeration for the edge/vertex superset contribution.
     * Returns the same-level pyramid keys that share at least {@code minSharedVertices} vertices with
     * the query element. A tet-leaf or non-decodable key yields an empty list (so for a tet-leaf key the
     * edge/vertex sets equal the cross-shape face set — exhaustive cross-shape edge/vertex topology is
     * deferred to bead Luciferase-0utt); never throws.
     */
    private List<PyramidKey> sameShapeNeighbors(PyramidKey element, int minSharedVertices) {
        Pyramid self = resolvePyramid(element);
        if (self == null) {
            return List.of(); // tet-leaf key: same-shape edge/vertex N/A (exhaustive → bead Luciferase-0utt)
        }
        int len = self.length();
        Point3i[] selfVerts = self.coordinates();
        byte level = self.level();
        var neighbors = new ArrayList<PyramidKey>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int nx = self.x() + dx * len;
                    int ny = self.y() + dy * len;
                    int nz = self.z() + dz * len;
                    if (nx < 0 || ny < 0 || nz < 0 || nx > Constants.MAX_COORD || ny > Constants.MAX_COORD
                        || nz > Constants.MAX_COORD) {
                        continue;
                    }
                    for (byte candType = Pyramid.TYPE_6; candType <= Pyramid.TYPE_7; candType++) {
                        var cand = new Pyramid(nx, ny, nz, level, candType);
                        if (cand.equals(self)) {
                            continue; // self
                        }
                        if (sharedVertexCount(selfVerts, cand.coordinates()) < minSharedVertices) {
                            continue;
                        }
                        var key = PyramidKeyCodec.encode(cand);
                        if (key != null) { // null ⇒ not a genuine SFC element
                            neighbors.add(key);
                        }
                    }
                }
            }
        }
        return neighbors;
    }

    /**
     * Decode {@code element} to its pyramid, or {@code null} if it is a tet leaf (cross-shape, pi1.5)
     * or the root (no same-level neighbors). The key's leaf type bit is authoritative for shape —
     * {@link PyramidIndex#pyramidFromKey} returns the enclosing parent pyramid for a deep tet-leaf
     * key, so a {@code null} from it is not a reliable tet-leaf signal on its own.
     */
    private static Pyramid resolvePyramid(PyramidKey element) {
        byte level = element.getLevel();
        if (level == 0) {
            return null; // root spans the domain; no same-level neighbors
        }
        byte leafType = element.getTypeAtLevel(level);
        if (leafType != Pyramid.TYPE_6 && leafType != Pyramid.TYPE_7) {
            return null; // tet leaf: same-shape enumeration N/A (cross-shape faces handled separately)
        }
        Pyramid self = PyramidIndex.pyramidFromKey(element);
        if (self == null || self.level() != level || self.type() != leafType) {
            return null; // defensive: decode did not resolve to the leaf pyramid
        }
        return self;
    }

    /** Count vertices shared (by exact integer coordinate) between two pyramid vertex sets. */
    private static int sharedVertexCount(Point3i[] a, Point3i[] b) {
        int shared = 0;
        for (Point3i pa : a) {
            for (Point3i pb : b) {
                if (pa.equals(pb)) {
                    shared++;
                    break;
                }
            }
        }
        return shared;
    }

    /** The surrounding-cube anchor of {@code element}, accumulated from its per-level cube-ids. */
    private static int[] anchorOf(PyramidKey element) {
        int ax = 0, ay = 0, az = 0;
        byte level = element.getLevel();
        for (int l = 1; l <= level; l++) {
            int len = Constants.lengthAtLevel((byte) l);
            int cubeId = element.getCoordBitsAtLevel(l);
            if ((cubeId & 1) != 0) {
                ax += len;
            }
            if ((cubeId & 2) != 0) {
                ay += len;
            }
            if ((cubeId & 4) != 0) {
                az += len;
            }
        }
        return new int[] { ax, ay, az };
    }
}
