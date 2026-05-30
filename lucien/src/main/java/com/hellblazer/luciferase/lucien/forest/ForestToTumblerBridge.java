/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.lucien.forest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToLongFunction;

/**
 * Bridge between AdaptiveForest and Tumbler framework for server assignment.
 *
 * Translates forest lifecycle events (tree creation, subdivision, removal)
 * into server assignment operations. By default this uses a simple round-robin
 * mock assignment; a {@link #forShapeWeightedAssignment shape-weighted} bridge
 * instead assigns root trees to the least-loaded server by accumulated
 * {@code N_shape(level)} weight (RDR-010 §4c). Future integration with actual
 * Tumbler will replace the mock logic.
 *
 * Thread Safety: lookups (ConcurrentHashMap) are lock-free; the assignment
 * mutators ({@code handleTree*}) are {@code synchronized} so the read-min /
 * accumulate sequence of the weighted greedy path is atomic. The legacy
 * round-robin path also uses an {@link AtomicInteger} counter.
 *
 * Design Rationale: The forest remains agnostic to server assignment,
 * emitting events that this bridge translates. This loose coupling enables:
 * - Independent testing of forest spatial logic and server assignment
 * - Easy replacement of assignment strategy (mock -> real Tumbler)
 * - Extensibility (other listeners can subscribe to same events)
 *
 * @author hal.hildebrand
 */
public class ForestToTumblerBridge implements ForestEventListener {

    private static final Logger log = LoggerFactory.getLogger(ForestToTumblerBridge.class);

    /** Number of mock servers the bridge balances across (server-0 .. server-{N-1}). */
    private static final int SERVER_COUNT = 4;

    /**
     * Mock server assignment mapping (tree ID -> server ID).
     * In real Tumbler integration, this would be replaced with actual
     * Tumbler API calls.
     */
    private final Map<String, String> treeToServerAssignments = new ConcurrentHashMap<>();

    /**
     * Round-robin counter for mock server assignment.
     * Cycles through server-0, server-1, server-2, server-3.
     */
    private final AtomicInteger serverAssignmentCounter = new AtomicInteger(0);

    /**
     * Optional per-tree shape weight (tree ID → {@code N_shape(level)} weight). When non-null, root trees
     * are assigned to the least-loaded server by accumulated weight (RDR-010 §4c, bead Luciferase-d3z3),
     * so a pyramid-heavy forest balances server <em>load</em> rather than tree <em>count</em>. When null,
     * assignment is the legacy exact round-robin.
     */
    private final ToLongFunction<String> treeWeigher;

    /** Accumulated shape weight per server (only used when {@link #treeWeigher} is non-null). */
    private final Map<String, Long> serverLoad;

    /** Weight credited per tree at assignment time, so removals decrement the right amount (weighted only). */
    private final Map<String, Long> treeAssignedWeight;

    /** Create a bridge with legacy round-robin server assignment (no shape weighting). */
    public ForestToTumblerBridge() {
        this(null);
    }

    /**
     * Create a bridge that assigns root trees to the least-loaded server by accumulated shape weight.
     *
     * @param treeWeigher tree ID → shape weight (e.g. {@code N_shape(level)}); {@code null} ⇒ legacy
     *                    round-robin
     */
    public ForestToTumblerBridge(ToLongFunction<String> treeWeigher) {
        this.treeWeigher = treeWeigher;
        this.serverLoad = treeWeigher == null ? null : new ConcurrentHashMap<>();
        this.treeAssignedWeight = treeWeigher == null ? null : new ConcurrentHashMap<>();
    }

