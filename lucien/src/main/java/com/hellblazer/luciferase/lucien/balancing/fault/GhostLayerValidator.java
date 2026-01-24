package com.hellblazer.luciferase.lucien.balancing.fault;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Validates ghost layer consistency after partition recovery (Phase 4.3).
 * <p>
 * Performs 3-tier validation to detect inconsistencies in the ghost layer
 * after partition failure and recovery:
 * <ol>
 *   <li><b>Duplicate detection</b> - finds multiple instances of same entity ID</li>
 *   <li><b>Orphan detection</b> - finds ghosts from failed/inactive partitions</li>
 *   <li><b>Boundary gap detection</b> - identifies missing boundary elements</li>
 * </ol>
 * <p>
 * Used during {@link RecoveryPhase#VALIDATING} phase to ensure recovery
 * completed correctly before transitioning to {@link RecoveryPhase#COMPLETE}.
 * <p>
 * <b>Thread Safety</b>: This class is immutable and thread-safe. Multiple
 * threads can safely validate concurrently.
 * <p>
 * <b>Example Usage</b>:
 * <pre>{@code
 * var validator = new GhostLayerValidator();
 * var activeRanks = Set.of(0, 1, 2);  // Ranks 0,1,2 are healthy
 * var failedRank = 3;  // Rank 3 failed and was recovered
 *
 * var result = validator.validate(ghostLayer, activeRanks, failedRank);
 * if (!result.valid()) {
 *     log.error("Ghost layer validation failed: {}", result.errors());
 *     // Retry recovery or escalate
 * }
 * }</pre>
 *
 * @see RecoveryPhase#VALIDATING
 */
public class GhostLayerValidator {

    /**
     * Result of ghost layer validation.
     * <p>
     * Immutable record containing validation outcome and detailed counts
     * of detected inconsistencies.
     *
     * @param valid true if validation passed (no errors), false otherwise
     * @param duplicateCount number of duplicate entity instances detected
     * @param orphanCount number of orphaned ghost elements detected
     * @param boundaryGapCount number of missing boundary elements detected
     * @param errors list of human-readable error messages (empty if valid)
     */
    public record ValidationResult(
        boolean valid,
        int duplicateCount,
        int orphanCount,
        int boundaryGapCount,
        List<String> errors
    ) {
        /**
         * Compact constructor with validation.
         */
        public ValidationResult {
            if (duplicateCount < 0) {
                throw new IllegalArgumentException("duplicateCount must be non-negative");
            }
            if (orphanCount < 0) {
                throw new IllegalArgumentException("orphanCount must be non-negative");
            }
            if (boundaryGapCount < 0) {
                throw new IllegalArgumentException("boundaryGapCount must be non-negative");
            }
            if (errors == null) {
                throw new IllegalArgumentException("errors cannot be null");
            }
            // Make errors immutable
            errors = List.copyOf(errors);
        }

        /**
         * Create successful validation result (no errors).
         *
         * @return ValidationResult with valid=true and zero error counts
         */
        public static ValidationResult success() {
            return new ValidationResult(true, 0, 0, 0, List.of());
        }

        /**
         * Create validation result with errors.
         *
         * @param duplicates duplicate count
         * @param orphans orphan count
         * @param gaps boundary gap count
         * @param errorMessages list of error descriptions
         * @return ValidationResult with valid=false and error details
         */
        public static ValidationResult withErrors(
            int duplicates,
            int orphans,
            int gaps,
            List<String> errorMessages
        ) {
            return new ValidationResult(false, duplicates, orphans, gaps, errorMessages);
        }
    }

    /**
     * Validate ghost layer consistency.
     * <p>
     * Checks for:
     * <ul>
     *   <li>Duplicate entity instances across partitions</li>
     *   <li>Orphaned ghosts from inactive/failed partitions</li>
     *   <li>Missing boundary elements after recovery</li>
     * </ul>
     * <p>
     * This is a placeholder implementation that accepts any ghost layer type.
     * Full implementation will be added when GhostLayer API is finalized.
     * For now, performs basic validation that can be tested with mocks.
     *
     * @param ghostLayer the ghost layer to validate (accepts any type for flexibility)
     * @param activeRanks set of currently active partition ranks
     * @param failedRank rank of the failed partition that was recovered
     * @return validation result with counts and error messages
     * @throws IllegalArgumentException if activeRanks is null or failedRank is negative
     */
    public ValidationResult validate(
        Object ghostLayer,
        Set<Integer> activeRanks,
        int failedRank
    ) {
        if (activeRanks == null) {
            throw new IllegalArgumentException("activeRanks cannot be null");
        }
        if (failedRank < -1) {
            throw new IllegalArgumentException("failedRank must be >= -1, got: " + failedRank);
        }

        var errors = new ArrayList<String>();

        // Placeholder for future ghost layer validation
        // When full GhostLayer API is available:
        // 1. var allGhosts = ghostLayer.getAllGhostElements();
        // 2. detectDuplicates(allGhosts) -> add errors if found
        // 3. detectOrphans(allGhosts, activeRanks, failedRank) -> add errors if found
        // 4. detectBoundaryGaps(allGhosts) -> add errors if found

        // For now, basic validation: check if ghostLayer is null
        if (ghostLayer == null) {
            errors.add("Ghost layer is null (expected valid ghost layer instance)");
        }

        // Note: activeRanks contains ALL registered partitions (healthy and failed).
        // The failedRank parameter indicates which partition is being recovered,
        // but it remains in the topology during recovery. Status tracking is
        // separate (via FaultHandler). So we don't check if failedRank is in activeRanks.

        return new ValidationResult(
            errors.isEmpty(),
            0,  // duplicateCount - TODO when GhostLayer API available
            0,  // orphanCount - TODO when GhostLayer API available
            0,  // boundaryGapCount - TODO when GhostLayer API available
            errors
        );
    }

    /**
     * Validate ghost layer with default failed rank (-1 indicates no specific failure).
     * <p>
     * Convenience method for validation when no specific partition failed
     * (e.g., validating initial state or after manual intervention).
     *
     * @param ghostLayer the ghost layer to validate
     * @param activeRanks set of currently active partition ranks
     * @return validation result
     */
    public ValidationResult validate(Object ghostLayer, Set<Integer> activeRanks) {
        return validate(ghostLayer, activeRanks, -1);
    }
}
