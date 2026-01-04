package com.hellblazer.luciferase.simulation.tumbler;

/**
 * Configuration for SpatialTumbler adaptive volume sharding behavior.
 * <p>
 * Controls split/join thresholds, region depth limits, and boundary span parameters.
 * <p>
 * Default Configuration:
 * - Split threshold: 5000 entities (trigger subdivision)
 * - Join threshold: 500 entities (trigger consolidation)
 * - Min region level: 4 (prevents excessive splits near root)
 * - Max region level: 12 (prevents infinite subdivision)
 * - Span width ratio: 0.1 (10% of region size)
 * - Min span distance: 1.0 (absolute minimum boundary width)
 * - Auto adapt: true (automatic split/join enabled)
 * - Adapt check interval: 100 operations between checks
 *
 * @author hal.hildebrand
 */
public record TumblerConfig(
    int splitThreshold,          // Entity count triggering split (default: 5000)
    int joinThreshold,           // Combined count for join (default: 500)
    byte minRegionLevel,         // Minimum region depth (default: 4)
    byte maxRegionLevel,         // Maximum region depth (default: 12)
    float spanWidthRatio,        // Span as fraction of region (default: 0.1)
    float minSpanDistance,       // Minimum span width (default: 1.0)
    boolean autoAdapt,           // Enable automatic split/join (default: true)
    int adaptCheckInterval,      // Operations between adapt checks (default: 100)
    RegionSplitStrategy splitStrategy  // How to split regions (default: OCTANT)
) {

    /**
     * Region split strategy.
     */
    public enum RegionSplitStrategy {
        /**
         * 8-way subdivision following Bey tetrahedral refinement.
         * Splits region into 8 child tetrahedra using S0-S5 types.
         */
        OCTANT
    }

    /**
     * Create config with default values.
     *
     * @return Default configuration
     */
    public static TumblerConfig defaults() {
        return new TumblerConfig(
            5000,    // splitThreshold
            500,     // joinThreshold
            (byte) 4,  // minRegionLevel
            (byte) 10, // maxRegionLevel (CompactTetreeKey limit)
            0.1f,    // spanWidthRatio
            1.0f,    // minSpanDistance
            true,    // autoAdapt
            100,     // adaptCheckInterval
            RegionSplitStrategy.OCTANT
        );
    }

    /**
     * Create config with custom split threshold.
     *
     * @param threshold Entity count triggering split
     * @return New config with updated threshold
     */
    public TumblerConfig withSplitThreshold(int threshold) {
        return new TumblerConfig(threshold, joinThreshold, minRegionLevel,
                                maxRegionLevel, spanWidthRatio, minSpanDistance,
                                autoAdapt, adaptCheckInterval, splitStrategy);
    }

    /**
     * Create config with custom join threshold.
     *
     * @param threshold Combined entity count for join
     * @return New config with updated threshold
     */
    public TumblerConfig withJoinThreshold(int threshold) {
        return new TumblerConfig(splitThreshold, threshold, minRegionLevel,
                                maxRegionLevel, spanWidthRatio, minSpanDistance,
                                autoAdapt, adaptCheckInterval, splitStrategy);
    }

    /**
     * Create config with custom region level range.
     *
     * @param minLevel Minimum region depth
     * @param maxLevel Maximum region depth
     * @return New config with updated levels
     */
    public TumblerConfig withRegionLevels(byte minLevel, byte maxLevel) {
        return new TumblerConfig(splitThreshold, joinThreshold, minLevel,
                                maxLevel, spanWidthRatio, minSpanDistance,
                                autoAdapt, adaptCheckInterval, splitStrategy);
    }

    /**
     * Create config with custom span parameters.
     *
     * @param widthRatio Span width as fraction of region size
     * @param minDistance Minimum absolute span width
     * @return New config with updated span parameters
     */
    public TumblerConfig withSpanParameters(float widthRatio, float minDistance) {
        return new TumblerConfig(splitThreshold, joinThreshold, minRegionLevel,
                                maxRegionLevel, widthRatio, minDistance,
                                autoAdapt, adaptCheckInterval, splitStrategy);
    }

    /**
     * Create config with auto-adapt disabled.
     *
     * @return New config with auto-adapt disabled
     */
    public TumblerConfig withoutAutoAdapt() {
        return new TumblerConfig(splitThreshold, joinThreshold, minRegionLevel,
                                maxRegionLevel, spanWidthRatio, minSpanDistance,
                                false, adaptCheckInterval, splitStrategy);
    }

    /**
     * Create config with custom adapt check interval.
     *
     * @param interval Operations between adapt checks
     * @return New config with updated interval
     */
    public TumblerConfig withAdaptCheckInterval(int interval) {
        return new TumblerConfig(splitThreshold, joinThreshold, minRegionLevel,
                                maxRegionLevel, spanWidthRatio, minSpanDistance,
                                autoAdapt, interval, splitStrategy);
    }

    /**
     * Create config with custom split strategy.
     *
     * @param strategy Split strategy to use
     * @return New config with updated strategy
     */
    public TumblerConfig withSplitStrategy(RegionSplitStrategy strategy) {
        return new TumblerConfig(splitThreshold, joinThreshold, minRegionLevel,
                                maxRegionLevel, spanWidthRatio, minSpanDistance,
                                autoAdapt, adaptCheckInterval, strategy);
    }

    /**
     * Validate configuration parameters.
     *
     * @throws IllegalArgumentException if parameters are invalid
     */
    public TumblerConfig {
        if (splitThreshold <= 0) {
            throw new IllegalArgumentException("Split threshold must be positive");
        }
        if (joinThreshold <= 0) {
            throw new IllegalArgumentException("Join threshold must be positive");
        }
        if (joinThreshold >= splitThreshold) {
            throw new IllegalArgumentException(
                "Join threshold must be less than split threshold (hysteresis gap)");
        }
        if (minRegionLevel < 0 || minRegionLevel > 21) {
            throw new IllegalArgumentException("Min region level must be in [0, 21]");
        }
        if (maxRegionLevel < minRegionLevel || maxRegionLevel > 21) {
            throw new IllegalArgumentException(
                "Max region level must be >= min level and <= 21");
        }
        if (spanWidthRatio < 0.0f || spanWidthRatio > 1.0f) {
            throw new IllegalArgumentException("Span width ratio must be in [0.0, 1.0]");
        }
        if (minSpanDistance < 0.0f) {
            throw new IllegalArgumentException("Min span distance must be non-negative");
        }
        if (adaptCheckInterval <= 0) {
            throw new IllegalArgumentException("Adapt check interval must be positive");
        }
    }
}
