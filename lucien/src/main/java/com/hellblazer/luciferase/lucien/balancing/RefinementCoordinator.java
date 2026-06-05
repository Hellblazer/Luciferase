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

import com.hellblazer.luciferase.common.time.Clock;
import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * <p>Thread-safe: Uses thread-safe exchange and request manager.
 *
 * @param <Key> the spatial key type (MortonKey, TetreeKey, etc.)
 * @param <ID> the entity ID type
 * @param <Content> the content type stored with entities
 * @author hal.hildebrand
 */
public class RefinementCoordinator<Key extends SpatialKey<Key>, ID extends EntityID, Content> {

    private static final Logger log = LoggerFactory.getLogger(RefinementCoordinator.class);

    private volatile Clock clock = Clock.system();

    private final RefinementExchange<Key, ID, Content> exchange;
    private final RefinementRequestManager requestManager;
    private final int myRank;
    private final int totalPartitions;

    /**
     * Per-partner refinement requests for the current round, keyed by target (partner) rank (Luciferase-uhsn, B10b —
     * D1). Populated by {@code CrossPartitionBalancePhase} from the round's 2:1 violations (findViolations ->
     * createRefinementRequests, D3-filtered to ghost-coarser violations only). {@link #buildRequestsForPartner}
     * selects the bucket for the butterfly partner. Empty map => no work to push this round (vacuous requests are no
     * longer synthesized).
     */
    private final Map<Integer, List<RefinementRequest<Key>>> requestsByPartner;

    /**
     * Create a new refinement coordinator.
     *
     * @param exchange the domain exchange interface for refinement communication
     * @param requestManager the request manager for tracking
     * @param myRank this partition's rank (0 to P-1)
     * @param totalPartitions total number of partitions P
     * @throws NullPointerException if exchange or requestManager is null
     * @throws IllegalArgumentException if myRank < 0 or totalPartitions <= 0
     */
    public RefinementCoordinator(RefinementExchange<Key, ID, Content> exchange,
                                 RefinementRequestManager requestManager,
                                 int myRank, int totalPartitions) {
        this(exchange, requestManager, myRank, totalPartitions, Map.of());
    }

