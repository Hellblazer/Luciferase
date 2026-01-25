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

package com.hellblazer.luciferase.lucien.balancing.grpc;

import com.hellblazer.luciferase.lucien.balancing.proto.*;
import com.hellblazer.luciferase.lucien.forest.ghost.proto.GhostElement;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Map;

/**
 * Implementation of the BalanceCoordinator gRPC service using virtual threads.
 *
 * This service handles distributed tree balancing coordination between processes
 * in a spatial index system. It uses virtual threads for scalable concurrent
 * processing and synchronous call patterns for simplified logic.
 *
 * @author Hal Hildebrand
 */
public class BalanceCoordinatorServer extends BalanceCoordinatorGrpc.BalanceCoordinatorImplBase {

    private static final Logger log = LoggerFactory.getLogger(BalanceCoordinatorServer.class);

    // Balance provider for accessing balance state
    private final BalanceProvider balanceProvider;

    // Virtual thread executor for concurrent operations
    private final ExecutorService virtualExecutor;

    // Statistics tracking
    private final AtomicLong requestCount;
    private final AtomicLong coordinationCount;
    private final AtomicLong streamUpdateCount;

    // Active streaming sessions
    private final Map<String, StreamSession> activeStreams;

    /**
     * Creates a new balance coordinator service implementation.
     *
     * @param balanceProvider provider for accessing balance state
     */
    public BalanceCoordinatorServer(BalanceProvider balanceProvider) {
        this.balanceProvider = balanceProvider;
        this.virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.requestCount = new AtomicLong(0);
        this.coordinationCount = new AtomicLong(0);
        this.streamUpdateCount = new AtomicLong(0);
        this.activeStreams = new ConcurrentHashMap<>();

        log.info("BalanceCoordinatorServer initialized for rank {}", balanceProvider.getCurrentRank());
    }

    /**
     * Handles refinement requests from remote processes.
     *
     * @param request the refinement request
     * @param responseObserver observer for sending the response
     */
    @Override
    public void requestRefinement(RefinementRequest request, StreamObserver<RefinementResponse> responseObserver) {
        requestCount.incrementAndGet();

        // Use virtual thread for processing to avoid blocking the gRPC thread pool
        virtualExecutor.submit(() -> {
            try {
                log.debug("Processing refinement request from rank {} for tree {} at level {}",
                         request.getRequesterRank(), request.getRequesterTreeId(), request.getTreeLevel());

                // Record the request
                balanceProvider.recordRefinementRequest(request);

                // Get ghost elements for refinement
                var ghostElements = balanceProvider.getGhostElementsForRefinement(request);

                // Build response
                var responseBuilder = RefinementResponse.newBuilder()
                    .setResponderRank(balanceProvider.getCurrentRank())
                    .setResponderTreeId(request.getRequesterTreeId())
                    .setRoundNumber(request.getRoundNumber())
                    .setTimestamp(System.currentTimeMillis());

                // Add ghost elements
                for (var element : ghostElements) {
                    responseBuilder.addGhostElements(element);
                }

                // Determine if further refinement is needed
                var needsFurtherRefinement = !ghostElements.isEmpty() &&
                    ghostElements.stream().anyMatch(GhostElement::getNeedsRefinement);
                responseBuilder.setNeedsFurtherRefinement(needsFurtherRefinement);

                var response = responseBuilder.build();
                responseObserver.onNext(response);
                responseObserver.onCompleted();

                log.debug("Sent {} ghost elements to rank {} for refinement",
                         ghostElements.size(), request.getRequesterRank());

                // Record applied refinements
                if (!ghostElements.isEmpty()) {
                    balanceProvider.recordRefinementApplied(request.getRequesterRank(), ghostElements.size());
                }

            } catch (Exception e) {
                log.error("Error processing refinement request from rank {}: {}",
                         request.getRequesterRank(), e.getMessage(), e);
                responseObserver.onError(e);
            }
        });
    }

