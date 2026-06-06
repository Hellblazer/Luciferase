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
import com.hellblazer.luciferase.common.grpc.GrpcServerHardening;
import com.hellblazer.luciferase.common.time.Clock;
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
    // Bounded retry for transient gRPC failures (Luciferase-0frcy.23). Exponential backoff;
    // a partition outlasting the budget still drops the event (documented on the interface).
    private static final int MAX_RPC_RETRIES = 3;
    private static final long INITIAL_RETRY_BACKOFF_MS = 50;
    /**
     * Consecutive terminal delivery failures (after retries are exhausted) to one node before it is
     * treated as unreachable (Luciferase-0frcy.99). A crashed/partitioned node stays registered, so a
     * pure address-registry liveness check reports it reachable forever; this failure-count gate marks
     * it unreachable so callers stop attempting delivery and can trigger rollback.
     */
    private static final int UNREACHABLE_FAILURE_THRESHOLD = 3;

    private UUID localNodeId;
    private String localAddress;
    private Server server;
    // Luciferase-3l1b5: track the JVM shutdown hook so it is registered at most once per instance and can be
    // deregistered in close() (was leaked: a new hook added on every initialize(), never removed).
    private volatile Thread shutdownHook;
    private final java.util.concurrent.atomic.AtomicBoolean shutdownHookRegistered =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    private final Map<UUID, ManagedChannel> remoteChannels = new ConcurrentHashMap<>();
    private final Map<UUID, String> nodeAddresses = new ConcurrentHashMap<>();
    // Per-node consecutive terminal-failure counter for liveness gating (Luciferase-0frcy.99).
    private final Map<UUID, java.util.concurrent.atomic.AtomicInteger> consecutiveFailures = new ConcurrentHashMap<>();
    // Luciferase-zwyf2: bounded pool. newCachedThreadPool() is unbounded — under a migration/RPC
    // storm it spawns one thread per queued callback with no cap, risking thread/stack OOM. Cap at
    // 2x CPU cores; excess callbacks queue rather than spawning unbounded threads.
    private final ExecutorService executorService =
        Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors() * 2));
    // Schedules retry attempts for transient RPC failures (Luciferase-0frcy.23).
    private final ScheduledExecutorService retryScheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "grpc-bubble-retry");
            t.setDaemon(true);
            return t;
        });

    /**
     * Transport-security opt-in flag (Luciferase-7wzml.200).
     *
     * <p>Full mTLS with peer-identity binding is tracked in Luciferase-l9dny (coordinates
     * with RDR-005/RDR-013 cert plumbing). Until that work is done this flag is the only
     * way to enable the channel: callers must explicitly opt into plaintext rather than
     * silently inheriting an insecure default.
     *
     * <p>Set via {@link #setAllowPlaintext(boolean)} or the constructor overload
     * {@link #GrpcBubbleNetworkChannel(boolean)}.
     */
    private volatile boolean allowPlaintext = false;

    private volatile EntityDepartureListener departureListener;
    private volatile ViewSynchronyAckListener ackListener;
    private volatile EntityRollbackListener rollbackListener;

    // Optional simulation parameters (for backward compatibility with FakeNetworkChannel)
    private volatile long networkLatencyMs = 0;
    private volatile double packetLossRate = 0.0;
    private volatile Clock clock = Clock.system();

    public GrpcBubbleNetworkChannel() {
        // allowPlaintext defaults to false; callers must opt in via setAllowPlaintext(true)
        // or GrpcBubbleNetworkChannel(true) before calling initialize().
    }

    /**
     * Constructor with explicit transport-security opt-in.
     *
     * @param allowPlaintext {@code true} to permit unencrypted plaintext transport.
     *                       Should only be {@code true} in tests or controlled environments
     *                       where TLS is not yet available (see Luciferase-l9dny).
     */
    public GrpcBubbleNetworkChannel(boolean allowPlaintext) {
        this.allowPlaintext = allowPlaintext;
    }

    /**
     * Explicitly opt into plaintext (unencrypted) transport.
     *
     * <p>Must be called before {@link #initialize} if no TLS is configured.
     * Production deployments should not call this method — see Luciferase-l9dny for
     * the mTLS implementation roadmap.
     *
     * @param allowPlaintext {@code true} to permit plaintext; {@code false} (default) to require TLS
     */
    public void setAllowPlaintext(boolean allowPlaintext) {
        this.allowPlaintext = allowPlaintext;
    }

    /**
     * Set the clock for deterministic testing.
     *
     * @param clock Clock instance to use
     */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void initialize(UUID nodeId, String nodeAddress) {
        this.localNodeId = nodeId;

        // Server-side plaintext gate (Luciferase-7wzml.200 I1).
        // Mirror the client-side gate in getOrCreateChannel(): both directions must opt in.
        //
        // NOTE: TLS does not yet exist — this gate is a construction-time reminder that plaintext
        // is the only transport, NOT runtime security enforcement. Real mTLS (with peer-identity
        // binding) is tracked in Luciferase-l9dny. Do not interpret passing this gate as a
        // security guarantee; it is an explicit acknowledgement that plaintext is intentional.
        if (!allowPlaintext) {
            throw new IllegalStateException(
                "Server: plaintext transport requires explicit opt-in via setAllowPlaintext(true). "
                + "Full mTLS with peer-identity binding is tracked in Luciferase-l9dny "
                + "(coordinates with RDR-005/RDR-013 cert plumbing).");
        }

        try {
            // Parse port from address (format: "host:port")
            var port = parsePort(nodeAddress);

            // Build and start gRPC server on specified port (0 = dynamic)
            var bubbleServerBuilder = NettyServerBuilder.forPort(port)
                .addService(new BubbleMigrationServiceImpl())
                .executor(executorService);
            // RDR-013 / Luciferase-06ujn: explicit inbound size + metadata bounds (DoS surface) — this is the
            // third production gRPC server (alongside Ghost), enumerated in RDR-005's inventory.
            GrpcServerHardening.applyInboundLimits(bubbleServerBuilder);
            server = bubbleServerBuilder
                .build()
                .start();

            // Store actual address with assigned port
            var actualPort = server.getPort();
            var host = nodeAddress.split(":")[0];
            this.localAddress = host + ":" + actualPort;

            nodeAddresses.put(nodeId, localAddress);
            log.info("gRPC network channel initialized: {} at {}", nodeId, localAddress);

            // Register a JVM shutdown hook exactly once per instance (Luciferase-3l1b5). Without the guard a
            // fresh hook was added on every initialize() and never removed, leaking a Thread per call.
            if (shutdownHookRegistered.compareAndSet(false, true)) {
                shutdownHook = new Thread(() -> {
                    log.info("Shutting down gRPC channel via shutdown hook");
                    try {
                        GrpcBubbleNetworkChannel.this.close();
                    } catch (Exception e) {
                        log.error("Error during shutdown hook", e);
                    }
                });
                Runtime.getRuntime().addShutdownHook(shutdownHook);
            }

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
            var protoEvent = convertToProto(event);

            // Async RPC with bounded transient-failure retry (Luciferase-0frcy.23).
            sendWithRetry("EntityDepartureEvent", targetNodeId, 0, observer ->
                BubbleMigrationServiceGrpc.newStub(getOrCreateChannel(targetNodeId))
                    .withDeadlineAfter(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .initiateMigration(protoEvent, observer),
                r -> log.debug("Migration initiated: {}", ((MigrationResponse) r).getEntityId()));

            return true;

        } catch (Exception e) {
            log.error("Failed to send EntityDepartureEvent to {}", targetNodeId, e);
            return false;
        }
    }

    /**
     * Dispatch an async unary RPC and retry transient failures (UNAVAILABLE / DEADLINE_EXCEEDED)
     * with exponential backoff up to {@link #MAX_RPC_RETRIES} (Luciferase-0frcy.23). Non-transient
     * failures and exhausted retries are logged at error level — the boolean returned by the caller
     * is a queued/dispatched signal only, never a delivery guarantee (see interface javadoc).
     *
     * @param label    human-readable RPC label for logging
     * @param peerId   target/source node id (for logging)
     * @param attempt  current 0-based attempt number
     * @param rpc      invokes the stub method with the supplied response observer
     * @param onNext   handler for a successful response
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void sendWithRetry(String label, UUID peerId, int attempt,
                               java.util.function.Consumer<StreamObserver> rpc,
                               java.util.function.Consumer<Object> onNext) {
        StreamObserver<Object> observer = new StreamObserver<>() {
            @Override
            public void onNext(Object response) {
                // Successful delivery: clear the node's consecutive-failure streak (Luciferase-0frcy.99).
                consecutiveFailures.computeIfAbsent(peerId, k -> new java.util.concurrent.atomic.AtomicInteger())
                                   .set(0);
                try {
                    onNext.accept(response);
                } catch (Exception e) {
                    // Luciferase-7wzml.201: log rather than silently swallow — handler exceptions
                    // are not propagated (the observer contract is void) but must not be invisible.
                    log.warn("sendWithRetry onNext handler threw for peer {} (label={})", peerId, label, e);
                }
            }

            @Override
            public void onError(Throwable t) {
                var code = Status.fromThrowable(t).getCode();
                var transient_ = code == Status.Code.UNAVAILABLE || code == Status.Code.DEADLINE_EXCEEDED;
                if (transient_ && attempt < MAX_RPC_RETRIES) {
                    long backoff = INITIAL_RETRY_BACKOFF_MS * (1L << attempt);
                    log.warn("Transient failure sending {} to {} (attempt {}/{}, {}); retrying in {}ms",
                             label, peerId, attempt + 1, MAX_RPC_RETRIES, code, backoff);
                    try {
                        retryScheduler.schedule(
                            () -> sendWithRetry(label, peerId, attempt + 1, rpc, onNext),
                            backoff, TimeUnit.MILLISECONDS);
                    } catch (RejectedExecutionException rejected) {
                        log.warn("Retry scheduler unavailable (shutting down) for {} to {}", label, peerId);
                    }
                } else {
                    // Terminal failure (non-transient, or retries exhausted): bump the node's
                    // consecutive-failure count so isNodeReachable() can mark it unreachable
                    // (Luciferase-0frcy.99).
                    var failures = consecutiveFailures
                        .computeIfAbsent(peerId, k -> new java.util.concurrent.atomic.AtomicInteger())
                        .incrementAndGet();
                    log.error("Failed to send {} to {} after {} attempt(s): {} ({}); consecutive failures={}",
                              label, peerId, attempt + 1, t.getMessage(), code, failures);
                }
            }

            @Override
            public void onCompleted() {
                // Success
            }
        };
        rpc.accept(observer);
    }

    @Override
    public boolean sendViewSynchronyAck(UUID sourceNodeId, ViewSynchronyAck event) {
        if (!isNodeReachable(sourceNodeId)) {
            log.warn("Source node {} unreachable", sourceNodeId);
            return false;
        }

        try {
            var protoAck = convertToProto(event);

            // Async RPC with bounded transient-failure retry (Luciferase-0frcy.23).
            sendWithRetry("ViewSynchronyAck", sourceNodeId, 0, observer ->
                BubbleMigrationServiceGrpc.newStub(getOrCreateChannel(sourceNodeId))
                    .withDeadlineAfter(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .acknowledgeViewSynchrony(protoAck, observer),
                r -> log.debug("View synchrony acknowledged: {}",
                    ((com.hellblazer.luciferase.lucien.distributed.migration.proto.ViewSynchronyAck) r).getEntityId()));

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
            var protoRollback = convertToProto(event);

            // Async RPC with bounded transient-failure retry (Luciferase-0frcy.23).
            sendWithRetry("EntityRollbackEvent", targetNodeId, 0, observer ->
                BubbleMigrationServiceGrpc.newStub(getOrCreateChannel(targetNodeId))
                    .withDeadlineAfter(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .rollbackMigration(protoRollback, observer),
                r -> log.debug("Rollback completed: {}",
                    ((com.hellblazer.luciferase.lucien.distributed.migration.proto.EntityRollbackEvent) r).getEntityId()));

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
        // Luciferase-0frcy.99: registry presence alone does not mean live — a crashed/partitioned node
        // remains registered. Treat a node as unreachable once it has accumulated
        // UNREACHABLE_FAILURE_THRESHOLD consecutive terminal delivery failures (reset on any success).
        if (!nodeAddresses.containsKey(nodeId)) {
            return false;
        }
        var failures = consecutiveFailures.get(nodeId);
        return failures == null || failures.get() < UNREACHABLE_FAILURE_THRESHOLD;
    }

    /**
     * Re-register a node, clearing any accumulated failure count (Luciferase-0frcy.99). Use when a
     * previously-unreachable node has recovered and should be considered live again.
     *
     * @param nodeId the node to mark reachable again
     */
    public void markNodeRecovered(UUID nodeId) {
        var failures = consecutiveFailures.get(nodeId);
        if (failures != null) {
            failures.set(0);
        }
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

        // Deregister the JVM shutdown hook so it does not leak past close() (Luciferase-3l1b5). When close() is
        // itself running from the hook (JVM already shutting down), removeShutdownHook throws
        // IllegalStateException — ignore it, the hook is already executing.
        if (shutdownHookRegistered.compareAndSet(true, false)) {
            var hook = shutdownHook;
            shutdownHook = null;
            if (hook != null) {
                try {
                    Runtime.getRuntime().removeShutdownHook(hook);
                } catch (IllegalStateException e) {
                    // JVM already in shutdown — hook is running and cannot be removed; safe to ignore.
                }
            }
        }

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

        // Shutdown retry scheduler (Luciferase-0frcy.23)
        retryScheduler.shutdownNow();

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
     *
     * @throws IllegalStateException if plaintext has not been explicitly opted into and
     *                               TLS is not yet configured (see Luciferase-l9dny)
     */
    private ManagedChannel getOrCreateChannel(UUID targetNodeId) {
        return remoteChannels.computeIfAbsent(targetNodeId, nodeId -> {
            var address = nodeAddresses.get(nodeId);
            if (address == null) {
                throw new IllegalStateException("No address registered for node: " + nodeId);
            }

            // Client-side plaintext gate (Luciferase-7wzml.200).
            //
            // NOTE: TLS does not yet exist — this gate is a construction-time reminder that
            // plaintext is the only transport, NOT runtime security enforcement. Real mTLS
            // (with peer-identity binding) is tracked in Luciferase-l9dny. Passing this gate
            // is an explicit acknowledgement that plaintext is intentional, not a security
            // guarantee.
            if (!allowPlaintext) {
                throw new IllegalStateException(
                    "plaintext transport requires explicit opt-in: call setAllowPlaintext(true) "
                    + "or use GrpcBubbleNetworkChannel(true). "
                    + "Full mTLS with peer-identity binding is tracked in Luciferase-l9dny "
                    + "(coordinates with RDR-005/RDR-013 cert plumbing).");
            }

            var parts = address.split(":");
            var host = parts[0];
            var port = Integer.parseInt(parts[1]);

            log.debug("Creating plaintext gRPC channel to {}:{} (opt-in; see Luciferase-l9dny for TLS roadmap)",
                      host, port);
            return NettyChannelBuilder.forAddress(host, port)
                .usePlaintext()
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
                    .setResponseTimestamp(clock.nanoTime())
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
                // The ack originates from the target bubble, which writes its own identity
                // into sourceBubbleId (see convertToProto). Read sourceBubbleId — reading
                // targetBubbleId yielded a phantom id and orphaned the source FSM
                // (Luciferase-wikxz).
                var sourceNodeId = UUID.fromString(request.getSourceBubbleId());

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
                .setResponseTimestamp(clock.nanoTime())
                .setStatusMessage("OK")
                .setPendingMigrations(0)
                .setActiveEntities(0)
                .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }
}
