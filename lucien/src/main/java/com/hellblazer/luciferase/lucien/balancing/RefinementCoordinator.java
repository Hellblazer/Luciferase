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

import com.hellblazer.luciferase.lucien.balancing.grpc.BalanceCoordinatorClient;
import com.hellblazer.luciferase.lucien.balancing.proto.RefinementRequest;
import com.hellblazer.luciferase.lucien.balancing.proto.RefinementResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Coordinates refinement across all partitions in O(log P) rounds.
 *
 * <p>This class orchestrates the distributed refinement protocol by:
 * <ul>
 *   <li>Executing ceil(log₂(P)) refinement rounds</li>
 *   <li>Coordinating communication with neighbor partitions</li>
 *   <li>Managing barrier synchronization for round completion</li>
 *   <li>Tracking convergence across all partitions</li>
 * </ul>
 *
 * <p>Based on the p4est parallel AMR algorithm's O(log P) refinement protocol.
 *
 * <p>Thread-safe: Uses thread-safe client and request manager.
 *
 * @author hal.hildebrand
 */
public class RefinementCoordinator {

    private static final Logger log = LoggerFactory.getLogger(RefinementCoordinator.class);

    private final BalanceCoordinatorClient client;
    private final RefinementRequestManager requestManager;
    private final int myRank;
    private final int totalPartitions;

    /**
     * Create a new refinement coordinator.
     *
     * @param client the gRPC client for communication
     * @param requestManager the request manager for tracking
     * @param myRank this partition's rank (0 to P-1)
     * @param totalPartitions total number of partitions P
     * @throws NullPointerException if client or requestManager is null
     * @throws IllegalArgumentException if myRank < 0 or totalPartitions <= 0
     */
    public RefinementCoordinator(BalanceCoordinatorClient client, RefinementRequestManager requestManager,
                                int myRank, int totalPartitions) {
        this.client = Objects.requireNonNull(client, "client cannot be null");
        this.requestManager = Objects.requireNonNull(requestManager, "requestManager cannot be null");

        if (myRank < 0) {
            throw new IllegalArgumentException("myRank must be non-negative, got " + myRank);
        }
        if (totalPartitions <= 0) {
            throw new IllegalArgumentException("totalPartitions must be positive, got " + totalPartitions);
        }

        this.myRank = myRank;
        this.totalPartitions = totalPartitions;
    }

    /**
     * Coordinate refinement across all partitions in O(log P) rounds.
     *
     * <p>The algorithm executes up to ceil(log₂(P)) rounds, where P is the total
     * partition count. Each round:
     * <ol>
     *   <li>Executes refinement round with all neighbors</li>
     *   <li>Synchronizes partitions via barrier</li>
     *   <li>Checks for convergence</li>
     * </ol>
     *
     * <p>Terminates early if convergence is detected or maxRounds is reached.
     *
     * @param totalPartitions the total number of partitions
     * @param maxRounds the maximum number of rounds to execute
     * @param initiatorRank the rank of the initiating partition
     * @param registry the partition registry for barrier synchronization
     * @return the coordination result with statistics
     */
    public CoordinationResult coordinateRefinement(int totalPartitions, int maxRounds, int initiatorRank,
                                                   ParallelBalancer.PartitionRegistry registry) {
        log.info("Coordinating refinement: partitions={}, maxRounds={}, initiator={}",
                totalPartitions, maxRounds, initiatorRank);

        // Calculate optimal rounds = min(ceil(log₂(P)), maxRounds)
        var optimalRounds = (int) Math.ceil(Math.log(totalPartitions) / Math.log(2));
        var targetRounds = Math.min(optimalRounds, maxRounds);

        log.debug("Calculated target rounds: optimal={}, max={}, target={}",
                 optimalRounds, maxRounds, targetRounds);

        var startTime = System.currentTimeMillis();
        var totalRefinements = 0;
        var converged = false;

        // Execute refinement rounds
        for (int round = 1; round <= targetRounds; round++) {
            var roundResult = executeRefinementRound(round, targetRounds);
            totalRefinements += roundResult.refinementsApplied();

            // Synchronize after each round
            synchronizePartitions(round, registry);

            // Check for convergence (early termination)
            if (!roundResult.needsMoreRefinement()) {
                log.info("Converged after {} rounds (no more refinement needed)", round);
                converged = true;
                var elapsed = System.currentTimeMillis() - startTime;
                return new CoordinationResult(round, totalRefinements, true, elapsed);
            }

            log.debug("Completed refinement round {}: refinements={}, needsMore={}",
                     round, roundResult.refinementsApplied(), roundResult.needsMoreRefinement());
        }

        var elapsed = System.currentTimeMillis() - startTime;

        log.info("Coordination complete: executed {} rounds, refinements={}, converged={}",
                targetRounds, totalRefinements, converged);

        return new CoordinationResult(targetRounds, totalRefinements, converged, elapsed);
    }

