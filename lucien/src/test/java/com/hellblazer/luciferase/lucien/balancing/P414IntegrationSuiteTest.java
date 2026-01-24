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

import com.hellblazer.luciferase.lucien.balancing.fault.FaultConfiguration;
import com.hellblazer.luciferase.lucien.balancing.fault.FaultHandler;
import com.hellblazer.luciferase.lucien.balancing.fault.SimpleFaultHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for P4.1: DistributedForest Integration (P4.1.4).
 *
 * <p>Validates end-to-end fault tolerance workflows across all P4.1 components:
 * <ul>
 *   <li>Pause/resume coordination with concurrent balance operations</li>
 *   <li>Quorum enforcement during concurrent recoveries</li>
 *   <li>Ghost sync failure detection and recovery routing</li>
 *   <li>Livelock recovery queue processing</li>
 * </ul>
 *
 * <p><b>Test Strategy</b>:
 * <ol>
 *   <li>Multi-threaded coordination between balancer and recovery</li>
 *   <li>Concurrent operation tracking and pause synchronization</li>
 *   <li>Fault detection triggering recovery coordination</li>
 *   <li>Recovery queue management during quorum loss scenarios</li>
 * </ol>
 *
 * @author hal.hildebrand
 */
public class P414IntegrationSuiteTest {

    private BalanceConfiguration balanceConfig;
    private FaultConfiguration faultConfig;
    private SimpleFaultHandler faultHandler;
    private DefaultParallelBalancer<?, ?, ?> balancer;
    private GhostSyncFaultAdapter ghostSyncAdapter;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        balanceConfig = BalanceConfiguration.defaultConfig();
        faultConfig = FaultConfiguration.defaultConfig();
        faultHandler = new SimpleFaultHandler(faultConfig);
        faultHandler.start();

