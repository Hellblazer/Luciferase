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
package com.hellblazer.luciferase.esvo.dag.config;

import com.hellblazer.luciferase.esvo.dag.CompressionStrategy;
import com.hellblazer.luciferase.esvo.dag.HashAlgorithm;

import java.util.Objects;

/**
 * Immutable configuration for DAG compression behavior.
 *
 * <p>Defines all parameters controlling when and how DAG compression occurs,
 * including memory management, retention policies, and performance monitoring.
 *
 * <p>Use {@link #builder()} for fluent construction or {@link #defaultConfig()}
 * for sensible defaults suitable for most use cases.
 *
 * @param mode when compression should occur
 * @param strategy compression strategy balancing speed vs ratio
 * @param memoryPolicy how memory budget is enforced
 * @param retentionPolicy how original SVO is retained
 * @param memoryBudgetBytes maximum memory for compressed data (0 = unlimited)
 * @param enableMetrics whether to collect performance metrics
 * @param hashAlgorithm algorithm for structural hashing
 * @author hal.hildebrand
 */
public record CompressionConfiguration(
    CompressionMode mode,
    CompressionStrategy strategy,
    MemoryPolicy memoryPolicy,
    RetentionPolicy retentionPolicy,
    long memoryBudgetBytes,
    boolean enableMetrics,
    HashAlgorithm hashAlgorithm
) {
    /**
     * Canonical constructor with validation.
     */
    public CompressionConfiguration {
        Objects.requireNonNull(mode, "mode cannot be null");
        Objects.requireNonNull(strategy, "strategy cannot be null");
        Objects.requireNonNull(memoryPolicy, "memoryPolicy cannot be null");
        Objects.requireNonNull(retentionPolicy, "retentionPolicy cannot be null");
        Objects.requireNonNull(hashAlgorithm, "hashAlgorithm cannot be null");

        if (memoryBudgetBytes < 0) {
            throw new IllegalArgumentException("memoryBudgetBytes cannot be negative: " + memoryBudgetBytes);
        }
    }

    /**
     * @return default configuration with sensible defaults
     */
    public static CompressionConfiguration defaultConfig() {
        return builder().build();
    }

    /**
     * @return new builder instance with default values
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for CompressionConfiguration.
     */
    public static class Builder {
        private CompressionMode mode = CompressionMode.defaultMode();
        private CompressionStrategy strategy = CompressionStrategy.BALANCED;
        private MemoryPolicy memoryPolicy = MemoryPolicy.defaultPolicy();
        private RetentionPolicy retentionPolicy = RetentionPolicy.defaultPolicy();
        private long memoryBudgetBytes = Runtime.getRuntime().maxMemory() / 4;
        private boolean enableMetrics = true;
        private HashAlgorithm hashAlgorithm = HashAlgorithm.SHA256;

        /**
         * Set when compression should occur.
         */
        public Builder mode(CompressionMode mode) {
            this.mode = mode;
            return this;
        }

        /**
         * Set compression strategy.
         */
        public Builder strategy(CompressionStrategy strategy) {
            this.strategy = strategy;
            return this;
        }

        /**
         * Set memory enforcement policy.
         */
        public Builder memoryPolicy(MemoryPolicy memoryPolicy) {
            this.memoryPolicy = memoryPolicy;
            return this;
        }

        /**
         * Set SVO retention policy.
         */
        public Builder retentionPolicy(RetentionPolicy retentionPolicy) {
            this.retentionPolicy = retentionPolicy;
            return this;
        }

        /**
         * Set memory budget in bytes (0 = unlimited).
         */
        public Builder memoryBudgetBytes(long memoryBudgetBytes) {
            this.memoryBudgetBytes = memoryBudgetBytes;
            return this;
        }

        /**
         * Set whether to collect performance metrics.
         */
        public Builder enableMetrics(boolean enableMetrics) {
            this.enableMetrics = enableMetrics;
            return this;
        }

        /**
         * Set hash algorithm for structural hashing.
         */
        public Builder hashAlgorithm(HashAlgorithm hashAlgorithm) {
            this.hashAlgorithm = hashAlgorithm;
            return this;
        }

        /**
         * Build immutable configuration.
         *
         * @throws IllegalArgumentException if validation fails
         * @throws NullPointerException if required field is null
         */
        public CompressionConfiguration build() {
            return new CompressionConfiguration(
                mode,
                strategy,
                memoryPolicy,
                retentionPolicy,
                memoryBudgetBytes,
                enableMetrics,
                hashAlgorithm
            );
        }
    }
}
