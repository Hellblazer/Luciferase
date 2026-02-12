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

/**
 * Bridge between AdaptiveForest and Tumbler framework for server assignment.
 *
 * Translates forest lifecycle events (tree creation, subdivision, removal)
 * into server assignment operations. In Phase 3, this uses a simple
 * round-robin mock assignment. Future integration with actual Tumbler
 * will replace the mock logic.
 *
 * Thread Safety: All operations are thread-safe using ConcurrentHashMap
 * and atomic counters.
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
    private void handleTreeAdded(ForestEvent.TreeAdded event) {
        String serverId;

        if (event.parentId() != null) {
            // Child tree: inherit parent's server
            serverId = treeToServerAssignments.get(event.parentId());
            if (serverId == null) {
                log.warn("Parent tree {} has no server assignment, using round-robin for child {}",
                        event.parentId(), event.treeId());
                serverId = "server-" + (serverAssignmentCounter.getAndIncrement() % 4);
            }
        } else {
            // Root tree: round-robin assignment
            serverId = "server-" + (serverAssignmentCounter.getAndIncrement() % 4);
        }

        treeToServerAssignments.put(event.treeId(), serverId);

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
    private void handleTreeSubdivided(ForestEvent.TreeSubdivided event) {
        var parentServer = treeToServerAssignments.get(event.parentId());

        if (parentServer == null) {
            // Parent has no assignment yet (e.g., root tree created via addTree())
            // Assign parent first using round-robin
            parentServer = "server-" + (serverAssignmentCounter.getAndIncrement() % 4);
            treeToServerAssignments.put(event.parentId(), parentServer);
            log.debug("Assigned parent tree {} to {} (no prior assignment)", event.parentId(), parentServer);
        }

        // Children inherit parent's server (overriding any prior TreeAdded assignments)
        for (var childId : event.childIds()) {
            treeToServerAssignments.put(childId, parentServer);
        }

        log.debug("Assigned {} {} children to parent's server {}",
            event.childIds().size(), event.childShape(), parentServer);
    }

    /**
     * Handle tree removal by clearing server assignment.
     *
     * @param event tree removal event
     */
    private void handleTreeRemoved(ForestEvent.TreeRemoved event) {
        treeToServerAssignments.remove(event.treeId());
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
    private void handleTreesMerged(ForestEvent.TreesMerged event) {
        // Remove source tree assignments
        for (var sourceId : event.sourceIds()) {
            treeToServerAssignments.remove(sourceId);
        }

        // Assign merged tree
        var serverId = "server-" + (serverAssignmentCounter.getAndIncrement() % 4);
        treeToServerAssignments.put(event.mergedId(), serverId);

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
