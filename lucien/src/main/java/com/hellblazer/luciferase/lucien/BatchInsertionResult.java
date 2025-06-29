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

import com.hellblazer.luciferase.lucien.entity.EntityID;

import java.util.List;
import java.util.Map;

/**
 * Result of a batch insertion operation, providing detailed information about the operation's outcome.
 *
 * @param <ID> The type of EntityID used for entity identification
 * @author hal.hildebrand
 */
public class BatchInsertionResult<ID extends EntityID> {

    private final List<ID>             insertedIds;
    private final int                  successCount;
    private final int                  failureCount;
    private final Map<Integer, String> failures;
    private final long                 elapsedTimeNanos;
    private final int                  nodesCreated;
    private final int                  nodesModified;
    private final boolean              subdivisionDeferred;

    private BatchInsertionResult(Builder<ID> builder) {
        this.insertedIds = builder.insertedIds;
        this.successCount = builder.successCount;
        this.failureCount = builder.failureCount;
        this.failures = builder.failures;
        this.elapsedTimeNanos = builder.elapsedTimeNanos;
        this.nodesCreated = builder.nodesCreated;
        this.nodesModified = builder.nodesModified;
        this.subdivisionDeferred = builder.subdivisionDeferred;
    }

    /**
     * Get the elapsed time in milliseconds.
     */
    public double getElapsedTimeMillis() {
        return elapsedTimeNanos / 1_000_000.0;
    }

    /**
     * Get the total elapsed time in nanoseconds.
     */
    public long getElapsedTimeNanos() {
        return elapsedTimeNanos;
    }

    /**
     * Get the number of failed insertions.
     */
    public int getFailureCount() {
        return failureCount;
    }

    /**
     * Get details about failures, mapping input index to error message.
     */
    public Map<Integer, String> getFailures() {
        return failures;
    }

    /**
     * Get the list of successfully inserted entity IDs in order.
     */
    public List<ID> getInsertedIds() {
        return insertedIds;
    }

    /**
     * Calculate the insertion rate in entities per second.
     */
    public double getInsertionRate() {
        if (elapsedTimeNanos == 0) {
            return 0.0;
        }
        return successCount * 1_000_000_000.0 / elapsedTimeNanos;
    }

    /**
     * Get the number of new nodes created during insertion.
     */
    public int getNodesCreated() {
        return nodesCreated;
    }

    /**
     * Get the number of existing nodes modified during insertion.
     */
    public int getNodesModified() {
        return nodesModified;
    }

    /**
     * Get the number of successful insertions.
     */
    public int getSuccessCount() {
        return successCount;
    }

    /**
     * Get a summary string of the operation results.
     */
    public String getSummary() {
        return String.format(
        "BatchInsertionResult[success=%d, failed=%d, nodes created=%d, modified=%d, time=%.2fms, rate=%.0f/sec]",
        successCount, failureCount, nodesCreated, nodesModified, getElapsedTimeMillis(), getInsertionRate());
    }

    /**
     * Check if all insertions were successful.
     */
    public boolean isCompleteSuccess() {
        return failureCount == 0;
    }

    /**
     * Check if subdivision was deferred during this operation.
     */
    public boolean isSubdivisionDeferred() {
        return subdivisionDeferred;
    }

    @Override
    public String toString() {
        return getSummary();
    }

    /**
     * Builder for BatchInsertionResult.
     */
    public static class Builder<ID extends EntityID> {
        private List<ID>             insertedIds;
        private int                  successCount;
        private int                  failureCount;
        private Map<Integer, String> failures = Map.of();
        private long                 elapsedTimeNanos;
        private int                  nodesCreated;
        private int                  nodesModified;
        private boolean              subdivisionDeferred;

        public BatchInsertionResult<ID> build() {
            return new BatchInsertionResult<>(this);
        }

        public Builder<ID> withElapsedTimeNanos(long nanos) {
            this.elapsedTimeNanos = nanos;
            return this;
        }

        public Builder<ID> withFailureCount(int count) {
            this.failureCount = count;
            return this;
        }

        public Builder<ID> withFailures(Map<Integer, String> failures) {
            this.failures = failures;
            return this;
        }

        public Builder<ID> withInsertedIds(List<ID> ids) {
            this.insertedIds = ids;
            return this;
        }

        public Builder<ID> withNodesCreated(int count) {
            this.nodesCreated = count;
            return this;
        }

        public Builder<ID> withNodesModified(int count) {
            this.nodesModified = count;
            return this;
        }

        public Builder<ID> withSubdivisionDeferred(boolean deferred) {
            this.subdivisionDeferred = deferred;
            return this;
        }

        public Builder<ID> withSuccessCount(int count) {
            this.successCount = count;
            return this;
        }
    }
}
