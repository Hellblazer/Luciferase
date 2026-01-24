package com.hellblazer.luciferase.lucien.balancing.fault.testinfra;

import java.util.Objects;

/**
 * Service Level Agreement (SLA) for latency requirements.
 * <p>
 * Defines acceptable latency thresholds for performance validation.
 * Used by {@link ConfigurableValidator} to validate performance metrics.
 *
 * @param p50MaxMs Maximum acceptable P50 latency in milliseconds
 * @param p95MaxMs Maximum acceptable P95 latency in milliseconds
 * @param p99MaxMs Maximum acceptable P99 latency in milliseconds
 * @param maxLatencyMs Hard upper bound on any single operation
 */
public record LatencySLA(
    long p50MaxMs,
    long p95MaxMs,
    long p99MaxMs,
    long maxLatencyMs
) {
    /**
     * Compact constructor with validation.
     */
    public LatencySLA {
        if (p50MaxMs <= 0 || p95MaxMs <= 0 || p99MaxMs <= 0 || maxLatencyMs <= 0) {
            throw new IllegalArgumentException("All latency thresholds must be positive");
        }
        if (p50MaxMs > p95MaxMs || p95MaxMs > p99MaxMs || p99MaxMs > maxLatencyMs) {
            throw new IllegalArgumentException(
                "Latency thresholds must be in order: p50 <= p95 <= p99 <= max"
            );
        }
    }

    /**
     * Create default SLA for standard operations.
     * <p>
     * P50=10ms, P95=50ms, P99=100ms, max=500ms
     *
     * @return default LatencySLA
     */
    public static LatencySLA standard() {
        return new LatencySLA(10, 50, 100, 500);
    }

    /**
     * Create strict SLA for low-latency requirements.
     * <p>
     * P50=5ms, P95=20ms, P99=50ms, max=100ms
     *
     * @return strict LatencySLA
     */
    public static LatencySLA strict() {
        return new LatencySLA(5, 20, 50, 100);
    }

    /**
     * Create relaxed SLA for high-latency tolerance.
     * <p>
     * P50=50ms, P95=200ms, P99=500ms, max=2000ms
     *
     * @return relaxed LatencySLA
     */
    public static LatencySLA relaxed() {
        return new LatencySLA(50, 200, 500, 2000);
    }
}
