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

import com.hellblazer.luciferase.simulation.von.VonTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Centralized topology authority for distributed bubble coordination.
 * <p>
 * Responsibilities:
 * - Process registration and lifecycle management
 * - Heartbeat monitoring for failure detection
 * - Coordinator election protocol integration
 * - Topology update broadcasting
 * <p>
 * Lifecycle:
 * 1. Create ProcessCoordinator(transport)
 * 2. start() to begin listening and election
 * 3. Processes register via registerProcess(id, bubbles)
 * 4. Heartbeat monitoring detects failures
 * 5. stop() to shut down gracefully
 * <p>
 * Bucket Synchronization:
 * - BUCKET_DURATION_MS: 100ms per simulation tick
 * - TOLERANCE_MS: 50ms clock skew tolerance
 * <p>
 * Heartbeat Protocol:
 * - Interval: 1000ms (every second)
 * - Timeout: 3000ms (3 missed heartbeats)
 * <p>
 * Architecture Decision D6B.5: Coordinator Election
 *
 * @author hal.hildebrand
 */
public class ProcessCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ProcessCoordinator.class);

    public static final long BUCKET_DURATION_MS = 100;
    public static final long TOLERANCE_MS = 50;

    private final VonTransport transport;
    private final ProcessRegistry registry;
    private final CoordinatorElectionProtocol election;
    private final ScheduledExecutorService heartbeatScheduler;

    private volatile boolean running = false;

    /**
     * Create a ProcessCoordinator with the given transport.
     *
     * @param transport VonTransport for inter-process communication
     */
    public ProcessCoordinator(VonTransport transport) {
        this.transport = transport;
        this.registry = new ProcessRegistry();
        this.election = new CoordinatorElectionProtocol();
        this.heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "coordinator-heartbeat-monitor");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Start the coordinator: begin listening and election protocol.
     * <p>
     * Initiates:
     * - Heartbeat monitoring (every 1000ms)
     * - Failure detection (timeout 3000ms)
     * - Election if this is the first process
     */
    public void start() throws Exception {
        if (running) {
            throw new IllegalStateException("ProcessCoordinator already running");
        }

        running = true;

        // Start heartbeat monitoring
        heartbeatScheduler.scheduleAtFixedRate(
            this::monitorHeartbeats,
            ProcessRegistry.HEARTBEAT_INTERVAL_MS,
            ProcessRegistry.HEARTBEAT_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );

        log.info("ProcessCoordinator started on {}", transport.getLocalId());
    }

    /**
     * Stop the coordinator gracefully.
     */
    public void stop() {
        if (!running) {
            return;
        }

        running = false;
        heartbeatScheduler.shutdown();
        log.info("ProcessCoordinator stopped");
    }

    /**
     * Register a process with its bubbles.
     *
     * @param processId UUID of the process
     * @param bubbles   List of bubble UUIDs hosted by this process
     */
    public void registerProcess(UUID processId, List<UUID> bubbles) throws Exception {
        registry.register(processId, bubbles);
        log.info("Registered process {} with {} bubbles", processId, bubbles.size());

        // Trigger election if needed
        conductElection();
    }

    /**
     * Unregister a process (called on process failure or shutdown).
     *
     * @param processId UUID of the process to remove
     */
    public void unregisterProcess(UUID processId) throws Exception {
        registry.unregister(processId);
        log.info("Unregistered process {}", processId);

        // Check if coordinator failed
        if (election.isElected(processId)) {
            election.coordinatorFailed();
            conductElection();
        }
    }

    /**
     * Process a heartbeat acknowledgment from a process.
     * <p>
     * Updates the last heartbeat timestamp for failure detection.
     *
     * @param processId UUID of the process sending heartbeat
     */
    public void processHeartbeatAck(UUID processId) {
        registry.updateHeartbeat(processId);
        log.trace("Heartbeat ACK from {}", processId);
    }

    /**
     * Broadcast topology update to all registered processes.
     * <p>
     * Phase 6B1: API exists but messaging is minimal.
     * Phase 6B2: Will use TopologyUpdateMessage for distribution.
     *
     * @param topology List of all bubble UUIDs in the topology
     */
    public void broadcastTopologyUpdate(List<UUID> topology) throws Exception {
        log.debug("Broadcasting topology update: {} bubbles", topology.size());
        // Phase 6B2: Implement message broadcasting
    }

    /**
     * Conduct coordinator election.
     * <p>
     * Runs election protocol with all registered processes.
     * Lowest UUID wins (deterministic).
     */
    public void conductElection() {
        var processes = registry.getAllProcesses();
        if (processes.isEmpty()) {
            log.debug("No processes registered, skipping election");
            return;
        }

        election.startElection(processes);
        var winner = election.getWinner();
        log.info("Coordinator elected: {}", winner);
    }

    /**
     * Check if this process is the elected coordinator.
     *
     * @return true if this process won the election
     */
    public boolean isCoordinator() {
        return election.isElected(transport.getLocalId());
    }

    /**
     * Monitor heartbeats and detect failed processes.
     * <p>
     * Called periodically by heartbeatScheduler.
     * Unregisters processes that missed heartbeat timeout.
     */
    private void monitorHeartbeats() {
        var processes = registry.getAllProcesses();
        for (var processId : processes) {
            if (!registry.isAlive(processId)) {
                log.warn("Process {} missed heartbeat timeout, unregistering", processId);
                try {
                    unregisterProcess(processId);
                } catch (Exception e) {
                    log.error("Failed to unregister process {}: {}", processId, e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Get the process registry.
     *
     * @return ProcessRegistry instance
     */
    public ProcessRegistry getRegistry() {
        return registry;
    }

    /**
     * Check if coordinator is running.
     *
     * @return true if started and not stopped
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Get the election protocol instance.
     *
     * @return CoordinatorElectionProtocol
     */
    public CoordinatorElectionProtocol getElection() {
        return election;
    }
}
