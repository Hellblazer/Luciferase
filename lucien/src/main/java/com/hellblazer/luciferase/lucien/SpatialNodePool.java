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
package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.entity.EntityID;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Object pool for spatial nodes to reduce allocation overhead during bulk operations.
 * Based on high-performance C++ spatial index memory pooling strategies.
 * 
 * Features:
 * - Thread-safe node recycling
 * - Configurable pool size limits
 * - Statistics tracking for optimization
 * - Type-specific pooling for different node implementations
 *
 * @param <NodeType> The type of spatial nodes to pool
 * 
 * @author hal.hildebrand
 */
public class SpatialNodePool<NodeType extends SpatialNodeStorage<?>> {
    
    /**
     * Configuration for the memory pool
     */
    public static class PoolConfig {
        private int initialSize = 100;
        private int maxSize = 10000;
        private double growthFactor = 2.0;
        private boolean preAllocate = true;
        private boolean enableStatistics = true;
        
        public PoolConfig withInitialSize(int size) {
            this.initialSize = size;
            return this;
        }
        
        public PoolConfig withMaxSize(int size) {
            this.maxSize = size;
            return this;
        }
        
        public PoolConfig withGrowthFactor(double factor) {
            this.growthFactor = factor;
            return this;
        }
        
        public PoolConfig withPreAllocation(boolean enable) {
            this.preAllocate = enable;
            return this;
        }
        
        public PoolConfig withStatistics(boolean enable) {
            this.enableStatistics = enable;
            return this;
        }
        
        public int getInitialSize() { return initialSize; }
        public int getMaxSize() { return maxSize; }
        public double getGrowthFactor() { return growthFactor; }
        public boolean isPreAllocate() { return preAllocate; }
        public boolean isEnableStatistics() { return enableStatistics; }
    }
    
    /**
     * Pool statistics for monitoring and optimization
     */
    public static class PoolStats {
        private final long allocations;
        private final long deallocations;
        private final long hits;
        private final long misses;
        private final int currentPoolSize;
        private final int peakPoolSize;
        private final long totalMemorySaved;
        
        public PoolStats(long allocations, long deallocations, long hits, long misses,
                        int currentPoolSize, int peakPoolSize, long totalMemorySaved) {
            this.allocations = allocations;
            this.deallocations = deallocations;
            this.hits = hits;
            this.misses = misses;
            this.currentPoolSize = currentPoolSize;
            this.peakPoolSize = peakPoolSize;
            this.totalMemorySaved = totalMemorySaved;
        }
        
        public double getHitRate() {
            long total = hits + misses;
            return total > 0 ? (double) hits / total : 0.0;
        }
        
        public long getAllocationsSaved() {
            return hits;
        }
        
        public double getPoolEfficiency() {
            return allocations > 0 ? (double) hits / allocations : 0.0;
        }
        
        // Getters
        public long getAllocations() { return allocations; }
        public long getDeallocations() { return deallocations; }
        public long getHits() { return hits; }
        public long getMisses() { return misses; }
        public int getCurrentPoolSize() { return currentPoolSize; }
        public int getPeakPoolSize() { return peakPoolSize; }
        public long getTotalMemorySaved() { return totalMemorySaved; }
    }
    
    // Pool storage
    private final Queue<NodeType> pool = new ConcurrentLinkedQueue<>();
    private final Supplier<NodeType> nodeFactory;
    private final PoolConfig config;
    
    // Statistics
    private final AtomicLong allocations = new AtomicLong(0);
    private final AtomicLong deallocations = new AtomicLong(0);
    private final AtomicLong hits = new AtomicLong(0);
    private final AtomicLong misses = new AtomicLong(0);
    private final AtomicInteger currentSize = new AtomicInteger(0);
    private final AtomicInteger peakSize = new AtomicInteger(0);
    private final AtomicLong memorySaved = new AtomicLong(0);
    
    // Estimated memory per node (bytes)
    private static final long ESTIMATED_NODE_MEMORY = 64; // Base object + collection overhead
    
    public SpatialNodePool(Supplier<NodeType> nodeFactory) {
        this(nodeFactory, new PoolConfig());
    }
    
    public SpatialNodePool(Supplier<NodeType> nodeFactory, PoolConfig config) {
        this.nodeFactory = nodeFactory;
        this.config = config;
        
        if (config.isPreAllocate()) {
            preAllocate();
        }
    }
    
