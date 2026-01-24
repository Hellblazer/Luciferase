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
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.forest.Forest;
import com.hellblazer.luciferase.lucien.forest.ghost.DistributedGhostManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Default implementation of ParallelBalancer for distributed tree balancing.
 *
 * <p>This implementation integrates:
 * <ul>
 *   <li><b>Phase 1 (Local Balance)</b>: Uses {@link TreeBalancer} via {@link LocalBalancePhase}
 *       to balance trees within each partition independently</li>
 *   <li><b>Phase 2 (Ghost Exchange)</b>: Uses {@link GhostExchangePhase} to exchange ghost
 *       elements with level information across partition boundaries</li>
 *   <li><b>Phase 3 (Cross-Partition Balance)</b>: Performs O(log P) rounds of distributed
 *       refinement to achieve global 2:1 balance invariant</li>
 * </ul>
 *
 * <p><b>Thread Safety</b>: This class is thread-safe. Metrics are tracked using atomic operations,
 * and balance operations can be called concurrently (though typically executed sequentially by design).
 *
 * <p><b>TDD RED PHASE</b>: This is a skeleton implementation that compiles but does NOT work yet.
 * Tests in DefaultParallelBalancerPhase1Test should FAIL until full implementation is complete.
 *
 * @param <Key> the spatial key type (MortonKey, TetreeKey, etc.)
 * @param <ID> the entity ID type
 * @param <Content> the content type stored with entities
 * @author hal.hildebrand
 */
public class DefaultParallelBalancer<Key extends SpatialKey<Key>, ID extends EntityID, Content>
    implements ParallelBalancer<Key, ID, Content> {

    private static final Logger log = LoggerFactory.getLogger(DefaultParallelBalancer.class);

    private final BalanceConfiguration configuration;
    private final BalanceMetrics metrics;
    private final LocalBalancePhase<Key, ID, Content> localBalancePhase;
    private final GhostExchangePhase<Key, ID, Content> ghostExchangePhase;

    /**
     * Create a new default parallel balancer with the specified configuration.
     *
     * @param configuration the balance configuration
     * @throws NullPointerException if configuration is null
     */
    public DefaultParallelBalancer(BalanceConfiguration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "Configuration cannot be null");
        this.metrics = new BalanceMetrics();

        // Create phase executors (skeleton implementations)
        this.localBalancePhase = new LocalBalancePhase<>(configuration, metrics);
        this.ghostExchangePhase = new GhostExchangePhase<>(configuration);

        log.info("Created DefaultParallelBalancer with config: {}", configuration);
    }

    @Override
    public BalanceResult localBalance(Forest<Key, ID, Content> forest) {
        if (forest == null) {
            throw new IllegalArgumentException("Forest cannot be null");
        }

        log.debug("Starting Phase 1: Local balance");

        // Execute local balance phase
        var phaseResult = localBalancePhase.execute(forest);

        // Build result based on phase outcome
        if (phaseResult.successful()) {
            return BalanceResult.success(metrics.snapshot(), phaseResult.refinementsApplied());
        } else {
            return BalanceResult.failure(metrics.snapshot(), "Local balance failed");
        }
    }

    @Override
    public void exchangeGhosts(DistributedGhostManager<Key, ID, Content> ghostManager) {
        if (ghostManager == null) {
            throw new IllegalArgumentException("Ghost manager cannot be null");
        }

        log.debug("Starting Phase 2: Ghost exchange");

        // Extract boundary ghosts from local balance
        var boundaryGhosts = localBalancePhase.extractBoundaryGhosts();
        log.debug("Extracted {} boundary ghosts for exchange", boundaryGhosts.size());

        // Exchange with neighbors
        var exchangeResult = ghostExchangePhase.exchange(boundaryGhosts, ghostManager);

        // Apply received ghosts
        ghostExchangePhase.applyReceivedGhosts(exchangeResult.receivedGhosts());

        log.debug("Ghost exchange completed: sent {}, received {}",
                 exchangeResult.ghostsSent(), exchangeResult.ghostsReceived());
    }

    @Override
    public BalanceResult crossPartitionBalance(PartitionRegistry registry) {
        if (registry == null) {
            throw new IllegalArgumentException("Partition registry cannot be null");
        }

        log.debug("Starting Phase 3: Cross-partition balance");

        var startTime = java.time.Instant.now();

        // TODO: Implement Phase 3 - O(log P) cross-partition refinement
        // This is a skeleton that executes at least one round for metrics

        // Simulate one round of cross-partition balance
        var roundDuration = java.time.Duration.between(startTime, java.time.Instant.now());
        metrics.recordRound(roundDuration);

        log.debug("Cross-partition balance completed: {} rounds", metrics.roundCount());

        // This will be fully implemented in F4.1.4
        return BalanceResult.success(metrics.snapshot(), 0);
    }

    @Override
    public BalanceResult balance(DistributedForest<Key, ID, Content> distributedForest) {
        Objects.requireNonNull(distributedForest, "Distributed forest cannot be null");

        log.info("Starting full parallel balance cycle");

        try {
            // Phase 1: Local balance
            var localResult = localBalance(distributedForest.getLocalForest());
            if (!localResult.successful()) {
                return localResult;
            }

            // Phase 2: Ghost exchange
            exchangeGhosts(distributedForest.getGhostManager());

            // Phase 3: Cross-partition balance
            var crossPartitionResult = crossPartitionBalance(distributedForest.getPartitionRegistry());

            // Return final result
            return crossPartitionResult;

        } catch (Exception e) {
            log.error("Balance cycle failed with exception", e);
            return BalanceResult.failure(metrics.snapshot(), "Exception during balance: " + e.getMessage());
        }
    }

    @Override
    public BalanceMetrics getMetrics() {
        return metrics;
    }

    /**
     * Get the balance configuration.
     *
     * @return the configuration
     */
    public BalanceConfiguration getConfiguration() {
        return configuration;
    }
}
