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

import java.util.*;
import java.util.stream.Collectors;

/**
 * Validates entity consistency after crash recovery.
 * <p>
 * Checks that:
 * - No entity exists in multiple bubbles (duplicates are prevented)
 * - All entities are accounted for (none lost)
 * - Entity counts remain consistent
 * <p>
 * Used after recovery completes to verify system health.
 */
public class EntityConsistencyValidator {

    /**
     * Validate that no entity exists in multiple bubbles.
     * <p>
     * Scans all bubbles for entities and checks for duplicates.
     * Used after crash recovery to ensure 2PC protocol preserved entity uniqueness.
     *
     * @param bubbles Map of bubble ID to local bubble reference
     * @return ValidationResult with success flag and message
     */
    public static ValidationResult validateNoDuplicates(Map<UUID, LocalBubbleReference> bubbles) {
        var entityLocations = new HashMap<String, List<UUID>>();

        // Scan all bubbles for entities
        for (var entry : bubbles.entrySet()) {
            var bubbleId = entry.getKey();
            var bubble = entry.getValue();

            try {
                var entities = bubble.getEntityIds();
                for (var entityId : entities) {
                    entityLocations.computeIfAbsent(entityId, k -> new ArrayList<>())
                        .add(bubbleId);
                }
            } catch (Exception e) {
                return new ValidationResult(
                    false,
                    "Failed to scan bubble " + bubbleId + ": " + e.getMessage()
                );
            }
        }

        // Check for duplicates
        var duplicates = entityLocations.entrySet().stream()
            .filter(e -> e.getValue().size() > 1)
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        if (duplicates.isEmpty()) {
            var entityCount = entityLocations.size();
            return new ValidationResult(
                true,
                "No duplicates found. Total entities: " + entityCount
            );
        } else {
            return new ValidationResult(
                false,
                "Found duplicate entities in " + duplicates.size() + " cases: " + duplicates
            );
        }
    }

    /**
     * Validate entity count consistency.
     * <p>
     * Compares entity count before and after recovery to detect losses.
     *
     * @param beforeCount Entity count before recovery
     * @param afterCount  Entity count after recovery
     * @return ValidationResult with success flag
     */
    public static ValidationResult validateEntityCount(int beforeCount, int afterCount) {
        if (beforeCount == afterCount) {
            return new ValidationResult(
                true,
                "Entity count consistent: " + beforeCount
            );
        } else {
            return new ValidationResult(
                false,
                "Entity count mismatch: before=" + beforeCount + ", after=" + afterCount
            );
        }
    }

    /**
     * Validation result with success flag and diagnostic message.
     *
     * @param success Whether validation passed
     * @param message Diagnostic message (success or error details)
     */
    public record ValidationResult(
        boolean success,
        String message
    ) {
    }

    /**
     * Local bubble reference for validation (minimal interface).
     * <p>
     * Used to abstract away LocalBubble implementation details during validation.
     */
    public interface LocalBubbleReference {
        /**
         * Get all entity IDs in this bubble.
         *
         * @return Set of entity IDs
         * @throws Exception If bubble cannot be accessed
         */
        Set<String> getEntityIds() throws Exception;
    }
}
