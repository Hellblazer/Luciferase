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
 * Defines the topology of bubbles and processes for distributed simulation.
 * <p>
 * Organizes processes and bubbles in a 2D grid layout where:
 * <ul>
 *   <li>Processes are arranged in a grid (2x2 for 4 processes, 4x2 for 8 processes)</li>
 *   <li>Each process contains N bubbles stacked vertically</li>
 *   <li>Neighbor relationships are based on spatial adjacency</li>
 * </ul>
 * <p>
 * Phase 6B5.2: TestProcessCluster Infrastructure
 * Phase 6B6: 8-Process Scaling & GC Benchmarking
 * <p>
 * Grid Layout Examples:
 * <pre>
 * 4 processes (2x2):          8 processes (4x2):
 *   P0-B1  P1-B1               P0-B1  P1-B1  P2-B1  P3-B1
 *   P0-B2  P1-B2               P0-B2  P1-B2  P2-B2  P3-B2
 *   P2-B1  P3-B1               P4-B1  P5-B1  P6-B1  P7-B1
 *   P2-B2  P3-B2               P4-B2  P5-B2  P6-B2  P7-B2
 * </pre>
 *
 * @author hal.hildebrand
 */
public class TestProcessTopology {

    private static final float BUBBLE_SPACING = 100.0f;
    private static final float BUBBLE_RADIUS = 45.0f;

    private final int processCount;
    private final int bubblesPerProcess;
    private final List<UUID> processIds;
    private final Map<UUID, BubbleInfo> bubbles;
    private final Map<UUID, Set<UUID>> bubbleNeighbors;
    private final Map<UUID, UUID> bubbleToProcess;
    private final Map<UUID, Set<UUID>> processNeighbors;

    /**
     * Creates a new topology with the specified dimensions.
     *
     * @param processCount      number of processes
     * @param bubblesPerProcess number of bubbles per process
     */
    public TestProcessTopology(int processCount, int bubblesPerProcess) {
        this.processCount = processCount;
        this.bubblesPerProcess = bubblesPerProcess;
        this.processIds = new ArrayList<>();
        this.bubbles = new LinkedHashMap<>();
        this.bubbleNeighbors = new HashMap<>();
        this.bubbleToProcess = new HashMap<>();
        this.processNeighbors = new HashMap<>();

        initializeTopology();
    }

    private void initializeTopology() {
        // Generate process IDs
        for (int i = 0; i < processCount; i++) {
            processIds.add(generateDeterministicUUID("process", i));
        }

        // Create bubbles for each process
        for (int p = 0; p < processCount; p++) {
            var processId = processIds.get(p);
            for (int b = 0; b < bubblesPerProcess; b++) {
                var bubbleId = generateDeterministicUUID("bubble", p * bubblesPerProcess + b);
                var position = calculateBubblePosition(p, b);
                bubbles.put(bubbleId, new BubbleInfo(bubbleId, processId, position, BUBBLE_RADIUS));
                bubbleToProcess.put(bubbleId, processId);
                bubbleNeighbors.put(bubbleId, new HashSet<>());
            }
        }

        // Calculate bubble neighbors based on spatial proximity
        calculateBubbleNeighbors();

        // Calculate process neighbors (processes that share bubble boundaries)
        calculateProcessNeighbors();
    }

    private UUID generateDeterministicUUID(String prefix, int index) {
        // Generate deterministic UUIDs for reproducibility
        var seed = (prefix + "-" + index).hashCode();
        var random = new Random(seed);
        return new UUID(random.nextLong(), random.nextLong());
    }

    private Point3D calculateBubblePosition(int processIndex, int bubbleIndex) {
        // Layout: processes along X axis, bubbles stacked along Y axis
        var x = processIndex * BUBBLE_SPACING;
        var y = bubbleIndex * BUBBLE_SPACING;
        var z = 0.0f;
        return new Point3D(x, y, z);
    }

    private void calculateBubbleNeighbors() {
        var bubbleList = new ArrayList<>(bubbles.keySet());

        for (int i = 0; i < bubbleList.size(); i++) {
            var bubbleA = bubbleList.get(i);
            var infoA = bubbles.get(bubbleA);

            for (int j = i + 1; j < bubbleList.size(); j++) {
                var bubbleB = bubbleList.get(j);
                var infoB = bubbles.get(bubbleB);

                // Check if bubbles are adjacent (distance <= BUBBLE_SPACING * 1.5)
                var distance = infoA.position().distance(infoB.position());
                if (distance <= BUBBLE_SPACING * 1.5) {
                    bubbleNeighbors.get(bubbleA).add(bubbleB);
                    bubbleNeighbors.get(bubbleB).add(bubbleA);
                }
            }
        }
    }

