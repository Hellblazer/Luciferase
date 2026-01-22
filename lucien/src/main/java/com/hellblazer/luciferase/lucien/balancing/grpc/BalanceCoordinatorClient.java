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
import com.hellblazer.luciferase.lucien.forest.ghost.proto.SpatialKey;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Client for communicating with remote BalanceCoordinator services using virtual threads.
 *
 * This client provides synchronous and asynchronous methods for requesting refinements,
 * coordinating balance operations, and monitoring balance statistics with remote processes.
 * It uses connection pooling, request batching, and virtual threads for scalability.
 *
 * @author Hal Hildebrand
 */
public class BalanceCoordinatorClient {

    private static final Logger log = LoggerFactory.getLogger(BalanceCoordinatorClient.class);
    private static final int DEFAULT_BATCH_SIZE = 10;
    private static final long DEFAULT_BATCH_TIMEOUT_MILLIS = 100;

    // Connection management
    private final Map<Integer, ManagedChannel> channels;
    private final Map<Integer, BalanceCoordinatorGrpc.BalanceCoordinatorBlockingStub> blockingStubs;
    private final Map<Integer, BalanceCoordinatorGrpc.BalanceCoordinatorStub> asyncStubs;

    // Configuration
    private final int currentRank;
    private final ServiceDiscovery serviceDiscovery;
    private final int batchSize;
    private final long batchTimeoutMillis;

    // Virtual thread executor for concurrent operations
    private final ExecutorService virtualExecutor;

    // Statistics
    private final AtomicLong requestCount;
    private final AtomicLong coordinationCount;
    private final AtomicLong streamCount;
    private final AtomicLong failureCount;
    private final AtomicLong batchedRequestCount;

    // Active streaming connections
    private final Map<String, StreamingConnection> activeStreams;

    // Request batching
    private final Map<Integer, BatchQueue> batchQueues;

    /**
     * Creates a new balance coordinator client.
     *
     * @param currentRank the rank of this process
     * @param serviceDiscovery service discovery mechanism
     */
    public BalanceCoordinatorClient(int currentRank, ServiceDiscovery serviceDiscovery) {
        this(currentRank, serviceDiscovery, DEFAULT_BATCH_SIZE, DEFAULT_BATCH_TIMEOUT_MILLIS);
    }

    /**
     * Creates a new balance coordinator client with custom batching parameters.
     *
     * @param currentRank the rank of this process
     * @param serviceDiscovery service discovery mechanism
     * @param batchSize maximum batch size
     * @param batchTimeoutMillis maximum time to wait before sending partial batch
     */
    public BalanceCoordinatorClient(int currentRank, ServiceDiscovery serviceDiscovery,
                                   int batchSize, long batchTimeoutMillis) {
        this.currentRank = currentRank;
        this.serviceDiscovery = serviceDiscovery;
        this.batchSize = batchSize;
        this.batchTimeoutMillis = batchTimeoutMillis;
        this.channels = new ConcurrentHashMap<>();
        this.blockingStubs = new ConcurrentHashMap<>();
        this.asyncStubs = new ConcurrentHashMap<>();
        this.virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.requestCount = new AtomicLong(0);
        this.coordinationCount = new AtomicLong(0);
        this.streamCount = new AtomicLong(0);
        this.failureCount = new AtomicLong(0);
        this.batchedRequestCount = new AtomicLong(0);
        this.activeStreams = new ConcurrentHashMap<>();
        this.batchQueues = new ConcurrentHashMap<>();

        log.info("BalanceCoordinatorClient initialized for rank {} with batch size {}, timeout {}ms",
                currentRank, batchSize, batchTimeoutMillis);
    }

    /**
     * Requests refinement from a remote process synchronously.
     *
     * @param targetRank the rank of the target process
     * @param treeId the tree ID to request refinement for
     * @param roundNumber the balance round number
     * @param treeLevel the tree level requiring refinement
     * @param boundaryKeys specific boundary keys to refine (optional)
     * @return the refinement response, or null if request fails
     */
    public RefinementResponse requestRefinement(int targetRank, long treeId, int roundNumber,
                                               int treeLevel, List<SpatialKey> boundaryKeys) {
        requestCount.incrementAndGet();

        try {
            var stub = getBlockingStub(targetRank);
            if (stub == null) {
                log.warn("No connection available to rank {}", targetRank);
                failureCount.incrementAndGet();
                return null;
            }

            var requestBuilder = RefinementRequest.newBuilder()
                .setRequesterRank(currentRank)
                .setRequesterTreeId(treeId)
                .setRoundNumber(roundNumber)
                .setTreeLevel(treeLevel)
                .setTimestamp(System.currentTimeMillis());

            // Add boundary keys if specified
            if (boundaryKeys != null && !boundaryKeys.isEmpty()) {
                requestBuilder.addAllBoundaryKeys(boundaryKeys);
            }

            var request = requestBuilder.build();
            var response = stub.requestRefinement(request);

            log.debug("Received {} ghost elements from rank {} for refinement",
                     response.getGhostElementsCount(), targetRank);

            return response;

        } catch (StatusRuntimeException e) {
            log.error("Failed to request refinement from rank {}: {}", targetRank, e.getStatus());
            failureCount.incrementAndGet();
            return null;
        }
    }

