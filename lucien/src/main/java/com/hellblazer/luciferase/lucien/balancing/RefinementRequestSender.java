/*
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien.balancing;

import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.entity.EntityID;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Seam for dispatching a batch of {@link RefinementRequest}s to remote partitions in parallel.
 *
 * <p>This is the test seam for {@link CrossPartitionBalancePhase#identifyRefinementNeeds}, whose
 * 4-arg form has no production caller — the production round path is
 * {@code CrossPartitionBalancePhase.execute() -> executeRefinementRound(int)}, which sends
 * per-target-rank requests directly via the exchange (Luciferase-uhsn). Expressing the dispatch as
 * this interface (rather than a reflective {@code getMethod("sendRequestsParallel", ...)} lookup,
 * Luciferase-ln6wu) makes a missing or mis-typed seam a <em>compile</em> error at the call site
 * instead of a {@code NoSuchMethodException} surfaced at runtime.</p>
 *
 * @param <Key>     the spatial key type
 * @param <ID>      the entity identifier type
 * @param <Content> the entity content type
 * @author hal.hildebrand
 */
@FunctionalInterface
public interface RefinementRequestSender<Key extends SpatialKey<Key>, ID extends EntityID, Content> {

    /**
     * Send the given refinement requests in parallel, returning one future per request.
     *
     * @param requests the refinement requests to dispatch
     * @return a future per request, completing with the remote partition's {@link RefinementResponse}
     */
    List<CompletableFuture<RefinementResponse<Key, ID, Content>>> sendRequestsParallel(
        List<RefinementRequest<Key>> requests);
}
