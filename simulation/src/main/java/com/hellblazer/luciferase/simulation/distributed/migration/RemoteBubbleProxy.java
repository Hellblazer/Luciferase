/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
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

package com.hellblazer.luciferase.simulation.distributed.migration;

import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import com.hellblazer.luciferase.simulation.von.Message;
import com.hellblazer.luciferase.simulation.von.MessageFactory;
import com.hellblazer.luciferase.simulation.von.Transport;
import javafx.geometry.Point3D;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Proxy for a bubble in a remote process.
 * <p>
 * Delegates operations to remote process via Transport.
 * Manages connection failures and timeouts gracefully.
 * <p>
 * Features:
 * - Remote method invocation via transport
 * - Timeout handling (default: 5000ms)
 * - Response caching with TTL (default: 10 seconds)
 * - Fallback to stale cache on connection failure
 * - Thread-safe concurrent access
 *
 * @author hal.hildebrand
 */
public class RemoteBubbleProxy implements BubbleReference {

    private static final Logger log = LoggerFactory.getLogger(RemoteBubbleProxy.class);
    private static final long DEFAULT_TIMEOUT_MS = 5000;
    private static final long DEFAULT_CACHE_TTL_MS = 10000;

    private volatile Clock clock = Clock.system();

    private final UUID bubbleId;
    private final Transport transport;
    private volatile MessageFactory factory;
    private final long timeoutMs;
    private final long cacheTTL;

    // Cache for remote bubble info
    private final ConcurrentHashMap<String, CacheEntry> cache;

    // Query correlation: queryId -> CompletableFuture<QueryResponse>
    private final ConcurrentHashMap<UUID, CompletableFuture<Message.QueryResponse>> pendingQueries;

    /**
     * Set the clock source for deterministic testing.
     */
    public void setClock(Clock clock) {
        this.clock = clock;
        this.factory = new MessageFactory(clock);
    }

    /**
     * Create a remote bubble proxy.
     *
     * @param bubbleId  UUID of the remote bubble
     * @param transport Transport for communication
     */
    public RemoteBubbleProxy(UUID bubbleId, Transport transport) {
        this(bubbleId, transport, DEFAULT_TIMEOUT_MS);
    }

    /**
     * Create a remote bubble proxy with custom timeout.
     *
     * @param bubbleId  UUID of the remote bubble
     * @param transport Transport for communication
     * @param timeoutMs Timeout in milliseconds for remote calls
     */
    public RemoteBubbleProxy(UUID bubbleId, Transport transport, long timeoutMs) {
        this(bubbleId, transport, timeoutMs, DEFAULT_CACHE_TTL_MS);
    }

    /**
     * Create a remote bubble proxy with custom timeout and cache TTL.
     *
     * @param bubbleId  UUID of the remote bubble
     * @param transport Transport for communication
     * @param timeoutMs Timeout in milliseconds for remote calls
     * @param cacheTTL  Cache time-to-live in milliseconds
     */
    public RemoteBubbleProxy(UUID bubbleId, Transport transport, long timeoutMs, long cacheTTL) {
        this.bubbleId = Objects.requireNonNull(bubbleId, "Bubble ID cannot be null");
        this.transport = Objects.requireNonNull(transport, "Transport cannot be null");
        this.factory = new MessageFactory(clock);
        this.timeoutMs = timeoutMs;
        this.cacheTTL = cacheTTL;
        this.cache = new ConcurrentHashMap<>();
        this.pendingQueries = new ConcurrentHashMap<>();

        // Register handler for QueryResponse messages
        transport.onMessage(this::handleMessage);
    }

    @Override
    public boolean isLocal() {
        return false;
    }

    @Override
    public LocalBubbleReference asLocal() {
        throw new IllegalStateException("Cannot cast RemoteBubbleProxy to LocalBubbleReference");
    }

    @Override
    public RemoteBubbleProxy asRemote() {
        return this;
    }

    @Override
    public UUID getBubbleId() {
        return bubbleId;
    }

    @Override
    public Point3D getPosition() {
        return getCachedOrFetch("position", this::fetchPosition, new Point3D(0, 0, 0));
    }

    @Override
    public Set<UUID> getNeighbors() {
        return getCachedOrFetch("neighbors", this::fetchNeighbors, Set.of());
    }

    /**
     * Get cached value or fetch from remote.
     *
     * @param key          Cache key
     * @param fetcher      Function to fetch from remote
     * @param defaultValue Default value on failure
     * @param <T>          Value type
     * @return Cached or fetched value
     */
    @SuppressWarnings("unchecked")
    private <T> T getCachedOrFetch(String key, FetchFunction<T> fetcher, T defaultValue) {
        var entry = cache.get(key);

        // Check cache validity
        if (entry != null && !entry.isExpired(clock)) {
            return (T) entry.value;
        }

        // Try to fetch from remote
        try {
            var value = fetcher.fetch();
            cache.put(key, new CacheEntry(value, clock.currentTimeMillis() + cacheTTL));
            return value;
        } catch (Exception e) {
            log.warn("Failed to fetch {} from remote bubble {}: {}", key, bubbleId, e.getMessage());

            // Fall back to stale cache if available
            if (entry != null) {
                log.debug("Falling back to stale cache for {} (bubble: {})", key, bubbleId);
                return (T) entry.value;
            }

            // No cache available, return default
            return defaultValue;
        }
    }

