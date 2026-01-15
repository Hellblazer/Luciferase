/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.lucien.forest.ghost;

import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.forest.ghost.grpc.GhostCommunicationManager;
import com.hellblazer.luciferase.lucien.forest.ghost.proto.GhostBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * gRPC-based ghost channel adapter for distributed ghost communication.
 *
 * <p>This class wraps {@link GhostCommunicationManager} to provide a simplified
 * batched transmission interface for ghost elements across distributed processes.
 * It handles queuing, batching, and asynchronous transmission of ghost elements
 * to remote processes using gRPC.
 *
 * <p><strong>Key Features:</strong>
 * <ul>
 *   <li>Batched transmission to reduce network overhead</li>
 *   <li>Asynchronous communication with CompletableFuture support</li>
 *   <li>Automatic rank-based routing through service discovery</li>
 *   <li>Per-target batching and queue management</li>
 * </ul>
 *
 * <p><strong>Usage Pattern:</strong>
 * <pre>
 * var channel = new GrpcGhostChannel&lt;&gt;(communicationManager, currentRank, treeId);
 *
 * // Queue ghosts for transmission
 * channel.queueGhost(targetRank, ghostElement);
 *
 * // Flush at appropriate intervals
 * channel.flush();
 * </pre>
 *
 * @param <Key> the type of spatial key
 * @param <ID> the type of entity identifier
 * @param <Content> the type of content stored in entities
 *
 * @author Hal Hildebrand
 */
public class GrpcGhostChannel<Key extends SpatialKey<Key>, ID extends EntityID, Content> {

    private static final Logger log = LoggerFactory.getLogger(GrpcGhostChannel.class);

    private final GhostCommunicationManager<Key, ID, Content> communicationManager;
    private final int currentRank;
    private final long treeId;
    private final GhostType ghostType;

    // Batching infrastructure
    private final Map<Integer, List<GhostElement<Key, ID, Content>>> queuedGhosts;
    private final int batchSize;

    /**
     * Create a gRPC ghost channel.
     *
     * @param communicationManager the underlying gRPC communication manager
     * @param currentRank the rank of this process
     * @param treeId the tree identifier
     * @param ghostType the type of ghosts to transmit
     */
    public GrpcGhostChannel(GhostCommunicationManager<Key, ID, Content> communicationManager,
                           int currentRank,
                           long treeId,
                           GhostType ghostType) {
        this(communicationManager, currentRank, treeId, ghostType, 100);
    }

    /**
     * Create a gRPC ghost channel with custom batch size.
     *
     * @param communicationManager the underlying gRPC communication manager
     * @param currentRank the rank of this process
     * @param treeId the tree identifier
     * @param ghostType the type of ghosts to transmit
     * @param batchSize the maximum number of ghosts to batch per transmission
     */
    public GrpcGhostChannel(GhostCommunicationManager<Key, ID, Content> communicationManager,
                           int currentRank,
                           long treeId,
                           GhostType ghostType,
                           int batchSize) {
        this.communicationManager = Objects.requireNonNull(communicationManager);
        this.currentRank = currentRank;
        this.treeId = treeId;
        this.ghostType = Objects.requireNonNull(ghostType);
        this.batchSize = batchSize;
        this.queuedGhosts = new ConcurrentHashMap<>();

        log.debug("Created GrpcGhostChannel for rank {} tree {} with batch size {}",
                 currentRank, treeId, batchSize);
    }

    /**
     * Queue a ghost element for batched transmission to a target process.
     *
     * @param targetRank the rank of the target process
     * @param ghostElement the ghost element to send
     */
    public void queueGhost(int targetRank, GhostElement<Key, ID, Content> ghostElement) {
        if (targetRank == currentRank) {
            log.debug("Skipping ghost queue for self (rank {})", currentRank);
            return;
        }

        queuedGhosts.computeIfAbsent(targetRank, k -> new ArrayList<>()).add(ghostElement);

        // Auto-flush if batch size reached
        var queue = queuedGhosts.get(targetRank);
        if (queue.size() >= batchSize) {
            flushToTarget(targetRank);
        }
    }

    /**
     * Send a batch of ghost elements immediately to a target process.
     * This bypasses the queue and sends directly.
     *
     * @param targetRank the rank of the target process
     * @param ghosts the ghost elements to send
     * @return CompletableFuture that completes when transmission finishes
     */
    public CompletableFuture<Void> sendBatch(int targetRank, List<GhostElement<Key, ID, Content>> ghosts) {
        if (targetRank == currentRank || ghosts.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        log.debug("Sending batch of {} ghosts to rank {}", ghosts.size(), targetRank);

        // Convert ghost elements to keys for the request
        var boundaryKeys = ghosts.stream()
                                 .map(GhostElement::getSpatialKey)
                                 .toList();

        return communicationManager.requestGhostsAsync(targetRank, treeId, ghostType, boundaryKeys)
            .thenAccept(response -> {
                if (response != null) {
                    log.debug("Successfully sent batch of {} ghosts to rank {}",
                             ghosts.size(), targetRank);
                } else {
                    log.warn("Failed to send batch to rank {} - null response", targetRank);
                }
            })
            .exceptionally(e -> {
                log.error("Error sending batch to rank {}: {}", targetRank, e.getMessage());
                return null;
            });
    }

    /**
     * Flush all queued ghosts to their target processes.
     * This sends all pending batches and clears the queues.
     *
     * @return CompletableFuture that completes when all flushes finish
     */
    public CompletableFuture<Void> flush() {
        if (queuedGhosts.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        log.debug("Flushing queued ghosts to {} targets", queuedGhosts.size());

        var futures = new ArrayList<CompletableFuture<Void>>();

        for (var targetRank : new ArrayList<>(queuedGhosts.keySet())) {
            var future = flushToTarget(targetRank);
            futures.add(future);
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    /**
     * Flush queued ghosts to a specific target process.
     *
     * @param targetRank the rank of the target process
     * @return CompletableFuture that completes when flush finishes
     */
    public CompletableFuture<Void> flushToTarget(int targetRank) {
        var queue = queuedGhosts.remove(targetRank);
        if (queue == null || queue.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        return sendBatch(targetRank, queue);
    }

    /**
     * Get the number of queued ghosts for a target process.
     *
     * @param targetRank the rank of the target process
     * @return the number of queued ghosts
     */
    public int getPendingCount(int targetRank) {
        var queue = queuedGhosts.get(targetRank);
        return queue != null ? queue.size() : 0;
    }

    /**
     * Get the total number of queued ghosts across all targets.
     *
     * @return the total number of queued ghosts
     */
    public int getTotalPendingCount() {
        return queuedGhosts.values().stream()
                          .mapToInt(List::size)
                          .sum();
    }

    /**
     * Get all target ranks with pending ghosts.
     *
     * @return set of target ranks with queued ghosts
     */
    public Set<Integer> getTargetsWithPending() {
        return new HashSet<>(queuedGhosts.keySet());
    }

    /**
     * Clear all queued ghosts without sending them.
     */
    public void clear() {
        queuedGhosts.clear();
        log.debug("Cleared all queued ghosts");
    }

    /**
     * Get the current rank.
     *
     * @return the current process rank
     */
    public int getCurrentRank() {
        return currentRank;
    }

    /**
     * Get the tree ID.
     *
     * @return the tree identifier
     */
    public long getTreeId() {
        return treeId;
    }

    /**
     * Get the ghost type.
     *
     * @return the ghost type
     */
    public GhostType getGhostType() {
        return ghostType;
    }
}
