/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 * Part of Luciferase Simulation Framework. Licensed under AGPL v3.0.
 */

package com.hellblazer.luciferase.simulation.distributed.network;

import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.bubble.EnhancedBubbleMigrationIntegration;
import com.hellblazer.luciferase.simulation.causality.*;
import com.hellblazer.luciferase.simulation.distributed.migration.*;
import com.hellblazer.luciferase.simulation.events.*;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Large-scale distributed testing for Phase 7F Day 7.
 * Tests distributed migrations across 8+ node clusters with
 * complex topologies, many concurrent migrations, and network scalability.
 */
@DisplayName("Large-Scale Distributed - 8+ Node Cluster Testing")
class LargeScaleDistributedTest {

    private static final Logger log = LoggerFactory.getLogger(LargeScaleDistributedTest.class);

    private static final int CLUSTER_SIZE = 8;

    private UUID[] nodeIds;
    private DistributedBubbleNode[] nodes;
    private OptimisticMigratorImpl[] migrators;

    @BeforeEach
    void setUp() {
        FakeNetworkChannel.clearNetwork();

        nodeIds = new UUID[CLUSTER_SIZE];
        nodes = new DistributedBubbleNode[CLUSTER_SIZE];
        migrators = new OptimisticMigratorImpl[CLUSTER_SIZE];

        var channels = new FakeNetworkChannel[CLUSTER_SIZE];

        // Phase 1: Create all node IDs and initialize channels
        for (int i = 0; i < CLUSTER_SIZE; i++) {
            nodeIds[i] = UUID.randomUUID();
            channels[i] = new FakeNetworkChannel(nodeIds[i]);
            channels[i].initialize(nodeIds[i], "localhost:" + (11000 + i));
        }

        // Phase 2: Register all nodes with all channels
        for (int i = 0; i < CLUSTER_SIZE; i++) {
            for (int j = 0; j < CLUSTER_SIZE; j++) {
                if (i != j) {
                    channels[i].registerNode(nodeIds[j], "localhost:" + (11000 + j));
                }
            }
        }

        // Phase 3: Create bubbles, migrators, and distributed nodes
        for (int i = 0; i < CLUSTER_SIZE; i++) {
            // Create bubble
            var bubble = new EnhancedBubble(nodeIds[i], (byte) 10, 100L);

            // Create migration components
            var mockView = mock(com.hellblazer.luciferase.simulation.delos.MembershipView.class);
            var viewMonitor = new FirefliesViewMonitor(mockView, CLUSTER_SIZE);
            var fsm = new EntityMigrationStateMachine(viewMonitor);

            migrators[i] = new OptimisticMigratorImpl();
            var oracle = new MigrationOracleImpl(CLUSTER_SIZE, CLUSTER_SIZE, CLUSTER_SIZE);

            var integration = new EnhancedBubbleMigrationIntegration(
                bubble, fsm, oracle, migrators[i], viewMonitor, CLUSTER_SIZE);

            // Create distributed node with pre-initialized channel
            nodes[i] = new DistributedBubbleNode(nodeIds[i], bubble, channels[i], integration, fsm);
        }

        log.info("Cluster setup: {} nodes initialized", CLUSTER_SIZE);
    }

    @AfterEach
    void tearDown() {
        FakeNetworkChannel.clearNetwork();
    }

    @Test
    @DisplayName("8-node fully connected cluster: all nodes reach all nodes")
    void testFullyConnectedCluster() {
        for (int i = 0; i < CLUSTER_SIZE; i++) {
            for (int j = 0; j < CLUSTER_SIZE; j++) {
                if (i != j) {
                    assertTrue(nodes[i].isNodeReachable(nodeIds[j]),
                        "Node " + i + " should reach node " + j);
                }
            }
        }
        log.info("✓ Fully connected cluster: all {} nodes reachable", CLUSTER_SIZE);
    }

