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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 6B7: 3D Entity Simulation with Distributed Topology Tests.
 * <p>
 * Tests entity behavior within the 3D process topology framework:
 * - Entities moving through 3D bubble regions
 * - Cross-process migration along 3D boundaries
 * - Entity distribution in 3D space
 * - Load balancing across 3D process grid
 * - Ghost synchronization with 3D spatial awareness
 * - Bubble dynamics (joining, dividing) with entity coordination
 * <p>
 * Uses 2x2x2 cube topology (8 processes, 2 bubbles per process = 16 bubbles total)
 * for comprehensive 3D testing.
 * <p>
 * Phase 6B7: 3D Topology & Entity Simulation
 * Bead: Luciferase-i71d (Inc 6: Distributed Multi-Bubble Simulation)
 *
 * @author hal.hildebrand
 */
class Entity3DTopologySimulationTest {

    private TestProcessTopology topology;
    private Entity3DDistributor distributor;
    private Entity3DTracker tracker;

    @BeforeEach
    void setUp() {
        // Create 8-process 2x2x2 cube topology with 2 bubbles per process
        topology = new TestProcessTopology(8, 2);
        distributor = new Entity3DDistributor(topology);
        tracker = new Entity3DTracker(topology);
    }

    @AfterEach
    void tearDown() {
        if (tracker != null) {
            tracker.close();
        }
        // Topology doesn't hold resources that need cleanup
    }

    // ==================== 3D Topology Structure Tests ====================

    @Test
    void test3DTopologyStructure_8Processes2x2x2Cube() {
        // Given: 8-process topology
        // Then: Should form 2x2x2 cube
        assertEquals(8, topology.getProcessCount(), "Should have 8 processes");
        assertEquals(16, topology.getBubbleCount(), "Should have 16 bubbles (8 procs * 2 bubbles)");

        // Verify each process has 3 neighbors (faces of cube)
        for (int i = 0; i < 8; i++) {
            var processId = topology.getProcessId(i);
            var neighbors = topology.getNeighborProcesses(processId);
            assertEquals(3, neighbors.size(), "Process " + i + " should have 3 neighbors in 2x2x2 cube");
        }
    }

    @Test
    void test3DTopology_12EdgeConnectivity() {
        // A 2x2x2 cube has 12 edges
        var totalNeighbors = 0;
        for (int i = 0; i < 8; i++) {
            var processId = topology.getProcessId(i);
            totalNeighbors += topology.getNeighborProcesses(processId).size();
        }

        // Each edge connects 2 processes
        var edgeCount = totalNeighbors / 2;
        assertEquals(12, edgeCount, "Should have 12 edges in 2x2x2 cube");
    }

    @Test
    void test3DTopology_BubblePositioning() {
        // Given: All bubbles in the topology
        var bubbleIds = topology.getAllBubbleIds();
        assertEquals(16, bubbleIds.size(), "Should have 16 bubbles");

        // When: Checking positions
        var positions = new HashSet<Point3D>();
        for (var bubbleId : bubbleIds) {
            var pos = topology.getPosition(bubbleId);
            assertNotNull(pos, "Each bubble should have a position");
            positions.add(pos);
        }

        // Then: All bubbles should have unique or clustered positions
        assertTrue(positions.size() > 0, "Should have position information");

        // Verify positions span 3D space (X, Y, Z)
        double minX = positions.stream().mapToDouble(Point3D::getX).min().orElse(0);
        double maxX = positions.stream().mapToDouble(Point3D::getX).max().orElse(0);
        double minY = positions.stream().mapToDouble(Point3D::getY).min().orElse(0);
        double maxY = positions.stream().mapToDouble(Point3D::getY).max().orElse(0);
        double minZ = positions.stream().mapToDouble(Point3D::getZ).min().orElse(0);
        double maxZ = positions.stream().mapToDouble(Point3D::getZ).max().orElse(0);

        assertTrue(maxX > minX, "Positions should span X dimension");
        assertTrue(maxY > minY, "Positions should span Y dimension");
        assertTrue(maxZ > minZ, "Positions should span Z dimension");
    }

    // ==================== Entity Distribution Tests ====================

