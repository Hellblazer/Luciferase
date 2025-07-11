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
package com.hellblazer.luciferase.lucien.lockfree;

import com.hellblazer.luciferase.lucien.entity.EntityID;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lock-free spatial node implementation using atomic operations and copy-on-write semantics.
 * Designed for high-concurrency scenarios where multiple threads frequently read and occasionally write.
 * 
 * <p>This implementation prioritizes consistency and lock-free operations over raw performance
 * for single-threaded scenarios. Entity additions and removals are atomic and visible immediately
 * to all threads without requiring explicit synchronization.</p>
 *
 * @param <ID> The entity ID type
 * @author hal.hildebrand
 */
public class AtomicSpatialNode<ID extends EntityID> {

    /**
     * States for coordinating node lifecycle and operations
     */
    public enum NodeState {
        /** Node is active and accepting entities */
        ACTIVE,
        /** Node is being subdivided - no new entities */
        SUBDIVIDING,
        /** Node is being removed - cleanup in progress */
        REMOVING,
        /** Node has been subdivided and should not be used */
        SUBDIVIDED,
        /** Node is marked for removal but may still contain entities */
        MARKED_FOR_REMOVAL
    }

    // Thread-safe entity storage with atomic operations
    private final CopyOnWriteArraySet<ID> entities;
    
    // Atomic counters for efficient size tracking
    private final AtomicInteger entityCount;
    
    // Node state for coordination between threads
    private final AtomicReference<NodeState> state;
    
    // Configuration
    private final int maxEntitiesPerNode;
    private final byte level;

    /**
     * Creates a new atomic spatial node.
     *
     * @param maxEntitiesPerNode Maximum entities before subdivision
     * @param level The spatial level of this node
     */
    public AtomicSpatialNode(int maxEntitiesPerNode, byte level) {
        this.entities = new CopyOnWriteArraySet<>();
        this.entityCount = new AtomicInteger(0);
        this.state = new AtomicReference<>(NodeState.ACTIVE);
        this.maxEntitiesPerNode = maxEntitiesPerNode;
        this.level = level;
    }

    /**
     * Atomically adds an entity to this node.
     * 
     * @param entityId The entity to add
     * @return true if added successfully, false if node is not accepting entities
     */
    public boolean addEntity(ID entityId) {
        var currentState = state.get();
        if (currentState != NodeState.ACTIVE) {
            return false;
        }

        if (entities.add(entityId)) {
            entityCount.incrementAndGet();
            return true;
        }
        return false; // Entity already present
    }

    /**
     * Atomically removes an entity from this node.
     * 
     * @param entityId The entity to remove
     * @return true if removed successfully, false if not found
     */
    public boolean removeEntity(ID entityId) {
        if (entities.remove(entityId)) {
            entityCount.decrementAndGet();
            
            // Check if node should be marked for removal when empty
            if (entityCount.get() == 0 && state.get() == NodeState.ACTIVE) {
                state.compareAndSet(NodeState.ACTIVE, NodeState.MARKED_FOR_REMOVAL);
            }
            return true;
        }
        return false;
    }

    /**
     * Atomically adds multiple entities in a batch operation.
     * 
     * @param entityIds Collection of entities to add
     * @return Number of entities successfully added
     */
    public int addEntities(Collection<ID> entityIds) {
        var currentState = state.get();
        if (currentState != NodeState.ACTIVE) {
            return 0;
        }

        int added = 0;
        for (var entityId : entityIds) {
            if (entities.add(entityId)) {
                added++;
            }
        }
        
        if (added > 0) {
            entityCount.addAndGet(added);
        }
        
        return added;
    }

    /**
     * Atomically removes multiple entities in a batch operation.
     * 
     * @param entityIds Collection of entities to remove
     * @return Number of entities successfully removed
     */
    public int removeEntities(Collection<ID> entityIds) {
        int removed = 0;
        for (var entityId : entityIds) {
            if (entities.remove(entityId)) {
                removed++;
            }
        }
        
        if (removed > 0) {
            entityCount.addAndGet(-removed);
            
            // Check if node should be marked for removal when empty
            if (entityCount.get() == 0 && state.get() == NodeState.ACTIVE) {
                state.compareAndSet(NodeState.ACTIVE, NodeState.MARKED_FOR_REMOVAL);
            }
        }
        
        return removed;
    }

