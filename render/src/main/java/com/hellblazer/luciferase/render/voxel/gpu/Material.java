package com.hellblazer.luciferase.render.voxel.gpu;

import javax.vecmath.Vector4f;

/**
 * Represents a material for voxel rendering.
 */
public class Material {
    public final Vector4f albedo;      // RGBA color
    public final float metallic;       // 0.0 = dielectric, 1.0 = metallic
    public final float roughness;      // 0.0 = smooth, 1.0 = rough
    public final float emission;       // Emission strength
    
    public Material(Vector4f albedo, float metallic, float roughness, float emission) {
        this.albedo = albedo;
        this.metallic = metallic;
        this.roughness = roughness;
        this.emission = emission;
    }
    
    /**
     * Create a default material.
     */
    public static Material defaultMaterial() {
        return new Material(
            new Vector4f(0.8f, 0.8f, 0.8f, 1.0f),  // Light gray
            0.0f,   // Non-metallic
            0.5f,   // Medium roughness
            0.0f    // No emission
        );
    }
}