    /**
     * Handles balance coordination requests.
     *
     * @param request the coordination request
     * @param responseObserver observer for sending the response
     */
    @Override
    public void coordinateBalance(BalanceCoordinationRequest request,
                                  StreamObserver<BalanceCoordinationResponse> responseObserver) {
        coordinationCount.incrementAndGet();

        virtualExecutor.submit(() -> {
            try {
                log.debug("Processing balance coordination from rank {} for tree {}",
                         request.getInitiatorRank(), request.getInitiatorTreeId());

                var responseBuilder = BalanceCoordinationResponse.newBuilder();

                // Check if coordination is accepted
                var accepted = balanceProvider.acceptCoordination(request);
                responseBuilder.setCoordinationAccepted(accepted);

                if (accepted) {
                    // Assign round for this participant
                    var assignedRound = balanceProvider.assignRound(request);
                    responseBuilder.setAssignedRound(assignedRound);
                    responseBuilder.setErrorMessage("");

                    log.debug("Accepted balance coordination from rank {}, assigned round {}",
                             request.getInitiatorRank(), assignedRound);
                } else {
                    responseBuilder.setAssignedRound(-1);
                    responseBuilder.setErrorMessage("Coordination rejected by rank " +
                                                   balanceProvider.getCurrentRank());

                    log.warn("Rejected balance coordination from rank {}",
                            request.getInitiatorRank());
                }

                responseObserver.onNext(responseBuilder.build());
                responseObserver.onCompleted();

            } catch (Exception e) {
                log.error("Error processing coordination request from rank {}: {}",
                         request.getInitiatorRank(), e.getMessage(), e);
                responseObserver.onError(e);
            }
        });
    }

    /**
     * Handles streaming balance updates for real-time monitoring.
     *
     * @param responseObserver observer for sending statistics
     * @return observer for receiving statistics
     */
    @Override
    public StreamObserver<BalanceStatistics> streamBalanceUpdates(
            StreamObserver<BalanceStatistics> responseObserver) {

        var sessionId = "stream-" + System.currentTimeMillis() + "-" + streamUpdateCount.incrementAndGet();
        var session = new StreamSession(sessionId, responseObserver);
        activeStreams.put(sessionId, session);

        log.debug("Started balance update streaming session: {}", sessionId);

        return new StreamObserver<BalanceStatistics>() {
            @Override
            public void onNext(BalanceStatistics stats) {
                virtualExecutor.submit(() -> processStreamUpdate(session, stats));
            }

            @Override
            public void onError(Throwable t) {
                log.error("Streaming error in session {}: {}", sessionId, t.getMessage(), t);
                activeStreams.remove(sessionId);
            }

            @Override
            public void onCompleted() {
                log.debug("Streaming session completed: {}", sessionId);
                activeStreams.remove(sessionId);
                responseObserver.onCompleted();
            }
        };
    }

    /**
     * Exchanges 2:1 balance violations during butterfly pattern rounds.
     * Implements bidirectional exchange: receives violations from requester,
     * processes them, and returns this partition's current violation set.
     *
     * @param request the violation batch from remote partition
     * @param responseObserver observer for sending the response batch
     */
    @Override
    public void exchangeViolations(ViolationBatch request, StreamObserver<ViolationBatch> responseObserver) {
        virtualExecutor.submit(() -> {
            try {
                log.debug("Processing violation exchange from rank {} in round {}: {} violations",
                         request.getRequesterRank(), request.getRoundNumber(),
                         request.getViolationsCount());

                // Process received violations and get response batch
                var responseBatch = balanceProvider.processViolations(request);

                responseObserver.onNext(responseBatch);
                responseObserver.onCompleted();

                log.debug("Exchanged {} violations with rank {} in round {}: sent {} back",
                         request.getViolationsCount(), request.getRequesterRank(),
                         request.getRoundNumber(), responseBatch.getViolationsCount());

            } catch (Exception e) {
                log.error("Error processing violation exchange from rank {}: {}",
                         request.getRequesterRank(), e.getMessage(), e);
                responseObserver.onError(e);
            }
        });
    }

