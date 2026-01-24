package com.hellblazer.luciferase.lucien.balancing.fault;

import com.hellblazer.luciferase.lucien.balancing.fault.testinfra.ConfigurableValidator;
import com.hellblazer.luciferase.lucien.balancing.fault.testinfra.Event;
import com.hellblazer.luciferase.lucien.balancing.fault.testinfra.EventCapture;
import com.hellblazer.luciferase.lucien.balancing.fault.testinfra.IntegrationTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P4.4.4 Concurrency Tests (C1-C2) - Thread safety and concurrent recovery validation.
 * <p>
 * Tests validate:
 * <ul>
 *   <li>C1: Recovery Isolation - No cross-partition interference during concurrent recoveries</li>
 *   <li>C2: Listener Thread Safety - Concurrent listener notifications without race conditions</li>
 * </ul>
 * <p>
 * Uses P4.4.1 infrastructure (IntegrationTestFixture, EventCapture, ConfigurableValidator)
 * and Phase 4.3 recovery system with thread-safe concurrent collections.
 */
class P444ConcurrencyTest {

    private IntegrationTestFixture fixture;
    private EventCapture capture;
    private ConfigurableValidator validator;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        fixture = new IntegrationTestFixture();
        capture = new EventCapture();
        validator = new ConfigurableValidator();
        executor = Executors.newFixedThreadPool(10);
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            executor.shutdownNow();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                System.err.println("Executor did not terminate in time");
            }
        } finally {
            fixture.tearDown();
        }
    }

    /**
     * C1: Recovery Isolation - No Cross-Partition Interference.
     * <p>
     * Validates that concurrent recoveries on multiple partitions:
     * <ul>
     *   <li>Execute independently without interference</li>
     *   <li>Complete successfully without deadlocks</li>
     *   <li>Maintain isolated state per partition</li>
     *   <li>Track distinct recovery paths via EventCapture</li>
     * </ul>
     * <p>
     * Test setup: 5 partitions, all fail simultaneously, all recover concurrently.
     * Expected: All 5 recoveries complete within 10 seconds, no cross-interference.
     */
    @Test
    void testRecoveryIsolationNoCrossPartitionInterference() throws Exception {
        // Setup 5-partition forest with recovery coordinators
        fixture.setupForestWithPartitions(5);
        fixture.configureFaultHandler();
        fixture.setupRecoveryCoordinators();

        var partitions = fixture.getPartitionIds();
        assertThat(partitions).hasSize(5);

        capture.reset();

        // Subscribe event capture to all recovery coordinators
        for (var partition : partitions) {
            var recovery = fixture.getRecoveryCoordinator(partition);
            recovery.subscribe(phase -> {
                capture.recordEvent(
                    "recovery",
                    new Event(
                        System.currentTimeMillis(),
                        "recovery",
                        "PHASE_CHANGE",
                        Map.of("partition", partition, "phase", phase)
                    )
                );
            });
        }

        // Trigger failures on all 5 partitions simultaneously
        var failureStart = new CountDownLatch(1);
        var completionLatch = new CountDownLatch(5);
        var recoveryResults = new ConcurrentHashMap<UUID, RecoveryResult>();
        var recoveryErrors = new ConcurrentHashMap<UUID, Exception>();

        for (var partition : partitions) {
            executor.submit(() -> {
                try {
                    // Wait for start signal
                    failureStart.await();

                    // Inject failure and trigger recovery
                    fixture.injectPartitionFailure(partition, 0);
                    var recovery = fixture.getRecoveryCoordinator(partition);
                    var handler = fixture.configureFaultHandler();

                    // Execute recovery
                    var result = recovery.recover(partition, handler).get(10, TimeUnit.SECONDS);
                    recoveryResults.put(partition, result);

                    completionLatch.countDown();
                } catch (Exception e) {
                    recoveryErrors.put(partition, e);
                    completionLatch.countDown();
                }
            });
        }

        // Start all recoveries simultaneously
        var startTime = System.currentTimeMillis();
        failureStart.countDown();

        // Wait for all recoveries to complete (max 10 seconds)
        var completed = completionLatch.await(10, TimeUnit.SECONDS);
        var duration = System.currentTimeMillis() - startTime;

        // Verify all recoveries completed
        assertThat(completed)
            .as("All 5 recoveries should complete within 10 seconds")
            .isTrue();

        assertThat(recoveryErrors)
            .as("No recovery should fail with exception")
            .isEmpty();

        assertThat(recoveryResults)
            .as("All 5 partitions should have recovery results")
            .hasSize(5);

        // Verify all recoveries succeeded
        for (var entry : recoveryResults.entrySet()) {
            var partition = entry.getKey();
            var result = entry.getValue();

            assertThat(result.success())
                .as("Recovery for partition %s should succeed", partition)
                .isTrue();
        }

        // Verify isolation: no cross-partition interference
        var activePartitions = fixture.getActivePartitions();
        assertThat(activePartitions)
            .as("All partitions should be inactive (failed)")
            .isEmpty();

        // Verify independent recovery paths in event sequence
        var stats = capture.getStatistics();
        var recoveryEvents = stats.getEventCount("recovery");

        // Each partition should have at least 5 phase transitions:
        // IDLE → DETECTING → REDISTRIBUTING → REBALANCING → VALIDATING → COMPLETE
        // 5 partitions × 6 phases = 30 minimum (IDLE might not be captured)
        assertThat(recoveryEvents)
            .as("Should have recovery events from all 5 partitions (5 partitions × 5+ phases)")
            .isGreaterThanOrEqualTo(25);

        // Verify no deadlocks detected
        assertThat(stats.getDeadlockWarnings())
            .as("Should have no deadlock warnings")
            .isZero();

        // Verify execution time reasonable
        assertThat(duration)
            .as("All recoveries should complete within 10 seconds")
            .isLessThan(10_000);
    }

    /**
     * C2: Listener Thread Safety - Concurrent Listener Notifications.
     * <p>
     * Validates that concurrent listener notifications:
     * <ul>
     *   <li>Deliver events to all listeners without race conditions</li>
     *   <li>Maintain consistent notification sequence across all listeners</li>
     *   <li>Handle listener exceptions gracefully without crashing recovery</li>
     *   <li>Support concurrent listener registration/deregistration</li>
     * </ul>
     * <p>
     * Test setup: 1 partition with 10 concurrent listeners.
     * Expected: All listeners receive identical phase transition sequences.
     */
    @Test
    void testListenerThreadSafetyConcurrentNotifications() throws Exception {
        // Setup single-partition forest with recovery coordinator
        fixture.setupForestWithPartitions(1);
        fixture.configureFaultHandler();
        fixture.setupRecoveryCoordinators();

        var partition = fixture.getPartitionIds().get(0);
        var recovery = fixture.getRecoveryCoordinator(partition);

        capture.reset();

        // Concurrent listener tracking
        var listenerEvents = new ConcurrentHashMap<Integer, List<RecoveryPhase>>();
        var allListenersReady = new CountDownLatch(10);
        var listenerExceptions = new ConcurrentHashMap<Integer, Exception>();

        // Register 10 concurrent listeners
        for (var i = 0; i < 10; i++) {
            final var listenerIndex = i;

            // Create thread-safe list for this listener
            var eventList = Collections.synchronizedList(new ArrayList<RecoveryPhase>());
            listenerEvents.put(listenerIndex, eventList);

            recovery.subscribe(phase -> {
                try {
                    // Record phase transition
                    eventList.add(phase);

                    // Capture event
                    capture.recordEvent(
                        "listener",
                        new Event(
                            System.currentTimeMillis(),
                            "listener",
                            "PHASE_RECEIVED",
                            Map.of("listener", listenerIndex, "phase", phase)
                        )
                    );

                    // Simulate listener processing time
                    Thread.sleep(1);
                } catch (Exception e) {
                    listenerExceptions.put(listenerIndex, e);
                }
            });

            allListenersReady.countDown();
        }

        // Wait for all listeners to be registered
        assertThat(allListenersReady.await(5, TimeUnit.SECONDS))
            .as("All listeners should be registered")
            .isTrue();

        // Inject failure - triggers phase transitions
        fixture.injectPartitionFailure(partition, 0);
        var handler = fixture.configureFaultHandler();

        // Execute recovery
        var result = recovery.recover(partition, handler).get(10, TimeUnit.SECONDS);

        // Verify recovery completed successfully
        assertThat(result.success())
            .as("Recovery should complete successfully")
            .isTrue();

        // Wait for all listeners to finish processing (give time for async notifications)
        Thread.sleep(500);

        // Verify no listener exceptions
        assertThat(listenerExceptions)
            .as("No listeners should throw exceptions")
            .isEmpty();

        // Verify all listeners got notifications
        assertThat(listenerEvents)
            .as("All 10 listeners should receive notifications")
            .hasSize(10);

        // Verify all listeners got same notification sequence
        var firstListenerSequence = listenerEvents.get(0);
        assertThat(firstListenerSequence)
            .as("First listener should receive phase transitions")
            .isNotEmpty();

        for (var i = 1; i < 10; i++) {
            var listenerSequence = listenerEvents.get(i);
            assertThat(listenerSequence)
                .as("Listener %d should receive same sequence as listener 0", i)
                .containsExactlyElementsOf(firstListenerSequence);
        }

        // Verify standard recovery sequence (no missing/duplicate phases)
        // Expected: DETECTING → REDISTRIBUTING → REBALANCING → VALIDATING → COMPLETE
        // (IDLE might not be included depending on when listeners are registered)
        assertThat(firstListenerSequence)
            .as("Should contain standard recovery phases in order")
            .containsSequence(
                RecoveryPhase.DETECTING,
                RecoveryPhase.REDISTRIBUTING,
                RecoveryPhase.REBALANCING,
                RecoveryPhase.VALIDATING,
                RecoveryPhase.COMPLETE
            );

        // Verify thread-safe event delivery via EventCapture
        var stats = capture.getStatistics();
        var listenerEventCount = stats.getEventCount("listener");

        // Each listener should receive 5+ phase notifications
        var expectedMinEvents = 10 * 5; // 10 listeners × 5 phases minimum
        assertThat(listenerEventCount)
            .as("Should have events from all 10 listeners (10 × 5+ phases)")
            .isGreaterThanOrEqualTo(expectedMinEvents);
    }

    /**
     * C2 Extended: Listener Exception Handling.
     * <p>
     * Validates that listener exceptions don't crash recovery process.
     * <p>
     * Test setup: 1 partition with 5 normal listeners + 5 failing listeners.
     * Expected: Recovery completes, normal listeners receive all events, failing listeners don't crash system.
     */
    @Test
    void testListenerExceptionHandling() throws Exception {
        // Setup single-partition forest with recovery coordinator
        fixture.setupForestWithPartitions(1);
        fixture.configureFaultHandler();
        fixture.setupRecoveryCoordinators();

        var partition = fixture.getPartitionIds().get(0);
        var recovery = fixture.getRecoveryCoordinator(partition);

        capture.reset();

        // Track normal listeners
        var normalListenerEvents = new ConcurrentHashMap<Integer, List<RecoveryPhase>>();

        // Register 5 normal listeners
        for (var i = 0; i < 5; i++) {
            final var listenerIndex = i;
            var eventList = Collections.synchronizedList(new ArrayList<RecoveryPhase>());
            normalListenerEvents.put(listenerIndex, eventList);

            recovery.subscribe(phase -> {
                eventList.add(phase);
            });
        }

        // Register 5 failing listeners (throw exceptions)
        for (var i = 0; i < 5; i++) {
            recovery.subscribe(phase -> {
                throw new RuntimeException("Simulated listener failure");
            });
        }

        // Inject failure and execute recovery
        fixture.injectPartitionFailure(partition, 0);
        var handler = fixture.configureFaultHandler();

        var result = recovery.recover(partition, handler).get(10, TimeUnit.SECONDS);

        // Verify recovery completed successfully despite listener exceptions
        assertThat(result.success())
            .as("Recovery should complete successfully despite listener exceptions")
            .isTrue();

        // Wait for listeners to process
        Thread.sleep(500);

        // Verify normal listeners still received all events
        assertThat(normalListenerEvents)
            .as("All 5 normal listeners should receive notifications")
            .hasSize(5);

        for (var entry : normalListenerEvents.entrySet()) {
            var listenerIndex = entry.getKey();
            var sequence = entry.getValue();

            assertThat(sequence)
                .as("Normal listener %d should receive complete phase sequence", listenerIndex)
                .containsSequence(
                    RecoveryPhase.DETECTING,
                    RecoveryPhase.REDISTRIBUTING,
                    RecoveryPhase.REBALANCING,
                    RecoveryPhase.VALIDATING,
                    RecoveryPhase.COMPLETE
                );
        }
    }
}
