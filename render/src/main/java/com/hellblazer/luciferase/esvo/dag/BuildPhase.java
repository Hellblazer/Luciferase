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
 * DAG build phases for progress tracking.
 *
 * <p>Defines the sequential phases of DAG construction:
 *
 * <ol>
 * <li><b>HASHING</b>: Compute subtree hashes bottom-up from leaf nodes to root</li>
 * <li><b>DEDUPLICATION</b>: Identify duplicate subtrees using hash comparison</li>
 * <li><b>COMPACTION</b>: Build compacted node pool with shared subtrees</li>
 * <li><b>VALIDATION</b>: Validate the result for correctness</li>
 * <li><b>COMPLETE</b>: Build complete, DAG ready for use</li>
 * </ol>
 *
 * <p>These phases are reported during asynchronous DAG construction to provide
 * progress feedback to the caller.
 *
 * @author hal.hildebrand
 * @see BuildProgress
 */
public enum BuildPhase {
    /**
     * Phase 1: Computing subtree hashes bottom-up.
     */
    HASHING,

    /**
     * Phase 2: Identifying duplicate subtrees.
     */
    DEDUPLICATION,

    /**
     * Phase 3: Building compacted node pool.
     */
    COMPACTION,

    /**
     * Phase 4: Validating the result.
     */
    VALIDATION,

    /**
     * Phase 5: Build complete.
     */
    COMPLETE
}
