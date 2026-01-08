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

import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.bubble.TetreeBubbleGrid;
import com.hellblazer.luciferase.simulation.entity.StringEntityID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects and reconciles duplicate entities across multiple bubbles.
 * <p>
 * Duplicate entities arise from migration failures (see TetrahedralMigration.executeMigration()
 * JavaDoc for failure modes). This detector provides:
 * <ul>
 *   <li><b>O(n) Scanning</b> - Scan all entities across all bubbles</li>
 *   <li><b>Source Determination</b> - Use MigrationLog to identify authoritative bubble</li>
 *   <li><b>Reconciliation</b> - Remove duplicates from non-source bubbles</li>
 *   <li><b>Metrics Tracking</b> - Count duplicates detected/resolved</li>
 * </ul>
 * <p>
 * <b>Algorithm</b>:
 * <ol>
 *   <li>Scan all bubbles, collect entity IDs with their bubble locations</li>
 *   <li>Identify entities in 2+ bubbles (duplicates)</li>
 *   <li>For each duplicate, query MigrationLog for source bubble</li>
 *   <li>Remove entity from all non-source bubbles</li>
 *   <li>If no migration log, keep in all locations and flag for review</li>
 * </ol>
 * <p>
 * <b>Reconciliation Strategy (SOURCE_BUBBLE)</b>:
 * <pre>
 * latestMigration = migrationLog.getLatestMigration(entityId)
 * sourceBubble = latestMigration.targetBubble()  // Where entity was migrated TO
 * for (bubble : otherBubbles) {
 *     bubble.removeEntity(entityId)  // Remove from all except source
 * }
 * </pre>
 * <p>
 * <b>Fallback</b>: If no MigrationLog entry exists:
 * <ul>
 *   <li>Keep entity in all locations (do not arbitrarily choose)</li>
 *   <li>Log warning for manual review</li>
 *   <li>Metrics flag for visibility</li>
 * </ul>
 * <p>
 * <b>Phase 6 Limitation</b>: Single-process only. Distributed simulation requires
 * consensus protocol for cross-process reconciliation.
 * <p>
 * Thread-safe: Uses concurrent data structures for scanning.
 *
 * @author hal.hildebrand
 */
public class DuplicateEntityDetector {

    private static final Logger log = LoggerFactory.getLogger(DuplicateEntityDetector.class);

    private final TetreeBubbleGrid bubbleGrid;
    private final MigrationLog migrationLog;
    private final DuplicateDetectionConfig config;
    private final DuplicateEntityMetrics metrics;

    /**
     * Create a duplicate entity detector.
     *
     * @param bubbleGrid   Bubble grid for topology
     * @param migrationLog Migration log for source-of-truth determination
     * @param config       Detection configuration
     */
    public DuplicateEntityDetector(
        TetreeBubbleGrid bubbleGrid,
        MigrationLog migrationLog,
        DuplicateDetectionConfig config
    ) {
        this.bubbleGrid = Objects.requireNonNull(bubbleGrid, "BubbleGrid cannot be null");
        this.migrationLog = Objects.requireNonNull(migrationLog, "MigrationLog cannot be null");
        this.config = Objects.requireNonNull(config, "Config cannot be null");
        this.metrics = new DuplicateEntityMetrics();
    }

    /**
     * Scan all bubbles for duplicate entities.
     * <p>
     * Algorithm: O(n) where n = total entity count across all bubbles.
     * <ol>
     *   <li>Iterate all bubbles</li>
     *   <li>For each bubble, collect entity IDs and bubble UUID</li>
     *   <li>Group entities by ID, track which bubbles contain each</li>
     *   <li>Filter to entities in 2+ bubbles</li>
     *   <li>For each duplicate, fetch latest migration from log</li>
     * </ol>
     *
     * @param bubbles Collection of bubbles to scan
     * @return List of duplicate entities detected
     */
    public List<DuplicateEntity> scan(Collection<EnhancedBubble> bubbles) {
        // Map: entityId -> Set of bubble UUIDs containing it
        var entityLocations = new ConcurrentHashMap<String, Set<UUID>>();

        // Scan all bubbles
        for (var bubble : bubbles) {
            var bubbleId = bubble.id();
            var entityIds = bubble.getEntities();  // Returns Set<String>

            for (var entityId : entityIds) {
                entityLocations.computeIfAbsent(entityId, k -> ConcurrentHashMap.newKeySet())
                               .add(bubbleId);
            }
        }

        // Filter to duplicates (2+ locations)
        var duplicates = new ArrayList<DuplicateEntity>();

        for (var entry : entityLocations.entrySet()) {
            var entityId = entry.getKey();
            var locations = entry.getValue();

            if (locations.size() >= 2) {
                // Query migration log for latest migration
                var latestMigration = migrationLog.getLatestMigration(
                    new StringEntityID(entityId)
                );

                var duplicate = new DuplicateEntity(entityId, locations, latestMigration);
                duplicates.add(duplicate);

                if (config.logLevel() == DuplicateDetectionConfig.LogLevel.DEBUG) {
                    log.debug("Duplicate detected: entity={} locations={} hasMigrationHistory={}",
                             entityId, locations, latestMigration.isPresent());
                }
            }
        }

        return duplicates;
    }