    /**
     * Create a refinement coordinator with a per-partner request map (Luciferase-uhsn, B10b — D1).
     *
     * @param exchange the domain exchange interface for refinement communication
     * @param requestManager the request manager for tracking
     * @param myRank this partition's rank (0 to P-1)
     * @param totalPartitions total number of partitions P
     * @param requestsByPartner refinement requests for this round keyed by target (partner) rank; an empty map means
     *                          this partition has nothing to push (no vacuous requests are synthesized)
     * @throws NullPointerException if exchange, requestManager, or requestsByPartner is null
     * @throws IllegalArgumentException if myRank < 0 or totalPartitions <= 0
     */
    public RefinementCoordinator(RefinementExchange<Key, ID, Content> exchange,
                                 RefinementRequestManager requestManager,
                                 int myRank, int totalPartitions,
                                 Map<Integer, List<RefinementRequest<Key>>> requestsByPartner) {
        this.exchange = Objects.requireNonNull(exchange, "exchange cannot be null");
        this.requestManager = Objects.requireNonNull(requestManager, "requestManager cannot be null");
        this.requestsByPartner = Objects.requireNonNull(requestsByPartner, "requestsByPartner cannot be null");

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
     * Inject a clock for deterministic time in tests. Propagates to the owned
     * RefinementRequestManager so both use the same virtual timeline and RTT
     * measurements are consistent.
     *
     * @param clock the clock to use; must not be null
     */
    public void setClock(Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock cannot be null");
        requestManager.setClock(clock);
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

        var startTime = clock.currentTimeMillis();
        var totalRefinements = 0;
        var converged = false;
        var roundsExecuted = 0;

        // Luciferase-uhsn (B10b — D2): iterate until a global Allreduce-LAND reports every partition is locally
        // balanced (t8_forest_balance's `while (!done_global)`), NOT a fixed ceil(log2 P) count. maxRounds is a
        // safety cap only. NOTE: this coordinator drives off a single injected per-partner snapshot, so it cannot
        // recompute violations between rounds — the live, multi-round convergence loop that re-derives violations as
        // refinements land is CrossPartitionBalancePhase.execute(). This method is the unit-level convergence harness.
        for (int round = 1; round <= maxRounds; round++) {
            roundsExecuted = round;

            var partner = ButterflyPattern.getPartner(myRank, round - 1, totalPartitions);
            var hadRequests = partner >= 0 && !requestsByPartner.getOrDefault(partner, List.of()).isEmpty();

            var roundResult = executeRefinementRound(round, maxRounds);
            totalRefinements += roundResult.refinementsApplied();

            // Synchronize after each round
            synchronizePartitions(round, registry);

            // Locally balanced this round iff we pushed no requests AND applied no refinements from responses.
            var locallyBalanced = !hadRequests && roundResult.refinementsApplied() == 0;

            boolean globallyConverged;
            try {
                globallyConverged = registry.allReduceConverged(locallyBalanced);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Allreduce convergence interrupted at round " + round, e);
            }

            log.debug("Completed refinement round {}: refinements={}, locallyBalanced={}, globallyConverged={}",
                     round, roundResult.refinementsApplied(), locallyBalanced, globallyConverged);

            if (globallyConverged) {
                log.info("Converged after {} rounds (global Allreduce-LAND)", round);
                converged = true;
                break;
            }
        }

        var elapsed = clock.currentTimeMillis() - startTime;

        log.info("Coordination complete: executed {} rounds, refinements={}, converged={}",
                roundsExecuted, totalRefinements, converged);

        return new CoordinationResult(roundsExecuted, totalRefinements, converged, elapsed);
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

        var startTime = clock.currentTimeMillis();

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
                // Send requests in parallel (targeted at the butterfly partner)
                var futures = sendRequestsParallel(partner, requests);

                // Wait for responses with timeout
                var responses = new ArrayList<RefinementResponse<Key, ID, Content>>();
                for (var future : futures) {
                    try {
                        var response = future.get(5, TimeUnit.SECONDS);
                        responses.add(response);

                        // Track refinements from this response
                        if (response.ghostElements().size() > 0) {
                            refinementsApplied += response.ghostElements().size();
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
        var elapsed = clock.currentTimeMillis() - startTime;

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
    private List<RefinementRequest<Key>> buildRequestsForPartner(int partnerRank, int roundNumber) {
        // D1 (Luciferase-uhsn): select the real per-partner request bucket built from this round's 2:1 violations.
        // No bucket => nothing to push to this partner this round (no vacuous request).
        var partnerRequests = requestsByPartner.getOrDefault(partnerRank, List.of());
        if (partnerRequests.isEmpty()) {
            return List.of();
        }

        // Overwrite the pre-coordinator placeholders stamped by TwoOneBalanceChecker.createRefinementRequests
        // (roundNumber=0, which collides with the butterfly sentinel) with the actual convergence round. requesterRank
        // stays the LOCAL rank (reply-to); requesterTreeId/treeLevel/boundaryKeys/timestamp are preserved.
        var requests = new ArrayList<RefinementRequest<Key>>(partnerRequests.size());
        for (var r : partnerRequests) {
            requests.add(new RefinementRequest<>(r.requesterRank(), r.requesterTreeId(), roundNumber,
                                                 r.treeLevel(), r.boundaryKeys(), r.timestamp()));
        }

        log.trace("Built {} refinement requests for partner rank {} (round {})",
                  requests.size(), partnerRank, roundNumber);

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
     * @param requests the domain refinement requests to send (typically 1 per butterfly partner)
     * @return futures for all requests
     */
    private List<CompletableFuture<RefinementResponse<Key, ID, Content>>> sendRequestsParallel(
            int targetRank, List<RefinementRequest<Key>> requests) {
        log.debug("Sending {} refinement requests in parallel to partner rank {}", requests.size(), targetRank);

        var futures = new ArrayList<CompletableFuture<RefinementResponse<Key, ID, Content>>>();

        // Default timeout of 5 seconds per request
        // (will be made configurable via BalanceConfiguration in future phases)
        final long timeoutSeconds = 5;

        for (var request : requests) {
            // Track request for monitoring
            requestManager.trackRequest(request, clock.currentTimeMillis());

            // Send async with timeout via domain exchange. The request travels to the butterfly PARTNER
            // (targetRank); request.requesterRank() is the LOCAL reply-to rank (Luciferase-uhsn D1/w3lm S1),
            // NOT the destination — do not conflate the two.
            var future = exchange.requestRefinementAsync(
                targetRank,
                request.requesterTreeId(),
                request.roundNumber(),
                request.treeLevel(),
                request.boundaryKeys()
            )
            .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .exceptionally(ex -> {
                log.warn("Request from rank {} failed in round {}: {}",
                    request.requesterRank(), request.roundNumber(), ex.getMessage());
                // Return empty response on timeout/failure
                return RefinementResponse.empty();
            });

            futures.add(future);
            log.trace("Queued request from rank {} in round {}", request.requesterRank(), request.roundNumber());
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
