/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.portal.mesh.explorer;

import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pool for managing and reusing PhongMaterial instances to reduce memory allocation
 * and improve performance. Materials are keyed by their properties and reused when
 * possible.
 *
 * @author hal.hildebrand
 */
public class MaterialPool {
    
    /**
     * Key for identifying unique materials
     */
    private static class MaterialKey {
        final Color diffuseColor;
        final Color specularColor;
        final double opacity;
        final boolean selected;
        
        MaterialKey(Color diffuseColor, Color specularColor, double opacity, boolean selected) {
            this.diffuseColor = diffuseColor;
            this.specularColor = specularColor;
            this.opacity = opacity;
            this.selected = selected;
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            MaterialKey that = (MaterialKey) o;
            return Double.compare(that.opacity, opacity) == 0 &&
                   selected == that.selected &&
                   diffuseColor.equals(that.diffuseColor) &&
                   (specularColor == null ? that.specularColor == null : specularColor.equals(that.specularColor));
        }
        
        @Override
        public int hashCode() {
            int result = diffuseColor.hashCode();
            result = 31 * result + (specularColor != null ? specularColor.hashCode() : 0);
            long opacityBits = Double.doubleToLongBits(opacity);
            result = 31 * result + (int) (opacityBits ^ (opacityBits >>> 32));
            result = 31 * result + (selected ? 1 : 0);
            return result;
        }
    }
    
    // LRU cache for materials
    private final LinkedHashMap<MaterialKey, PhongMaterial> materials;
    private final int maxSize;
    
    /**
     * Create a material pool with default size (100 materials).
     */
    public MaterialPool() {
        this(100);
    }
    
    /**
     * Create a material pool with specified maximum size.
     *
     * @param maxSize Maximum number of materials to cache
     */
    public MaterialPool(int maxSize) {
        this.maxSize = maxSize;
        this.materials = new LinkedHashMap<MaterialKey, PhongMaterial>(maxSize + 1, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<MaterialKey, PhongMaterial> eldest) {
                return size() > MaterialPool.this.maxSize;
            }
        };
    }
    
    /**
     * Get a material with the specified properties, creating it if necessary.
     *
     * @param baseColor The base diffuse color
     * @param opacity The opacity (0.0 to 1.0)
     * @param selected Whether this is for a selected state
     * @return A PhongMaterial with the requested properties
     */
    public synchronized PhongMaterial getMaterial(Color baseColor, double opacity, boolean selected) {
        // Adjust color for selection if needed
        Color diffuse = selected ? baseColor.brighter() : baseColor;
        Color specular = selected ? Color.WHITE : baseColor.brighter();
        
        // Apply opacity to diffuse color
        diffuse = diffuse.deriveColor(0, 1, 1, opacity);
        
        MaterialKey key = new MaterialKey(diffuse, specular, opacity, selected);
        
        PhongMaterial material = materials.get(key);
        if (material == null) {
            material = createMaterial(diffuse, specular);
            materials.put(key, material);
        }
        
        return material;
    }
    
    /**
     * Get a simple material with just a base color.
     *
     * @param color The color
     * @return A PhongMaterial
     */
    public PhongMaterial getMaterial(Color color) {
        return getMaterial(color, 1.0, false);
    }
    
    /**
     * Get the current size of the material pool.
     *
     * @return Number of cached materials
     */
    public synchronized int getPoolSize() {
        return materials.size();
    }
    
    /**
     * Clear all cached materials.
     */
    public synchronized void clear() {
        materials.clear();
    }
    
    /**
     * Get pool statistics.
     *
     * @return Statistics map
     */
    public synchronized Map<String, Object> getStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("poolSize", materials.size());
        stats.put("maxSize", maxSize);
        stats.put("utilizationPercent", (materials.size() * 100.0) / maxSize);
        return stats;
    }
    
    /**
     * Get a material for wireframe rendering.
     *
     * @param type The type identifier (e.g., tetrahedron type)
     * @param color The wireframe color
     * @return A PhongMaterial for wireframe rendering
     */
    public synchronized PhongMaterial getWireframeMaterial(int type, Color color) {
        // Wireframes are always opaque
        MaterialKey key = new MaterialKey(color, Color.WHITE, 1.0, false);
        
        PhongMaterial material = materials.get(key);
        if (material == null) {
            material = createMaterial(color, Color.WHITE);
            material.setSpecularPower(32); // Add some shine to wireframes
            materials.put(key, material);
        }
        
        return material;
    }
    
    /**
     * Create a new PhongMaterial with the specified properties.
     */
    private PhongMaterial createMaterial(Color diffuse, Color specular) {
        PhongMaterial material = new PhongMaterial();
        material.setDiffuseColor(diffuse);
        if (specular != null) {
            material.setSpecularColor(specular);
        }
        return material;
    }
}