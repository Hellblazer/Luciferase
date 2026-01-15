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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Queries neighbors across process boundaries with caching.
 * <p>
 * Responsibilities:
 * - Query neighbors from tetrahedral topology
 * - Cache results with TTL (default: 5 seconds)
 * - Lazy evaluation (resolve on-demand)
 * - Per-BubbleId result caching
 * - Cache invalidation on topology changes
 * <p>
 * Thread Safety:
 * - ConcurrentHashMap for cache storage
 * - AtomicLong for cache stats
 * - No blocking operations
 * <p>
 * Performance:
 * - Cached lookup: <1ms
 * - Uncached lookup: <100ms
 * - Cache hit rate target: >80%
 * <p>
 * Architecture Decision D6B.3: Neighbor Lookups
 *
 * @author hal.hildebrand
 */
public class CrossProcessNeighborIndex {

    private static final Logger log = LoggerFactory.getLogger(CrossProcessNeighborIndex.class);
    private static final long DEFAULT_TTL_MS = 5000; // 5 seconds

    private final ProcessRegistry registry;
    private final long cacheTTL;
    private final ConcurrentHashMap<UUID, CacheEntry> cache;

    // Cache statistics
    private final AtomicLong cacheHits;
    private final AtomicLong cacheMisses;
    private final AtomicLong cacheEvictions;

    /**
     * Create a CrossProcessNeighborIndex.
     *
     * @param registry ProcessRegistry for topology queries
     */
    public CrossProcessNeighborIndex(ProcessRegistry registry) {
        this(registry, DEFAULT_TTL_MS);
    }

    /**
     * Create a CrossProcessNeighborIndex with custom TTL.
     *
     * @param registry ProcessRegistry for topology queries
     * @param cacheTTL Cache time-to-live in milliseconds
     */
    public CrossProcessNeighborIndex(ProcessRegistry registry, long cacheTTL) {
        this.registry = Objects.requireNonNull(registry, "ProcessRegistry cannot be null");
        this.cacheTTL = cacheTTL;
        this.cache = new ConcurrentHashMap<>();
        this.cacheHits = new AtomicLong(0);
        this.cacheMisses = new AtomicLong(0);
        this.cacheEvictions = new AtomicLong(0);
    }

    /**
     * Get neighbors for a bubble.
     * <p>
     * Uses cached results if available and not expired.
     * Otherwise, queries topology and caches the result.
     *
     * @param bubble BubbleReference to find neighbors for
     * @return Set of BubbleReferences (never null, may be empty)
     */
    public Set<BubbleReference> getNeighbors(BubbleReference bubble) {
        Objects.requireNonNull(bubble, "Bubble cannot be null");

        var bubbleId = bubble.getBubbleId();
        var entry = cache.get(bubbleId);

        // Check cache validity
        if (entry != null && !entry.isExpired()) {
            cacheHits.incrementAndGet();
            return entry.neighbors;
        }

        // Cache miss - resolve neighbors
        cacheMisses.incrementAndGet();
        var neighbors = resolveNeighbors(bubble);

        // Cache the result
        cache.put(bubbleId, new CacheEntry(neighbors, System.currentTimeMillis() + cacheTTL));

        return neighbors;
    }

    /**
     * Invalidate cache for a specific bubble.
     * <p>
     * Called when topology changes affect this bubble's neighbors.
     *
     * @param bubbleId UUID of bubble to invalidate
     */
    public void invalidateCache(UUID bubbleId) {
        Objects.requireNonNull(bubbleId, "Bubble ID cannot be null");

        var removed = cache.remove(bubbleId);
        if (removed != null) {
            cacheEvictions.incrementAndGet();
            log.trace("Invalidated cache for bubble {}", bubbleId);
        }
    }

    /**
     * Get cache statistics.
     *
     * @return CacheStats record with hits, misses, evictions
     */
    public CacheStats getCacheStats() {
        return new CacheStats(
            cacheHits.get(),
            cacheMisses.get(),
            cacheEvictions.get()
        );
    }

    /**
     * Resolve neighbors from topology.
     * <p>
     * For local bubbles: use VonBubble.neighbors()
     * For remote bubbles: query via RemoteBubbleProxy
     *
     * @param bubble BubbleReference to resolve neighbors for
     * @return Set of BubbleReferences
     */
    private Set<BubbleReference> resolveNeighbors(BubbleReference bubble) {
        try {
            if (bubble.isLocal()) {
                // Local bubble - get neighbors directly
                var localBubble = bubble.asLocal().getBubble();
                var neighborIds = localBubble.neighbors();

                // Convert UUIDs to BubbleReferences
                var references = ConcurrentHashMap.<BubbleReference>newKeySet();
                for (var neighborId : neighborIds) {
                    // Check if neighbor is local or remote
                    var processId = registry.findProcess(neighborId);
                    if (processId != null) {
                        // Remote neighbor - create proxy
                        var proxy = new RemoteBubbleProxy(neighborId, localBubble.getTransport());
                        references.add(proxy);
                    }
                }
                return references;
            } else {
                // Remote bubble - query via proxy
                var proxy = bubble.asRemote();
                var neighborIds = proxy.getNeighbors();

                // Convert UUIDs to BubbleReferences
                var references = ConcurrentHashMap.<BubbleReference>newKeySet();
                // For remote bubbles, we can't easily get their transport
                // In a real implementation, would need ProcessRegistry to provide transport routing
                // For now, return empty set for remote-to-remote neighbor resolution
                return references;
            }
        } catch (Exception e) {
            log.warn("Failed to resolve neighbors for bubble {}: {}", bubble.getBubbleId(), e.getMessage());
            return Set.of(); // Empty set on error
        }
    }

    /**
     * Cache entry with expiration.
     */
    private static class CacheEntry {
        final Set<BubbleReference> neighbors;
        final long expiresAt;

        CacheEntry(Set<BubbleReference> neighbors, long expiresAt) {
            this.neighbors = neighbors;
            this.expiresAt = expiresAt;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    /**
     * Cache statistics record.
     *
     * @param hits      Number of cache hits
     * @param misses    Number of cache misses
     * @param evictions Number of cache evictions
     */
    public record CacheStats(long hits, long misses, long evictions) {
    }
}
