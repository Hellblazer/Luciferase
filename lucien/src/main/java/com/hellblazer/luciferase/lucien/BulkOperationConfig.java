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
 * Configuration for bulk operations on spatial indices. Provides control over various optimization strategies
 * used during bulk insertions and updates.
 *
 * @author hal.hildebrand
 */
public class BulkOperationConfig {
    
    private boolean deferSubdivision = true;
    private boolean preSortByMorton = true;
    private boolean enableParallel = false;
    private int batchSize = 1000;
    private int parallelThreshold = 10000;
    private int maxThreads = Runtime.getRuntime().availableProcessors();
    
    /**
     * Whether to defer node subdivision until after all bulk insertions are complete.
     * This improves performance by avoiding repeated tree restructuring.
     */
    public boolean isDeferSubdivision() {
        return deferSubdivision;
    }
    
    /**
     * Whether to pre-sort entities by Morton code before insertion.
     * This improves cache locality and reduces tree traversal.
     */
    public boolean isPreSortByMorton() {
        return preSortByMorton;
    }
    
    /**
     * Whether to enable parallel processing for bulk operations.
     * Only activates when batch size exceeds parallelThreshold.
     */
    public boolean isEnableParallel() {
        return enableParallel;
    }
    
    /**
     * The size of individual batches for processing.
     * Larger batches are more efficient but use more memory.
     */
    public int getBatchSize() {
        return batchSize;
    }
    
    /**
     * Minimum number of entities required to trigger parallel processing.
     */
    public int getParallelThreshold() {
        return parallelThreshold;
    }
    
    /**
     * Maximum number of threads to use for parallel operations.
     */
    public int getMaxThreads() {
        return maxThreads;
    }
    
    // Fluent API for configuration
    
    public BulkOperationConfig withDeferredSubdivision(boolean defer) {
        this.deferSubdivision = defer;
        return this;
    }
    
    public BulkOperationConfig withPreSortByMorton(boolean sort) {
        this.preSortByMorton = sort;
        return this;
    }
    
    public BulkOperationConfig withParallelProcessing(boolean parallel) {
        this.enableParallel = parallel;
        return this;
    }
    
    public BulkOperationConfig withBatchSize(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("Batch size must be positive");
        }
        this.batchSize = size;
        return this;
    }
    
    public BulkOperationConfig withParallelThreshold(int threshold) {
        if (threshold <= 0) {
            throw new IllegalArgumentException("Parallel threshold must be positive");
        }
        this.parallelThreshold = threshold;
        return this;
    }
    
    public BulkOperationConfig withMaxThreads(int threads) {
        if (threads <= 0) {
            throw new IllegalArgumentException("Max threads must be positive");
        }
        this.maxThreads = threads;
        return this;
    }
    
    /**
     * Create a default configuration optimized for performance.
     */
    public static BulkOperationConfig highPerformance() {
        return new BulkOperationConfig()
            .withDeferredSubdivision(true)
            .withPreSortByMorton(true)
            .withParallelProcessing(true)
            .withBatchSize(5000);
    }
    
    /**
     * Create a configuration optimized for memory efficiency.
     */
    public static BulkOperationConfig memoryEfficient() {
        return new BulkOperationConfig()
            .withDeferredSubdivision(true)
            .withPreSortByMorton(true)
            .withParallelProcessing(false)
            .withBatchSize(500);
    }
    
    /**
     * Create a configuration for real-time insertions with minimal latency.
     */
    public static BulkOperationConfig lowLatency() {
        return new BulkOperationConfig()
            .withDeferredSubdivision(false)
            .withPreSortByMorton(false)
            .withParallelProcessing(false)
            .withBatchSize(100);
    }
}