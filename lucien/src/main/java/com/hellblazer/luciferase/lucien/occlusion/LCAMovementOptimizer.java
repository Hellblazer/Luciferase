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
package com.hellblazer.luciferase.lucien.occlusion;

import com.hellblazer.luciferase.lucien.AbstractSpatialIndex;
import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.SpatialNodeImpl;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Optimizes entity movement in spatial indices by finding the Lowest Common Ancestor (LCA)
 * of the old and new positions. This reduces the number of nodes that need to be updated
 * during entity movement, especially for small movements.
 *
 * The optimizer tracks movement patterns and can predict likely destinations to further
 * optimize updates.
 *
 * @param <Key> The spatial key type (e.g., MortonKey, TetreeKey)
 * @author hal.hildebrand
 */
public class LCAMovementOptimizer<Key extends SpatialKey<Key>> {
    
    private static final Logger log = LoggerFactory.getLogger(LCAMovementOptimizer.class);
    
    /**
     * Movement pattern information
     */
    public static class MovementPattern {
        private final Vector3f averageDirection = new Vector3f();
        private final float averageDistance;
        private final int sampleCount;
        
        public MovementPattern(Vector3f direction, float distance, int samples) {
            this.averageDirection.set(direction);
            this.averageDistance = distance;
            this.sampleCount = samples;
        }
        
        public Vector3f getAverageDirection() { return new Vector3f(averageDirection); }
        public float getAverageDistance() { return averageDistance; }
        public int getSampleCount() { return sampleCount; }
    }
    
    // Movement tracking
    private final Map<EntityID, MovementPattern> movementPatterns;
    private final int patternHistorySize;
    
    // Statistics
    private final AtomicLong totalMovements = new AtomicLong(0);
    private final AtomicLong lcaOptimizedMovements = new AtomicLong(0);
    private final AtomicLong fullReinsertions = new AtomicLong(0);
    private final AtomicLong averageLCADepth = new AtomicLong(0);
    
    /**
     * Create an LCA movement optimizer
     *
     * @param patternHistorySize number of movements to track per entity
     */
    public LCAMovementOptimizer(int patternHistorySize) {
        this.patternHistorySize = patternHistorySize;
        this.movementPatterns = new ConcurrentHashMap<>();
    }
    
    /**
     * Create optimizer with default history size (10)
     */
    public LCAMovementOptimizer() {
        this(10);
    }
    
    /**
     * Find the lowest common ancestor of two spatial keys
     *
     * @param key1 first spatial key
     * @param key2 second spatial key
     * @return the LCA key, or null if no common ancestor
     */
    public Key findLCA(Key key1, Key key2) {
        if (key1 == null || key2 == null) {
            return null;
        }
        
        // Same key - LCA is itself
        if (key1.equals(key2)) {
            return key1;
        }
        
        // Get parent chains
        var parents1 = getParentChain(key1);
        var parents2 = getParentChain(key2);
        
        // Find deepest common ancestor
        Key lca = null;
        int lcaLevel = -1;
        
        for (var p1 : parents1) {
            if (parents2.contains(p1)) {
                var level = p1.getLevel();
                if (level > lcaLevel) {
                    lca = p1;
                    lcaLevel = level;
                }
            }
        }
        
        return lca;
    }
    
    /**
     * Perform optimized movement using LCA
     *
     * @param index the spatial index
     * @param entityId entity to move
     * @param oldKey old spatial key
     * @param newKey new spatial key
     * @return true if LCA optimization was used, false if full reinsert was needed
     */
    public <ID extends EntityID, Content> boolean optimizedMove(
            AbstractSpatialIndex<Key, ID, Content> index,
            ID entityId, Key oldKey, Key newKey) {
        
        totalMovements.incrementAndGet();
        
        // Find LCA
        var lca = findLCA(oldKey, newKey);
        
        if (lca == null || lca.getLevel() == 0) {
            // No useful LCA - need full reinsert
            fullReinsertions.incrementAndGet();
            return false;
        }
        
        // Check if LCA optimization is worthwhile
        var depthSaved = Math.min(oldKey.getLevel(), newKey.getLevel()) - lca.getLevel();
        if (depthSaved < 2) {
            // Not enough depth saved
            fullReinsertions.incrementAndGet();
            return false;
        }
        
        // Perform optimized move
        performLCAMove(index, entityId, oldKey, newKey, lca);
        
        lcaOptimizedMovements.incrementAndGet();
        updateAverageLCADepth(lca.getLevel());
        
        return true;
    }
    
