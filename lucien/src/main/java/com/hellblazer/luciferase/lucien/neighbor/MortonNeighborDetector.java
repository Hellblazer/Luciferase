/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.lucien.neighbor;

import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostType;
import com.hellblazer.luciferase.geometry.MortonCurve;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Neighbor detector implementation for Morton-encoded octrees.
 *
 * <p>This class provides efficient neighbor detection using Morton code
 * manipulation to find face, edge, and vertex neighbors.
 *
 * <h2>Contract: geometric (potential) neighbors</h2>
 * <p>{@link #findFaceNeighbors}, {@link #findEdgeNeighbors}, and
 * {@link #findVertexNeighbors} return <em>geometric</em> neighbor keys: every
 * same-level cell that is topologically adjacent within the global grid and
 * whose coordinates fall within the valid domain {@code [0, MAX_COORD]}.
 * <strong>Existence in the octree is not checked.</strong>  A returned key
 * may correspond to an empty cell (no node present in the octree).
 *
 * <p>This is the standard contract for SFC-based neighbor enumeration: the
 * caller decides whether to filter against octree occupancy.  Ghost-layer and
 * forest consumers that need only <em>occupied</em> neighbors must guard each
 * returned key before consuming it using whichever occupancy predicate the
 * {@link com.hellblazer.luciferase.lucien.SpatialIndex} exposes:
 * {@link com.hellblazer.luciferase.lucien.SpatialIndex#containsSpatialKey}
 * (default delegation to {@code hasNode}) or
 * {@link com.hellblazer.luciferase.lucien.SpatialIndex#hasNode} directly.
 * Both are valid; production callers such as {@code GhostBoundaryDetector}
 * and {@code TwoOneBalanceChecker} use {@code containsSpatialKey}, while
 * set-membership guards (e.g. in {@code PyramidIndex.addNeighboringNodes})
 * are also acceptable.  For cross-process ghost wiring use
 * {@link #findNeighborsWithOwners}, which enforces a real ownership resolver
 * (and therefore fails loudly if none is wired).
 *
 * @author Hal Hildebrand
 */
public class MortonNeighborDetector implements NeighborDetector<MortonKey> {
    
    private static final Logger log = LoggerFactory.getLogger(MortonNeighborDetector.class);
    
    // Offsets for face neighbors (6 faces)
    private static final int[][] FACE_OFFSETS = {
        {1, 0, 0},   // +X
        {-1, 0, 0},  // -X
        {0, 1, 0},   // +Y
        {0, -1, 0},  // -Y
        {0, 0, 1},   // +Z
        {0, 0, -1}   // -Z
    };
    
    // Offsets for edge neighbors (12 edges + 6 faces = 18 total)
    private static final int[][] EDGE_OFFSETS = {
        // Face neighbors
        {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1},
        // Edge neighbors
        {1, 1, 0}, {1, -1, 0}, {-1, 1, 0}, {-1, -1, 0},
        {1, 0, 1}, {1, 0, -1}, {-1, 0, 1}, {-1, 0, -1},
        {0, 1, 1}, {0, 1, -1}, {0, -1, 1}, {0, -1, -1}
    };
    
    // Offsets for vertex neighbors (8 vertices + 12 edges + 6 faces = 26 total)
    private static final int[][] VERTEX_OFFSETS = {
        // Face neighbors
        {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1},
        // Edge neighbors
        {1, 1, 0}, {1, -1, 0}, {-1, 1, 0}, {-1, -1, 0},
        {1, 0, 1}, {1, 0, -1}, {-1, 0, 1}, {-1, 0, -1},
        {0, 1, 1}, {0, 1, -1}, {0, -1, 1}, {0, -1, -1},
        // Vertex neighbors
        {1, 1, 1}, {1, 1, -1}, {1, -1, 1}, {1, -1, -1},
        {-1, 1, 1}, {-1, 1, -1}, {-1, -1, 1}, {-1, -1, -1}
    };
    
    private final Octree<?, ?> octree;

    public MortonNeighborDetector(Octree<?, ?> octree) {
        this.octree = Objects.requireNonNull(octree, "Octree cannot be null");
    }
    
    /**
     * Returns the (up to 6) geometric face neighbors of {@code element}.
     *
     * <p>Keys are computed purely from grid arithmetic; no octree-occupancy
     * check is performed (see class-level contract).
     */
    @Override
    public List<MortonKey> findFaceNeighbors(MortonKey element) {
        return findNeighborsWithOffsets(element, FACE_OFFSETS);
    }

    /**
     * Returns the (up to 18) geometric face-and-edge neighbors of {@code element}.
     *
     * <p>Keys are computed purely from grid arithmetic; no octree-occupancy
     * check is performed (see class-level contract).
     */
    @Override
    public List<MortonKey> findEdgeNeighbors(MortonKey element) {
        return findNeighborsWithOffsets(element, EDGE_OFFSETS);
    }

    /**
     * Returns the (up to 26) geometric face-, edge-, and vertex-neighbors of
     * {@code element}.
     *
     * <p>Keys are computed purely from grid arithmetic; no octree-occupancy
     * check is performed (see class-level contract).
     */
    @Override
    public List<MortonKey> findVertexNeighbors(MortonKey element) {
        return findNeighborsWithOffsets(element, VERTEX_OFFSETS);
    }
    
    @Override
    public boolean isBoundaryElement(MortonKey element, Direction direction) {
        // Decode raw grid coordinates — same convention as MortonKey.neighbor()
        int[] rawCoords = MortonCurve.decode(element.getMortonCode());
        var cellSize = Constants.lengthAtLevel(element.getLevel());

        return switch (direction) {
            case POSITIVE_X -> rawCoords[0] + cellSize > Constants.MAX_COORD;
            case NEGATIVE_X -> rawCoords[0] == 0;
            case POSITIVE_Y -> rawCoords[1] + cellSize > Constants.MAX_COORD;
            case NEGATIVE_Y -> rawCoords[1] == 0;
            case POSITIVE_Z -> rawCoords[2] + cellSize > Constants.MAX_COORD;
            case NEGATIVE_Z -> rawCoords[2] == 0;
        };
    }
    
    @Override
    public Set<Direction> getBoundaryDirections(MortonKey element) {
        var directions = EnumSet.noneOf(Direction.class);
        for (var dir : Direction.values()) {
            if (isBoundaryElement(element, dir)) {
                directions.add(dir);
            }
        }
        return directions;
    }
    
    @Override
    public List<NeighborInfo<MortonKey>> findNeighborsWithOwners(MortonKey element, GhostType type) {
        // No partition/ownership resolver is wired into this detector.
        // Returning isLocal=true with rank=0 for every neighbor would silently
        // degrade the ghost layer in distributed configurations.
        // Fail loud until a real owner-resolver is injected via the constructor.
        throw new UnsupportedOperationException(
            "findNeighborsWithOwners requires a partition ownership resolver that has not been wired into MortonNeighborDetector. "
            + "Either inject an owner-resolver through the constructor or use the local-only neighbor methods "
            + "(findFaceNeighbors/findEdgeNeighbors/findVertexNeighbors) for single-node use. "
            + "Remediation tracked in bead Luciferase-8neqb.");
    }
    
    /**
     * Core geometric neighbor computation shared by all three public find* methods.
     *
     * <p>A candidate neighbor is admitted when its raw grid coordinates satisfy
     * {@code 0 <= coord <= Constants.MAX_COORD} on every axis, which is exactly
     * the valid-coordinate range {@code [0, (1<<21)-1]}.  The upper bound uses
     * {@code <=} (inclusive) so that cells whose origin coordinate equals
     * {@code MAX_COORD} — i.e. cells at the positive domain boundary — are
     * correctly included.  Using strict {@code <} would exclude those cells
     * (off-by-one, bead Luciferase-7wzml.146).
     *
     * <p>No octree-occupancy check is performed; see class-level contract.
     */
    private List<MortonKey> findNeighborsWithOffsets(MortonKey element, int[][] offsets) {
        var neighbors = new ArrayList<MortonKey>();
        var coords = decodeCoordinates(element);
        var cellSize = Constants.lengthAtLevel(element.getLevel());

        for (var offset : offsets) {
            var nx = coords[0] + offset[0] * cellSize;
            var ny = coords[1] + offset[1] * cellSize;
            var nz = coords[2] + offset[2] * cellSize;

            // Check bounds — same convention as MortonKey.neighbor()
            if (nx >= 0 && nx <= Constants.MAX_COORD &&
                ny >= 0 && ny <= Constants.MAX_COORD &&
                nz >= 0 && nz <= Constants.MAX_COORD) {

                var neighborMorton = MortonCurve.encode(nx, ny, nz);
                var neighborKey = new MortonKey(neighborMorton, element.getLevel());
                neighbors.add(neighborKey);
            }
        }

        return neighbors;
    }

    /**
     * Decode Morton code to raw grid coordinates (0..2^21-1), consistent with
     * MortonKey.neighbor() and isBoundaryElement() conventions.
     */
    public int[] decodeCoordinates(MortonKey key) {
        return MortonCurve.decode(key.getMortonCode());
    }
}