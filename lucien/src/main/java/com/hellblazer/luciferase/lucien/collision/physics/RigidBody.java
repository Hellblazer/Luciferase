/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.collision.physics;

import javax.vecmath.Matrix3f;
import javax.vecmath.Point3f;
import javax.vecmath.Quat4f;
import javax.vecmath.Vector3f;

/**
 * Represents a rigid body with physics properties for dynamics simulation.
 * Tracks linear and angular motion, mass properties, and forces.
 *
 * @author hal.hildebrand
 */
public class RigidBody {
    
    // State
    private final Point3f position;
    private final Quat4f orientation;
    private final Vector3f linearVelocity;
    private final Vector3f angularVelocity;
    
    // Mass properties
    private final float mass;
    private final float inverseMass;
    private final Matrix3f inertiaTensor;
    private final Matrix3f inverseInertiaTensor;
    
    // Forces and torques
    private final Vector3f forceAccumulator;
    private final Vector3f torqueAccumulator;
    
    // Material and damping
    private PhysicsMaterial material;
    private float linearDamping;
    private float angularDamping;
    
    // Motion type
    private boolean isKinematic;
    
    public RigidBody(float mass, Matrix3f inertiaTensor) {
        this.position = new Point3f();
        this.orientation = new Quat4f(0, 0, 0, 1);
        this.linearVelocity = new Vector3f();
        this.angularVelocity = new Vector3f();
        
        this.mass = mass;
        this.inverseMass = mass > 0 ? 1.0f / mass : 0;
        this.inertiaTensor = new Matrix3f(inertiaTensor);
        this.inverseInertiaTensor = computeInverseInertia(inertiaTensor, mass);
        
        this.forceAccumulator = new Vector3f();
        this.torqueAccumulator = new Vector3f();
        
        this.material = PhysicsMaterial.DEFAULT;
        this.linearDamping = 0.05f;
        this.angularDamping = 0.05f;
        this.isKinematic = false;
    }
    
    /**
     * Create a static (infinite mass) rigid body.
     */
    public static RigidBody createStatic() {
        var inertia = new Matrix3f();
        inertia.setIdentity();
        var body = new RigidBody(0, inertia);
        body.isKinematic = true;
        return body;
    }
    
    /**
     * Apply a force at the center of mass.
     */
    public void applyForce(Vector3f force) {
        if (!isKinematic && inverseMass > 0) {
            forceAccumulator.add(force);
        }
    }
    
    /**
     * Apply a force at a world space point.
     */
    public void applyForceAtPoint(Vector3f force, Point3f worldPoint) {
        if (!isKinematic && inverseMass > 0) {
            // Apply linear force
            forceAccumulator.add(force);
            
            // Calculate torque
            var r = new Vector3f(worldPoint);
            r.sub(position);
            var torque = new Vector3f();
            torque.cross(r, force);
            torqueAccumulator.add(torque);
        }
    }
    
    /**
     * Apply an impulse (instantaneous change in momentum).
     */
    public void applyImpulse(Vector3f impulse, Point3f worldPoint) {
        if (!isKinematic && inverseMass > 0) {
            // Linear impulse
            var deltaV = new Vector3f(impulse);
            deltaV.scale(inverseMass);
            linearVelocity.add(deltaV);
            
            // Angular impulse
            var r = new Vector3f(worldPoint);
            r.sub(position);
            var angularImpulse = new Vector3f();
            angularImpulse.cross(r, impulse);
            
            var deltaOmega = new Vector3f();
            inverseInertiaTensor.transform(angularImpulse, deltaOmega);
            angularVelocity.add(deltaOmega);
        }
    }
    
    /**
     * Integrate physics state over time step.
     */
    public void integrate(float deltaTime) {
        if (isKinematic || inverseMass == 0) {
            return;
        }
        
        // Update linear velocity from forces
        var acceleration = new Vector3f(forceAccumulator);
        acceleration.scale(inverseMass);
        var deltaV = new Vector3f(acceleration);
        deltaV.scale(deltaTime);
        linearVelocity.add(deltaV);
        
        // Apply linear damping
        linearVelocity.scale(1.0f - linearDamping * deltaTime);
        
        // Update position
        var deltaPos = new Vector3f(linearVelocity);
        deltaPos.scale(deltaTime);
        position.add(deltaPos);
        
        // Update angular velocity from torques
        var angularAcceleration = new Vector3f();
        inverseInertiaTensor.transform(torqueAccumulator, angularAcceleration);
        var deltaOmega = new Vector3f(angularAcceleration);
        deltaOmega.scale(deltaTime);
        angularVelocity.add(deltaOmega);
        
        // Apply angular damping
        angularVelocity.scale(1.0f - angularDamping * deltaTime);
        
        // Update orientation
        var spin = new Quat4f(
            angularVelocity.x * deltaTime * 0.5f,
            angularVelocity.y * deltaTime * 0.5f,
            angularVelocity.z * deltaTime * 0.5f,
            0
        );
        var tempQuat = new Quat4f(spin);
        tempQuat.mul(orientation);
        orientation.x += tempQuat.x;
        orientation.y += tempQuat.y;
        orientation.z += tempQuat.z;
        orientation.w += tempQuat.w;
        orientation.normalize();
        
        // Clear accumulators
        forceAccumulator.set(0, 0, 0);
        torqueAccumulator.set(0, 0, 0);
    }
    
    /**
     * Get velocity at a world space point (includes rotational component).
     */
    public Vector3f getVelocityAtPoint(Point3f worldPoint) {
        var r = new Vector3f(worldPoint);
        r.sub(position);
        
        var rotationalVelocity = new Vector3f();
        rotationalVelocity.cross(angularVelocity, r);
        
        var totalVelocity = new Vector3f(linearVelocity);
        totalVelocity.add(rotationalVelocity);
        
        return totalVelocity;
    }
    
    private Matrix3f computeInverseInertia(Matrix3f inertia, float mass) {
        if (mass == 0) {
            // Infinite mass = zero inverse
            return new Matrix3f();
        }
        
        var inverse = new Matrix3f(inertia);
        inverse.invert();
        return inverse;
    }
    
    // Getters and setters
    public Point3f getPosition() { return new Point3f(position); }
    public void setPosition(Point3f pos) { position.set(pos); }
    
    public Quat4f getOrientation() { return new Quat4f(orientation); }
    public void setOrientation(Quat4f orient) { orientation.set(orient); }
    
    public Vector3f getLinearVelocity() { return new Vector3f(linearVelocity); }
    public void setLinearVelocity(Vector3f vel) { linearVelocity.set(vel); }
    
    public Vector3f getAngularVelocity() { return new Vector3f(angularVelocity); }
    public void setAngularVelocity(Vector3f vel) { angularVelocity.set(vel); }
    
    public float getMass() { return mass; }
    public float getInverseMass() { return inverseMass; }
    
    public Matrix3f getInertiaTensor() { return new Matrix3f(inertiaTensor); }
    public Matrix3f getInverseInertiaTensor() { return new Matrix3f(inverseInertiaTensor); }
    
    public PhysicsMaterial getMaterial() { return material; }
    public void setMaterial(PhysicsMaterial mat) { material = mat; }
    
    public boolean isKinematic() { return isKinematic; }
    public void setKinematic(boolean kinematic) { isKinematic = kinematic; }
    
    public float getLinearDamping() { return linearDamping; }
    public void setLinearDamping(float damping) { linearDamping = damping; }
    
    public float getAngularDamping() { return angularDamping; }
    public void setAngularDamping(float damping) { angularDamping = damping; }
}