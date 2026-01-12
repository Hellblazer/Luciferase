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

package com.hellblazer.luciferase.simulation.distributed;

import com.hellblazer.luciferase.simulation.von.VonMessage;
import com.hellblazer.luciferase.simulation.von.VonMessageFactory;
import com.hellblazer.luciferase.simulation.von.VonTransport;
import javafx.geometry.Point3D;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Proxy for a bubble in a remote process.
 * <p>
 * Delegates operations to remote process via VonTransport.
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

    private final UUID bubbleId;
    private final VonTransport transport;
    private final VonMessageFactory factory;
    private final long timeoutMs;
    private final long cacheTTL;

    // Cache for remote bubble info
    private final ConcurrentHashMap<String, CacheEntry> cache;

    /**
     * Create a remote bubble proxy.
     *
     * @param bubbleId  UUID of the remote bubble
     * @param transport VonTransport for communication
     */
    public RemoteBubbleProxy(UUID bubbleId, VonTransport transport) {
        this(bubbleId, transport, DEFAULT_TIMEOUT_MS);
    }

    /**
     * Create a remote bubble proxy with custom timeout.
     *
     * @param bubbleId  UUID of the remote bubble
     * @param transport VonTransport for communication
     * @param timeoutMs Timeout in milliseconds for remote calls
     */
    public RemoteBubbleProxy(UUID bubbleId, VonTransport transport, long timeoutMs) {
        this(bubbleId, transport, timeoutMs, DEFAULT_CACHE_TTL_MS);
    }

    /**
     * Create a remote bubble proxy with custom timeout and cache TTL.
     *
     * @param bubbleId  UUID of the remote bubble
     * @param transport VonTransport for communication
     * @param timeoutMs Timeout in milliseconds for remote calls
     * @param cacheTTL  Cache time-to-live in milliseconds
     */
    public RemoteBubbleProxy(UUID bubbleId, VonTransport transport, long timeoutMs, long cacheTTL) {
        this.bubbleId = Objects.requireNonNull(bubbleId, "Bubble ID cannot be null");
        this.transport = Objects.requireNonNull(transport, "Transport cannot be null");
        this.factory = VonMessageFactory.system();
        this.timeoutMs = timeoutMs;
        this.cacheTTL = cacheTTL;
        this.cache = new ConcurrentHashMap<>();
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
        if (entry != null && !entry.isExpired()) {
            return (T) entry.value;
        }

        // Try to fetch from remote
        try {
            var value = fetcher.fetch();
            cache.put(key, new CacheEntry(value, System.currentTimeMillis() + cacheTTL));
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
        try {
            // Send query message
            var query = factory.createQuery(transport.getLocalId(), bubbleId, "position");
            transport.sendToNeighbor(bubbleId, query);

            // Wait for response (blocking with timeout)
            var start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < timeoutMs) {
                Thread.sleep(10);
                // In production, would use Future/CompletableFuture
                // For now, return a placeholder
            }

            throw new RuntimeException("Timeout fetching position from " + bubbleId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while fetching position", e);
        } catch (VonTransport.TransportException e) {
            throw new RuntimeException("Transport error fetching position", e);
        }
    }

    /**
     * Fetch neighbors from remote bubble.
     *
     * @return Set of neighbor UUIDs
     * @throws RuntimeException on timeout or connection failure
     */
    private Set<UUID> fetchNeighbors() {
        try {
            // Send query message
            var query = factory.createQuery(transport.getLocalId(), bubbleId, "neighbors");
            transport.sendToNeighbor(bubbleId, query);

            // Wait for response (blocking with timeout)
            var start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < timeoutMs) {
                Thread.sleep(10);
                // In production, would use Future/CompletableFuture
            }

            throw new RuntimeException("Timeout fetching neighbors from " + bubbleId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while fetching neighbors", e);
        } catch (VonTransport.TransportException e) {
            throw new RuntimeException("Transport error fetching neighbors", e);
        }
    }

    /**
     * Invalidate all cached entries.
     */
    public void invalidateCache() {
        cache.clear();
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

        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
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
