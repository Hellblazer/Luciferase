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

import com.hellblazer.luciferase.lucien.SpatialIndex.CollisionPair;
import com.hellblazer.luciferase.lucien.entity.EntityID;

import javax.vecmath.Vector3f;

/**
 * Resolves collisions between entities using physics-based calculations. Supports different collision response types
 * and material properties.
 *
 * @author hal.hildebrand
 */
public class CollisionResolver {

    private static final float           DEFAULT_RESTITUTION         = 0.5f;
    private static final float           DEFAULT_FRICTION            = 0.3f;
    private static final float           POSITION_CORRECTION_PERCENT = 0.8f;
    private static final float           POSITION_CORRECTION_SLOP    = 0.01f;
    private final        CollisionConfig config;

    public CollisionResolver() {
        this(CollisionConfig.defaultConfig());
    }

    public CollisionResolver(CollisionConfig config) {
        this.config = config;
    }

    /**
     * Calculate separation vector to push entities apart
     */
    public <ID extends EntityID, Content> Vector3f calculateSeparation(CollisionPair<ID, Content> collision,
                                                                       float mass1, float mass2) {

        if (collision.penetrationDepth() <= 0) {
            return new Vector3f(0, 0, 0);
        }

        Vector3f separation = new Vector3f(collision.contactNormal());
        separation.scale(collision.penetrationDepth());

        // Distribute based on mass
        if (!Float.isInfinite(mass1) && !Float.isInfinite(mass2)) {
            float totalMass = mass1 + mass2;
            separation.scale(mass2 / totalMass);
        } else if (Float.isInfinite(mass1)) {
            // Entity 1 is immovable, entity 2 moves full separation
            separation.scale(1.0f);
        } else {
            // Entity 2 is immovable, entity 1 moves full separation
            separation.scale(-1.0f);
        }

        return separation;
    }

    /**
     * Resolve a collision between two entities
     *
     * @param collision the collision pair to resolve
     * @param velocity1 velocity of entity 1 (null for static)
     * @param velocity2 velocity of entity 2 (null for static)
     * @param mass1     mass of entity 1 (use Float.POSITIVE_INFINITY for immovable)
     * @param mass2     mass of entity 2 (use Float.POSITIVE_INFINITY for immovable)
     * @return collision response with impulses and corrections
     */
    public <ID extends EntityID, Content> CollisionResponse resolveCollision(CollisionPair<ID, Content> collision,
                                                                             Vector3f velocity1, Vector3f velocity2,
                                                                             float mass1, float mass2) {

        // Handle null velocities (static objects)
        Vector3f v1 = velocity1 != null ? velocity1 : new Vector3f(0, 0, 0);
        Vector3f v2 = velocity2 != null ? velocity2 : new Vector3f(0, 0, 0);

        // Handle infinite mass (immovable objects)
        if (Float.isInfinite(mass1) && Float.isInfinite(mass2)) {
            return CollisionResponse.noResponse(); // Both immovable
        }

        // Calculate relative velocity
        Vector3f relativeVelocity = new Vector3f();
        relativeVelocity.sub(v2, v1);

        // Calculate relative velocity along collision normal
        float velocityAlongNormal = relativeVelocity.dot(collision.contactNormal());

        // Objects moving apart - but only skip if they're not interpenetrating
        // If there's penetration, we need to resolve it even if velocities suggest separation
        if (velocityAlongNormal > 0 && collision.penetrationDepth() <= config.positionCorrectionSlop) {
            return CollisionResponse.noResponse();
        }

        // Calculate restitution (bounciness)
        float restitution = config.defaultRestitution;

        // Calculate impulse scalar
        float impulseScalar = -(1 + restitution) * velocityAlongNormal;
        impulseScalar /= (1 / mass1 + 1 / mass2);

        // Calculate impulse vector
        Vector3f impulse = new Vector3f(collision.contactNormal());
        impulse.scale(impulseScalar);

        // Calculate individual impulses
        Vector3f impulse1 = new Vector3f(impulse);
        impulse1.scale(-1 / mass1);

        Vector3f impulse2 = new Vector3f(impulse);
        impulse2.scale(1 / mass2);

        // Handle infinite mass
        if (Float.isInfinite(mass1)) {
            impulse1.set(0, 0, 0);
            impulse2.scale(2); // Double impulse for collision with immovable object
        } else if (Float.isInfinite(mass2)) {
            impulse2.set(0, 0, 0);
            impulse1.scale(2); // Double impulse for collision with immovable object
        }

        // Calculate position correction
        Vector3f correction1 = new Vector3f(0, 0, 0);
        Vector3f correction2 = new Vector3f(0, 0, 0);

        if (config.enablePositionCorrection && collision.penetrationDepth() > config.positionCorrectionSlop) {
            float correctionMagnitude = (collision.penetrationDepth() - config.positionCorrectionSlop)
            * config.positionCorrectionPercent;

            Vector3f correction = new Vector3f(collision.contactNormal());
            correction.scale(correctionMagnitude);

            float totalInverseMass = (1 / mass1 + 1 / mass2);

            correction1 = new Vector3f(correction);
            correction1.scale(-(1 / mass1) / totalInverseMass);

            correction2 = new Vector3f(correction);
            correction2.scale((1 / mass2) / totalInverseMass);

            // Handle infinite mass
            if (Float.isInfinite(mass1)) {
                correction1.set(0, 0, 0);
                correction2.scale(2);
            } else if (Float.isInfinite(mass2)) {
                correction2.set(0, 0, 0);
                correction1.scale(2);
            }
        }

        // Apply friction if enabled
        if (config.enableFriction) {
            applyFriction(collision, relativeVelocity, impulseScalar, impulse1, impulse2, mass1, mass2);
        }

        return new CollisionResponse(impulse1, impulse2, correction1, correction2, restitution, config.defaultFriction);
    }

