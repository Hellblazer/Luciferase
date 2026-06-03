/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntityID;

import java.util.Set;

/**
 * No-op subdivision strategy for flat (non-hierarchical) spatial indices such as {@code SFCArrayIndex}
 * (Luciferase-5oruk). Returns a NoOp rather than {@code null} from {@code createDefaultSubdivisionStrategy} so that
 * external callers of {@code getSubdivisionStrategy().…} never NPE — mirroring the NoOp fix applied to the tree
 * balancer ({@code NoOpTreeBalancer}, Luciferase-7sv7). A flat array index never subdivides: entities always stay in
 * the parent.
 *
 * @param <Key>     the spatial key type
 * @param <ID>      the entity identifier type
 * @param <Content> the entity content type
 * @author hal.hildebrand
 */
public final class NoOpSubdivisionStrategy<Key extends SpatialKey<Key>, ID extends EntityID, Content>
extends SubdivisionStrategy<Key, ID, Content> {

    @Override
    public Set<Key> calculateTargetNodes(Key parentIndex, byte parentLevel, EntityBounds entityBounds,
                                         AbstractSpatialIndex<Key, ID, Content> spatialIndex) {
        return Set.of(); // flat index: no child nodes
    }

    @Override
    public SubdivisionResult determineStrategy(SubdivisionContext<Key, ID> context) {
        return SubdivisionResult.insertInParent("flat SFC array index does not subdivide");
    }

    @Override
    protected double estimateEntitySizeFactor(SubdivisionContext<Key, ID> context) {
        return 0.0; // flat index does not weight entities for subdivision
    }
}