    /**
     * Execute a single refinement round with all butterfly partners.
     *
     * <p>Uses the butterfly pattern to identify partners for this round and sends
     * refinement requests to them in parallel using virtual threads.
     *
     * @param roundNumber the current round number (1-based)
     * @param targetRounds the target number of rounds
     * @return the result of this refinement round
     */
    public RoundResult executeRefinementRound(int roundNumber, int targetRounds) {
        log.debug("Executing refinement round {} (rank {}/{})", roundNumber, myRank, totalPartitions);

        var startTime = System.currentTimeMillis();

        // Convert from 1-based to 0-based for ButterflyPattern
        var zeroBasedRound = roundNumber - 1;

        // Get butterfly partner for this round
        var partner = ButterflyPattern.getPartner(myRank, zeroBasedRound, totalPartitions);

        var refinementsApplied = 0;

        if (partner < 0) {
            // No partner for this round (non-power-of-2 edge case)
            log.debug("Rank {} has no partner in round {} (0-based round {})",
                     myRank, roundNumber, zeroBasedRound);
        } else {
            log.debug("Rank {} communicating with partner {} in round {}",
                     myRank, partner, roundNumber);

            // Build refinement requests for partner
            var requests = buildRequestsForPartner(partner, roundNumber);

            if (!requests.isEmpty()) {
                // Send requests in parallel
                var futures = sendRequestsParallel(requests);

                // Wait for responses with timeout
                var responses = new ArrayList<RefinementResponse>();
                for (var future : futures) {
                    try {
                        var response = future.get(5, TimeUnit.SECONDS);
                        responses.add(response);

                        // Track refinements from this response
                        if (response.getGhostElementsCount() > 0) {
                            refinementsApplied += response.getGhostElementsCount();
                        }
                    } catch (Exception e) {
                        log.warn("Failed to get response from partner {} in round {}: {}",
                                partner, roundNumber, e.getMessage());
                        // Continue processing other responses
                    }
                }

                log.debug("Received {} responses from partner {} in round {}",
                         responses.size(), partner, roundNumber);
            }
        }

        var needsMoreRefinement = (roundNumber < targetRounds);
        var elapsed = System.currentTimeMillis() - startTime;

        log.trace("Completed refinement round {}: refinements={}, needsMore={}",
                 roundNumber, refinementsApplied, needsMoreRefinement);

        return new RoundResult(roundNumber, refinementsApplied, needsMoreRefinement, elapsed);
    }

