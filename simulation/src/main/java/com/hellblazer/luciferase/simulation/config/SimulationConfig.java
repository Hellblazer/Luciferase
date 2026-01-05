package com.hellblazer.luciferase.simulation.config;

import java.util.Properties;

/**
 * Immutable simulation configuration record.
 * <p>
 * Externalizes hardcoded simulation parameters for tuning and observability:
 * <ul>
 *   <li>Ghost layer: TTL (500ms), memory limits (1000 entities)</li>
 *   <li>Bucket timing: interval (100ms)</li>
 *   <li>Health thresholds: NC (0.9), partition risk (0.5)</li>
 *   <li>Performance: frame split threshold (1.2x), latency alerts (100ms)</li>
 * </ul>
 * <p>
 * Usage:
 * <pre>
 * // Use defaults
 * var config = SimulationConfig.defaults();
 *
 * // Load from properties file
 * var props = new Properties();
 * props.load(new FileInputStream("simulation.properties"));
 * var config = SimulationConfig.fromProperties(props);
 *
 * // Build custom configuration
 * var config = SimulationConfig.builder()
 *     .ghostTtlMs(1000)
 *     .ghostMemoryLimit(2000)
 *     .build();
 * </pre>
 * <p>
 * All configurations are validated on construction. Invalid configurations throw
 * {@link IllegalArgumentException}.
 *
 * @param ghostTtlMs              Ghost entity time-to-live in milliseconds (default: 500)
 * @param ghostMemoryLimit        Maximum ghosts per neighbor (default: 1000)
 * @param bucketIntervalMs        Bucket duration in milliseconds (default: 100)
 * @param ncThreshold             NC healthy threshold in range [0, 1] (default: 0.9)
 * @param partitionThreshold      Partition risk threshold in range [0, 1] (default: 0.5)
 * @param frameSplitThreshold     Frame utilization split trigger, > 0 (default: 1.2)
 * @param latencyAlertThresholdMs Ghost latency alert threshold in milliseconds (default: 100)
 * @param ghostTtlBuckets         Derived: TTL in buckets (ghostTtlMs / bucketIntervalMs)
 * @author hal.hildebrand
 */
