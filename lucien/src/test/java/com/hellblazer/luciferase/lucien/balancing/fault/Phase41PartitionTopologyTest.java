package com.hellblazer.luciferase.lucien.balancing.fault;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for Phase 4.1 PartitionTopology interface and InMemoryPartitionTopology implementation.
 *
 * Verifies:
 * - Bidirectional UUID<->rank mapping
 * - Concurrent access thread safety
 * - Version incrementing on topology changes
 * - Invalid operation handling
 */
class Phase41PartitionTopologyTest {

    private PartitionTopology topology;

    @BeforeEach
    void setUp() {
        topology = new InMemoryPartitionTopology();
    }

    @Test
    void testBidirectionalMapping() {
        var partition0 = UUID.randomUUID();
        var partition1 = UUID.randomUUID();
        var partition2 = UUID.randomUUID();

        // Register partitions
        topology.register(partition0, 0);
        topology.register(partition1, 1);
        topology.register(partition2, 2);

        // Test UUID -> rank lookups
        assertThat(topology.rankFor(partition0)).hasValue(0);
        assertThat(topology.rankFor(partition1)).hasValue(1);
        assertThat(topology.rankFor(partition2)).hasValue(2);

        // Test rank -> UUID lookups
        assertThat(topology.partitionFor(0)).hasValue(partition0);
        assertThat(topology.partitionFor(1)).hasValue(partition1);
        assertThat(topology.partitionFor(2)).hasValue(partition2);

        // Test unknown lookups return empty
        assertThat(topology.rankFor(UUID.randomUUID())).isEmpty();
        assertThat(topology.partitionFor(99)).isEmpty();

        // Test totalPartitions
        assertThat(topology.totalPartitions()).isEqualTo(3);

        // Test activeRanks
        var ranks = topology.activeRanks();
        assertThat(ranks).containsExactlyInAnyOrder(0, 1, 2);

        // Test unregister
        topology.unregister(partition1);
        assertThat(topology.rankFor(partition1)).isEmpty();
        assertThat(topology.partitionFor(1)).isEmpty();
        assertThat(topology.totalPartitions()).isEqualTo(2);
        assertThat(topology.activeRanks()).containsExactlyInAnyOrder(0, 2);
    }

    @Test
    void testConcurrentAccess() throws InterruptedException {
        // Test thread-safe concurrent registration and lookups
        final int numThreads = 10;
        final int operationsPerThread = 100;
        var executor = Executors.newFixedThreadPool(numThreads);
        var latch = new CountDownLatch(numThreads);
        var successCount = new AtomicInteger(0);

        // Each thread registers unique partitions
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < operationsPerThread; i++) {
                        var partitionId = UUID.randomUUID();
                        int rank = threadId * operationsPerThread + i;

                        topology.register(partitionId, rank);

                        // Verify immediately
                        if (topology.rankFor(partitionId).orElse(-1) == rank
                            && topology.partitionFor(rank).orElse(null) == partitionId) {
                            successCount.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        // Verify all registrations succeeded
        assertThat(successCount.get()).isEqualTo(numThreads * operationsPerThread);
        assertThat(topology.totalPartitions()).isEqualTo(numThreads * operationsPerThread);
    }

    @Test
    void testVersionIncrementing() {
        // Version should start at 0
        assertThat(topology.topologyVersion()).isZero();

        // Registration increments version
        var partition0 = UUID.randomUUID();
        topology.register(partition0, 0);
        assertThat(topology.topologyVersion()).isEqualTo(1);

        // Another registration increments again
        var partition1 = UUID.randomUUID();
        topology.register(partition1, 1);
        assertThat(topology.topologyVersion()).isEqualTo(2);

        // Unregister increments version
        topology.unregister(partition0);
        assertThat(topology.topologyVersion()).isEqualTo(3);

        // Unregistering unknown partition does NOT increment
        topology.unregister(UUID.randomUUID());
        assertThat(topology.topologyVersion()).isEqualTo(3);

        // Multiple operations increment correctly
        var partition2 = UUID.randomUUID();
        topology.register(partition2, 2);
        topology.unregister(partition1);
        topology.unregister(partition2);
        assertThat(topology.topologyVersion()).isEqualTo(6);
    }

    @Test
    void testInvalidOperations() {
        var partition0 = UUID.randomUUID();
        var partition1 = UUID.randomUUID();

        // Register partition0 at rank 0
        topology.register(partition0, 0);

        // Cannot register different partition at same rank
        assertThatThrownBy(() -> topology.register(partition1, 0))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Rank 0 already mapped");

        // Cannot register same UUID again (even at different rank)
        // Implementation may allow this (re-registration) or throw
        // Verify current behavior is consistent
        var initialVersion = topology.topologyVersion();
        topology.register(partition0, 0); // Re-register at same rank should be idempotent
        assertThat(topology.topologyVersion()).isEqualTo(initialVersion); // No change if idempotent

        // Negative ranks are technically allowed (implementation choice)
        // but let's verify the behavior is consistent
        var partition2 = UUID.randomUUID();
        topology.register(partition2, -1);
        assertThat(topology.rankFor(partition2)).hasValue(-1);
        assertThat(topology.partitionFor(-1)).hasValue(partition2);
    }
}
