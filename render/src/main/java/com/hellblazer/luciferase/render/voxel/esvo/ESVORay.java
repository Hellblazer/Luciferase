/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * Licensed under the AGPL License, Version 3.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hellblazer.luciferase.render.voxel.esvo;

import javax.vecmath.Vector3f;

/**
 * Ray for ESVO traversal operations.
 * Represents a ray with origin, direction, and maximum distance.
 */
public class ESVORay {
    
    private final Vector3f origin;
    private final Vector3f direction;
    private final float maxDistance;
    
    public ESVORay(Vector3f origin, Vector3f direction, float maxDistance) {
        this.origin = new Vector3f(origin);
        this.direction = new Vector3f(direction);
        this.direction.normalize();
        this.maxDistance = maxDistance;
    }
    
    public Vector3f getOrigin() {
        return new Vector3f(origin);
    }
    
    public Vector3f getDirection() {
        return new Vector3f(direction);
    }
    
    public float getMaxDistance() {
        return maxDistance;
    }
    
    public Vector3f getPointAt(float t) {
        var point = new Vector3f(direction);
        point.scale(t);
        point.add(origin);
        return point;
    }
    
    @Override
    public String toString() {
        return String.format("ESVORay{origin=%s, direction=%s, maxDistance=%.2f}", 
                           origin, direction, maxDistance);
    }
}