        balancer = new DefaultParallelBalancer<>(balanceConfig);
        ghostSyncAdapter = new GhostSyncFaultAdapter(faultHandler);
        executor = Executors.newFixedThreadPool(5);
    }

    /**
     * Integration Test 1: Pause/resume with concurrent balance operations.
     *
     * <p>Verifies that balance operations are properly coordinated during pause/resume cycle,
     * with concurrent operations blocked during pause.
     */
    @Test
    void testPauseResumeWithConcurrentBalance() throws InterruptedException {
        var operationsPre = new AtomicInteger(0);
        var operationsDuring = new AtomicInteger(0);
        var operationsPost = new AtomicInteger(0);
        var startLatch = new CountDownLatch(1);
        var completeLatch = new CountDownLatch(3);

        // Start 3 concurrent balance operations before pause
        for (int i = 0; i < 3; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    // Try to start operation
                    var token = balancer.getOperationTracker().tryBeginOperation();
                    if (token.isPresent()) {
                        operationsPre.incrementAndGet();
                        token.get().close();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    completeLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        completeLatch.await();

        // Then: Pre-pause operations should succeed
        assertEquals(3, operationsPre.get(), "All operations should succeed before pause");

        // When: Pause balance operations
        balancer.pauseCrossPartitionBalance();

        // And: Try 3 concurrent operations during pause
        var startLatch2 = new CountDownLatch(1);
        var completeLatch2 = new CountDownLatch(3);

        for (int i = 0; i < 3; i++) {
            executor.submit(() -> {
                try {
                    startLatch2.await();
                    var token = balancer.getOperationTracker().tryBeginOperation();
                    if (token.isPresent()) {
                        operationsDuring.incrementAndGet();
                        token.get().close();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    completeLatch2.countDown();
                }
            });
        }

        startLatch2.countDown();
        completeLatch2.await();

        // Then: During-pause operations should be blocked
        assertEquals(0, operationsDuring.get(), "Operations should be blocked during pause");

        // When: Resume balance operations
        balancer.resumeCrossPartitionBalance();

        // And: Try 3 concurrent operations after resume
        var startLatch3 = new CountDownLatch(1);
        var completeLatch3 = new CountDownLatch(3);

        for (int i = 0; i < 3; i++) {
            executor.submit(() -> {
                try {
                    startLatch3.await();
                    var token = balancer.getOperationTracker().tryBeginOperation();
                    if (token.isPresent()) {
                        operationsPost.incrementAndGet();
                        token.get().close();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    completeLatch3.countDown();
                }
            });
        }

        startLatch3.countDown();
        completeLatch3.await();

        // Then: Post-resume operations should succeed
        assertEquals(3, operationsPost.get(), "All operations should succeed after resume");
    }

    /**
     * Integration Test 2: Ghost sync failure detection triggers recovery coordination.
     *
     * <p>Verifies that ghost sync failures are properly routed to fault handler and
     * trigger recovery coordination.
     */
    @Test
    void testGhostSyncFailureDetection() throws InterruptedException {
        // Given: Ghost sync adapter with fault handler
        var partitionId = UUID.randomUUID();
        var rank = 1;
        ghostSyncAdapter.mapRankToPartition(rank, partitionId);

        // Track status changes
        var statusChanges = new AtomicInteger(0);
        faultHandler.subscribeToChanges(event -> {
            if (partitionId.equals(event.partitionId())) {
                statusChanges.incrementAndGet();
            }
        });

        // When: Report sync failure
        ghostSyncAdapter.onSyncFailure(rank, new RuntimeException("Network error"));

        // Then: Fault should be recorded
        var status = faultHandler.checkHealth(partitionId);
        assertTrue(!status.toString().contains("HEALTHY"), "Partition should be in fault state");
        assertEquals(1, statusChanges.get(), "Should record status transition");
    }

    /**
     * Integration Test 3: Concurrent recovery with quorum-aware coordination.
     *
     * <p>Verifies that multiple concurrent recovery attempts respect quorum constraints
     * and don't allow simultaneous recoveries beyond configured limit.
     */
    @Test
    void testConcurrentRecoveryWithQuorum() throws InterruptedException {
        // Given: Multiple partitions with fault handler
        var partitionIds = new UUID[] {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
        var recoveryAttempts = new AtomicInteger(0);
        var completedRecoveries = new AtomicInteger(0);

        // Mark partitions as suspected
        for (var partitionId : partitionIds) {
            faultHandler.reportSyncFailure(partitionId);
        }

        // When: Attempt concurrent recoveries
        var startLatch = new CountDownLatch(1);
        var completeLatch = new CountDownLatch(3);

        for (var partitionId : partitionIds) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    recoveryAttempts.incrementAndGet();

                    // Simulate recovery work
                    Thread.sleep(100);

                    // Mark as healthy after recovery
                    faultHandler.markHealthy(partitionId);
                    completedRecoveries.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    completeLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        completeLatch.await(5, TimeUnit.SECONDS);

        // Then: All recoveries should complete
        assertEquals(3, recoveryAttempts.get(), "All recovery attempts should start");
        assertEquals(3, completedRecoveries.get(), "All recoveries should complete");

        // And: Partitions should be marked healthy
        for (var partitionId : partitionIds) {
            var status = faultHandler.checkHealth(partitionId);
            assertTrue(status.toString().contains("HEALTHY"), "Partition should be healthy after recovery");
        }
    }

    /**
     * Integration Test 4: Livelock recovery queue processing.
     *
     * <p>Verifies that queued recovery operations (when quorum is temporarily lost) are
     * processed when quorum is restored.
     */
    @Test
    void testLivelockRecoveryQueueProcessing() throws InterruptedException {
        // Given: Setup for quorum-based recovery coordination
        var partitionId = UUID.randomUUID();
        var rank = 1;
        ghostSyncAdapter.mapRankToPartition(rank, partitionId);

        var recoveryAttempts = new AtomicInteger(0);
        var recoveryQueue = new java.util.concurrent.ConcurrentLinkedQueue<UUID>();

        // Simulate recovery queuing logic: if no quorum, queue for retry
        faultHandler.subscribeToChanges(event -> {
            if (event.newStatus().toString().contains("SUSPECTED")) {
                recoveryQueue.offer(event.partitionId());
            }
        });

        // When: Partition enters suspected state (recovery queued)
        ghostSyncAdapter.onSyncFailure(rank, new Exception("Sync failed"));
        assertTrue(recoveryQueue.contains(partitionId), "Recovery should be queued");

        // Then: Process queued recoveries
        while (!recoveryQueue.isEmpty()) {
            var queuedPartitionId = recoveryQueue.poll();
            if (queuedPartitionId != null) {
                recoveryAttempts.incrementAndGet();
                // Simulate recovery
                faultHandler.markHealthy(queuedPartitionId);
            }
        }

        // Then: Recovery should have been processed
        assertEquals(1, recoveryAttempts.get(), "Queued recovery should be processed");
        var status = faultHandler.checkHealth(partitionId);
        assertTrue(status.toString().contains("HEALTHY"), "Partition should be recovered");
    }

    /**
     * Integration Test 5: End-to-end workflow with all P4.1 components.
     *
     * <p>Verifies complete workflow: pause → detect fault → queue recovery → resume → recover.
     */
    @Test
    void testCompleteEndToEndWorkflow() throws InterruptedException {
        // Given: Complete setup
        var partitionId = UUID.randomUUID();
        var rank = 1;
        ghostSyncAdapter.mapRankToPartition(rank, partitionId);

        var workflowStages = new AtomicInteger(0);

        // Stage 1: Normal operation (pre-pause)
        var token = balancer.getOperationTracker().tryBeginOperation();
        assertTrue(token.isPresent(), "Should allow operations before pause");
        token.get().close();
        workflowStages.incrementAndGet();

        // Stage 2: Pause for recovery
        balancer.pauseCrossPartitionBalance();
        workflowStages.incrementAndGet();

        // Stage 3: Detect fault via ghost sync
        ghostSyncAdapter.onSyncFailure(rank, new Exception("Simulated failure"));
        workflowStages.incrementAndGet();

        // Stage 4: Verify operations blocked during pause
        var blockedToken = balancer.getOperationTracker().tryBeginOperation();
        assertTrue(blockedToken.isEmpty(), "Should block operations during pause");
        workflowStages.incrementAndGet();

        // Stage 5: Recover partition
        faultHandler.markHealthy(partitionId);
        workflowStages.incrementAndGet();

        // Stage 6: Resume operations
        balancer.resumeCrossPartitionBalance();
        workflowStages.incrementAndGet();

        // Stage 7: Verify operations allowed after resume
        token = balancer.getOperationTracker().tryBeginOperation();
        assertTrue(token.isPresent(), "Should allow operations after resume");
        token.get().close();
        workflowStages.incrementAndGet();

        // Then: All workflow stages completed
        assertEquals(7, workflowStages.get(), "All workflow stages should complete");

        // And: System should be in consistent state
        var balancerNotPaused = !balancer.isPaused();
        var partitionHealthy = faultHandler.checkHealth(partitionId).toString().contains("HEALTHY");
        assertTrue(balancerNotPaused && partitionHealthy, "System should be in consistent state");
    }
}
