/*
 * Copyright (c) 2024 Hal Hildebrand. All rights reserved.
 */

package com.hellblazer.luciferase.esvo.dag.metrics;

/**
 * Immutable cache performance metrics snapshot.
 * <p>
 * Captures hit/miss/eviction statistics for cache-based compression or DAG construction.
 * Used to measure cache effectiveness and guide memory tuning decisions.
 *
 * <h3>Metrics Tracked</h3>
 * <ul>
 *   <li><b>Hit count</b>: Successful lookups (data found in cache)</li>
 *   <li><b>Miss count</b>: Failed lookups (data not in cache, requires computation)</li>
 *   <li><b>Eviction count</b>: Cache entries removed to free space</li>
 *   <li><b>Hit rate</b>: Ratio of hits to total accesses (derived metric)</li>
 * </ul>
 *
 * <h3>Usage Example</h3>
 * <pre>{@code
 * var metrics = new CacheMetrics(8500, 1500, 200);
 *
 * System.out.printf("Hit rate: %.2f%%%n", metrics.hitRate() * 100);
 * // Output: "Hit rate: 85.00%"
 *
 * System.out.printf("Evictions: %d (%.1f%% of misses)%n",
 *     metrics.evictionCount(),
 *     (double) metrics.evictionCount() / metrics.missCount() * 100);
 * // Output: "Evictions: 200 (13.3% of misses)"
 * }</pre>
 *
 * <h3>Hit Rate Interpretation</h3>
 * <ul>
 *   <li>0.90+ (90%+) = Excellent cache performance</li>
 *   <li>0.70-0.89 (70-89%) = Good cache performance</li>
 *   <li>0.50-0.69 (50-69%) = Moderate cache performance (consider tuning)</li>
 *   <li>Below 0.50 (50%) = Poor cache performance (increase cache size or change strategy)</li>
 * </ul>
 *
 * <h3>Thread Safety</h3>
 * This record is immutable and thread-safe. Instances can be safely shared across
 * threads without synchronization.
 *
 * @param hitCount      Number of cache hits (successful lookups)
 * @param missCount     Number of cache misses (failed lookups)
 * @param evictionCount Number of cache evictions (entries removed to free space)
 * @author hal.hildebrand
 * @see CacheMetricsCollector
 * @see FileMetricsExporter
 */
public record CacheMetrics(long hitCount, long missCount, long evictionCount) {

    /**
     * Calculate cache hit rate as ratio of hits to total accesses.
     * <p>
     * The hit rate is computed as:
     * <pre>
     * hitRate = hitCount / (hitCount + missCount)
     * </pre>
     *
     * <h3>Interpretation</h3>
     * <ul>
     *   <li>1.0 = 100% hit rate (all accesses found in cache)</li>
     *   <li>0.85 = 85% hit rate (15% require computation)</li>
     *   <li>0.0 = 0% hit rate (cache not effective)</li>
     * </ul>
     *
     * <h3>Example</h3>
     * <pre>{@code
     * var metrics = new CacheMetrics(850, 150, 20);
     * float hitRate = metrics.hitRate();  // 0.85 (85%)
     *
     * // Convert to percentage for display
     * System.out.printf("Cache efficiency: %.1f%%%n", hitRate * 100);
     * // Output: "Cache efficiency: 85.0%"
     * }</pre>
     *
     * <h3>Edge Cases</h3>
     * Returns 0.0 if no accesses have been made (both hitCount and missCount are 0).
     *
     * @return hit rate as ratio (0.0 to 1.0), or 0.0 if no accesses recorded
     */
    public float hitRate() {
        var total = hitCount + missCount;
        if (total == 0) {
            return 0.0f;
        }
        return (float) hitCount / total;
    }
}