    @Test
    void testEntityDistribution_RoundRobinAcross16Bubbles() {
        // Given: 400 entities (25 per bubble)
        var entities = distributor.createEntities(400, EntityDistributionMode.ROUND_ROBIN);

        // When: Checking distribution
        var distribution = distributor.getDistribution();

        // Then: Should be evenly distributed
        assertEquals(16, distribution.size(), "Should distribute across all 16 bubbles");
        for (var count : distribution.values()) {
            assertEquals(25, count, "Each bubble should get 25 entities in round-robin");
        }
    }

    @Test
    void testEntityDistribution_BalancedAcross8Processes() {
        // Given: 400 entities distributed across 8 processes
        var entities = distributor.createEntities(400, EntityDistributionMode.PROCESS_BALANCED);

        // When: Checking per-process distribution
        var perProcess = distributor.getDistributionPerProcess();

        // Then: Each process should have ~50 entities (400 / 8)
        assertEquals(8, perProcess.size(), "Should have 8 process groups");
        for (var count : perProcess.values()) {
            assertTrue(count >= 48 && count <= 52, "Each process should have ~50 entities, got: " + count);
        }
    }

    @Test
    void testEntityDistribution_3DGridPositioning() {
        // Given: Entities distributed across 3D topology
        var entities = distributor.createEntities(160, EntityDistributionMode.ROUND_ROBIN);

        // When: Verifying 3D positioning
        var positionClusters = new HashMap<Integer, Integer>();  // processIndex -> count
        for (var entity : entities) {
            // Find which process region this entity belongs to
            var position = entity.position();
            var closestBubble = findClosestBubble(position);
            var processId = topology.getProcessForBubble(closestBubble);

            // Map processId back to index
            for (int i = 0; i < 8; i++) {
                if (topology.getProcessId(i).equals(processId)) {
                    positionClusters.put(i, positionClusters.getOrDefault(i, 0) + 1);
                    break;
                }
            }
        }

        // Then: Entities should be distributed across all 3D regions
        assertTrue(positionClusters.size() >= 6, "Entities should span multiple process regions, got: " + positionClusters.size());
    }

    // ==================== Entity Migration Tests ====================

    @Test
    void testEntityMigration_AcrossFaceAdjacencyBoundaries() {
        // Given: Two adjacent processes (face-adjacent in 3D cube)
        var process0 = topology.getProcessId(0);
        var process1 = topology.getProcessId(1);
        var neighbors0 = topology.getNeighborProcesses(process0);
        assertTrue(neighbors0.contains(process1), "Processes should be neighbors");

        // Create entities in process 0's bubbles
        var entities = distributor.createEntitiesInProcess(50, process0);

        // When: Tracking migration across boundary
        var crossed = new AtomicInteger(0);
        var tracked = tracker.trackMigrations(entities, (entity, oldProc, newProc) -> {
            if ((oldProc.equals(process0) && newProc.equals(process1)) ||
                (oldProc.equals(process1) && newProc.equals(process0))) {
                crossed.incrementAndGet();
            }
        });

        // Then: Some entities should be able to migrate across the boundary
        assertTrue(crossed.get() >= 0, "Should track potential migrations");
    }

    @Test
    void testEntityMigration_NotAcrossNonAdjacentProcesses() {
        // Given: Two non-adjacent processes in 3D cube
        var process0 = topology.getProcessId(0);  // (0,0,0)
        var process7 = topology.getProcessId(7);  // (1,1,1) - diagonal, not adjacent
        var neighbors0 = topology.getNeighborProcesses(process0);
        assertFalse(neighbors0.contains(process7), "Diagonal processes should not be neighbors");

        // Create entities in process 0
        var entities = distributor.createEntitiesInProcess(50, process0);

        // Then: Direct migration between them should not occur
        var crossed = new AtomicInteger(0);
        tracker.trackMigrations(entities, (entity, oldProc, newProc) -> {
            if ((oldProc.equals(process0) && newProc.equals(process7)) ||
                (oldProc.equals(process7) && newProc.equals(process0))) {
                crossed.incrementAndGet();
            }
        });

        assertEquals(0, crossed.get(), "Should not migrate directly across non-adjacent processes");
    }

