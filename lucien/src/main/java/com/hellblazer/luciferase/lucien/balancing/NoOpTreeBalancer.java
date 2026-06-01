/*
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien.balancing;

import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.entity.EntityID;

import java.util.List;
import java.util.Set;

/**
 * Null-object {@link TreeBalancer} for spatial structures that have no tree to balance — e.g. the flat,
 * Morton-sorted {@code SFCArrayIndex}. Every operation is a benign no-op so that
 * {@code AbstractSpatialIndex.createTreeBalancer()} can return a non-null balancer instead of {@code null},
 * keeping the {@code treeBalancer != null} invariant and removing per-call-site null guards (Luciferase-7sv7).
 *
 * <p>{@link #rebalanceTree()} returns {@code successful=true} with no modifications: there is nothing to balance,
 * so the operation trivially succeeds. {@link #isAutoBalancingEnabled()} is always {@code false}.
 *
 * @param <Key> the spatial key type
 * @param <ID>  the entity identifier type
 * @author hal.hildebrand
 */
public final class NoOpTreeBalancer<Key extends SpatialKey<Key>, ID extends EntityID> implements TreeBalancer<Key, ID> {

    @Override
    public BalancingAction checkNodeBalance(Key nodeIndex) {
        return BalancingAction.NONE;
    }

    @Override
    public TreeBalancingStrategy.TreeBalancingStats getBalancingStats() {
        return new TreeBalancingStrategy.TreeBalancingStats(0, 0, 0, 0, 0, 0.0, 0.0);
    }

    @Override
    public boolean isAutoBalancingEnabled() {
        return false;
    }

    @Override
    public boolean mergeNodes(Set<Key> nodeIndices, Key parentIndex) {
        return false;
    }

    @Override
    public int rebalanceSubtree(Key rootNodeIndex) {
        return 0;
    }

    @Override
    public RebalancingResult rebalanceTree() {
        return new RebalancingResult(0, 0, 0, 0, 0, 0L, true);
    }

    @Override
    public void setAutoBalancingEnabled(boolean enabled) {
        // No-op: nothing to balance.
    }

    @Override
    public void setBalancingStrategy(TreeBalancingStrategy<ID> strategy) {
        // No-op: nothing to balance.
    }

    @Override
    public List<Key> splitNode(Key nodeIndex, byte nodeLevel) {
        return List.of();
    }
}