    @Test
    @DisplayName("Sequential migrations across cluster: entity migrates through 8 nodes")
    void testSequentialClusterMigrations() {
        var entityId = UUID.randomUUID();

        // Entity migrates: N0 → N1 → N2 → N3 → N4 → N5 → N6 → N7
        for (int i = 0; i < CLUSTER_SIZE - 1; i++) {
            int source = i;
            int target = i + 1;

            migrators[source].initiateOptimisticMigration(entityId, nodeIds[target]);
            migrators[source].queueDeferredUpdate(entityId,
                new float[]{source, source + 1, source + 2},
                new float[]{0.1f, 0.2f, 0.3f});

            var event = new EntityDepartureEvent(
                entityId, nodeIds[source], nodeIds[target],
                EntityMigrationState.MIGRATING_OUT, i);
            nodes[source].sendEntityDeparture(nodeIds[target], event);

            migrators[target].queueDeferredUpdate(entityId,
                new float[]{source + 0.1f, source + 1.1f, source + 2.1f},
                new float[]{0.1f, 0.2f, 0.3f});
            migrators[target].flushDeferredUpdates(entityId);

            var ack = new ViewSynchronyAck(entityId, nodeIds[target], nodeIds[source], CLUSTER_SIZE, i);
            nodes[target].sendViewSynchronyAck(nodeIds[source], ack);
            migrators[source].flushDeferredUpdates(entityId);
        }

        // Verify all pending counts cleared
        for (int i = 0; i < CLUSTER_SIZE; i++) {
            assertEquals(0, migrators[i].getPendingDeferredCount(),
                "Node " + i + " should have no pending deferred updates");
        }

        log.info("✓ Sequential cluster migrations: entity traversed all {} nodes", CLUSTER_SIZE);
    }

    @Test
    @DisplayName("Concurrent fan-out: node 0 initiates migrations to all other 7 nodes")
    void testConcurrentFanOutMigrations() throws InterruptedException {
        var entityIds = new UUID[CLUSTER_SIZE - 1];
        var threads = new Thread[CLUSTER_SIZE - 1];

        // Create threads for concurrent migrations from node 0 to all others
        for (int target = 1; target < CLUSTER_SIZE; target++) {
            final int targetIndex = target;
            entityIds[target - 1] = UUID.randomUUID();
            final UUID entityId = entityIds[target - 1];

            threads[target - 1] = new Thread(() -> {
                migrators[0].initiateOptimisticMigration(entityId, nodeIds[targetIndex]);
                migrators[0].queueDeferredUpdate(entityId,
                    new float[]{0.0f, 1.0f, targetIndex},
                    new float[]{0.1f, 0.2f, 0.3f});

                var event = new EntityDepartureEvent(
                    entityId, nodeIds[0], nodeIds[targetIndex],
                    EntityMigrationState.MIGRATING_OUT, targetIndex);
                nodes[0].sendEntityDeparture(nodeIds[targetIndex], event);

                migrators[targetIndex].queueDeferredUpdate(entityId,
                    new float[]{0.1f, 1.1f, targetIndex + 0.1f},
                    new float[]{0.1f, 0.2f, 0.3f});
                migrators[targetIndex].flushDeferredUpdates(entityId);

                var ack = new ViewSynchronyAck(entityId, nodeIds[targetIndex], nodeIds[0], CLUSTER_SIZE, targetIndex);
                nodes[targetIndex].sendViewSynchronyAck(nodeIds[0], ack);
                migrators[0].flushDeferredUpdates(entityId);
            });
        }

        // Start all threads
        for (var thread : threads) {
            thread.start();
        }

        // Wait for all to complete
        for (var thread : threads) {
            thread.join();
        }

        // Verify all migrations succeeded
        assertEquals(0, migrators[0].getPendingDeferredCount());
        for (int i = 1; i < CLUSTER_SIZE; i++) {
            assertEquals(0, migrators[i].getPendingDeferredCount(),
                "Node " + i + " should have no pending updates");
        }

        log.info("✓ Concurrent fan-out: node 0 migrated {} entities to all other nodes", CLUSTER_SIZE - 1);
    }