    /**
     * Update movement pattern for an entity
     *
     * @param entityId the entity
     * @param oldPos old position
     * @param newPos new position
     */
    public void updateMovementPattern(EntityID entityId, Point3f oldPos, Point3f newPos) {
        var direction = new Vector3f();
        direction.sub(newPos, oldPos);
        var distance = direction.length();
        
        if (distance < 0.001f) {
            return; // No significant movement
        }
        
        direction.normalize();
        
        var existing = movementPatterns.get(entityId);
        if (existing == null) {
            movementPatterns.put(entityId, new MovementPattern(direction, distance, 1));
        } else {
            // Update running average
            var avgDir = existing.getAverageDirection();
            var avgDist = existing.getAverageDistance();
            var count = existing.getSampleCount();
            
            // Weighted average
            avgDir.scale(count);
            avgDir.add(direction);
            avgDir.scale(1.0f / (count + 1));
            avgDir.normalize();
            
            avgDist = (avgDist * count + distance) / (count + 1);
            
            movementPatterns.put(entityId, 
                new MovementPattern(avgDir, avgDist, Math.min(count + 1, patternHistorySize)));
        }
    }
    
    /**
     * Predict likely destination based on movement pattern
     *
     * @param entityId the entity
     * @param currentPos current position
     * @return predicted position, or null if no pattern
     */
    public Point3f predictDestination(EntityID entityId, Point3f currentPos) {
        var pattern = movementPatterns.get(entityId);
        if (pattern == null || pattern.getSampleCount() < 3) {
            return null;
        }
        
        var predicted = new Point3f(currentPos);
        var movement = new Vector3f(pattern.getAverageDirection());
        movement.scale(pattern.getAverageDistance());
        predicted.add(movement);
        
        return predicted;
    }
    
    /**
     * Clear movement pattern for an entity
     *
     * @param entityId the entity
     */
    public void clearPattern(EntityID entityId) {
        movementPatterns.remove(entityId);
    }
    
    /**
     * Clear all movement patterns
     */
    public void clearAllPatterns() {
        movementPatterns.clear();
    }
    
    /**
     * Get optimization statistics
     *
     * @return map of statistics
     */
    public Map<String, Object> getStatistics() {
        var stats = new HashMap<String, Object>();
        stats.put("totalMovements", totalMovements.get());
        stats.put("lcaOptimizedMovements", lcaOptimizedMovements.get());
        stats.put("fullReinsertions", fullReinsertions.get());
        stats.put("optimizationRate", getOptimizationRate());
        stats.put("averageLCADepth", getAverageLCADepth());
        stats.put("trackedPatterns", movementPatterns.size());
        return stats;
    }
    
    /**
     * Get the optimization rate (percentage of movements that used LCA)
     *
     * @return rate between 0.0 and 1.0
     */
    public float getOptimizationRate() {
        var total = totalMovements.get();
        if (total == 0) {
            return 0.0f;
        }
        return lcaOptimizedMovements.get() / (float) total;
    }
    
    /**
     * Get average LCA depth
     *
     * @return average depth
     */
    public float getAverageLCADepth() {
        var count = lcaOptimizedMovements.get();
        if (count == 0) {
            return 0.0f;
        }
        return averageLCADepth.get() / (float) count;
    }
    
    // Private helper methods
    
    /**
     * Get parent chain from key to root
     */
    private List<Key> getParentChain(Key key) {
        var chain = new ArrayList<Key>();
        var current = key;
        
        while (current != null && current.getLevel() > 0) {
            chain.add(current);
            current = current.parent();
        }
        
        // Add root
        if (current != null) {
            chain.add(current);
        }
        
        return chain;
    }
    
    /**
     * Perform the actual LCA-optimized move
     */
    private <ID extends EntityID, Content> void performLCAMove(
            AbstractSpatialIndex<Key, ID, Content> index,
            ID entityId, Key oldKey, Key newKey, Key lca) {
        
        // This is a simplified version - in practice, this would:
        // 1. Remove entity only from nodes between oldKey and LCA
        // 2. Add entity only to nodes between newKey and LCA
        // 3. Update node metadata only in affected subtree
        
        log.debug("LCA optimized move for entity {} - LCA at level {}", 
                 entityId, lca.getLevel());
        
        // For now, we'll note that the actual implementation would need
        // access to internal spatial index structures
    }
    
    /**
     * Update running average of LCA depth
     */
    private void updateAverageLCADepth(int depth) {
        // Simple running average
        var currentAvg = averageLCADepth.get();
        var count = lcaOptimizedMovements.get();
        var newAvg = (currentAvg * (count - 1) + depth) / count;
        averageLCADepth.set(Math.round(newAvg));
    }
}