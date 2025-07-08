/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.collision.physics;

import javax.vecmath.Matrix3f;
import javax.vecmath.Vector3f;

/**
 * Utility class for calculating inertia tensors for various shapes.
 * The inertia tensor determines how an object resists rotational motion.
 *
 * @author hal.hildebrand
 */
public class InertiaTensor {
    
    /**
     * Calculate inertia tensor for a solid sphere.
     * I = (2/5) * m * r^2 * Identity
     */
    public static Matrix3f sphere(float mass, float radius) {
        float value = 0.4f * mass * radius * radius;
        var tensor = new Matrix3f();
        tensor.setIdentity();
        tensor.mul(value);
        return tensor;
    }
    
    /**
     * Calculate inertia tensor for a solid box.
     * I_xx = (1/12) * m * (h^2 + d^2)
     * I_yy = (1/12) * m * (w^2 + d^2)
     * I_zz = (1/12) * m * (w^2 + h^2)
     */
    public static Matrix3f box(float mass, Vector3f halfExtents) {
        float w = halfExtents.x * 2;
        float h = halfExtents.y * 2;
        float d = halfExtents.z * 2;
        
        float factor = mass / 12.0f;
        
        var tensor = new Matrix3f();
        tensor.m00 = factor * (h * h + d * d);
        tensor.m11 = factor * (w * w + d * d);
        tensor.m22 = factor * (w * w + h * h);
        tensor.m01 = tensor.m02 = tensor.m10 = 0;
        tensor.m12 = tensor.m20 = tensor.m21 = 0;
        
        return tensor;
    }
    
    /**
     * Calculate inertia tensor for a cylinder/capsule along Y axis.
     * I_xx = I_zz = (1/12) * m * (3r^2 + h^2)
     * I_yy = (1/2) * m * r^2
     */
    public static Matrix3f cylinder(float mass, float radius, float height) {
        var tensor = new Matrix3f();
        
        float lateral = (mass / 12.0f) * (3 * radius * radius + height * height);
        float axial = 0.5f * mass * radius * radius;
        
        tensor.m00 = lateral;
        tensor.m11 = axial;
        tensor.m22 = lateral;
        tensor.m01 = tensor.m02 = tensor.m10 = 0;
        tensor.m12 = tensor.m20 = tensor.m21 = 0;
        
        return tensor;
    }
    
    /**
     * Calculate inertia tensor for a capsule (cylinder with hemispherical caps).
     * More complex due to the spherical end caps.
     */
    public static Matrix3f capsule(float mass, float radius, float cylinderHeight) {
        // Mass distribution
        float cylinderVolume = (float)(Math.PI * radius * radius * cylinderHeight);
        float sphereVolume = (float)(4.0/3.0 * Math.PI * radius * radius * radius);
        float totalVolume = cylinderVolume + sphereVolume;
        
        float cylinderMass = mass * (cylinderVolume / totalVolume);
        float sphereMass = mass * (sphereVolume / totalVolume);
        
        // Cylinder contribution
        var cylinderTensor = cylinder(cylinderMass, radius, cylinderHeight);
        
        // Sphere contribution (two hemispheres)
        float sphereInertia = 0.4f * sphereMass * radius * radius;
        
        // Parallel axis theorem for hemispheres
        float hemisphereOffset = cylinderHeight / 2.0f + 3.0f * radius / 8.0f;
        float parallelAxis = sphereMass * hemisphereOffset * hemisphereOffset;
        
        var tensor = new Matrix3f();
        tensor.m00 = cylinderTensor.m00 + sphereInertia + parallelAxis;
        tensor.m11 = cylinderTensor.m11 + sphereInertia;
        tensor.m22 = cylinderTensor.m22 + sphereInertia + parallelAxis;
        tensor.m01 = tensor.m02 = tensor.m10 = 0;
        tensor.m12 = tensor.m20 = tensor.m21 = 0;
        
        return tensor;
    }
    
    /**
     * Scale an inertia tensor by a uniform scale factor.
     * When scaling an object, inertia scales with the 5th power of the scale.
     */
    public static Matrix3f scale(Matrix3f tensor, float scale) {
        var scaled = new Matrix3f(tensor);
        float factor = scale * scale * scale * scale * scale;
        scaled.mul(factor);
        return scaled;
    }
    
    /**
     * Transform inertia tensor from local to world space.
     * I_world = R * I_local * R^T
     */
    public static Matrix3f transform(Matrix3f localTensor, Matrix3f rotation) {
        var result = new Matrix3f();
        var temp = new Matrix3f();
        
        // temp = I_local * R^T
        var rotationTranspose = new Matrix3f(rotation);
        rotationTranspose.transpose();
        temp.mul(localTensor, rotationTranspose);
        
        // result = R * temp
        result.mul(rotation, temp);
        
        return result;
    }
}