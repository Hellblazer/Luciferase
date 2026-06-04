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
import com.hellblazer.luciferase.common.time.Clock;
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
    private final PerformanceConfig performanceConfig;
    private final ConcurrentHashMap<URI, UpstreamState> connections = new ConcurrentHashMap<>();
    private final ExecutorService virtualThreadPool;
    /**
     * Scheduler for timed reconnection / circuit-breaker re-checks (Luciferase-0frcy.120).
     * <p>
     * Replaces the previous {@code virtualThreadPool.submit(() -> Thread.sleep(...))}
     * pattern: a 5-minute {@code Thread.sleep} parked a virtual thread per upstream in
     * circuit-breaker state and could not be advanced by an injected clock, forcing
     * reconnection tests onto wall-clock waits. A {@link ScheduledExecutorService} defers
     * the work without a parked thread and lets tests drive timing by submitting an
     * immediate-execution scheduler.
     */
    private final ScheduledExecutorService reconnectScheduler;
    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile Clock clock = Clock.system();

    /**
     * Create consumer with system clock and default performance config.
     */
    public EntityStreamConsumer(List<UpstreamConfig> upstreams,
                                AdaptiveRegionManager regionManager) {
        this(upstreams, regionManager, PerformanceConfig.defaults(), Clock.system());
    }

    /**
     * Create consumer with custom clock (for testing).
     */
    public EntityStreamConsumer(List<UpstreamConfig> upstreams,
                                AdaptiveRegionManager regionManager,
                                Clock clock) {
        this(upstreams, regionManager, PerformanceConfig.testing(), clock);
    }

    /**
     * Create consumer with full configuration (primary constructor).
     */
    public EntityStreamConsumer(List<UpstreamConfig> upstreams,
                                AdaptiveRegionManager regionManager,
                                PerformanceConfig performanceConfig,
                                Clock clock) {
        this(upstreams, regionManager, performanceConfig, clock,
             Executors.newSingleThreadScheduledExecutor(r -> {
                 var t = new Thread(r, "entity-stream-reconnect");
                 t.setDaemon(true);
                 return t;
             }));
    }

    /**
     * Full configuration with an injectable reconnect scheduler (Luciferase-0frcy.120).
     * <p>
     * Tests can pass a scheduler that runs scheduled tasks immediately (or a controllable
     * one) so reconnection / circuit-breaker timing is deterministic and does not require
     * real wall-clock waits.
     */
    public EntityStreamConsumer(List<UpstreamConfig> upstreams,
                                AdaptiveRegionManager regionManager,
                                PerformanceConfig performanceConfig,
                                Clock clock,
                                ScheduledExecutorService reconnectScheduler) {
        this.upstreams = upstreams;
        this.regionManager = regionManager;
        this.performanceConfig = performanceConfig;
        this.clock = clock;
        this.virtualThreadPool = Executors.newVirtualThreadPerTaskExecutor();
        this.reconnectScheduler = reconnectScheduler;

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
                                       .connectTimeout(Duration.ofSeconds(performanceConfig.httpConnectTimeoutSec()))
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

        // Defer the reconnect via the scheduler instead of parking a virtual thread on
        // Thread.sleep (Luciferase-0frcy.120). The actual connect runs on the virtual-thread
        // pool so blocking I/O stays off the single scheduler thread.
        reconnectScheduler.schedule(() -> {
            virtualThreadPool.submit(() -> {
                var upstreamConfig = upstreams.stream()
                    .filter(u -> u.uri().equals(upstream))
                    .findFirst()
                    .orElse(null);

                if (upstreamConfig != null) {
                    connect(upstreamConfig);
                }
            });
        }, backoffMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Schedule circuit breaker check after timeout.
     */
    private void scheduleCircuitBreakerCheck(URI upstream) {
        // Defer the circuit-breaker re-check via the scheduler instead of parking a
        // virtual thread on a 5-minute Thread.sleep (Luciferase-0frcy.120). No thread is
        // held for the timeout window, and tests can advance it via the injected scheduler.
        reconnectScheduler.schedule(() -> {
            if (running.get()) {
                reconnectWithBackoff(upstream);
            }
        }, CIRCUIT_BREAKER_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Handle incoming WebSocket message.
     * <p>
     * Parses JSON format: {"entities":[{"id":"e1","x":1.0,"y":2.0,"z":3.0,"type":"PREY"}],...}
     */
    // Package-private for regression testing of malformed-entity resilience
    // (Luciferase-0frcy.68).
    void onMessage(URI source, String json) {
        try {
            var upstreamLabel = upstreams.stream()
                .filter(u -> u.uri().equals(source))
                .map(UpstreamConfig::label)
                .findFirst()
                .orElse("unknown");

            var root = jsonMapper.readTree(json);
            var entitiesNode = root.get("entities");

            if (entitiesNode != null && entitiesNode.isArray()) {
                int processed = 0;
                int skipped = 0;
                for (JsonNode entityNode : entitiesNode) {
                    // Per-entity guard: a single malformed entity (missing/typed-
                    // wrong field) must not abort the whole batch. Using path()
                    // (never null) plus presence checks, and keeping the try/catch
                    // inside the loop, so one bad entity is skipped, not the rest
                    // of potentially hundreds of valid entities (Luciferase-0frcy.68).
                    try {
                        var idNode = entityNode.path("id");
                        var xNode = entityNode.path("x");
                        var yNode = entityNode.path("y");
                        var zNode = entityNode.path("z");
                        var typeNode = entityNode.path("type");

                        if (idNode.isMissingNode() || !idNode.isValueNode()
                            || !xNode.isNumber() || !yNode.isNumber() || !zNode.isNumber()
                            || typeNode.isMissingNode() || !typeNode.isValueNode()) {
                            skipped++;
                            log.warn("Skipping malformed entity from {}: {}", upstreamLabel, entityNode);
                            continue;
                        }

                        var id = idNode.asText();
                        var x = (float) xNode.asDouble();
                        var y = (float) yNode.asDouble();
                        var z = (float) zNode.asDouble();
                        var type = typeNode.asText();

                        // M4: Prefix entity ID with upstream label for multi-upstream support
                        var globalId = upstreamLabel + ":" + id;

                        regionManager.updateEntity(globalId, x, y, z, type);
                        processed++;
                    } catch (Exception perEntity) {
                        skipped++;
                        log.warn("Skipping entity from {} due to error: {}", upstreamLabel, perEntity.getMessage());
                    }
                }

                log.debug("Processed {} entities ({} skipped) from {}", processed, skipped, upstreamLabel);
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

        reconnectScheduler.shutdownNow();
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
