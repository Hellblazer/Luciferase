/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.lucien.balancing;

import com.hellblazer.luciferase.lucien.balancing.fault.FaultHandler;
import com.hellblazer.luciferase.lucien.forest.ghost.DistributedGhostManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Adapter that routes ghost synchronization events to fault detection.
 *
 * <p>This adapter implements the callback interface for ghost synchronization operations
 * and routes success/failure events to the fault handler, enabling:
 * <ul>
 *   <li><b>Fault Detection</b>: Detect partition failures through ghost sync failures</li>
 *   <li><b>Recovery Coordination</b>: Mark partitions healthy when sync succeeds</li>
 *   <li><b>Rank ↔ UUID Mapping</b>: Translate between partition ranks and UUID identifiers</li>
 * </ul>
 *
 * <p><b>Integration Points</b>:
 * <ul>
 *   <li>Registered as callback in {@link DistributedGhostManager}</li>
 *   <li>Reports sync failures via {@link FaultHandler#reportSyncFailure(UUID)}</li>
 *   <li>Reports sync success via {@link FaultHandler#markHealthy(UUID)}</li>
 * </ul>
 *
 * <p>Thread-safe: Uses ConcurrentHashMap for rank-to-partition mappings.
 *
 * @author hal.hildebrand
 */
public class GhostSyncFaultAdapter {

    private static final Logger log = LoggerFactory.getLogger(GhostSyncFaultAdapter.class);

    private final FaultHandler faultHandler;
    private final Map<Integer, UUID> rankToPartition;

    /**
     * Create a new ghost sync fault adapter.
     *
     * @param faultHandler the fault handler to route sync failures to
     * @throws NullPointerException if faultHandler is null
     */
    public GhostSyncFaultAdapter(FaultHandler faultHandler) {
        this.faultHandler = Objects.requireNonNull(faultHandler, "faultHandler cannot be null");
        this.rankToPartition = new ConcurrentHashMap<>();

        log.debug("Created GhostSyncFaultAdapter");
    }

    /**
     * Register this adapter as a callback in the distributed ghost manager.
     *
     * @param ghostManager the ghost manager to register with
     * @throws NullPointerException if ghostManager is null
     */
    public void registerWithGhostManager(DistributedGhostManager<?, ?, ?> ghostManager) {
        Objects.requireNonNull(ghostManager, "ghostManager cannot be null");
        ghostManager.registerSyncCallback(this);
        log.info("Registered GhostSyncFaultAdapter with ghost manager");
    }

    /**
     * Map a partition rank to its UUID identifier.
     *
     * <p>Called during system initialization to establish the rank-to-UUID mapping
     * used for translating sync callbacks to fault handler calls.
     *
     * @param rank the partition rank (0-based)
     * @param partitionId the UUID of the partition
     */
    public void mapRankToPartition(int rank, UUID partitionId) {
        Objects.requireNonNull(partitionId, "partitionId cannot be null");
        rankToPartition.put(rank, partitionId);
        log.debug("Mapped rank {} to partition {}", rank, partitionId);
    }

    /**
     * Get the partition UUID for a given rank.
     *
     * @param rank the partition rank
     * @return the partition UUID, or null if no mapping exists
     */
    public UUID getPartitionIdForRank(int rank) {
        return rankToPartition.get(rank);
    }

    /**
     * Callback invoked when ghost synchronization with a partition succeeds.
     *
     * <p>Routes success event to fault handler to mark partition as healthy.
     *
     * @param rank the rank of the partition that synced successfully
     */
    public void onSyncSuccess(int rank) {
        var partitionId = rankToPartition.get(rank);
        if (partitionId != null) {
            log.debug("Ghost sync success for rank {} (partition {})", rank, partitionId);
            faultHandler.markHealthy(partitionId);
        } else {
            log.warn("Ghost sync success for unknown rank: {}", rank);
        }
    }

    /**
     * Callback invoked when ghost synchronization with a partition fails.
     *
     * <p>Routes failure event to fault handler to report sync failure.
     *
     * @param rank the rank of the partition where sync failed
     * @param cause the exception causing the sync failure
     */
    public void onSyncFailure(int rank, Exception cause) {
        var partitionId = rankToPartition.get(rank);
        if (partitionId != null) {
            log.warn("Ghost sync failure for rank {} (partition {}): {}", rank, partitionId, cause.getMessage());
            faultHandler.reportSyncFailure(partitionId);
        } else {
            log.warn("Ghost sync failure for unknown rank {}: {}", rank, cause.getMessage());
        }
    }
}
