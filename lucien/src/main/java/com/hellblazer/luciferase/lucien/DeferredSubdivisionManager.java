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

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages deferred subdivisions during bulk operations to improve performance.
 * Instead of subdividing nodes immediately when they exceed capacity, this manager
 * tracks nodes that need subdivision and processes them in batch after bulk insertions.
 *
 * @param <ID> The type of EntityID used for entity identification
 * @param <NodeType> The type of spatial nodes
 * 
 * @author hal.hildebrand
 */
public class DeferredSubdivisionManager<ID extends EntityID, NodeType extends SpatialNodeStorage<ID>> {
    
    /**
     * Information about a node that needs subdivision
     */
    public static class SubdivisionCandidate<NodeType> {
        public final long nodeIndex;
        public final NodeType node;
        public final int entityCount;
        public final byte level;
        public final long timestamp;
        
        public SubdivisionCandidate(long nodeIndex, NodeType node, int entityCount, byte level) {
            this.nodeIndex = nodeIndex;
            this.node = node;
            this.entityCount = entityCount;
            this.level = level;
            this.timestamp = System.nanoTime();
        }
        
        /**
         * Priority for subdivision - higher entity counts get higher priority
         */
        public int getPriority() {
            return entityCount;
        }
    }
    
    /**
     * Result of batch subdivision processing
     */
    public static class SubdivisionResult {
        public final int nodesProcessed;
        public final int nodesSubdivided;
        public final int newNodesCreated;
        public final long processingTimeNanos;
        public final Map<Long, Integer> redistributionCounts;
        
        public SubdivisionResult(int nodesProcessed, int nodesSubdivided, 
                                int newNodesCreated, long processingTimeNanos,
                                Map<Long, Integer> redistributionCounts) {
            this.nodesProcessed = nodesProcessed;
            this.nodesSubdivided = nodesSubdivided;
            this.newNodesCreated = newNodesCreated;
            this.processingTimeNanos = processingTimeNanos;
            this.redistributionCounts = redistributionCounts;
        }
        
        public double getProcessingTimeMs() {
            return processingTimeNanos / 1_000_000.0;
        }
        
        public double getSubdivisionRate() {
            return nodesProcessed > 0 ? (double) nodesSubdivided / nodesProcessed : 0;
        }
    }
    
    // Candidates for subdivision, keyed by node index
    private final Map<Long, SubdivisionCandidate<NodeType>> candidates;
    
    // Configuration
    private final int maxDeferredNodes;
    private final boolean priorityBasedProcessing;
    private final double subdivisionThreshold;
    
    // Statistics
    private final AtomicInteger totalDeferred = new AtomicInteger(0);
    private final AtomicInteger totalProcessed = new AtomicInteger(0);
    
    public DeferredSubdivisionManager() {
        this(10000, true, 0.8);
    }
    
    public DeferredSubdivisionManager(int maxDeferredNodes, 
                                     boolean priorityBasedProcessing,
                                     double subdivisionThreshold) {
        this.candidates = new ConcurrentHashMap<>();
        this.maxDeferredNodes = maxDeferredNodes;
        this.priorityBasedProcessing = priorityBasedProcessing;
        this.subdivisionThreshold = subdivisionThreshold;
    }
    
    /**
     * Mark a node for deferred subdivision
     */
    public void deferSubdivision(long nodeIndex, NodeType node, int entityCount, byte level) {
        if (candidates.size() >= maxDeferredNodes) {
            // If we've hit the limit, process immediately or drop based on priority
            if (priorityBasedProcessing) {
                processLowestPriorityIfNeeded(entityCount);
            }
        }
        
        SubdivisionCandidate<NodeType> candidate = new SubdivisionCandidate<>(
            nodeIndex, node, entityCount, level
        );
        
        candidates.put(nodeIndex, candidate);
        totalDeferred.incrementAndGet();
    }
    
    /**
     * Check if a node is marked for deferred subdivision
     */
    public boolean isDeferred(long nodeIndex) {
        return candidates.containsKey(nodeIndex);
    }
    
    /**
     * Process all deferred subdivisions
     */
    public SubdivisionResult processAll(SubdivisionProcessor<ID, NodeType> processor) {
        if (candidates.isEmpty()) {
            return new SubdivisionResult(0, 0, 0, 0, Collections.emptyMap());
        }
        
        long startTime = System.nanoTime();
        
        // Get candidates in processing order
        List<SubdivisionCandidate<NodeType>> toProcess = getProcessingOrder();
        
        // Clear candidates map
        candidates.clear();
        
        // Process subdivisions
        int nodesProcessed = 0;
        int nodesSubdivided = 0;
        int newNodesCreated = 0;
        Map<Long, Integer> redistributionCounts = new HashMap<>();
        
        for (SubdivisionCandidate<NodeType> candidate : toProcess) {
            nodesProcessed++;
            
            // Check if node still needs subdivision
            if (shouldSubdivide(candidate)) {
                SubdivisionProcessor.Result result = processor.subdivideNode(
                    candidate.nodeIndex,
                    candidate.node,
                    candidate.level
                );
                
                if (result.wasSubdivided()) {
                    nodesSubdivided++;
                    newNodesCreated += result.getNewNodeCount();
                    redistributionCounts.put(candidate.nodeIndex, result.getEntitiesRedistributed());
                }
            }
        }
        
        totalProcessed.addAndGet(nodesProcessed);
        long processingTime = System.nanoTime() - startTime;
        
        return new SubdivisionResult(nodesProcessed, nodesSubdivided, 
                                    newNodesCreated, processingTime,
                                    redistributionCounts);
    }
    
