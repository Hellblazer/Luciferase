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
 * Manages deferred subdivisions during bulk operations to improve performance. Instead of subdividing nodes immediately
 * when they exceed capacity, this manager tracks nodes that need subdivision and processes them in batch after bulk
 * insertions.
 *
 * @param <ID>       The type of EntityID used for entity identification
 * @param <NodeType> The type of spatial nodes
 * @author hal.hildebrand
 */
public class DeferredSubdivisionManager<Key extends SpatialKey<Key>, ID extends EntityID, NodeType extends SpatialNodeStorage<ID>> {

    // Candidates for subdivision, keyed by node index
    private final Map<Key, SubdivisionCandidate<Key, NodeType>> candidates;
    // Configuration
    private final int                                           maxDeferredNodes;
    private final boolean                                       priorityBasedProcessing;
    private final double                                        subdivisionThreshold;
    // Statistics
    private final AtomicInteger                                 totalDeferred  = new AtomicInteger(0);
    private final AtomicInteger                                 totalProcessed = new AtomicInteger(0);

    public DeferredSubdivisionManager() {
        this(10000, true, 0.8);
    }

    public DeferredSubdivisionManager(int maxDeferredNodes, boolean priorityBasedProcessing,
                                      double subdivisionThreshold) {
        this.candidates = new ConcurrentHashMap<>();
        this.maxDeferredNodes = maxDeferredNodes;
        this.priorityBasedProcessing = priorityBasedProcessing;
        this.subdivisionThreshold = subdivisionThreshold;
    }

    /**
     * Clear all deferred subdivisions without processing
     */
    public void clear() {
        candidates.clear();
    }

    /**
     * Mark a node for deferred subdivision
     */
    public void deferSubdivision(Key nodeIndex, NodeType node, int entityCount, byte level) {
        if (candidates.size() >= maxDeferredNodes) {
            // If we've hit the limit, process immediately or drop based on priority
            if (priorityBasedProcessing) {
                processLowestPriorityIfNeeded(entityCount);
            }
        }

        var candidate = new SubdivisionCandidate<>(nodeIndex, node, entityCount, level);

        candidates.put(nodeIndex, candidate);
        totalDeferred.incrementAndGet();
    }

    /**
     * Get current statistics
     */
    public DeferredStats getStats() {
        return new DeferredStats(candidates.size(), totalDeferred.get(), totalProcessed.get(), getAverageEntityCount());
    }

    /**
     * Check if a node is marked for deferred subdivision
     */
    public boolean isDeferred(Key nodeIndex) {
        return candidates.containsKey(nodeIndex);
    }

    /**
     * Process all deferred subdivisions
     */
    public SubdivisionResult processAll(SubdivisionProcessor<Key, ID, NodeType> processor) {
        if (candidates.isEmpty()) {
            return new SubdivisionResult(0, 0, 0, 0, Collections.emptyMap());
        }

        var startTime = System.nanoTime();

        // Get candidates in processing order
        var toProcess = getProcessingOrder();

        // Clear candidates map
        candidates.clear();

        // Process subdivisions
        var nodesProcessed = 0;
        var nodesSubdivided = 0;
        var newNodesCreated = 0;
        var redistributionCounts = new HashMap<Key, Integer>();

        for (var candidate : toProcess) {
            nodesProcessed++;

            // Check if node still needs subdivision
            if (shouldSubdivide(candidate)) {
                var result = processor.subdivideNode(candidate.nodeIndex, candidate.node, candidate.level);

                if (result.wasSubdivided()) {
                    nodesSubdivided++;
                    newNodesCreated += result.getNewNodeCount();
                    redistributionCounts.put(candidate.nodeIndex, result.getEntitiesRedistributed());
                }
            }
        }

        totalProcessed.addAndGet(nodesProcessed);
        var processingTime = System.nanoTime() - startTime;

        return new SubdivisionResult(nodesProcessed, nodesSubdivided, newNodesCreated, processingTime,
                                     redistributionCounts);
    }

