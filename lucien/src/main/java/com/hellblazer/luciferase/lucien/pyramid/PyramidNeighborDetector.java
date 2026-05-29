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

import com.hellblazer.luciferase.lucien.forest.ghost.GhostType;
import com.hellblazer.luciferase.lucien.neighbor.NeighborDetector;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Phase-A stub for the pyramid topology neighbor detector. Mirrors the Octree/Tetree construction
 * pattern where the index wires a {@link NeighborDetector} for its key type. The real implementation
 * (face/edge/vertex topology for pyramid 4-tri + 1-quad faces, Knapp §4.3-4.4 cross-shape neighbor
 * dispatch) lands in RDR-010 bead {@code Luciferase-pi1.4} (P4: PyramidNeighborDetector). Every method
 * here fails loud with the bead reference so any code path that reaches it during Phases B-E is
 * immediately surfaced rather than silently returning empty/wrong neighbor sets.
 */
public final class PyramidNeighborDetector implements NeighborDetector<PyramidKey> {
    private final PyramidIndex<?, ?> index;

    public PyramidNeighborDetector(PyramidIndex<?, ?> index) {
        this.index = Objects.requireNonNull(index, "PyramidIndex cannot be null");
    }

    private static UnsupportedOperationException stub(String method) {
        return new UnsupportedOperationException(
        "RDR-010 pi1.4: PyramidNeighborDetector." + method + " — bead Luciferase-pi1.4");
    }

    @Override
    public List<PyramidKey> findFaceNeighbors(PyramidKey element) {
        throw stub("findFaceNeighbors");
    }

    @Override
    public List<PyramidKey> findEdgeNeighbors(PyramidKey element) {
        throw stub("findEdgeNeighbors");
    }

    @Override
    public List<PyramidKey> findVertexNeighbors(PyramidKey element) {
        throw stub("findVertexNeighbors");
    }

    @Override
    public boolean isBoundaryElement(PyramidKey element, Direction direction) {
        throw stub("isBoundaryElement");
    }

    @Override
    public Set<Direction> getBoundaryDirections(PyramidKey element) {
        throw stub("getBoundaryDirections");
    }

    @Override
    public List<NeighborInfo<PyramidKey>> findNeighborsWithOwners(PyramidKey element, GhostType type) {
        throw stub("findNeighborsWithOwners");
    }
}