    /**
     * Acquire a node from the pool or create a new one
     */
    public NodeType acquire() {
        NodeType node = pool.poll();
        
        if (node != null) {
            // Got node from pool
            if (config.isEnableStatistics()) {
                hits.incrementAndGet();
                memorySaved.addAndGet(ESTIMATED_NODE_MEMORY);
            }
            currentSize.decrementAndGet();
            
            // Clear the node for reuse
            node.clear();
        } else {
            // Pool is empty, create new node
            if (config.isEnableStatistics()) {
                misses.incrementAndGet();
                allocations.incrementAndGet();
            }
            node = nodeFactory.get();
        }
        
        return node;
    }
    
    /**
     * Release a node back to the pool for reuse
     */
    public void release(NodeType node) {
        if (node == null) {
            return;
        }
        
        int size = currentSize.get();
        if (size < config.getMaxSize()) {
            // Clear node state before returning to pool
            node.clear();
            
            pool.offer(node);
            int newSize = currentSize.incrementAndGet();
            
            if (config.isEnableStatistics()) {
                deallocations.incrementAndGet();
                updatePeakSize(newSize);
            }
        }
        // If pool is full, let GC handle the node
    }
    
    /**
     * Release multiple nodes back to the pool
     */
    public void releaseAll(Iterable<NodeType> nodes) {
        for (NodeType node : nodes) {
            release(node);
        }
    }
    
    /**
     * Pre-allocate nodes to the pool
     */
    public void preAllocate() {
        preAllocate(config.getInitialSize());
    }
    
    /**
     * Pre-allocate a specific number of nodes
     */
    public void preAllocate(int count) {
        int toAllocate = Math.min(count, config.getMaxSize() - currentSize.get());
        
        for (int i = 0; i < toAllocate; i++) {
            NodeType node = nodeFactory.get();
            pool.offer(node);
            
            if (config.isEnableStatistics()) {
                allocations.incrementAndGet();
            }
        }
        
        int newSize = currentSize.addAndGet(toAllocate);
        if (config.isEnableStatistics()) {
            updatePeakSize(newSize);
        }
    }
    
    /**
     * Grow the pool by the configured growth factor
     */
    public void grow() {
        int currentPoolSize = currentSize.get();
        int targetSize = (int) Math.min(currentPoolSize * config.getGrowthFactor(), config.getMaxSize());
        int toAllocate = targetSize - currentPoolSize;
        
        if (toAllocate > 0) {
            preAllocate(toAllocate);
        }
    }
    
    /**
     * Shrink the pool to a target size
     */
    public void shrink(int targetSize) {
        targetSize = Math.max(0, targetSize);
        
        while (currentSize.get() > targetSize) {
            NodeType node = pool.poll();
            if (node == null) {
                break;
            }
            currentSize.decrementAndGet();
        }
    }
    
    /**
     * Clear the pool, releasing all pooled nodes
     */
    public void clear() {
        pool.clear();
        currentSize.set(0);
    }
    
    /**
     * Get current pool statistics
     */
    public PoolStats getStats() {
        return new PoolStats(
            allocations.get(),
            deallocations.get(),
            hits.get(),
            misses.get(),
            currentSize.get(),
            peakSize.get(),
            memorySaved.get()
        );
    }
    
    /**
     * Reset statistics counters
     */
    public void resetStats() {
        allocations.set(0);
        deallocations.set(0);
        hits.set(0);
        misses.set(0);
        memorySaved.set(0);
        // Don't reset current/peak size as they represent pool state
    }
    
    /**
     * Get current pool size
     */
    public int size() {
        return currentSize.get();
    }
    
    /**
     * Check if pool is empty
     */
    public boolean isEmpty() {
        return pool.isEmpty();
    }
    
    /**
     * Get pool configuration
     */
    public PoolConfig getConfig() {
        return config;
    }
    
    // Private helper methods
    
    private void updatePeakSize(int size) {
        int peak;
        do {
            peak = peakSize.get();
            if (size <= peak) {
                break;
            }
        } while (!peakSize.compareAndSet(peak, size));
    }
    
    /**
     * Factory method for creating type-specific pools
     */
    public static <ID extends EntityID, NodeType extends SpatialNodeStorage<ID>> 
    SpatialNodePool<NodeType> createPool(Supplier<NodeType> nodeFactory, PoolConfig config) {
        return new SpatialNodePool<>(nodeFactory, config);
    }
    
    /**
     * Create a pool with default configuration
     */
    public static <ID extends EntityID, NodeType extends SpatialNodeStorage<ID>> 
    SpatialNodePool<NodeType> createDefaultPool(Supplier<NodeType> nodeFactory) {
        return new SpatialNodePool<>(nodeFactory, new PoolConfig());
    }
}