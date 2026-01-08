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

package com.hellblazer.luciferase.simulation.distributed.migration;

import javafx.geometry.Point3D;

import java.util.UUID;

/**
 * Immutable snapshot of entity state at migration time.
 * <p>
 * Captures all data needed to restore entity if migration fails (abort/rollback).
 * Used in the ABORT phase to return entity to source bubble.
 * <p>
 * Example usage:
 * <pre>
 * // During PREPARE: snapshot entity before removal
 * var snapshot = new EntitySnapshot(
 *     entity.getId(),
 *     entity.getPosition(),
 *     entity.getContent(),
 *     sourceBubbleId,
 *     entity.getEpoch(),
 *     entity.getVersion(),
 *     System.currentTimeMillis()
 * );
 *
 * // During ABORT: restore from snapshot
 * sourceBubble.addEntity(snapshot.toEntity());
 * </pre>
 *
 * @param entityId          Entity identifier
 * @param position          Entity position at snapshot time
 * @param content           Entity content (type-erased)
 * @param authorityBubbleId Authority bubble (for epoch tracking)
 * @param epoch             Entity epoch at snapshot time
 * @param version           Entity version within epoch
 * @param timestamp         Snapshot creation timestamp
 * @author hal.hildebrand
 */
public record EntitySnapshot(
    String entityId,
    Point3D position,
    Object content,
    UUID authorityBubbleId,
    long epoch,
    long version,
    long timestamp
) {
}