    /**
     * Requests refinement asynchronously using virtual threads.
     *
     * @param targetRank the rank of the target process
     * @param treeId the tree ID to request refinement for
     * @param roundNumber the balance round number
     * @param treeLevel the tree level requiring refinement
     * @param boundaryKeys specific boundary keys to refine (optional)
     * @return CompletableFuture with the refinement response
     */
    public CompletableFuture<RefinementResponse> requestRefinementAsync(
            int targetRank, long treeId, int roundNumber, int treeLevel, List<SpatialKey> boundaryKeys) {
        return CompletableFuture.supplyAsync(() ->
            requestRefinement(targetRank, treeId, roundNumber, treeLevel, boundaryKeys),
            virtualExecutor);
    }

    /**
     * Batches multiple refinement requests and sends them together.
     * This method adds the request to a batch queue and returns a future.
     *
     * @param targetRank the rank of the target process
     * @param treeId the tree ID to request refinement for
     * @param roundNumber the balance round number
     * @param treeLevel the tree level requiring refinement
     * @param boundaryKeys specific boundary keys to refine (optional)
     * @return CompletableFuture with the refinement response
     */
    public CompletableFuture<RefinementResponse> requestRefinementBatched(
            int targetRank, long treeId, int roundNumber, int treeLevel, List<SpatialKey> boundaryKeys) {

        batchedRequestCount.incrementAndGet();

        var queue = batchQueues.computeIfAbsent(targetRank,
            rank -> new BatchQueue(rank, batchSize, batchTimeoutMillis));

        var future = new CompletableFuture<RefinementResponse>();
        var request = RefinementRequest.newBuilder()
            .setRequesterRank(currentRank)
            .setRequesterTreeId(treeId)
            .setRoundNumber(roundNumber)
            .setTreeLevel(treeLevel)
            .setTimestamp(System.currentTimeMillis());

        if (boundaryKeys != null && !boundaryKeys.isEmpty()) {
            request.addAllBoundaryKeys(boundaryKeys);
        }

        queue.add(new BatchedRequest(request.build(), future));

        return future;
    }

    /**
     * Initiates balance coordination with a remote process.
     *
     * @param targetRank the rank of the target process
     * @param treeId the tree ID to coordinate balance for
     * @param totalPartitions total number of partitions
     * @param maxRounds maximum number of balance rounds
     * @param refinementThreshold imbalance tolerance threshold
     * @return the coordination response, or null if request fails
     */
    public BalanceCoordinationResponse coordinateBalance(int targetRank, long treeId,
                                                        int totalPartitions, int maxRounds,
                                                        float refinementThreshold) {
        coordinationCount.incrementAndGet();

        try {
            var stub = getBlockingStub(targetRank);
            if (stub == null) {
                log.warn("No connection available to rank {}", targetRank);
                failureCount.incrementAndGet();
                return null;
            }

            var request = BalanceCoordinationRequest.newBuilder()
                .setInitiatorRank(currentRank)
                .setInitiatorTreeId(treeId)
                .setTotalPartitions(totalPartitions)
                .setMaxRounds(maxRounds)
                .setRefinementThreshold(refinementThreshold)
                .build();

            var response = stub.coordinateBalance(request);

            log.debug("Coordination with rank {} {}: assigned round {}",
                     targetRank,
                     response.getCoordinationAccepted() ? "accepted" : "rejected",
                     response.getAssignedRound());

            return response;

        } catch (StatusRuntimeException e) {
            log.error("Failed to coordinate balance with rank {}: {}", targetRank, e.getStatus());
            failureCount.incrementAndGet();
            return null;
        }
    }

    /**
     * Gets balance statistics from a remote process.
     *
     * @param targetRank the rank of the target process
     * @return the balance statistics, or null if request fails
     */
    public BalanceStatistics getRemoteStatistics(int targetRank) {
        try {
            var stub = getBlockingStub(targetRank);
            if (stub == null) {
                return null;
            }

            var request = BalanceCoordinationRequest.newBuilder()
                .setInitiatorRank(currentRank)
                .setInitiatorTreeId(0L)
                .setTotalPartitions(1)
                .setMaxRounds(1)
                .setRefinementThreshold(0.2f)
                .build();

            return stub.getBalanceStatistics(request);

        } catch (StatusRuntimeException e) {
            log.error("Failed to get stats from rank {}: {}", targetRank, e.getStatus());
            return null;
        }
    }