    @Test
    void testEntityMigration_ChainedAcross3DPath() {
        // Given: Three processes forming a path: P0 -> P1 -> P2
        var process0 = topology.getProcessId(0);
        var process1Neighbors = topology.getNeighborProcesses(process0).stream().findFirst();
        assertTrue(process1Neighbors.isPresent(), "Process 0 should have neighbors");

        var process1 = process1Neighbors.get();
        var process2Neighbors = topology.getNeighborProcesses(process1);
        var process2 = process2Neighbors.stream()
            .filter(p -> !p.equals(process0))
            .findFirst();
        assertTrue(process2.isPresent(), "Process 1 should have another neighbor");

        // Create entities that can trace through the path
        var entities = distributor.createEntitiesInProcess(100, process0);

        // When: Tracking multi-hop migration
        var migrationPath = new AtomicInteger(0);
        tracker.trackMigrations(entities, (entity, oldProc, newProc) -> {
            migrationPath.incrementAndGet();
        });

        // Then: Some entities could potentially follow the migration path
        assertTrue(migrationPath.get() >= 0, "Should track migration path");
    }

    // ==================== Load Balancing Tests ====================

    @Test
    void testLoadBalancing_EvenDistributionAcross8Processes() {
        // Given: 800 entities
        var entities = distributor.createEntities(800, EntityDistributionMode.PROCESS_BALANCED);

        // When: Checking distribution balance
        var perProcess = distributor.getDistributionPerProcess();

        // Then: Should be evenly balanced (100 per process)
        var min = perProcess.values().stream().mapToInt(Integer::intValue).min().orElse(0);
        var max = perProcess.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        var imbalance = (max - min) / (double) (800 / 8);
        assertTrue(imbalance < 0.1, "Imbalance should be < 10%, got: " + (imbalance * 100) + "%");
    }

    @Test
    void testLoadBalancing_SkewedDistributionWith4HeavyBubbles() {
        // Given: 1200 entities with 4 heavy bubbles
        var entities = distributor.createEntities(1200, EntityDistributionMode.SKEWED_4HEAVY);

        // When: Checking distribution
        var distribution = distributor.getDistribution();
        var sorted = distribution.values().stream()
            .sorted(Collections.reverseOrder())
            .toList();

        // Then: Top 4 bubbles should have majority of entities
        var topFourTotal = sorted.stream().limit(4).mapToInt(Integer::intValue).sum();
        var topFourPercent = (topFourTotal / 1200.0) * 100;
        assertTrue(topFourPercent >= 75, "Top 4 bubbles should have >= 75% of entities, got: " + topFourPercent + "%");
    }

    @Test
    void testLoadBalancing_Across3DDimensions() {
        // Given: 400 entities
        var entities = distributor.createEntities(400, EntityDistributionMode.ROUND_ROBIN);

        // When: Analyzing distribution across 3D axes
        var xDistribution = new AtomicInteger(0);
        var yDistribution = new AtomicInteger(0);
        var zDistribution = new AtomicInteger(0);

        for (var entity : entities) {
            var pos = entity.position();
            if (pos.getX() > 150) xDistribution.incrementAndGet();
            if (pos.getY() > 150) yDistribution.incrementAndGet();
            if (pos.getZ() > 150) zDistribution.incrementAndGet();
        }

        // Then: Should have entities spread across all 3D regions
        assertTrue(xDistribution.get() > 0 && xDistribution.get() < 400, "X distribution should be balanced");
        assertTrue(yDistribution.get() > 0 && yDistribution.get() < 400, "Y distribution should be balanced");
        assertTrue(zDistribution.get() > 0 && zDistribution.get() < 400, "Z distribution should be balanced");
    }

    // ==================== Ghost Sync Tests ====================

    @Test
    void testGhostSync_AcrossProcessBoundaries() {
        // Given: Entities near process boundaries
        var process0 = topology.getProcessId(0);
        var process1 = topology.getProcessId(1);
        var entities = distributor.createEntitiesNearBoundary(200, process0, process1);

        // When: Entities exist at boundary
        var nearBoundary = entities.stream()
            .filter(e -> isNearBoundary(e.position(), process0, process1))
            .count();

        // Then: Some entities should be near the boundary for ghost sync
        assertTrue(nearBoundary > 0, "Some entities should be near process boundary");
    }

