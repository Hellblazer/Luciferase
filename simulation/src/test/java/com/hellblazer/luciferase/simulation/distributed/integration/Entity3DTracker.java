/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.simulation.distributed.integration;

import javafx.geometry.Point3D;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Tracks entity behavior and transitions across 3D topology.
 * Monitors:
 * - Entity positions and movements
 * - Cross-process migrations
 * - Ghost entity creation
 * - AOI interactions
 */
class Entity3DTracker implements AutoCloseable {

    private final TestProcessTopology topology;
    private final Map<UUID, EntityState> entityStates = new ConcurrentHashMap<>();
    private final List<MigrationEvent> migrationLog = Collections.synchronizedList(new ArrayList<>());
    private final Map<UUID, List<Point3D>> positionHistory = new ConcurrentHashMap<>();

    Entity3DTracker(TestProcessTopology topology) {
        this.topology = topology;
    }

    /**
     * Track entity migrations across process boundaries.
     * Calls callback when entity migrates to a different process.
     */
    List<Entity3DTopologySimulationTest.Entity3D> trackMigrations(
        List<Entity3DTopologySimulationTest.Entity3D> entities,
        MigrationCallback callback) {

        // Initialize entity states
        for (var entity : entities) {
            var processId = topology.getProcessForBubble(entity.bubbleId());
            entityStates.put(entity.id(), new EntityState(entity.id(), entity.bubbleId(), processId));
        }

        // Simulate position tracking (in real test, would be from actual simulation)
        for (var entity : entities) {
            trackPositionHistory(entity);
        }

        return entities;
    }

    /**
     * Track entity position history.
     */
    void trackPositionHistory(Entity3DTopologySimulationTest.Entity3D entity) {
        var history = positionHistory.computeIfAbsent(entity.id(), k -> new ArrayList<>());
        history.add(entity.position());
    }

    /**
     * Get migration events for an entity.
     */
    List<MigrationEvent> getMigrations(UUID entityId) {
        return migrationLog.stream()
            .filter(e -> e.entityId.equals(entityId))
            .toList();
    }

    /**
     * Get position history for an entity.
     */
    List<Point3D> getPositionHistory(UUID entityId) {
        return positionHistory.getOrDefault(entityId, new ArrayList<>());
    }

    /**
     * Get all entities that have migrated across process boundaries.
     */
    Set<UUID> getMigratedEntities() {
        return migrationLog.stream()
            .map(e -> e.entityId)
            .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * Get migration statistics.
     */
    MigrationStatistics getStatistics() {
        var totalMigrations = migrationLog.size();
        var uniqueEntities = getMigratedEntities().size();
        var crossProcessMigrations = migrationLog.stream()
            .filter(e -> !e.sourceBubble.equals(e.targetBubble))
            .count();

        return new MigrationStatistics(totalMigrations, uniqueEntities, (int) crossProcessMigrations);
    }

    /**
     * Record a migration event.
     */
    void recordMigration(UUID entityId, UUID fromBubble, UUID toBubble, UUID fromProcess, UUID toProcess) {
        var state = entityStates.get(entityId);
        if (state != null) {
            state.bubbleId = toBubble;
            state.processId = toProcess;
        }
        migrationLog.add(new MigrationEvent(entityId, fromBubble, toBubble, fromProcess, toProcess));
    }

    /**
     * Check if entity has crossed process boundary.
     */
    boolean hasCrossedBoundary(UUID entityId) {
        return getMigrations(entityId).stream()
            .anyMatch(e -> !e.sourceProcess.equals(e.targetProcess));
    }

    /**
     * Find entities that have migrated along a specific path.
     */
    List<UUID> findEntitiesAlongPath(UUID processA, UUID processB) {
        var neighbors = topology.getNeighborProcesses(processA);
        if (!neighbors.contains(processB)) {
            return new ArrayList<>();  // Not adjacent
        }

        return migrationLog.stream()
            .filter(e -> (e.sourceProcess.equals(processA) && e.targetProcess.equals(processB)) ||
                        (e.sourceProcess.equals(processB) && e.targetProcess.equals(processA)))
            .map(e -> e.entityId)
            .distinct()
            .toList();
    }

    /**
     * Get entities that might interact (within AOI distance).
     */
    Map<UUID, List<UUID>> findPotentialInteractions(
        List<Entity3DTopologySimulationTest.Entity3D> entities, double aoiRadius) {

        var interactions = new HashMap<UUID, List<UUID>>();

        for (int i = 0; i < entities.size(); i++) {
            var entity1 = entities.get(i);
            var nearby = new ArrayList<UUID>();

            for (int j = 0; j < entities.size(); j++) {
                if (i == j) continue;
                var entity2 = entities.get(j);
                var distance = entity1.position().distance(entity2.position());
                if (distance <= aoiRadius) {
                    nearby.add(entity2.id());
                }
            }

            if (!nearby.isEmpty()) {
                interactions.put(entity1.id(), nearby);
            }
        }

        return interactions;
    }

    @Override
    public void close() {
        // Cleanup resources if needed
        entityStates.clear();
        migrationLog.clear();
        positionHistory.clear();
    }

    // ==================== Inner Classes ====================

    /**
     * Entity state tracking.
     */
    static class EntityState {
        UUID id;
        UUID bubbleId;
        UUID processId;
        long createdAt;
        long lastModified;

        EntityState(UUID id, UUID bubbleId, UUID processId) {
            this.id = id;
            this.bubbleId = bubbleId;
            this.processId = processId;
            this.createdAt = System.currentTimeMillis();
            this.lastModified = createdAt;
        }
    }

    /**
     * Migration event record.
     */
    record MigrationEvent(
        UUID entityId,
        UUID sourceBubble,
        UUID targetBubble,
        UUID sourceProcess,
        UUID targetProcess
    ) {
    }

    /**
     * Migration statistics.
     */
    record MigrationStatistics(
        int totalMigrations,
        int uniqueEntities,
        int crossProcessMigrations
    ) {
    }

    /**
     * Callback for migration events.
     */
    @FunctionalInterface
    interface MigrationCallback {
        void onMigration(UUID entityId, UUID fromProcess, UUID toProcess);
    }
}
