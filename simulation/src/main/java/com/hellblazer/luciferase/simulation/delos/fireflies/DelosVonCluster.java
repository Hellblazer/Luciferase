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

package com.hellblazer.luciferase.simulation.delos.fireflies;

import com.hellblazer.luciferase.simulation.von.Event;
import javafx.geometry.Point3D;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * A cluster of integrated VON nodes backed by Delos Fireflies.
 * <p>
 * Provides:
 * <ul>
 *   <li>Creation of {@link DelosVonNode}s from a {@link DelosClusterFactory.DelosCluster}</li>
 *   <li>Position initialization (random, grid, or custom)</li>
 *   <li>Cluster-wide event aggregation</li>
 *   <li>Lifecycle management</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class DelosVonCluster {

    private static final Logger log = LoggerFactory.getLogger(DelosVonCluster.class);

    private final DelosClusterFactory.DelosCluster delosCluster;
    private final List<DelosVonNode>               vonNodes;
    private final Map<UUID, DelosVonNode>          nodesByUuid;
    private final List<Event>                      allEvents = new CopyOnWriteArrayList<>();

    /**
     * Create a VON cluster from a Delos cluster.
     *
     * @param delosCluster    the underlying Delos cluster
     * @param vonNodes        the VON nodes created for each Delos node
     */
    private DelosVonCluster(DelosClusterFactory.DelosCluster delosCluster,
                            List<DelosVonNode> vonNodes) {
        this.delosCluster = delosCluster;
        this.vonNodes = Collections.unmodifiableList(vonNodes);
        this.nodesByUuid = vonNodes.stream()
                                   .collect(Collectors.toMap(DelosVonNode::id, n -> n));
    }

    /**
     * Get all VON nodes in the cluster.
     */
    public List<DelosVonNode> getNodes() {
        return vonNodes;
    }

    /**
     * Get VON node by index.
     */
    public DelosVonNode getNode(int index) {
        return vonNodes.get(index);
    }

    /**
     * Get VON node by UUID.
     */
    public DelosVonNode getNode(UUID id) {
        return nodesByUuid.get(id);
    }

    /**
     * Get the underlying Delos cluster.
     */
    public DelosClusterFactory.DelosCluster getDelosCluster() {
        return delosCluster;
    }

    /**
     * Get all events emitted across all nodes.
     */
    public List<Event> getAllEvents() {
        return Collections.unmodifiableList(allEvents);
    }

    /**
     * Clear the event history.
     */
    public void clearEvents() {
        allEvents.clear();
    }

    /**
     * Get the number of nodes in the cluster.
     */
    public int size() {
        return vonNodes.size();
    }

    /**
     * Announce all nodes to the cluster (join protocol).
     */
    public void announceAll() {
        vonNodes.forEach(DelosVonNode::announceJoin);
    }

    /**
     * Request discovery from all nodes.
     */
    public void discoverAll() {
        vonNodes.forEach(DelosVonNode::requestDiscovery);
    }

    /**
     * Get total neighbor count across all nodes.
     */
    public int getTotalNeighborCount() {
        return vonNodes.stream()
                       .mapToInt(n -> n.neighbors().size())
                       .sum();
    }

    /**
     * Check if all nodes have discovered at least one neighbor.
     */
    public boolean allNodesHaveNeighbors() {
        return vonNodes.stream().allMatch(n -> !n.neighbors().isEmpty());
    }

    @Override
    public String toString() {
        return String.format("DelosVonCluster[nodes=%d, totalNeighbors=%d]",
                             vonNodes.size(), getTotalNeighborCount());
    }

    // ========== Builder ==========

    /**
     * Builder for creating DelosVonCluster instances.
     */
    public static class Builder {
        private final DelosClusterFactory.DelosCluster delosCluster;
        private PositionStrategy positionStrategy = PositionStrategy.RANDOM;
        private double spatialExtent = 100.0;
        private Consumer<Event> globalEventHandler = e -> {};

        public Builder(DelosClusterFactory.DelosCluster delosCluster) {
            this.delosCluster = delosCluster;
        }

        /**
         * Set the position initialization strategy.
         */
        public Builder positionStrategy(PositionStrategy strategy) {
            this.positionStrategy = strategy;
            return this;
        }

        /**
         * Set the spatial extent (used by position strategies).
         */
        public Builder spatialExtent(double extent) {
            this.spatialExtent = extent;
            return this;
        }

        /**
         * Set a global event handler for all events.
         */
        public Builder globalEventHandler(Consumer<Event> handler) {
            this.globalEventHandler = handler;
            return this;
        }

        /**
         * Build the VON cluster.
         */
        public DelosVonCluster build() {
            var clusterNodes = delosCluster.getNodes();
            var vonNodes = new ArrayList<DelosVonNode>();
            var allEvents = new CopyOnWriteArrayList<Event>();

            // Create event collector that captures all events
            Consumer<Event> eventCollector = event -> {
                allEvents.add(event);
                globalEventHandler.accept(event);
            };

            // Generate positions based on strategy
            var positions = generatePositions(clusterNodes.size());

            // Create VON nodes
            for (int i = 0; i < clusterNodes.size(); i++) {
                var clusterNode = clusterNodes.get(i);
                var position = positions.get(i);
                var vonNode = new DelosVonNode(clusterNode, position, eventCollector);
                vonNodes.add(vonNode);
            }

            var cluster = new DelosVonCluster(delosCluster, vonNodes);
            // Link the event list
            cluster.allEvents.addAll(allEvents);

            log.info("Built DelosVonCluster with {} nodes using {} strategy",
                     vonNodes.size(), positionStrategy);
            return cluster;
        }

        private List<Point3D> generatePositions(int count) {
            return switch (positionStrategy) {
                case RANDOM -> generateRandomPositions(count);
                case GRID -> generateGridPositions(count);
                case CLUSTERED -> generateClusteredPositions(count);
                case ORIGIN -> generateOriginPositions(count);
            };
        }

        private List<Point3D> generateRandomPositions(int count) {
            var random = new Random(42); // Reproducible
            var positions = new ArrayList<Point3D>();
            for (int i = 0; i < count; i++) {
                positions.add(new Point3D(
                    random.nextDouble() * spatialExtent,
                    random.nextDouble() * spatialExtent,
                    random.nextDouble() * spatialExtent
                ));
            }
            return positions;
        }

        private List<Point3D> generateGridPositions(int count) {
            var positions = new ArrayList<Point3D>();
            int gridSize = (int) Math.ceil(Math.cbrt(count));
            double spacing = spatialExtent / gridSize;
            int idx = 0;
            outer:
            for (int x = 0; x < gridSize; x++) {
                for (int y = 0; y < gridSize; y++) {
                    for (int z = 0; z < gridSize; z++) {
                        if (idx++ >= count) break outer;
                        positions.add(new Point3D(
                            x * spacing + spacing / 2,
                            y * spacing + spacing / 2,
                            z * spacing + spacing / 2
                        ));
                    }
                }
            }
            return positions;
        }

        private List<Point3D> generateClusteredPositions(int count) {
            var random = new Random(42);
            var positions = new ArrayList<Point3D>();
            var center = new Point3D(spatialExtent / 2, spatialExtent / 2, spatialExtent / 2);
            double clusterRadius = spatialExtent / 4;
            for (int i = 0; i < count; i++) {
                positions.add(new Point3D(
                    center.getX() + (random.nextDouble() - 0.5) * clusterRadius * 2,
                    center.getY() + (random.nextDouble() - 0.5) * clusterRadius * 2,
                    center.getZ() + (random.nextDouble() - 0.5) * clusterRadius * 2
                ));
            }
            return positions;
        }

        private List<Point3D> generateOriginPositions(int count) {
            var positions = new ArrayList<Point3D>();
            for (int i = 0; i < count; i++) {
                positions.add(Point3D.ZERO);
            }
            return positions;
        }
    }

    /**
     * Position initialization strategies.
     */
    public enum PositionStrategy {
        /** Random positions within spatial extent */
        RANDOM,
        /** Grid-aligned positions */
        GRID,
        /** Clustered around center */
        CLUSTERED,
        /** All at origin (for testing) */
        ORIGIN
    }
}