    /**
     * Provides balance statistics for monitoring and debugging.
     *
     * @param request the statistics request
     * @param responseObserver observer for sending the response
     */
    @Override
    public void getBalanceStatistics(BalanceCoordinationRequest request,
                                    StreamObserver<BalanceStatistics> responseObserver) {
        virtualExecutor.submit(() -> {
            try {
                var stats = balanceProvider.getStatistics();
                responseObserver.onNext(stats);
                responseObserver.onCompleted();

            } catch (Exception e) {
                log.error("Error getting balance stats for rank {}: {}",
                         request.getInitiatorRank(), e.getMessage(), e);
                responseObserver.onError(e);
            }
        });
    }

    /**
     * Processes a streaming balance update.
     */
    private void processStreamUpdate(StreamSession session, BalanceStatistics stats) {
        try {
            log.debug("Received balance update in session {}: {} rounds completed",
                     session.sessionId, stats.getTotalRoundsCompleted());

            // Echo back current local statistics
            var localStats = balanceProvider.getStatistics();
            session.responseObserver.onNext(localStats);

        } catch (Exception e) {
            log.error("Error processing stream update in session {}: {}",
                     session.sessionId, e.getMessage(), e);
        }
    }

    /**
     * Gets service statistics for monitoring.
     *
     * @return map of statistics
     */
    public Map<String, Object> getServiceStats() {
        return Map.of(
            "rank", balanceProvider.getCurrentRank(),
            "requestCount", requestCount.get(),
            "coordinationCount", coordinationCount.get(),
            "streamUpdateCount", streamUpdateCount.get(),
            "activeStreams", activeStreams.size()
        );
    }

    /**
     * Shuts down the service and cleans up resources.
     */
    public void shutdown() {
        log.info("Shutting down BalanceCoordinatorServer");

        // Close all active streams
        activeStreams.values().forEach(session -> {
            try {
                session.responseObserver.onCompleted();
            } catch (Exception e) {
                log.warn("Error closing stream session {}: {}", session.sessionId, e.getMessage());
            }
        });
        activeStreams.clear();

        // Shutdown virtual thread executor
        virtualExecutor.shutdown();
    }

    /**
     * Represents an active streaming session.
     */
    private static class StreamSession {
        final String sessionId;
        final StreamObserver<BalanceStatistics> responseObserver;

        StreamSession(String sessionId, StreamObserver<BalanceStatistics> responseObserver) {
            this.sessionId = sessionId;
            this.responseObserver = responseObserver;
        }
    }

    /**
     * Interface for providing access to balance state and related operations.
     */
    public interface BalanceProvider {

        /**
         * Gets the current process rank.
         *
         * @return the process rank
         */
        int getCurrentRank();

        /**
         * Gets ghost elements for a refinement request.
         *
         * @param request the refinement request
         * @return list of ghost elements to send
         */
        List<GhostElement> getGhostElementsForRefinement(RefinementRequest request);

        /**
         * Determines if a coordination request should be accepted.
         *
         * @param request the coordination request
         * @return true if accepted, false otherwise
         */
        boolean acceptCoordination(BalanceCoordinationRequest request);

        /**
         * Assigns a round number for a coordination request.
         *
         * @param request the coordination request
         * @return the assigned round number
         */
        int assignRound(BalanceCoordinationRequest request);

        /**
         * Gets current balance statistics.
         *
         * @return balance statistics
         */
        BalanceStatistics getStatistics();

        /**
         * Records a refinement request.
         *
         * @param request the refinement request
         */
        void recordRefinementRequest(RefinementRequest request);

        /**
         * Records applied refinements.
         *
         * @param rank the rank that applied refinements
         * @param count the number of refinements applied
         */
        void recordRefinementApplied(int rank, int count);

        /**
         * Processes received violation batch during butterfly pattern exchange.
         * Implements bidirectional exchange by returning this partition's current violations.
         *
         * @param batch the violation batch from remote partition
         * @return violation batch containing this partition's current violations
         */
        ViolationBatch processViolations(ViolationBatch batch);
    }
}
