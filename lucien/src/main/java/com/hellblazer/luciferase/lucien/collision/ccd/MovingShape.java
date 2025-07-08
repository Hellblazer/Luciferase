/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien.collision.ccd;

import com.hellblazer.luciferase.lucien.collision.CollisionShape;
import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

/**
 * Represents a collision shape with motion information for continuous collision detection.
 *
 * @author hal.hildebrand
 */
public class MovingShape {
    
    private final CollisionShape shape;
    private final Point3f startPosition;
    private final Point3f endPosition;
    private final Vector3f linearVelocity;
    private final Vector3f angularVelocity;
    private final float startTime;
    private final float endTime;
    
    /**
     * Create a moving shape with linear motion only
     */
    public MovingShape(CollisionShape shape, Point3f startPosition, Point3f endPosition, 
                      float startTime, float endTime) {
        this.shape = shape;
        this.startPosition = new Point3f(startPosition);
        this.endPosition = new Point3f(endPosition);
        this.startTime = startTime;
        this.endTime = endTime;
        
        // Calculate linear velocity
        this.linearVelocity = new Vector3f();
        linearVelocity.sub(endPosition, startPosition);
        float deltaTime = endTime - startTime;
        if (deltaTime > 0) {
            linearVelocity.scale(1.0f / deltaTime);
        }
        
        this.angularVelocity = new Vector3f(0, 0, 0);
    }
    
    /**
     * Create a moving shape with linear and angular motion
     */
    public MovingShape(CollisionShape shape, Point3f startPosition, Vector3f linearVelocity,
                      Vector3f angularVelocity, float startTime, float endTime) {
        this.shape = shape;
        this.startPosition = new Point3f(startPosition);
        this.linearVelocity = new Vector3f(linearVelocity);
        this.angularVelocity = new Vector3f(angularVelocity);
        this.startTime = startTime;
        this.endTime = endTime;
        
        // Calculate end position
        this.endPosition = new Point3f(startPosition);
        Vector3f displacement = new Vector3f(linearVelocity);
        displacement.scale(endTime - startTime);
        endPosition.add(displacement);
    }
    
    /**
     * Get the position at a specific time (0.0 to 1.0)
     */
    public Point3f getPositionAtTime(float t) {
        Point3f result = new Point3f();
        result.interpolate(startPosition, endPosition, t);
        return result;
    }
    
    /**
     * Get the shape at a specific time (with interpolated position)
     */
    public CollisionShape getShapeAtTime(float t) {
        Point3f position = getPositionAtTime(t);
        Vector3f delta = new Vector3f();
        delta.sub(position, shape.getPosition());
        
        // Create a copy of the shape at the new position
        // Note: This is simplified - a full implementation would handle rotation
        CollisionShape movedShape = shape; // Would need proper cloning
        movedShape.translate(delta);
        return movedShape;
    }
    
    // Getters
    public CollisionShape getShape() { return shape; }
    public Point3f getStartPosition() { return new Point3f(startPosition); }
    public Point3f getEndPosition() { return new Point3f(endPosition); }
    public Vector3f getLinearVelocity() { return new Vector3f(linearVelocity); }
    public Vector3f getAngularVelocity() { return new Vector3f(angularVelocity); }
    public float getStartTime() { return startTime; }
    public float getEndTime() { return endTime; }
    
    /**
     * Get the motion vector (displacement)
     */
    public Vector3f getMotionVector() {
        Vector3f motion = new Vector3f();
        motion.sub(endPosition, startPosition);
        return motion;
    }
    
    /**
     * Check if this shape is actually moving
     */
    public boolean isMoving() {
        return linearVelocity.lengthSquared() > 0 || angularVelocity.lengthSquared() > 0;
    }
}