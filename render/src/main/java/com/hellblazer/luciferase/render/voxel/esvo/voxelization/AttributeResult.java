package com.hellblazer.luciferase.render.voxel.esvo.voxelization;

import java.util.HashMap;
import java.util.Map;

/**
 * Result of attribute injection containing attributes for each voxel.
 */
public class AttributeResult {
    private final Map<Voxel, VoxelAttribute> attributes;
    
    public AttributeResult() {
        this.attributes = new HashMap<>();
    }
    
    public void addAttribute(Voxel voxel, VoxelAttribute attribute) {
        attributes.put(voxel, attribute);
    }
    
    public VoxelAttribute getAttributeFor(Voxel voxel) {
        return attributes.get(voxel);
    }
    
    public int getAttributeCount() {
        return attributes.size();
    }
}