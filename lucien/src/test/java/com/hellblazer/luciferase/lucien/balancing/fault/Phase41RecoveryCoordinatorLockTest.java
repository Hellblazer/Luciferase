package com.hellblazer.luciferase.lucien.balancing.fault;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for Phase 4.1 RecoveryCoordinatorLock.
 *
 * Verifies:
 * - Quorum checking (majority of partitions must be active)
 * - Semaphore-based concurrent recovery limiting
 * - Concurrent lock acquisition thread safety
 * - Ghost manager serialization
 * - Recovery lock acquisition and release
 */
class Phase41RecoveryCoordinatorLockTest {

    private RecoveryCoordinatorLock coordinatorLock;
    private PartitionTopology mockTopology;

    @BeforeEach
    void setUp() {
        coordinatorLock = new RecoveryCoordinatorLock(3); // Max 3 concurrent recoveries
        mockTopology = mock(PartitionTopology.class);
    }

    @Test
    void testQuorumCheck() {
        // Test hasQuorum method with various partition counts

        // Quorum maintained: 3 active out of 5 total (> 50%)
        assertThat(coordinatorLock.hasQuorum(3, 5)).isTrue();

        // Quorum maintained: 4 active out of 5 total
        assertThat(coordinatorLock.hasQuorum(4, 5)).isTrue();

        // Quorum maintained: 5 active out of 5 total
        assertThat(coordinatorLock.hasQuorum(5, 5)).isTrue();

        // Quorum lost: 2 active out of 5 total (40%, not > 50%)
        assertThat(coordinatorLock.hasQuorum(2, 5)).isFalse();

        // Quorum lost: 1 active out of 5 total
        assertThat(coordinatorLock.hasQuorum(1, 5)).isFalse();

        // Edge case: exactly half (2 out of 4) - should be false (need strict majority)
        assertThat(coordinatorLock.hasQuorum(2, 4)).isFalse();

        // Edge case: strict majority (3 out of 4)
        assertThat(coordinatorLock.hasQuorum(3, 4)).isTrue();

        // Edge case: single partition (1 out of 1)
        assertThat(coordinatorLock.hasQuorum(1, 1)).isTrue();

        // Edge case: 3 partitions
        assertThat(coordinatorLock.hasQuorum(2, 3)).isTrue(); // > 50%
        assertThat(coordinatorLock.hasQuorum(1, 3)).isFalse(); // < 50%
    }

    @Test
    void testSemaphoreLimit() throws InterruptedException {
        // Test that semaphore limits concurrent recoveries to maxConcurrentRecoveries
        var partition1 = UUID.randomUUID();
        var partition2 = UUID.randomUUID();
        var partition3 = UUID.randomUUID();
        var partition4 = UUID.randomUUID();

        // Mock topology with 10 partitions, 9 active (quorum maintained)
        when(mockTopology.activeRanks()).thenReturn(Set.of(0, 1, 2, 3, 4, 5, 6, 7, 8));
        when(mockTopology.totalPartitions()).thenReturn(10);
        when(mockTopology.partitionFor(0)).thenReturn(java.util.Optional.of(partition1));

        // Acquire 3 locks (should succeed - at limit)
        boolean lock1 = coordinatorLock.acquireRecoveryLock(partition1, mockTopology, 1, TimeUnit.SECONDS);
        boolean lock2 = coordinatorLock.acquireRecoveryLock(partition2, mockTopology, 1, TimeUnit.SECONDS);
        boolean lock3 = coordinatorLock.acquireRecoveryLock(partition3, mockTopology, 1, TimeUnit.SECONDS);

        assertThat(lock1).isTrue();
        assertThat(lock2).isTrue();
        assertThat(lock3).isTrue();
        assertThat(coordinatorLock.activeRecoveryCount()).isEqualTo(3);

        // 4th lock should fail (timeout) - semaphore exhausted
        boolean lock4 = coordinatorLock.acquireRecoveryLock(partition4, mockTopology, 100, TimeUnit.MILLISECONDS);
        assertThat(lock4).isFalse();
        assertThat(coordinatorLock.activeRecoveryCount()).isEqualTo(3); // Still 3

        // Release one lock
        coordinatorLock.releaseRecoveryLock(partition1);
        assertThat(coordinatorLock.activeRecoveryCount()).isEqualTo(2);

        // Now 4th lock should succeed
        lock4 = coordinatorLock.acquireRecoveryLock(partition4, mockTopology, 1, TimeUnit.SECONDS);
        assertThat(lock4).isTrue();
        assertThat(coordinatorLock.activeRecoveryCount()).isEqualTo(3);

        // Cleanup
        coordinatorLock.releaseRecoveryLock(partition2);
        coordinatorLock.releaseRecoveryLock(partition3);
        coordinatorLock.releaseRecoveryLock(partition4);
        assertThat(coordinatorLock.activeRecoveryCount()).isZero();
    }

