/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 * Part of Luciferase Simulation Framework. Licensed under AGPL v3.0.
 */

package com.hellblazer.luciferase.simulation.distributed.network;

// Proto classes - DON'T import to avoid name collisions with domain classes
import com.hellblazer.luciferase.lucien.distributed.migration.proto.BubbleMigrationServiceGrpc;
import com.hellblazer.luciferase.lucien.distributed.migration.proto.HealthCheckRequest;
import com.hellblazer.luciferase.lucien.distributed.migration.proto.HealthCheckResponse;
import com.hellblazer.luciferase.lucien.distributed.migration.proto.MigrationResponse;

// Domain event classes
import com.hellblazer.luciferase.simulation.causality.EntityMigrationState;
import com.hellblazer.luciferase.simulation.events.EntityDepartureEvent;
import com.hellblazer.luciferase.simulation.events.EntityRollbackEvent;
import com.hellblazer.luciferase.simulation.events.ViewSynchronyAck;

// gRPC
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Production gRPC-based network channel for inter-bubble communication.
 * Uses gRPC/Netty for real network transport with connection pooling and timeout handling.
 */
public class GrpcBubbleNetworkChannel implements BubbleNetworkChannel, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GrpcBubbleNetworkChannel.class);
    private static final int DEFAULT_TIMEOUT_SECONDS = 5;

    private UUID localNodeId;
    private String localAddress;
    private Server server;
    private final Map<UUID, ManagedChannel> remoteChannels = new ConcurrentHashMap<>();
    private final Map<UUID, String> nodeAddresses = new ConcurrentHashMap<>();
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    private volatile EntityDepartureListener departureListener;
    private volatile ViewSynchronyAckListener ackListener;
    private volatile EntityRollbackListener rollbackListener;

    // Optional simulation parameters (for backward compatibility with FakeNetworkChannel)
    private volatile long networkLatencyMs = 0;
    private volatile double packetLossRate = 0.0;

    public GrpcBubbleNetworkChannel() {
        // Default constructor
    }

    @Override
    public void initialize(UUID nodeId, String nodeAddress) {
        this.localNodeId = nodeId;

        try {
            // Parse port from address (format: "host:port")
            var port = parsePort(nodeAddress);

            // Build and start gRPC server on specified port (0 = dynamic)
            server = NettyServerBuilder.forPort(port)
                .addService(new BubbleMigrationServiceImpl())
                .executor(executorService)
                .build()
                .start();

            // Store actual address with assigned port
            var actualPort = server.getPort();
            var host = nodeAddress.split(":")[0];
            this.localAddress = host + ":" + actualPort;

            nodeAddresses.put(nodeId, localAddress);
            log.info("gRPC network channel initialized: {} at {}", nodeId, localAddress);

            // Add shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Shutting down gRPC channel via shutdown hook");
                try {
                    GrpcBubbleNetworkChannel.this.close();
                } catch (Exception e) {
                    log.error("Error during shutdown hook", e);
                }
            }));

        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize gRPC server", e);
        }
    }

    @Override
    public void registerNode(UUID nodeId, String nodeAddress) {
        nodeAddresses.put(nodeId, nodeAddress);
        log.debug("Registered remote node: {} at {}", nodeId, nodeAddress);
    }

    @Override
    public boolean sendEntityDeparture(UUID targetNodeId, EntityDepartureEvent event) {
        if (!isNodeReachable(targetNodeId)) {
            log.warn("Target node {} unreachable", targetNodeId);
            return false;
        }

        try {
            var channel = getOrCreateChannel(targetNodeId);
            var stub = BubbleMigrationServiceGrpc.newStub(channel)
                .withDeadlineAfter(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            var protoEvent = convertToProto(event);

            // Async RPC call (fire-and-forget)
            stub.initiateMigration(protoEvent, new StreamObserver<MigrationResponse>() {
                @Override
                public void onNext(MigrationResponse response) {
                    log.debug("Migration initiated: {}", response.getEntityId());
                }

                @Override
                public void onError(Throwable t) {
                    log.error("Error sending EntityDepartureEvent to {}: {}",
                             targetNodeId, t.getMessage());
                }

                @Override
                public void onCompleted() {
                    // Success
                }
            });

            return true;

        } catch (Exception e) {
            log.error("Failed to send EntityDepartureEvent to {}", targetNodeId, e);
            return false;
        }
    }

    @Override
    public boolean sendViewSynchronyAck(UUID sourceNodeId, ViewSynchronyAck event) {
        if (!isNodeReachable(sourceNodeId)) {
            log.warn("Source node {} unreachable", sourceNodeId);
            return false;
        }

        try {
            var channel = getOrCreateChannel(sourceNodeId);
            var stub = BubbleMigrationServiceGrpc.newStub(channel)
                .withDeadlineAfter(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            var protoAck = convertToProto(event);

            // Async RPC call (fire-and-forget)
            stub.acknowledgeViewSynchrony(protoAck, new StreamObserver<com.hellblazer.luciferase.lucien.distributed.migration.proto.ViewSynchronyAck>() {
                @Override
                public void onNext(com.hellblazer.luciferase.lucien.distributed.migration.proto.ViewSynchronyAck response) {
                    log.debug("View synchrony acknowledged: {}", response.getEntityId());
                }

                @Override
                public void onError(Throwable t) {
                    log.error("Error sending ViewSynchronyAck to {}: {}",
                             sourceNodeId, t.getMessage());
                }

                @Override
                public void onCompleted() {
                    // Success
                }
            });

            return true;

        } catch (Exception e) {
            log.error("Failed to send ViewSynchronyAck to {}", sourceNodeId, e);
            return false;
        }
    }

    @Override
    public boolean sendEntityRollback(UUID targetNodeId, EntityRollbackEvent event) {
        if (!isNodeReachable(targetNodeId)) {
            log.warn("Target node {} unreachable", targetNodeId);
            return false;
        }

        try {
            var channel = getOrCreateChannel(targetNodeId);
            var stub = BubbleMigrationServiceGrpc.newStub(channel)
                .withDeadlineAfter(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            var protoRollback = convertToProto(event);

            // Async RPC call (fire-and-forget)
            stub.rollbackMigration(protoRollback, new StreamObserver<com.hellblazer.luciferase.lucien.distributed.migration.proto.EntityRollbackEvent>() {
                @Override
                public void onNext(com.hellblazer.luciferase.lucien.distributed.migration.proto.EntityRollbackEvent response) {
                    log.debug("Rollback completed: {}", response.getEntityId());
                }

                @Override
                public void onError(Throwable t) {
                    log.error("Error sending EntityRollbackEvent to {}: {}",
                             targetNodeId, t.getMessage());
                }

                @Override
                public void onCompleted() {
                    // Success
                }
            });

            return true;

        } catch (Exception e) {
            log.error("Failed to send EntityRollbackEvent to {}", targetNodeId, e);
            return false;
        }
    }

    @Override
    public void setEntityDepartureListener(EntityDepartureListener listener) {
        this.departureListener = listener;
    }

    @Override
    public void setViewSynchronyAckListener(ViewSynchronyAckListener listener) {
        this.ackListener = listener;
    }

    @Override
    public void setEntityRollbackListener(EntityRollbackListener listener) {
        this.rollbackListener = listener;
    }

    @Override
    public void setNetworkLatency(long latencyMs) {
        this.networkLatencyMs = Math.max(0, latencyMs);
        log.debug("Network latency simulation set to {}ms (note: gRPC may not honor this exactly)",
                 networkLatencyMs);
    }

    @Override
    public void setPacketLoss(double lossRate) {
        this.packetLossRate = Math.max(0.0, Math.min(1.0, lossRate));
        log.debug("Packet loss simulation set to {} (note: gRPC may not honor this)", packetLossRate);
    }

    @Override
    public boolean isNodeReachable(UUID nodeId) {
        // For now, just check if we have the address registered
        // In production, could implement health checks
        return nodeAddresses.containsKey(nodeId);
    }

    @Override
    public int getPendingMessageCount() {
        // gRPC handles message queuing internally, so return 0
        return 0;
    }

    /**
     * Get the local address this channel is bound to.
     * @return Local address in format "host:port"
     */
    public String getLocalAddress() {
        return localAddress;
    }

    @Override
    public void close() throws Exception {
        log.info("Shutting down gRPC channel: {}", localNodeId);

        // Shutdown all remote channels
        for (var entry : remoteChannels.entrySet()) {
            try {
                entry.getValue().shutdown().awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                entry.getValue().shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        remoteChannels.clear();

        // Shutdown server
        if (server != null) {
            server.shutdown();
            try {
                if (!server.awaitTermination(2, TimeUnit.SECONDS)) {
                    server.shutdownNow();
                }
            } catch (InterruptedException e) {
                server.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // Shutdown executor
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(2, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }

        log.info("gRPC channel shutdown complete: {}", localNodeId);
    }

    /**
     * Get or create a gRPC channel to the target node (connection pooling).
     */
    private ManagedChannel getOrCreateChannel(UUID targetNodeId) {
        return remoteChannels.computeIfAbsent(targetNodeId, nodeId -> {
            var address = nodeAddresses.get(nodeId);
            if (address == null) {
                throw new IllegalStateException("No address registered for node: " + nodeId);
            }

            var parts = address.split(":");
            var host = parts[0];
            var port = Integer.parseInt(parts[1]);

            log.debug("Creating gRPC channel to {}:{}", host, port);
            return NettyChannelBuilder.forAddress(host, port)
                .usePlaintext() // For testing; use TLS in production
                .executor(executorService)
                .build();
        });
    }

    /**
     * Parse port from address string.
     */
    private int parsePort(String address) {
        var parts = address.split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid address format: " + address);
        }
        return Integer.parseInt(parts[1]);
    }

    // ==================== Proto Conversion Methods ====================

    private com.hellblazer.luciferase.lucien.distributed.migration.proto.EntityDepartureEvent convertToProto(EntityDepartureEvent event) {
        return com.hellblazer.luciferase.lucien.distributed.migration.proto.EntityDepartureEvent.newBuilder()
            .setVersion(1)
            .setEntityId(event.getEntityId().toString())
            .setSourceBubbleId(event.getSourceBubbleId().toString())
            .setTargetBubbleId(event.getTargetBubbleId().toString())
            .setState(convertToProtoState(event.getStateSnapshot()))
            .setTimestampNanos(event.getLamportClock())
            .build();
    }

    private com.hellblazer.luciferase.lucien.distributed.migration.proto.ViewSynchronyAck
            convertToProto(ViewSynchronyAck event) {
        return com.hellblazer.luciferase.lucien.distributed.migration.proto.ViewSynchronyAck.newBuilder()
            .setVersion(1)
            .setEntityId(event.getEntityId().toString())
            .setSourceBubbleId(event.getSourceBubbleId().toString())
            .setTargetBubbleId(event.getTargetBubbleId().toString())
            .setState(com.hellblazer.luciferase.lucien.distributed.migration.proto.EntityMigrationState.OWNED)
            .setTimestampNanos(event.getLamportClock())
            .setSuccess(true)
            .setMemberCount(event.getStabilityTicksVerified())
            .build();
    }

    private com.hellblazer.luciferase.lucien.distributed.migration.proto.EntityRollbackEvent
            convertToProto(EntityRollbackEvent event) {
        return com.hellblazer.luciferase.lucien.distributed.migration.proto.EntityRollbackEvent.newBuilder()
            .setVersion(1)
            .setEntityId(event.getEntityId().toString())
            .setSourceBubbleId(event.getSourceBubbleId().toString())
            .setTargetBubbleId(event.getTargetBubbleId().toString())
            .setState(com.hellblazer.luciferase.lucien.distributed.migration.proto.EntityMigrationState.ROLLBACK_OWNED)
            .setTimestampNanos(event.getLamportClock())
            .setRollbackReason(event.getReason())
            .setSourceInitiated(true)
            .build();
    }

    private EntityDepartureEvent convertFromProto(com.hellblazer.luciferase.lucien.distributed.migration.proto.EntityDepartureEvent proto, UUID sourceNodeId) {
        return new EntityDepartureEvent(
            UUID.fromString(proto.getEntityId()),
            sourceNodeId,
            UUID.fromString(proto.getTargetBubbleId()),
            convertFromProtoState(proto.getState()),
            proto.getTimestampNanos()
        );
    }

    private ViewSynchronyAck convertFromProto(
            com.hellblazer.luciferase.lucien.distributed.migration.proto.ViewSynchronyAck proto,
            UUID sourceNodeId) {
        return new ViewSynchronyAck(
            UUID.fromString(proto.getEntityId()),
            UUID.fromString(proto.getSourceBubbleId()),
            sourceNodeId,
            proto.getMemberCount(),
            proto.getTimestampNanos()
        );
    }

    private EntityRollbackEvent convertFromProto(
            com.hellblazer.luciferase.lucien.distributed.migration.proto.EntityRollbackEvent proto,
            UUID sourceNodeId) {
        return new EntityRollbackEvent(
            UUID.fromString(proto.getEntityId()),
            sourceNodeId,
            UUID.fromString(proto.getTargetBubbleId()),
            proto.getRollbackReason(),
            proto.getTimestampNanos()
        );
    }

    private com.hellblazer.luciferase.lucien.distributed.migration.proto.EntityMigrationState
            convertToProtoState(EntityMigrationState state) {
        return switch (state) {
            case OWNED -> com.hellblazer.luciferase.lucien.distributed.migration.proto.EntityMigrationState.OWNED;
            case MIGRATING_OUT -> com.hellblazer.luciferase.lucien.distributed.migration.proto.EntityMigrationState.MIGRATING_OUT;
            case DEPARTED -> com.hellblazer.luciferase.lucien.distributed.migration.proto.EntityMigrationState.DEPARTED;
            case GHOST -> com.hellblazer.luciferase.lucien.distributed.migration.proto.EntityMigrationState.GHOST;
            case MIGRATING_IN -> com.hellblazer.luciferase.lucien.distributed.migration.proto.EntityMigrationState.MIGRATING_IN;
            case ROLLBACK_OWNED -> com.hellblazer.luciferase.lucien.distributed.migration.proto.EntityMigrationState.ROLLBACK_OWNED;
        };
    }

    private EntityMigrationState convertFromProtoState(
            com.hellblazer.luciferase.lucien.distributed.migration.proto.EntityMigrationState state) {
        return switch (state) {
            case OWNED -> EntityMigrationState.OWNED;
            case MIGRATING_OUT -> EntityMigrationState.MIGRATING_OUT;
            case DEPARTED -> EntityMigrationState.DEPARTED;
            case GHOST -> EntityMigrationState.GHOST;
            case MIGRATING_IN -> EntityMigrationState.MIGRATING_IN;
            case ROLLBACK_OWNED -> EntityMigrationState.ROLLBACK_OWNED;
            default -> throw new IllegalArgumentException("Unknown state: " + state);
        };
    }

    // ==================== gRPC Service Implementation ====================

    /**
     * gRPC service implementation for BubbleMigrationService.
     * Handles incoming RPC calls and dispatches to listeners.
     */
    private class BubbleMigrationServiceImpl extends BubbleMigrationServiceGrpc.BubbleMigrationServiceImplBase {

        @Override
        public void initiateMigration(com.hellblazer.luciferase.lucien.distributed.migration.proto.EntityDepartureEvent request,
                                     StreamObserver<MigrationResponse> responseObserver) {
            try {
                // Extract source node ID from request
                var sourceNodeId = UUID.fromString(request.getSourceBubbleId());

                // Dispatch to listener asynchronously
                if (departureListener != null) {
                    executorService.execute(() -> {
                        try {
                            var event = convertFromProto(request, sourceNodeId);
                            departureListener.onEntityDeparture(sourceNodeId, event);
                        } catch (Exception e) {
                            log.error("Error dispatching EntityDepartureEvent", e);
                        }
                    });
                }

                // Send response
                var response = MigrationResponse.newBuilder()
                    .setVersion(1)
                    .setEntityId(request.getEntityId())
                    .setSourceBubbleId(request.getSourceBubbleId())
                    .setTargetBubbleId(request.getTargetBubbleId())
                    .setAccepted(true)
                    .setResponseTimestamp(System.nanoTime())
                    .build();

                responseObserver.onNext(response);
                responseObserver.onCompleted();

            } catch (Exception e) {
                log.error("Error handling initiateMigration", e);
                responseObserver.onError(Status.INTERNAL
                    .withDescription("Error processing migration: " + e.getMessage())
                    .asRuntimeException());
            }
        }

        @Override
        public void acknowledgeViewSynchrony(
                com.hellblazer.luciferase.lucien.distributed.migration.proto.ViewSynchronyAck request,
                StreamObserver<com.hellblazer.luciferase.lucien.distributed.migration.proto.ViewSynchronyAck> responseObserver) {
            try {
                // Extract source node ID from request
                var sourceNodeId = UUID.fromString(request.getTargetBubbleId());

                // Dispatch to listener asynchronously
                if (ackListener != null) {
                    executorService.execute(() -> {
                        try {
                            var ack = convertFromProto(request, sourceNodeId);
                            ackListener.onViewSynchronyAck(sourceNodeId, ack);
                        } catch (Exception e) {
                            log.error("Error dispatching ViewSynchronyAck", e);
                        }
                    });
                }

                // Echo back the ack
                responseObserver.onNext(request);
                responseObserver.onCompleted();

            } catch (Exception e) {
                log.error("Error handling acknowledgeViewSynchrony", e);
                responseObserver.onError(Status.INTERNAL
                    .withDescription("Error processing ack: " + e.getMessage())
                    .asRuntimeException());
            }
        }

        @Override
        public void rollbackMigration(
                com.hellblazer.luciferase.lucien.distributed.migration.proto.EntityRollbackEvent request,
                StreamObserver<com.hellblazer.luciferase.lucien.distributed.migration.proto.EntityRollbackEvent> responseObserver) {
            try {
                // Extract source node ID from request
                var sourceNodeId = UUID.fromString(request.getSourceBubbleId());

                // Dispatch to listener asynchronously
                if (rollbackListener != null) {
                    executorService.execute(() -> {
                        try {
                            var rollback = convertFromProto(request, sourceNodeId);
                            rollbackListener.onEntityRollback(sourceNodeId, rollback);
                        } catch (Exception e) {
                            log.error("Error dispatching EntityRollbackEvent", e);
                        }
                    });
                }

                // Echo back the rollback
                responseObserver.onNext(request);
                responseObserver.onCompleted();

            } catch (Exception e) {
                log.error("Error handling rollbackMigration", e);
                responseObserver.onError(Status.INTERNAL
                    .withDescription("Error processing rollback: " + e.getMessage())
                    .asRuntimeException());
            }
        }

        @Override
        public void healthCheck(HealthCheckRequest request,
                               StreamObserver<HealthCheckResponse> responseObserver) {
            var response = HealthCheckResponse.newBuilder()
                .setVersion(1)
                .setBubbleId(localNodeId.toString())
                .setHealthy(true)
                .setResponseTimestamp(System.nanoTime())
                .setStatusMessage("OK")
                .setPendingMigrations(0)
                .setActiveEntities(0)
                .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }
}
