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
import com.hellblazer.luciferase.lucien.balancing.grpc.BalanceCoordinatorClient;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.forest.Forest;
import com.hellblazer.luciferase.lucien.forest.ghost.DistributedGhostManager;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostLayer;
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

    // Context for current balance cycle (set during balance() execution)
    private volatile Forest<Key, ID, Content> currentForest;
    private volatile DistributedGhostManager<Key, ID, Content> currentGhostManager;
    private volatile CrossPartitionBalancePhase<Key, ID, Content> crossPartitionPhase;

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

        // Check if forest context is available for full implementation
        if (currentForest != null && currentGhostManager != null) {
            log.debug("Full forest context available, using CrossPartitionBalancePhase");

            try {
                // Get ghost layer from the ghost manager
                var ghostLayer = currentGhostManager.getGhostLayer();

                // Create cross-partition balance phase if not already created
                if (crossPartitionPhase == null) {
                    crossPartitionPhase = new CrossPartitionBalancePhase<>(
                        createBalanceCoordinatorClient(registry),
                        registry,
                        configuration
                    );
                }

                // Set forest context for violation detection
                crossPartitionPhase.setForestContext(currentForest, ghostLayer);

                log.debug("Phase 3: Executing O(log P) cross-partition refinement");

                // Execute cross-partition balance with forest integration
                return crossPartitionPhase.execute(
                    currentForest,
                    registry.getCurrentPartitionId(),
                    registry.getPartitionCount()
                );

            } catch (Exception e) {
                log.error("Cross-partition balance failed", e);
                return BalanceResult.failure(metrics.snapshot(), "Cross-partition balance failed: " + e.getMessage());
            }
        } else {
            // Fallback: skeleton implementation for testing without forest context
            log.debug("No forest context available, using skeleton implementation");

            var startTime = java.time.Instant.now();

            // Simulate one round of cross-partition balance for metrics
            var roundDuration = java.time.Duration.between(startTime, java.time.Instant.now());
            metrics.recordRound(roundDuration);

            log.debug("Cross-partition balance skeleton completed: {} rounds", metrics.roundCount());

            return BalanceResult.success(metrics.snapshot(), 0);
        }
    }

    @Override
    public BalanceResult balance(DistributedForest<Key, ID, Content> distributedForest) {
        Objects.requireNonNull(distributedForest, "Distributed forest cannot be null");

        log.info("Starting full parallel balance cycle");

        try {
            // Store context for use in balance phases
            this.currentForest = distributedForest.getLocalForest();
            this.currentGhostManager = distributedForest.getGhostManager();

            // Phase 1: Local balance
            var localResult = localBalance(currentForest);
            if (!localResult.successful()) {
                return localResult;
            }

            // Phase 2: Ghost exchange
            exchangeGhosts(currentGhostManager);

            // Phase 3: Cross-partition balance
            var crossPartitionResult = crossPartitionBalance(distributedForest.getPartitionRegistry());

            // Return final result
            return crossPartitionResult;

        } catch (Exception e) {
            log.error("Balance cycle failed with exception", e);
            return BalanceResult.failure(metrics.snapshot(), "Exception during balance: " + e.getMessage());
        } finally {
            // Clear context after balance cycle
            this.currentForest = null;
            this.currentGhostManager = null;
            this.crossPartitionPhase = null;
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

    /**
     * Create a balance coordinator client for gRPC refinement communication.
     *
     * <p>This factory method creates the gRPC client that CrossPartitionBalancePhase uses
     * to send refinement requests to neighbor partitions.
     *
     * @param registry the partition registry for client configuration
     * @return a new balance coordinator client
     */
    private BalanceCoordinatorClient createBalanceCoordinatorClient(PartitionRegistry registry) {
        // TODO: Implement gRPC client creation from registry
        // For now, return a stub that delegates to the registry
        log.debug("Creating BalanceCoordinatorClient for partition {}", registry.getCurrentPartitionId());

        // This will be fully implemented when gRPC integration is complete
        // For skeleton tests, this can be mocked
        return null;
    }
}
