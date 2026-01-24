package com.hellblazer.luciferase.lucien.balancing.fault.testinfra;

import com.hellblazer.luciferase.lucien.balancing.fault.DefaultPartitionRecovery;
import com.hellblazer.luciferase.lucien.balancing.fault.InMemoryPartitionTopology;
import com.hellblazer.luciferase.lucien.balancing.fault.PartitionTopology;
import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Integration test fixture for distributed forest testing.
 * <p>
 * Provides unified test harness for setting up and managing:
 * <ul>
 *   <li>Multi-partition distributed forests</li>
 *   <li>VON overlay networks</li>
 *   <li>Fault handlers with configurable behavior</li>
 *   <li>Recovery coordinator instances</li>
 *   <li>Clock management (production vs test clocks)</li>
 * </ul>
 * <p>
 * <b>Usage Pattern</b>:
 * <pre>{@code
 * var fixture = new IntegrationTestFixture();
 *
 * // Setup distributed system
 * var forest = fixture.setupForestWithPartitions(3);
 * var network = fixture.setupVONNetwork(5);
 * var handler = fixture.configureFaultHandler();
 *
 * // Inject failures for testing
 * fixture.injectPartitionFailure(partitionId, 100);
 *
 * // Use test clock for deterministic timing
 * fixture.resetClock(1000L);
 *
 * // Cleanup
 * fixture.tearDown();
 * }</pre>
 * <p>
 * <b>Thread Safety</b>: This class is thread-safe. Multiple concurrent test
 * instances are supported via concurrent collections.
 */
public class IntegrationTestFixture {

    private final Map<String, Object> resources;
    private volatile TestDistributedForest currentForest;
    private volatile TestVONNetwork currentNetwork;
    private volatile TestFaultHandler currentHandler;
    private volatile TestClock testClock;
    private volatile boolean cleanedUp;
    private final ExecutorService executorService;
    private volatile PartitionTopology partitionTopology;
    private final Map<UUID, DefaultPartitionRecovery> recoveryCoordinators;

    /**
     * Create new integration test fixture.
     */
    public IntegrationTestFixture() {
        this.resources = new ConcurrentHashMap<>();
        this.executorService = Executors.newCachedThreadPool();
        this.recoveryCoordinators = new ConcurrentHashMap<>();
        this.cleanedUp = false;
    }

    /**
     * Setup distributed forest with specified partition count.
     * <p>
     * Creates test forest with all partitions initially healthy.
     * Stores reference for cleanup and failure injection.
     *
     * @param partitionCount number of partitions (must be positive)
     * @return TestDistributedForest instance
     * @throws IllegalArgumentException if partitionCount <= 0
     */
    public TestDistributedForest setupForestWithPartitions(int partitionCount) {
        if (partitionCount <= 0) {
            throw new IllegalArgumentException("partitionCount must be positive, got: " + partitionCount);
        }

        currentForest = new TestDistributedForest(partitionCount);
        resources.put("forest", currentForest);
        return currentForest;
    }

    /**
     * Setup VON overlay network with specified node count.
     * <p>
     * Creates test network with nodes initialized but no neighbor relationships.
     * Stores reference for cleanup.
     *
     * @param nodeCount number of nodes (must be positive)
     * @return TestVONNetwork instance
     * @throws IllegalArgumentException if nodeCount <= 0
     */
    public TestVONNetwork setupVONNetwork(int nodeCount) {
        if (nodeCount <= 0) {
            throw new IllegalArgumentException("nodeCount must be positive, got: " + nodeCount);
        }

        currentNetwork = new TestVONNetwork(nodeCount);
        resources.put("network", currentNetwork);
        return currentNetwork;
    }

    /**
     * Configure fault handler for testing.
     * <p>
     * Creates TestFaultHandler with default configuration.
     * Stores reference for cleanup and failure injection.
     *
     * @return TestFaultHandler instance
     */
    public TestFaultHandler configureFaultHandler() {
        currentHandler = new TestFaultHandler();
        currentHandler.start();
        resources.put("handler", currentHandler);
        return currentHandler;
    }

