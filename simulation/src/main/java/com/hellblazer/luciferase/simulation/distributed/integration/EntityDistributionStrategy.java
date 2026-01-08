/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.simulation.distributed.integration;

import java.util.UUID;

/**
 * Strategy interface for distributing entities across bubbles.
 * <p>
 * Implementations determine which bubble receives each entity during
 * initial distribution.
 * <p>
 * Phase 6B5.3: Entity Distribution & Initialization
 *
 * @author hal.hildebrand
 */
public interface EntityDistributionStrategy {

    /**
     * Selects the target bubble for an entity.
     *
     * @param entityId the entity UUID
     * @return the target bubble UUID
     */
    UUID selectBubble(UUID entityId);
}
