/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * Licensed under the AGPL License, Version 3.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hellblazer.luciferase.render.voxel.esvo;

import javax.vecmath.Vector3f;

/**
 * Coherent ray beam for ESVO beam optimization.
 * Represents a group of rays with similar directions for efficient traversal.
 */
public class ESVOBeam {
    
    private final ESVORay centerRay;
    private final float coneAngle;
    private final int rayCount;
    
    public ESVOBeam(ESVORay centerRay, float coneAngle, int rayCount) {
        this.centerRay = centerRay;
        this.coneAngle = coneAngle;
        this.rayCount = rayCount;
    }
    
    public ESVORay getCenterRay() {
        return centerRay;
    }
    
    public float getConeAngle() {
        return coneAngle;
    }
    
    public int getRayCount() {
        return rayCount;
    }
    
    @Override
    public String toString() {
        return String.format("ESVOBeam{centerRay=%s, coneAngle=%.3f, rayCount=%d}", 
                           centerRay, coneAngle, rayCount);
    }
}