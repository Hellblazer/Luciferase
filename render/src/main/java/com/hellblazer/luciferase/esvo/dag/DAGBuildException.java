/*
 * Copyright (c) 2024 Hal Hildebrand. All rights reserved.
 * This file is part of Luciferase, licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See LICENSE file for details.
 */

package com.hellblazer.luciferase.esvo.dag;

/**
 * Sealed exception hierarchy for DAG build operations.
 * <p>
 * Permits only specific, well-defined exception types to ensure
 * robust error handling and type-safe exception catching.
 * <p>
 * Exception types:
 * <ul>
 * <li>{@link InvalidInputException} - Invalid input SVO (null, empty, malformed)</li>
 * <li>{@link OutOfMemoryException} - Insufficient memory during DAG build</li>
 * <li>{@link MemoryBudgetExceededException} - Memory budget exceeded during compression</li>
 * <li>{@link BuildTimeoutException} - DAG build exceeded time limit</li>
 * <li>{@link CorruptedDataException} - Input SVO is corrupted (invalid pointers, circular refs)</li>
 * <li>{@link ValidationFailedException} - DAG validation failed (compression ratio, structural integrity)</li>
 * </ul>
 */
public sealed class DAGBuildException extends RuntimeException
    permits DAGBuildException.InvalidInputException,
            DAGBuildException.OutOfMemoryException,
            DAGBuildException.MemoryBudgetExceededException,
            DAGBuildException.BuildTimeoutException,
            DAGBuildException.CorruptedDataException,
            DAGBuildException.ValidationFailedException {

    /**
     * Constructs a new DAG build exception with the specified detail message.
     *
     * @param message the detail message
     */
    public DAGBuildException(String message) {
        super(message);
    }

    /**
     * Constructs a new DAG build exception with the specified detail message and cause.
     *
     * @param message the detail message
     * @param cause   the cause
     */
    public DAGBuildException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Constructs a new DAG build exception with the specified cause.
     *
     * @param cause the cause
     */
    public DAGBuildException(Throwable cause) {
        super(cause);
    }

    /**
     * Invalid input SVO exception.
     * <p>
     * Thrown when the input SVO is null, empty, or malformed.
     */
    public static final class InvalidInputException extends DAGBuildException {

        /**
         * Constructs a new invalid input exception with the specified detail message.
         *
         * @param message the detail message
         */
        public InvalidInputException(String message) {
            super(message);
        }

        /**
         * Constructs a new invalid input exception with the specified detail message and cause.
         *
         * @param message the detail message
         * @param cause   the cause
         */
        public InvalidInputException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Out of memory exception.
     * <p>
     * Thrown when there is insufficient memory during DAG build.
     */
    public static final class OutOfMemoryException extends DAGBuildException {
        private final long requiredBytes;
        private final long availableBytes;

        /**
         * Constructs a new out of memory exception with memory statistics.
         *
         * @param required  bytes required
         * @param available bytes available
         */
        public OutOfMemoryException(long required, long available) {
            super(String.format(
                "Insufficient memory: need %,d bytes, available %,d bytes (%.1fGB needed, %.1fGB available)",
                required, available, required / 1e9, available / 1e9));
            this.requiredBytes = required;
            this.availableBytes = available;
        }

        /**
         * Constructs a new out of memory exception with the specified cause.
         *
         * @param cause the cause
         */
        public OutOfMemoryException(Throwable cause) {
            super("Out of memory during DAG build", cause);
            this.requiredBytes = 0;
            this.availableBytes = 0;
        }

        /**
         * Gets the number of bytes required.
         *
         * @return required bytes
         */
        public long getRequiredBytes() {
            return requiredBytes;
        }

        /**
         * Gets the number of bytes available.
         *
         * @return available bytes
         */
        public long getAvailableBytes() {
            return availableBytes;
        }
    }

    /**
     * Build timeout exception.
     * <p>
     * Thrown when DAG build exceeds the configured time limit.
     */
    public static final class BuildTimeoutException extends DAGBuildException {

        /**
         * Constructs a new build timeout exception with the specified detail message.
         *
         * @param message the detail message
         */
        public BuildTimeoutException(String message) {
            super(message);
        }

        /**
         * Constructs a new build timeout exception with timing information.
         *
         * @param timeoutMs timeout limit in milliseconds
         * @param elapsedMs elapsed time in milliseconds
         */
        public BuildTimeoutException(long timeoutMs, long elapsedMs) {
            super(String.format("Build timeout: exceeded %,dms limit (elapsed: %,dms)",
                timeoutMs, elapsedMs));
        }
    }

    /**
     * Corrupted data exception.
     * <p>
     * Thrown when the input SVO is corrupted (invalid pointers, circular references, etc).
     */
    public static final class CorruptedDataException extends DAGBuildException {

        /**
         * Constructs a new corrupted data exception with the specified detail message.
         *
         * @param message the detail message
         */
        public CorruptedDataException(String message) {
            super(message);
        }

        /**
         * Constructs a new corrupted data exception with the specified detail message and cause.
         *
         * @param message the detail message
         * @param cause   the cause
         */
        public CorruptedDataException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Memory budget exceeded exception.
     * <p>
     * Thrown when compression would exceed the configured memory budget.
     */
    public static final class MemoryBudgetExceededException extends DAGBuildException {
        private final long budgetBytes;
        private final long estimatedBytes;

        /**
         * Constructs a new memory budget exceeded exception with budget information.
         *
         * @param budget    configured memory budget in bytes
         * @param estimated estimated memory usage in bytes
         */
        public MemoryBudgetExceededException(long budget, long estimated) {
            super(String.format(
                "Memory budget exceeded: budget=%,d bytes (%.1fGB), estimated=%,d bytes (%.1fGB)",
                budget, budget / 1e9, estimated, estimated / 1e9));
            this.budgetBytes = budget;
            this.estimatedBytes = estimated;
        }

        /**
         * Gets the configured memory budget.
         *
         * @return budget in bytes
         */
        public long getBudgetBytes() {
            return budgetBytes;
        }

        /**
         * Gets the estimated memory usage.
         *
         * @return estimated usage in bytes
         */
        public long getEstimatedBytes() {
            return estimatedBytes;
        }
    }

    /**
     * Validation failed exception.
     * <p>
     * Thrown when DAG validation fails (compression ratio below threshold,
     * structural integrity violated, etc).
     */
    public static final class ValidationFailedException extends DAGBuildException {

        /**
         * Constructs a new validation failed exception with the specified detail message.
         *
         * @param message the detail message
         */
        public ValidationFailedException(String message) {
            super(message);
        }

        /**
         * Constructs a new validation failed exception with the specified detail message and cause.
         *
         * @param message the detail message
         * @param cause   the cause
         */
        public ValidationFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
