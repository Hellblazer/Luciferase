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
package com.hellblazer.luciferase.lucien.entity;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.ArrayList;
import java.util.List;

/**
 * Tracks dynamic properties of an entity including velocity, acceleration, and movement history.
 * Uses a circular buffer to maintain position history for velocity calculation and prediction.
 *
 * @author hal.hildebrand
 */
public class EntityDynamics {
    /**
     * Timestamped position entry for movement history
     */
    private static class PositionEntry {
        final Point3f position;
        final long    timestamp;

        PositionEntry(Point3f position, long timestamp) {
            this.position = new Point3f(position);
            this.timestamp = timestamp;
        }
    }

    private final int                  historySize;
    private final List<PositionEntry>  positionHistory;
    private       Vector3f             velocity;
    private       Vector3f             acceleration;
    private       int                  historyIndex;
    private       boolean              historyWrapped;

    /**
     * Create entity dynamics with default history size of 10
     */
    public EntityDynamics() {
        this(10);
    }

    /**
     * Create entity dynamics with specified history size
     *
     * @param historySize Number of position entries to maintain in history
     */
    public EntityDynamics(int historySize) {
        if (historySize < 2) {
            throw new IllegalArgumentException("History size must be at least 2");
        }
        this.historySize = historySize;
        this.positionHistory = new ArrayList<>(historySize);
        // Pre-allocate list to avoid IndexOutOfBoundsException
        for (int i = 0; i < historySize; i++) {
            this.positionHistory.add(null);
        }
        this.velocity = new Vector3f(0, 0, 0);
        this.acceleration = new Vector3f(0, 0, 0);
        this.historyIndex = 0;
        this.historyWrapped = false;
    }

    /**
     * Update position and recalculate velocity and acceleration
     *
     * @param newPosition New position of the entity
     * @param timestamp   Timestamp of the position update (milliseconds)
     */
    public void updatePosition(Point3f newPosition, long timestamp) {
        var entry = new PositionEntry(newPosition, timestamp);
        
        // Set entry at current index
        positionHistory.set(historyIndex, entry);
        
        // Update index and check for wrap
        historyIndex = (historyIndex + 1) % historySize;
        if (!historyWrapped && historyIndex == 0) {
            historyWrapped = true;
        }
        
        // Calculate velocity if we have at least 2 positions
        if (getHistoryCount() >= 2) {
            calculateVelocity();
        }
        
        // Calculate acceleration if we have at least 3 positions  
        if (getHistoryCount() >= 3) {
            calculateAcceleration();
        }
    }

    /**
     * Calculate current velocity from recent position history
     */
    private void calculateVelocity() {
        var count = getHistoryCount();
        if (count < 2) {
            velocity.set(0, 0, 0);
            return;
        }
        
        // Get most recent and previous position
        var currentIdx = (historyIndex - 1 + historySize) % historySize;
        var previousIdx = (historyIndex - 2 + historySize) % historySize;
        
        var current = positionHistory.get(currentIdx);
        var previous = positionHistory.get(previousIdx);
        
        // Skip if either entry is null
        if (current == null || previous == null) {
            velocity.set(0, 0, 0);
            return;
        }
        
        var deltaTime = (current.timestamp - previous.timestamp) / 1000.0f; // Convert to seconds
        if (deltaTime > 0) {
            velocity.set(current.position.x - previous.position.x,
                        current.position.y - previous.position.y,
                        current.position.z - previous.position.z);
            velocity.scale(1.0f / deltaTime);
        }
    }

    /**
     * Calculate current acceleration from velocity changes
     */
    private void calculateAcceleration() {
        var count = getHistoryCount();
        if (count < 3) {
            acceleration.set(0, 0, 0);
            return;
        }
        
        // Calculate velocity at t-1 and t-2
        var idx1 = (historyIndex - 1 + historySize) % historySize;
        var idx2 = (historyIndex - 2 + historySize) % historySize;
        var idx3 = (historyIndex - 3 + historySize) % historySize;
        
        var pos1 = positionHistory.get(idx1);
        var pos2 = positionHistory.get(idx2);
        var pos3 = positionHistory.get(idx3);
        
        // Skip if any entry is null
        if (pos1 == null || pos2 == null || pos3 == null) {
            acceleration.set(0, 0, 0);
            return;
        }
        
        // Calculate velocities
        var deltaTime1 = (pos1.timestamp - pos2.timestamp) / 1000.0f;
        var deltaTime2 = (pos2.timestamp - pos3.timestamp) / 1000.0f;
        
        if (deltaTime1 > 0 && deltaTime2 > 0) {
            var v1 = new Vector3f((pos1.position.x - pos2.position.x) / deltaTime1,
                                 (pos1.position.y - pos2.position.y) / deltaTime1,
                                 (pos1.position.z - pos2.position.z) / deltaTime1);
            
            var v2 = new Vector3f((pos2.position.x - pos3.position.x) / deltaTime2,
                                 (pos2.position.y - pos3.position.y) / deltaTime2,
                                 (pos2.position.z - pos3.position.z) / deltaTime2);
            
            // Calculate acceleration
            acceleration.set(v1.x - v2.x, v1.y - v2.y, v1.z - v2.z);
            acceleration.scale(1.0f / deltaTime1);
        }
    }

