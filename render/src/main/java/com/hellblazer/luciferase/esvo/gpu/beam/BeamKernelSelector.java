package com.hellblazer.luciferase.esvo.gpu.beam;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Selects between single-ray and batch kernels based on ray coherence metrics.
 *
 * Analyzes BeamTree statistics to make dynamic kernel selection decisions:
 * - BATCH kernel: when coherence >= threshold (typically 0.3)
 * - SINGLE_RAY kernel: when coherence < threshold
 */
public class BeamKernelSelector {
    private static final Logger log = LoggerFactory.getLogger(BeamKernelSelector.class);

    // Default coherence threshold for batch kernel activation
    private static final double DEFAULT_COHERENCE_THRESHOLD = 0.3;

    private final double coherenceThreshold;
    private KernelMetrics metrics;

    /**
     * Kernel selection choices.
     */
    public enum KernelChoice {
        SINGLE_RAY,  // Process rays individually
        BATCH        // Process rays in coherent batches
    }

    /**
     * Create selector with default coherence threshold.
     */
    public BeamKernelSelector() {
        this(DEFAULT_COHERENCE_THRESHOLD);
    }

    /**
     * Create selector with custom coherence threshold.
     *
     * @param coherenceThreshold threshold for batch activation [0.0, 1.0]
     */
    public BeamKernelSelector(double coherenceThreshold) {
        if (coherenceThreshold < 0.0 || coherenceThreshold > 1.0) {
            throw new IllegalArgumentException("Coherence threshold must be in [0.0, 1.0]");
        }
        this.coherenceThreshold = coherenceThreshold;
        this.metrics = new KernelMetrics();
    }

    /**
     * Select kernel based on BeamTree coherence.
     *
     * @param tree BeamTree to analyze (may be null)
     * @return kernel choice (BATCH or SINGLE_RAY)
     */
    public KernelChoice selectKernel(BeamTree tree) {
        if (tree == null) {
            metrics.recordSingleRay();
            return KernelChoice.SINGLE_RAY;
        }

        var stats = tree.getStatistics();
        var avgCoherence = stats.averageCoherence();

        var choice = avgCoherence >= coherenceThreshold ?
                KernelChoice.BATCH :
                KernelChoice.SINGLE_RAY;

        if (choice == KernelChoice.BATCH) {
            metrics.recordBatch();
        } else {
            metrics.recordSingleRay();
        }

        log.debug("Kernel selection: {} (coherence={:.3f}, threshold={:.3f})",
                choice, avgCoherence, coherenceThreshold);

        return choice;
    }

    /**
     * Get kernel selection metrics.
     */
    public SelectionMetrics getMetrics() {
        return metrics.snapshot();
    }

    /**
     * Reset metrics.
     */
    public void resetMetrics() {
        metrics = new KernelMetrics();
    }

    /**
     * Selection metrics snapshot.
     */
    public record SelectionMetrics(int selectionCount, int batchSelections, int singleRaySelections) {

        public double batchPercentage() {
            if (selectionCount == 0) return 0.0;
            return 100.0 * batchSelections / selectionCount;
        }
    }

    /**
     * Internal metrics accumulator.
     */
    private static class KernelMetrics {
        private int totalSelections = 0;
        private int batchCount = 0;
        private int singleRayCount = 0;

        void recordBatch() {
            totalSelections++;
            batchCount++;
        }

        void recordSingleRay() {
            totalSelections++;
            singleRayCount++;
        }

        SelectionMetrics snapshot() {
            return new SelectionMetrics(totalSelections, batchCount, singleRayCount);
        }
    }
}
