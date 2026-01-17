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
package com.hellblazer.luciferase.simulation.bubble;

import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import com.hellblazer.luciferase.simulation.entity.StringEntityID;
import com.hellblazer.luciferase.simulation.entity.StringEntityIDGenerator;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A Forest of 6 Tetree instances representing the S0-S5 tetrahedral decomposition of a cube.
 * <p>
 * The S0-S5 subdivision divides a cube into 6 tetrahedra that completely tile the space:
 * <ul>
 *   <li>S0 (type 0): vertices {0,1,3,7}</li>
 *   <li>S1 (type 1): vertices {0,2,3,7}</li>
 *   <li>S2 (type 2): vertices {0,4,5,7}</li>
 *   <li>S3 (type 3): vertices {0,4,6,7}</li>
 *   <li>S4 (type 4): vertices {0,1,5,7}</li>
 *   <li>S5 (type 5): vertices {0,2,6,7}</li>
 * </ul>
 * All tetrahedra share vertices V0 (origin) and V7 (opposite corner).
 * <p>
 * Each tetrahedral region becomes an animation bubble for load balancing.
 *
 * @author hal.hildebrand
 */
public class CubeForest {

    /** Bubble instances mapped to tetrahedral types (S0-S5) */
    private final Map<Byte, EnhancedBubble> bubbles;

    /** World space bounds */
    private final float worldMin;
    private final float worldMax;

    /**
     * Create a CubeForest with 6 tetrahedral bubbles tiling the world space.
     *
     * @param worldMin Minimum coordinate in world space
     * @param worldMax Maximum coordinate in world space
     * @param maxLevel Maximum Tetree refinement level (unused, kept for API compatibility)
     * @param targetFrameMs Target frame time budget per bubble
     */
    public CubeForest(float worldMin, float worldMax, byte maxLevel, long targetFrameMs) {
        this.worldMin = worldMin;
        this.worldMax = worldMax;
        this.bubbles = new ConcurrentHashMap<>();

        // Create 6 bubbles, one for each S0-S5 tetrahedral type
        for (byte type = 0; type < 6; type++) {
            var bubble = new EnhancedBubble(UUID.randomUUID(), (byte) 0, targetFrameMs);
            bubbles.put(type, bubble);
        }
    }

    /**
     * Classify a world-space point into one of the 6 S0-S5 tetrahedral types.
     * <p>
     * Uses the deterministic classification algorithm:
     * 1. Primary: Which coordinate dominates (X/Y/Z)?
     * 2. Secondary: Upper or lower diagonal split?
     *
     * @param worldPos Position in world space
     * @return Tetrahedral type (0-5)
     */
    public byte classifyPoint(Point3f worldPos) {
        // Normalize to [0,1] cube
        float worldSize = worldMax - worldMin;
        float x = (worldPos.x - worldMin) / worldSize;
        float y = (worldPos.y - worldMin) / worldSize;
        float z = (worldPos.z - worldMin) / worldSize;

        // Clamp to cube bounds
        x = Math.max(0.0f, Math.min(1.0f, x));
        y = Math.max(0.0f, Math.min(1.0f, y));
        z = Math.max(0.0f, Math.min(1.0f, z));

        // Primary: Which coordinate dominates?
        boolean xDominant = (x >= y && x >= z);
        boolean yDominant = (y >= x && y >= z);
        // zDominant is the remaining case

        // Secondary: Which side of diagonal?
        boolean upperDiagonal = (x + y + z >= 1.5f);

        if (xDominant) {
            return upperDiagonal ? (byte) 0 : (byte) 4; // S0 or S4
        } else if (yDominant) {
            return upperDiagonal ? (byte) 1 : (byte) 5; // S1 or S5
        } else {
            return upperDiagonal ? (byte) 2 : (byte) 3; // S2 or S3
        }
    }

    /**
     * Get the bubble (animation region) for a given tetrahedral type.
     *
     * @param type Tetrahedral type (0-5)
     * @return EnhancedBubble for that region
     */
    public EnhancedBubble getBubble(byte type) {
        if (type < 0 || type > 5) {
            throw new IllegalArgumentException("Type must be 0-5, got: " + type);
        }
        return bubbles.get(type);
    }

    /**
     * Get the bubble that contains a world-space position.
     *
     * @param worldPos Position in world space
     * @return EnhancedBubble containing that position
     */
    public EnhancedBubble getBubbleForPosition(Point3f worldPos) {
        byte type = classifyPoint(worldPos);
        return bubbles.get(type);
    }

    /**
     * Get all 6 bubbles.
     *
     * @return Collection of all bubbles
     */
    public Collection<EnhancedBubble> getAllBubbles() {
        return bubbles.values();
    }


    /**
     * Get all 6 tetrahedral types mapped to their bubbles.
     *
     * @return Map of type → bubble
     */
    public Map<Byte, EnhancedBubble> getBubblesByType() {
        return new HashMap<>(bubbles);
    }

    /**
     * Get world space bounds.
     *
     * @return [min, max] pair
     */
    public float[] getWorldBounds() {
        return new float[] { worldMin, worldMax };
    }
}
