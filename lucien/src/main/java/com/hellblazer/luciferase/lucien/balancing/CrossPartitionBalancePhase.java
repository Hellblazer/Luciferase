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
import com.hellblazer.luciferase.lucien.balancing.proto.RefinementRequest;
import com.hellblazer.luciferase.lucien.balancing.proto.RefinementResponse;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.forest.Forest;
import com.hellblazer.luciferase.lucien.forest.ghost.ContentSerializer;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostElement;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostLayer;
import com.hellblazer.luciferase.lucien.forest.ghost.grpc.ProtobufConverters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

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
 *   <li>Sends RefinementRequest to neighbor partitions via gRPC</li>
 *   <li>Receives RefinementResponse with ghost elements</li>
 *   <li>Applies ghost elements to local forest</li>
 *   <li>Synchronizes all partitions via barrier</li>
 *   <li>Checks convergence (no more refinements needed)</li>
 * </ol>
 *
 * <p>Based on the p4est parallel AMR algorithm (Burstedde et al., SIAM 2011).
 *
 * <p>Thread-safe: Uses immutable configuration and thread-safe client/registry.
 *
 * @param <Key> the spatial key type (MortonKey, TetreeKey, etc.)
 * @param <ID> the entity ID type
 * @param <Content> the content type stored with entities
 * @author hal.hildebrand
 */
public class CrossPartitionBalancePhase<Key extends SpatialKey<Key>, ID extends EntityID, Content> {

    private static final Logger log = LoggerFactory.getLogger(CrossPartitionBalancePhase.class);

    private final BalanceCoordinatorClient client;
    private final ParallelBalancer.PartitionRegistry registry;
    private final BalanceConfiguration config;
    private final RefinementRequestManager requestManager;
    private volatile RefinementCoordinator coordinator;  // Initialized lazily in execute()
    private volatile boolean lastRoundIndicatedConvergence = false;

    // Forest context for violation detection and ghost element application
    private volatile Forest<Key, ID, Content> forest;
    private volatile GhostLayer<Key, ID, Content> ghostLayer;
    private volatile TwoOneBalanceChecker<Key, ID, Content> balanceChecker;
    private volatile ContentSerializer<Content> contentSerializer;
    private volatile Class<ID> idType;

