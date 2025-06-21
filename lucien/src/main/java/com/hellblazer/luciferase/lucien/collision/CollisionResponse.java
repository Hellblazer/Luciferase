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
package com.hellblazer.luciferase.lucien.collision;

import javax.vecmath.Vector3f;

/**
 * Represents the response to a collision, including impulse and position correction.
 *
 * @author hal.hildebrand
 */
public record CollisionResponse(Vector3f impulse1,          // Impulse to apply to entity 1
                                Vector3f impulse2,          // Impulse to apply to entity 2
                                Vector3f correction1,       // Position correction for entity 1
                                Vector3f correction2,       // Position correction for entity 2
                                float restitution,          // Coefficient of restitution (bounciness)
                                float friction              // Coefficient of friction
) {

    /**
     * Create a basic elastic collision response
     */
    public static CollisionResponse elastic(Vector3f impulse1, Vector3f impulse2, Vector3f correction1,
                                            Vector3f correction2) {
        return new CollisionResponse(impulse1, impulse2, correction1, correction2, 1.0f, 0.0f);
    }

    /**
     * Create a basic inelastic collision response
     */
    public static CollisionResponse inelastic(Vector3f impulse1, Vector3f impulse2, Vector3f correction1,
                                              Vector3f correction2) {
        return new CollisionResponse(impulse1, impulse2, correction1, correction2, 0.0f, 0.5f);
    }

    /**
     * Create a response with no collision (entities pass through)
     */
    public static CollisionResponse noResponse() {
        return new CollisionResponse(new Vector3f(0, 0, 0), new Vector3f(0, 0, 0), new Vector3f(0, 0, 0),
                                     new Vector3f(0, 0, 0), 0.0f, 0.0f);
    }

    /**
     * Check if this represents an actual response (not a pass-through)
     */
    public boolean hasResponse() {
        return impulse1.lengthSquared() > 0 || impulse2.lengthSquared() > 0 || correction1.lengthSquared() > 0
        || correction2.lengthSquared() > 0;
    }

    /**
     * Scale the response by a factor (useful for time step integration)
     */
    public CollisionResponse scale(float factor) {
        Vector3f scaledImpulse1 = new Vector3f(impulse1);
        scaledImpulse1.scale(factor);

        Vector3f scaledImpulse2 = new Vector3f(impulse2);
        scaledImpulse2.scale(factor);

        Vector3f scaledCorrection1 = new Vector3f(correction1);
        scaledCorrection1.scale(factor);

        Vector3f scaledCorrection2 = new Vector3f(correction2);
        scaledCorrection2.scale(factor);

        return new CollisionResponse(scaledImpulse1, scaledImpulse2, scaledCorrection1, scaledCorrection2, restitution,
                                     friction);
    }
}