    /**
     * Get current velocity
     *
     * @return Current velocity vector (units per second)
     */
    public Vector3f getVelocity() {
        return new Vector3f(velocity);
    }

    /**
     * Get current acceleration
     *
     * @return Current acceleration vector (units per second squared)
     */
    public Vector3f getAcceleration() {
        return new Vector3f(acceleration);
    }

    /**
     * Get current speed (magnitude of velocity)
     *
     * @return Current speed
     */
    public float getSpeed() {
        return velocity.length();
    }

    /**
     * Predict future position based on current velocity and acceleration
     *
     * @param deltaTime Time in the future (seconds)
     * @return Predicted position
     */
    public Point3f predictPosition(float deltaTime) {
        if (getHistoryCount() == 0) {
            return null;
        }
        
        // Get current position
        var currentIdx = (historyIndex - 1 + historySize) % historySize;
        var entry = positionHistory.get(currentIdx);
        if (entry == null) {
            return null;
        }
        
        // Calculate predicted position: p = p0 + v*t + 0.5*a*t^2
        var predicted = new Point3f(entry.position);
        predicted.x += velocity.x * deltaTime + 0.5f * acceleration.x * deltaTime * deltaTime;
        predicted.y += velocity.y * deltaTime + 0.5f * acceleration.y * deltaTime * deltaTime;
        predicted.z += velocity.z * deltaTime + 0.5f * acceleration.z * deltaTime * deltaTime;
        
        return predicted;
    }

    /**
     * Predict velocity at a future time based on current acceleration
     *
     * @param deltaTime Time in the future (seconds)
     * @return Predicted velocity
     */
    public Vector3f predictVelocity(float deltaTime) {
        // v = v0 + a*t
        var predicted = new Vector3f(velocity);
        predicted.x += acceleration.x * deltaTime;
        predicted.y += acceleration.y * deltaTime;
        predicted.z += acceleration.z * deltaTime;
        return predicted;
    }

    /**
     * Get the number of position entries in history
     *
     * @return Number of positions stored
     */
    public int getHistoryCount() {
        if (historyWrapped) {
            return historySize;
        }
        // Count non-null entries
        int count = 0;
        for (var entry : positionHistory) {
            if (entry != null) {
                count++;
            }
        }
        return count;
    }

    /**
     * Get the most recent position
     *
     * @return Most recent position or null if no history
     */
    public Point3f getCurrentPosition() {
        if (getHistoryCount() == 0) {
            return null;
        }
        var currentIdx = (historyIndex - 1 + historySize) % historySize;
        var entry = positionHistory.get(currentIdx);
        return entry != null ? new Point3f(entry.position) : null;
    }

    /**
     * Get the timestamp of the most recent position update
     *
     * @return Timestamp in milliseconds or -1 if no history
     */
    public long getCurrentTimestamp() {
        if (getHistoryCount() == 0) {
            return -1;
        }
        var currentIdx = (historyIndex - 1 + historySize) % historySize;
        var entry = positionHistory.get(currentIdx);
        return entry != null ? entry.timestamp : -1;
    }

    /**
     * Clear all history and reset velocity/acceleration
     */
    public void reset() {
        positionHistory.clear();
        velocity.set(0, 0, 0);
        acceleration.set(0, 0, 0);
        historyIndex = 0;
        historyWrapped = false;
    }

    /**
     * Get average velocity over the entire history
     *
     * @return Average velocity vector
     */
    public Vector3f getAverageVelocity() {
        var count = getHistoryCount();
        if (count < 2) {
            return new Vector3f(0, 0, 0);
        }
        
        var avgVelocity = new Vector3f(0, 0, 0);
        var validCount = 0;
        
        for (var i = 1; i < count; i++) {
            var idx1 = (historyIndex - i - 1 + historySize * 2) % historySize;
            var idx2 = (historyIndex - i + historySize * 2) % historySize;
            
            var pos1 = positionHistory.get(idx1);
            var pos2 = positionHistory.get(idx2);
            
            // Skip if either entry is null
            if (pos1 == null || pos2 == null) {
                continue;
            }
            
            var deltaTime = (pos2.timestamp - pos1.timestamp) / 1000.0f;
            if (deltaTime > 0) {
                var v = new Vector3f((pos2.position.x - pos1.position.x) / deltaTime,
                                    (pos2.position.y - pos1.position.y) / deltaTime,
                                    (pos2.position.z - pos1.position.z) / deltaTime);
                avgVelocity.add(v);
                validCount++;
            }
        }
        
        if (validCount > 0) {
            avgVelocity.scale(1.0f / validCount);
        }
        
        return avgVelocity;
    }

    /**
     * Check if the entity is moving (velocity magnitude above threshold)
     *
     * @param threshold Minimum speed to be considered moving
     * @return true if moving, false otherwise
     */
    public boolean isMoving(float threshold) {
        return getSpeed() > threshold;
    }

    /**
     * Check if the entity is accelerating (acceleration magnitude above threshold)
     *
     * @param threshold Minimum acceleration to be considered accelerating
     * @return true if accelerating, false otherwise
     */
    public boolean isAccelerating(float threshold) {
        return acceleration.length() > threshold;
    }
}