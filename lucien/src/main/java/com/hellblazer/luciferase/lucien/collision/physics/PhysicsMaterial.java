/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.collision.physics;

/**
 * Represents physical material properties for collision response.
 * These properties determine how objects interact during collisions.
 *
 * @author hal.hildebrand
 */
public record PhysicsMaterial(
    float friction,      // Coefficient of friction [0, ∞)
    float restitution,   // Bounciness/elasticity [0, 1]
    float density        // Mass per unit volume
) {
    
    // Common material presets
    public static final PhysicsMaterial STEEL = new PhysicsMaterial(0.8f, 0.2f, 7850f);
    public static final PhysicsMaterial RUBBER = new PhysicsMaterial(1.5f, 0.8f, 1500f);
    public static final PhysicsMaterial WOOD = new PhysicsMaterial(0.6f, 0.3f, 700f);
    public static final PhysicsMaterial ICE = new PhysicsMaterial(0.05f, 0.1f, 920f);
    public static final PhysicsMaterial CONCRETE = new PhysicsMaterial(0.9f, 0.1f, 2400f);
    public static final PhysicsMaterial GLASS = new PhysicsMaterial(0.5f, 0.7f, 2500f);
    public static final PhysicsMaterial DEFAULT = new PhysicsMaterial(0.5f, 0.5f, 1000f);
    
    public PhysicsMaterial {
        // Validate material properties
        if (friction < 0) {
            throw new IllegalArgumentException("Friction must be non-negative");
        }
        if (restitution < 0 || restitution > 1) {
            throw new IllegalArgumentException("Restitution must be between 0 and 1");
        }
        if (density <= 0) {
            throw new IllegalArgumentException("Density must be positive");
        }
    }
    
    /**
     * Combine two materials for collision response.
     * Uses physics-based rules for combining material properties.
     */
    public static PhysicsMaterial combine(PhysicsMaterial a, PhysicsMaterial b) {
        // Friction: use geometric mean (models surface interaction)
        float combinedFriction = (float) Math.sqrt(a.friction * b.friction);
        
        // Restitution: use minimum (models energy loss)
        float combinedRestitution = Math.min(a.restitution, b.restitution);
        
        // Density: average (for contact properties)
        float combinedDensity = (a.density + b.density) * 0.5f;
        
        return new PhysicsMaterial(combinedFriction, combinedRestitution, combinedDensity);
    }
}