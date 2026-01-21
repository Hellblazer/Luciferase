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
package com.hellblazer.luciferase.esvo.dag.config;

/**
 * Defines how original SVO data is retained after DAG compression.
 *
 * @author hal.hildebrand
 * @see CompressionConfiguration
 */
public enum RetentionPolicy {
    /** Free original SVO immediately after compression (default). */
    DISCARD,

    /** Keep both SVO and DAG in memory. */
    RETAIN,

    /** Store SVO in LRU cache for validation/debugging. */
    CACHE;

    /**
     * @return the default retention policy (RETAIN)
     */
    public static RetentionPolicy defaultPolicy() {
        return RETAIN;
    }
}
