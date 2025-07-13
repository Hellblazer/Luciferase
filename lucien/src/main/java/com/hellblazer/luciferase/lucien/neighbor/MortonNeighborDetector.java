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
 * This class provides efficient neighbor detection using Morton code
 * manipulation to find face, edge, and vertex neighbors.
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
    private final long maxCoordinate;
    
    public MortonNeighborDetector(Octree<?, ?> octree) {
        this.octree = Objects.requireNonNull(octree, "Octree cannot be null");
        this.maxCoordinate = (1L << Constants.getMaxRefinementLevel()) - 1;
    }
    
    @Override
    public List<MortonKey> findFaceNeighbors(MortonKey element) {
        return findNeighborsWithOffsets(element, FACE_OFFSETS);
    }
    
    @Override
    public List<MortonKey> findEdgeNeighbors(MortonKey element) {
        return findNeighborsWithOffsets(element, EDGE_OFFSETS);
    }
    
    @Override
    public List<MortonKey> findVertexNeighbors(MortonKey element) {
        return findNeighborsWithOffsets(element, VERTEX_OFFSETS);
    }
    
    @Override
    public boolean isBoundaryElement(MortonKey element, Direction direction) {
        // Decode coordinates directly - these are actual coordinate values
        int[] rawCoords = MortonCurve.decode(element.getMortonCode());
        var cellSize = 1L << (Constants.getMaxRefinementLevel() - element.getLevel());
        
        return switch (direction) {
            case POSITIVE_X -> rawCoords[0] + cellSize > maxCoordinate;
            case NEGATIVE_X -> rawCoords[0] == 0;
            case POSITIVE_Y -> rawCoords[1] + cellSize > maxCoordinate;
            case NEGATIVE_Y -> rawCoords[1] == 0;
            case POSITIVE_Z -> rawCoords[2] + cellSize > maxCoordinate;
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
        var neighbors = findNeighbors(element, type);
        var result = new ArrayList<NeighborInfo<MortonKey>>(neighbors.size());
        
        for (var neighbor : neighbors) {
            // For now, assume all neighbors are local
            // This will be extended when distributed support is added
            result.add(new NeighborInfo<>(neighbor, 0, 0, true));
        }
        
        return result;
    }
    
    private List<MortonKey> findNeighborsWithOffsets(MortonKey element, int[][] offsets) {
        var neighbors = new ArrayList<MortonKey>();
        var coords = decodeCoordinates(element);
        var cellSize = 1L << (Constants.getMaxRefinementLevel() - element.getLevel());
        
        for (var offset : offsets) {
            var nx = coords[0] + offset[0] * cellSize;
            var ny = coords[1] + offset[1] * cellSize;
            var nz = coords[2] + offset[2] * cellSize;
            
            // Check bounds
            if (nx >= 0 && nx < maxCoordinate &&
                ny >= 0 && ny < maxCoordinate &&
                nz >= 0 && nz < maxCoordinate) {
                
                var neighborMorton = encodeMorton(nx, ny, nz);
                var neighborKey = new MortonKey(neighborMorton, element.getLevel());
                neighbors.add(neighborKey);
            }
        }
        
        return neighbors;
    }
    
    /**
     * Decode Morton code to coordinates.
     */
    public long[] decodeCoordinates(MortonKey key) {
        // Use the proper MortonCurve decode
        int[] decoded = MortonCurve.decode(key.getMortonCode());
        
        // Shift to correct level
        var shift = Constants.getMaxRefinementLevel() - key.getLevel();
        return new long[] {
            (long)decoded[0] << shift,
            (long)decoded[1] << shift,
            (long)decoded[2] << shift
        };
    }
    
    /**
     * Encode coordinates to Morton code.
     */
    private long encodeMorton(long x, long y, long z) {
        // Use the proper MortonCurve encode
        return MortonCurve.encode((int)x, (int)y, (int)z);
    }
}