/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.lucien.balancing;

import com.hellblazer.luciferase.lucien.SpatialKey;

/**
 * Lucien-core port for exchanging 2:1 balance violations during butterfly-pattern rounds
 * (RDR-007 Phase 0 Inc3).
 *
 * <p>Inverts the dependency from the concrete grpc {@code BalanceCoordinatorClient}: the violation
 * aggregators depend on this domain interface, and the grpc adapter implements it. Transport failures
 * surface as {@link BalanceExchangeException} so retry/timeout policy stays in core. Generic on
 * {@code Key} only — the violation path needs no entity/content typing.
 *
 * @param <Key> the spatial key type (MortonKey, TetreeKey, etc.)
 * @author hal.hildebrand
 */
public interface ViolationExchange<Key extends SpatialKey<Key>> {

    /**
     * Exchange this partition's violation batch with a remote partner and receive the partner's batch
     * for bidirectional merge.
     *
     * @param batch the violation batch to send (its {@code responderRank} identifies the partner)
     * @return the partner's violation batch, or {@code null} if no connection to the partner was available.
     *         Callers MUST null-check: a {@code null} return signals a skipped (not failed) exchange and is
     *         distinct from an empty batch, mirroring the underlying transport which yields no batch when a
     *         connection is missing. The aggregator treats {@code null} as graceful degradation (no merge,
     *         no success/failure tally), an empty batch as a successful exchange with zero violations.
     * @throws BalanceExchangeException if the exchange fails; inspect {@link BalanceExchangeException#isTransient()}
     *                                  and {@link BalanceExchangeException#isTimeout()} for retry classification
     */
    ViolationBatch<Key> exchangeViolations(ViolationBatch<Key> batch) throws BalanceExchangeException;
}
