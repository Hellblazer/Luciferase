/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.lucien.balancing.fault;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Adapter that converts rank-based ghost sync events to UUID-based FaultHandler calls.
 *
 * <p>Implements GhostSyncCallback and routes notifications to a FaultHandler by
 * mapping partition ranks to UUID identifiers. Enables ghost layer failure
 * detection through the fault handling subsystem.
 *
 * <p><b>Thread-Safe</b>: Uses ConcurrentHashMap for thread-safe rank-to-UUID mapping.
 */
public class GhostSyncFaultAdapter implements GhostSyncCallback {

    private static final Logger log = LoggerFactory.getLogger(GhostSyncFaultAdapter.class);

    private final FaultHandler faultHandler;
    private final Map<Integer, UUID> rankToPartitionMap = new ConcurrentHashMap<>();

    /**
     * Create adapter with given fault handler.
     *
     * @param faultHandler handler to receive fault notifications
     * @throws NullPointerException if faultHandler is null
     */
    public GhostSyncFaultAdapter(FaultHandler faultHandler) {
        this.faultHandler = Objects.requireNonNull(faultHandler, "faultHandler must not be null");
    }

    /**
     * Register mapping from partition rank to UUID.
     *
     * <p>Ranks are used by the ghost layer; UUIDs are used by the fault handler.
     *
     * @param rank partition rank (from ghost layer)
     * @param partitionId UUID of the partition
     * @throws NullPointerException if partitionId is null
     */
    public void registerPartitionRank(int rank, UUID partitionId) {
        Objects.requireNonNull(partitionId, "partitionId must not be null");
        rankToPartitionMap.put(rank, partitionId);
        log.debug("Registered rank {} -> partition {}", rank, partitionId);
    }

    /**
     * Unregister mapping for a partition rank.
     *
     * @param rank partition rank to unregister
     */
    public void unregisterPartitionRank(int rank) {
        rankToPartitionMap.remove(rank);
        log.debug("Unregistered rank {}", rank);
    }

    @Override
    public void onSyncSuccess(int targetRank) {
        var partitionId = rankToPartitionMap.get(targetRank);
        if (partitionId == null) {
            log.trace("Sync success for unknown rank {}, ignoring", targetRank);
            return;
        }

        // Mark partition as healthy
        faultHandler.markHealthy(partitionId);
        log.debug("Partition {} ghost sync succeeded", partitionId);
    }

    @Override
    public void onSyncFailure(int targetRank, Exception cause) {
        Objects.requireNonNull(cause, "cause must not be null");

        var partitionId = rankToPartitionMap.get(targetRank);
        if (partitionId == null) {
            log.trace("Sync failure for unknown rank {}, ignoring", targetRank);
            return;
        }

        // Report failure to fault handler
        faultHandler.reportSyncFailure(partitionId);
        log.warn("Partition {} ghost sync failed: {}", partitionId, cause.getMessage());
    }
}
