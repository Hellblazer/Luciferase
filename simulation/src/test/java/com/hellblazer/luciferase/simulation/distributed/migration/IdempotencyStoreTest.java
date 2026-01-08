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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for IdempotencyStore.
 * <p>
 * Verifies:
 * - First token accepted, duplicates rejected
 * - Different entities can use same token
 * - TTL expiration (5 minute default)
 * - Cleanup removes expired entries
 * - Thread-safety under concurrent access
 * - Metrics tracking
 *
 * @author hal.hildebrand
 */
class IdempotencyStoreTest {

    private IdempotencyStore store;

    @BeforeEach
    void setUp() {
        store = new IdempotencyStore();
    }

    @Test
    void testFirstTokenAccepted() {
        var token = new IdempotencyToken(
            "entity-1",
            UUID.randomUUID(),
            UUID.randomUUID(),
            System.currentTimeMillis(),
            UUID.randomUUID()
        );

        // First attempt should succeed
        assertTrue(store.checkAndStore(token));

        // Token should be known
        assertTrue(store.isKnown(token.toUUID()));
    }

    @Test
    void testDuplicateTokenRejected() {
        var token = new IdempotencyToken(
            "entity-1",
            UUID.randomUUID(),
            UUID.randomUUID(),
            System.currentTimeMillis(),
            UUID.randomUUID()
        );

        // First attempt succeeds
        assertTrue(store.checkAndStore(token));

        // Second attempt with same token should fail
        assertFalse(store.checkAndStore(token));

        // Stats should reflect duplicate rejection
        var stats = store.getStats();
        assertEquals(1, stats.tokensStored());
        assertEquals(1, stats.duplicatesRejected());
    }

    @Test
    void testDifferentEntitiesSameTokenComponents() {
        var sourceProcess = UUID.randomUUID();
        var destProcess = UUID.randomUUID();
        var timestamp = System.currentTimeMillis();
        var nonce = UUID.randomUUID();

        var token1 = new IdempotencyToken("entity-1", sourceProcess, destProcess, timestamp, nonce);
        var token2 = new IdempotencyToken("entity-2", sourceProcess, destProcess, timestamp, nonce);

        // Both should succeed (different entity IDs = different tokens)
        assertTrue(store.checkAndStore(token1));
        assertTrue(store.checkAndStore(token2));

        // Both should be known
        assertTrue(store.isKnown(token1.toUUID()));
        assertTrue(store.isKnown(token2.toUUID()));

        // Stats should show 2 tokens stored
        assertEquals(2, store.getStats().tokensStored());
    }

    @Test
    void testTTLExpiration() throws InterruptedException {
        // Create store with 100ms TTL for testing
        var shortTTLStore = new IdempotencyStore(100);

        var token = new IdempotencyToken(
            "entity-1",
            UUID.randomUUID(),
            UUID.randomUUID(),
            System.currentTimeMillis(),
            UUID.randomUUID()
        );

        // Store token
        assertTrue(shortTTLStore.checkAndStore(token));
        assertTrue(shortTTLStore.isKnown(token.toUUID()));

        // Wait for TTL to expire
        Thread.sleep(150);

        // Cleanup should remove expired token
        shortTTLStore.cleanup();

        // Token should no longer be known
        assertFalse(shortTTLStore.isKnown(token.toUUID()));

        // Stats should reflect expiration
        var stats = shortTTLStore.getStats();
        assertEquals(1, stats.tokensExpired());
    }

