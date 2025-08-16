package com.hellblazer.luciferase.render.voxel.esvo.voxelization;

import java.util.HashMap;
import java.util.Map;

/**
 * Texture atlas for packing multiple textures.
 */
public class TextureAtlas {
    private final int width;
    private final int height;
    private final Map<String, TextureRegion> regions;
    
    public TextureAtlas(int width, int height) {
        this.width = width;
        this.height = height;
        this.regions = new HashMap<>();
    }
    
    public void addTexture(String name, int x, int y, int width, int height) {
        regions.put(name, new TextureRegion(x, y, width, height));
    }
    
    public TextureRegion getRegion(String name) {
        return regions.get(name);
    }
    
    public int getWidth() {
        return width;
    }
    
    public int getHeight() {
        return height;
    }
    
    public static class TextureRegion {
        public final int x;
        public final int y;
        public final int width;
        public final int height;
        
        public TextureRegion(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }
}