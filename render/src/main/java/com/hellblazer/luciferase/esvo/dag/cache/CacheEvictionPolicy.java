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
package com.hellblazer.luciferase.esvo.dag.cache;

/**
 * Cache eviction policies for DAGCache.
 *
 * @author hal.hildebrand
 */
public enum CacheEvictionPolicy {
    /**
     * Least Recently Used - evict the entry that was accessed longest ago.
     */
    LRU,

    /**
     * Least Frequently Used - evict the entry with the fewest accesses.
     * (Future implementation - Phase 4 focuses on LRU)
     */
    LFU,

    /**
     * First In First Out - evict the oldest entry.
     * (Future implementation - Phase 4 focuses on LRU)
     */
    FIFO
}