    /**
     * Build a shape-weighted bridge whose weigher resolves each tree's {@code N_shape(level)} from its
     * spatial index ({@link com.hellblazer.luciferase.lucien.balancing.ShapeWeightProvider#elementCount}).
     * This is the live consumer of the per-shape partition weight (RDR-010 §4c): a pyramid tree
     * ({@code N_pyramid = 2·8^ℓ − 6^ℓ}) outweighs a hex/tet tree ({@code 8^ℓ}). An absent tree weighs 0.
     *
     * <p>The weigher is evaluated at assignment time. {@code elementCount} is the structural refinement
     * count {@code N_shape(level)} — independent of entity population — so a just-created (empty) tree
     * already carries its full shape weight (unlike a live-entity-count weigher, which would read 0).
     *
     * @param forest the forest whose trees are being assigned
     * @param level  the uniform refinement level at which to evaluate each shape's element count
     */
    public static <Key extends com.hellblazer.luciferase.lucien.SpatialKey<Key>,
                   ID extends com.hellblazer.luciferase.lucien.entity.EntityID, Content>
    ForestToTumblerBridge forShapeWeightedAssignment(Forest<Key, ID, Content> forest, int level) {
        Objects.requireNonNull(forest, "forest");
        return new ForestToTumblerBridge(treeId -> {
            var tree = forest.getTree(treeId);
            return tree == null ? 0L : tree.getSpatialIndex().elementCount(level);
        });
    }

    /** The shape weight this bridge attributes to {@code treeId} (1 when no weigher is configured). */
    public long treeWeight(String treeId) {
        return treeWeigher == null ? 1L : treeWeigher.applyAsLong(treeId);
    }

    /**
     * Choose a server for a root/standalone tree: legacy round-robin when unweighted, else the
     * least-loaded server by accumulated shape weight (ties broken by lowest server index — which makes
     * the equal-weight case reproduce round-robin).
     */
    private String chooseRootServer() {
        if (treeWeigher == null) {
            return "server-" + (serverAssignmentCounter.getAndIncrement() % SERVER_COUNT);
        }
        String best = "server-0";
        long bestLoad = Long.MAX_VALUE;
        for (int i = 0; i < SERVER_COUNT; i++) {
            var server = "server-" + i;
            long load = serverLoad.getOrDefault(server, 0L);
            if (load < bestLoad) {
                bestLoad = load;
                best = server;
            }
        }
        return best;
    }

    /** Record a tree→server assignment, accumulating its shape weight onto the server (when weighted). */
    private void recordAssignment(String treeId, String serverId) {
        var prior = treeToServerAssignments.put(treeId, serverId);
        if (serverLoad != null) {
            // If this tree was already assigned elsewhere (e.g. child re-homed on subdivision), move its
            // previously-credited weight off the old server first.
            if (prior != null) {
                Long old = treeAssignedWeight.remove(treeId);
                if (old != null) {
                    serverLoad.merge(prior, -old, Long::sum);
                }
            }
            long weight = treeWeigher.applyAsLong(treeId);
            treeAssignedWeight.put(treeId, weight);
            serverLoad.merge(serverId, weight, Long::sum);
        }
    }

    /** Remove a tree's assignment, returning its credited shape weight to its server (when weighted). */
    private void removeAssignment(String treeId) {
        var server = treeToServerAssignments.remove(treeId);
        if (serverLoad != null && server != null) {
            Long weight = treeAssignedWeight.remove(treeId);
            if (weight != null) {
                serverLoad.merge(server, -weight, Long::sum);
            }
        }
    }

    @Override
    public void onEvent(ForestEvent event) {
        switch (event) {
            case ForestEvent.TreeAdded added -> handleTreeAdded(added);
            case ForestEvent.TreeSubdivided subdivided -> handleTreeSubdivided(subdivided);
            case ForestEvent.TreeRemoved removed -> handleTreeRemoved(removed);
            case ForestEvent.TreesMerged merged -> handleTreesMerged(merged);
            case ForestEvent.EntityMigrated migrated -> handleEntityMigrated(migrated);
        }
    }

