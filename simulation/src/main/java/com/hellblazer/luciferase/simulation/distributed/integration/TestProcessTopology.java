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
 * Organizes processes and bubbles in a 3D cube layout where:
 * <ul>
 *   <li>Processes are arranged in a 3D grid (2x2x1 for 4 processes, 2x2x2 for 8 processes)</li>
 *   <li>Each process contains N bubbles stacked vertically within its region</li>
 *   <li>Neighbor relationships are based on 3D spatial adjacency (face neighbors only)</li>
 * </ul>
 * <p>
 * Phase 6B5.2: TestProcessCluster Infrastructure
 * Phase 6B6: 8-Process Scaling & GC Benchmarking
 * Phase 6B7: 3D Topology & Entity Simulation
 * <p>
 * Grid Layout Examples:
 * <pre>
 * 4 processes (2x2x1):          8 processes (2x2x2):
 *
 * Z=0:                          Z=1 (Top):
 *   P0 --- P1                      P4 --- P5
 *   |       |                      |       |
 *   P2 --- P3                      P6 --- P7
 *
 *                                 Z=0 (Bottom):
 *                                   P0 --- P1
 *                                   |       |
 *                                   P2 --- P3
 *
 * Each bubble in a process is stacked along the local Z-axis within that process's region.
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
    private final int gridWidth;  // X dimension
    private final int gridHeight; // Y dimension
    private final int gridDepth;  // Z dimension
    private final Map<Integer, int[]> processToGridCoords; // process index -> [x, y, z]

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
        this.processToGridCoords = new HashMap<>();

        // Calculate grid dimensions for 3D topology
        var dims = calculateGridDimensions(processCount);
        this.gridWidth = dims[0];
        this.gridHeight = dims[1];
        this.gridDepth = dims[2];

        initializeTopology();
    }

    private void initializeTopology() {
        // Generate process IDs and map to 3D grid coordinates
        for (int i = 0; i < processCount; i++) {
            processIds.add(generateDeterministicUUID("process", i));
            var coords = getProcessGridCoordinates(i);
            processToGridCoords.put(i, coords);
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

        // Calculate process neighbors based on 3D grid adjacency
        calculateProcessNeighbors();
    }

    /**
     * Calculates grid dimensions for 3D topology based on process count.
     * Returns [width, height, depth] for X, Y, Z dimensions.
     *
     * @param count number of processes
     * @return [gridWidth, gridHeight, gridDepth]
     */
    private int[] calculateGridDimensions(int count) {
        return switch (count) {
            case 1 -> new int[]{1, 1, 1};   // 1x1x1
            case 2 -> new int[]{2, 1, 1};   // 2x1x1
            case 4 -> new int[]{2, 2, 1};   // 2x2x1 (backward compatible)
            case 8 -> new int[]{2, 2, 2};   // 2x2x2 (true 3D cube)
            default -> {
                // Fallback: approximate cube root
                int side = (int) Math.round(Math.cbrt(count));
                yield new int[]{side, side, side};
            }
        };
    }

    /**
     * Converts a process index to 3D grid coordinates [x, y, z].
     * Maps processes sequentially in order: z varies fastest, then y, then x.
     *
     * @param processIndex the process index
     * @return [x, y, z] coordinates in the grid
     */
    private int[] getProcessGridCoordinates(int processIndex) {
        int x = (processIndex / (gridHeight * gridDepth)) % gridWidth;
        int y = (processIndex / gridDepth) % gridHeight;
        int z = processIndex % gridDepth;
        return new int[]{x, y, z};
    }

    private UUID generateDeterministicUUID(String prefix, int index) {
        // Generate deterministic UUIDs for reproducibility
        var seed = (prefix + "-" + index).hashCode();
        var random = new Random(seed);
        return new UUID(random.nextLong(), random.nextLong());
    }

    /**
     * Calculates the position of a bubble in 3D space.
     * Each process occupies a region in 3D space, and bubbles are stacked vertically within that region.
     *
     * @param processIndex the process index
     * @param bubbleIndex  the bubble index within the process
     * @return 3D position
     */
    private Point3D calculateBubblePosition(int processIndex, int bubbleIndex) {
        var coords = processToGridCoords.get(processIndex);
        int gridX = coords[0];
        int gridY = coords[1];
        int gridZ = coords[2];

        // Each process region is BUBBLE_SPACING in each dimension
        var regionX = gridX * BUBBLE_SPACING;
        var regionY = gridY * BUBBLE_SPACING;
        var regionZ = gridZ * BUBBLE_SPACING;

        // Within the process region, stack bubbles vertically (add to Y axis)
        var bubbleLocalY = bubbleIndex * BUBBLE_SPACING;

        // Return the center of the bubble's region
        return new Point3D(
            regionX + BUBBLE_SPACING / 2.0,
            regionY + bubbleLocalY + BUBBLE_SPACING / 2.0,
            regionZ + BUBBLE_SPACING / 2.0
        );
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

    /**
     * Calculates process neighbors based on 3D grid adjacency.
     * Two processes are neighbors if they are face-adjacent in the 3D grid
     * (differ by 1 in exactly one dimension).
     */
    private void calculateProcessNeighbors() {
        for (var processId : processIds) {
            processNeighbors.put(processId, new HashSet<>());
        }

        // Check all pairs of processes for 3D grid adjacency
        for (int i = 0; i < processCount; i++) {
            var coordsI = processToGridCoords.get(i);

            for (int j = i + 1; j < processCount; j++) {
                var coordsJ = processToGridCoords.get(j);

                // Check if processes are face-adjacent (differ by 1 in exactly one dimension)
                if (isAdjacent3D(coordsI, coordsJ)) {
                    addProcessNeighbor(i, j);
                }
            }
        }
    }

    /**
     * Checks if two 3D grid coordinates are face-adjacent.
     * Face adjacency means differing by 1 in exactly one dimension.
     *
     * @param coords1 [x, y, z] coordinates
     * @param coords2 [x, y, z] coordinates
     * @return true if face-adjacent
     */
    private boolean isAdjacent3D(int[] coords1, int[] coords2) {
        int diffCount = 0;
        for (int dim = 0; dim < 3; dim++) {
            int diff = Math.abs(coords1[dim] - coords2[dim]);
            if (diff == 1) {
                diffCount++;
            } else if (diff != 0) {
                return false; // Differ by more than 1 in this dimension
            }
        }
        return diffCount == 1; // Must differ by exactly 1 in exactly one dimension
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
