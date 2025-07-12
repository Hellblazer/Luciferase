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
package com.hellblazer.luciferase.lucien.forest;

import com.hellblazer.luciferase.lucien.SpatialKey;

import javax.vecmath.Point3f;
import java.util.Objects;

/**
 * An immutable record of an entity's location within a forest of spatial index trees.
 * 
 * <p>TreeLocation captures the complete spatial state of an entity at a specific point in time,
 * including which tree contains it, its node location within that tree, and its exact position
 * in 3D space.
 * 
 * <p>This class is immutable and thread-safe. All fields are final and defensive copies are made
 * of mutable objects (Point3f) in the constructor.
 * 
 * @param <Key> The spatial key type (e.g., MortonKey, TetreeKey)
 * @author hal.hildebrand
 */
public class TreeLocation<Key extends SpatialKey<Key>> {
    
    /** The ID of the tree containing the entity */
    private final String treeId;
    
    /** The spatial key identifying the node within the tree */
    private final Key nodeKey;
    
    /** The exact position of the entity in 3D space */
    private final Point3f position;
    
    /** The timestamp when this location was recorded (milliseconds since epoch) */
    private final long timestamp;
    
    /**
     * Creates a new TreeLocation with the current timestamp.
     * 
     * @param treeId   the ID of the tree containing the entity
     * @param nodeKey  the spatial key of the node containing the entity
     * @param position the exact position of the entity (defensive copy made)
     * @throws NullPointerException if any parameter is null
     */
    public TreeLocation(String treeId, Key nodeKey, Point3f position) {
        this(treeId, nodeKey, position, System.currentTimeMillis());
    }
    
    /**
     * Creates a new TreeLocation with a specific timestamp.
     * 
     * @param treeId    the ID of the tree containing the entity
     * @param nodeKey   the spatial key of the node containing the entity
     * @param position  the exact position of the entity (defensive copy made)
     * @param timestamp the timestamp when this location was recorded
     * @throws NullPointerException if treeId, nodeKey, or position is null
     * @throws IllegalArgumentException if timestamp is negative
     */
    public TreeLocation(String treeId, Key nodeKey, Point3f position, long timestamp) {
        this.treeId = Objects.requireNonNull(treeId, "Tree ID cannot be null");
        this.nodeKey = Objects.requireNonNull(nodeKey, "Node key cannot be null");
        Objects.requireNonNull(position, "Position cannot be null");
        this.position = new Point3f(position); // Defensive copy
        
        if (timestamp < 0) {
            throw new IllegalArgumentException("Timestamp cannot be negative: " + timestamp);
        }
        this.timestamp = timestamp;
    }
    
    /**
     * Get the ID of the tree containing the entity.
     * 
     * @return the tree ID
     */
    public String getTreeId() {
        return treeId;
    }
    
    /**
     * Get the spatial key of the node containing the entity.
     * 
     * @return the node key
     */
    public Key getNodeKey() {
        return nodeKey;
    }
    
    /**
     * Get the exact position of the entity in 3D space.
     * 
     * @return a copy of the position to maintain immutability
     */
    public Point3f getPosition() {
        return new Point3f(position); // Return defensive copy
    }
    
    /**
     * Get the timestamp when this location was recorded.
     * 
     * @return timestamp in milliseconds since epoch
     */
    public long getTimestamp() {
        return timestamp;
    }
    
    /**
     * Get the age of this location record in milliseconds.
     * 
     * @return age in milliseconds (current time - timestamp)
     */
    public long getAge() {
        return System.currentTimeMillis() - timestamp;
    }
    
    /**
     * Check if this location is within the same tree as another location.
     * 
     * @param other the other location to compare
     * @return true if both locations are in the same tree
     */
    public boolean isSameTree(TreeLocation<?> other) {
        return other != null && treeId.equals(other.treeId);
    }
    
    /**
     * Check if this location is at the same node as another location.
     * 
     * @param other the other location to compare
     * @return true if both locations are in the same tree and node
     */
    public boolean isSameNode(TreeLocation<Key> other) {
        return other != null && treeId.equals(other.treeId) && nodeKey.equals(other.nodeKey);
    }
    
    /**
     * Create a new TreeLocation with an updated position but same tree and node.
     * 
     * @param newPosition the new position
     * @return a new TreeLocation with updated position and current timestamp
     */
    public TreeLocation<Key> withPosition(Point3f newPosition) {
        return new TreeLocation<>(treeId, nodeKey, newPosition);
    }
    
    /**
     * Create a new TreeLocation with an updated node but same tree and position.
     * 
     * @param newNodeKey the new node key
     * @return a new TreeLocation with updated node and current timestamp
     */
    public TreeLocation<Key> withNode(Key newNodeKey) {
        return new TreeLocation<>(treeId, newNodeKey, position);
    }
    
    /**
     * Create a new TreeLocation in a different tree.
     * 
     * @param newTreeId  the new tree ID
     * @param newNodeKey the new node key
     * @return a new TreeLocation with updated tree and node, keeping same position
     */
    public TreeLocation<Key> withTree(String newTreeId, Key newNodeKey) {
        return new TreeLocation<>(newTreeId, newNodeKey, position);
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        
        var that = (TreeLocation<?>) o;
        return timestamp == that.timestamp &&
               treeId.equals(that.treeId) &&
               nodeKey.equals(that.nodeKey) &&
               position.equals(that.position);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(treeId, nodeKey, position, timestamp);
    }
    
    @Override
    public String toString() {
        return String.format("TreeLocation[tree=%s, node=%s, pos=(%.2f,%.2f,%.2f), time=%d]",
                           treeId, nodeKey, position.x, position.y, position.z, timestamp);
    }
}