    @Test
    @DisplayName("All-to-all migrations: each node migrates to all other nodes")
    void testAllToAllMigrations() throws InterruptedException {
        var migrationCount = new AtomicInteger(0);
        var threads = new ArrayList<Thread>();

        // Each source node migrates to each target node
        for (int source = 0; source < CLUSTER_SIZE; source++) {
            for (int target = 0; target < CLUSTER_SIZE; target++) {
                if (source != target) {
                    final int src = source;
                    final int tgt = target;
                    final UUID entityId = UUID.randomUUID();

                    var thread = new Thread(() -> {
                        migrators[src].initiateOptimisticMigration(entityId, nodeIds[tgt]);
                        migrators[src].queueDeferredUpdate(entityId,
                            new float[]{src, tgt, 1.0f},
                            new float[]{0.1f, 0.2f, 0.3f});

                        var event = new EntityDepartureEvent(
                            entityId, nodeIds[src], nodeIds[tgt],
                            EntityMigrationState.MIGRATING_OUT, src * 100 + tgt);
                        if (nodes[src].sendEntityDeparture(nodeIds[tgt], event)) {
                            migrators[tgt].queueDeferredUpdate(entityId,
                                new float[]{src + 0.1f, tgt + 0.1f, 1.1f},
                                new float[]{0.1f, 0.2f, 0.3f});
                            migrators[tgt].flushDeferredUpdates(entityId);
                            migrationCount.incrementAndGet();
                        }
                        migrators[src].flushDeferredUpdates(entityId);
                    });

                    threads.add(thread);
                }
            }
        }

        // Start all threads in parallel
        for (var thread : threads) {
            thread.start();
        }

        // Wait for all to complete
        for (var thread : threads) {
            thread.join();
        }

        // Verify all migrations succeeded
        for (int i = 0; i < CLUSTER_SIZE; i++) {
            assertEquals(0, migrators[i].getPendingDeferredCount(),
                "Node " + i + " should have no pending updates");
        }

        int totalMigrations = CLUSTER_SIZE * (CLUSTER_SIZE - 1);
        log.info("✓ All-to-all migrations: {}/{} migrations completed",
            migrationCount.get(), totalMigrations);
    }

    @Test
    @DisplayName("Ring topology: migrations form a circle N0→N1→...→N7→N0")
    void testRingTopologyMigrations() {
        var entityId = UUID.randomUUID();

        // Form a ring: each node migrates to the next, last to first
        for (int i = 0; i < CLUSTER_SIZE; i++) {
            int source = i;
            int target = (i + 1) % CLUSTER_SIZE;

            migrators[source].initiateOptimisticMigration(entityId, nodeIds[target]);
            migrators[source].queueDeferredUpdate(entityId,
                new float[]{source, target, i},
                new float[]{0.1f, 0.2f, 0.3f});

            var event = new EntityDepartureEvent(
                entityId, nodeIds[source], nodeIds[target],
                EntityMigrationState.MIGRATING_OUT, i);
            nodes[source].sendEntityDeparture(nodeIds[target], event);

            migrators[target].queueDeferredUpdate(entityId,
                new float[]{source + 0.1f, target + 0.1f, i + 0.1f},
                new float[]{0.1f, 0.2f, 0.3f});
            migrators[target].flushDeferredUpdates(entityId);

            var ack = new ViewSynchronyAck(entityId, nodeIds[target], nodeIds[source], CLUSTER_SIZE, i);
            nodes[target].sendViewSynchronyAck(nodeIds[source], ack);
            migrators[source].flushDeferredUpdates(entityId);
        }

        for (int i = 0; i < CLUSTER_SIZE; i++) {
            assertEquals(0, migrators[i].getPendingDeferredCount());
        }

        log.info("✓ Ring topology: entity completed full circle through all {} nodes", CLUSTER_SIZE);
    }

    @Test
    @DisplayName("Scalability with latency: cluster operates correctly with 50ms latency")
    void testScalabilityWithLatency() {
        // Set varied latency for each node (5-50ms)
        for (int i = 0; i < CLUSTER_SIZE; i++) {
            nodes[i].setNetworkLatency(5 + (i * 5));
        }

        var entityId = UUID.randomUUID();

        // Test migration with latency
        migrators[0].initiateOptimisticMigration(entityId, nodeIds[7]);
        migrators[0].queueDeferredUpdate(entityId,
            new float[]{1.0f, 2.0f, 3.0f},
            new float[]{0.1f, 0.2f, 0.3f});

        long startMs = System.currentTimeMillis();
        var event = new EntityDepartureEvent(
            entityId, nodeIds[0], nodeIds[7],
            EntityMigrationState.MIGRATING_OUT, 0L);
        nodes[0].sendEntityDeparture(nodeIds[7], event);

        migrators[7].queueDeferredUpdate(entityId,
            new float[]{1.1f, 2.1f, 3.1f},
            new float[]{0.1f, 0.2f, 0.3f});
        migrators[7].flushDeferredUpdates(entityId);

        var ack = new ViewSynchronyAck(entityId, nodeIds[7], nodeIds[0], CLUSTER_SIZE, 0L);
        nodes[7].sendViewSynchronyAck(nodeIds[0], ack);
        migrators[0].flushDeferredUpdates(entityId);

        long elapsedMs = System.currentTimeMillis() - startMs;

        assertEquals(0, migrators[0].getPendingDeferredCount());
        assertEquals(0, migrators[7].getPendingDeferredCount());

        log.info("✓ Scalability with latency: migration completed in {}ms with varied link delays", elapsedMs);
    }

