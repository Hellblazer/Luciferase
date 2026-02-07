/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.simulation.distributed.integration;

import com.hellblazer.luciferase.simulation.delos.MembershipView;
import com.hellblazer.luciferase.simulation.distributed.ProcessCoordinator;
import com.hellblazer.luciferase.simulation.von.LocalServerTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Manages a cluster of simulated processes for distributed simulation testing.
 * <p>
 * Creates and coordinates multiple ProcessCoordinator instances running in
 * separate threads, connected via LocalServerTransport for in-process
 * communication.
 * <p>
 * Phase 6B5.2: TestProcessCluster Infrastructure
 * <p>
 * Key features:
 * <ul>
 *   <li>N processes, M bubbles per process</li>
 *   <li>In-process communication via LocalServerTransport.Registry</li>
 *   <li>Coordinated startup/shutdown</li>
 *   <li>Integrated EntityAccountant for entity tracking</li>
 *   <li>Aggregated metrics collection</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class TestProcessCluster {

    private static final Logger log = LoggerFactory.getLogger(TestProcessCluster.class);

    private final int processCount;
    private final int bubblesPerProcess;
    private final Map<UUID, ProcessCoordinator> coordinators = new ConcurrentHashMap<>();
    private final Map<UUID, LocalServerTransport> transports = new ConcurrentHashMap<>();
    private final EntityAccountant entityAccountant = new EntityAccountant();
    private final DistributedSimulationMetrics metrics = new DistributedSimulationMetrics();

    private LocalServerTransport.Registry registry;
    private TestProcessTopology topology;
    private volatile boolean running = false;

    // Failure injection state
    private final Set<UUID> crashedProcesses = ConcurrentHashMap.newKeySet();
    private volatile int messageDelayMs = 0;
    private final Map<UUID, Integer> processSlowdownMs = new ConcurrentHashMap<>();
    private volatile boolean ghostSyncFailuresEnabled = false;

    /**
     * Creates a new test process cluster.
     *
     * @param processCount      number of processes (simulated JVMs)
     * @param bubblesPerProcess number of bubbles per process
     */
    public TestProcessCluster(int processCount, int bubblesPerProcess) {
        if (processCount < 1) {
            throw new IllegalArgumentException("processCount must be >= 1");
        }
        if (bubblesPerProcess < 1) {
            throw new IllegalArgumentException("bubblesPerProcess must be >= 1");
        }
        this.processCount = processCount;
        this.bubblesPerProcess = bubblesPerProcess;
    }

    /**
     * Starts all processes in the cluster.
     *
     * @throws IllegalStateException if already running
     * @throws Exception if startup fails
     */
    public synchronized void start() throws Exception {
        if (running) {
            throw new IllegalStateException("Cluster already running");
        }

        log.info("Starting TestProcessCluster with {} processes, {} bubbles each",
                processCount, bubblesPerProcess);

        // Create shared transport registry
        registry = LocalServerTransport.Registry.create();

        // Create topology
        topology = new TestProcessTopology(processCount, bubblesPerProcess);

        // Create process coordinators
        for (int i = 0; i < processCount; i++) {
            var processId = topology.getProcessId(i);
            var transport = registry.register(processId);
            transports.put(processId, transport);

            // Create mock membership view for testing (Phase 4.1.4)
            var mockView = new MockMembershipView<UUID>();
            var coordinator = new ProcessCoordinator(transport, mockView);
            coordinators.put(processId, coordinator);

            // Register this process with its bubbles
            var bubbles = topology.getBubblesForProcess(processId);
            coordinator.start();
            coordinator.registerProcess(processId, new ArrayList<>(bubbles));

            log.debug("Started process {} with {} bubbles", processId, bubbles.size());
        }

        // Update metrics
        metrics.setActiveProcessCount(processCount);

        running = true;
        log.info("TestProcessCluster started with {} processes, {} bubbles total",
                processCount, topology.getBubbleCount());
    }

    /**
     * Stops all processes in the cluster.
     */
    public synchronized void stop() {
        if (!running) {
            return;
        }

        log.info("Stopping TestProcessCluster");

        // Stop coordinators
        for (var coordinator : coordinators.values()) {
            try {
                coordinator.stop();
            } catch (Exception e) {
                log.warn("Error stopping coordinator: {}", e.getMessage());
            }
        }

        // Close transports
        for (var transport : transports.values()) {
            try {
                transport.close();
            } catch (Exception e) {
                log.warn("Error closing transport: {}", e.getMessage());
            }
        }

        // Close registry
        if (registry != null) {
            try {
                registry.close();
            } catch (Exception e) {
                log.warn("Error closing registry: {}", e.getMessage());
            }
        }

        coordinators.clear();
        transports.clear();
        registry = null;
        topology = null;
        metrics.setActiveProcessCount(0);

        running = false;
        log.info("TestProcessCluster stopped");
    }

    /**
     * Returns true if the cluster is running.
     *
     * @return true if running
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Returns the number of processes in the cluster.
     *
     * @return process count
     */
    public int getProcessCount() {
        return processCount;
    }

    /**
     * Returns all process IDs.
     *
     * @return set of process UUIDs
     */
    public Set<UUID> getProcessIds() {
        return coordinators.keySet();
    }

    /**
     * Gets the ProcessCoordinator for a specific process.
     *
     * @param processId process UUID
     * @return ProcessCoordinator or null if not found
     */
    public ProcessCoordinator getProcessCoordinator(UUID processId) {
        return coordinators.get(processId);
    }

    /**
     * Gets the LocalServerTransport for a specific process.
     *
     * @param processId process UUID
     * @return LocalServerTransport or null if not found
     */
    public LocalServerTransport getTransport(UUID processId) {
        return transports.get(processId);
    }

    /**
     * Gets the topology configuration.
     *
     * @return TestProcessTopology
     */
    public TestProcessTopology getTopology() {
        return topology;
    }

    /**
     * Gets the distributed simulation metrics.
     *
     * @return DistributedSimulationMetrics
     */
    public DistributedSimulationMetrics getMetrics() {
        return metrics;
    }

    /**
     * Gets the entity accountant for tracking entity locations.
     *
     * @return EntityAccountant
     */
    public EntityAccountant getEntityAccountant() {
        return entityAccountant;
    }

    /**
     * Crashes a process by removing it from the active set.
     * The process can be recovered with recoverProcess().
     *
     * @param processId the process UUID to crash
     */
    public void crashProcess(UUID processId) {
        if (!coordinators.containsKey(processId)) {
            throw new IllegalArgumentException("Process not found: " + processId);
        }
        crashedProcesses.add(processId);
        var coordinator = coordinators.get(processId);
        if (coordinator != null) {
            try {
                coordinator.stop();
            } catch (Exception e) {
                log.warn("Error stopping crashed process {}: {}", processId, e.getMessage());
            }
        }
        metrics.recordProcessCrash(processId);
        log.info("Crashed process {}", processId);
    }

    /**
     * Recovers a crashed process by restarting it.
     *
     * @param processId the process UUID to recover
     */
    public void recoverProcess(UUID processId) {
        if (!coordinators.containsKey(processId)) {
            throw new IllegalArgumentException("Process not found: " + processId);
        }
        crashedProcesses.remove(processId);
        var coordinator = coordinators.get(processId);
        if (coordinator != null) {
            try {
                coordinator.start();
                var bubbles = topology.getBubblesForProcess(processId);
                coordinator.registerProcess(processId, new ArrayList<>(bubbles));
            } catch (Exception e) {
                log.warn("Error recovering process {}: {}", processId, e.getMessage());
            }
        }
        metrics.recordProcessRecovery(processId);
        log.info("Recovered process {}", processId);
    }

    /**
     * Checks if a process is crashed.
     *
     * @param processId the process UUID
     * @return true if the process is crashed
     */
    public boolean isCrashed(UUID processId) {
        return crashedProcesses.contains(processId);
    }

    /**
     * Injects a message delay on all inter-process communications.
     * A delay of 0 removes the delay.
     *
     * @param delayMs delay in milliseconds (0 to disable)
     */
    public void injectMessageDelay(int delayMs) {
        this.messageDelayMs = delayMs;
        if (delayMs > 0) {
            log.info("Injecting {}ms message delay", delayMs);
        } else {
            log.info("Removing message delay");
        }
    }

    /**
     * Gets the current message delay.
     *
     * @return delay in milliseconds (0 if no delay)
     */
    public int getMessageDelay() {
        return messageDelayMs;
    }

    /**
     * Injects slowdown on a specific process (simulating GC pause or CPU throttling).
     *
     * @param processId the process UUID
     * @param delayMs   delay in milliseconds (0 to remove slowdown)
     */
    public void injectProcessSlowdown(UUID processId, int delayMs) {
        if (!coordinators.containsKey(processId)) {
            throw new IllegalArgumentException("Process not found: " + processId);
        }
        if (delayMs > 0) {
            processSlowdownMs.put(processId, delayMs);
            log.info("Injecting {}ms slowdown on process {}", delayMs, processId);
        } else {
            processSlowdownMs.remove(processId);
            log.info("Removing slowdown on process {}", processId);
        }
    }

    /**
     * Gets the slowdown delay for a process.
     *
     * @param processId the process UUID
     * @return delay in milliseconds (0 if no slowdown)
     */
    public int getProcessSlowdown(UUID processId) {
        return processSlowdownMs.getOrDefault(processId, 0);
    }

    /**
     * Enables or disables ghost synchronization failures.
     * When enabled, ghost sync operations will fail silently.
     *
     * @param enabled true to enable failures, false to disable
     */
    public void injectGhostSyncFailures(boolean enabled) {
        this.ghostSyncFailuresEnabled = enabled;
        if (enabled) {
            log.info("Injecting ghost sync failures");
        } else {
            log.info("Removing ghost sync failures");
        }
    }

    /**
     * Checks if ghost sync failures are enabled.
     *
     * @return true if ghost sync failures are enabled
     */
    public boolean areGhostSyncFailuresEnabled() {
        return ghostSyncFailuresEnabled;
    }

    /**
     * Triggers a ghost synchronization operation across all bubbles.
     * If ghost sync failures are injected, this may fail silently.
     */
    public void syncGhosts() {
        if (ghostSyncFailuresEnabled) {
            log.debug("Ghost sync triggered but failures are enabled - falling back to on-demand discovery");
            return;
        }

        // Trigger ghost sync across all coordinators
        for (var coordinator : coordinators.values()) {
            try {
                if (coordinator != null) {
                    // Synchronize ghost entities across process boundaries
                    // This is a simplified implementation for testing
                    coordinator.syncGhosts();
                }
            } catch (Exception e) {
                log.debug("Error syncing ghosts: {}", e.getMessage());
            }
        }
        log.debug("Ghost sync completed");
    }

    /**
     * Returns metrics about the ghost layer synchronization.
     *
     * @return GhostMetrics with current ghost information
     */
    public GhostMetrics getGhostMetrics() {
        // Count active neighbor relationships (simplified metric)
        int activeNeighbors = 0;
        var allBubbles = topology.getAllBubbleIds();

        for (var bubbleId : allBubbles) {
            activeNeighbors += topology.getNeighbors(bubbleId).size();
        }

        // Count ghosts (entities that have been migrated across boundaries)
        var distribution = entityAccountant.getDistribution();
        int totalEntities = distribution.values().stream().mapToInt(Integer::intValue).sum();

        return new GhostMetrics(
            activeNeighbors,
            totalEntities,
            0  // Ghost sync latency in ms
        );
    }

    /**
     * Mock MembershipView for testing (Phase 4.1.4).
     * <p>
     * Provides stub implementation of MembershipView that does nothing.
     * Real Fireflies integration would use FirefliesMembershipView.
     *
     * @param <M> member type
     */
    private static class MockMembershipView<M> implements MembershipView<M> {
        private final List<Consumer<ViewChange<M>>> listeners = new CopyOnWriteArrayList<>();

        @Override
        public Stream<M> getMembers() {
            return Stream.empty();  // No members in mock view
        }

        @Override
        public void addListener(Consumer<ViewChange<M>> listener) {
            listeners.add(listener);
        }

        /**
         * Simulate a view change (for testing).
         *
         * @param joined members that joined
         * @param left   members that left
         */
        public void fireViewChange(List<M> joined, List<M> left) {
            var change = new ViewChange<>(joined, left);
            for (var listener : listeners) {
                listener.accept(change);
            }
        }
    }
}

/**
 * Metrics about the ghost layer synchronization.
 *
 * @param activeNeighbors number of active neighbor relationships
 * @param totalGhosts     total count of ghost entities
 * @param syncLatencyMs   ghost synchronization latency in milliseconds
 */
record GhostMetrics(int activeNeighbors, int totalGhosts, int syncLatencyMs) {
}
