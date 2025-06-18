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
package com.hellblazer.luciferase.lucien.tetree;

/**
 * Configuration class for Tetree behavior, including node storage strategies.
 * This class provides settings for when to switch between different node implementations
 * based on entity count thresholds.
 *
 * @author hal.hildebrand
 */
public class TetreeConfiguration {

    /**
     * Default threshold for switching to array-based node storage
     */
    public static final int DEFAULT_ARRAY_THRESHOLD = 32;

    /**
     * Default initial capacity for array-based nodes
     */
    public static final int DEFAULT_ARRAY_INITIAL_CAPACITY = 16;

    private final int arrayThreshold;
    private final int arrayInitialCapacity;
    private final boolean useArrayNodes;
    private final boolean alwaysUseArrayNodes;
    private final boolean enableNodeCompaction;
    private final float compactionThreshold;

    /**
     * Create a default configuration
     */
    public TetreeConfiguration() {
        this(DEFAULT_ARRAY_THRESHOLD, DEFAULT_ARRAY_INITIAL_CAPACITY, true, false, true, 0.5f);
    }

    /**
     * Create a configuration with custom settings
     *
     * @param arrayThreshold       Entity count threshold to switch to array-based storage
     * @param arrayInitialCapacity Initial capacity for array-based nodes
     * @param useArrayNodes        Whether to use array nodes at all
     * @param alwaysUseArrayNodes  If true, always use array nodes regardless of entity count
     * @param enableNodeCompaction Whether to enable array compaction
     * @param compactionThreshold  Fill ratio below which to compact arrays
     */
    public TetreeConfiguration(int arrayThreshold, int arrayInitialCapacity, boolean useArrayNodes,
                               boolean alwaysUseArrayNodes, boolean enableNodeCompaction, float compactionThreshold) {
        if (arrayThreshold <= 0) {
            throw new IllegalArgumentException("Array threshold must be positive");
        }
        if (arrayInitialCapacity <= 0) {
            throw new IllegalArgumentException("Array initial capacity must be positive");
        }
        if (compactionThreshold < 0 || compactionThreshold > 1) {
            throw new IllegalArgumentException("Compaction threshold must be between 0 and 1");
        }

        this.arrayThreshold = arrayThreshold;
        this.arrayInitialCapacity = arrayInitialCapacity;
        this.useArrayNodes = useArrayNodes;
        this.alwaysUseArrayNodes = alwaysUseArrayNodes;
        this.enableNodeCompaction = enableNodeCompaction;
        this.compactionThreshold = compactionThreshold;
    }

    /**
     * Get the entity count threshold for switching to array-based storage
     *
     * @return threshold entity count
     */
    public int getArrayThreshold() {
        return arrayThreshold;
    }

    /**
     * Get the initial capacity for array-based nodes
     *
     * @return initial array capacity
     */
    public int getArrayInitialCapacity() {
        return arrayInitialCapacity;
    }

    /**
     * Check if array nodes are enabled
     *
     * @return true if array nodes can be used
     */
    public boolean isUseArrayNodes() {
        return useArrayNodes;
    }

    /**
     * Check if array nodes should always be used
     *
     * @return true if array nodes should always be used
     */
    public boolean isAlwaysUseArrayNodes() {
        return alwaysUseArrayNodes;
    }

    /**
     * Check if node compaction is enabled
     *
     * @return true if compaction is enabled
     */
    public boolean isEnableNodeCompaction() {
        return enableNodeCompaction;
    }

    /**
     * Get the fill ratio threshold for compaction
     *
     * @return compaction threshold (0.0 to 1.0)
     */
    public float getCompactionThreshold() {
        return compactionThreshold;
    }

    /**
     * Builder for TetreeConfiguration
     */
    public static class Builder {
        private int arrayThreshold = DEFAULT_ARRAY_THRESHOLD;
        private int arrayInitialCapacity = DEFAULT_ARRAY_INITIAL_CAPACITY;
        private boolean useArrayNodes = true;
        private boolean alwaysUseArrayNodes = false;
        private boolean enableNodeCompaction = true;
        private float compactionThreshold = 0.5f;

        public Builder arrayThreshold(int threshold) {
            this.arrayThreshold = threshold;
            return this;
        }

        public Builder arrayInitialCapacity(int capacity) {
            this.arrayInitialCapacity = capacity;
            return this;
        }

        public Builder useArrayNodes(boolean use) {
            this.useArrayNodes = use;
            return this;
        }

        public Builder alwaysUseArrayNodes(boolean always) {
            this.alwaysUseArrayNodes = always;
            return this;
        }

        public Builder enableNodeCompaction(boolean enable) {
            this.enableNodeCompaction = enable;
            return this;
        }

        public Builder compactionThreshold(float threshold) {
            this.compactionThreshold = threshold;
            return this;
        }

        public TetreeConfiguration build() {
            return new TetreeConfiguration(arrayThreshold, arrayInitialCapacity, useArrayNodes,
                                           alwaysUseArrayNodes, enableNodeCompaction, compactionThreshold);
        }
    }
}