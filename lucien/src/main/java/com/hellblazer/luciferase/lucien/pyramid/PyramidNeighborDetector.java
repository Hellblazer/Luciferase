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
import com.hellblazer.luciferase.lucien.forest.ghost.GhostType;
import com.hellblazer.luciferase.lucien.neighbor.NeighborDetector;

import javax.vecmath.Point3i;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Same-shape (pyramid↔pyramid) topology neighbor detector for the pyramid SFC (RDR-010 pi1.4,
 * bead Luciferase-mu9, Knapp 2026 §4.3-4.4). Mirrors {@link com.hellblazer.luciferase.lucien.neighbor.MortonNeighborDetector}:
 * neighbor methods return the geometric same-level neighbor keys (independent of index occupancy), and
 * boundary/owner methods mirror the Morton peer.
 *
 * <p><b>Same-shape only (pi1.4 scope).</b> A pyramid's only same-shape face is the quadrilateral base
 * (f4, type 6↔7); its four triangular faces neighbor tetrahedra, and a {@link PyramidKey} can also
 * encode a tet leaf (type 0-5). Cross-shape neighbor finding (pyramid↔tet↔hex) and tet-leaf neighbor
 * topology are deferred to pi1.5 (bead Luciferase-pi1.5): for a tet-leaf key this detector returns an
 * empty result rather than throwing, so ghost/kNN wiring is not broken.
 *
 * <p><b>Unified enumeration.</b> All three neighbor queries share one geometric pass: for each of the
 * 27 cube offsets {@code (dx,dy,dz) ∈ {-1,0,+1}³} and each pyramid type {@code {6,7}}, a candidate
 * same-level pyramid is built, filtered to genuine SFC elements via {@link PyramidKeyCodec#encode}
 * (a non-SFC candidate encodes to {@code null}), and classified by shared-vertex count against the
 * query element. The buckets are cumulative supersets per the {@link NeighborDetector} contract:
 * <ul>
 *   <li>face   — shared ≥ 4 (a conforming quad base; two distinct same-level pyramids share ≥3
 *                vertices only across the quad base, hence exactly 4)</li>
 *   <li>edge   — shared ≥ 2 (⊇ face)</li>
 *   <li>vertex — shared ≥ 1 (⊇ edge)</li>
 * </ul>
 *
 * @author Hal Hildebrand
 */
public final class PyramidNeighborDetector implements NeighborDetector<PyramidKey> {

    private static final int FACE_SHARED_VERTICES   = 4;
    private static final int EDGE_SHARED_VERTICES   = 2;
    private static final int VERTEX_SHARED_VERTICES = 1;

    /** Retained for the pi1.5 ghost/ownership wiring; the same-shape paths here need only geometry. */
    private final PyramidIndex<?, ?> index;

    public PyramidNeighborDetector(PyramidIndex<?, ?> index) {
        this.index = Objects.requireNonNull(index, "PyramidIndex cannot be null");
    }

    @Override
    public List<PyramidKey> findFaceNeighbors(PyramidKey element) {
        return sameShapeNeighbors(element, FACE_SHARED_VERTICES);
    }

    @Override
    public List<PyramidKey> findEdgeNeighbors(PyramidKey element) {
        return sameShapeNeighbors(element, EDGE_SHARED_VERTICES);
    }

    @Override
    public List<PyramidKey> findVertexNeighbors(PyramidKey element) {
        return sameShapeNeighbors(element, VERTEX_SHARED_VERTICES);
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
     * Unified geometric same-shape enumeration. Returns the same-level pyramid keys that share at
     * least {@code minSharedVertices} vertices with the query element. A tet-leaf or non-decodable key
     * yields an empty list (pi1.5 deferral; never throws).
     */
    private List<PyramidKey> sameShapeNeighbors(PyramidKey element, int minSharedVertices) {
        Pyramid self = resolvePyramid(element);
        if (self == null) {
            return List.of(); // tet-leaf / cross-shape — deferred to pi1.5
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
            return null; // tet leaf — deferred to pi1.5
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
