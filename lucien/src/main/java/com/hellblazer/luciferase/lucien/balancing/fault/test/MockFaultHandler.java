package com.hellblazer.luciferase.lucien.balancing.fault.test;

import com.hellblazer.luciferase.lucien.balancing.fault.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Controllable FaultHandler implementation for testing.
 * <p>
 * Provides programmatic control over partition states, event recording,
 * call counting, and failure simulation. NOT intended for production use.
 * <p>
 * Features:
 * <ul>
 *   <li>Inject status changes without triggering real detection logic</li>
 *   <li>Record all events for verification</li>
 *   <li>Count method invocations (like Mockito verify)</li>
 *   <li>Simulate recovery success/failure</li>
 *   <li>Thread-safe for concurrent test scenarios</li>
 * </ul>
 * <p>
 * Example usage:
 * <pre>{@code
 * var mock = new MockFaultHandler();
 *
 * // Inject status change
 * mock.injectStatusChange(partitionId, PartitionStatus.SUSPECTED);
 *
 * // Verify events
 * var events = mock.getRecordedEvents();
 * assertThat(events).hasSize(1);
 * assertThat(events.get(0).newStatus()).isEqualTo(PartitionStatus.SUSPECTED);
 *
 * // Verify calls
 * assertThat(mock.getCallCount("markHealthy")).isEqualTo(3);
 * }</pre>
 */
public class MockFaultHandler implements FaultHandler {

    private final Map<UUID, PartitionStatus> partitionStates = new ConcurrentHashMap<>();
    private final Map<UUID, MockPartitionView> partitionViews = new ConcurrentHashMap<>();
    private final Map<UUID, PartitionRecovery> recoveryStrategies = new ConcurrentHashMap<>();
    private final List<PartitionChangeEvent> recordedEvents = new CopyOnWriteArrayList<>();
    private final List<Consumer<PartitionChangeEvent>> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, Integer> callCounts = new ConcurrentHashMap<>();
    private final FaultConfiguration configuration;
    private volatile boolean running = false;

    /**
     * Create mock with default configuration.
     */
    public MockFaultHandler() {
        this(FaultConfiguration.defaultConfig());
    }

    /**
     * Create mock with custom configuration.
     *
     * @param configuration fault configuration
     */
    public MockFaultHandler(FaultConfiguration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
    }

    // ===== FaultHandler Interface =====

    @Override
    public PartitionStatus checkHealth(UUID partitionId) {
        incrementCallCount("checkHealth");
        return partitionStates.getOrDefault(partitionId, PartitionStatus.HEALTHY);
    }

    @Override
    public PartitionView getPartitionView(UUID partitionId) {
        incrementCallCount("getPartitionView");
        return partitionViews.computeIfAbsent(partitionId, id ->
            new MockPartitionView()
                .withPartitionId(id)
                .withStatus(partitionStates.getOrDefault(id, PartitionStatus.HEALTHY))
        );
    }

    @Override
    public Subscription subscribeToChanges(Consumer<PartitionChangeEvent> consumer) {
        incrementCallCount("subscribeToChanges");
        listeners.add(consumer);
        return () -> listeners.remove(consumer);
    }

    @Override
    public void markHealthy(UUID partitionId) {
        incrementCallCount("markHealthy");
        transitionStatus(partitionId, PartitionStatus.HEALTHY, "marked healthy");
    }

    @Override
    public void reportBarrierTimeout(UUID partitionId) {
        incrementCallCount("reportBarrierTimeout");
        var currentStatus = partitionStates.getOrDefault(partitionId, PartitionStatus.HEALTHY);
        var newStatus = currentStatus == PartitionStatus.HEALTHY ? PartitionStatus.SUSPECTED : PartitionStatus.FAILED;
        transitionStatus(partitionId, newStatus, "barrier timeout");
    }

    @Override
    public void reportSyncFailure(UUID partitionId) {
        incrementCallCount("reportSyncFailure");
        var currentStatus = partitionStates.getOrDefault(partitionId, PartitionStatus.HEALTHY);
        var newStatus = currentStatus == PartitionStatus.HEALTHY ? PartitionStatus.SUSPECTED : PartitionStatus.FAILED;
        transitionStatus(partitionId, newStatus, "sync failure");
    }

    @Override
    public void reportHeartbeatFailure(UUID partitionId, UUID nodeId) {
        incrementCallCount("reportHeartbeatFailure");
        var currentStatus = partitionStates.getOrDefault(partitionId, PartitionStatus.HEALTHY);
        var newStatus = currentStatus == PartitionStatus.HEALTHY ? PartitionStatus.SUSPECTED : PartitionStatus.FAILED;
        transitionStatus(partitionId, newStatus, "heartbeat failure: node " + nodeId);
    }

    @Override
    public void registerRecovery(UUID partitionId, PartitionRecovery recovery) {
        incrementCallCount("registerRecovery");
        recoveryStrategies.put(partitionId, recovery);
    }

