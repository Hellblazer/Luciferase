/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.collision.physics.constraints;

import com.hellblazer.luciferase.lucien.collision.physics.RigidBody;

/**
 * Base interface for physics constraints.
 * Constraints limit the relative motion between rigid bodies.
 *
 * @author hal.hildebrand
 */
public interface Constraint {
    
    /**
     * Prepare the constraint for solving.
     * Called once per physics step before iterations.
     */
    void prepare(float deltaTime);
    
    /**
     * Solve the constraint by applying corrective impulses.
     * Called multiple times per physics step.
     */
    void solve();
    
    /**
     * Get the bodies involved in this constraint.
     */
    RigidBody[] getBodies();
    
    /**
     * Check if the constraint is still valid.
     */
    boolean isValid();
    
    /**
     * Get the current constraint error/violation.
     */
    float getError();
}