    /**
     * Fetch position from remote bubble.
     *
     * @return Point3D position
     * @throws RuntimeException on timeout or connection failure
     */
    private Point3D fetchPosition() {
        // Send query message
        var query = factory.createQuery(transport.getLocalId(), bubbleId, "position");
        var future = new CompletableFuture<Message.QueryResponse>();
        pendingQueries.put(query.queryId(), future);

        try {
            transport.sendToNeighbor(bubbleId, query);

            // Wait for response with timeout
            var response = future.get(timeoutMs, TimeUnit.MILLISECONDS);

            // Parse position from response data (JSON format: "x,y,z")
            var parts = response.responseData().split(",");
            return new Point3D(
                Double.parseDouble(parts[0]),
                Double.parseDouble(parts[1]),
                Double.parseDouble(parts[2])
            );
        } catch (TimeoutException e) {
            pendingQueries.remove(query.queryId());  // Clean up on timeout
            throw new RuntimeException("Timeout fetching position from " + bubbleId, e);
        } catch (InterruptedException e) {
            pendingQueries.remove(query.queryId());  // Clean up on interrupt
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while fetching position", e);
        } catch (Exception e) {
            pendingQueries.remove(query.queryId());  // Clean up on error
            throw new RuntimeException("Error fetching position from " + bubbleId, e);
        }
    }

    /**
     * Fetch neighbors from remote bubble.
     *
     * @return Set of neighbor UUIDs
     * @throws RuntimeException on timeout or connection failure
     */
    private Set<UUID> fetchNeighbors() {
        // Send query message
        var query = factory.createQuery(transport.getLocalId(), bubbleId, "neighbors");
        var future = new CompletableFuture<Message.QueryResponse>();
        pendingQueries.put(query.queryId(), future);

        try {
            transport.sendToNeighbor(bubbleId, query);

            // Wait for response with timeout
            var response = future.get(timeoutMs, TimeUnit.MILLISECONDS);

            // Parse neighbors from response data (JSON format: "uuid1,uuid2,uuid3,...")
            if (response.responseData().isEmpty()) {
                return Set.of();
            }

            var parts = response.responseData().split(",");
            var neighbors = new java.util.HashSet<UUID>();
            for (var part : parts) {
                neighbors.add(UUID.fromString(part.trim()));
            }
            return neighbors;
        } catch (TimeoutException e) {
            pendingQueries.remove(query.queryId());  // Clean up on timeout
            throw new RuntimeException("Timeout fetching neighbors from " + bubbleId, e);
        } catch (InterruptedException e) {
            pendingQueries.remove(query.queryId());  // Clean up on interrupt
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while fetching neighbors", e);
        } catch (Exception e) {
            pendingQueries.remove(query.queryId());  // Clean up on error
            throw new RuntimeException("Error fetching neighbors from " + bubbleId, e);
        }
    }

    /**
     * Invalidate all cached entries.
     */
    public void invalidateCache() {
        cache.clear();
    }

    /**
     * Handle incoming messages from transport.
     * <p>
     * Completes pending QueryResponse futures for query correlation.
     *
     * @param message Incoming message
     */
    private void handleMessage(Message message) {
        if (message instanceof Message.QueryResponse queryResponse) {
            var future = pendingQueries.remove(queryResponse.queryId());
            if (future != null) {
                future.complete(queryResponse);
            } else {
                log.warn("Received QueryResponse for unknown queryId: {}", queryResponse.queryId());
            }
        }
        // Ignore other message types - they're handled elsewhere
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        var that = (RemoteBubbleProxy) o;
        return Objects.equals(bubbleId, that.bubbleId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(bubbleId);
    }

    @Override
    public String toString() {
        return "RemoteBubbleProxy{bubbleId=" + bubbleId + "}";
    }

    /**
     * Cache entry with expiration.
     */
    private static class CacheEntry {
        final Object value;
        final long expiresAt;

        CacheEntry(Object value, long expiresAt) {
            this.value = value;
            this.expiresAt = expiresAt;
        }

        boolean isExpired(Clock clock) {
            return clock.currentTimeMillis() > expiresAt;
        }
    }

    /**
     * Functional interface for fetching values.
     */
    @FunctionalInterface
    private interface FetchFunction<T> {
        T fetch() throws Exception;
    }
}
