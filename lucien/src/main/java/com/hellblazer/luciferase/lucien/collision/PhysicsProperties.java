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
 * Physics properties for entities including mass, velocity, and material properties.
 *
 * @author hal.hildebrand
 */
public class PhysicsProperties {

    private float    mass;
    private Vector3f velocity;
    private Vector3f acceleration;
    private float    restitution;
    private float    friction;
    private boolean  isStatic;
    private boolean  isKinematic;

    /**
     * Create default physics properties (1kg mass, no velocity)
     */
    public PhysicsProperties() {
        this.mass = 1.0f;
        this.velocity = new Vector3f(0, 0, 0);
        this.acceleration = new Vector3f(0, 0, 0);
        this.restitution = 0.5f;
        this.friction = 0.3f;
        this.isStatic = false;
        this.isKinematic = false;
    }

    /**
     * Create physics properties with specified mass and velocity
     */
    public PhysicsProperties(float mass, Vector3f velocity) {
        this();
        this.mass = mass;
        this.velocity = new Vector3f(velocity);
    }

    /**
     * Create kinematic physics properties (infinite mass but can have velocity)
     */
    public static PhysicsProperties createKinematic(Vector3f velocity) {
        PhysicsProperties props = new PhysicsProperties();
        props.mass = Float.POSITIVE_INFINITY;
        props.velocity = new Vector3f(velocity);
        props.isKinematic = true;
        return props;
    }

    /**
     * Create static physics properties (infinite mass, no movement)
     */
    public static PhysicsProperties createStatic() {
        PhysicsProperties props = new PhysicsProperties();
        props.mass = Float.POSITIVE_INFINITY;
        props.isStatic = true;
        return props;
    }

    // Getters

    /**
     * Apply damping to velocity
     */
    public void applyDamping(float damping, float deltaTime) {
        if (!isStatic && !isKinematic) {
            float dampingFactor = (float) Math.pow(1.0f - damping, deltaTime);
            velocity.scale(dampingFactor);
        }
    }

    /**
     * Apply a force to this object (adds to acceleration)
     */
    public void applyForce(Vector3f force) {
        if (!isStatic && !isKinematic) {
            acceleration.scaleAdd(getInverseMass(), force, acceleration);
        }
    }

    /**
     * Apply an impulse to this object
     */
    public void applyImpulse(Vector3f impulse) {
        if (!isStatic && !isKinematic) {
            velocity.scaleAdd(getInverseMass(), impulse, velocity);
        }
    }

    /**
     * Clear forces (acceleration) for next frame
     */
    public void clearForces() {
        acceleration.set(0, 0, 0);
    }

    public Vector3f getAcceleration() {
        return new Vector3f(acceleration);
    }

    public float getFriction() {
        return friction;
    }

    public float getInverseMass() {
        return Float.isInfinite(mass) ? 0.0f : 1.0f / mass;
    }

    public float getMass() {
        return mass;
    }

    // Setters

    public float getRestitution() {
        return restitution;
    }

    public Vector3f getVelocity() {
        return new Vector3f(velocity);
    }

    /**
     * Integrate velocity and position over time
     */
    public Vector3f integrate(float deltaTime) {
        if (isStatic) {
            return new Vector3f(0, 0, 0);
        }

        // Update velocity from acceleration
        if (!isKinematic) {
            velocity.scaleAdd(deltaTime, acceleration, velocity);
        }

        // Calculate displacement
        Vector3f displacement = new Vector3f(velocity);
        displacement.scale(deltaTime);

        return displacement;
    }

    public boolean isKinematic() {
        return isKinematic;
    }

    public boolean isStatic() {
        return isStatic;
    }

    // Physics operations

    public void setAcceleration(Vector3f acceleration) {
        this.acceleration = new Vector3f(acceleration);
    }

    public void setFriction(float friction) {
        this.friction = Math.max(0, friction);
    }

    public void setMass(float mass) {
        if (mass <= 0) {
            throw new IllegalArgumentException("Mass must be positive");
        }
        this.mass = mass;
        this.isStatic = Float.isInfinite(mass) && velocity.lengthSquared() == 0;
        this.isKinematic = Float.isInfinite(mass) && velocity.lengthSquared() > 0;
    }

    public void setRestitution(float restitution) {
        this.restitution = Math.max(0, Math.min(1, restitution));
    }

    public void setVelocity(Vector3f velocity) {
        this.velocity = new Vector3f(velocity);
        this.isStatic = Float.isInfinite(mass) && velocity.lengthSquared() == 0;
        this.isKinematic = Float.isInfinite(mass) && velocity.lengthSquared() > 0;
    }

    @Override
    public String toString() {
        return String.format("PhysicsProperties[mass=%.2f, velocity=(%.2f,%.2f,%.2f), static=%b, kinematic=%b]", mass,
                             velocity.x, velocity.y, velocity.z, isStatic, isKinematic);
    }
}
