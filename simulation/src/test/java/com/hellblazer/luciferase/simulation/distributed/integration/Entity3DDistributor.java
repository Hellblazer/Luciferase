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

/**
 * Distributes entities across 3D topology for testing.
 * Supports multiple distribution strategies (round-robin, balanced, skewed, spatial).
 */
class Entity3DDistributor {

    private final TestProcessTopology topology;
    private final Map<UUID, Entity3DTopologySimulationTest.Entity3D> entities = new LinkedHashMap<>();
    private final Random random = new Random(42);  // Deterministic for reproducibility

    Entity3DDistributor(TestProcessTopology topology) {
        this.topology = topology;
    }

    /**
     * Create entities with specified distribution mode.
     */
    List<Entity3DTopologySimulationTest.Entity3D> createEntities(
        int count, Entity3DTopologySimulationTest.EntityDistributionMode mode) {
        entities.clear();

        return switch (mode) {
            case ROUND_ROBIN -> distributeRoundRobin(count);
            case PROCESS_BALANCED -> distributeBalancedByProcess(count);
            case SKEWED_4HEAVY -> distributeSkewed4Heavy(count);
            case SPATIAL_CLUSTERED -> distributeSpatialClusters(count);
        };
    }

    /**
     * Create entities within a specific process's bubbles.
     */
    List<Entity3DTopologySimulationTest.Entity3D> createEntitiesInProcess(int count, UUID processId) {
        var bubbles = topology.getBubblesForProcess(processId);
        var bubbleList = new ArrayList<>(bubbles);
        var created = new ArrayList<Entity3DTopologySimulationTest.Entity3D>();

        for (int i = 0; i < count; i++) {
            var bubble = bubbleList.get(i % bubbleList.size());
            var entity = createEntityInBubble(bubble);
            entities.put(entity.id(), entity);
            created.add(entity);
        }

        return created;
    }

    /**
     * Create entities within a specific bubble.
     */
    List<Entity3DTopologySimulationTest.Entity3D> createEntitiesInBubble(int count, UUID bubbleId) {
        var created = new ArrayList<Entity3DTopologySimulationTest.Entity3D>();
        for (int i = 0; i < count; i++) {
            var entity = createEntityInBubble(bubbleId);
            entities.put(entity.id(), entity);
            created.add(entity);
        }
        return created;
    }

    /**
     * Create entities near a boundary between two processes.
     */
    List<Entity3DTopologySimulationTest.Entity3D> createEntitiesNearBoundary(
        int count, UUID process0, UUID process1) {
        var bubbles0 = topology.getBubblesForProcess(process0).stream().toList();
        var bubbles1 = topology.getBubblesForProcess(process1).stream().toList();

        var created = new ArrayList<Entity3DTopologySimulationTest.Entity3D>();
        for (int i = 0; i < count; i++) {
            // Alternate between processes to create boundary entities
            var bubble = (i % 2 == 0) ? bubbles0.get(i % bubbles0.size())
                                      : bubbles1.get(i % bubbles1.size());
            var entity = createEntityInBubble(bubble);
            entities.put(entity.id(), entity);
            created.add(entity);
        }

        return created;
    }

    /**
     * Get current distribution across bubbles.
     */
    Map<UUID, Integer> getDistribution() {
        var distribution = new HashMap<UUID, Integer>();
        for (var entity : entities.values()) {
            distribution.put(entity.bubbleId(), distribution.getOrDefault(entity.bubbleId(), 0) + 1);
        }
        return distribution;
    }

    /**
     * Get distribution grouped by process.
     */
    Map<UUID, Integer> getDistributionPerProcess() {
        var distribution = new HashMap<UUID, Integer>();
        for (var entity : entities.values()) {
            var processId = topology.getProcessForBubble(entity.bubbleId());
            distribution.put(processId, distribution.getOrDefault(processId, 0) + 1);
        }
        return distribution;
    }

    // ==================== Distribution Strategies ====================