    /**
     * Resolve collision for a single movable entity against a static entity
     */
    public <ID extends EntityID, Content> CollisionResponse resolveStaticCollision(CollisionPair<ID, Content> collision,
                                                                                   Vector3f velocity, float mass,
                                                                                   boolean isEntity1Moving) {

        if (isEntity1Moving) {
            return resolveCollision(collision, velocity, null, mass, Float.POSITIVE_INFINITY);
        } else {
            return resolveCollision(collision, null, velocity, Float.POSITIVE_INFINITY, mass);
        }
    }

    /**
     * Apply friction to the collision response
     */
    private void applyFriction(CollisionPair<?, ?> collision, Vector3f relativeVelocity, float normalImpulse,
                               Vector3f impulse1, Vector3f impulse2, float mass1, float mass2) {

        // Calculate tangent vector (perpendicular to normal)
        Vector3f tangent = new Vector3f(relativeVelocity);
        float dot = relativeVelocity.dot(collision.contactNormal());
        tangent.scaleAdd(-dot, collision.contactNormal(), tangent);

        if (tangent.lengthSquared() < 1e-6f) {
            return; // No tangential velocity
        }

        tangent.normalize();

        // Calculate friction impulse magnitude
        float frictionImpulse = -relativeVelocity.dot(tangent);
        frictionImpulse /= (1 / mass1 + 1 / mass2);

        // Clamp friction (Coulomb friction model)
        float maxFriction = Math.abs(normalImpulse) * config.defaultFriction;
        frictionImpulse = Math.max(-maxFriction, Math.min(maxFriction, frictionImpulse));

        // Apply friction impulse
        Vector3f friction = new Vector3f(tangent);
        friction.scale(frictionImpulse);

        if (!Float.isInfinite(mass1)) {
            friction.scale(-1 / mass1);
            impulse1.add(friction);
        }

        if (!Float.isInfinite(mass2)) {
            friction.scale(mass2 / mass1); // Undo previous scale
            friction.scale(1 / mass2);
            impulse2.add(friction);
        }
    }

    /**
     * Configuration for collision resolution
     */
    public record CollisionConfig(float defaultMass, float defaultRestitution, float defaultFriction,
                                  boolean enablePositionCorrection, boolean enableFriction,
                                  float positionCorrectionPercent, float positionCorrectionSlop) {
        public static CollisionConfig defaultConfig() {
            return new CollisionConfig(1.0f,                           // defaultMass
                                       DEFAULT_RESTITUTION,            // defaultRestitution
                                       DEFAULT_FRICTION,               // defaultFriction
                                       true,                           // enablePositionCorrection
                                       true,                           // enableFriction
                                       POSITION_CORRECTION_PERCENT,    // positionCorrectionPercent
                                       POSITION_CORRECTION_SLOP        // positionCorrectionSlop
            );
        }
    }
}