    /**
     * Process deferred subdivisions in batches
     */
    public List<SubdivisionResult> processBatches(SubdivisionProcessor<ID, NodeType> processor,
                                                  int batchSize) {
        List<SubdivisionResult> results = new ArrayList<>();
        
        while (!candidates.isEmpty()) {
            // Get next batch
            List<SubdivisionCandidate<NodeType>> batch = getNextBatch(batchSize);
            if (batch.isEmpty()) {
                break;
            }
            
            // Process batch
            long startTime = System.nanoTime();
            int nodesProcessed = 0;
            int nodesSubdivided = 0;
            int newNodesCreated = 0;
            Map<Long, Integer> redistributionCounts = new HashMap<>();
            
            for (SubdivisionCandidate<NodeType> candidate : batch) {
                nodesProcessed++;
                
                if (shouldSubdivide(candidate)) {
                    SubdivisionProcessor.Result result = processor.subdivideNode(
                        candidate.nodeIndex,
                        candidate.node,
                        candidate.level
                    );
                    
                    if (result.wasSubdivided()) {
                        nodesSubdivided++;
                        newNodesCreated += result.getNewNodeCount();
                        redistributionCounts.put(candidate.nodeIndex, result.getEntitiesRedistributed());
                    }
                }
                
                // Remove from candidates
                candidates.remove(candidate.nodeIndex);
            }
            
            totalProcessed.addAndGet(nodesProcessed);
            long processingTime = System.nanoTime() - startTime;
            
            results.add(new SubdivisionResult(nodesProcessed, nodesSubdivided,
                                            newNodesCreated, processingTime,
                                            redistributionCounts));
        }
        
        return results;
    }
    
    /**
     * Clear all deferred subdivisions without processing
     */
    public void clear() {
        candidates.clear();
    }
    
    /**
     * Get current statistics
     */
    public DeferredStats getStats() {
        return new DeferredStats(
            candidates.size(),
            totalDeferred.get(),
            totalProcessed.get(),
            getAverageEntityCount()
        );
    }
    
    public static class DeferredStats {
        public final int currentDeferred;
        public final int totalDeferred;
        public final int totalProcessed;
        public final double averageEntityCount;
        
        public DeferredStats(int currentDeferred, int totalDeferred, 
                           int totalProcessed, double averageEntityCount) {
            this.currentDeferred = currentDeferred;
            this.totalDeferred = totalDeferred;
            this.totalProcessed = totalProcessed;
            this.averageEntityCount = averageEntityCount;
        }
    }
    
    /**
     * Interface for processing subdivisions
     */
    public interface SubdivisionProcessor<ID extends EntityID, NodeType> {
        Result subdivideNode(long nodeIndex, NodeType node, byte level);
        
        class Result {
            private final boolean subdivided;
            private final int newNodeCount;
            private final int entitiesRedistributed;
            
            public Result(boolean subdivided, int newNodeCount, int entitiesRedistributed) {
                this.subdivided = subdivided;
                this.newNodeCount = newNodeCount;
                this.entitiesRedistributed = entitiesRedistributed;
            }
            
            public boolean wasSubdivided() { return subdivided; }
            public int getNewNodeCount() { return newNodeCount; }
            public int getEntitiesRedistributed() { return entitiesRedistributed; }
        }
    }
    
    // Private helper methods
    
    private List<SubdivisionCandidate<NodeType>> getProcessingOrder() {
        List<SubdivisionCandidate<NodeType>> toProcess = new ArrayList<>(candidates.values());
        
        if (priorityBasedProcessing) {
            // Sort by priority (highest entity count first)
            toProcess.sort((a, b) -> Integer.compare(b.getPriority(), a.getPriority()));
        }
        
        return toProcess;
    }
    
    private List<SubdivisionCandidate<NodeType>> getNextBatch(int batchSize) {
        List<SubdivisionCandidate<NodeType>> batch = new ArrayList<>();
        List<SubdivisionCandidate<NodeType>> allCandidates = getProcessingOrder();
        
        for (int i = 0; i < Math.min(batchSize, allCandidates.size()); i++) {
            batch.add(allCandidates.get(i));
        }
        
        return batch;
    }
    
    private boolean shouldSubdivide(SubdivisionCandidate<NodeType> candidate) {
        // Could add additional checks here based on current tree state
        // For now, trust that if it was deferred, it needs subdivision
        return true;
    }
    
    private void processLowestPriorityIfNeeded(int newPriority) {
        // Find and process lowest priority candidate if new one has higher priority
        SubdivisionCandidate<NodeType> lowest = null;
        for (SubdivisionCandidate<NodeType> candidate : candidates.values()) {
            if (lowest == null || candidate.getPriority() < lowest.getPriority()) {
                lowest = candidate;
            }
        }
        
        if (lowest != null && lowest.getPriority() < newPriority) {
            candidates.remove(lowest.nodeIndex);
        }
    }
    
    private double getAverageEntityCount() {
        if (candidates.isEmpty()) {
            return 0;
        }
        
        int totalEntities = candidates.values().stream()
            .mapToInt(c -> c.entityCount)
            .sum();
            
        return (double) totalEntities / candidates.size();
    }
}