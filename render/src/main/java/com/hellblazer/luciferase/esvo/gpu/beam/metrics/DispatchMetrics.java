package com.hellblazer.luciferase.esvo.gpu.beam.metrics;

/**
 * Kernel dispatch statistics tracking batch vs single-ray execution.
 * Immutable and thread-safe.
 *
 * @param totalDispatches Total number of kernel dispatches
 * @param batchDispatches Number of batch kernel dispatches
 * @param singleRayDispatches Number of single-ray kernel dispatches
 * @param batchPercentage Percentage of dispatches that were batch (0.0 to 100.0)
 */
public record DispatchMetrics(
    int totalDispatches,
    int batchDispatches,
    int singleRayDispatches,
    double batchPercentage
) {
    /**
     * Compact constructor with validation.
     */
    public DispatchMetrics {
        if (totalDispatches < 0) {
            throw new IllegalArgumentException("totalDispatches cannot be negative: " + totalDispatches);
        }
        if (batchDispatches < 0) {
            throw new IllegalArgumentException("batchDispatches cannot be negative: " + batchDispatches);
        }
        if (singleRayDispatches < 0) {
            throw new IllegalArgumentException("singleRayDispatches cannot be negative: " + singleRayDispatches);
        }
        if (batchDispatches + singleRayDispatches != totalDispatches) {
            throw new IllegalArgumentException(
                "batchDispatches (" + batchDispatches + ") + singleRayDispatches (" + singleRayDispatches +
                ") must equal totalDispatches (" + totalDispatches + ")"
            );
        }
        if (batchPercentage < 0.0 || batchPercentage > 100.0) {
            throw new IllegalArgumentException(
                "batchPercentage must be in range [0.0, 100.0], got: " + batchPercentage
            );
        }
    }

    /**
     * Creates an empty dispatch metrics with all values set to zero.
     */
    public static DispatchMetrics empty() {
        return new DispatchMetrics(0, 0, 0, 0.0);
    }

    /**
     * Creates dispatch metrics from raw counts, calculating percentage automatically.
     *
     * @param total Total number of dispatches
     * @param batch Number of batch dispatches
     * @param singleRay Number of single-ray dispatches
     * @return DispatchMetrics with calculated percentage
     */
    public static DispatchMetrics from(int total, int batch, int singleRay) {
        double pct = total > 0 ? 100.0 * batch / total : 0.0;
        return new DispatchMetrics(total, batch, singleRay, pct);
    }
}
