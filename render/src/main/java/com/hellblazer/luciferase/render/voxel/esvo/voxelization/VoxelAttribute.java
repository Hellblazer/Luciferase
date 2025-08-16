package com.hellblazer.luciferase.render.voxel.esvo.voxelization;

/**
 * Attributes for a voxel including color, material properties, etc.
 */
public class VoxelAttribute {
    private float[] color;
    private float[] normal;
    private float[] textureCoords;
    private float[] diffuse;
    private float[] specular;
    private float[] emissive;
    private float roughness;
    private float metallic;
    private float opacity = 1.0f;
    
    public VoxelAttribute withColor(float r, float g, float b, float a) {
        this.color = new float[]{r, g, b, a};
        return this;
    }
    
    public VoxelAttribute withNormal(float x, float y, float z) {
        this.normal = new float[]{x, y, z};
        return this;
    }
    
    public VoxelAttribute withRoughness(float roughness) {
        this.roughness = roughness;
        return this;
    }
    
    public VoxelAttribute withMetallic(float metallic) {
        this.metallic = metallic;
        return this;
    }
    
    public float[] getColor() {
        return color;
    }
    
    public void setColor(float[] color) {
        this.color = color;
    }
    
    public float[] getNormal() {
        return normal;
    }
    
    public void setNormal(float[] normal) {
        this.normal = normal;
    }
    
    public float[] getTextureCoords() {
        return textureCoords;
    }
    
    public void setTextureCoords(float[] textureCoords) {
        this.textureCoords = textureCoords;
    }
    
    public float[] getDiffuse() {
        return diffuse;
    }
    
    public void setDiffuse(float[] diffuse) {
        this.diffuse = diffuse;
    }
    
    public float[] getSpecular() {
        return specular;
    }
    
    public void setSpecular(float[] specular) {
        this.specular = specular;
    }
    
    public float[] getEmissive() {
        return emissive;
    }
    
    public void setEmissive(float[] emissive) {
        this.emissive = emissive;
    }
    
    public float getRoughness() {
        return roughness;
    }
    
    public void setRoughness(float roughness) {
        this.roughness = roughness;
    }
    
    public float getMetallic() {
        return metallic;
    }
    
    public void setMetallic(float metallic) {
        this.metallic = metallic;
    }
    
    public float getOpacity() {
        return opacity;
    }
    
    public void setOpacity(float opacity) {
        this.opacity = opacity;
    }
    
    public boolean isTransparent() {
        return opacity < 1.0f;
    }
}