public record SimulationConfig(
    // Ghost layer configuration
    long ghostTtlMs,
    int ghostMemoryLimit,

    // Bucket timing
    long bucketIntervalMs,

    // Health thresholds
    float ncThreshold,
    float partitionThreshold,

    // Performance thresholds
    float frameSplitThreshold,
    long latencyAlertThresholdMs,

    // Derived values
    int ghostTtlBuckets
) {

    /**
     * Default ghost TTL in milliseconds (5 buckets @ 100ms).
     */
    public static final long DEFAULT_GHOST_TTL_MS = 500L;

    /**
     * Default maximum ghosts per neighbor (memory limit).
     */
    public static final int DEFAULT_GHOST_MEMORY_LIMIT = 1000;

    /**
     * Default bucket interval in milliseconds.
     */
    public static final long DEFAULT_BUCKET_INTERVAL_MS = 100L;

    /**
     * Default NC threshold for healthy operation (90% neighbor coverage).
     */
    public static final float DEFAULT_NC_THRESHOLD = 0.9f;

    /**
     * Default partition risk threshold (50% neighbor coverage).
     */
    public static final float DEFAULT_PARTITION_THRESHOLD = 0.5f;

    /**
     * Default frame split threshold (120% of target frame time).
     */
    public static final float DEFAULT_FRAME_SPLIT_THRESHOLD = 1.2f;

    /**
     * Default latency alert threshold in milliseconds.
     */
    public static final long DEFAULT_LATENCY_ALERT_THRESHOLD_MS = 100L;

    /**
     * Compact constructor with validation.
     */
    public SimulationConfig {
        validate(ghostTtlMs, ghostMemoryLimit, bucketIntervalMs, ncThreshold,
                 partitionThreshold, frameSplitThreshold, latencyAlertThresholdMs, ghostTtlBuckets);
    }

    /**
     * Create default configuration matching current hardcoded values.
     *
     * @return Default SimulationConfig
     */
    public static SimulationConfig defaults() {
        var ghostTtlBuckets = (int) (DEFAULT_GHOST_TTL_MS / DEFAULT_BUCKET_INTERVAL_MS);
        return new SimulationConfig(
            DEFAULT_GHOST_TTL_MS,
            DEFAULT_GHOST_MEMORY_LIMIT,
            DEFAULT_BUCKET_INTERVAL_MS,
            DEFAULT_NC_THRESHOLD,
            DEFAULT_PARTITION_THRESHOLD,
            DEFAULT_FRAME_SPLIT_THRESHOLD,
            DEFAULT_LATENCY_ALERT_THRESHOLD_MS,
            ghostTtlBuckets
        );
    }

    /**
     * Load configuration from Properties.
     * <p>
     * Property keys:
     * <ul>
     *   <li>ghost.ttl.ms</li>
     *   <li>ghost.memory.limit</li>
     *   <li>bucket.interval.ms</li>
     *   <li>nc.threshold</li>
     *   <li>partition.threshold</li>
     *   <li>frame.split.threshold</li>
     *   <li>latency.alert.threshold.ms</li>
     * </ul>
     * <p>
     * Missing properties use default values.
     *
     * @param props Properties to load from
     * @return SimulationConfig loaded from properties
     * @throws IllegalArgumentException if properties contain invalid values
     */
    public static SimulationConfig fromProperties(Properties props) {
        var ghostTtlMs = Long.parseLong(props.getProperty("ghost.ttl.ms",
                                                           String.valueOf(DEFAULT_GHOST_TTL_MS)));
        var ghostMemoryLimit = Integer.parseInt(props.getProperty("ghost.memory.limit",
                                                                   String.valueOf(DEFAULT_GHOST_MEMORY_LIMIT)));
        var bucketIntervalMs = Long.parseLong(props.getProperty("bucket.interval.ms",
                                                                 String.valueOf(DEFAULT_BUCKET_INTERVAL_MS)));
        var ncThreshold = Float.parseFloat(props.getProperty("nc.threshold",
                                                              String.valueOf(DEFAULT_NC_THRESHOLD)));
        var partitionThreshold = Float.parseFloat(props.getProperty("partition.threshold",
                                                                     String.valueOf(DEFAULT_PARTITION_THRESHOLD)));
        var frameSplitThreshold = Float.parseFloat(props.getProperty("frame.split.threshold",
                                                                      String.valueOf(DEFAULT_FRAME_SPLIT_THRESHOLD)));
        var latencyAlertThresholdMs = Long.parseLong(props.getProperty("latency.alert.threshold.ms",
                                                                        String.valueOf(DEFAULT_LATENCY_ALERT_THRESHOLD_MS)));

        var ghostTtlBuckets = (int) (ghostTtlMs / bucketIntervalMs);

        return new SimulationConfig(
            ghostTtlMs,
            ghostMemoryLimit,
            bucketIntervalMs,
            ncThreshold,
            partitionThreshold,
            frameSplitThreshold,
            latencyAlertThresholdMs,
            ghostTtlBuckets
        );
    }

    /**
     * Create a builder for custom configurations.
     *
     * @return New Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Validate this configuration.
     * <p>
     * Validation rules:
     * <ul>
     *   <li>ghostTtlMs > 0</li>
     *   <li>ghostMemoryLimit > 0</li>
     *   <li>bucketIntervalMs > 0</li>
     *   <li>ncThreshold in [0, 1]</li>
     *   <li>partitionThreshold in [0, 1]</li>
     *   <li>frameSplitThreshold > 0</li>
     *   <li>latencyAlertThresholdMs > 0</li>
     *   <li>ghostTtlBuckets > 0 (ghostTtlMs must be >= bucketIntervalMs)</li>
     * </ul>
     *
     * @throws IllegalArgumentException if validation fails
     */
    public void validate() {
        validate(ghostTtlMs, ghostMemoryLimit, bucketIntervalMs, ncThreshold,
                 partitionThreshold, frameSplitThreshold, latencyAlertThresholdMs, ghostTtlBuckets);
    }

    /**
     * Internal validation logic.
     */
    private static void validate(long ghostTtlMs, int ghostMemoryLimit, long bucketIntervalMs,
                                  float ncThreshold, float partitionThreshold, float frameSplitThreshold,
                                  long latencyAlertThresholdMs, int ghostTtlBuckets) {
        if (ghostTtlMs <= 0) {
            throw new IllegalArgumentException("ghostTtlMs must be > 0, got: " + ghostTtlMs);
        }
        if (ghostMemoryLimit <= 0) {
            throw new IllegalArgumentException("ghostMemoryLimit must be > 0, got: " + ghostMemoryLimit);
        }
        if (bucketIntervalMs <= 0) {
            throw new IllegalArgumentException("bucketIntervalMs must be > 0, got: " + bucketIntervalMs);
        }
        if (ncThreshold < 0.0f || ncThreshold > 1.0f) {
            throw new IllegalArgumentException("ncThreshold must be in [0, 1], got: " + ncThreshold);
        }
        if (partitionThreshold < 0.0f || partitionThreshold > 1.0f) {
            throw new IllegalArgumentException("partitionThreshold must be in [0, 1], got: " + partitionThreshold);
        }
        if (frameSplitThreshold <= 0.0f) {
            throw new IllegalArgumentException("frameSplitThreshold must be > 0, got: " + frameSplitThreshold);
        }
        if (latencyAlertThresholdMs <= 0) {
            throw new IllegalArgumentException("latencyAlertThresholdMs must be > 0, got: " + latencyAlertThresholdMs);
        }
        if (ghostTtlBuckets <= 0) {
            throw new IllegalArgumentException(
                "ghostTtlBuckets must be > 0 (ghostTtlMs must be >= bucketIntervalMs), " +
                "got: ghostTtlMs=" + ghostTtlMs + ", bucketIntervalMs=" + bucketIntervalMs +
                ", ghostTtlBuckets=" + ghostTtlBuckets
            );
        }
    }

    /**
     * Builder for custom SimulationConfig instances.
     * <p>
     * Provides fluent API with default values. Call {@link #build()} to create
     * the validated configuration.
     */
    public static class Builder {
        private long ghostTtlMs = DEFAULT_GHOST_TTL_MS;
        private int ghostMemoryLimit = DEFAULT_GHOST_MEMORY_LIMIT;
        private long bucketIntervalMs = DEFAULT_BUCKET_INTERVAL_MS;
        private float ncThreshold = DEFAULT_NC_THRESHOLD;
        private float partitionThreshold = DEFAULT_PARTITION_THRESHOLD;
        private float frameSplitThreshold = DEFAULT_FRAME_SPLIT_THRESHOLD;
        private long latencyAlertThresholdMs = DEFAULT_LATENCY_ALERT_THRESHOLD_MS;

        /**
         * Set ghost TTL in milliseconds.
         *
         * @param ghostTtlMs Ghost TTL (must be > 0)
         * @return This builder
         */
        public Builder ghostTtlMs(long ghostTtlMs) {
            this.ghostTtlMs = ghostTtlMs;
            return this;
        }

        /**
         * Set ghost memory limit.
         *
         * @param ghostMemoryLimit Max ghosts per neighbor (must be > 0)
         * @return This builder
         */
        public Builder ghostMemoryLimit(int ghostMemoryLimit) {
            this.ghostMemoryLimit = ghostMemoryLimit;
            return this;
        }

        /**
         * Set bucket interval in milliseconds.
         *
         * @param bucketIntervalMs Bucket duration (must be > 0)
         * @return This builder
         */
        public Builder bucketIntervalMs(long bucketIntervalMs) {
            this.bucketIntervalMs = bucketIntervalMs;
            return this;
        }

        /**
         * Set NC threshold.
         *
         * @param ncThreshold NC healthy threshold (must be in [0, 1])
         * @return This builder
         */
        public Builder ncThreshold(float ncThreshold) {
            this.ncThreshold = ncThreshold;
            return this;
        }

        /**
         * Set partition threshold.
         *
         * @param partitionThreshold Partition risk threshold (must be in [0, 1])
         * @return This builder
         */
        public Builder partitionThreshold(float partitionThreshold) {
            this.partitionThreshold = partitionThreshold;
            return this;
        }

        /**
         * Set frame split threshold.
         *
         * @param frameSplitThreshold Frame utilization split trigger (must be > 0)
         * @return This builder
         */
        public Builder frameSplitThreshold(float frameSplitThreshold) {
            this.frameSplitThreshold = frameSplitThreshold;
            return this;
        }

        /**
         * Set latency alert threshold in milliseconds.
         *
         * @param latencyAlertThresholdMs Latency alert threshold (must be > 0)
         * @return This builder
         */
        public Builder latencyAlertThresholdMs(long latencyAlertThresholdMs) {
            this.latencyAlertThresholdMs = latencyAlertThresholdMs;
            return this;
        }

        /**
         * Build and validate the SimulationConfig.
         *
         * @return Validated SimulationConfig
         * @throws IllegalArgumentException if configuration is invalid
         */
        public SimulationConfig build() {
            var ghostTtlBuckets = (int) (ghostTtlMs / bucketIntervalMs);
            return new SimulationConfig(
                ghostTtlMs,
                ghostMemoryLimit,
                bucketIntervalMs,
                ncThreshold,
                partitionThreshold,
                frameSplitThreshold,
                latencyAlertThresholdMs,
                ghostTtlBuckets
            );
        }
    }
}