    private List<Entity3DTopologySimulationTest.Entity3D> distributeRoundRobin(int count) {
        var bubbles = new ArrayList<>(topology.getAllBubbleIds());
        var created = new ArrayList<Entity3DTopologySimulationTest.Entity3D>();

        for (int i = 0; i < count; i++) {
            var bubble = bubbles.get(i % bubbles.size());
            var entity = createEntityInBubble(bubble);
            entities.put(entity.id(), entity);
            created.add(entity);
        }

        return created;
    }

    private List<Entity3DTopologySimulationTest.Entity3D> distributeBalancedByProcess(int count) {
        var processes = new ArrayList<UUID>();
        for (int i = 0; i < topology.getProcessCount(); i++) {
            processes.add(topology.getProcessId(i));
        }

        var perProcess = count / processes.size();
        var created = new ArrayList<Entity3DTopologySimulationTest.Entity3D>();

        for (var processId : processes) {
            var bubbles = topology.getBubblesForProcess(processId).stream().toList();
            for (int i = 0; i < perProcess; i++) {
                var bubble = bubbles.get(i % bubbles.size());
                var entity = createEntityInBubble(bubble);
                entities.put(entity.id(), entity);
                created.add(entity);
            }
        }

        return created;
    }

    private List<Entity3DTopologySimulationTest.Entity3D> distributeSkewed4Heavy(int count) {
        var bubbles = new ArrayList<>(topology.getAllBubbleIds());
        var created = new ArrayList<Entity3DTopologySimulationTest.Entity3D>();

        // 4 heavy bubbles get 80% of entities, 12 light get 20%
        var heavyBubbles = new ArrayList<UUID>();
        for (int i = 0; i < 4; i++) {
            heavyBubbles.add(bubbles.get(i * 4));  // Spread heavy bubbles across topology
        }

        var heavyCount = (int) (count * 0.8 / 4);
        var lightCount = (int) (count * 0.2 / 12);

        // Create heavy entities
        for (var heavy : heavyBubbles) {
            for (int i = 0; i < heavyCount; i++) {
                var entity = createEntityInBubble(heavy);
                entities.put(entity.id(), entity);
                created.add(entity);
            }
        }

        // Create light entities
        var lightBubbles = bubbles.stream()
            .filter(b -> !heavyBubbles.contains(b))
            .toList();
        for (var light : lightBubbles) {
            for (int i = 0; i < lightCount; i++) {
                var entity = createEntityInBubble(light);
                entities.put(entity.id(), entity);
                created.add(entity);
            }
        }

        return created;
    }

    private List<Entity3DTopologySimulationTest.Entity3D> distributeSpatialClusters(int count) {
        // Create spatial clusters based on 3D bubble positions
        var bubbles = new ArrayList<>(topology.getAllBubbleIds());
        var created = new ArrayList<Entity3DTopologySimulationTest.Entity3D>();

        // Group bubbles by spatial proximity (simple: by process)
        for (int p = 0; p < topology.getProcessCount(); p++) {
            var processId = topology.getProcessId(p);
            var processBubbles = topology.getBubblesForProcess(processId).stream().toList();
            var entitiesPerBubble = count / (topology.getProcessCount() * processBubbles.size());

            for (var bubble : processBubbles) {
                for (int i = 0; i < entitiesPerBubble; i++) {
                    var entity = createEntityInBubble(bubble);
                    entities.put(entity.id(), entity);
                    created.add(entity);
                }
            }
        }

        return created;
    }

    // ==================== Entity Creation ====================

    private Entity3DTopologySimulationTest.Entity3D createEntityInBubble(UUID bubbleId) {
        var position = topology.getPosition(bubbleId);
        if (position == null) {
            position = new Point3D(0, 0, 0);
        }

        // Add small random offset within bubble region
        var offset = 20.0;
        var x = position.getX() + (random.nextDouble() - 0.5) * offset;
        var y = position.getY() + (random.nextDouble() - 0.5) * offset;
        var z = position.getZ() + (random.nextDouble() - 0.5) * offset;

        var id = UUID.randomUUID();
        return new Entity3DTopologySimulationTest.Entity3D(id, new Point3D(x, y, z), bubbleId);
    }
}