    /**
     * Starts a streaming connection for real-time balance updates.
     *
     * @param targetRank the rank of the target process
     * @param updateHandler handler for processing incoming updates
     * @param errorHandler handler for processing errors
     * @return stream ID for managing the connection
     */
    public String startStreaming(int targetRank,
                                Consumer<BalanceStatistics> updateHandler,
                                Consumer<Throwable> errorHandler) {
        streamCount.incrementAndGet();

        var streamId = "stream-" + targetRank + "-" + System.currentTimeMillis();

        virtualExecutor.submit(() -> {
            try {
                var stub = getAsyncStub(targetRank);
                if (stub == null) {
                    log.warn("No async connection available to rank {}", targetRank);
                    errorHandler.accept(new RuntimeException("No connection to rank " + targetRank));
                    return;
                }

                var responseObserver = new StreamObserver<BalanceStatistics>() {
                    @Override
                    public void onNext(BalanceStatistics stats) {
                        updateHandler.accept(stats);
                    }

                    @Override
                    public void onError(Throwable t) {
                        log.error("Streaming error to rank {}: {}", targetRank, t.getMessage());
                        activeStreams.remove(streamId);
                        errorHandler.accept(t);
                    }

                    @Override
                    public void onCompleted() {
                        log.debug("Streaming to rank {} completed", targetRank);
                        activeStreams.remove(streamId);
                    }
                };

                var requestObserver = stub.streamBalanceUpdates(responseObserver);
                var connection = new StreamingConnection(targetRank, requestObserver, updateHandler);
                activeStreams.put(streamId, connection);

                log.debug("Started streaming connection {} to rank {}", streamId, targetRank);

            } catch (Exception e) {
                log.error("Failed to start streaming to rank {}: {}", targetRank, e.getMessage(), e);
                errorHandler.accept(e);
            }
        });

        return streamId;
    }

    /**
     * Sends a balance statistics update through an active stream.
     *
     * @param streamId the stream identifier
     * @param stats the balance statistics to send
     * @return true if sent successfully, false otherwise
     */
    public boolean sendStreamUpdate(String streamId, BalanceStatistics stats) {
        var connection = activeStreams.get(streamId);
        if (connection == null) {
            log.warn("No active stream found with ID: {}", streamId);
            return false;
        }

        try {
            connection.requestObserver.onNext(stats);
            return true;
        } catch (Exception e) {
            log.error("Failed to send stream update on {}: {}", streamId, e.getMessage());
            return false;
        }
    }

    /**
     * Closes a streaming connection.
     *
     * @param streamId the stream identifier
     */
    public void closeStream(String streamId) {
        var connection = activeStreams.remove(streamId);
        if (connection != null) {
            try {
                connection.requestObserver.onCompleted();
                log.debug("Closed streaming connection: {}", streamId);
            } catch (Exception e) {
                log.warn("Error closing stream {}: {}", streamId, e.getMessage());
            }
        }
    }

    /**
     * Gets or creates a blocking stub for the specified rank.
     */
    private BalanceCoordinatorGrpc.BalanceCoordinatorBlockingStub getBlockingStub(int targetRank) {
        return blockingStubs.computeIfAbsent(targetRank, rank -> {
            var channel = getChannel(rank);
            return channel != null ? BalanceCoordinatorGrpc.newBlockingStub(channel) : null;
        });
    }

    /**
     * Gets or creates an async stub for the specified rank.
     */
    private BalanceCoordinatorGrpc.BalanceCoordinatorStub getAsyncStub(int targetRank) {
        return asyncStubs.computeIfAbsent(targetRank, rank -> {
            var channel = getChannel(rank);
            return channel != null ? BalanceCoordinatorGrpc.newStub(channel) : null;
        });
    }

    /**
     * Gets or creates a channel for the specified rank.
     */
    private ManagedChannel getChannel(int targetRank) {
        return channels.computeIfAbsent(targetRank, rank -> {
            var endpoint = serviceDiscovery.getEndpoint(rank);
            if (endpoint == null) {
                log.warn("No endpoint found for rank {}", rank);
                return null;
            }

            log.debug("Creating channel to rank {} at {}", rank, endpoint);
            return ManagedChannelBuilder.forTarget(endpoint)
                .usePlaintext() // For development - use TLS in production
                .keepAliveTime(30, TimeUnit.SECONDS)
                .keepAliveTimeout(5, TimeUnit.SECONDS)
                .keepAliveWithoutCalls(true)
                .build();
        });
    }

