/*
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.simulation.ghost;

/**
 * Configuration for duplicate entity detection and reconciliation.
 * <p>
 * Controls detection behavior, scanning frequency, logging, and reconciliation strategy.
 * <p>
 * Default configuration:
 * <ul>
 *   <li>Detection enabled</li>
 *   <li>Scan every tick (scanIntervalTicks = 1)</li>
 *   <li>ERROR-level logging</li>
 *   <li>SOURCE_BUBBLE reconciliation strategy</li>
 * </ul>
 *
 * @param enabled              Enable duplicate detection (default: true)
 * @param scanIntervalTicks    Ticks between scans (default: 1, always scan every tick)
 * @param logLevel             Logging verbosity (ERROR, WARN, DEBUG)
 * @param reconciliationStrategy How to resolve duplicates (SOURCE_BUBBLE only for Phase 5D)
 *
 * @author hal.hildebrand
 */
public record DuplicateDetectionConfig(
    boolean enabled,
    int scanIntervalTicks,
    LogLevel logLevel,
    ReconciliationStrategy reconciliationStrategy
) {

    /**
     * Logging verbosity levels.
     */
    public enum LogLevel {
        /** Log only errors (duplicate creation failures) */
        ERROR,
        /** Log warnings (duplicates detected and reconciled) */
        WARN,
        /** Log debug info (all scans, decisions, removals) */
        DEBUG
    }

    /**
     * Reconciliation strategies for duplicate resolution.
     * <p>
     * <b>SOURCE_BUBBLE Strategy</b>:
     * Uses MigrationLog to determine the source bubble (where entity originated).
     * Keeps entity in source bubble, removes from all other locations.
     * <p>
     * <b>Rationale for SOURCE_BUBBLE</b>:
     * <ul>
     *   <li>Migration log provides authoritative source-of-truth</li>
     *   <li>Ensures entity remains where it was last successfully placed</li>
     *   <li>Avoids choosing arbitrary bubble in multi-duplicate scenarios</li>
     *   <li>Supports debugging by preserving original location</li>
     * </ul>
     * <p>
     * <b>Phase 6 Limitation</b>:
     * Single-process only. Distributed multi-process simulation (Phase 6) requires
     * distributed consensus protocol for reconciliation across process boundaries.
     */
    public enum ReconciliationStrategy {
        /**
         * Keep entity in source bubble (from MigrationLog), remove from others.
         * If no migration log entry exists, keep in all locations and flag for manual review.
         */
        SOURCE_BUBBLE
    }

    /**
     * Default configuration: enabled, scan every tick, ERROR logging, SOURCE_BUBBLE strategy.
     *
     * @return Default configuration instance
     */
    public static DuplicateDetectionConfig defaultConfig() {
        return new DuplicateDetectionConfig(
            true,                                   // enabled
            1,                                      // scanIntervalTicks (always scan)
            LogLevel.ERROR,                         // logLevel
            ReconciliationStrategy.SOURCE_BUBBLE   // reconciliationStrategy
        );
    }

    /**
     * Create a config builder for customization.
     *
     * @return Builder instance starting from default config
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for DuplicateDetectionConfig.
     */
    public static class Builder {
        private boolean enabled = true;
        private int scanIntervalTicks = 1;
        private LogLevel logLevel = LogLevel.ERROR;
        private ReconciliationStrategy reconciliationStrategy = ReconciliationStrategy.SOURCE_BUBBLE;

        public Builder withEnabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder withScanIntervalTicks(int ticks) {
            if (ticks < 1) {
                throw new IllegalArgumentException("Scan interval must be >= 1");
            }
            this.scanIntervalTicks = ticks;
            return this;
        }

        public Builder withLogLevel(LogLevel level) {
            this.logLevel = level;
            return this;
        }

        public Builder withReconciliationStrategy(ReconciliationStrategy strategy) {
            this.reconciliationStrategy = strategy;
            return this;
        }

        public DuplicateDetectionConfig build() {
            return new DuplicateDetectionConfig(enabled, scanIntervalTicks, logLevel, reconciliationStrategy);
        }
    }
}
