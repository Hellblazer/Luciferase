/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.lucien.forest.ghost;

/**
 * Enumeration of ghost creation algorithms.
 * 
 * Different algorithms optimize for different use cases:
 * - Memory efficiency vs query performance
 * - Network traffic vs local computation
 * - Creation time vs maintenance overhead
 * 
 * @author Hal Hildebrand
 */
public enum GhostAlgorithm {
    
    /**
     * Minimal ghost creation - only creates ghosts for direct (face) neighbors.
     *
     * Characteristics:
     * - Lowest memory usage
     * - Minimal network traffic
     * - Fast creation
     * - Sufficient for 2:1-balanced meshes, where a face neighbor is at most one level away
     * - <b>Default choice</b> (Luciferase-9m31)
     */
    MINIMAL,

    /**
     * Deep-coverage ghost creation - creates ghosts for neighbors and their immediate neighbors (depth-2 BFS).
     * Renamed from {@code CONSERVATIVE} (Luciferase-9m31): the old name understated that this is the
     * over-aggressive depth-2 option, not a conservative one. Prefer {@link #MINIMAL} on 2:1-balanced meshes;
     * use this only when the mesh is not balanced and second-level coverage is genuinely required.
     *
     * Characteristics:
     * - Moderate memory usage
     * - Heavier network traffic (second-level fan-out)
     * - Broader query coverage on unbalanced meshes
     */
    DEEP_COVERAGE,
    
    /**
     * Aggressive ghost creation - creates extensive ghost layers for maximum query performance.
     * 
     * Characteristics:
     * - Higher memory usage
     * - More network traffic during creation
     * - Excellent query performance
     * - Best for read-heavy workloads
     */
    AGGRESSIVE,
    
    /**
     * Adaptive ghost creation - dynamically adjusts ghost creation based on usage patterns.
     * 
     * Characteristics:
     * - Variable memory usage based on access patterns
     * - Intelligent network usage
     * - Learns from query patterns
     * - Best for mixed or unknown workloads
     */
    ADAPTIVE,
    
    /**
     * Custom ghost creation - uses a user-provided algorithm.
     * 
     * Characteristics:
     * - Application-specific optimization
     * - Requires custom implementation
     * - Full control over ghost creation strategy
     */
    CUSTOM
}