    /**
     * Gets client statistics for monitoring.
     *
     * @return map of statistics
     */
    public Map<String, Object> getClientStats() {
        return Map.of(
            "rank", currentRank,
            "requestCount", requestCount.get(),
            "coordinationCount", coordinationCount.get(),
            "streamCount", streamCount.get(),
            "failureCount", failureCount.get(),
            "batchedRequestCount", batchedRequestCount.get(),
            "activeStreams", activeStreams.size(),
            "activeChannels", channels.size()
        );
    }

    /**
     * Shuts down the client and closes all connections.
     */
    public void shutdown() {
        log.info("Shutting down BalanceCoordinatorClient for rank {}", currentRank);

        // Close all active streams
        activeStreams.keySet().forEach(this::closeStream);

        // Shutdown all batch queues
        batchQueues.values().forEach(BatchQueue::shutdown);
        batchQueues.clear();

        // Shutdown all channels
        channels.values().forEach(channel -> {
            try {
                channel.shutdown();
                if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                    channel.shutdownNow();
                }
            } catch (InterruptedException e) {
                channel.shutdownNow();
                Thread.currentThread().interrupt();
            }
        });

        channels.clear();
        blockingStubs.clear();
        asyncStubs.clear();

        // Shutdown virtual thread executor
        virtualExecutor.shutdown();
    }

    /**
     * Represents an active streaming connection.
     */
    private static class StreamingConnection {
        final int targetRank;
        final StreamObserver<BalanceStatistics> requestObserver;
        final Consumer<BalanceStatistics> updateHandler;

        StreamingConnection(int targetRank,
                          StreamObserver<BalanceStatistics> requestObserver,
                          Consumer<BalanceStatistics> updateHandler) {
            this.targetRank = targetRank;
            this.requestObserver = requestObserver;
            this.updateHandler = updateHandler;
        }
    }

    /**
     * Represents a batched request with its completion future.
     */
    private static class BatchedRequest {
        final RefinementRequest request;
        final CompletableFuture<RefinementResponse> future;

        BatchedRequest(RefinementRequest request, CompletableFuture<RefinementResponse> future) {
            this.request = request;
            this.future = future;
        }
    }

    /**
     * Manages batching of refinement requests for a specific rank.
     */
    private class BatchQueue {
        private final int targetRank;
        private final int maxBatchSize;
        private final long timeoutMillis;
        private final List<BatchedRequest> queue;
        private final ScheduledExecutorService scheduler;
        private ScheduledFuture<?> timeoutTask;

        BatchQueue(int targetRank, int maxBatchSize, long timeoutMillis) {
            this.targetRank = targetRank;
            this.maxBatchSize = maxBatchSize;
            this.timeoutMillis = timeoutMillis;
            this.queue = new ArrayList<>();
            this.scheduler = Executors.newSingleThreadScheduledExecutor();
        }

        synchronized void add(BatchedRequest request) {
            queue.add(request);

            // Schedule timeout if this is the first request
            if (queue.size() == 1) {
                timeoutTask = scheduler.schedule(this::flush, timeoutMillis, TimeUnit.MILLISECONDS);
            }

            // Flush if batch is full
            if (queue.size() >= maxBatchSize) {
                if (timeoutTask != null) {
                    timeoutTask.cancel(false);
                }
                flush();
            }
        }

        synchronized void flush() {
            if (queue.isEmpty()) {
                return;
            }

            var batch = new ArrayList<>(queue);
            queue.clear();

            log.debug("Flushing batch of {} refinement requests to rank {}", batch.size(), targetRank);

            // Process each request individually (could be optimized with batch RPC in future)
            virtualExecutor.submit(() -> {
                for (var batchedReq : batch) {
                    try {
                        var stub = getBlockingStub(targetRank);
                        if (stub == null) {
                            batchedReq.future.completeExceptionally(
                                new RuntimeException("No connection to rank " + targetRank));
                            continue;
                        }

                        var response = stub.requestRefinement(batchedReq.request);
                        batchedReq.future.complete(response);
                    } catch (Exception e) {
                        batchedReq.future.completeExceptionally(e);
                    }
                }
            });
        }

        void shutdown() {
            flush();
            scheduler.shutdown();
        }
    }

    /**
     * Interface for service discovery mechanism.
     */
    public interface ServiceDiscovery {

        /**
         * Gets the gRPC endpoint for a specific process rank.
         *
         * @param rank the process rank
         * @return the endpoint (host:port), or null if not found
         */
        String getEndpoint(int rank);

        /**
         * Registers this process's endpoint.
         *
         * @param rank the process rank
         * @param endpoint the endpoint (host:port)
         */
        void registerEndpoint(int rank, String endpoint);

        /**
         * Gets all known endpoints.
         *
         * @return map of rank to endpoint
         */
        Map<Integer, String> getAllEndpoints();
    }
}