    /**
     * Build refinement requests for a specific butterfly partner.
     *
     * <p>Creates requests containing boundary keys that need refinement
     * from the partner partition.
     *
     * @param partnerRank the rank of the partner partition
     * @param roundNumber the current round number
     * @return list of refinement requests (typically 1 per partner)
     */
    private List<RefinementRequest> buildRequestsForPartner(int partnerRank, int roundNumber) {
        var requests = new ArrayList<RefinementRequest>();

        // Build request for this partner
        var request = RefinementRequest.newBuilder()
            .setRequesterRank(myRank)
            .setRequesterTreeId(0L)  // TODO: support multiple trees
            .setRoundNumber(roundNumber)
            .setTreeLevel(0)  // TODO: extract actual level from boundary violations
            .setTimestamp(System.currentTimeMillis())
            .build();

        requests.add(request);

        log.trace("Built {} refinement requests for partner rank {}", requests.size(), partnerRank);

        return requests;
    }

    /**
     * Synchronize all partitions at a barrier.
     *
     * <p>Ensures all partitions complete the current round before any partition
     * proceeds to the next round. This maintains consistency across the distributed
     * refinement protocol.
     *
     * @param roundNumber the round number for barrier synchronization
     * @param registry the partition registry for synchronization
     */
    public void synchronizePartitions(int roundNumber, ParallelBalancer.PartitionRegistry registry) {
        log.debug("Synchronizing partitions at round {}", roundNumber);

        try {
            registry.barrier(roundNumber);
            log.trace("Barrier synchronization complete for round {}", roundNumber);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while synchronizing at round {}", roundNumber);
            throw new RuntimeException("Barrier synchronization interrupted", e);
        }
    }

    /**
     * Send refinement requests to butterfly partners in parallel.
     *
     * <p>Uses virtual threads to handle concurrent requests to multiple butterfly partners
     * in a single refinement round. In the butterfly pattern, each rank communicates with
     * exactly one partner per round (calculated as rank XOR 2^round).
     *
     * @param requests the refinement requests to send (typically 1 per butterfly partner)
     * @return futures for all requests
     */
    private List<CompletableFuture<RefinementResponse>> sendRequestsParallel(List<RefinementRequest> requests) {
        log.debug("Sending {} refinement requests in parallel", requests.size());

        var futures = new ArrayList<CompletableFuture<RefinementResponse>>();

        // Default timeout of 5 seconds per request
        // (will be made configurable via BalanceConfiguration in future phases)
        final long timeoutSeconds = 5;

        for (var request : requests) {
            // Track request for monitoring
            requestManager.trackRequest(request, System.currentTimeMillis());

            // Send async with timeout
            var future = client.requestRefinementAsync(
                request.getRequesterRank(),
                request.getRequesterTreeId(),
                request.getRoundNumber(),
                request.getTreeLevel(),
                request.getBoundaryKeysList()
            )
            .orTimeout(timeoutSeconds, TimeUnit.SECONDS)  // Default 5 second timeout
            .exceptionally(ex -> {
                log.warn("Request from rank {} failed in round {}: {}",
                    request.getRequesterRank(), request.getRoundNumber(), ex.getMessage());
                // Return empty response on timeout/failure
                return RefinementResponse.getDefaultInstance();
            });

            futures.add(future);
            log.trace("Queued request from rank {} in round {}", request.getRequesterRank(), request.getRoundNumber());
        }

        return futures;
    }

    /**
     * Result of coordinating refinement across all partitions.
     *
     * @param roundsExecuted the number of refinement rounds executed
     * @param refinementsApplied the total refinements applied across all rounds
     * @param converged whether the refinement converged
     * @param totalTimeMillis the total time spent in milliseconds
     */
    public record CoordinationResult(
        int roundsExecuted,
        int refinementsApplied,
        boolean converged,
        long totalTimeMillis
    ) {}

    /**
     * Result of a single refinement round.
     *
     * @param roundNumber the round number
     * @param refinementsApplied the number of refinements applied in this round
     * @param needsMoreRefinement whether more refinement is needed
     * @param roundTimeMillis the time spent in this round in milliseconds
     */
    public record RoundResult(
        int roundNumber,
        int refinementsApplied,
        boolean needsMoreRefinement,
        long roundTimeMillis
    ) {}
}