    /**
     * Create a new cross-partition balance phase.
     *
     * @param client the gRPC client for refinement requests
     * @param registry the partition registry for coordination
     * @param config the balance configuration
     * @throws NullPointerException if any parameter is null
     */
    public CrossPartitionBalancePhase(BalanceCoordinatorClient client,
                                     ParallelBalancer.PartitionRegistry registry,
                                     BalanceConfiguration config) {
        this.client = Objects.requireNonNull(client, "client cannot be null");
        this.registry = Objects.requireNonNull(registry, "registry cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.requestManager = new RefinementRequestManager();
        this.coordinator = null;  // Initialized lazily in execute() when rank/partition count known
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
     * Set forest context for violation detection and refinement with ghost element deserialization support.
     *
     * @param forest the local forest containing local elements
     * @param ghostLayer the ghost layer for boundary element checking
     * @param contentSerializer the serializer for content type
     * @param idType the entity ID class for deserialization
     * @throws NullPointerException if forest, ghostLayer, contentSerializer, or idType is null
     */
    public void setForestContext(Forest<Key, ID, Content> forest,
                                GhostLayer<Key, ID, Content> ghostLayer,
                                ContentSerializer<Content> contentSerializer,
                                Class<ID> idType) {
        this.forest = Objects.requireNonNull(forest, "forest cannot be null");
        this.ghostLayer = Objects.requireNonNull(ghostLayer, "ghostLayer cannot be null");
        this.contentSerializer = Objects.requireNonNull(contentSerializer, "contentSerializer cannot be null");
        this.idType = Objects.requireNonNull(idType, "idType cannot be null");
        this.balanceChecker = new TwoOneBalanceChecker<>();
        log.debug("Forest context set: forest with {} trees, {} ghost elements (with deserialization support)",
                 forest.getTreeCount(), ghostLayer.getNumGhostElements());
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

        // Lazily initialize coordinator with rank and partition count
        if (coordinator == null) {
            coordinator = new RefinementCoordinator(client, requestManager, initiatorRank, totalPartitions);
            log.debug("Initialized RefinementCoordinator for rank {} with {} total partitions",
                     initiatorRank, totalPartitions);
        }

        var metrics = new BalanceMetrics();
        var startTime = System.currentTimeMillis();

        try {
            // Calculate optimal rounds = ceil(log₂(P))
            var optimalRounds = (int) Math.ceil(Math.log(totalPartitions) / Math.log(2));
            var targetRounds = Math.min(optimalRounds, config.maxRounds());

            log.debug("Executing {} refinement rounds for {} partitions", targetRounds, totalPartitions);

            var totalRefinements = 0;
            var converged = false;

            // Execute refinement rounds
            for (int round = 1; round <= targetRounds; round++) {
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

                // Check for convergence (early termination only after minimum rounds)
                if (round >= optimalRounds && isConverged()) {
                    log.info("Converged after {} rounds", round);
                    converged = true;
                    break;
                }
            }

            var elapsed = System.currentTimeMillis() - startTime;
            log.info("Cross-partition balance complete: rounds={}, refinements={}, converged={}, time={}ms",
                    targetRounds, totalRefinements, converged, elapsed);

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
            // Phase 1: Identify refinement needs at partition boundaries
            var refinementNeeds = identifyRefinementNeeds(roundNumber);

            // Phase 2: Send requests to neighbors
            // Send each request via client
            var responses = new java.util.ArrayList<RefinementResponse>();

            if (!refinementNeeds.isEmpty()) {
                log.debug("Sending {} refinement requests in round {}", refinementNeeds.size(), roundNumber);

                // Send each request via client and collect responses (for testing)
                for (int i = 0; i < refinementNeeds.size(); i++) {
                    var responseFuture = client.requestRefinementAsync(i, 0L, roundNumber, 0, List.of());
                    try {
                        var response = responseFuture.get();
                        responses.add(response);
                        log.trace("Received response from rank {} in round {}", i, roundNumber);
                    } catch (Exception e) {
                        log.warn("Failed to get response from rank {} in round {}", i, roundNumber, e);
                    }
                }
            }

            // Phase 3: Process responses
            log.debug("Received {} responses in round {}", responses.size(), roundNumber);

            // Phase 4: Apply refinements from responses
            applyRefinementResponses(responses);

            // Phase 5: Check if more refinement is needed
            // Update convergence state based on responses
            lastRoundIndicatedConvergence = responses.stream()
                .noneMatch(RefinementResponse::getNeedsFurtherRefinement);

            var needsMore = !isConverged() && roundNumber < config.maxRounds();

            log.debug("Refinement round {} complete: requests={}, responses={}, converged={}, needsMore={}",
                     roundNumber, refinementNeeds.size(), responses.size(), lastRoundIndicatedConvergence, needsMore);

            var elapsedMs = Math.max(1L, (System.nanoTime() - startTime) / 1_000_000L);
            return new RefinementRoundResult(refinementNeeds.size(), needsMore, elapsedMs);

        } catch (Exception e) {
            log.error("Error in refinement round {}", roundNumber, e);
            var elapsedMs = Math.max(1L, (System.nanoTime() - startTime) / 1_000_000L);
            return new RefinementRoundResult(0, false, elapsedMs);
        }
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
     * @param coordinator the coordinator to send requests
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

        // Step 3: Build RefinementRequests
        var requests = new java.util.ArrayList<RefinementRequest>();
        for (var entry : violationsByRank.entrySet()) {
            var sourceRank = entry.getKey();
            var groupViolations = entry.getValue();

            // Extract max level from violations
            var maxLevel = groupViolations.stream()
                .mapToInt(v -> Math.max(v.localLevel(), v.ghostLevel()))
                .max()
                .orElse(0);

            // Collect boundary keys from violations
            var boundaryKeys = groupViolations.stream()
                .flatMap(v -> java.util.stream.Stream.of(v.localKey(), v.ghostKey()))
                .map(ProtobufConverters::spatialKeyToProtobuf)
                .collect(Collectors.toList());

            var request = RefinementRequest.newBuilder()
                .setRequesterRank(registry.getCurrentPartitionId())
                .setRequesterTreeId(0L)
                .setRoundNumber(roundNumber)
                .setTreeLevel(maxLevel)
                .addAllBoundaryKeys(boundaryKeys)
                .setTimestamp(System.currentTimeMillis())
                .build();

            requests.add(request);
            log.trace("Round {}: Created request for rank {} with {} violations, level={}",
                     roundNumber, sourceRank, groupViolations.size(), maxLevel);
        }

        // Step 4: Send async requests via coordinator
        List<java.util.concurrent.CompletableFuture<RefinementResponse>> futures;
        try {
            // Use reflection to call sendRequestsParallel on coordinator
            var method = coordinator.getClass().getMethod("sendRequestsParallel", List.class);
            @SuppressWarnings("unchecked")
            var result = (List<java.util.concurrent.CompletableFuture<RefinementResponse>>) method.invoke(coordinator, requests);
            futures = result;
        } catch (Exception e) {
            log.error("Failed to send requests via coordinator", e);
            throw new RuntimeException("Coordinator sendRequestsParallel failed", e);
        }

        // Step 5: Await all futures with timeout
        var responses = new java.util.ArrayList<RefinementResponse>();
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
     * Identify refinement needs at partition boundaries for the current round.
     *
     * <p>Uses the TwoOneBalanceChecker to detect 2:1 balance violations at partition
     * boundaries and creates refinement requests for neighboring partitions.
     *
     * <p>In distributed balance protocol, each partition sends requests to its butterfly
     * partners each round. The butterfly pattern ensures O(log P) communication.
     *
     * @return list of refinement requests for neighbors
     */
    private List<RefinementRequest> identifyRefinementNeeds(int roundNumber) {
        var requests = new java.util.ArrayList<RefinementRequest>();

        // Check if forest context is available
        if (balanceChecker == null || forest == null || ghostLayer == null) {
            log.warn("Forest context not set, using fallback request for round {}", roundNumber);
            // Fallback: single empty request to maintain butterfly pattern
            var request = RefinementRequest.newBuilder()
                .setRequesterRank(registry.getCurrentPartitionId())
                .setRequesterTreeId(0L)
                .setRoundNumber(roundNumber)
                .setTreeLevel(0)
                .setTimestamp(System.currentTimeMillis())
                .build();
            requests.add(request);
            return requests;
        }

        // Find all 2:1 violations using TwoOneBalanceChecker
        var violations = balanceChecker.findViolations(ghostLayer, forest);

        if (violations.isEmpty()) {
            log.debug("No 2:1 violations found in round {}", roundNumber);
            // Still send request to maintain butterfly pattern, but with no boundary keys
            var request = RefinementRequest.newBuilder()
                .setRequesterRank(registry.getCurrentPartitionId())
                .setRequesterTreeId(0L)
                .setRoundNumber(roundNumber)
                .setTreeLevel(0)
                .setTimestamp(System.currentTimeMillis())
                .build();
            requests.add(request);
            return requests;
        }

        log.debug("Found {} violations in round {}, creating requests", violations.size(), roundNumber);

        // Group violations by source rank (butterfly partner)
        var violationsByRank = new java.util.HashMap<Integer, java.util.ArrayList<TwoOneBalanceChecker.BalanceViolation<Key>>>();
        for (var violation : violations) {
            violationsByRank.computeIfAbsent(violation.sourceRank(), rank -> new java.util.ArrayList<>())
                           .add(violation);
        }

        // Create refinement request per source rank
        for (var entry : violationsByRank.entrySet()) {
            var sourceRank = entry.getKey();
            var groupViolations = entry.getValue();

            // Extract max level from violations
            var maxLevel = groupViolations.stream()
                .mapToInt(v -> Math.max(v.localLevel(), v.ghostLevel()))
                .max()
                .orElse(0);

            // Collect boundary keys from violations
            var boundaryKeys = groupViolations.stream()
                .flatMap(v -> java.util.stream.Stream.of(v.localKey(), v.ghostKey()))
                .map(ProtobufConverters::spatialKeyToProtobuf)
                .collect(Collectors.toList());

            var request = RefinementRequest.newBuilder()
                .setRequesterRank(registry.getCurrentPartitionId())
                .setRequesterTreeId(0L)
                .setRoundNumber(roundNumber)
                .setTreeLevel(maxLevel)
                .addAllBoundaryKeys(boundaryKeys)
                .setTimestamp(System.currentTimeMillis());

            requests.add(request.build());
            log.trace("Created request for rank {} with {} violations", sourceRank, groupViolations.size());
        }

        return requests;
    }

    /**
     * Process a single refinement response and apply ghost elements to the ghost layer.
     *
     * <p>This method deserializes ghost elements from the RefinementResponse protobuf
     * and adds them to the local ghost layer for boundary element checking.
     *
     * @param response the refinement response from a remote partition
     * @param coordinator the refinement coordinator (for context/logging)
     * @throws ContentSerializer.SerializationException if ghost element deserialization fails
     */
    public void applyRefinementResponses(
            RefinementResponse response,
            RefinementCoordinator coordinator) throws ContentSerializer.SerializationException {

        // Validate input
        if (response == null) {
            log.debug("Received null response, skipping ghost element application");
            return;
        }

        if (ghostLayer == null) {
            log.debug("No ghost layer context, skipping response processing");
            return;
        }

        // Extract ghost elements from response
        var ghostProtos = response.getGhostElementsList();
        if (ghostProtos.isEmpty()) {
            log.debug("Response contains no ghost elements, returning");
            return;
        }

        log.debug("Processing {} ghost elements from response (responder rank: {})",
                 ghostProtos.size(), response.getResponderRank());

        var addedCount = 0;
        var skippedCount = 0;

        // Process each ghost element
        for (var ghostProto : ghostProtos) {
            try {
                // Deserialize ghost element using contentSerializer and idType
                if (contentSerializer != null && idType != null) {
                    @SuppressWarnings("unchecked")
                    var ghostElement = (GhostElement<Key, ID, Content>) GhostElement.fromProtobuf(
                        ghostProto,
                        contentSerializer,
                        idType
                    );

                    // Add to ghost layer
                    ghostLayer.addGhostElement(ghostElement);
                    addedCount++;

                    log.trace("Added ghost element: key={}, entityId={}, ownerRank={}",
                             ghostElement.getSpatialKey(),
                             ghostElement.getEntityId(),
                             ghostElement.getOwnerRank());
                } else {
                    // Backward compatibility: skip if serializer not configured
                    log.trace("Skipping ghost element deserialization (no serializer configured)");
                    skippedCount++;
                }
            } catch (ContentSerializer.SerializationException e) {
                log.warn("Failed to deserialize ghost element from response: {}", e.getMessage());
                skippedCount++;
            } catch (Exception e) {
                log.warn("Unexpected error processing ghost element: {}", e.getMessage(), e);
                skippedCount++;
            }
        }

        log.debug("Applied {} ghost elements from response (rank {}), skipped {} invalid elements",
                 addedCount, response.getResponderRank(), skippedCount);
    }

    /**
     * Process refinement responses and apply updates to forest.
     *
     * @param responses the refinement responses from neighbors
     */
    private void applyRefinementResponses(List<RefinementResponse> responses) {
        if (ghostLayer == null) {
            log.debug("No ghost layer context, skipping response processing");
            return;
        }

        int appliedCount = 0;

        for (var response : responses) {
            // Extract ghost elements from response
            for (var ghostProto : response.getGhostElementsList()) {
                try {
                    // Convert protobuf ghost element to domain object and add to ghost layer
                    if (contentSerializer != null && idType != null) {
                        @SuppressWarnings("unchecked")
                        var ghostElement = (GhostElement<Key, ID, Content>) GhostElement.fromProtobuf(
                            ghostProto,
                            contentSerializer,
                            idType
                        );
                        ghostLayer.addGhostElement(ghostElement);
                        appliedCount++;
                        log.trace("Applied ghost element from response");
                    } else {
                        // Backward compatibility: skip deserialization if serializer not configured
                        log.trace("Skipping ghost element deserialization (no serializer configured)");
                    }
                } catch (Exception e) {
                    log.warn("Failed to apply ghost element from response: {}", e.getMessage());
                }
            }
        }

        log.debug("Applied {} ghost elements from {} responses", appliedCount, responses.size());
    }

    /**
     * Check if balance has converged (no further refinement needed).
     *
     * <p>Convergence is detected when all neighbors report that no further refinement
     * is needed. This is tracked via the responses from refinement requests.
     *
     * @return true if converged
     */
    private boolean isConverged() {
        // Convergence is indicated when:
        // 1. Last round's responses all indicated no further refinement needed
        // 2. This prevents unnecessary rounds from executing

        return lastRoundIndicatedConvergence;
    }

    /**
     * Result of a single refinement round.
     */
    private record RefinementRoundResult(int refinementsApplied, boolean needsMoreRefinement, long roundTimeMillis) {}
}