    @Test
    void testCleanupRemovesExpiredEntries() throws InterruptedException {
        var shortTTLStore = new IdempotencyStore(50);

        // Add multiple tokens
        var tokens = new ArrayList<IdempotencyToken>();
        for (int i = 0; i < 10; i++) {
            var token = new IdempotencyToken(
                "entity-" + i,
                UUID.randomUUID(),
                UUID.randomUUID(),
                System.currentTimeMillis(),
                UUID.randomUUID()
            );
            tokens.add(token);
            assertTrue(shortTTLStore.checkAndStore(token));
        }

        // All should be known
        assertEquals(10, shortTTLStore.getStats().tokensStored());

        // Wait for expiration
        Thread.sleep(100);

        // Add a fresh token after expiration
        var freshToken = new IdempotencyToken(
            "fresh-entity",
            UUID.randomUUID(),
            UUID.randomUUID(),
            System.currentTimeMillis(),
            UUID.randomUUID()
        );
        assertTrue(shortTTLStore.checkAndStore(freshToken));

        // Cleanup
        shortTTLStore.cleanup();

        // Old tokens should be gone
        for (var token : tokens) {
            assertFalse(shortTTLStore.isKnown(token.toUUID()));
        }

        // Fresh token should remain
        assertTrue(shortTTLStore.isKnown(freshToken.toUUID()));

        // Stats should reflect cleanup
        var stats = shortTTLStore.getStats();
        assertEquals(10, stats.tokensExpired());
    }

    @Test
    void testConcurrentInsertionsSameToken() throws InterruptedException, ExecutionException {
        var token = new IdempotencyToken(
            "entity-1",
            UUID.randomUUID(),
            UUID.randomUUID(),
            System.currentTimeMillis(),
            UUID.randomUUID()
        );

        var executor = Executors.newFixedThreadPool(10);
        var successCount = new AtomicInteger(0);

        var futures = new ArrayList<Future<?>>();

        // 100 threads try to insert same token concurrently
        for (int i = 0; i < 100; i++) {
            futures.add(executor.submit(() -> {
                if (store.checkAndStore(token)) {
                    successCount.incrementAndGet();
                }
            }));
        }

        // Wait for all threads
        for (var future : futures) {
            future.get();
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        // Exactly ONE should have succeeded
        assertEquals(1, successCount.get());

        // Stats should reflect one success and 99 duplicates
        var stats = store.getStats();
        assertEquals(1, stats.tokensStored());
        assertEquals(99, stats.duplicatesRejected());
    }

    @Test
    void testConcurrentInsertionsDifferentTokens() throws InterruptedException, ExecutionException {
        var executor = Executors.newFixedThreadPool(10);
        var successCount = new AtomicInteger(0);

        var futures = new ArrayList<Future<?>>();

        // 100 threads insert different tokens concurrently
        for (int i = 0; i < 100; i++) {
            final int entityNum = i;
            futures.add(executor.submit(() -> {
                var token = new IdempotencyToken(
                    "entity-" + entityNum,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    System.currentTimeMillis(),
                    UUID.randomUUID()
                );
                if (store.checkAndStore(token)) {
                    successCount.incrementAndGet();
                }
            }));
        }

        // Wait for all threads
        for (var future : futures) {
            future.get();
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        // All 100 should succeed (different tokens)
        assertEquals(100, successCount.get());

        // Stats should reflect 100 successful insertions
        var stats = store.getStats();
        assertEquals(100, stats.tokensStored());
        assertEquals(0, stats.duplicatesRejected());
    }

    @Test
    void testStatsTracking() {
        // Initially empty
        var initialStats = store.getStats();
        assertEquals(0, initialStats.tokensStored());
        assertEquals(0, initialStats.duplicatesRejected());
        assertEquals(0, initialStats.tokensExpired());

        // Add token
        var token1 = new IdempotencyToken(
            "entity-1",
            UUID.randomUUID(),
            UUID.randomUUID(),
            System.currentTimeMillis(),
            UUID.randomUUID()
        );
        assertTrue(store.checkAndStore(token1));

        var afterFirstStats = store.getStats();
        assertEquals(1, afterFirstStats.tokensStored());

        // Try duplicate
        assertFalse(store.checkAndStore(token1));

        var afterDuplicateStats = store.getStats();
        assertEquals(1, afterDuplicateStats.tokensStored());
        assertEquals(1, afterDuplicateStats.duplicatesRejected());

        // Add another unique token
        var token2 = new IdempotencyToken(
            "entity-2",
            UUID.randomUUID(),
            UUID.randomUUID(),
            System.currentTimeMillis(),
            UUID.randomUUID()
        );
        assertTrue(store.checkAndStore(token2));

        var finalStats = store.getStats();
        assertEquals(2, finalStats.tokensStored());
        assertEquals(1, finalStats.duplicatesRejected());
    }
}
