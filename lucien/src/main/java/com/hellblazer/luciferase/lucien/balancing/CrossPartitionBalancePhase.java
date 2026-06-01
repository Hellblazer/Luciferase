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
import com.hellblazer.luciferase.lucien.forest.ghost.GhostElement;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostLayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Implements Phase 3 of parallel balancing: O(log P) cross-partition refinement protocol.
 *
 * <p>This class coordinates distributed tree balancing across multiple partitions using
 * an iterative refinement protocol. The algorithm executes ceil(log₂(P)) refinement rounds
 * where P is the total number of partitions, ensuring O(log P) complexity.
 *
 * <p>Each refinement round:
 * <ol>
 *   <li>Identifies boundary elements needing refinement using level information</li>
 *   <li>Sends RefinementRequest to neighbor partitions via the domain exchange interface</li>
 *   <li>Receives RefinementResponse with already-deserialized domain ghost elements</li>
 *   <li>Applies ghost elements to local forest</li>
 *   <li>Synchronizes all partitions via barrier</li>
 *   <li>Checks convergence (no more refinements needed)</li>
 * </ol>
 *
 * <p>Based on the p4est parallel AMR algorithm (Burstedde et al., SIAM 2011).
 *
 * <p>Thread-safe: Uses immutable configuration and thread-safe exchange/registry.
 *
 * @param <Key> the spatial key type (MortonKey, TetreeKey, etc.)
 * @param <ID> the entity ID type
 * @param <Content> the content type stored with entities
 * @author hal.hildebrand
 */
public class CrossPartitionBalancePhase<Key extends SpatialKey<Key>, ID extends EntityID, Content> {

    private static final Logger log = LoggerFactory.getLogger(CrossPartitionBalancePhase.class);

    private final RefinementExchange<Key, ID, Content> exchange;
    private final ParallelBalancer.PartitionRegistry registry;
    private final BalanceConfiguration config;

    // Forest context for violation detection and ghost element application
    private volatile Forest<Key, ID, Content> forest;
    private volatile GhostLayer<Key, ID, Content> ghostLayer;
    private volatile TwoOneBalanceChecker<Key, ID, Content> balanceChecker;

    // Luciferase-m27q (S9): hook invoked once per round after local subdivisions to re-synchronize the ghost layer
    // before the next balance-check, so a stale ghost cannot hide a violation on either side. Default is a no-op
    // (single-partition / tests that re-sync externally); cross-partition wiring sets a real ghost refresh.
    private volatile Runnable ghostResync = () -> { };

