/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 * 
 * This file is part of the Luciferase.
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
package com.hellblazer.luciferase.lucien.entity;

/**
 * Interface for generating entity IDs. Implementations can provide
 * sequential, UUID, or custom ID generation strategies.
 * 
 * @param <T> The type of EntityID this generator produces
 * @author hal.hildebrand
 */
public interface EntityIDGenerator<T extends EntityID> {
    /**
     * Generate a new unique entity ID
     */
    T generateID();
    
    /**
     * Reset the generator state (if applicable)
     */
    default void reset() {
        // No-op by default
    }
}