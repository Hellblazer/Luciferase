/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien;

/**
 * Configuration for bulk operations on spatial indices. Provides control over various optimization strategies used
 * during bulk insertions and updates.
 *
 * @author hal.hildebrand
 */
public class BulkOperationConfig {

    private boolean                           deferSubdivision         = true;
    private boolean                           preSortByMorton          = true;
    private boolean                           enableParallel           = false;
    private int                               batchSize                = 1000;
    private int                               parallelThreshold        = 10000;
    private int                               maxThreads               = Runtime.getRuntime().availableProcessors();
    private boolean                           useStackBasedBuilder     = false;
    private int                               stackBuilderThreshold    = 10000;
    private StackBasedTreeBuilder.BuildConfig stackBuilderConfig       = StackBasedTreeBuilder.defaultConfig();
    private boolean                           useDynamicLevelSelection = false;
    private boolean                           useAdaptiveSubdivision   = false;

    /**
     * Create a default configuration optimized for performance.
     */
    public static BulkOperationConfig highPerformance() {
        return new BulkOperationConfig().withDeferredSubdivision(true).withPreSortByMorton(true).withParallelProcessing(
        true).withBatchSize(5000).withStackBasedBuilder(true).withStackBuilderThreshold(10000).withStackBuilderConfig(
        StackBasedTreeBuilder.highPerformanceConfig()).withDynamicLevelSelection(true).withAdaptiveSubdivision(true);
    }

    /**
     * Create a configuration for real-time insertions with minimal latency.
     */
    public static BulkOperationConfig lowLatency() {
        return new BulkOperationConfig().withDeferredSubdivision(false)
                                        .withPreSortByMorton(false)
                                        .withParallelProcessing(false)
                                        .withBatchSize(100);
    }

    /**
     * Create a configuration optimized for memory efficiency.
     */
    public static BulkOperationConfig memoryEfficient() {
        return new BulkOperationConfig().withDeferredSubdivision(true).withPreSortByMorton(true).withParallelProcessing(
        false).withBatchSize(500);
    }

    /**
     * The size of individual batches for processing. Larger batches are more efficient but use more memory.
     */
    public int getBatchSize() {
        return batchSize;
    }

    /**
     * Maximum number of threads to use for parallel operations.
     */
    public int getMaxThreads() {
        return maxThreads;
    }

    /**
     * Minimum number of entities required to trigger parallel processing.
     */
    public int getParallelThreshold() {
        return parallelThreshold;
    }

    /**
     * Configuration for the stack-based tree builder.
     */
    public StackBasedTreeBuilder.BuildConfig getStackBuilderConfig() {
        return stackBuilderConfig;
    }

    /**
     * Minimum number of entities required to trigger stack-based tree building. Only applies when useStackBasedBuilder
     * is true.
     */
    public int getStackBuilderThreshold() {
        return stackBuilderThreshold;
    }

    /**
     * Whether to defer node subdivision until after all bulk insertions are complete. This improves performance by
     * avoiding repeated tree restructuring.
     */
    public boolean isDeferSubdivision() {
        return deferSubdivision;
    }

    /**
     * Whether to enable parallel processing for bulk operations. Only activates when batch size exceeds
     * parallelThreshold.
     */
    public boolean isEnableParallel() {
        return enableParallel;
    }

    /**
     * Whether to pre-sort entities by Morton code before insertion. This improves cache locality and reduces tree
     * traversal.
     */
    public boolean isPreSortByMorton() {
        return preSortByMorton;
    }

    // Fluent API for configuration

    /**
     * Whether to use adaptive subdivision thresholds based on tree depth. Prevents excessive subdivision at deep
     * levels.
     */
    public boolean isUseAdaptiveSubdivision() {
        return useAdaptiveSubdivision;
    }

    /**
     * Whether to use dynamic level selection based on data distribution. This can significantly improve performance for
     * randomly distributed data.
     */
    public boolean isUseDynamicLevelSelection() {
        return useDynamicLevelSelection;
    }

    /**
     * Whether to use stack-based tree building for large bulk operations. This can significantly improve cache locality
     * and reduce memory allocation overhead.
     */
    public boolean isUseStackBasedBuilder() {
        return useStackBasedBuilder;
    }

    public BulkOperationConfig withAdaptiveSubdivision(boolean useAdaptive) {
        this.useAdaptiveSubdivision = useAdaptive;
        return this;
    }

    public BulkOperationConfig withBatchSize(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("Batch size must be positive");
        }
        this.batchSize = size;
        return this;
    }

    public BulkOperationConfig withDeferredSubdivision(boolean defer) {
        this.deferSubdivision = defer;
        return this;
    }

    public BulkOperationConfig withDynamicLevelSelection(boolean useDynamic) {
        this.useDynamicLevelSelection = useDynamic;
        return this;
    }

    public BulkOperationConfig withMaxThreads(int threads) {
        if (threads <= 0) {
            throw new IllegalArgumentException("Max threads must be positive");
        }
        this.maxThreads = threads;
        return this;
    }

    public BulkOperationConfig withParallelProcessing(boolean parallel) {
        this.enableParallel = parallel;
        return this;
    }

    public BulkOperationConfig withParallelThreshold(int threshold) {
        if (threshold <= 0) {
            throw new IllegalArgumentException("Parallel threshold must be positive");
        }
        this.parallelThreshold = threshold;
        return this;
    }

    public BulkOperationConfig withPreSortByMorton(boolean sort) {
        this.preSortByMorton = sort;
        return this;
    }

    public BulkOperationConfig withStackBasedBuilder(boolean useStackBuilder) {
        this.useStackBasedBuilder = useStackBuilder;
        return this;
    }

    public BulkOperationConfig withStackBuilderConfig(StackBasedTreeBuilder.BuildConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("Stack builder config cannot be null");
        }
        this.stackBuilderConfig = config;
        return this;
    }

    public BulkOperationConfig withStackBuilderThreshold(int threshold) {
        if (threshold <= 0) {
            throw new IllegalArgumentException("Stack builder threshold must be positive");
        }
        this.stackBuilderThreshold = threshold;
        return this;
    }
}
