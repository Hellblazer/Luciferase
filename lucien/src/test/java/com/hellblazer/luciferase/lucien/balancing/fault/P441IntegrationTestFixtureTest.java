package com.hellblazer.luciferase.lucien.balancing.fault;

import com.hellblazer.luciferase.lucien.balancing.fault.testinfra.IntegrationTestFixture;
import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for IntegrationTestFixture test infrastructure (P4.4.1).
 * <p>
 * Validates test harness setup, distributed forest creation, VON network setup,
 * fault injection, clock management, and cleanup.
 */
class P441IntegrationTestFixtureTest {

    private IntegrationTestFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new IntegrationTestFixture();
    }

    @Test
    void testForestSetup() {
        // When: Setup forest with 3 partitions
        var forest = fixture.setupForestWithPartitions(3);

        // Then: Should create forest with correct partition count
        assertNotNull(forest, "Forest should be created");
        assertEquals(3, forest.getTotalPartitions(), "Should have 3 partitions");
    }

    @Test
    void testVONNetworkSetup() {
        // When: Setup VON network with 5 nodes
        var network = fixture.setupVONNetwork(5);

        // Then: Should create network with correct node count
        assertNotNull(network, "Network should be created");
        assertEquals(5, network.getNodeCount(), "Should have 5 nodes");
    }

    @Test
    void testFaultHandlerConfiguration() {
        // When: Configure fault handler
        var handler = fixture.configureFaultHandler();

        // Then: Should create handler
        assertNotNull(handler, "Handler should be created");
        assertNotNull(handler.getConfiguration(), "Handler should have configuration");
    }

    @Test
    void testPartitionFailureInjection() throws InterruptedException {
        // Given: Forest with 3 partitions and configured handler
        var forest = fixture.setupForestWithPartitions(3);
        fixture.configureFaultHandler();
        var partitionId = forest.getPartitionIds().iterator().next();

        // When: Inject partition failure with 100ms delay
        fixture.injectPartitionFailure(partitionId, 100);

        // Then: Partition should fail after delay
        Thread.sleep(150); // Wait for failure
        assertFalse(
            forest.isPartitionHealthy(partitionId),
            "Partition should be marked unhealthy"
        );
    }

    @Test
    void testClockManagement() {
        // When: Reset clock to specific time
        var testTime = 5000L;
        fixture.resetClock(testTime);

        // Then: Clock should be set to test time
        var clock = fixture.getClock();
        assertNotNull(clock, "Clock should be available");
        assertEquals(testTime, clock.currentTimeMillis(), "Clock should be at test time");
    }

    @Test
    void testTeardownCleanup() {
        // Given: Forest and VON network setup
        var forest = fixture.setupForestWithPartitions(3);
        var network = fixture.setupVONNetwork(5);

        assertNotNull(forest);
        assertNotNull(network);

        // When: Teardown
        fixture.tearDown();

        // Then: Resources should be cleaned up
        assertTrue(fixture.isCleanedUp(), "Resources should be cleaned up");
    }

    @Test
    void testConcurrentSetups() throws InterruptedException {
        // Given: Multiple concurrent fixture instances
        var fixtureCount = 5;
        var latch = new CountDownLatch(fixtureCount);
        var fixtures = new java.util.concurrent.ConcurrentHashMap<Integer, IntegrationTestFixture>();

        var threads = new java.util.ArrayList<Thread>();
        for (var i = 0; i < fixtureCount; i++) {
            var threadId = i;
            var thread = new Thread(() -> {
                var threadFixture = new IntegrationTestFixture();
                fixtures.put(threadId, threadFixture);

                // Setup forest and network
                threadFixture.setupForestWithPartitions(3);
                threadFixture.setupVONNetwork(5);

                latch.countDown();
            });
            threads.add(thread);
            thread.start();
        }

        // When: Wait for all setups to complete
        assertTrue(latch.await(10, TimeUnit.SECONDS), "All setups should complete");

        // Then: All fixtures should be properly initialized
        assertEquals(fixtureCount, fixtures.size(), "All fixtures should be created");
        for (var threadFixture : fixtures.values()) {
            assertNotNull(threadFixture, "Fixture should not be null");
        }

        // Cleanup
        for (var threadFixture : fixtures.values()) {
            threadFixture.tearDown();
        }
    }

    @Test
    void testResetState() {
        // Given: Fixture with forest and network
        var forest1 = fixture.setupForestWithPartitions(3);
        var network1 = fixture.setupVONNetwork(5);

        assertNotNull(forest1);
        assertNotNull(network1);

        // When: Reset state
        fixture.reset();

        // Then: Should be able to setup fresh instances
        var forest2 = fixture.setupForestWithPartitions(4);
        var network2 = fixture.setupVONNetwork(6);

        assertNotNull(forest2);
        assertNotNull(network2);
        assertEquals(4, forest2.getTotalPartitions(), "Should have new partition count");
        assertEquals(6, network2.getNodeCount(), "Should have new node count");
    }

    @Test
    void testSetupForestWithPartitions_InvalidCount() {
        // When/Then: Setup with invalid partition count
        assertThrows(
            IllegalArgumentException.class,
            () -> fixture.setupForestWithPartitions(0),
            "Should reject zero partitions"
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> fixture.setupForestWithPartitions(-1),
            "Should reject negative partitions"
        );
    }

    @Test
    void testSetupVONNetwork_InvalidCount() {
        // When/Then: Setup with invalid node count
        assertThrows(
            IllegalArgumentException.class,
            () -> fixture.setupVONNetwork(0),
            "Should reject zero nodes"
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> fixture.setupVONNetwork(-1),
            "Should reject negative nodes"
        );
    }

    @Test
    void testInjectPartitionFailure_ImmediateFailure() {
        // Given: Forest with partitions and configured handler
        var forest = fixture.setupForestWithPartitions(3);
        fixture.configureFaultHandler();
        var partitionId = forest.getPartitionIds().iterator().next();

        // When: Inject immediate failure (0ms delay)
        fixture.injectPartitionFailure(partitionId, 0);

        // Then: Partition should be marked unhealthy immediately
        assertFalse(
            forest.isPartitionHealthy(partitionId),
            "Partition should be immediately unhealthy"
        );
    }

    @Test
    void testGetClock_DefaultSystemClock() {
        // When: Get clock without reset
        var clock = fixture.getClock();

        // Then: Should return system clock by default
        assertNotNull(clock, "Clock should not be null");
        assertTrue(clock.currentTimeMillis() > 0, "System clock should return positive time");
    }

    @Test
    void testResetClock_MultipleResets() {
        // Given: Initial clock reset
        fixture.resetClock(1000L);
        assertEquals(1000L, fixture.getClock().currentTimeMillis());

        // When: Reset to different time
        fixture.resetClock(5000L);

        // Then: Clock should be at new time
        assertEquals(5000L, fixture.getClock().currentTimeMillis());
    }

    @Test
    void testTearDown_Idempotent() {
        // Given: Fixture with resources
        fixture.setupForestWithPartitions(3);

        // When: Teardown multiple times
        fixture.tearDown();
        fixture.tearDown();
        fixture.tearDown();

        // Then: Should handle multiple teardowns gracefully
        assertTrue(fixture.isCleanedUp(), "Should remain cleaned up");
    }
}