    private void calculateProcessNeighbors() {
        for (var processId : processIds) {
            processNeighbors.put(processId, new HashSet<>());
        }

        // For a 2D grid layout, create deterministic neighbor relationships
        // With 4 processes in a 2x2 grid:
        // P0 -- P1
        // |     |
        // P2 -- P3
        //
        // Neighbors: P0<->P1, P0<->P2, P1<->P3, P2<->P3
        // Each process has exactly 2 neighbors

        if (processCount == 4) {
            // 2x2 grid layout
            addProcessNeighbor(0, 1); // P0 <-> P1
            addProcessNeighbor(0, 2); // P0 <-> P2
            addProcessNeighbor(1, 3); // P1 <-> P3
            addProcessNeighbor(2, 3); // P2 <-> P3
        } else if (processCount == 8) {
            // 4x2 grid layout (4 columns, 2 rows)
            // P0 -- P1 -- P2 -- P3  (Row 0)
            // |     |     |     |
            // P4 -- P5 -- P6 -- P7  (Row 1)
            //
            // Horizontal edges (Row 0): P0-P1, P1-P2, P2-P3 (3 edges)
            // Horizontal edges (Row 1): P4-P5, P5-P6, P6-P7 (3 edges)
            // Vertical edges: P0-P4, P1-P5, P2-P6, P3-P7 (4 edges)
            // Total: 10 edges

            // Row 0 horizontal neighbors
            addProcessNeighbor(0, 1); // P0 <-> P1
            addProcessNeighbor(1, 2); // P1 <-> P2
            addProcessNeighbor(2, 3); // P2 <-> P3

            // Row 1 horizontal neighbors
            addProcessNeighbor(4, 5); // P4 <-> P5
            addProcessNeighbor(5, 6); // P5 <-> P6
            addProcessNeighbor(6, 7); // P6 <-> P7

            // Vertical neighbors
            addProcessNeighbor(0, 4); // P0 <-> P4
            addProcessNeighbor(1, 5); // P1 <-> P5
            addProcessNeighbor(2, 6); // P2 <-> P6
            addProcessNeighbor(3, 7); // P3 <-> P7
        } else {
            // Fallback to bubble-based neighbor detection for other sizes
            for (var entry : bubbleNeighbors.entrySet()) {
                var bubbleId = entry.getKey();
                var neighbors = entry.getValue();
                var processId = bubbleToProcess.get(bubbleId);

                for (var neighborBubble : neighbors) {
                    var neighborProcess = bubbleToProcess.get(neighborBubble);
                    if (!neighborProcess.equals(processId)) {
                        processNeighbors.get(processId).add(neighborProcess);
                    }
                }
            }
        }
    }

    private void addProcessNeighbor(int indexA, int indexB) {
        var processA = processIds.get(indexA);
        var processB = processIds.get(indexB);
        processNeighbors.get(processA).add(processB);
        processNeighbors.get(processB).add(processA);
    }

    /**
     * Gets the total number of processes.
     *
     * @return process count
     */
    public int getProcessCount() {
        return processCount;
    }

    /**
     * Gets the process ID at the given index.
     *
     * @param index process index (0-based)
     * @return process UUID
     */
    public UUID getProcessId(int index) {
        return processIds.get(index);
    }

    /**
     * Gets the total number of bubbles.
     *
     * @return bubble count
     */
    public int getBubbleCount() {
        return bubbles.size();
    }

    /**
     * Gets all bubble IDs.
     *
     * @return set of bubble UUIDs
     */
    public Set<UUID> getAllBubbleIds() {
        return bubbles.keySet();
    }

    /**
     * Gets the bubbles hosted by a process.
     *
     * @param processId process UUID
     * @return set of bubble UUIDs
     */
    public Set<UUID> getBubblesForProcess(UUID processId) {
        var result = new HashSet<UUID>();
        for (var entry : bubbleToProcess.entrySet()) {
            if (entry.getValue().equals(processId)) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    /**
     * Gets the process that hosts a bubble.
     *
     * @param bubbleId bubble UUID
     * @return process UUID or null if not found
     */
    public UUID getProcessForBubble(UUID bubbleId) {
        return bubbleToProcess.get(bubbleId);
    }

    /**
     * Gets the neighbors of a bubble.
     *
     * @param bubbleId bubble UUID
     * @return set of neighbor bubble UUIDs
     */
    public Set<UUID> getNeighbors(UUID bubbleId) {
        return bubbleNeighbors.getOrDefault(bubbleId, Set.of());
    }

    /**
     * Gets the position of a bubble.
     *
     * @param bubbleId bubble UUID
     * @return Point3D position or null if not found
     */
    public Point3D getPosition(UUID bubbleId) {
        var info = bubbles.get(bubbleId);
        return info != null ? info.position() : null;
    }

    /**
     * Gets the neighbor processes for a process.
     *
     * @param processId process UUID
     * @return set of neighbor process UUIDs
     */
    public Set<UUID> getNeighborProcesses(UUID processId) {
        return processNeighbors.getOrDefault(processId, Set.of());
    }

    /**
     * Gets the bubble info for a specific bubble.
     *
     * @param bubbleId bubble UUID
     * @return BubbleInfo or null if not found
     */
    public BubbleInfo getBubbleInfo(UUID bubbleId) {
        return bubbles.get(bubbleId);
    }

    /**
     * Information about a bubble in the topology.
     *
     * @param bubbleId  bubble UUID
     * @param processId hosting process UUID
     * @param position  3D position
     * @param radius    bubble radius
     */
    public record BubbleInfo(UUID bubbleId, UUID processId, Point3D position, float radius) {
    }
}
