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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe idempotency token store with TTL (Time-To-Live).
 * <p>
 * Prevents duplicate migration operations by tracking previously seen tokens.
 * Each token has an expiration time (default 5 minutes) after which it can be
 * cleaned up to prevent unbounded memory growth.
 * <p>
 * Thread-Safety:
 * - Uses ConcurrentHashMap for lock-free token storage
 * - checkAndStore() is atomic using putIfAbsent()
 * - cleanup() can run concurrently with other operations
 * <p>
 * Example usage:
 * <pre>
 * var store = new IdempotencyStore();
 *
 * if (store.checkAndStore(token)) {
 *     // First time seeing this token - execute migration
 *     executeMigration(entity);
 * } else {
 *     // Duplicate token - skip migration
 *     log.debug("Skipping duplicate migration for token {}", token.toUUID());
 * }
 *
 * // Periodic cleanup (e.g., every 60 seconds)
 * store.cleanup();
 * </pre>
 *
 * @author hal.hildebrand
 */
public class IdempotencyStore {

    private static final Logger log                = LoggerFactory.getLogger(IdempotencyStore.class);
    private static final long   DEFAULT_TTL_MS     = 300_000; // 5 minutes
    private static final long   CLEANUP_BATCH_SIZE = 100;

    /**
     * Token entry with expiration timestamp.
     *
     * @param storedAt Timestamp when token was stored (milliseconds)
     */
    private record TokenEntry(long storedAt) {
        boolean isExpired(long ttlMs, long currentTime) {
            return (currentTime - storedAt) > ttlMs;
        }
    }

    /**
     * Statistics record for monitoring store performance.
     *
     * @param tokensStored       Number of unique tokens stored
     * @param duplicatesRejected Number of duplicate token attempts rejected
     * @param tokensExpired      Number of tokens expired and removed
     */
    public record IdempotencyStoreStats(long tokensStored, long duplicatesRejected, long tokensExpired) {
    }

    // Token storage: UUID -> TokenEntry
    private final ConcurrentHashMap<UUID, TokenEntry> tokens;

    // TTL in milliseconds
    private final long ttlMs;

    // Metrics
    private final AtomicLong tokensStored;
    private final AtomicLong duplicatesRejected;
    private final AtomicLong tokensExpired;

    /**
     * Create store with default TTL (5 minutes).
     */
    public IdempotencyStore() {
        this(DEFAULT_TTL_MS);
    }

    /**
     * Create store with custom TTL.
     *
     * @param ttlMs Time-to-live in milliseconds
     */
    public IdempotencyStore(long ttlMs) {
        this.tokens = new ConcurrentHashMap<>();
        this.ttlMs = ttlMs;
        this.tokensStored = new AtomicLong(0);
        this.duplicatesRejected = new AtomicLong(0);
        this.tokensExpired = new AtomicLong(0);
    }

    /**
     * Check if token is duplicate and store if new.
     * <p>
     * This is an atomic operation - only the first caller for a given token
     * will return true. All subsequent callers (duplicates) will return false.
     * <p>
     * Thread-safe: Uses ConcurrentHashMap.putIfAbsent() for atomicity.
     *
     * @param token Idempotency token to check and store
     * @return true if token was stored (first time seen), false if duplicate
     */
    public boolean checkAndStore(IdempotencyToken token) {
        var tokenId = token.toUUID();
        var entry = new TokenEntry(System.currentTimeMillis());

        // Atomic check-and-store: putIfAbsent returns null if key was absent
        var existing = tokens.putIfAbsent(tokenId, entry);

        if (existing == null) {
            // Token was new
            tokensStored.incrementAndGet();
            log.debug("Stored new idempotency token: {}", tokenId);
            return true;
        } else {
            // Token already exists (duplicate)
            duplicatesRejected.incrementAndGet();
            log.debug("Rejected duplicate idempotency token: {}", tokenId);
            return false;
        }
    }

    /**
     * Check if a token ID is known (already stored).
     * <p>
     * Does not modify the store - read-only check.
     *
     * @param tokenId Token UUID to check
     * @return true if token is in the store, false otherwise
     */
    public boolean isKnown(UUID tokenId) {
        return tokens.containsKey(tokenId);
    }

    /**
     * Remove expired tokens based on TTL.
     * <p>
     * Should be called periodically (e.g., every 60 seconds) to prevent
     * unbounded memory growth. Safe to call concurrently with other operations.
     * <p>
     * Thread-safe: Concurrent iteration and removal are safe with ConcurrentHashMap.
     *
     * @return Number of tokens removed
     */
    public void cleanup() {
        var currentTime = System.currentTimeMillis();
        var removed = 0;

        // Iterate and remove expired entries
        var iterator = tokens.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getValue().isExpired(ttlMs, currentTime)) {
                iterator.remove();
                removed++;
                tokensExpired.incrementAndGet();

                // Log in batches to avoid log spam
                if (removed % CLEANUP_BATCH_SIZE == 0) {
                    log.debug("Cleaned up {} expired idempotency tokens", removed);
                }
            }
        }

        if (removed > 0) {
            log.debug("Cleanup complete: removed {} expired tokens, {} tokens remain", removed, tokens.size());
        }
    }

    /**
     * Get current statistics.
     *
     * @return Snapshot of current statistics
     */
    public IdempotencyStoreStats getStats() {
        return new IdempotencyStoreStats(tokensStored.get(), duplicatesRejected.get(), tokensExpired.get());
    }

    /**
     * Get current token count (including expired but not yet cleaned).
     *
     * @return Number of tokens currently in store
     */
    public int size() {
        return tokens.size();
    }

    /**
     * Clear all tokens (primarily for testing).
     */
    public void clear() {
        tokens.clear();
        tokensStored.set(0);
        duplicatesRejected.set(0);
        tokensExpired.set(0);
        log.debug("IdempotencyStore cleared");
    }
}
