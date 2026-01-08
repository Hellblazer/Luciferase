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

package com.hellblazer.luciferase.simulation.distributed.migration;

/**
 * Two-Phase Commit phases for cross-process entity migration.
 * <p>
 * Migration follows this state machine:
 * <pre>
 * PREPARE → COMMIT (success path)
 * PREPARE → ABORT  (failure path)
 * </pre>
 * <p>
 * Phase semantics:
 * - PREPARE: Remove entity from source, prepare for commit
 * - COMMIT: Add entity to destination, finalize migration
 * - ABORT: Rollback - restore entity to source
 * <p>
 * Architecture Decision D6B.8: Remove-then-commit ordering prevents duplicates.
 *
 * @author hal.hildebrand
 */
public enum MigrationPhase {
    /**
     * PREPARE phase: Entity removed from source bubble.
     * <p>
     * Actions:
     * - Snapshot entity state
     * - Remove entity from source bubble
     * - Store migration transaction
     * <p>
     * Next phases: COMMIT (success) or ABORT (failure)
     */
    PREPARE,

    /**
     * COMMIT phase: Entity added to destination bubble.
     * <p>
     * Actions:
     * - Add entity to destination bubble
     * - Increment entity epoch
     * - Persist idempotency token
     * - Update migration log
     * <p>
     * Terminal state (success)
     */
    COMMIT,

    /**
     * ABORT phase: Rollback migration.
     * <p>
     * Actions:
     * - Restore entity to source bubble (from snapshot)
     * - Remove idempotency token (allow retry)
     * - Record abort in metrics
     * <p>
     * Terminal state (failure)
     */
    ABORT
}
