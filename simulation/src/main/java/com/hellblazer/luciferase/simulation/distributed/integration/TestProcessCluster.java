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

import com.hellblazer.luciferase.simulation.distributed.ProcessCoordinator;
import com.hellblazer.luciferase.simulation.von.LocalServerTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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

            var coordinator = new ProcessCoordinator(transport);
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
}
