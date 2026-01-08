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

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Record of a duplicate entity detected across multiple bubbles.
 * <p>
 * Contains:
 * <ul>
 *   <li>Entity identifier (String ID)</li>
 *   <li>Set of bubble UUIDs where entity was found</li>
 *   <li>Latest migration record from MigrationLog (if available)</li>
 * </ul>
 * <p>
 * Used by DuplicateEntityDetector to track and reconcile duplicates.
 * <p>
 * Thread-safe: Immutable record with defensive copies of mutable collections.
 *
 * @param entityId        Entity identifier (user-provided String ID)
 * @param locations       Set of bubble UUIDs where entity was found (defensive copy)
 * @param latestMigration Latest migration record from MigrationLog (Optional)
 *
 * @author hal.hildebrand
 */
public record DuplicateEntity(
    String entityId,
    Set<UUID> locations,
    Optional<MigrationLog.MigrationRecord> latestMigration
) {

    /**
     * Compact constructor for validation and defensive copying.
     */
    public DuplicateEntity {
        if (entityId == null || entityId.isBlank()) {
            throw new IllegalArgumentException("Entity ID cannot be null or blank");
        }
        if (locations == null || locations.size() < 2) {
            throw new IllegalArgumentException("Duplicate entity must be in at least 2 locations");
        }

        // Defensive copy to ensure immutability
        locations = Set.copyOf(locations);

        if (latestMigration == null) {
            latestMigration = Optional.empty();
        }
    }

    /**
     * Get the source bubble UUID from the latest migration.
     * <p>
     * Returns the target bubble from the latest migration (where entity was migrated TO),
     * which is the authoritative source-of-truth for the entity's current location.
     *
     * @return Optional containing source bubble UUID, or empty if no migration history
     */
    public Optional<UUID> getSourceBubble() {
        return latestMigration.map(MigrationLog.MigrationRecord::targetBubble);
    }

    /**
     * Check if this duplicate has migration history.
     *
     * @return true if MigrationLog has record for this entity
     */
    public boolean hasMigrationHistory() {
        return latestMigration.isPresent();
    }

    /**
     * Get number of duplicate locations.
     *
     * @return Count of bubbles containing this entity (>= 2)
     */
    public int duplicateCount() {
        return locations.size();
    }

    @Override
    public String toString() {
        return String.format(
            "DuplicateEntity{id='%s', locations=%s, hasMigrationHistory=%s}",
            entityId,
            locations,
            hasMigrationHistory()
        );
    }
}