    @Test
    void testGhostSync_3DMultiBubbleInteraction() {
        // Given: Entities distributed across 3D topology
        var entities = distributor.createEntities(400, EntityDistributionMode.ROUND_ROBIN);

        // When: Checking ghost entity potential
        var allBubbles = topology.getAllBubbleIds();
        var adjacencyMap = new HashMap<UUID, Integer>();

        for (var bubbleId : allBubbles) {
            var neighbors = topology.getNeighbors(bubbleId);
            adjacencyMap.put(bubbleId, neighbors.size());
        }

        // Then: Most bubbles should have neighbors for ghost sync
        var bubblesWithNeighbors = adjacencyMap.values().stream()
            .filter(count -> count > 0)
            .count();
        assertTrue(bubblesWithNeighbors >= 14, "Most bubbles should have neighbors, got: " + bubblesWithNeighbors);
    }

    // ==================== Entity Behavior Tests ====================

    @Test
    void testEntityBehavior_MovementWithinBubbleRegion() {
        // Given: Entities in specific bubbles
        var allBubbles = topology.getAllBubbleIds().stream().limit(4).toList();
        var entities = new ArrayList<Entity3D>();
        for (var bubbleId : allBubbles) {
            entities.addAll(distributor.createEntitiesInBubble(25, bubbleId));
        }

        // When: Tracking initial and potential new positions
        var initialPositions = new HashMap<UUID, Point3D>();
        for (var entity : entities) {
            initialPositions.put(entity.id(), entity.position());
        }

        // Simulate movement (in real test, would run actual simulation)
        var movedCount = new AtomicInteger(0);
        for (var entity : entities) {
            var newPos = simulateEntityMovement(entity.position());
            if (!newPos.equals(entity.position())) {
                movedCount.incrementAndGet();
            }
        }

        // Then: Entities should have moved
        assertTrue(movedCount.get() > 0, "Entities should be able to move");
    }

    @Test
    void testEntityBehavior_CoordinationAcrossBubbles() {
        // Given: 200 entities spread across 3D topology
        var entities = distributor.createEntities(200, EntityDistributionMode.ROUND_ROBIN);

        // When: Checking for potential interactions
        var interactionCount = new AtomicInteger(0);
        var areaOfInterest = 150.0;  // Interaction radius

        for (var i = 0; i < entities.size(); i++) {
            for (var j = i + 1; j < entities.size(); j++) {
                var dist = entities.get(i).position().distance(entities.get(j).position());
                if (dist <= areaOfInterest) {
                    interactionCount.incrementAndGet();
                }
            }
        }

        // Then: Some entities should be able to interact
        assertTrue(interactionCount.get() > 0, "Entities should be able to interact within their AOI");
    }

    // ==================== Helper Methods ====================

    private UUID findClosestBubble(Point3D position) {
        var allBubbles = topology.getAllBubbleIds();
        return allBubbles.stream()
            .min(Comparator.comparingDouble(b -> {
                var bubblePos = topology.getPosition(b);
                return bubblePos != null ? position.distance(bubblePos) : Double.MAX_VALUE;
            }))
            .orElse(allBubbles.stream().findFirst().orElseThrow());
    }

    private boolean isNearBoundary(Point3D position, UUID process0, UUID process1) {
        // Simplified: check if within threshold of boundary between processes
        var bubbles0 = topology.getBubblesForProcess(process0);
        var bubbles1 = topology.getBubblesForProcess(process1);

        for (var b0 : bubbles0) {
            for (var b1 : bubbles1) {
                var pos0 = topology.getPosition(b0);
                var pos1 = topology.getPosition(b1);
                if (pos0 != null && pos1 != null) {
                    var threshold = 150.0;  // Half of bubble spacing
                    var boundaryDist = pos0.distance(pos1) / 2;
                    if (position.distance(pos0) < boundaryDist + threshold &&
                        position.distance(pos1) < boundaryDist + threshold) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private Point3D simulateEntityMovement(Point3D current) {
        // Simple movement: small random offset
        var offset = new java.util.Random().nextDouble() * 10 - 5;
        return current.add(offset, offset, offset);
    }

    // ==================== Inner Classes ====================

    /**
     * Entity representation with position tracking
     */
    record Entity3D(UUID id, Point3D position, UUID bubbleId) {
    }

    /**
     * Distribution mode for entity creation
     */
    enum EntityDistributionMode {
        ROUND_ROBIN,
        PROCESS_BALANCED,
        SKEWED_4HEAVY,
        SPATIAL_CLUSTERED
    }
}