    /**
     * Create a new cross-partition balance phase.
     *
     * @param exchange the domain exchange interface for refinement requests
     * @param registry the partition registry for coordination
     * @param config the balance configuration
     * @throws NullPointerException if any parameter is null
     */
    public CrossPartitionBalancePhase(RefinementExchange<Key, ID, Content> exchange,
                                      ParallelBalancer.PartitionRegistry registry,
                                      BalanceConfiguration config) {
        this.exchange = Objects.requireNonNull(exchange, "exchange cannot be null");
        this.registry = Objects.requireNonNull(registry, "registry cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
    }

    /**
     * Set forest context for violation detection and refinement.
     *
     * @param forest the local forest containing local elements
     * @param ghostLayer the ghost layer for boundary element checking
     * @throws NullPointerException if forest or ghostLayer is null
     */
    public void setForestContext(Forest<Key, ID, Content> forest,
                                 GhostLayer<Key, ID, Content> ghostLayer) {
        this.forest = Objects.requireNonNull(forest, "forest cannot be null");
        this.ghostLayer = Objects.requireNonNull(ghostLayer, "ghostLayer cannot be null");
        this.balanceChecker = new TwoOneBalanceChecker<>();
        log.debug("Forest context set: forest with {} trees, {} ghost elements",
                 forest.getTreeCount(), ghostLayer.getNumGhostElements());
    }

    /**
     * Override the balance checker after {@link #setForestContext} (test seam, Luciferase-m27q). Lets a test drive
     * the round loop with a controlled violation set, since constructing a Morton-detectable local-coarser violation
     * geometrically is intricate and orthogonal to the local-refinement path under test. Not used in production.
     *
     * @param balanceChecker the checker to use (must not be null)
     */
    public void setBalanceChecker(TwoOneBalanceChecker<Key, ID, Content> balanceChecker) {
        this.balanceChecker = Objects.requireNonNull(balanceChecker, "balanceChecker cannot be null");
    }

    /**
     * Set the ghost re-synchronization hook (Luciferase-m27q, S9). Invoked once per round, after this partition's
     * local subdivisions, so the ghost layer reflects the adapted tree before the next balance-check — closing the
     * stale-ghost / undetected-violation hazard. Defaults to a no-op.
     *
     * @param ghostResync the re-sync action (must not be null)
     */
    public void setGhostResync(Runnable ghostResync) {
        this.ghostResync = Objects.requireNonNull(ghostResync, "ghostResync cannot be null");
    }

    /**
     * Execute O(log P) refinement rounds for cross-partition balance.
     *
     * <p>The algorithm performs ceil(log₂(P)) rounds where P is the total partition count,
     * or terminates early if:
     * <ul>
     *   <li>Convergence is detected (no more refinements needed)</li>
     *   <li>Maximum rounds configured is reached</li>
     *   <li>Timeout per round is exceeded</li>
     * </ul>
     *
     * @param forest the forest to balance
     * @param initiatorRank the rank of the partition initiating balance
     * @param totalPartitions the total number of partitions
     * @return the balance result with metrics
     * @throws NullPointerException if forest is null
     */
    public BalanceResult execute(Forest<Key, ID, Content> forest, int initiatorRank, int totalPartitions) {
        Objects.requireNonNull(forest, "forest cannot be null");

        log.info("Starting cross-partition balance: initiator={}, partitions={}", initiatorRank, totalPartitions);

        // Luciferase-uhsn: execute() is the canonical LIVE path — it detects violations and sends per-target-rank
        // requests inline via the exchange (see executeRefinementRound). RefinementCoordinator.coordinateRefinement
        // is the separate unit-level convergence harness and is intentionally NOT on this path (driving it here would
        // discard the response->ghostLayer application that executeRefinementRound performs).

        var metrics = new BalanceMetrics();
        var startTime = System.currentTimeMillis();

        try {
            // Luciferase-uhsn (B10b — D2): iterate until a global Allreduce-LAND reports EVERY partition is locally
            // balanced (no 2:1 violations this round), mirroring t8_forest_balance's `while (!done_global)`. The
            // round count is therefore data-driven — a balanced forest converges in one round — NOT a fixed
            // ceil(log2 P). config.maxRounds() is only a safety cap against non-termination.
            log.debug("Executing cross-partition balance via Allreduce-LAND convergence (maxRounds={})",
                     config.maxRounds());

            var totalRefinements = 0;
            var converged = false;

            for (int round = 1; round <= config.maxRounds(); round++) {
                var roundResult = executeRefinementRound(round);
                totalRefinements += roundResult.refinementsApplied();
                metrics.recordRound(java.time.Duration.ofMillis(roundResult.roundTimeMillis()));

                // Synchronize after each round
                try {
                    registry.barrier(round);
                    log.trace("Barrier synchronization complete for round {}", round);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Interrupted while synchronizing at round {}", round);
                }

                // Global termination: logical-AND every partition's "I am locally balanced" flag.
                boolean globallyConverged;
                try {
                    globallyConverged = registry.allReduceConverged(roundResult.locallyBalanced());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Interrupted during Allreduce convergence at round {}", round);
                    // Convergence was abandoned — do NOT report success (which would mask the shortfall, since
                    // BalanceResult has no converged flag of its own). Surface it as a failure.
                    return BalanceResult.failure(metrics.snapshot(),
                        "Interrupted during Allreduce convergence at round " + round);
                }
                if (globallyConverged) {
                    log.info("Converged after {} rounds (global Allreduce-LAND)", round);
                    converged = true;
                    break;
                }
            }

            var elapsed = System.currentTimeMillis() - startTime;
            log.info("Cross-partition balance complete: refinements={}, converged={}, time={}ms",
                    totalRefinements, converged, elapsed);

            return BalanceResult.success(metrics.snapshot(), totalRefinements);

        } catch (Exception e) {
            log.error("Cross-partition balance failed", e);
            return BalanceResult.failure(metrics.snapshot(), e.getMessage());
        }
    }

    /**
     * Execute a single refinement round with neighbor communication.
     *
     * <p>Executes the refinement protocol:
     * <ol>
     *   <li>Identify boundary elements needing refinement</li>
     *   <li>Create refinement requests for each boundary</li>
     *   <li>Send requests to neighbors (using butterfly pattern in Phase C)</li>
     *   <li>Collect and process responses</li>
     *   <li>Apply refinements to local forest</li>
     *   <li>Determine if more refinement is needed</li>
     * </ol>
     *
     * @param roundNumber the current round number
     * @return the result of this refinement round
     */
    private RefinementRoundResult executeRefinementRound(int roundNumber) {
        log.debug("Executing refinement round {}", roundNumber);
        var startTime = System.nanoTime();

        try {
            // No forest context => nothing to detect; the partition is trivially locally balanced
            // (t8_forest_balance's done=1). No vacuous request is synthesized (Luciferase-uhsn).
            if (balanceChecker == null || forest == null || ghostLayer == null) {
                log.warn("Forest context not set; round {} has no violations to process", roundNumber);
                var elapsed0 = Math.max(1L, (System.nanoTime() - startTime) / 1_000_000L);
                return new RefinementRoundResult(0, true, elapsed0);
            }

            // Phase 1: detect 2:1 violations. "Locally balanced" iff none remain (the Allreduce-LAND input). Every
            // violation is either local-coarser (-> local queue) or ghost-coarser (-> remote request), so
            // violations.isEmpty() is exactly the design's "produced no remote requests AND queued no local
            // refinements this round". NOTE: this partition does not subdivide (m27q/B10c owns that), so a forest with
            // unresolved violations stays locally-unbalanced every round and the loop runs to the maxRounds safety
            // cap — convergence of a genuinely unbalanced forest only becomes reachable once m27q applies refinements.
            var violations = balanceChecker.findViolations(ghostLayer, forest);
            var locallyBalanced = violations.isEmpty();

            // Phase 2: build per-target-rank requests. Grouping ALL violations by sourceRank and routing each group
            // through createRefinementRequests applies D3 (ghost-coarser -> remote request keyed by that rank;
            // local-coarser -> the checker's local refinement queue for m27q/B10c) and yields one request per remote
            // rank. Overwrite the placeholder roundNumber with the actual round (D1; 0 collides with the butterfly
            // sentinel and must never reach the wire).
            var ts = System.currentTimeMillis();
            var localRank = registry.getCurrentPartitionId();
            var byRank = violations.stream()
                .collect(Collectors.groupingBy(TwoOneBalanceChecker.BalanceViolation::sourceRank));
            var requestsByRank = new java.util.HashMap<Integer, RefinementRequest<Key>>();
            for (var entry : byRank.entrySet()) {
                var reqs = balanceChecker.createRefinementRequests(entry.getValue(), ts, localRank);
                if (!reqs.isEmpty()) {
                    var r = reqs.get(0); // createRefinementRequests emits exactly one request per sourceRank group
                    requestsByRank.put(entry.getKey(),
                        new RefinementRequest<>(r.requesterRank(), r.requesterTreeId(), roundNumber,
                                                r.treeLevel(), r.boundaryKeys(), r.timestamp()));
                }
            }

            // Phase 3: send each request to its target (ghost-owner) rank carrying REAL boundary keys (this replaces
            // the prior inline send that shipped empty key lists), collect responses.
            var responses = new ArrayList<RefinementResponse<Key, ID, Content>>();
            for (var entry : requestsByRank.entrySet()) {
                var targetRank = entry.getKey();
                var req = entry.getValue();
                try {
                    var response = exchange.requestRefinementAsync(
                        targetRank, req.requesterTreeId(), req.roundNumber(), req.treeLevel(),
                        req.boundaryKeys()).get();
                    responses.add(response);
                } catch (Exception e) {
                    log.warn("Round {}: request to rank {} failed: {}", roundNumber, targetRank, e.getMessage());
                }
            }

            // Phase 4: apply returned domain ghost elements (the remote's refined children become finer local
            // ghosts). This stays ghost-only — the LOCAL SpatialIndex refinement is Phase 5.
            applyRefinementResponses(responses);

            // Phase 5 (Luciferase-m27q, B10c): consume the local refinement queue — for violations where the LOCAL
            // element is the coarser side (createRefinementRequests routed these to the queue, not the wire),
            // subdivide the local node by one level so the next round sees it balanced. Trigger ghost re-sync once
            // after the local adapt so the stale-ghost / undetected-violation hazard (partition-balance S9) is closed
            // before the next balance-check.
            var localSubdivides = applyLocalRefinements(roundNumber);

            log.debug("Refinement round {}: {} violations, {} remote requests, {} responses, {} local subdivides, "
                     + "locallyBalanced={}",
                     roundNumber, violations.size(), requestsByRank.size(), responses.size(), localSubdivides,
                     locallyBalanced);

            var elapsedMs = Math.max(1L, (System.nanoTime() - startTime) / 1_000_000L);
            return new RefinementRoundResult(violations.size(), locallyBalanced, elapsedMs);

        } catch (Exception e) {
            log.error("Error in refinement round {}", roundNumber, e);
            var elapsedMs = Math.max(1L, (System.nanoTime() - startTime) / 1_000_000L);
            // On error, do not claim convergence — let the loop continue up to maxRounds.
            return new RefinementRoundResult(0, false, elapsedMs);
        }
    }

    /**
     * Consume the local refinement queue and subdivide each local-coarser node by one level (Luciferase-m27q, B10c).
     *
     * <p>{@link TwoOneBalanceChecker#createRefinementRequests} routed local-coarser violations (where this partition
     * is the side that must refine) to {@link TwoOneBalanceChecker#drainLocalRefinements}. Here we drain that queue
     * and call {@link SpatialIndex#subdivide} on the owning tree for each key, refining the coarse node by one level
     * so the next round's {@code findViolations} sees the boundary balanced. If any node was actually refined, the
     * ghost re-sync hook runs once (S9) so a stale ghost cannot mask a violation before the next balance-check.
     *
     * @param roundNumber current round (logging only)
     * @return the number of nodes actually subdivided this round
     */
    private int applyLocalRefinements(int roundNumber) {
        if (balanceChecker == null || forest == null) {
            return 0;
        }
        var localKeys = balanceChecker.drainLocalRefinements();
        if (localKeys.isEmpty()) {
            return 0;
        }
        int subdivided = 0;
        for (var key : localKeys) {
            for (var tree : forest.getAllTrees()) {
                var index = tree.getSpatialIndex();
                if (index.containsSpatialKey(key)) {
                    if (index.subdivide(key)) {
                        subdivided++;
                    }
                    break;
                }
            }
        }
        if (subdivided > 0) {
            ghostResync.run(); // S9: refresh ghosts after the local adapt, before the next balance-check
        }
        log.debug("Round {}: drained {} local refinement key(s), subdivided {}", roundNumber, localKeys.size(),
                  subdivided);
        return subdivided;
    }

    /**
     * Identify refinement needs using TwoOneBalanceChecker and coordinate with RefinementCoordinator.
     *
     * <p>This method implements the core refinement protocol:
     * <ol>
     *   <li>Uses balanceChecker to find all 2:1 violations</li>
     *   <li>Groups violations by source rank (partition)</li>
     *   <li>Builds RefinementRequest for each rank group</li>
     *   <li>Sends requests in parallel via coordinator</li>
     *   <li>Collects and aggregates responses</li>
     * </ol>
     *
     * @param roundNumber the current round number
     * @param targetRounds the target number of rounds
     * @param balanceChecker the checker to detect violations
     * @param coordinator the coordinator to send requests (accessed via reflection for sendRequestsParallel)
     * @param <Coord> the coordinator type parameter
     * @return RoundResult with violations processed, round status, and timing
     * @throws java.util.concurrent.TimeoutException if requests timeout
     * @throws InterruptedException if interrupted while waiting
     */
    public <Coord> RefinementCoordinator.RoundResult identifyRefinementNeeds(
        int roundNumber,
        int targetRounds,
        TwoOneBalanceChecker<Key, ID, Content> balanceChecker,
        Coord coordinator
    ) throws java.util.concurrent.TimeoutException, InterruptedException {
        var startTime = System.currentTimeMillis();

        // Step 1: Identify violations using balanceChecker
        var violations = balanceChecker.findViolations(ghostLayer, forest);

        log.info("Round {}: Found {} balance violations", roundNumber, violations.size());

        // Return empty result if no violations
        if (violations.isEmpty()) {
            var elapsed = System.currentTimeMillis() - startTime;
            return new RefinementCoordinator.RoundResult(
                roundNumber,
                0,  // refinementsApplied
                false,  // needsMoreRefinement
                elapsed
            );
        }

        // Step 2: Group violations by rank
        var violationsByRank = new java.util.HashMap<Integer, java.util.ArrayList<TwoOneBalanceChecker.BalanceViolation<Key>>>();
        for (var violation : violations) {
            violationsByRank.computeIfAbsent(violation.sourceRank(), rank -> new java.util.ArrayList<>())
                           .add(violation);
        }

        log.debug("Round {}: Grouped {} violations into {} rank groups",
                 roundNumber, violations.size(), violationsByRank.size());

        // Step 3: Build domain RefinementRequests
        var requests = new java.util.ArrayList<RefinementRequest<Key>>();
        for (var entry : violationsByRank.entrySet()) {
            var sourceRank = entry.getKey();
            var groupViolations = entry.getValue();

            // Extract max level from violations
            var maxLevel = groupViolations.stream()
                .mapToInt(v -> Math.max(v.localLevel(), v.ghostLevel()))
                .max()
                .orElse(0);

            // Collect boundary keys from violations (domain SpatialKey, no proto conversion)
            var boundaryKeys = groupViolations.stream()
                .flatMap(v -> Stream.of(v.localKey(), v.ghostKey()))
                .collect(Collectors.toList());

            var request = new RefinementRequest<>(
                registry.getCurrentPartitionId(),
                0L,
                roundNumber,
                maxLevel,
                boundaryKeys,
                System.currentTimeMillis()
            );

            requests.add(request);
            log.trace("Round {}: Created request for rank {} with {} violations, level={}",
                     roundNumber, sourceRank, groupViolations.size(), maxLevel);
        }

        // Step 4: Send async requests via coordinator using reflection.
        // This 4-arg overload is a TEST-ONLY entry point (no production caller); the production round path is
        // execute() -> executeRefinementRound(int), which detects violations and sends per-target-rank requests
        // directly via the exchange (Luciferase-uhsn). Tests pass a coordinator exposing a PUBLIC sendRequestsParallel, so
        // getMethod (public-only) resolves it — byte-identical to the pre-inversion behavior. We keep
        // getMethod (not getDeclaredMethod/setAccessible) precisely to preserve that behavior: against a
        // real RefinementCoordinator (private method) this throws NoSuchMethodException, exactly as before.
        // Erasure-matched: List.class covers the generic List<RefinementRequest<Key>> at the call site.
        List<java.util.concurrent.CompletableFuture<RefinementResponse<Key, ID, Content>>> futures;
        try {
            var method = coordinator.getClass().getMethod("sendRequestsParallel", List.class);
            @SuppressWarnings("unchecked")
            var result = (List<java.util.concurrent.CompletableFuture<RefinementResponse<Key, ID, Content>>>) method.invoke(coordinator, requests);
            futures = result;
        } catch (java.lang.reflect.InvocationTargetException e) {
            // Unwrap exceptions thrown by sendRequestsParallel()
            var cause = e.getCause();
            if (cause instanceof RuntimeException rte) {
                log.warn("Round {}: Coordinator threw exception: {}", roundNumber, rte.getMessage());
                throw rte;  // Propagate RuntimeException directly
            }
            log.error("Failed to send requests via coordinator", e);
            throw new RuntimeException("Coordinator sendRequestsParallel failed", cause);
        } catch (Exception e) {
            log.error("Failed to send requests via coordinator (reflection)", e);
            throw new RuntimeException("Coordinator sendRequestsParallel failed", e);
        }

        // Step 5: Await all futures with timeout
        var responses = new java.util.ArrayList<RefinementResponse<Key, ID, Content>>();
        for (var future : futures) {
            try {
                var response = future.get(5, java.util.concurrent.TimeUnit.SECONDS);
                responses.add(response);
            } catch (java.util.concurrent.TimeoutException e) {
                log.warn("Round {}: Request timed out", roundNumber);
                throw e;
            } catch (java.util.concurrent.ExecutionException e) {
                log.warn("Round {}: Request execution failed: {}", roundNumber, e.getMessage());
            }
        }

        // Step 6: Build RoundResult
        var refinementsApplied = violations.size();
        var needsMoreRefinement = roundNumber < targetRounds;
        var elapsed = System.currentTimeMillis() - startTime;

        log.info("Round {}: Processed {} violations, {} responses, time={}ms",
                roundNumber, refinementsApplied, responses.size(), elapsed);

        return new RefinementCoordinator.RoundResult(
            roundNumber,
            refinementsApplied,
            needsMoreRefinement,
            elapsed
        );
    }

    /**
     * Process a single refinement response and apply domain ghost elements to the ghost layer.
     *
     * <p>Ghost elements are already deserialized domain objects (deserialization happens in the
     * grpc adapter, not here). Each element is applied individually with a per-element guard so
     * a single bad element does not abort the whole batch.
     *
     * @param response the domain refinement response from a remote partition (null is a no-op)
     * @param coordinator the refinement coordinator (for context/logging)
     */
    public void applyRefinementResponses(
            RefinementResponse<Key, ID, Content> response,
            RefinementCoordinator<Key, ID, Content> coordinator) {

        // Validate input
        if (response == null) {
            log.debug("Received null response, skipping ghost element application");
            return;
        }

        if (ghostLayer == null) {
            log.debug("No ghost layer context, skipping response processing");
            return;
        }

        // Extract already-deserialized domain ghost elements from response
        var ghosts = response.ghostElements();
        if (ghosts.isEmpty()) {
            log.debug("Response contains no ghost elements, returning");
            return;
        }

        log.debug("Processing {} ghost elements from response (responder rank: {})",
                 ghosts.size(), response.responderRank());

        var addedCount = 0;
        var skippedCount = 0;

        // Process each domain ghost element with per-element guard
        for (var ghost : ghosts) {
            try {
                ghostLayer.addGhostElement(ghost);
                addedCount++;

                log.trace("Added ghost element: key={}, entityId={}, ownerRank={}",
                         ghost.getSpatialKey(),
                         ghost.getEntityId(),
                         ghost.getOwnerRank());
            } catch (Exception e) {
                log.warn("Unexpected error adding ghost element: {}", e.getMessage(), e);
                skippedCount++;
            }
        }

        log.debug("Applied {} ghost elements from response (rank {}), skipped {} invalid elements",
                 addedCount, response.responderRank(), skippedCount);
    }

    /**
     * Process refinement responses and apply domain ghost elements to forest.
     *
     * @param responses the domain refinement responses from neighbors
     */
    private void applyRefinementResponses(List<RefinementResponse<Key, ID, Content>> responses) {
        if (ghostLayer == null) {
            log.debug("No ghost layer context, skipping response processing");
            return;
        }

        int appliedCount = 0;

        for (var response : responses) {
            // Skip empty/sentinel responses (unreachable peers) for symmetry with the convergence-vote
            // filter. They carry no ghost elements so this is a no-op, but keeps the two paths aligned.
            if (response.isEmpty()) {
                continue;
            }
            // Apply each already-deserialized domain ghost element with per-element guard
            for (var ghost : response.ghostElements()) {
                try {
                    ghostLayer.addGhostElement(ghost);
                    appliedCount++;
                    log.trace("Applied ghost element from response");
                } catch (Exception e) {
                    log.warn("Failed to apply ghost element from response: {}", e.getMessage());
                }
            }
        }

        log.debug("Applied {} ghost elements from {} responses", appliedCount, responses.size());
    }

    /**
     * Result of a single refinement round.
     */
    private record RefinementRoundResult(int refinementsApplied, boolean locallyBalanced, long roundTimeMillis) {}
}
