package com.hellblazer.luciferase.render.voxel.esvo.voxelization;

/**
 * Material properties for voxelization.
 */
public class Material {
    private float[] diffuseColor = {1, 1, 1};
    private float[] specularColor = {1, 1, 1};
    private float[] emissiveColor = {0, 0, 0};
    private float emissiveIntensity = 0;
    private float roughness = 0.5f;
    private float metallic = 0;
    private float opacity = 1.0f;
    
    public Material withDiffuseColor(float r, float g, float b) {
        this.diffuseColor = new float[]{r, g, b};
        return this;
    }
    
    public Material withSpecularColor(float r, float g, float b) {
        this.specularColor = new float[]{r, g, b};
        return this;
    }
    
    public Material withEmissiveColor(float r, float g, float b) {
        this.emissiveColor = new float[]{r, g, b};
        return this;
    }
    
    public Material withEmissiveIntensity(float intensity) {
        this.emissiveIntensity = intensity;
        return this;
    }
    
    public Material withRoughness(float roughness) {
        this.roughness = roughness;
        return this;
    }
    
    public Material withMetallic(float metallic) {
        this.metallic = metallic;
        return this;
    }
    
    public Material withOpacity(float opacity) {
        this.opacity = opacity;
        return this;
    }
    
    public float[] getDiffuseColor() {
        return diffuseColor;
    }
    
    public float[] getSpecularColor() {
        return specularColor;
    }
    
    public float[] getEmissiveColor() {
        return emissiveColor;
    }
    
    public float getEmissiveIntensity() {
        return emissiveIntensity;
    }
    
    public float getRoughness() {
        return roughness;
    }
    
    public float getMetallic() {
        return metallic;
    }
    
    public float getOpacity() {
        return opacity;
    }
}