    /**
     * Checks if this node contains the specified entity.
     * 
     * @param entityId The entity to check
     * @return true if entity is present
     */
    public boolean containsEntity(ID entityId) {
        return entities.contains(entityId);
    }

    /**
     * Gets a snapshot of all entities in this node.
     * The returned set is immutable and thread-safe to iterate.
     * 
     * @return Immutable set of entity IDs
     */
    public Set<ID> getEntities() {
        return Set.copyOf(entities);
    }

    /**
     * Gets the current entity count.
     * 
     * @return Number of entities in this node
     */
    public int getEntityCount() {
        return entityCount.get();
    }

    /**
     * Checks if this node should be subdivided based on entity count.
     * 
     * @return true if subdivision is recommended
     */
    public boolean shouldSubdivide() {
        return entityCount.get() > maxEntitiesPerNode && state.get() == NodeState.ACTIVE;
    }

    /**
     * Checks if this node is empty and can be removed.
     * 
     * @return true if node is empty
     */
    public boolean isEmpty() {
        return entityCount.get() == 0;
    }

    /**
     * Gets the current node state.
     * 
     * @return Current NodeState
     */
    public NodeState getState() {
        return state.get();
    }

    /**
     * Attempts to transition the node to subdivision state.
     * This is used to coordinate subdivision operations between threads.
     * 
     * @return true if successfully transitioned to SUBDIVIDING state
     */
    public boolean tryBeginSubdivision() {
        return state.compareAndSet(NodeState.ACTIVE, NodeState.SUBDIVIDING);
    }

    /**
     * Marks the node as subdivided, preventing further entity additions.
     * Called after successful subdivision to indicate child nodes are active.
     * 
     * @return true if successfully marked as subdivided
     */
    public boolean markSubdivided() {
        var currentState = state.get();
        return (currentState == NodeState.SUBDIVIDING || currentState == NodeState.ACTIVE) &&
               state.compareAndSet(currentState, NodeState.SUBDIVIDED);
    }

    /**
     * Attempts to mark the node for removal.
     * Used when the node becomes empty and can be garbage collected.
     * 
     * @return true if successfully marked for removal
     */
    public boolean tryMarkForRemoval() {
        var currentState = state.get();
        return (currentState == NodeState.ACTIVE || currentState == NodeState.MARKED_FOR_REMOVAL) &&
               entityCount.get() == 0 &&
               state.compareAndSet(currentState, NodeState.MARKED_FOR_REMOVAL);
    }

    /**
     * Forces the node into removal state for cleanup operations.
     * Use with caution - should only be called during coordinated cleanup.
     */
    public void forceRemovalState() {
        state.set(NodeState.REMOVING);
    }

    /**
     * Gets the spatial level of this node.
     * 
     * @return The level in the spatial hierarchy
     */
    public byte getLevel() {
        return level;
    }

    /**
     * Gets statistics about this node for monitoring and debugging.
     * 
     * @return NodeStats containing current state information
     */
    public NodeStats getStats() {
        return new NodeStats(
            entityCount.get(),
            state.get(),
            level,
            maxEntitiesPerNode,
            shouldSubdivide(),
            isEmpty()
        );
    }

    /**
     * Statistics snapshot for a spatial node
     */
    public record NodeStats(
        int entityCount,
        NodeState state,
        byte level,
        int maxEntitiesPerNode,
        boolean shouldSubdivide,
        boolean isEmpty
    ) {}

    @Override
    public String toString() {
        return String.format("AtomicSpatialNode{level=%d, entities=%d, state=%s, maxEntities=%d}",
                           level, entityCount.get(), state.get(), maxEntitiesPerNode);
    }
}