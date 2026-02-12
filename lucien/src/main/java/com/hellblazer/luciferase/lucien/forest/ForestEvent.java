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

import java.util.List;

/**
 * Sealed interface for forest lifecycle events.
 *
 * Events are immutable records that capture structural changes in the forest,
 * enabling event-driven integration with external systems like Tumbler.
 *
 * The sealed hierarchy enables exhaustive pattern matching in Java 24:
 * <pre>
 * switch (event) {
 *     case TreeAdded e -> handleAdd(e);
 *     case TreeRemoved e -> handleRemove(e);
 *     case TreeSubdivided e -> handleSubdivision(e);
 *     case TreesMerged e -> handleMerge(e);
 *     case EntityMigrated e -> handleMigration(e);
 * }
 * </pre>
 *
 * @author hal.hildebrand
 */
public sealed interface ForestEvent permits
    ForestEvent.TreeAdded,
    ForestEvent.TreeRemoved,
    ForestEvent.TreeSubdivided,
    ForestEvent.TreesMerged,
    ForestEvent.EntityMigrated {

    /**
     * Event timestamp in milliseconds since epoch.
     * @return event timestamp
     */
    long timestamp();

    /**
     * Forest identifier for the forest that generated this event.
     * @return forest ID
     */
    String forestId();

    /**
     * Event emitted when a new tree is added to the forest.
     *
     * @param timestamp event timestamp
     * @param forestId forest identifier
     * @param treeId unique tree identifier
     * @param bounds spatial bounds (CubicBounds or TetrahedralBounds)
     * @param regionShape shape of the spatial region (CUBIC or TETRAHEDRAL)
     * @param parentId parent tree ID (null for root trees)
     */
    record TreeAdded(
        long timestamp,
        String forestId,
        String treeId,
        TreeBounds bounds,
        RegionShape regionShape,
        String parentId
    ) implements ForestEvent {}

    /**
     * Event emitted when a tree is removed from the forest.
     *
     * @param timestamp event timestamp
     * @param forestId forest identifier
     * @param treeId tree being removed
     */
    record TreeRemoved(
        long timestamp,
        String forestId,
        String treeId
    ) implements ForestEvent {}

    /**
     * Event emitted when a tree is subdivided into children.
     *
     * @param timestamp event timestamp
     * @param forestId forest identifier
     * @param parentId parent tree being subdivided
     * @param childIds list of child tree IDs created
     * @param strategy subdivision strategy used (OCTANT, TETRAHEDRAL, etc.)
     * @param childShape shape of child regions (CUBIC or TETRAHEDRAL)
     */
    record TreeSubdivided(
        long timestamp,
        String forestId,
        String parentId,
        List<String> childIds,
        AdaptiveForest.AdaptationConfig.SubdivisionStrategy strategy,
        RegionShape childShape
    ) implements ForestEvent {}

    /**
     * Event emitted when multiple trees are merged into one.
     *
     * @param timestamp event timestamp
     * @param forestId forest identifier
     * @param sourceIds trees being merged
     * @param mergedId resulting merged tree ID
     */
    record TreesMerged(
        long timestamp,
        String forestId,
        List<String> sourceIds,
        String mergedId
    ) implements ForestEvent {}

    /**
     * Event emitted when an entity migrates between trees.
     *
     * @param timestamp event timestamp
     * @param forestId forest identifier
     * @param entityId entity being migrated
     * @param fromTreeId source tree
     * @param toTreeId destination tree
     */
    record EntityMigrated(
        long timestamp,
        String forestId,
        Object entityId,
        String fromTreeId,
        String toTreeId
    ) implements ForestEvent {}
}
