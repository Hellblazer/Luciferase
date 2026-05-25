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

import java.util.Map;

/**
 * Rank-to-endpoint discovery for distributed ghost communication.
 *
 * <p>Promoted to a top-level lucien-core interface (formerly nested in the gRPC-package
 * {@code GhostServiceClient}) so core consumers can reference it without depending on the
 * gRPC transport package.
 *
 * @author Hal Hildebrand
 */
public interface ServiceDiscovery {

    /**
     * @param rank the process rank
     * @return the gRPC endpoint registered for the given rank, or {@code null} if unknown
     */
    String getEndpoint(int rank);

    /**
     * Register the endpoint for a process rank.
     *
     * @param rank     the process rank
     * @param endpoint the endpoint to associate with the rank
     */
    void registerEndpoint(int rank, String endpoint);

    /**
     * @return an immutable view of all known rank-to-endpoint mappings
     */
    Map<Integer, String> getAllEndpoints();
}