    /**
     * Handle tree creation by assigning it to a server.
     *
     * If the tree has a parent, inherit the parent's server assignment.
     * Otherwise (root tree), use round-robin assignment across 4 servers.
     *
     * @param event tree creation event
     */
    private synchronized void handleTreeAdded(ForestEvent.TreeAdded event) {
        String serverId;

        if (event.parentId() != null) {
            // Child tree: inherit parent's server
            serverId = treeToServerAssignments.get(event.parentId());
            if (serverId == null) {
                log.warn("Parent tree {} has no server assignment, assigning child {} directly",
                        event.parentId(), event.treeId());
                serverId = chooseRootServer();
            }
        } else {
            // Root tree: least-loaded (shape-weighted) or round-robin assignment
            serverId = chooseRootServer();
        }

        recordAssignment(event.treeId(), serverId);

        log.debug("Assigned tree {} ({}) to {} (parent: {})",
                event.treeId(), event.regionShape(), serverId, event.parentId());
    }

    /**
     * Handle tree subdivision by assigning children to servers.
     *
     * Current strategy: Children inherit parent's server initially.
     * If parent has no assignment (e.g., root tree created via addTree()),
     * assign parent first, then children inherit.
     *
     * @param event subdivision event
     */
    private synchronized void handleTreeSubdivided(ForestEvent.TreeSubdivided event) {
        var parentServer = treeToServerAssignments.get(event.parentId());

        if (parentServer == null) {
            // Parent has no assignment yet (e.g., root tree created via addTree())
            parentServer = chooseRootServer();
            recordAssignment(event.parentId(), parentServer);
            log.debug("Assigned parent tree {} to {} (no prior assignment)", event.parentId(), parentServer);
        }

        // Children inherit parent's server (overriding any prior TreeAdded assignments)
        for (var childId : event.childIds()) {
            recordAssignment(childId, parentServer);
        }

        log.debug("Assigned {} {} children to parent's server {}",
            event.childIds().size(), event.childShape(), parentServer);
    }

    /**
     * Handle tree removal by clearing server assignment.
     *
     * @param event tree removal event
     */
    private synchronized void handleTreeRemoved(ForestEvent.TreeRemoved event) {
        removeAssignment(event.treeId());
        log.debug("Removed server assignment for tree {}", event.treeId());
    }

    /**
     * Handle tree merge by reassigning merged tree.
     *
     * Mock implementation: Assign merged tree to new server.
     * Real Tumbler would consider load balancing and locality.
     *
     * @param event tree merge event
     */
    private synchronized void handleTreesMerged(ForestEvent.TreesMerged event) {
        // Remove source tree assignments
        for (var sourceId : event.sourceIds()) {
            removeAssignment(sourceId);
        }

        // Assign merged tree
        var serverId = chooseRootServer();
        recordAssignment(event.mergedId(), serverId);

        log.debug("Merged {} trees into {} on {}", event.sourceIds().size(), event.mergedId(), serverId);
    }

    /**
     * Handle entity migration between trees.
     *
     * In Phase 3, this is informational only (no action needed).
     * Real Tumbler would update load metrics when entities cross server boundaries.
     *
     * @param event entity migration event
     */
    private void handleEntityMigrated(ForestEvent.EntityMigrated event) {
        var fromServer = treeToServerAssignments.get(event.fromTreeId());
        var toServer = treeToServerAssignments.get(event.toTreeId());

        if (!Objects.equals(fromServer, toServer)) {
            log.debug("Entity {} migrated from {} to {}",
                event.entityId(), fromServer, toServer);
        }
    }

    /**
     * Get the server assignment for a specific tree.
     *
     * @param treeId tree identifier
     * @return server ID, or null if tree has no assignment
     */
    public String getServerAssignment(String treeId) {
        return treeToServerAssignments.get(treeId);
    }

    /**
     * Get all server assignments as an unmodifiable map.
     *
     * @return unmodifiable map of tree ID to server ID
     */
    public Map<String, String> getAllAssignments() {
        return Collections.unmodifiableMap(treeToServerAssignments);
    }
}
