package com.hellblazer.luciferase.simulation.ghost;

import com.hellblazer.luciferase.simulation.ghost.*;

import com.hellblazer.luciferase.simulation.bubble.*;

import com.hellblazer.luciferase.lucien.entity.EntityID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ConcurrentModificationException;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MigrationLog - idempotency token tracking for entity migrations.
 * <p>
 * MigrationLog prevents duplicate entity migrations using idempotency tokens:
 * - Record migrations with unique tokens
 * - Detect duplicate migration attempts
 * - Track migration history per entity
 * - Support cleanup of old migration records
 * <p>
 * Idempotency guarantees:
 * - Same token = no-op (migration already applied)
 * - Different token for same entity = allowed (new migration)
 * - Token collision across different entities = independent
 * <p>
 * Use case: Network partition causes duplicate migration messages
 * - First message applied normally
 * - Duplicate message rejected (same token)
 * - No entity duplication or loss
 *
 * @author hal.hildebrand
 */
class MigrationLogTest {

    // Simple EntityID for testing
    static class TestEntityID implements EntityID {
        private final String id;

        TestEntityID(String id) {
            this.id = id;
        }

        @Override
        public String toDebugString() {
            return id;
        }

        @Override
        public int compareTo(EntityID other) {
            return id.compareTo(other.toDebugString());
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TestEntityID that)) return false;
            return id.equals(that.id);
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }
    }

    private MigrationLog migrationLog;

    @BeforeEach
    void setUp() {
        migrationLog = new MigrationLog();
    }

    @Test
    void testInitialState() {
        assertEquals(0, migrationLog.getMigrationCount(),
                    "Initially no migrations recorded");
        assertEquals(0, migrationLog.getUniqueEntityCount(),
                    "Initially no entities tracked");
    }

    @Test
    void testRecordFirstMigration() {
        var entityId = new TestEntityID("entity-1");
        var token = UUID.randomUUID();
        var sourceBubble = UUID.randomUUID();
        var targetBubble = UUID.randomUUID();

        boolean recorded = migrationLog.recordMigration(
            entityId, token, sourceBubble, targetBubble, 100L
        );

        assertTrue(recorded, "First migration should be recorded");
        assertEquals(1, migrationLog.getMigrationCount());
        assertEquals(1, migrationLog.getUniqueEntityCount());
    }

    @Test
    void testDuplicateTokenRejected() {
        var entityId = new TestEntityID("entity-1");
        var token = UUID.randomUUID();
        var sourceBubble = UUID.randomUUID();
        var targetBubble = UUID.randomUUID();

        // First migration
        assertTrue(migrationLog.recordMigration(
            entityId, token, sourceBubble, targetBubble, 100L
        ));

        // Duplicate token - should be rejected
        boolean duplicate = migrationLog.recordMigration(
            entityId, token, sourceBubble, targetBubble, 100L
        );

        assertFalse(duplicate,
                   "Duplicate token should be rejected (idempotency)");
        assertEquals(1, migrationLog.getMigrationCount(),
                    "Migration count should not increase");
    }

    @Test
    void testDifferentTokenForSameEntity() {
        var entityId = new TestEntityID("entity-1");
        var token1 = UUID.randomUUID();
        var token2 = UUID.randomUUID();
        var sourceBubble1 = UUID.randomUUID();
        var targetBubble1 = UUID.randomUUID();
        var sourceBubble2 = UUID.randomUUID();
        var targetBubble2 = UUID.randomUUID();

        // First migration
        assertTrue(migrationLog.recordMigration(
            entityId, token1, sourceBubble1, targetBubble1, 100L
        ));

        // Second migration with different token (entity moved again)
        assertTrue(migrationLog.recordMigration(
            entityId, token2, sourceBubble2, targetBubble2, 200L
        ));

        assertEquals(2, migrationLog.getMigrationCount(),
                    "Different tokens should both be recorded");
        assertEquals(1, migrationLog.getUniqueEntityCount(),
                    "Still only one unique entity");
    }

    @Test
    void testSameTokenDifferentEntities() {
        var entity1 = new TestEntityID("entity-1");
        var entity2 = new TestEntityID("entity-2");
        var token = UUID.randomUUID();  // Same token
        var sourceBubble = UUID.randomUUID();
        var targetBubble = UUID.randomUUID();

        // Migration for entity 1
        assertTrue(migrationLog.recordMigration(
            entity1, token, sourceBubble, targetBubble, 100L
        ));

        // Same token but different entity - should be independent
        assertTrue(migrationLog.recordMigration(
            entity2, token, sourceBubble, targetBubble, 100L
        ));

        assertEquals(2, migrationLog.getMigrationCount(),
                    "Same token for different entities should both record");
        assertEquals(2, migrationLog.getUniqueEntityCount());
    }

    @Test
    void testIsDuplicateCheck() {
        var entityId = new TestEntityID("entity-1");
        var token = UUID.randomUUID();
        var sourceBubble = UUID.randomUUID();
        var targetBubble = UUID.randomUUID();

        assertFalse(migrationLog.isDuplicate(entityId, token),
                   "Token not seen before should not be duplicate");

        migrationLog.recordMigration(
            entityId, token, sourceBubble, targetBubble, 100L
        );

        assertTrue(migrationLog.isDuplicate(entityId, token),
                  "Token seen before should be duplicate");
    }

    @Test
    void testGetMigrationHistory() {
        var entityId = new TestEntityID("entity-1");
        var token1 = UUID.randomUUID();
        var token2 = UUID.randomUUID();
        var sourceBubble1 = UUID.randomUUID();
        var targetBubble1 = UUID.randomUUID();
        var sourceBubble2 = UUID.randomUUID();
        var targetBubble2 = UUID.randomUUID();

        // No history yet
        var history = migrationLog.getMigrationHistory(entityId);
        assertTrue(history.isEmpty(), "No history for unmigrated entity");

        // Record 2 migrations
        migrationLog.recordMigration(
            entityId, token1, sourceBubble1, targetBubble1, 100L
        );
        migrationLog.recordMigration(
            entityId, token2, sourceBubble2, targetBubble2, 200L
        );

        history = migrationLog.getMigrationHistory(entityId);
        assertEquals(2, history.size(), "Should have 2 migration records");

        // Verify order (chronological by bucket)
        assertEquals(100L, history.get(0).bucket());
        assertEquals(200L, history.get(1).bucket());
        assertEquals(token1, history.get(0).token());
        assertEquals(token2, history.get(1).token());
    }

    @Test
    void testGetLatestMigration() {
        var entityId = new TestEntityID("entity-1");
        var token1 = UUID.randomUUID();
        var token2 = UUID.randomUUID();
        var sourceBubble1 = UUID.randomUUID();
        var targetBubble1 = UUID.randomUUID();
        var sourceBubble2 = UUID.randomUUID();
        var targetBubble2 = UUID.randomUUID();

        // No migrations yet
        var latest = migrationLog.getLatestMigration(entityId);
        assertTrue(latest.isEmpty(), "No latest migration for unmigrated entity");

        // Record 2 migrations
        migrationLog.recordMigration(
            entityId, token1, sourceBubble1, targetBubble1, 100L
        );
        migrationLog.recordMigration(
            entityId, token2, sourceBubble2, targetBubble2, 200L
        );

        latest = migrationLog.getLatestMigration(entityId);
        assertTrue(latest.isPresent(), "Should have latest migration");
        assertEquals(token2, latest.get().token(),
                    "Latest should be most recent migration");
        assertEquals(200L, latest.get().bucket());
    }

    @Test
    void testCleanupOldMigrations() {
        var entityId = new TestEntityID("entity-1");
        var token1 = UUID.randomUUID();
        var token2 = UUID.randomUUID();
        var token3 = UUID.randomUUID();
        var sourceBubble = UUID.randomUUID();
        var targetBubble = UUID.randomUUID();

        // Record 3 migrations at different times
        migrationLog.recordMigration(
            entityId, token1, sourceBubble, targetBubble, 100L
        );
        migrationLog.recordMigration(
            entityId, token2, sourceBubble, targetBubble, 200L
        );
        migrationLog.recordMigration(
            entityId, token3, sourceBubble, targetBubble, 300L
        );

        assertEquals(3, migrationLog.getMigrationCount());

        // Cleanup migrations before bucket 250
        int cleaned = migrationLog.cleanupBefore(250L);

        assertEquals(2, cleaned, "Should cleanup 2 old migrations");
        assertEquals(1, migrationLog.getMigrationCount(),
                    "Only most recent migration remains");

        var history = migrationLog.getMigrationHistory(entityId);
        assertEquals(1, history.size());
        assertEquals(token3, history.get(0).token(),
                    "Only bucket 300 migration should remain");
    }

    @Test
    void testCleanupPreservesLatest() {
        var entity1 = new TestEntityID("entity-1");
        var entity2 = new TestEntityID("entity-2");
        var token1 = UUID.randomUUID();
        var token2 = UUID.randomUUID();
        var sourceBubble = UUID.randomUUID();
        var targetBubble = UUID.randomUUID();

        // Entity 1: Old migration
        migrationLog.recordMigration(
            entity1, token1, sourceBubble, targetBubble, 100L
        );

        // Entity 2: Recent migration
        migrationLog.recordMigration(
            entity2, token2, sourceBubble, targetBubble, 300L
        );

        // Cleanup before 250
        migrationLog.cleanupBefore(250L);

        // Entity 1 should be removed, entity 2 preserved
        assertTrue(migrationLog.getMigrationHistory(entity1).isEmpty(),
                  "Old entity migration should be cleaned");
        assertFalse(migrationLog.getMigrationHistory(entity2).isEmpty(),
                   "Recent entity migration should be preserved");
    }

    @Test
    void testClearAllMigrations() {
        var entityId = new TestEntityID("entity-1");
        var token = UUID.randomUUID();
        var sourceBubble = UUID.randomUUID();
        var targetBubble = UUID.randomUUID();

        migrationLog.recordMigration(
            entityId, token, sourceBubble, targetBubble, 100L
        );

        assertEquals(1, migrationLog.getMigrationCount());

        migrationLog.clear();

        assertEquals(0, migrationLog.getMigrationCount(),
                    "Clear should remove all migrations");
        assertEquals(0, migrationLog.getUniqueEntityCount());
    }

    @Test
    void testMigrationRecordImmutability() {
        var entityId = new TestEntityID("entity-1");
        var token = UUID.randomUUID();
        var sourceBubble = UUID.randomUUID();
        var targetBubble = UUID.randomUUID();

        migrationLog.recordMigration(
            entityId, token, sourceBubble, targetBubble, 100L
        );

        var history = migrationLog.getMigrationHistory(entityId);

        // Records should be immutable (no setters, final fields)
        var record = history.get(0);
        assertEquals(token, record.token());
        assertEquals(sourceBubble, record.sourceBubble());
        assertEquals(targetBubble, record.targetBubble());
        assertEquals(100L, record.bucket());
    }

    @Test
    void testConcurrentMigrationRecording() throws InterruptedException {
        int threadCount = 10;
        int migrationsPerThread = 100;

        var threads = new Thread[threadCount];
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            threads[t] = new Thread(() -> {
                for (int i = 0; i < migrationsPerThread; i++) {
                    var entityId = new TestEntityID("t" + threadId + "-e" + i);
                    var token = UUID.randomUUID();
                    var sourceBubble = UUID.randomUUID();
                    var targetBubble = UUID.randomUUID();

                    migrationLog.recordMigration(
                        entityId, token, sourceBubble, targetBubble, 100L + i
                    );
                }
            });
            threads[t].start();
        }

        for (var thread : threads) {
            thread.join();
        }

        assertEquals(1000, migrationLog.getMigrationCount(),
                    "All 1000 migrations should be recorded (thread-safe)");
        assertEquals(1000, migrationLog.getUniqueEntityCount(),
                    "All entities unique in this test");
    }

    @Test
    void testDuplicateDetectionUnderConcurrency() throws InterruptedException {
        var entityId = new TestEntityID("entity-1");
        var token = UUID.randomUUID();
        var sourceBubble = UUID.randomUUID();
        var targetBubble = UUID.randomUUID();

        int threadCount = 10;
        var recordedCount = new java.util.concurrent.atomic.AtomicInteger(0);

        var threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                // All threads try same migration (same token)
                boolean recorded = migrationLog.recordMigration(
                    entityId, token, sourceBubble, targetBubble, 100L
                );
                if (recorded) {
                    recordedCount.incrementAndGet();
                }
            });
            threads[i].start();
        }

        for (var thread : threads) {
            thread.join();
        }

        assertEquals(1, recordedCount.get(),
                    "Only one thread should successfully record (idempotency)");
        assertEquals(1, migrationLog.getMigrationCount(),
                    "Only 1 migration recorded despite 10 attempts");
    }

    @Test
    void testGetAllMigratedEntities() {
        var entity1 = new TestEntityID("entity-1");
        var entity2 = new TestEntityID("entity-2");
        var entity3 = new TestEntityID("entity-3");
        var sourceBubble = UUID.randomUUID();
        var targetBubble = UUID.randomUUID();

        migrationLog.recordMigration(
            entity1, UUID.randomUUID(), sourceBubble, targetBubble, 100L
        );
        migrationLog.recordMigration(
            entity2, UUID.randomUUID(), sourceBubble, targetBubble, 200L
        );
        migrationLog.recordMigration(
            entity3, UUID.randomUUID(), sourceBubble, targetBubble, 300L
        );

        var entities = migrationLog.getAllMigratedEntities();

        assertEquals(3, entities.size());
        assertTrue(entities.contains(entity1));
        assertTrue(entities.contains(entity2));
        assertTrue(entities.contains(entity3));
    }

    @Test
    void testMigrationsBetweenBuckets() {
        var entity1 = new TestEntityID("entity-1");
        var entity2 = new TestEntityID("entity-2");
        var entity3 = new TestEntityID("entity-3");
        var sourceBubble = UUID.randomUUID();
        var targetBubble = UUID.randomUUID();

        migrationLog.recordMigration(
            entity1, UUID.randomUUID(), sourceBubble, targetBubble, 100L
        );
        migrationLog.recordMigration(
            entity2, UUID.randomUUID(), sourceBubble, targetBubble, 200L
        );
        migrationLog.recordMigration(
            entity3, UUID.randomUUID(), sourceBubble, targetBubble, 300L
        );

        var migrations = migrationLog.getMigrationsBetween(150L, 250L);

        assertEquals(1, migrations.size(),
                    "Only entity2 migration in range [150, 250]");
        assertEquals(entity2, migrations.get(0).entityId());
    }

    @Test
    void testConcurrentReadWriteNoDeadlock() throws Exception {
        int writerCount = 5;
        int readerCount = 5;
        int iterations = 100;
        var executor = Executors.newFixedThreadPool(writerCount + readerCount);
        var latch = new CountDownLatch(writerCount + readerCount);
        var deadlock = new AtomicBoolean(false);

        try {
            // Writer threads
            for (int w = 0; w < writerCount; w++) {
                final int writerId = w;
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < iterations; i++) {
                            var entityId = new TestEntityID("w" + writerId + "-e" + i);
                            migrationLog.recordMigration(
                                entityId, UUID.randomUUID(),
                                UUID.randomUUID(), UUID.randomUUID(),
                                100L + i
                            );
                        }
                    } catch (Exception e) {
                        deadlock.set(true);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            // Reader threads
            for (int r = 0; r < readerCount; r++) {
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < iterations; i++) {
                            migrationLog.getMigrationCount();
                            migrationLog.getMigrationsBetween(0, 1000);
                            migrationLog.getUniqueEntityCount();
                        }
                    } catch (Exception e) {
                        deadlock.set(true);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            boolean completed = latch.await(10, TimeUnit.SECONDS);
            assertTrue(completed, "Should complete without deadlock");
            assertFalse(deadlock.get(), "Should not throw exceptions");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void testConcurrentCleanupNoDeadlock() throws Exception {
        int threadCount = 10;
        var executor = Executors.newFixedThreadPool(threadCount);
        var startLatch = new CountDownLatch(1);
        var endLatch = new CountDownLatch(threadCount);
        var errors = new AtomicInteger(0);

        // Pre-populate with migrations
        for (int i = 0; i < 100; i++) {
            var entityId = new TestEntityID("e" + i);
            migrationLog.recordMigration(
                entityId, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(),
                i * 10L
            );
        }

        try {
            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        startLatch.await();

                        // Mix of operations
                        for (int i = 0; i < 50; i++) {
                            // Record new migrations
                            var entityId = new TestEntityID("t" + threadId + "-e" + i);
                            migrationLog.recordMigration(
                                entityId, UUID.randomUUID(),
                                UUID.randomUUID(), UUID.randomUUID(),
                                i * 10L
                            );

                            // Cleanup old
                            if (i % 10 == 0) {
                                migrationLog.cleanupBefore(i * 5L);
                            }

                            // Read history
                            migrationLog.getMigrationHistory(entityId);
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        endLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            boolean completed = endLatch.await(30, TimeUnit.SECONDS);
            assertTrue(completed, "All threads should complete");
            assertEquals(0, errors.get(), "No errors should occur");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void testIterationDuringModificationSafe() throws Exception {
        int threadCount = 5;
        var executor = Executors.newFixedThreadPool(threadCount);
        var running = new AtomicBoolean(true);
        var errors = new AtomicInteger(0);

        // Writer thread adds migrations continuously
        var writerFuture = executor.submit(() -> {
            int count = 0;
            while (running.get() && count < 1000) {
                try {
                    var entityId = new TestEntityID("e" + count);
                    migrationLog.recordMigration(
                        entityId, UUID.randomUUID(),
                        UUID.randomUUID(), UUID.randomUUID(),
                        count
                    );
                    count++;
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            }
        });

        // Reader threads iterate continuously
        var readerFutures = new CopyOnWriteArrayList<Future<?>>();
        for (int i = 0; i < threadCount - 1; i++) {
            readerFutures.add(executor.submit(() -> {
                while (running.get()) {
                    try {
                        // These should not throw ConcurrentModificationException
                        var migrations = migrationLog.getMigrationsBetween(0, Long.MAX_VALUE);
                        var count = migrationLog.getMigrationCount();
                        var entities = migrationLog.getAllMigratedEntities();
                    } catch (ConcurrentModificationException e) {
                        errors.incrementAndGet();
                    } catch (Exception e) {
                        // Other exceptions are OK (e.g., timing issues)
                    }
                }
            }));
        }

        // Let them run for a bit
        Thread.sleep(500);
        running.set(false);

        writerFuture.get(5, TimeUnit.SECONDS);
        for (var future : readerFutures) {
            future.get(5, TimeUnit.SECONDS);
        }

        executor.shutdown();

        assertEquals(0, errors.get(), "No ConcurrentModificationException should occur");
    }
}