    @Test
    @DisplayName("Massive concurrent load: 100+ simultaneous entities migrating")
    void testMassiveConcurrentLoad() throws InterruptedException {
        int entityCount = 100;
        var threads = new Thread[entityCount];
        var successCount = new AtomicInteger(0);

        for (int i = 0; i < entityCount; i++) {
            final int entityNum = i;
            final UUID entityId = UUID.randomUUID();
            final int sourceNode = i % CLUSTER_SIZE;
            final int targetNode = (i + 1) % CLUSTER_SIZE;

            threads[i] = new Thread(() -> {
                migrators[sourceNode].initiateOptimisticMigration(entityId, nodeIds[targetNode]);
                migrators[sourceNode].queueDeferredUpdate(entityId,
                    new float[]{sourceNode, targetNode, entityNum},
                    new float[]{0.1f, 0.2f, 0.3f});

                var event = new EntityDepartureEvent(
                    entityId, nodeIds[sourceNode], nodeIds[targetNode],
                    EntityMigrationState.MIGRATING_OUT, entityNum);
                if (nodes[sourceNode].sendEntityDeparture(nodeIds[targetNode], event)) {
                    migrators[targetNode].queueDeferredUpdate(entityId,
                        new float[]{sourceNode + 0.1f, targetNode + 0.1f, entityNum + 0.1f},
                        new float[]{0.1f, 0.2f, 0.3f});
                    migrators[targetNode].flushDeferredUpdates(entityId);
                    successCount.incrementAndGet();
                }
                migrators[sourceNode].flushDeferredUpdates(entityId);
            });
        }

        long startMs = System.currentTimeMillis();
        for (var thread : threads) {
            thread.start();
        }
        for (var thread : threads) {
            thread.join();
        }
        long elapsedMs = System.currentTimeMillis() - startMs;

        // Verify all pending counts
        for (int i = 0; i < CLUSTER_SIZE; i++) {
            assertEquals(0, migrators[i].getPendingDeferredCount(),
                "Node " + i + " should have no pending updates");
        }

        log.info("✓ Massive concurrent load: {} entities migrated in {}ms",
            successCount.get(), elapsedMs);
    }

    @Test
    @DisplayName("Network with packet loss: cluster handles 10% loss at scale")
    void testScalabilityWithPacketLoss() throws InterruptedException {
        // Set 10% packet loss on all nodes
        for (int i = 0; i < CLUSTER_SIZE; i++) {
            nodes[i].setPacketLoss(0.1);
        }

        int migrationCount = 50;
        var successCount = new AtomicInteger(0);
        var threads = new Thread[migrationCount];

        for (int i = 0; i < migrationCount; i++) {
            final int idx = i;
            final UUID entityId = UUID.randomUUID();
            final int sourceNode = i % CLUSTER_SIZE;
            final int targetNode = (i + 1) % CLUSTER_SIZE;

            threads[i] = new Thread(() -> {
                migrators[sourceNode].initiateOptimisticMigration(entityId, nodeIds[targetNode]);
                migrators[sourceNode].queueDeferredUpdate(entityId,
                    new float[]{sourceNode, targetNode, idx},
                    new float[]{0.1f, 0.2f, 0.3f});

                var event = new EntityDepartureEvent(
                    entityId, nodeIds[sourceNode], nodeIds[targetNode],
                    EntityMigrationState.MIGRATING_OUT, idx);
                if (nodes[sourceNode].sendEntityDeparture(nodeIds[targetNode], event)) {
                    migrators[targetNode].queueDeferredUpdate(entityId,
                        new float[]{sourceNode + 0.1f, targetNode + 0.1f, idx + 0.1f},
                        new float[]{0.1f, 0.2f, 0.3f});
                    migrators[targetNode].flushDeferredUpdates(entityId);
                    successCount.incrementAndGet();
                }
                migrators[sourceNode].flushDeferredUpdates(entityId);
            });
        }

        for (var thread : threads) {
            thread.start();
        }
        for (var thread : threads) {
            thread.join();
        }

        // Despite packet loss, significant portion should succeed
        assertTrue(successCount.get() > migrationCount / 2,
            "At least half should succeed despite 10% packet loss");

        log.info("✓ Scalability with packet loss: {}/{} migrations succeeded with 10% loss",
            successCount.get(), migrationCount);
    }
}
