package com.hellblazer.luciferase.lucien.balancing.fault.test;

import com.hellblazer.luciferase.lucien.balancing.fault.FaultMetrics;
import com.hellblazer.luciferase.lucien.balancing.fault.PartitionStatus;
import com.hellblazer.luciferase.lucien.balancing.fault.PartitionView;

import java.util.Objects;
import java.util.UUID;

/**
 * Mutable PartitionView implementation for testing.
 * <p>
 * Provides a fluent builder-style API for configuring partition state in tests.
 * Unlike production PartitionView implementations (which are immutable snapshots),
 * this mock allows direct state manipulation for testing fault scenarios.
 * <p>
 * <b>Thread Safety</b>: Not thread-safe. Intended for single-threaded test use.
 * <p>
 * Example usage:
 * <pre>{@code
 * var view = new MockPartitionView()
 *     .withPartitionId(UUID.randomUUID())
 *     .withStatus(PartitionStatus.SUSPECTED)
 *     .withNodeCount(5)
 *     .withHealthyNodes(3)
 *     .withLastSeenMs(System.currentTimeMillis() - 5000);
 *
 * assertThat(view.status()).isEqualTo(PartitionStatus.SUSPECTED);
 * assertThat(view.healthyNodes()).isEqualTo(3);
 * }</pre>
 */
public class MockPartitionView implements PartitionView {

    private UUID partitionId;
    private PartitionStatus status;
    private long lastSeenMs;
    private int nodeCount;
    private int healthyNodes;
    private FaultMetrics metrics;

    /**
     * Create a new mock with default values.
     */
    public MockPartitionView() {
        this.partitionId = UUID.randomUUID();
        this.status = PartitionStatus.HEALTHY;
        this.lastSeenMs = System.currentTimeMillis();
        this.nodeCount = 1;
        this.healthyNodes = 1;
        this.metrics = FaultMetrics.zero();
    }

    @Override
    public UUID partitionId() {
        return partitionId;
    }

    @Override
    public PartitionStatus status() {
        return status;
    }

    @Override
    public long lastSeenMs() {
        return lastSeenMs;
    }

    @Override
    public int nodeCount() {
        return nodeCount;
    }

    @Override
    public int healthyNodes() {
        return healthyNodes;
    }

    @Override
    public FaultMetrics metrics() {
        return metrics;
    }

    /**
     * Set the partition ID.
     *
     * @param partitionId partition UUID
     * @return this mock for chaining
     */
    public MockPartitionView withPartitionId(UUID partitionId) {
        this.partitionId = Objects.requireNonNull(partitionId, "partitionId must not be null");
        return this;
    }

    /**
     * Set the partition status.
     *
     * @param status partition status
     * @return this mock for chaining
     */
    public MockPartitionView withStatus(PartitionStatus status) {
        this.status = Objects.requireNonNull(status, "status must not be null");
        return this;
    }

    /**
     * Set the last seen timestamp.
     *
     * @param lastSeenMs milliseconds since epoch
     * @return this mock for chaining
     */
    public MockPartitionView withLastSeenMs(long lastSeenMs) {
        this.lastSeenMs = lastSeenMs;
        return this;
    }

    /**
     * Set the total node count.
     *
     * @param nodeCount total nodes
     * @return this mock for chaining
     */
    public MockPartitionView withNodeCount(int nodeCount) {
        if (nodeCount < 0) {
            throw new IllegalArgumentException("nodeCount must be non-negative, got: " + nodeCount);
        }
        this.nodeCount = nodeCount;
        return this;
    }

    /**
     * Set the healthy node count.
     *
     * @param healthyNodes healthy node count
     * @return this mock for chaining
     */
    public MockPartitionView withHealthyNodes(int healthyNodes) {
        if (healthyNodes < 0) {
            throw new IllegalArgumentException("healthyNodes must be non-negative, got: " + healthyNodes);
        }
        this.healthyNodes = healthyNodes;
        return this;
    }

    /**
     * Set the fault metrics.
     *
     * @param metrics fault metrics
     * @return this mock for chaining
     */
    public MockPartitionView withMetrics(FaultMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        return this;
    }

    /**
     * Simulate partition failure by setting status to FAILED and healthy nodes to 0.
     *
     * @return this mock for chaining
     */
    public MockPartitionView simulateFailure() {
        this.status = PartitionStatus.FAILED;
        this.healthyNodes = 0;
        return this;
    }

    /**
     * Simulate partition recovery by setting status to RECOVERING.
     *
     * @return this mock for chaining
     */
    public MockPartitionView simulateRecovery() {
        this.status = PartitionStatus.RECOVERING;
        return this;
    }

    /**
     * Simulate stale heartbeat by setting lastSeenMs to past timestamp.
     *
     * @param ageMs how old the last seen timestamp should be (milliseconds)
     * @return this mock for chaining
     */
    public MockPartitionView simulateStaleHeartbeat(long ageMs) {
        this.lastSeenMs = System.currentTimeMillis() - ageMs;
        return this;
    }

    @Override
    public String toString() {
        return "MockPartitionView{" +
               "partitionId=" + partitionId +
               ", status=" + status +
               ", lastSeenMs=" + lastSeenMs +
               ", nodeCount=" + nodeCount +
               ", healthyNodes=" + healthyNodes +
               ", metrics=" + metrics +
               '}';
    }
}
