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

package com.hellblazer.luciferase.simulation.distributed;

import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory registry of processes and bubbles.
 * <p>
 * Manages process metadata including:
 * - Process UUIDs
 * - Bubble assignments per process
 * - Heartbeat timestamps
 * - Readiness state
 * <p>
 * Thread Safety:
 * - Uses ConcurrentHashMap for thread-safe concurrent access
 * - ProcessMetadata is immutable (record type)
 * - Defensive copying for returned collections
 * <p>
 * Heartbeat Timeout:
 * - HEARTBEAT_INTERVAL_MS: 1000ms (how often processes send heartbeats)
 * - HEARTBEAT_TIMEOUT_MS: 3000ms (max time before declaring process dead)
 *
 * @author hal.hildebrand
 */
public class ProcessRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProcessRegistry.class);

    public static final long HEARTBEAT_INTERVAL_MS = 1000;
    public static final long HEARTBEAT_TIMEOUT_MS = 3000;

    private final Map<UUID, ProcessMetadata> processes = new ConcurrentHashMap<>();
    private volatile Clock clock = Clock.system();

    /**
     * Set the clock for deterministic testing.
     *
     * @param clock Clock instance to use
     */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Register a new process or update an existing one.
     *
     * @param processId UUID of the process
     * @param bubbles   List of bubble UUIDs hosted by this process
     */
    public void register(UUID processId, List<UUID> bubbles) {
        var metadata = ProcessMetadata.create(processId, bubbles);
        processes.put(processId, metadata);
        log.debug("Registered process {} with {} bubbles", processId, bubbles.size());
    }

    /**
     * Unregister a process from the registry.
     *
     * @param processId UUID of the process to remove
     */
    public void unregister(UUID processId) {
        var removed = processes.remove(processId);
        if (removed != null) {
            log.debug("Unregistered process {}", processId);
        }
    }

    /**
     * Get metadata for a specific process.
     *
     * @param processId UUID of the process
     * @return ProcessMetadata or null if not found
     */
    public ProcessMetadata getProcess(UUID processId) {
        return processes.get(processId);
    }

    /**
     * Get all registered bubbles across all processes.
     *
     * @return Immutable list of all bubble UUIDs
     */
    public List<UUID> getAllBubbles() {
        var allBubbles = new ArrayList<UUID>();
        for (var metadata : processes.values()) {
            allBubbles.addAll(metadata.bubbles());
        }
        return Collections.unmodifiableList(allBubbles);
    }

    /**
     * Get all registered process UUIDs.
     *
     * @return Immutable list of all process UUIDs
     */
    public List<UUID> getAllProcesses() {
        return Collections.unmodifiableList(new ArrayList<>(processes.keySet()));
    }

    /**
     * Update heartbeat timestamp for a process.
     *
     * @param processId UUID of the process
     * @return true if heartbeat was updated, false if process not found
     */
    public boolean updateHeartbeat(UUID processId) {
        var updated = new java.util.concurrent.atomic.AtomicBoolean(false);
        processes.computeIfPresent(processId, (id, metadata) -> {
            log.trace("Updated heartbeat for process {}", processId);
            updated.set(true);
            return metadata.withUpdatedHeartbeat();
        });
        return updated.get();
    }

    /**
     * Check if a process is alive based on heartbeat timestamp.
     * <p>
     * A process is considered alive if its last heartbeat was within
     * HEARTBEAT_TIMEOUT_MS milliseconds.
     *
     * @param processId UUID of the process
     * @return true if process heartbeat is fresh (<3000ms old)
     */
    public boolean isAlive(UUID processId) {
        var metadata = processes.get(processId);
        if (metadata == null) {
            return false;
        }

        var elapsed = clock.currentTimeMillis() - metadata.lastHeartbeat();
        return elapsed < HEARTBEAT_TIMEOUT_MS;
    }

    /**
     * Find which process hosts a specific bubble.
     *
     * @param bubbleId UUID of the bubble to find
     * @return Process UUID if found, null otherwise
     */
    public UUID findProcess(UUID bubbleId) {
        for (var entry : processes.entrySet()) {
            if (entry.getValue().bubbles().contains(bubbleId)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * Get the number of registered processes.
     *
     * @return Count of active processes
     */
    public int size() {
        return processes.size();
    }
}
