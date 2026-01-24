package com.hellblazer.luciferase.lucien.balancing.fault.testinfra;

/**
 * Performance SLA specification for E2E validation.
 * <p>
 * Defines maximum latency and minimum throughput thresholds that
 * system must meet for test to pass.
 *
 * @param maxLatencyMs maximum acceptable latency in milliseconds
 * @param minThroughput minimum acceptable throughput (operations per second)
 */
public record PerformanceSLA(
    long maxLatencyMs,
    double minThroughput
) {
    /**
     * Compact constructor with validation.
     */
    public PerformanceSLA {
        if (maxLatencyMs <= 0) {
            throw new IllegalArgumentException("maxLatencyMs must be positive, got: " + maxLatencyMs);
        }
        if (minThroughput < 0.0) {
            throw new IllegalArgumentException("minThroughput must be non-negative, got: " + minThroughput);
        }
    }

    /**
     * Create strict SLA (low latency, high throughput).
     *
     * @return strict SLA instance
     */
    public static PerformanceSLA strict() {
        return new PerformanceSLA(1000, 100.0); // 1s max latency, 100 ops/s min
    }

    /**
     * Create moderate SLA (balanced requirements).
     *
     * @return moderate SLA instance
     */
    public static PerformanceSLA moderate() {
        return new PerformanceSLA(3000, 50.0); // 3s max latency, 50 ops/s min
    }

    /**
     * Create relaxed SLA (generous requirements).
     *
     * @return relaxed SLA instance
     */
    public static PerformanceSLA relaxed() {
        return new PerformanceSLA(10000, 10.0); // 10s max latency, 10 ops/s min
    }

    /**
     * Create custom SLA.
     *
     * @param maxLatencyMs maximum latency in milliseconds
     * @param minThroughput minimum throughput (ops/s)
     * @return custom SLA instance
     */
    public static PerformanceSLA custom(long maxLatencyMs, double minThroughput) {
        return new PerformanceSLA(maxLatencyMs, minThroughput);
    }
}