    /**
     * Reconcile a duplicate entity using SOURCE_BUBBLE strategy.
     * <p>
     * Strategy:
     * <ol>
     *   <li>Determine source bubble from MigrationLog.latestMigration.targetBubble</li>
     *   <li>Remove entity from all bubbles EXCEPT source</li>
     *   <li>If no migration log, keep in all locations (fallback)</li>
     * </ol>
     *
     * @param duplicate Duplicate entity to reconcile
     * @return Number of duplicate copies removed (0 if fallback)
     */
    public int reconcile(DuplicateEntity duplicate) {
        var entityId = duplicate.entityId();
        var locations = duplicate.locations();

        // Determine source bubble
        var sourceBubbleOpt = duplicate.getSourceBubble();

        if (sourceBubbleOpt.isEmpty()) {
            // Fallback: No migration history, cannot determine source
            if (config.logLevel() != DuplicateDetectionConfig.LogLevel.ERROR) {
                log.warn("Reconciliation fallback: entity={} locations={} - no migration history, keeping in all locations",
                        entityId, locations);
            }
            return 0;  // No removal
        }

        var sourceBubble = sourceBubbleOpt.get();
        var removedCount = 0;

        // Remove from all non-source bubbles
        for (var bubbleId : locations) {
            if (!bubbleId.equals(sourceBubble)) {
                // Find bubble by ID and remove entity
                var bubble = findBubbleById(bubbleId);
                if (bubble != null) {
                    try {
                        bubble.removeEntity(entityId);
                        removedCount++;

                        if (config.logLevel() != DuplicateDetectionConfig.LogLevel.ERROR) {
                            log.info("Reconciled duplicate: entity={} source={} removed_from={}",
                                    entityId, sourceBubble, bubbleId);
                        }
                    } catch (Exception e) {
                        log.error("Failed to remove duplicate: entity={} bubble={}", entityId, bubbleId, e);
                    }
                } else {
                    log.error("Bubble not found during reconciliation: entity={} bubble={}", entityId, bubbleId);
                }
            }
        }

        if (config.logLevel() == DuplicateDetectionConfig.LogLevel.DEBUG) {
            log.debug("Reconciliation complete: entity={} source={} removed_count={} other_locations={}",
                     entityId, sourceBubble, removedCount,
                     locations.stream().filter(id -> !id.equals(sourceBubble)).toList());
        }

        return removedCount;
    }

    /**
     * Detect and reconcile all duplicates in a single operation.
     * <p>
     * Combines scan() and reconcile() for atomic detection + resolution.
     *
     * @param bubbles Collection of bubbles to process
     * @return Detection result with counts
     */
    public DuplicateDetectionResult detectAndReconcile(Collection<EnhancedBubble> bubbles) {
        var duplicates = scan(bubbles);
        var totalDetected = duplicates.size();
        var totalResolved = 0;
        var totalRemoved = 0;

        for (var duplicate : duplicates) {
            // Record detection
            metrics.recordDetected(duplicate.duplicateCount());

            // Reconcile
            var removed = reconcile(duplicate);
            totalRemoved += removed;

            if (removed > 0) {
                totalResolved++;
                metrics.recordResolved(removed);
            }
        }

        // Update metrics atomically
        var result = new DuplicateDetectionResult(totalDetected, totalResolved, totalRemoved);

        if (config.logLevel() != DuplicateDetectionConfig.LogLevel.ERROR && totalDetected > 0) {
            log.info("Duplicate detection cycle: {}", result);
        }

        return result;
    }

    /**
     * Get current metrics.
     *
     * @return Metrics instance
     */
    public DuplicateEntityMetrics getMetrics() {
        return metrics;
    }

    /**
     * Find bubble by UUID (helper method).
     */
    private EnhancedBubble findBubbleById(UUID bubbleId) {
        for (var bubble : bubbleGrid.getAllBubbles()) {
            if (bubble.id().equals(bubbleId)) {
                return bubble;
            }
        }
        return null;
    }

    /**
     * Result of a duplicate detection cycle.
     *
     * @param duplicatesDetected Count of duplicate entities found
     * @param duplicatesResolved Count of duplicates successfully reconciled
     * @param copiesRemoved      Total number of duplicate copies removed
     */
    public record DuplicateDetectionResult(
        int duplicatesDetected,
        int duplicatesResolved,
        int copiesRemoved
    ) {
        @Override
        public String toString() {
            return String.format("DuplicateDetectionResult{detected=%d, resolved=%d, removed=%d}",
                                duplicatesDetected, duplicatesResolved, copiesRemoved);
        }
    }
}
