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
package com.hellblazer.luciferase.esvo.dag;

/**
 * DAG compression strategies.
 *
 * <p>Defines the trade-off between build time and compression ratio during DAG construction:
 *
 * <ul>
 * <li><b>CONSERVATIVE</b>: Aggressive deduplication with thorough hash comparisons.
 *     <ul>
 *       <li>Highest compression ratio (maximum memory savings)</li>
 *       <li>Longest build time (most thorough hash checking)</li>
 *       <li>Best for offline processing where quality matters more than speed</li>
 *     </ul>
 * </li>
 * <li><b>BALANCED</b>: Moderate deduplication with reasonable hash comparisons (default).
 *     <ul>
 *       <li>Good compression ratio with acceptable build time</li>
 *       <li>Recommended for most use cases</li>
 *       <li>Balances performance and memory efficiency</li>
 *     </ul>
 * </li>
 * <li><b>AGGRESSIVE</b>: Minimal deduplication with fast hash lookups.
 *     <ul>
 *       <li>Lower compression ratio (less memory savings)</li>
 *       <li>Fastest build time (minimal hash checking)</li>
 *       <li>Best for real-time applications where speed is critical</li>
 *     </ul>
 * </li>
 * </ul>
 *
 * @author hal.hildebrand
 * @see DAGMetadata
 */
public enum CompressionStrategy {
    /**
     * Aggressive deduplication, high build time, maximum compression.
     * Best for offline processing.
     */
    CONSERVATIVE,

    /**
     * Moderate deduplication, balanced build time and compression.
     * Recommended default strategy.
     */
    BALANCED,

    /**
     * Minimal deduplication, fast build time, lower compression.
     * Best for real-time applications.
     */
    AGGRESSIVE
}
