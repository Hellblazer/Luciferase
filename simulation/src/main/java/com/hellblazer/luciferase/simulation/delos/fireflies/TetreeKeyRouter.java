/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.simulation.delos.fireflies;

import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import javafx.geometry.Point3D;

/**
 * Routes spatial queries to cluster members based on TetreeKey hashing.
 * <p>
 * Provides deterministic routing from 3D spatial coordinates to cluster members
 * using the Tetree space-filling curve properties. The routing is consistent
 * for the same keys, enabling predictable distribution of spatial responsibilities.
 * <p>
 * This router uses TetreeKey.getLowBits() and getHighBits() to create a
 * deterministic hash that maps to a member index via modulo arithmetic.
 *
 * @author hal.hildebrand
 */
public class TetreeKeyRouter {

    private final DynamicContext<Member> context;

    /**
     * Create a new TetreeKeyRouter.
     *
     * @param context the Delos membership context
     */
    public TetreeKeyRouter(DynamicContext<Member> context) {
        this.context = context;
    }

    /**
     * Route a TetreeKey to a member index.
     * <p>
     * Uses the key's internal bit representation to create a deterministic
     * hash that selects a member from the cluster.
     *
     * @param key the TetreeKey to route
     * @return the member index (0 to context.size()-1)
     */
    public int routeTo(TetreeKey<?> key) {
        var contextSize = context.size();
        if (contextSize == 0) {
            throw new IllegalStateException("Context has no members");
        }

        // Combine low and high bits for a good hash distribution
        var lowBits = key.getLowBits();
        var highBits = key.getHighBits();

        // XOR the bits to create a well-distributed hash
        var hash = lowBits ^ highBits;

        // Use Math.abs to handle negative values, then modulo to get valid index
        // Note: We need to handle Long.MIN_VALUE special case to avoid overflow
        var absHash = hash == Long.MIN_VALUE ? 0 : Math.abs(hash);
        return (int) (absHash % contextSize);
    }

    /**
     * Route a 3D position to a member index.
     * <p>
     * Converts the position to a TetreeKey at the specified level,
     * then routes to a member using that key.
     *
     * @param position        the 3D position
     * @param cubeSizeMeters  the size of the unit cube in meters
     * @param maxLevel        the refinement level to use for key generation
     * @return the member index (0 to context.size()-1)
     */
    public int routeToPosition(Point3D position, float cubeSizeMeters, int maxLevel) {
        // Normalize position to [0, 1) coordinates
        var normalizedX = (float) (position.getX() / cubeSizeMeters);
        var normalizedY = (float) (position.getY() / cubeSizeMeters);
        var normalizedZ = (float) (position.getZ() / cubeSizeMeters);

        // Locate the tetrahedron containing this point at the target level
        var tet = Tet.locatePointBeyRefinementFromRoot(normalizedX, normalizedY, normalizedZ, (byte) maxLevel);

        if (tet == null) {
            throw new IllegalArgumentException(
                "Position " + position + " is outside valid domain [0, " + cubeSizeMeters + ")");
        }

        // Get the TetreeKey for this tetrahedron and route it
        var key = tet.tmIndex();
        return routeTo(key);
    }
}
