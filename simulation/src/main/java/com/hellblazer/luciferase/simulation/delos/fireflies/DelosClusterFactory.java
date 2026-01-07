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

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.fireflies.View;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Factory for creating integrated Delos cluster nodes.
 * <p>
 * Creates {@link DelosClusterNode} instances from Fireflies Views and Members,
 * and wires them together with {@link DelosGossipAdapter.ClusterGossip} for
 * cluster-wide messaging.
 *
 * @author hal.hildebrand
 */
public class DelosClusterFactory {

    private static final Logger log = LoggerFactory.getLogger(DelosClusterFactory.class);

    /**
     * A connected cluster of DelosClusterNodes.
     */
    public static class DelosCluster {
        private final List<DelosClusterNode>             nodes;
        private final Map<Digest, DelosClusterNode>      nodesByDigest;
        private final Map<UUID, DelosClusterNode>        nodesByUuid;
        private final DelosGossipAdapter.ClusterGossip   clusterGossip;

        DelosCluster(List<DelosClusterNode> nodes,
                     DelosGossipAdapter.ClusterGossip clusterGossip) {
            this.nodes = Collections.unmodifiableList(nodes);
            this.clusterGossip = clusterGossip;

            // Build lookup maps
            this.nodesByDigest = nodes.stream()
                                      .collect(Collectors.toMap(
                                          DelosClusterNode::getNodeId,
                                          n -> n
                                      ));
            this.nodesByUuid = nodes.stream()
                                    .collect(Collectors.toMap(
                                        DelosClusterNode::getNodeUuid,
                                        n -> n
                                    ));
        }

        /**
         * Get all nodes in the cluster.
         */
        public List<DelosClusterNode> getNodes() {
            return nodes;
        }

        /**
         * Get node by index.
         */
        public DelosClusterNode getNode(int index) {
            return nodes.get(index);
        }

        /**
         * Get node by Digest ID.
         */
        public DelosClusterNode getNode(Digest id) {
            return nodesByDigest.get(id);
        }

        /**
         * Get node by UUID.
         */
        public DelosClusterNode getNode(UUID id) {
            return nodesByUuid.get(id);
        }

        /**
         * Get the cluster gossip coordinator.
         */
        public DelosGossipAdapter.ClusterGossip getClusterGossip() {
            return clusterGossip;
        }

        /**
         * Get the number of nodes in the cluster.
         */
        public int size() {
            return nodes.size();
        }

        /**
         * Check if all nodes have full membership.
         */
        public boolean isFullyConnected() {
            int expectedCount = nodes.size();
            return nodes.stream().allMatch(n -> n.getActiveCount() == expectedCount);
        }

        /**
         * Get nodes that don't have full membership.
         */
        public List<String> getIncompleteNodes() {
            int expectedCount = nodes.size();
            return nodes.stream()
                        .filter(n -> n.getActiveCount() != expectedCount)
                        .map(n -> String.format("%s: %d/%d",
                                                n.getNodeId(),
                                                n.getActiveCount(),
                                                expectedCount))
                        .toList();
        }
    }

    /**
     * Create a DelosCluster from Views and Members.
     * <p>
     * Creates DelosClusterNode instances for each View/Member pair and
     * wires them together with ClusterGossip for cluster-wide messaging.
     *
     * @param views   list of Fireflies Views
     * @param members map of Digest to ControlledIdentifierMember
     * @return connected DelosCluster
     */
    public static DelosCluster create(List<View> views,
                                       Map<Digest, ControlledIdentifierMember> members) {
        // Create nodes
        var nodes = new ArrayList<DelosClusterNode>();
        var adapters = new ArrayList<DelosGossipAdapter>();

        for (var view : views) {
            var nodeId = view.getNode().getId();
            var member = members.get(nodeId);
            if (member == null) {
                throw new IllegalArgumentException("No member found for node: " + nodeId);
            }

            var node = new DelosClusterNode(view, member);
            nodes.add(node);
            adapters.add(node.getDelosGossipAdapter());
        }

        // Wire gossip adapters together
        var clusterGossip = DelosGossipAdapter.ClusterGossip.create(adapters);

        log.info("Created DelosCluster with {} nodes", nodes.size());
        return new DelosCluster(nodes, clusterGossip);
    }

    /**
     * Create a DelosCluster from aligned lists of Views and Members.
     * <p>
     * Views and members must be in the same order.
     *
     * @param views   list of Fireflies Views
     * @param members list of ControlledIdentifierMembers (same order as views)
     * @return connected DelosCluster
     */
    public static DelosCluster create(List<View> views,
                                       List<ControlledIdentifierMember> members) {
        if (views.size() != members.size()) {
            throw new IllegalArgumentException(
                "Views and members must be same size: " + views.size() + " vs " + members.size());
        }

        // Create nodes
        var nodes = new ArrayList<DelosClusterNode>();
        var adapters = new ArrayList<DelosGossipAdapter>();

        for (int i = 0; i < views.size(); i++) {
            var view = views.get(i);
            var member = members.get(i);

            var node = new DelosClusterNode(view, member);
            nodes.add(node);
            adapters.add(node.getDelosGossipAdapter());
        }

        // Wire gossip adapters together
        var clusterGossip = DelosGossipAdapter.ClusterGossip.create(adapters);

        log.info("Created DelosCluster with {} nodes", nodes.size());
        return new DelosCluster(nodes, clusterGossip);
    }
}
