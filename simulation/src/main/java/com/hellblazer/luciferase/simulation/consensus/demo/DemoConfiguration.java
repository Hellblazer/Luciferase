/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase Simulation Framework.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.simulation.consensus.demo;

import java.util.Objects;

/**
 * Immutable configuration for spatial demo execution.
 * <p>
 * Defines all parameters for demo run including:
 * - Grid topology (4 bubbles at Tetree L1)
 * - Entity spawning (100-500 entities)
 * - Runtime duration (3 minutes default)
 * - Failure injection timing and type
 * <p>
 * Phase 8E Day 1: Demo Runner and Validation
 *
 * @author hal.hildebrand
 */
public record DemoConfiguration(
    int bubbleCount,
    int initialEntityCount,
    int maxEntityCount,
    int runtimeSeconds,
    int failureInjectionTimeSeconds,
    FailureInjector.FailureType failureType,
    int failureBubbleIndex
) {

    /**
     * Default configuration for MVP demo.
     */
    public static final DemoConfiguration DEFAULT = builder().build();

    /**
     * Create builder with default values.
     *
     * @return Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Validate configuration parameters.
     * <p>
     * Ensures all values are within acceptable ranges.
     *
     * @throws IllegalArgumentException if validation fails
     */
    public DemoConfiguration {
        if (bubbleCount <= 0) {
            throw new IllegalArgumentException("Bubble count must be positive, got " + bubbleCount);
        }
        if (initialEntityCount <= 0) {
            throw new IllegalArgumentException("Initial entity count must be positive, got " + initialEntityCount);
        }
        if (maxEntityCount < initialEntityCount) {
            throw new IllegalArgumentException(
                "Max entity count (" + maxEntityCount + ") must be >= initial count (" + initialEntityCount + ")"
            );
        }
        if (runtimeSeconds <= 0) {
            throw new IllegalArgumentException("Runtime must be positive, got " + runtimeSeconds);
        }
        if (failureInjectionTimeSeconds < 0) {
            throw new IllegalArgumentException(
                "Failure injection time must be non-negative, got " + failureInjectionTimeSeconds
            );
        }
        if (failureInjectionTimeSeconds >= runtimeSeconds) {
            throw new IllegalArgumentException(
                "Failure injection time (" + failureInjectionTimeSeconds +
                ") must be < runtime (" + runtimeSeconds + ")"
            );
        }
        if (failureBubbleIndex < 0 || failureBubbleIndex >= bubbleCount) {
            throw new IllegalArgumentException(
                "Failure bubble index must be 0-" + (bubbleCount - 1) + ", got " + failureBubbleIndex
            );
        }
        Objects.requireNonNull(failureType, "Failure type must not be null");
    }

    /**
     * Builder for DemoConfiguration with fluent API.
     */
    public static class Builder {
        private int bubbleCount = 4;
        private int initialEntityCount = 100;
        private int maxEntityCount = 500;
        private int runtimeSeconds = 180; // 3 minutes
        private int failureInjectionTimeSeconds = 60; // At 1 minute
        private FailureInjector.FailureType failureType = FailureInjector.FailureType.BYZANTINE_VOTE;
        private int failureBubbleIndex = 0;

        public Builder bubbleCount(int count) {
            this.bubbleCount = count;
            return this;
        }

        public Builder initialEntityCount(int count) {
            this.initialEntityCount = count;
            return this;
        }

        public Builder maxEntityCount(int count) {
            this.maxEntityCount = count;
            return this;
        }

        public Builder runtimeSeconds(int seconds) {
            this.runtimeSeconds = seconds;
            return this;
        }

        public Builder failureInjectionTimeSeconds(int seconds) {
            this.failureInjectionTimeSeconds = seconds;
            return this;
        }

        public Builder failureType(FailureInjector.FailureType type) {
            this.failureType = type;
            return this;
        }

        public Builder failureBubbleIndex(int index) {
            this.failureBubbleIndex = index;
            return this;
        }

        public DemoConfiguration build() {
            return new DemoConfiguration(
                bubbleCount,
                initialEntityCount,
                maxEntityCount,
                runtimeSeconds,
                failureInjectionTimeSeconds,
                failureType,
                failureBubbleIndex
            );
        }
    }
}
