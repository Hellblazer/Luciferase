package com.hellblazer.luciferase.simulation.bubble;

import javax.vecmath.Point3f;

/**
 * Observer interface for entity lifecycle changes.
 * Enables loose coupling between entity store and bounds tracker.
 *
 * @author hal.hildebrand
 */
public interface EntityChangeListener {

    /**
     * Called when an entity is added to the bubble.
     *
     * @param entityId Entity identifier
     * @param position Entity position
     */
    void onEntityAdded(String entityId, Point3f position);

    /**
     * Called when an entity is removed from the bubble.
     *
     * @param entityId Entity identifier
     */
    void onEntityRemoved(String entityId);

    /**
     * Called when an entity's position is updated.
     *
     * @param entityId    Entity identifier
     * @param oldPosition Previous position
     * @param newPosition New position
     */
    void onEntityMoved(String entityId, Point3f oldPosition, Point3f newPosition);
}
