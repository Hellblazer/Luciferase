package com.hellblazer.luciferase.simulation;

import com.hellblazer.luciferase.lucien.entity.EntityID;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

/**
 * Ghost entity interface for dead reckoning.
 * <p>
 * Minimal interface required by DeadReckoningEstimator to predict
 * entity positions between authoritative updates.
 * <p>
 * Dead reckoning requires:
 * - id(): Entity identification
 * - position(): Current spatial location
 * - velocity(): Movement vector for linear prediction
 * - timestamp(): When this state was observed
 * <p>
 * Implementations can wrap SimulationGhostEntity or provide
 * velocity from simulation-specific sources.
 *
 * @author hal.hildebrand
 */
public interface GhostEntity {

    /**
     * Get entity identifier.
     *
     * @return Entity ID
     */
    EntityID id();

    /**
     * Get current position.
     *
     * @return 3D position
     */
    Point3f position();

    /**
     * Get current velocity.
     * <p>
     * Used for linear prediction: position + velocity × Δt
     *
     * @return 3D velocity vector (units per second)
     */
    Vector3f velocity();

    /**
     * Get timestamp when this state was observed.
     *
     * @return Timestamp in milliseconds
     */
    long timestamp();
}