    @Override
    public CompletableFuture<Boolean> initiateRecovery(UUID partitionId) {
        incrementCallCount("initiateRecovery");
        transitionStatus(partitionId, PartitionStatus.RECOVERING, "recovery initiated");

        var recovery = recoveryStrategies.get(partitionId);
        if (recovery == null) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("No recovery registered for partition: " + partitionId)
            );
        }

        // Simulate async recovery
        return CompletableFuture.supplyAsync(() -> {
            try {
                var result = recovery.recover(partitionId, this).join();
                notifyRecoveryComplete(partitionId, result.success());
                return result.success();
            } catch (Exception e) {
                notifyRecoveryComplete(partitionId, false);
                return false;
            }
        });
    }

    @Override
    public void notifyRecoveryComplete(UUID partitionId, boolean success) {
        incrementCallCount("notifyRecoveryComplete");
        var newStatus = success ? PartitionStatus.HEALTHY : PartitionStatus.FAILED;
        transitionStatus(partitionId, newStatus, success ? "recovery succeeded" : "recovery failed");
    }

    @Override
    public FaultConfiguration getConfiguration() {
        incrementCallCount("getConfiguration");
        return configuration;
    }

    @Override
    public FaultMetrics getMetrics(UUID partitionId) {
        incrementCallCount("getMetrics");
        var view = partitionViews.get(partitionId);
        return view != null ? view.metrics() : FaultMetrics.zero();
    }

    @Override
    public FaultMetrics getAggregateMetrics() {
        incrementCallCount("getAggregateMetrics");
        return partitionViews.values().stream()
            .map(MockPartitionView::metrics)
            .reduce((m1, m2) -> new FaultMetrics(
                Math.max(m1.detectionLatencyMs(), m2.detectionLatencyMs()),
                Math.max(m1.recoveryLatencyMs(), m2.recoveryLatencyMs()),
                m1.failureCount() + m2.failureCount(),
                m1.recoveryAttempts() + m2.recoveryAttempts(),
                m1.successfulRecoveries() + m2.successfulRecoveries(),
                m1.failedRecoveries() + m2.failedRecoveries()
            ))
            .orElse(FaultMetrics.zero());
    }

    @Override
    public void start() {
        incrementCallCount("start");
        running = true;
    }

    @Override
    public void stop() {
        incrementCallCount("stop");
        running = false;
    }

    @Override
    public boolean isRunning() {
        incrementCallCount("isRunning");
        return running;
    }

    // ===== Test Control Methods =====

    /**
     * Inject status change without triggering detection logic.
     *
     * @param partitionId partition to change
     * @param newStatus new status
     */
    public void injectStatusChange(UUID partitionId, PartitionStatus newStatus) {
        transitionStatus(partitionId, newStatus, "injected for testing");
    }

    /**
     * Inject status change with custom reason.
     *
     * @param partitionId partition to change
     * @param newStatus new status
     * @param reason change reason
     */
    public void injectStatusChange(UUID partitionId, PartitionStatus newStatus, String reason) {
        transitionStatus(partitionId, newStatus, reason);
    }

    /**
     * Get all recorded events in chronological order.
     *
     * @return unmodifiable list of events
     */
    public List<PartitionChangeEvent> getRecordedEvents() {
        return Collections.unmodifiableList(recordedEvents);
    }

    /**
     * Get events for specific partition.
     *
     * @param partitionId partition to filter
     * @return filtered events
     */
    public List<PartitionChangeEvent> getEventsFor(UUID partitionId) {
        return recordedEvents.stream()
            .filter(e -> e.partitionId().equals(partitionId))
            .toList();
    }

    /**
     * Get number of times a method was called.
     *
     * @param methodName method name (e.g., "markHealthy")
     * @return call count
     */
    public int getCallCount(String methodName) {
        return callCounts.getOrDefault(methodName, 0);
    }

    /**
     * Reset all recorded state (events, call counts, partition states).
     */
    public void reset() {
        recordedEvents.clear();
        callCounts.clear();
        partitionStates.clear();
        partitionViews.clear();
        recoveryStrategies.clear();
        listeners.clear();
        running = false;
    }

    /**
     * Configure partition view for testing.
     *
     * @param partitionId partition identifier
     * @param view custom view
     */
    public void setPartitionView(UUID partitionId, MockPartitionView view) {
        partitionViews.put(partitionId, view);
        partitionStates.put(partitionId, view.status());
    }

    // ===== Internal Helpers =====

    private void transitionStatus(UUID partitionId, PartitionStatus newStatus, String reason) {
        var oldStatus = partitionStates.getOrDefault(partitionId, PartitionStatus.HEALTHY);
        if (oldStatus == newStatus) {
            return; // No change
        }

        partitionStates.put(partitionId, newStatus);

        // Update view
        var view = partitionViews.computeIfAbsent(partitionId, id ->
            new MockPartitionView().withPartitionId(id)
        );
        view.withStatus(newStatus);

        // Record and dispatch event
        var event = new PartitionChangeEvent(
            partitionId,
            oldStatus,
            newStatus,
            System.currentTimeMillis(),
            reason
        );
        recordedEvents.add(event);
        listeners.forEach(listener -> {
            try {
                listener.accept(event);
            } catch (Exception e) {
                // Swallow exceptions from listeners (test code)
            }
        });
    }

    private void incrementCallCount(String methodName) {
        callCounts.merge(methodName, 1, Integer::sum);
    }
}
