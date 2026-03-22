package com.hellblazer.luciferase.simulation.spatial;

import com.hellblazer.luciferase.simulation.spatial.*;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;

import javax.vecmath.Point3f;

/**
 * Utility for converting 3D positions to TetreeKeys for spatial routing.
 * <p>
 * Enables spatial routing in distributed simulation:
 * - Convert entity position to tetrahedral spatial key
 * - Route entities to appropriate bubble/node based on key
 * - Support consistent hashing via tetrahedral space-filling curve
 * <p>
 * Usage:
 * <pre>
 * // Convert position to key for routing
 * Point3f position = entity.getPosition();
 * TetreeKey<?> key = TetreeKeyHelper.positionToKey(position, (byte) 10);
 *
 * // Use key for routing (via TetreeKeyRouter from Phase 0 V1)
 * Member targetNode = router.routeToKey(key);
 * </pre>
 *
 * @author hal.hildebrand
 */
public class TetreeKeyHelper {

    /**
     * Convert a 3D position to a TetreeKey at the specified refinement level.
     * <p>
     * Uses Bey refinement traversal to locate the containing tetrahedron.
     *
     * @param position 3D point in space
     * @param level    Refinement level (0-21, higher = finer granularity)
     * @return TetreeKey for the tetrahedron containing the position, or null if position is outside bounds
     */
    public static TetreeKey<?> positionToKey(Point3f position, byte level) {
        return positionToKey(position.x, position.y, position.z, level);
    }

    /**
     * Convert 3D coordinates to a TetreeKey at the specified refinement level.
     * <p>
     * Uses Bey refinement traversal to locate the containing tetrahedron.
     *
     * @param x     X coordinate
     * @param y     Y coordinate
     * @param z     Z coordinate
     * @param level Refinement level (0-21, higher = finer granularity)
     * @return TetreeKey for the tetrahedron containing the point, or null if outside bounds
     */
    public static TetreeKey<?> positionToKey(float x, float y, float z, byte level) {
        // Scale normalized [0,1] coordinates to tetree integer coordinate space.
        // Clamp to just inside the valid domain [0, MAX_EXTENT) to handle x=1.0 boundary.
        float scale = Constants.MAX_EXTENT;
        float sx = Math.min(x * scale, scale - 1.0f);
        float sy = Math.min(y * scale, scale - 1.0f);
        float sz = Math.min(z * scale, scale - 1.0f);
        var tet = Tet.locatePointBeyRefinementFromRoot(sx, sy, sz, level);

        if (tet == null) {
            return null;  // Position outside tetree bounds
        }

        // Return the tet's TetreeKey
        return tet.tmIndex();
    }

    /**
     * Check if a position is within the tetree bounds.
     * <p>
     * Convenience method to test if a position can be converted to a key.
     *
     * @param position Position to test
     * @param level    Level to test at
     * @return true if position can be converted to a key
     */
    public static boolean isWithinBounds(Point3f position, byte level) {
        return positionToKey(position, level) != null;
    }

    /**
     * Check if coordinates are within the tetree bounds.
     *
     * @param x     X coordinate
     * @param y     Y coordinate
     * @param z     Z coordinate
     * @param level Level to test at
     * @return true if coordinates can be converted to a key
     */
    public static boolean isWithinBounds(float x, float y, float z, byte level) {
        return positionToKey(x, y, z, level) != null;
    }
}
