/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.simulation.topology.events;

import java.util.List;
import java.util.UUID;

/**
 * Event fired when a complete set of 8 sibling Bey child leaves collapses back into their parent
 * leaf (the coverage-preserving inverse-Bey merge — RDR-018 AC-3 prerequisite).
 * <p>
 * The 8 level-{@code (L+1)} children are removed and replaced by their single level-{@code L} parent,
 * which absorbs all of their entities. Net effect: {@code -7} bubbles (the inverse of a Bey split's
 * {@code +7}).
 *
 * @param eventId        unique event identifier
 * @param timestamp      event timestamp (ms; simulation time)
 * @param parentBubbleId the parent bubble re-registered by the collapse (absorbs all child entities)
 * @param childBubbleIds the 8 child bubbles removed by the collapse
 * @param entitiesMoved  total number of entities moved from the children into the parent
 * @param success        whether the collapse succeeded
 *
 * @author hal.hildebrand
 */
public record CollapseEvent(
    UUID eventId,
    long timestamp,
    UUID parentBubbleId,
    List<UUID> childBubbleIds,
    int entitiesMoved,
    boolean success
) implements TopologyEvent {
    @Override
    public String eventType() {
        return "collapse";
    }
}
