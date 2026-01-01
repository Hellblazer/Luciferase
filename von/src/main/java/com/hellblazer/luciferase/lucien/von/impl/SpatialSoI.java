/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Thoth Interest Management and Load Balancing Framework.
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
package com.hellblazer.luciferase.lucien.von.impl;

import com.hellblazer.luciferase.lucien.SpatialIndex;
import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.von.Node;
import com.hellblazer.luciferase.lucien.von.SphereOfInteraction;
import com.hellblazer.sentry.Vertex;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SpatialIndex-backed implementation of SphereOfInteraction.
 * <p>
 * Uses k-NN queries instead of Delaunay topology for neighbor lookups.
 * This provides O(log n) lookups instead of requiring Voronoi maintenance.
 *
 * @param <Key> The spatial key type
 * @param <ID>  The entity ID type
 * @author hal.hildebrand
 */
public class SpatialSoI<Key extends SpatialKey<Key>, ID extends EntityID> implements SphereOfInteraction {

    private static final int   DEFAULT_K_NEIGHBORS = 10;
    private static final float DEFAULT_AOI_RADIUS  = 100f;
    private static final byte  LEVEL               = 12;

    private final SpatialIndex<Key, ID, Node> index;
    private final Map<Node, ID>               nodeToId;
    private final int                         kNeighbors;
    private final float                       aoiRadius;

    public SpatialSoI(SpatialIndex<Key, ID, Node> index) {
        this(index, DEFAULT_K_NEIGHBORS, DEFAULT_AOI_RADIUS);
    }

    public SpatialSoI(SpatialIndex<Key, ID, Node> index, int kNeighbors, float aoiRadius) {
        this.index = index;
        this.nodeToId = new ConcurrentHashMap<>();
        this.kNeighbors = kNeighbors;
        this.aoiRadius = aoiRadius;
    }

    @Override
    public Node closestTo(Point3f coord) {
        var neighbors = index.kNearestNeighbors(coord, 1, Float.MAX_VALUE);
        if (neighbors.isEmpty()) {
            return null;
        }
        return index.getEntity(neighbors.getFirst());
    }

    @Override
    public List<Node> getEnclosingNeighbors(Node node) {
        var id = nodeToId.get(node);
        if (id == null) {
            return List.of();
        }
        var position = index.getEntityPosition(id);
        if (position == null) {
            return List.of();
        }
        // Use k-NN to approximate "enclosing neighbors"
        // In Voronoi terms, enclosing neighbors are natural neighbors
        // k-NN provides a reasonable approximation for most use cases
        var neighborIds = index.kNearestNeighbors(position, kNeighbors + 1, aoiRadius);
        var result = new ArrayList<Node>(neighborIds.size());
        for (var neighborId : neighborIds) {
            if (!neighborId.equals(id)) {
                var neighbor = index.getEntity(neighborId);
                if (neighbor != null) {
                    result.add(neighbor);
                }
            }
        }
        return result;
    }

    @Override
    public Iterable<Node> getPeers() {
        return nodeToId.keySet();
    }

    @Override
    public boolean includes(Node peer) {
        return nodeToId.containsKey(peer);
    }

    @Override
    public void insert(Node node, Point3f coord) {
        var entityId = index.insert(coord, LEVEL, node);
        nodeToId.put(node, entityId);
    }

    @Override
    public boolean isBoundary(Node peer, Vertex center, float radiusSquared) {
        var peerId = nodeToId.get(peer);
        if (peerId == null) {
            return false;
        }
        var peerPos = index.getEntityPosition(peerId);
        if (peerPos == null) {
            return false;
        }
        var centerPos = center.getLocation();
        var distSquared = peerPos.distanceSquared(centerPos);
        // A node is a boundary neighbor if it's within the AOI but near the edge
        return distSquared <= radiusSquared && distSquared > radiusSquared * 0.8f;
    }

    @Override
    public boolean isEnclosing(Node peer, Node centerNode) {
        return getEnclosingNeighbors(centerNode).contains(peer);
    }

    @Override
    public boolean overlaps(Node peer, Point3f center, float radiusSquared) {
        var peerId = nodeToId.get(peer);
        if (peerId == null) {
            return false;
        }
        var peerPos = index.getEntityPosition(peerId);
        if (peerPos == null) {
            return false;
        }
        return peerPos.distanceSquared(center) <= radiusSquared;
    }

    @Override
    public boolean remove(Node peer) {
        var id = nodeToId.remove(peer);
        if (id != null) {
            return index.removeEntity(id);
        }
        return false;
    }

    @Override
    public void update(Node peer, Point3f coord) {
        var id = nodeToId.get(peer);
        if (id != null) {
            index.updateEntity(id, coord, LEVEL);
        }
    }
}