    /**
     * Process deferred subdivisions in batches
     */
    public List<SubdivisionResult> processBatches(SubdivisionProcessor<Key, ID, NodeType> processor, int batchSize) {
        var results = new ArrayList<SubdivisionResult>();

        while (!candidates.isEmpty()) {
            // Get next batch
            var batch = getNextBatch(batchSize);
            if (batch.isEmpty()) {
                break;
            }

            // Process batch
            var startTime = System.nanoTime();
            var nodesProcessed = 0;
            var nodesSubdivided = 0;
            var newNodesCreated = 0;
            var redistributionCounts = new HashMap<Key, Integer>();

            for (var candidate : batch) {
                nodesProcessed++;

                if (shouldSubdivide(candidate)) {
                    var result = processor.subdivideNode(candidate.nodeIndex, candidate.node, candidate.level);

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
            var processingTime = System.nanoTime() - startTime;

            results.add(new SubdivisionResult(nodesProcessed, nodesSubdivided, newNodesCreated, processingTime,
                                              redistributionCounts));
        }

        return results;
    }

    private double getAverageEntityCount() {
        if (candidates.isEmpty()) {
            return 0;
        }

        var totalEntities = candidates.values().stream().mapToInt(c -> c.entityCount).sum();

        return (double) totalEntities / candidates.size();
    }

    private List<SubdivisionCandidate<Key, NodeType>> getNextBatch(int batchSize) {
        var batch = new ArrayList<SubdivisionCandidate<Key, NodeType>>();
        var allCandidates = getProcessingOrder();

        for (var i = 0; i < Math.min(batchSize, allCandidates.size()); i++) {
            batch.add(allCandidates.get(i));
        }

        return batch;
    }

    private List<SubdivisionCandidate<Key, NodeType>> getProcessingOrder() {
        var toProcess = new ArrayList<>(candidates.values());

        if (priorityBasedProcessing) {
            // Sort by priority (highest entity count first)
            toProcess.sort((a, b) -> Integer.compare(b.getPriority(), a.getPriority()));
        }

        return toProcess;
    }

    private void processLowestPriorityIfNeeded(int newPriority) {
        // Find and process lowest priority candidate if new one has higher priority
        SubdivisionCandidate<Key, NodeType> lowest = null;
        for (var candidate : candidates.values()) {
            if (lowest == null || candidate.getPriority() < lowest.getPriority()) {
                lowest = candidate;
            }
        }

        if (lowest != null && lowest.getPriority() < newPriority) {
            candidates.remove(lowest.nodeIndex);
        }
    }

    // Private helper methods

    private boolean shouldSubdivide(SubdivisionCandidate<Key, NodeType> candidate) {
        // Could add additional checks here based on current tree state
        // For now, trust that if it was deferred, it needs subdivision
        return true;
    }

    /**
     * Interface for processing subdivisions
     */
    public interface SubdivisionProcessor<Key extends SpatialKey<Key>, ID extends EntityID, NodeType> {
        Result subdivideNode(Key nodeIndex, NodeType node, byte level);

        class Result {
            private final boolean subdivided;
            private final int     newNodeCount;
            private final int     entitiesRedistributed;

            public Result(boolean subdivided, int newNodeCount, int entitiesRedistributed) {
                this.subdivided = subdivided;
                this.newNodeCount = newNodeCount;
                this.entitiesRedistributed = entitiesRedistributed;
            }

            public int getEntitiesRedistributed() {
                return entitiesRedistributed;
            }

            public int getNewNodeCount() {
                return newNodeCount;
            }

            public boolean wasSubdivided() {
                return subdivided;
            }
        }
    }

    /**
     * Information about a node that needs subdivision
     */
    public static class SubdivisionCandidate<Key extends SpatialKey<Key>, NodeType> {
        public final Key      nodeIndex;
        public final NodeType node;
        public final int      entityCount;
        public final byte     level;
        public final long     timestamp;

        public SubdivisionCandidate(Key nodeIndex, NodeType node, int entityCount, byte level) {
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
    public static class SubdivisionResult<Key extends SpatialKey<Key>> {
        public final int               nodesProcessed;
        public final int               nodesSubdivided;
        public final int               newNodesCreated;
        public final long              processingTimeNanos;
        public final Map<Key, Integer> redistributionCounts;

        public SubdivisionResult(int nodesProcessed, int nodesSubdivided, int newNodesCreated, long processingTimeNanos,
                                 Map<Key, Integer> redistributionCounts) {
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

    public static class DeferredStats {
        public final int    currentDeferred;
        public final int    totalDeferred;
        public final int    totalProcessed;
        public final double averageEntityCount;

        public DeferredStats(int currentDeferred, int totalDeferred, int totalProcessed, double averageEntityCount) {
            this.currentDeferred = currentDeferred;
            this.totalDeferred = totalDeferred;
            this.totalProcessed = totalProcessed;
            this.averageEntityCount = averageEntityCount;
        }
    }
}
