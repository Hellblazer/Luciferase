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
package com.hellblazer.luciferase.lucien.balancing;

import com.hellblazer.luciferase.lucien.SpatialKey;

import java.util.List;

/**
 * Pure-domain batch of 2:1 balance violations exchanged during butterfly-pattern rounds
 * (RDR-007 Phase 0 Inc3).
 *
 * <p>Replaces the protobuf {@code ViolationBatch} message at the lucien-core boundary.
 * Reuses the existing domain violation record {@link TwoOneBalanceChecker.BalanceViolation} rather than
 * minting a parallel type.
 *
 * @param <Key> the spatial key type (MortonKey, TetreeKey, etc.)
 * @author hal.hildebrand
 */
public record ViolationBatch<Key extends SpatialKey<Key>>(
    int requesterRank,
    int responderRank,
    int roundNumber,
    List<TwoOneBalanceChecker.BalanceViolation<Key>> violations,
    long timestamp
) {
    public ViolationBatch {
        violations = List.copyOf(violations);
    }
}