    /**
     * Inject partition failure with optional delay.
     * <p>
     * Schedules partition to fail after specified delay. If delay is 0,
     * partition fails immediately. Failure is injected via fault handler
     * and reflected in forest health status.
     *
     * @param partitionId partition UUID to fail
     * @param delayMs delay before failure in milliseconds (0 for immediate)
     * @throws IllegalStateException if forest or handler not configured
     */
    public void injectPartitionFailure(UUID partitionId, int delayMs) {
        if (currentForest == null) {
            throw new IllegalStateException("Forest not configured - call setupForestWithPartitions first");
        }
        if (currentHandler == null) {
            throw new IllegalStateException("Handler not configured - call configureFaultHandler first");
        }

        if (delayMs == 0) {
            // Immediate failure
            currentHandler.injectFailure(partitionId);
            currentForest.markPartitionFailed(partitionId);
        } else {
            // Delayed failure
            executorService.submit(() -> {
                try {
                    Thread.sleep(delayMs);
                    currentHandler.injectFailure(partitionId);
                    currentForest.markPartitionFailed(partitionId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
    }

    /**
     * Reset clock to specific test time.
     * <p>
     * Creates TestClock set to specified time. Subsequent getClock() calls
     * will return this test clock instead of system clock.
     *
     * @param initialTimeMs initial clock time in milliseconds
     */
    public void resetClock(long initialTimeMs) {
        testClock = new TestClock(initialTimeMs);
        resources.put("clock", testClock);
    }

    /**
     * Get current clock (test clock if set, system clock otherwise).
     *
     * @return Clock instance
     */
    public Clock getClock() {
        if (testClock != null) {
            return testClock;
        }
        return Clock.system();
    }

    /**
     * Setup recovery coordinators for all partitions.
     * <p>
     * Creates DefaultPartitionRecovery instances for each partition in the forest.
     * Recovery coordinators share a common PartitionTopology for rank mapping.
     *
     * @throws IllegalStateException if forest not configured
     */
    public void setupRecoveryCoordinators() {
        if (currentForest == null) {
            throw new IllegalStateException("Forest not configured - call setupForestWithPartitions first");
        }

        // Create topology and register all partitions
        partitionTopology = new InMemoryPartitionTopology();
        var partitionIds = currentForest.getPartitionIds();
        var rank = 0;
        for (var partitionId : partitionIds) {
            partitionTopology.register(partitionId, rank++);
        }

        // Create recovery coordinator for each partition
        recoveryCoordinators.clear();
        for (var partitionId : partitionIds) {
            var recovery = new DefaultPartitionRecovery(partitionId, partitionTopology);
            recovery.setClock(getClock());
            recoveryCoordinators.put(partitionId, recovery);
        }

        resources.put("recoveryCoordinators", recoveryCoordinators);
    }

    /**
     * Get partition IDs from current forest.
     *
     * @return list of partition UUIDs
     * @throws IllegalStateException if forest not configured
     */
    public List<UUID> getPartitionIds() {
        if (currentForest == null) {
            throw new IllegalStateException("Forest not configured - call setupForestWithPartitions first");
        }
        return new ArrayList<>(currentForest.getPartitionIds());
    }

    /**
     * Get active (healthy) partitions from current forest.
     *
     * @return set of healthy partition UUIDs
     * @throws IllegalStateException if forest not configured
     */
    public Set<UUID> getActivePartitions() {
        if (currentForest == null) {
            throw new IllegalStateException("Forest not configured - call setupForestWithPartitions first");
        }

        return currentForest.getPartitionIds().stream()
            .filter(currentForest::isPartitionHealthy)
            .collect(Collectors.toSet());
    }

    /**
     * Get recovery coordinator for specified partition.
     *
     * @param partitionId partition UUID
     * @return DefaultPartitionRecovery instance
     * @throws IllegalStateException if recovery coordinators not setup
     * @throws IllegalArgumentException if partition not found
     */
    public DefaultPartitionRecovery getRecoveryCoordinator(UUID partitionId) {
        if (recoveryCoordinators.isEmpty()) {
            throw new IllegalStateException("Recovery coordinators not setup - call setupRecoveryCoordinators first");
        }

        var recovery = recoveryCoordinators.get(partitionId);
        if (recovery == null) {
            throw new IllegalArgumentException("No recovery coordinator for partition: " + partitionId);
        }

        return recovery;
    }

    /**
     * Reset fixture state for sequential test use.
     * <p>
     * Clears all resources but does not shutdown executor.
     * Allows fixture reuse across multiple test scenarios.
     */
    public void reset() {
        resources.clear();
        currentForest = null;
        currentNetwork = null;
        currentHandler = null;
        testClock = null;
        partitionTopology = null;
        recoveryCoordinators.clear();
        cleanedUp = false;
    }

    /**
     * Teardown and cleanup all resources.
     * <p>
     * Stops fault handler, shuts down executor, and clears all resources.
     * Safe to call multiple times (idempotent).
     */
    public void tearDown() {
        if (cleanedUp) {
            return; // Already cleaned up
        }

        try {
            // Stop fault handler if running
            if (currentHandler != null && currentHandler.isRunning()) {
                currentHandler.stop();
            }

            // Shutdown executor
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }

            // Clear resources
            resources.clear();
            currentForest = null;
            currentNetwork = null;
            currentHandler = null;
            testClock = null;
            partitionTopology = null;
            recoveryCoordinators.clear();

            cleanedUp = true;
        } catch (Exception e) {
            // Log but don't throw - cleanup should be best-effort
            System.err.println("Error during teardown: " + e.getMessage());
        }
    }

    /**
     * Check if fixture has been cleaned up.
     *
     * @return true if tearDown() has been called
     */
    public boolean isCleanedUp() {
        return cleanedUp;
    }
}
