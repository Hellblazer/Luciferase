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
 * <p><b>Face neighbors: full depth.</b> {@link Tet#faceNeighborElement} resolves both the shallowest
 * pyramid↔tet boundary ({@code l == minTetLevel}) and deep pyramid-rooted tets ({@code l > minTetLevel})
 * via the t8code {@code t8_dpyramid_tet_boundary} corner-walk (RDR-010 Luciferase-cjwr). The codec
 * round-trips deep tet keys too (cjwr Phase B); in practice the detector still only holds shallow tet
 * keys because the index locate primitive stops at the shallowest tet leaf (deep tet keys are not
 * inserted until that primitive is extended) — but the face topology itself is no longer depth-limited.
 *
 * <p><b>Edge/vertex — exhaustive cross-shape cumulative supersets (RDR-010 Luciferase-0utt / full-depth
 * Luciferase-2l04).</b> Edge and vertex enumerate ALL same-level SFC elements — pyramid (6/7)
 * <em>and</em> tet (0-5) at every pyramidal-branch depth (shallowest <em>and</em> deep, RDR-010
 * Luciferase-2l04) — in the 27-cube neighbourhood, classified by shared-vertex count (≥ 2 edge, ≥ 1
 * vertex) and unioned with the cross-shape face set, preserving face ⊆ edge ⊆ vertex. This surfaces
 * tet↔tet edge sharing and pyramid↔tet vertex fans (the pi1.5 superset enumerated same-shape pyramids
 * only). Both a deep-tet query and deep-tet neighbors are now in scope (encode round-trips deep tet keys,
 * cjwr Phase B). ghost {@code FACES} exchange still needs only the (exact) face set.
 *
 * <p><b>All-shape enumeration (edge/vertex contribution).</b> For each of the 27 cube offsets
 * {@code (dx,dy,dz) ∈ {-1,0,+1}³}: each pyramid type {@code {6,7}}, and each tet type {@code {0..5}} at
 * each candidate depth {@code minTetLevel ∈ [1, level]}, a same-level candidate is built, filtered to a
 * genuine SFC element via {@link PyramidKeyCodec#encode} (non-SFC / wrong-depth → {@code null}; the
 * encode round-trip pins the unique valid {@code minTetLevel} per cell), and classified by shared-vertex
 * count against the query element (edge ≥ 2, vertex ≥ 1). The shared-vertex test is valid for the
 * conforming same-shape pyramid topology; it is deliberately NOT
 * applied to tet faces (Bey-SFC tet faces share 0-3 vertices — see CLAUDE.md face-neighbor caveat).
 *
 * <p><b>Deep cross-shape is infrastructure-only (RDR-012 D2).</b> The deep ({@code l > minTetLevel})
 * cross-shape topology this detector can compute (deep face neighbors, and deep-tet edge/vertex via
 * {@link #allShapeNeighbors}) is validated but <em>not consumed by live index operation</em>:
 * {@link PyramidIndex} locate stops at the shallowest tet leaf, so deep tet keys are never inserted
 * (pinned by {@code PyramidBoundaryPinningTest}). The production-live path is the shallow
 * ({@code l == minTetLevel}) hex↔tet boundary. RDR-012 (accepted 2026-05-31) kept the deep path
 * infrastructure-only; productionization is D1 (reopen-only).
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
        // Exhaustive cross-shape edge adjacency (RDR-010 Luciferase-0utt): cross-shape faces ∪ all-shape
        // (pyramid+tet) elements sharing ≥ 2 vertices, all pyramidal-branch depths (deep tets included,
        // RDR-010 Luciferase-2l04).
        return unionFaceWithAllShape(element, EDGE_SHARED_VERTICES);
    }

    @Override
    public List<PyramidKey> findVertexNeighbors(PyramidKey element) {
        // Exhaustive cross-shape vertex adjacency (RDR-010 Luciferase-0utt): cross-shape faces ∪ all-shape
        // (pyramid+tet) elements sharing ≥ 1 vertex, all pyramidal-branch depths (deep tets included,
        // RDR-010 Luciferase-2l04).
        return unionFaceWithAllShape(element, VERTEX_SHARED_VERTICES);
    }

    /**
     * Cross-shape face set unioned with the all-shape (pyramid+tet) edge/vertex neighbors at
     * {@code minSharedVertices}, insertion-ordered and de-duplicated, preserving face ⊆ edge ⊆ vertex.
     */
    private List<PyramidKey> unionFaceWithAllShape(PyramidKey element, int minSharedVertices) {
        var union = new LinkedHashSet<>(crossShapeFaceNeighbors(element));
        union.addAll(allShapeNeighbors(element, minSharedVertices));
        return new ArrayList<>(union);
    }

    /**
     * The exact conforming face neighbors of {@code element}'s leaf, as keys. Resolves the leaf via
     * {@link PyramidIndex#elementFromKey} (a {@link Pyramid} or a shallowest {@link Tet}) and walks
     * {@link Pyramid#faceNeighbor(int)} / {@link Tet#faceNeighborElement(int)} (deep pyramid-rooted tets
     * resolved via the cjwr corner-walk). Out-of-domain / non-SFC neighbors encode to {@code null} and
     * are dropped.
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
                // faceNeighborElement now resolves deep pyramid-rooted tets too (RDR-010 Luciferase-cjwr,
                // t8code t8_dpyramid_tet_boundary corner-walk); no fail-loud guard to catch. In practice
                // `self` is still a shallow tet here because the index locate primitive stops at the
                // shallowest tet leaf, so deep tet keys are not inserted into the index.
                addFaceNeighbor(t.faceNeighborElement(f), element, out);
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
            // Local-only for ALL ghost types (FACES, EDGES, VERTEXES) by design — the detector is purely
            // geometric. Distributed cross-rank ownership is assigned externally via the inverted seam
            // (GhostBoundaryDetector.setElementOwner), NOT resolved here (RDR-010 pi1.5, Luciferase-703).
            // So edge/vertex ghost exchange (Luciferase-0utt) follows the same external-ownership model as
            // faces: the geometric neighbour set is correct; rank resolution is the seam's job.
            result.add(new NeighborInfo<>(neighbor, 0, 0, true));
        }
        return result;
    }

    /**
     * Exhaustive cross-shape (pyramid↔pyramid, pyramid↔tet, tet↔tet) edge/vertex enumeration (RDR-010,
     * bead Luciferase-0utt; full-depth Luciferase-2l04). Returns every same-level SFC element — pyramid
     * (type 6/7) <em>or</em> tet (type 0-5) at <em>any</em> pyramidal-branch depth (shallowest
     * {@code minTetLevel == level} and deep {@code minTetLevel < level}) — in the 27-cube neighbourhood
     * that shares at least {@code minSharedVertices} vertices with the query element's leaf.
     *
     * <p>Shared-vertex count is a <em>conservative superset</em> classifier for edge (≥ 2 shared
     * vertices) and vertex (≥ 1) adjacency, honouring the {@link NeighborDetector} cumulative-superset
     * contract. It is not exact for edges: a pyramid's two base-diagonal corners share no pyramid edge,
     * so two elements sharing exactly that diagonal pair are counted as edge neighbours without a shared
     * geometric edge. Over-inclusion is safe for the BFS/ghost consumers (they tolerate extra neighbours;
     * never a false negative). Faces are NOT classified this way (Bey-SFC tet faces share 0-3 vertices) —
     * they are handled separately by {@link #crossShapeFaceNeighbors} and unioned in. Candidates are
     * filtered to genuine SFC elements via {@link PyramidKeyCodec#encode} (a non-SFC anchor/type, or a
     * tet at a pyramidal-branch depth it does not actually have, encodes to {@code null}). Works for a
     * pyramid <em>or</em> a tet-leaf query (vertices taken from the decoded leaf). Never throws.
     *
     * @implNote Cost is O(27 · 6 · level) {@code encode} probes per call, each a parent-chain walk of
     *           O(level), i.e. O(level²) overall — a (cube, type) cell that passes the geometric gate but
     *           has no tet at any depth scans all {@code level} depths. Cheap at the shallow levels of a
     *           cross-shape boundary; for deep BFS over a refined tree this is the dominant per-node cost.
     */
    private List<PyramidKey> allShapeNeighbors(PyramidKey element, int minSharedVertices) {
        HybridElement self = PyramidIndex.elementFromKey(element);
        Point3i[] selfVerts;
        if (self instanceof Pyramid p) {
            selfVerts = p.coordinates();
        } else if (self instanceof Tet t) {
            selfVerts = t.coordinates();
        } else {
            return List.of(); // root / non-decodable — no same-level neighbours
        }
        int len = self.length();
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
                    // Pyramid candidates (type 6/7).
                    for (byte candType = Pyramid.TYPE_6; candType <= Pyramid.TYPE_7; candType++) {
                        addCandidate(new Pyramid(nx, ny, nz, level, candType), selfVerts, minSharedVertices,
                                     element, neighbors);
                    }
                    // Tet candidates (type 0-5) at every pyramidal-branch depth (RDR-010 Luciferase-2l04).
                    // Tet.coordinates() is minTetLevel-independent, so the shared-vertex gate is evaluated
                    // ONCE per (cube, type); only for a genuine geometric neighbour do we probe the
                    // pyramidal-branch depths minTetLevel ∈ [1, level] — covering the shallowest tet
                    // (minTetLevel == level) and every deep pyramid-rooted tet (minTetLevel < level).
                    // (anchor, level, type) does not pin minTetLevel; encode() round-trip-filters each
                    // depth, and the hybrid partition tiles space once, so at most one depth survives.
                    for (byte tetType = 0; tetType < 6; tetType++) {
                        var repVerts = new Tet(nx, ny, nz, level, tetType, level).coordinates();
                        if (sharedVertexCount(selfVerts, repVerts) < minSharedVertices) {
                            continue;
                        }
                        // Probe depths shallowest-first (minTetLevel == level is the common case in a
                        // sparsely-refined tree), so the typical hit is the first encode.
                        for (byte mtl = level; mtl >= 1; mtl--) {
                            PyramidKey key = encodeElement(new Tet(nx, ny, nz, level, tetType, mtl));
                            if (key != null) {
                                // The hybrid partition tiles space once → a (cube, level, type) cell has
                                // exactly one valid pyramidal-branch depth, so the first non-null encode is
                                // the unique SFC tet there; stop probing other depths.
                                if (!key.equals(element)) {
                                    neighbors.add(key);
                                }
                                break;
                            }
                        }
                    }
                }
            }
        }
        return neighbors;
    }

    /** Add {@code cand}'s key if it is a genuine SFC element, not the query, and shares ≥ min vertices. */
    private static void addCandidate(HybridElement cand, Point3i[] selfVerts, int minSharedVertices,
                                     PyramidKey selfKey, List<PyramidKey> out) {
        Point3i[] candVerts = cand instanceof Pyramid p ? p.coordinates() : ((Tet) cand).coordinates();
        if (sharedVertexCount(selfVerts, candVerts) < minSharedVertices) {
            return;
        }
        PyramidKey key = encodeElement(cand);
        if (key != null && !key.equals(selfKey)) {
            out.add(key);
        }
    }

    /** Count vertices shared (by exact integer coordinate) between two vertex sets. */
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
