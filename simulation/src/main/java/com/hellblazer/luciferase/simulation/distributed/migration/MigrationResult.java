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

import java.util.UUID;

/**
 * Result of a cross-process entity migration attempt.
 * <p>
 * Contains success/failure status, timing information, and optional failure reason.
 * Returned by CrossProcessMigration.migrate() as a CompletableFuture&lt;MigrationResult&gt;.
 * <p>
 * Example usage:
 * <pre>
 * migration.migrate(entityId, source, dest).thenAccept(result -> {
 *     if (result.success()) {
 *         log.info("Migration completed in {} ms", result.latencyMs());
 *     } else {
 *         log.warn("Migration failed: {}", result.reason());
 *     }
 * });
 * </pre>
 *
 * @param success      True if migration completed successfully
 * @param entityId     Entity that was migrated
 * @param destProcessId Destination process UUID (null if failed before reaching dest)
 * @param latencyMs    Total migration latency in milliseconds
 * @param reason       Failure reason (null if successful)
 * @author hal.hildebrand
 */
public record MigrationResult(boolean success, String entityId, UUID destProcessId, Long latencyMs, String reason) {

    /**
     * Create a successful migration result.
     *
     * @param entityId     Entity that migrated
     * @param destProcessId Destination process
     * @param latencyMs    Migration latency
     * @return Success result
     */
    public static MigrationResult success(String entityId, UUID destProcessId, long latencyMs) {
        return new MigrationResult(true, entityId, destProcessId, latencyMs, null);
    }

    /**
     * Create a failed migration result.
     *
     * @param entityId Entity that failed to migrate
     * @param reason   Failure reason
     * @return Failure result
     */
    public static MigrationResult failure(String entityId, String reason) {
        return new MigrationResult(false, entityId, null, null, reason);
    }

    /**
     * Create an "already applied" result (idempotency duplicate).
     *
     * @param entityId Entity ID
     * @return Already applied result
     */
    public static MigrationResult alreadyApplied(String entityId) {
        return new MigrationResult(true, entityId, null, 0L, "Already applied (idempotency)");
    }

    /**
     * Create an "already migrating" result (concurrent migration of same entity blocked).
     *
     * @param entityId Entity ID
     * @return Already migrating result
     */
    public static MigrationResult alreadyMigrating(String entityId) {
        return new MigrationResult(false, entityId, null, null, "Already migrating (lock held)");
    }
}