    @Test
    void testConcurrentLockAcquisition() throws InterruptedException {
        // Test thread-safe concurrent lock acquisition
        final int numThreads = 10;
        var executor = Executors.newFixedThreadPool(numThreads);
        var latch = new CountDownLatch(numThreads);
        var successCount = new AtomicInteger(0);
        var failureCount = new AtomicInteger(0);

        // Mock topology with quorum maintained
        when(mockTopology.activeRanks()).thenReturn(Set.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9));
        when(mockTopology.totalPartitions()).thenReturn(10);

        // Each thread tries to acquire a lock for a unique partition
        for (int i = 0; i < numThreads; i++) {
            final UUID partitionId = UUID.randomUUID();
            executor.submit(() -> {
                try {
                    boolean acquired = coordinatorLock.acquireRecoveryLock(
                        partitionId, mockTopology, 2, TimeUnit.SECONDS
                    );

                    if (acquired) {
                        successCount.incrementAndGet();
                        // Hold lock briefly
                        Thread.sleep(10);
                        coordinatorLock.releaseRecoveryLock(partitionId);
                    } else {
                        failureCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        // With maxConcurrentRecoveries=3 and 10 threads, some must wait
        // But all should eventually succeed given the 2-second timeout
        assertThat(successCount.get()).isGreaterThanOrEqualTo(3); // At least the first 3
        assertThat(successCount.get() + failureCount.get()).isEqualTo(numThreads);
    }

    @Test
    void testGhostManagerSerialization() throws InterruptedException {
        // Test that withGhostManagerLock provides exclusive access
        var executor = Executors.newFixedThreadPool(5);
        var latch = new CountDownLatch(5);
        var concurrentAccessDetected = new AtomicInteger(0);
        var insideCriticalSection = new AtomicInteger(0);

        for (int i = 0; i < 5; i++) {
            executor.submit(() -> {
                try {
                    coordinatorLock.withGhostManagerLock(() -> {
                        int currentCount = insideCriticalSection.incrementAndGet();

                        // If > 1, we have concurrent access (bad!)
                        if (currentCount > 1) {
                            concurrentAccessDetected.incrementAndGet();
                        }

                        // Simulate work
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }

                        insideCriticalSection.decrementAndGet();
                        return null;
                    });
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        // Verify no concurrent access detected
        assertThat(concurrentAccessDetected.get()).isZero();
    }

    @Test
    void testQuorumLossRecoveryRejection() throws InterruptedException {
        // Test that recovery is rejected when quorum is lost
        var partitionId = UUID.randomUUID();

        // Mock topology with quorum lost: 2 active out of 5 total (40%)
        when(mockTopology.activeRanks()).thenReturn(Set.of(0, 1));
        when(mockTopology.totalPartitions()).thenReturn(5);

        // Attempt to acquire lock should fail due to quorum loss
        boolean acquired = coordinatorLock.acquireRecoveryLock(partitionId, mockTopology, 1, TimeUnit.SECONDS);

        assertThat(acquired).isFalse();
        assertThat(coordinatorLock.activeRecoveryCount()).isZero();

        // Restore quorum: 3 active out of 5 total
        when(mockTopology.activeRanks()).thenReturn(Set.of(0, 1, 2));

        // Now acquisition should succeed
        acquired = coordinatorLock.acquireRecoveryLock(partitionId, mockTopology, 1, TimeUnit.SECONDS);
        assertThat(acquired).isTrue();
        assertThat(coordinatorLock.activeRecoveryCount()).isEqualTo(1);

        // Cleanup
        coordinatorLock.releaseRecoveryLock(partitionId);
    }
}
