package com.hellblazer.luciferase.simulation.bubble;

/**
 * Configuration for bubble dynamics thresholds.
 * <p>
 * Defines thresholds for:
 * - **Merge**: Cross-bubble affinity must exceed this to trigger merge
 * - **Drift**: Entity affinity below this triggers transfer to better bubble
 * - **Partition**: Neighbor Consistency (NC) below this indicates partition
 * - **Recovery**: NC above this indicates partition has recovered
 * <p>
 * Default values:
 * <pre>
 * mergeThreshold     = 0.6f  (60% cross-bubble affinity)
 * driftThreshold     = 0.5f  (50% entity affinity)
 * partitionThreshold = 0.5f  (50% neighbor consistency)
 * recoveryThreshold  = 0.9f  (90% neighbor consistency)
 * </pre>
 * <p>
 * Usage:
 * <pre>
 * // Use defaults
 * var config = BubbleDynamicsConfig.defaults();
 *
 * // Custom thresholds
 * var config = new BubbleDynamicsConfig(0.7f, 0.4f, 0.6f, 0.95f);
 *
 * // Builder pattern for selective override
 * var config = BubbleDynamicsConfig.defaults()
 *     .withMergeThreshold(0.7f)
 *     .withRecoveryThreshold(0.95f);
 * </pre>
 *
 * @param mergeThreshold     Cross-bubble affinity threshold for merge (0.0-1.0)
 * @param driftThreshold     Entity affinity threshold for transfer (0.0-1.0)
 * @param partitionThreshold NC threshold below which partition is detected (0.0-1.0)
 * @param recoveryThreshold  NC threshold above which partition is recovered (0.0-1.0)
 * @author hal.hildebrand
 */
public record BubbleDynamicsConfig(
    float mergeThreshold,
    float driftThreshold,
    float partitionThreshold,
    float recoveryThreshold
) {

    /**
     * Default merge threshold: 60% cross-bubble affinity.
     */
    public static final float DEFAULT_MERGE_THRESHOLD = 0.6f;

    /**
     * Default drift threshold: 50% entity affinity.
     */
    public static final float DEFAULT_DRIFT_THRESHOLD = 0.5f;

    /**
     * Default partition threshold: 50% NC.
     */
    public static final float DEFAULT_PARTITION_THRESHOLD = 0.5f;

    /**
     * Default recovery threshold: 90% NC.
     */
    public static final float DEFAULT_RECOVERY_THRESHOLD = 0.9f;

    /**
     * Compact constructor for validation.
     */
    public BubbleDynamicsConfig {
        if (mergeThreshold < 0.0f || mergeThreshold > 1.0f) {
            throw new IllegalArgumentException("mergeThreshold must be in [0.0, 1.0]");
        }
        if (driftThreshold < 0.0f || driftThreshold > 1.0f) {
            throw new IllegalArgumentException("driftThreshold must be in [0.0, 1.0]");
        }
        if (partitionThreshold < 0.0f || partitionThreshold > 1.0f) {
            throw new IllegalArgumentException("partitionThreshold must be in [0.0, 1.0]");
        }
        if (recoveryThreshold < 0.0f || recoveryThreshold > 1.0f) {
            throw new IllegalArgumentException("recoveryThreshold must be in [0.0, 1.0]");
        }
        if (recoveryThreshold < partitionThreshold) {
            throw new IllegalArgumentException(
                "recoveryThreshold must be >= partitionThreshold to avoid oscillation");
        }
    }

    /**
     * Create configuration with default thresholds.
     *
     * @return Default configuration
     */
    public static BubbleDynamicsConfig defaults() {
        return new BubbleDynamicsConfig(
            DEFAULT_MERGE_THRESHOLD,
            DEFAULT_DRIFT_THRESHOLD,
            DEFAULT_PARTITION_THRESHOLD,
            DEFAULT_RECOVERY_THRESHOLD
        );
    }

    /**
     * Create new config with different merge threshold.
     *
     * @param threshold New merge threshold
     * @return New config with updated threshold
     */
    public BubbleDynamicsConfig withMergeThreshold(float threshold) {
        return new BubbleDynamicsConfig(threshold, driftThreshold, partitionThreshold, recoveryThreshold);
    }

    /**
     * Create new config with different drift threshold.
     *
     * @param threshold New drift threshold
     * @return New config with updated threshold
     */
    public BubbleDynamicsConfig withDriftThreshold(float threshold) {
        return new BubbleDynamicsConfig(mergeThreshold, threshold, partitionThreshold, recoveryThreshold);
    }

    /**
     * Create new config with different partition threshold.
     *
     * @param threshold New partition threshold
     * @return New config with updated threshold
     */
    public BubbleDynamicsConfig withPartitionThreshold(float threshold) {
        return new BubbleDynamicsConfig(mergeThreshold, driftThreshold, threshold, recoveryThreshold);
    }

    /**
     * Create new config with different recovery threshold.
     *
     * @param threshold New recovery threshold
     * @return New config with updated threshold
     */
    public BubbleDynamicsConfig withRecoveryThreshold(float threshold) {
        return new BubbleDynamicsConfig(mergeThreshold, driftThreshold, partitionThreshold, threshold);
    }
}
