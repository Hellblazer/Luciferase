/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.viz.render;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Consumes entity position streams from upstream simulation servers.
 * <p>
 * Connects as a WebSocket CLIENT using java.net.http.HttpClient.
 * Handles reconnection with exponential backoff.
 * Parses the JSON format produced by EntityVisualizationServer and
 * MultiBubbleVisualizationServer.
 * <p>
 * <b>CRITICAL FIX C2</b>: Implements circuit breaker to prevent unbounded
 * reconnection attempts. After MAX_RECONNECT_ATTEMPTS (10), enters circuit
 * breaker state and only retries after CIRCUIT_BREAKER_TIMEOUT_MS (5 minutes).
 * <p>
 * Thread model: One virtual thread per upstream connection.
 * Entity updates are forwarded to AdaptiveRegionManager on the
 * consuming thread.
 *
 * @author hal.hildebrand
 */
public class EntityStreamConsumer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EntityStreamConsumer.class);

    // C2: Reconnection limits to prevent resource exhaustion
    private static final int MAX_RECONNECT_ATTEMPTS = 10;
    private static final long CIRCUIT_BREAKER_TIMEOUT_MS = 300_000; // 5 minutes
    private static final long MAX_BACKOFF_MS = 60_000; // Cap backoff at 1 minute

    private final List<UpstreamConfig> upstreams;
    private final AdaptiveRegionManager regionManager;
    private final ConcurrentHashMap<URI, UpstreamState> connections = new ConcurrentHashMap<>();
    private final ExecutorService virtualThreadPool;
    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile Clock clock = Clock.system();

    /**
     * Create consumer with system clock.
     */
    public EntityStreamConsumer(List<UpstreamConfig> upstreams,
                                AdaptiveRegionManager regionManager) {
        this(upstreams, regionManager, Clock.system());
    }

    /**
     * Create consumer with custom clock (for testing).
     */
    public EntityStreamConsumer(List<UpstreamConfig> upstreams,
                                AdaptiveRegionManager regionManager,
                                Clock clock) {
        this.upstreams = upstreams;
        this.regionManager = regionManager;
        this.clock = clock;
        this.virtualThreadPool = Executors.newVirtualThreadPerTaskExecutor();

        log.info("EntityStreamConsumer created for {} upstreams", upstreams.size());
    }

    /**
     * Set the clock for deterministic testing.
     */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Start consuming from all upstream servers.
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("EntityStreamConsumer already started");
            return;
        }

        log.info("Starting EntityStreamConsumer");

        for (var upstream : upstreams) {
            connect(upstream);
        }
    }

    /**
     * Connect to an upstream server.
     */
    private void connect(UpstreamConfig upstream) {
        var state = connections.computeIfAbsent(upstream.uri(), uri -> new UpstreamState(
            uri,
            upstream.label(),
            null,
            new AtomicBoolean(false),
            new AtomicInteger(0),
            new AtomicLong(clock.currentTimeMillis()),
            new AtomicBoolean(false)
        ));

        virtualThreadPool.submit(() -> {
            try {
                log.info("Connecting to upstream: {} ({})", upstream.label(), upstream.uri());

                var client = HttpClient.newBuilder()
                                       .connectTimeout(Duration.ofSeconds(10))
                                       .build();

                var listener = new WebSocket.Listener() {
                    private final StringBuilder messageBuffer = new StringBuilder();

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        messageBuffer.append(data);
                        if (last) {
                            onMessage(upstream.uri(), messageBuffer.toString());
                            messageBuffer.setLength(0);
                        }
                        return WebSocket.Listener.super.onText(webSocket, data, last);
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        log.info("WebSocket closed for {}: {} - {}", upstream.label(), statusCode, reason);
                        state.connected.set(false);
                        reconnectWithBackoff(upstream.uri());
                        return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
                    }

                    @Override
                    public void onError(WebSocket webSocket, Throwable error) {
                        log.warn("WebSocket error for {}: {}", upstream.label(), error.getMessage());
                        state.connected.set(false);
                        reconnectWithBackoff(upstream.uri());
                    }

                    @Override
                    public void onOpen(WebSocket webSocket) {
                        log.info("WebSocket connected to {}", upstream.label());
                        state.connected.set(true);
                        state.reconnectAttempts.set(0);  // Reset on successful connection
                        WebSocket.Listener.super.onOpen(webSocket);
                    }
                };

                var webSocket = client.newWebSocketBuilder()
                                      .buildAsync(upstream.uri(), listener)
                                      .get(10, TimeUnit.SECONDS);

                // Update state with new WebSocket
                connections.computeIfPresent(upstream.uri(), (uri, oldState) ->
                    new UpstreamState(
                        oldState.uri,
                        oldState.label,
                        webSocket,
                        oldState.connected,
                        oldState.reconnectAttempts,
                        oldState.lastAttemptMs,
                        oldState.circuitBreakerOpen
                    )
                );

            } catch (Exception e) {
                log.error("Failed to connect to {}: {}", upstream.label(), e.getMessage());
                state.connected.set(false);
                reconnectWithBackoff(upstream.uri());
            }
        });
    }

    /**
     * CRITICAL FIX C2: Reconnection with circuit breaker.
     * <p>
     * Prevents unbounded reconnection attempts when upstream is down for
     * extended periods. After MAX_RECONNECT_ATTEMPTS, enters circuit breaker
     * state and only retries after CIRCUIT_BREAKER_TIMEOUT_MS.
     */
    private void reconnectWithBackoff(URI upstream) {
        if (!running.get()) {
            log.debug("Consumer stopped, skipping reconnection to {}", upstream);
            return;
        }

        var state = connections.get(upstream);
        if (state == null) {
            return;
        }

        // Check circuit breaker
        if (state.circuitBreakerOpen.get()) {
            long timeSinceLastAttempt = clock.currentTimeMillis() - state.lastAttemptMs.get();
            if (timeSinceLastAttempt < CIRCUIT_BREAKER_TIMEOUT_MS) {
                log.debug("Circuit breaker open for {}, skipping reconnect", upstream);
                return;
            } else {
                log.info("Circuit breaker timeout expired for {}, attempting reconnect", upstream);
                state.circuitBreakerOpen.set(false);
                state.reconnectAttempts.set(0);
            }
        }

        int attempts = state.reconnectAttempts.incrementAndGet();
        if (attempts > MAX_RECONNECT_ATTEMPTS) {
            log.error("Max reconnection attempts ({}) reached for {}, entering circuit breaker",
                      MAX_RECONNECT_ATTEMPTS, upstream);
            state.circuitBreakerOpen.set(true);
            state.lastAttemptMs.set(clock.currentTimeMillis());
            scheduleCircuitBreakerCheck(upstream);
            return;
        }

        // Exponential backoff with cap
        long backoffMs = Math.min((1L << attempts) * 1000, MAX_BACKOFF_MS);
        state.lastAttemptMs.set(clock.currentTimeMillis());

        log.info("Reconnecting to {} in {}ms (attempt {}/{})",
                 upstream, backoffMs, attempts, MAX_RECONNECT_ATTEMPTS);

        virtualThreadPool.submit(() -> {
            try {
                Thread.sleep(backoffMs);
                var upstreamConfig = upstreams.stream()
                    .filter(u -> u.uri().equals(upstream))
                    .findFirst()
                    .orElse(null);

                if (upstreamConfig != null) {
                    connect(upstreamConfig);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /**
     * Schedule circuit breaker check after timeout.
     */
    private void scheduleCircuitBreakerCheck(URI upstream) {
        virtualThreadPool.submit(() -> {
            try {
                Thread.sleep(CIRCUIT_BREAKER_TIMEOUT_MS);
                if (running.get()) {
                    reconnectWithBackoff(upstream);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /**
     * Handle incoming WebSocket message.
     * <p>
     * Parses JSON format: {"entities":[{"id":"e1","x":1.0,"y":2.0,"z":3.0,"type":"PREY"}],...}
     */
    private void onMessage(URI source, String json) {
        try {
            var upstreamLabel = upstreams.stream()
                .filter(u -> u.uri().equals(source))
                .map(UpstreamConfig::label)
                .findFirst()
                .orElse("unknown");

            var root = jsonMapper.readTree(json);
            var entitiesNode = root.get("entities");

            if (entitiesNode != null && entitiesNode.isArray()) {
                for (JsonNode entityNode : entitiesNode) {
                    var id = entityNode.get("id").asText();
                    var x = (float) entityNode.get("x").asDouble();
                    var y = (float) entityNode.get("y").asDouble();
                    var z = (float) entityNode.get("z").asDouble();
                    var type = entityNode.get("type").asText();

                    // M4: Prefix entity ID with upstream label for multi-upstream support
                    var globalId = upstreamLabel + ":" + id;

                    regionManager.updateEntity(globalId, x, y, z, type);
                }

                log.debug("Processed {} entities from {}", entitiesNode.size(), upstreamLabel);
            }
        } catch (Exception e) {
            log.error("Failed to parse entity JSON from {}: {}", source, e.getMessage());
        }
    }

    /**
     * Get health status for an upstream connection.
     */
    public UpstreamHealth getUpstreamHealth(URI upstream) {
        var state = connections.get(upstream);
        if (state == null) {
            return new UpstreamHealth(false, 0, false);
        }

        return new UpstreamHealth(
            state.connected.get(),
            state.reconnectAttempts.get(),
            state.circuitBreakerOpen.get()
        );
    }

    @Override
    public void close() {
        log.info("Closing EntityStreamConsumer");
        running.set(false);

        // Close all WebSocket connections
        for (var state : connections.values()) {
            if (state.webSocket != null) {
                state.webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Consumer closing");
            }
        }

        virtualThreadPool.shutdown();

        try {
            if (!virtualThreadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                virtualThreadPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            virtualThreadPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * State for a single upstream connection.
     */
    private record UpstreamState(
        URI uri,
        String label,
        WebSocket webSocket,
        AtomicBoolean connected,
        AtomicInteger reconnectAttempts,
        AtomicLong lastAttemptMs,        // C2: Track last reconnection attempt
        AtomicBoolean circuitBreakerOpen  // C2: Circuit breaker state
    ) {}

    /**
     * Health status for an upstream connection.
     */
    public record UpstreamHealth(
        boolean connected,
        int reconnectAttempts,
        boolean circuitBreakerOpen
    ) {}
}
