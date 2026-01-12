package com.hellblazer.luciferase.simulation.bubble;

import javafx.geometry.Point3D;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks and updates bubble spatial bounds based on entity positions.
 * Implements EntityChangeListener to react to entity changes.
 *
 * @author hal.hildebrand
 */
public class BubbleBoundsTracker implements EntityChangeListener {

    private final byte spatialLevel;
    private final Map<String, Point3f> entityPositions;  // For recalculation and centroid
    private volatile BubbleBounds bounds;

    /**
     * Create a bounds tracker at the specified spatial level.
     *
     * @param spatialLevel Tetree refinement level
     */
    public BubbleBoundsTracker(byte spatialLevel) {
        this.spatialLevel = spatialLevel;
        this.entityPositions = new ConcurrentHashMap<>();

        // Initialize bounds to root tetrahedron at specified level
        var rootKey = com.hellblazer.luciferase.lucien.tetree.TetreeKey.create(spatialLevel, 0L, 0L);
        this.bounds = BubbleBounds.fromTetreeKey(rootKey);
    }

    /**
     * Get the current bounds of this bubble.
     *
     * @return BubbleBounds or null if no entities
     */
    public BubbleBounds bounds() {
        return bounds;
    }

    /**
     * Get the centroid of this bubble based on entity positions.
     * Returns the average position of all entities, or tetrahedral centroid if no entities.
     *
     * @return Centroid point or null if no bounds
     */
    public Point3D centroid() {
        if (bounds == null) {
            return null;
        }

        // If there are entities, compute centroid from their positions
        if (!entityPositions.isEmpty()) {
            double sumX = 0, sumY = 0, sumZ = 0;
            int count = 0;

            for (var position : entityPositions.values()) {
                sumX += position.x;
                sumY += position.y;
                sumZ += position.z;
                count++;
            }

            if (count > 0) {
                return new Point3D(sumX / count, sumY / count, sumZ / count);
            }
        }

        // Fall back to tetrahedral centroid if no entities
        return bounds.centroid();
    }

    /**
     * Recalculate bounds from current entity positions.
     */
    public void recalculateBounds() {
        if (entityPositions.isEmpty()) {
            bounds = null;
            return;
        }

        var positions = new ArrayList<>(entityPositions.values());
        if (!positions.isEmpty()) {
            bounds = BubbleBounds.fromEntityPositions(positions);
        }
    }

    @Override
    public void onEntityAdded(String entityId, Point3f position) {
        entityPositions.put(entityId, position);

        // Update bounds
        if (bounds == null) {
            bounds = BubbleBounds.fromEntityPositions(List.of(position));
        } else {
            bounds = bounds.expand(position);
        }
    }

    @Override
    public void onEntityRemoved(String entityId) {
        entityPositions.remove(entityId);

        // Recalculate bounds if entities remain
        if (!entityPositions.isEmpty()) {
            recalculateBounds();
        } else {
            bounds = null;
        }
    }

    @Override
    public void onEntityMoved(String entityId, Point3f oldPosition, Point3f newPosition) {
        entityPositions.put(entityId, newPosition);

        // Expand bounds if needed
        if (bounds != null) {
            bounds = bounds.expand(newPosition);
        }
    }
}
