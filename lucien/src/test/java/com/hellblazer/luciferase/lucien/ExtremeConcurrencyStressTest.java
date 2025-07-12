/*
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Extreme concurrency stress test for Luciferase spatial index implementations. Tests with 50+ threads performing mixed
 * operations to expose any remaining concurrency issues after the ConcurrentSkipListMap implementation.
 *
 * @author hal.hildebrand
 */
@EnabledIfEnvironmentVariable(named = "RUN_SPATIAL_INDEX_PERF_TESTS", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ExtremeConcurrencyStressTest {

    // Test configuration
    private static final int MIN_THREADS                = 50;
    private static final int MAX_THREADS                = 100;
    private static final int BASE_OPERATIONS_PER_THREAD = 10000;

    // Operation mix percentages (should sum to 100)
    private static final int INSERT_PERCENTAGE = 40;
    private static final int QUERY_PERCENTAGE  = 30;
    private static final int UPDATE_PERCENTAGE = 20;
    private static final int DELETE_PERCENTAGE = 10;

    // Test duration options
    private static final long SHORT_TEST_DURATION_MS  = 10_000;  // 10 seconds
    private static final long MEDIUM_TEST_DURATION_MS = 30_000; // 30 seconds
    private static final long LONG_TEST_DURATION_MS   = 60_000;   // 60 seconds

    // Metrics collection
    private final AtomicLong totalOperations      = new AtomicLong(0);
    private final AtomicLong successfulOperations = new AtomicLong(0);
    private final AtomicLong failedOperations     = new AtomicLong(0);
    private final AtomicLong insertOperations     = new AtomicLong(0);
    private final AtomicLong queryOperations      = new AtomicLong(0);
    private final AtomicLong updateOperations     = new AtomicLong(0);
    private final AtomicLong deleteOperations     = new AtomicLong(0);

    // Error tracking
    private final ConcurrentHashMap<String, AtomicLong> errorCounts  = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<String>         errorDetails = new ConcurrentLinkedQueue<>();

    // Entity tracking for verification
    private final ConcurrentHashMap<LongEntityID, EntityState> entityStates = new ConcurrentHashMap<>();

    // Thread monitoring
    private final ThreadMXBean              threadMXBean         = ManagementFactory.getThreadMXBean();
    private final AtomicLong                maxConcurrentThreads = new AtomicLong(0);
    private final AtomicLong                activeThreads        = new AtomicLong(0);
    private final Random                    masterRandom         = new Random(
    42); // Deterministic seed for reproducibility
    // Test data
    private       SequentialLongIDGenerator idGenerator;

    @BeforeEach
    void setUp() {
        idGenerator = new SequentialLongIDGenerator();
        // Enable thread contention monitoring if available
        if (threadMXBean.isThreadContentionMonitoringSupported()) {
            threadMXBean.setThreadContentionMonitoringEnabled(true);
        }
    }

    @AfterEach
    void tearDown() {
        // Print detailed metrics
        System.out.println("\n=== Test Metrics ===");
        System.out.println("Total operations: " + totalOperations.get());
        System.out.println("Successful operations: " + successfulOperations.get());
        System.out.println("Failed operations: " + failedOperations.get());
        System.out.println("Operations/second: " + (totalOperations.get() * 1000.0 / Math.max(1, getTestDuration())));

        System.out.println("\nOperation breakdown:");
        System.out.println("  Inserts: " + insertOperations.get());
        System.out.println("  Queries: " + queryOperations.get());
        System.out.println("  Updates: " + updateOperations.get());
        System.out.println("  Deletes: " + deleteOperations.get());

        System.out.println("\nMax concurrent threads: " + maxConcurrentThreads.get());

        if (!errorCounts.isEmpty()) {
            System.out.println("\nError summary:");
            errorCounts.forEach((error, count) -> System.out.println("  " + error + ": " + count.get()));
        }

        // Reset metrics for next test
        resetMetrics();
    }

    @Test
    @Order(10)
    @DisplayName("Data integrity verification test - Mixed operations")
    void testDataIntegrityUnderStress() throws InterruptedException {
        var octree = new Octree<LongEntityID, String>(idGenerator);
        runDataIntegrityStressTest(octree, 60, MEDIUM_TEST_DURATION_MS);
    }

    @Test
    @Order(7)
    @DisplayName("Boundary crossing stress test - Octree")
    void testOctreeBoundaryCrossingStress() throws InterruptedException {
        runBoundaryCrossingStressTest(new Octree<>(idGenerator), 60, SHORT_TEST_DURATION_MS);
    }

    @Test
    @Order(9)
    @DisplayName("Extended duration stress test - Octree")
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void testOctreeExtendedDurationStress() throws InterruptedException {
        runExtremeStressTest(new Octree<>(idGenerator), 80, LONG_TEST_DURATION_MS);
    }

    @Test
    @Order(3)
    @DisplayName("Extreme stress test - Octree with 100 threads")
    void testOctreeExtremeStress100Threads() throws InterruptedException {
        runExtremeStressTest(new Octree<>(idGenerator), 100, MEDIUM_TEST_DURATION_MS);
    }

    @Test
    @Order(1)
    @DisplayName("Extreme stress test - Octree with 50 threads")
    void testOctreeExtremeStress50Threads() throws InterruptedException {
        runExtremeStressTest(new Octree<>(idGenerator), 50, SHORT_TEST_DURATION_MS);
    }

    @Test
    @Order(5)
    @DisplayName("Rapid entity movement stress test - Octree")
    void testOctreeRapidMovementStress() throws InterruptedException {
        runRapidMovementStressTest(new Octree<>(idGenerator), 75, SHORT_TEST_DURATION_MS);
    }

    @Test
    @Order(8)
    @DisplayName("Boundary crossing stress test - Tetree")
    void testTetreeBoundaryCrossingStress() throws InterruptedException {
        runBoundaryCrossingStressTest(new Tetree<>(idGenerator), 60, SHORT_TEST_DURATION_MS);
    }

    @Test
    @Order(4)
    @DisplayName("Extreme stress test - Tetree with 100 threads")
    void testTetreeExtremeStress100Threads() throws InterruptedException {
        runExtremeStressTest(new Tetree<>(idGenerator), 100, MEDIUM_TEST_DURATION_MS);
    }

    @Test
    @Order(2)
    @DisplayName("Extreme stress test - Tetree with 50 threads")
    void testTetreeExtremeStress50Threads() throws InterruptedException {
        runExtremeStressTest(new Tetree<>(idGenerator), 50, SHORT_TEST_DURATION_MS);
    }

    @Test
    @Order(6)
    @DisplayName("Rapid entity movement stress test - Tetree")
    void testTetreeRapidMovementStress() throws InterruptedException {
        runRapidMovementStressTest(new Tetree<>(idGenerator), 75, SHORT_TEST_DURATION_MS);
    }

    private Point3f generateRandomPosition(Random random) {
        return new Point3f(random.nextFloat() * 999f + 0.1f, random.nextFloat() * 999f + 0.1f,
                           random.nextFloat() * 999f + 0.1f);
    }

    private long getTestDuration() {
        // Approximate based on operations/second
        return Math.max(1, totalOperations.get() / 1000);
    }

    private <Key extends SpatialKey<Key>> void performDelete(SpatialIndex<Key, LongEntityID, String> spatialIndex,
                                                             Random random, List<LongEntityID> threadEntityIds) {

        if (threadEntityIds.isEmpty() && entityStates.isEmpty()) {
            return;
        }

        LongEntityID id;
        if (!threadEntityIds.isEmpty() && random.nextBoolean()) {
            int index = random.nextInt(threadEntityIds.size());
            id = threadEntityIds.remove(index);
        } else {
            var ids = new ArrayList<>(entityStates.keySet());
            if (ids.isEmpty()) {
                return;
            }
            id = ids.get(random.nextInt(ids.size()));
        }

        var state = entityStates.get(id);
        if (state == null || state.deleted) {
            return;
        }

        totalOperations.incrementAndGet();
        deleteOperations.incrementAndGet();

        if (spatialIndex.removeEntity(id)) {
            state.markDeleted();
            successfulOperations.incrementAndGet();
        } else {
            failedOperations.incrementAndGet();
        }
    }

    private <Key extends SpatialKey<Key>> void performInsert(SpatialIndex<Key, LongEntityID, String> spatialIndex,
                                                             Random random, List<LongEntityID> threadEntityIds) {

        var id = idGenerator.generateID();
        var pos = generateRandomPosition(random);
        var content = "Entity-" + id + "-T" + Thread.currentThread().getId();

        totalOperations.incrementAndGet();
        insertOperations.incrementAndGet();

        spatialIndex.insert(id, pos, (byte) 10, content);
        threadEntityIds.add(id);

        var state = new EntityState(id, pos, content);
        entityStates.put(id, state);

        successfulOperations.incrementAndGet();
    }

    private <Key extends SpatialKey<Key>> void performQuery(SpatialIndex<Key, LongEntityID, String> spatialIndex,
                                                            Random random) {

        totalOperations.incrementAndGet();
        queryOperations.incrementAndGet();

        int queryType = random.nextInt(4);
        switch (queryType) {
            case 0: // K-NN query
                var knnPos = generateRandomPosition(random);
                var knnResults = spatialIndex.kNearestNeighbors(knnPos, 10, Float.MAX_VALUE);
                assertNotNull(knnResults);
                break;

            case 1: // Range query
                var rangePos = generateRandomPosition(random);
                var rangeResults = spatialIndex.kNearestNeighbors(rangePos, Integer.MAX_VALUE, 50f);
                assertNotNull(rangeResults);
                break;

            case 2: // Ray intersection
                var origin = generateRandomPosition(random);
                var direction = new Vector3f(random.nextFloat() - 0.5f, random.nextFloat() - 0.5f,
                                             random.nextFloat() - 0.5f);
                direction.normalize();
                var ray = new Ray3D(origin, direction);
                var hits = spatialIndex.rayIntersectAll(ray);
                assertNotNull(hits);
                break;

            case 3: // Get specific entity
                if (!entityStates.isEmpty()) {
                    var ids = new ArrayList<>(entityStates.keySet());
                    var id = ids.get(random.nextInt(ids.size()));
                    var result = spatialIndex.getEntity(id);
                    // May be null if deleted
                }
                break;
        }

        successfulOperations.incrementAndGet();
    }

    private <Key extends SpatialKey<Key>> void performUpdate(SpatialIndex<Key, LongEntityID, String> spatialIndex,
                                                             Random random, List<LongEntityID> threadEntityIds) {

        if (threadEntityIds.isEmpty() && entityStates.isEmpty()) {
            return;
        }

        LongEntityID id;
        if (!threadEntityIds.isEmpty() && random.nextBoolean()) {
            id = threadEntityIds.get(random.nextInt(threadEntityIds.size()));
        } else {
            var ids = new ArrayList<>(entityStates.keySet());
            if (ids.isEmpty()) {
                return;
            }
            id = ids.get(random.nextInt(ids.size()));
        }

        var state = entityStates.get(id);
        if (state == null || state.deleted) {
            return;
        }

        totalOperations.incrementAndGet();
        updateOperations.incrementAndGet();

        var newPos = generateRandomPosition(random);
        spatialIndex.updateEntity(id, newPos, (byte) 10);
        state.update(newPos, state.content);

        successfulOperations.incrementAndGet();
    }

    private boolean positionsEqual(Point3f p1, Point3f p2) {
        if (p1 == null || p2 == null) {
            return false;
        }
        float epsilon = 0.001f;
        return Math.abs(p1.x - p2.x) < epsilon && Math.abs(p1.y - p2.y) < epsilon && Math.abs(p1.z - p2.z) < epsilon;
    }

    private <Key extends SpatialKey<Key>> List<LongEntityID> prepopulateIndex(
    SpatialIndex<Key, LongEntityID, String> spatialIndex, int count) {

        List<LongEntityID> ids = new ArrayList<>();
        Random random = new Random(masterRandom.nextInt());

        for (int i = 0; i < count; i++) {
            var id = idGenerator.generateID();
            var pos = generateRandomPosition(random);
            var content = "Initial-" + id;

            spatialIndex.insert(id, pos, (byte) 10, content);
            ids.add(id);

            var state = new EntityState(id, pos, content);
            entityStates.put(id, state);
        }

        return ids;
    }

    private void recordError(String context, Exception e) {
        errorCounts.computeIfAbsent(e.getClass().getSimpleName(), k -> new AtomicLong()).incrementAndGet();
        String detail = context + ": " + e.getMessage();
        errorDetails.offer(detail);

        // Keep only last 100 error details to avoid memory issues
        while (errorDetails.size() > 100) {
            errorDetails.poll();
        }
    }

    private void resetMetrics() {
        totalOperations.set(0);
        successfulOperations.set(0);
        failedOperations.set(0);
        insertOperations.set(0);
        queryOperations.set(0);
        updateOperations.set(0);
        deleteOperations.set(0);
        maxConcurrentThreads.set(0);
        activeThreads.set(0);
        errorCounts.clear();
        errorDetails.clear();
        entityStates.clear();
    }

    private <Key extends SpatialKey<Key>> void runBoundaryCrossingStressTest(
    SpatialIndex<Key, LongEntityID, String> spatialIndex, int threadCount, long durationMs)
    throws InterruptedException {

        System.out.println("\nStarting boundary crossing stress test with " + threadCount + " threads");

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicBoolean running = new AtomicBoolean(true);

        // Define boundary regions
        float[] boundaries = { 100f, 200f, 300f, 400f, 500f, 600f, 700f, 800f, 900f };

        // Launch boundary crossing threads
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();

                    Random random = new Random(threadId);
                    int operationCount = 0;

                    while (running.get()) {
                        // Create entity near boundary
                        var id = idGenerator.generateID();
                        float boundary = boundaries[random.nextInt(boundaries.length)];

                        // Position just before boundary
                        var pos1 = new Point3f(boundary - 1f + random.nextFloat() * 2f, random.nextFloat() * 100f,
                                               random.nextFloat() * 100f);

                        totalOperations.incrementAndGet();
                        insertOperations.incrementAndGet();

                        try {
                            spatialIndex.insert(id, pos1, (byte) 10, "Boundary-" + id);
                            var state = new EntityState(id, pos1, "Boundary-" + id);
                            entityStates.put(id, state);
                            successfulOperations.incrementAndGet();

                            // Immediately move across boundary
                            var pos2 = new Point3f(boundary + 1f + random.nextFloat() * 2f, pos1.y, pos1.z);

                            totalOperations.incrementAndGet();
                            updateOperations.incrementAndGet();

                            spatialIndex.updateEntity(id, pos2, (byte) 10);
                            state.update(pos2, state.content);
                            successfulOperations.incrementAndGet();

                            // Occasionally delete
                            if (operationCount++ % 10 == 0) {
                                totalOperations.incrementAndGet();
                                deleteOperations.incrementAndGet();

                                if (spatialIndex.removeEntity(id)) {
                                    state.markDeleted();
                                    successfulOperations.incrementAndGet();
                                } else {
                                    failedOperations.incrementAndGet();
                                }
                            }
                        } catch (Exception e) {
                            failedOperations.incrementAndGet();
                            recordError("Boundary operation failed", e);
                        }
                    }
                } catch (Exception e) {
                    recordError("Boundary thread " + threadId + " exception", e);
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // Start test
        startLatch.countDown();
        Thread.sleep(durationMs);
        running.set(false);

        boolean completed = endLatch.await(30, TimeUnit.SECONDS);
        assertTrue(completed, "Boundary threads did not complete");

        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        verifyIndexIntegrity(spatialIndex);
    }

    private <Key extends SpatialKey<Key>> void runDataIntegrityStressTest(
    SpatialIndex<Key, LongEntityID, String> spatialIndex, int threadCount, long durationMs)
    throws InterruptedException {

        System.out.println("\nStarting data integrity stress test with " + threadCount + " threads");

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        ScheduledExecutorService verifier = Executors.newScheduledThreadPool(1);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicLong verificationErrors = new AtomicLong(0);

        // Schedule periodic integrity checks
        verifier.scheduleAtFixedRate(() -> {
            try {
                var errors = verifyLiveDataIntegrity(spatialIndex);
                if (errors > 0) {
                    verificationErrors.addAndGet(errors);
                    System.err.println("Found " + errors + " integrity errors during live check");
                }
            } catch (Exception e) {
                recordError("Live verification failed", e);
            }
        }, 1, 2, TimeUnit.SECONDS);

        // Launch worker threads
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    runMixedOperations(spatialIndex, threadId, running);
                } catch (Exception e) {
                    recordError("Worker thread " + threadId + " exception", e);
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // Start test
        startLatch.countDown();
        Thread.sleep(durationMs);
        running.set(false);

        boolean completed = endLatch.await(30, TimeUnit.SECONDS);
        assertTrue(completed, "Worker threads did not complete");

        verifier.shutdown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        assertTrue(verifier.awaitTermination(5, TimeUnit.SECONDS));

        // Final integrity check
        assertEquals(0, verificationErrors.get(), "Data integrity violations detected during test");
        verifyIndexIntegrity(spatialIndex);
    }

    private <Key extends SpatialKey<Key>> void runExtremeStressTest(
    SpatialIndex<Key, LongEntityID, String> spatialIndex, int threadCount, long durationMs)
    throws InterruptedException {

        System.out.println(
        "\nStarting extreme stress test with " + threadCount + " threads for " + durationMs + "ms on "
        + spatialIndex.getClass().getSimpleName());

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicBoolean running = new AtomicBoolean(true);

        // Pre-populate with some entities
        prepopulateIndex(spatialIndex, 1000);

        // Launch worker threads
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    // Wait for all threads to be ready
                    startLatch.await();

                    // Track active threads
                    long active = activeThreads.incrementAndGet();
                    updateMaxConcurrentThreads(active);

                    // Run operations
                    runMixedOperations(spatialIndex, threadId, running);

                } catch (Exception e) {
                    recordError("Thread " + threadId + " exception", e);
                } finally {
                    activeThreads.decrementAndGet();
                    endLatch.countDown();
                }
            });
        }

        // Start all threads simultaneously
        Thread.sleep(100); // Give threads time to reach the barrier
        long startTime = System.currentTimeMillis();
        startLatch.countDown();

        // Let test run for specified duration
        Thread.sleep(durationMs);
        running.set(false);

        // Wait for all threads to complete
        boolean completed = endLatch.await(30, TimeUnit.SECONDS);
        assertTrue(completed, "Not all threads completed within timeout");

        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "Executor did not shut down cleanly");

        long actualDuration = System.currentTimeMillis() - startTime;
        System.out.println("Test completed in " + actualDuration + "ms");

        // Verify data integrity
        verifyIndexIntegrity(spatialIndex);
    }

    private <Key extends SpatialKey<Key>> void runMixedOperations(SpatialIndex<Key, LongEntityID, String> spatialIndex,
                                                                  int threadId, AtomicBoolean running) {

        Random random = new Random(threadId * 1000L + masterRandom.nextInt());
        List<LongEntityID> threadEntityIds = new ArrayList<>();

        while (running.get()) {
            int operation = random.nextInt(100);

            try {
                if (operation < INSERT_PERCENTAGE) {
                    // Insert operation
                    performInsert(spatialIndex, random, threadEntityIds);
                } else if (operation < INSERT_PERCENTAGE + QUERY_PERCENTAGE) {
                    // Query operation
                    performQuery(spatialIndex, random);
                } else if (operation < INSERT_PERCENTAGE + QUERY_PERCENTAGE + UPDATE_PERCENTAGE) {
                    // Update operation
                    performUpdate(spatialIndex, random, threadEntityIds);
                } else {
                    // Delete operation
                    performDelete(spatialIndex, random, threadEntityIds);
                }
            } catch (Exception e) {
                failedOperations.incrementAndGet();
                recordError("Operation failed", e);
            }
        }
    }

    private <Key extends SpatialKey<Key>> void runRapidMovementStressTest(
    SpatialIndex<Key, LongEntityID, String> spatialIndex, int threadCount, long durationMs)
    throws InterruptedException {

        System.out.println("\nStarting rapid movement stress test with " + threadCount + " threads");

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicBoolean running = new AtomicBoolean(true);

        // Pre-populate with entities
        List<LongEntityID> entityIds = prepopulateIndex(spatialIndex, 2000);

        // Launch movement threads
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();

                    Random random = new Random(threadId);
                    while (running.get()) {
                        // Pick random entity
                        var entityId = entityIds.get(random.nextInt(entityIds.size()));

                        // Generate new position with small delta for rapid movement
                        float delta = 5.0f;
                        var currentState = entityStates.get(entityId);
                        if (currentState != null && !currentState.deleted) {
                            var oldPos = currentState.position;
                            var newPos = new Point3f(Math.max(0.1f, oldPos.x + (random.nextFloat() - 0.5f) * delta),
                                                     Math.max(0.1f, oldPos.y + (random.nextFloat() - 0.5f) * delta),
                                                     Math.max(0.1f, oldPos.z + (random.nextFloat() - 0.5f) * delta));

                            // Ensure position stays within bounds
                            newPos.x = Math.min(999.9f, newPos.x);
                            newPos.y = Math.min(999.9f, newPos.y);
                            newPos.z = Math.min(999.9f, newPos.z);

                            // Update position
                            totalOperations.incrementAndGet();
                            updateOperations.incrementAndGet();

                            try {
                                spatialIndex.updateEntity(entityId, newPos, (byte) 10);
                                currentState.update(newPos, currentState.content);
                                successfulOperations.incrementAndGet();
                            } catch (Exception e) {
                                failedOperations.incrementAndGet();
                                recordError("Update failed", e);
                            }
                        }
                    }
                } catch (Exception e) {
                    recordError("Movement thread " + threadId + " exception", e);
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // Start test
        startLatch.countDown();
        Thread.sleep(durationMs);
        running.set(false);

        boolean completed = endLatch.await(30, TimeUnit.SECONDS);
        assertTrue(completed, "Movement threads did not complete");

        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        verifyIndexIntegrity(spatialIndex);
    }

    private void updateMaxConcurrentThreads(long current) {
        long max;
        do {
            max = maxConcurrentThreads.get();
        } while (current > max && !maxConcurrentThreads.compareAndSet(max, current));
    }

    private <Key extends SpatialKey<Key>> void verifyIndexIntegrity(
    SpatialIndex<Key, LongEntityID, String> spatialIndex) {

        System.out.println("\nVerifying index integrity...");

        // Check that all non-deleted entities in our tracking are in the index
        int expectedCount = 0;
        int missingEntities = 0;
        int incorrectPositions = 0;

        for (var entry : entityStates.entrySet()) {
            var state = entry.getValue();
            if (!state.deleted) {
                expectedCount++;

                var indexData = spatialIndex.getEntity(entry.getKey());
                if (indexData == null) {
                    missingEntities++;
                    System.err.println("Missing entity: " + entry.getKey());
                } else {
                    // Verify position matches
                    var indexPos = spatialIndex.getEntityPosition(entry.getKey());
                    if (indexPos == null || !positionsEqual(state.position, indexPos)) {
                        incorrectPositions++;
                        System.err.println(
                        "Position mismatch for " + entry.getKey() + ": expected " + state.position + ", got "
                        + indexPos);
                    }
                }
            }
        }

        // Check index statistics
        var stats = spatialIndex.getStats();
        System.out.println("Index statistics: " + stats);

        // Assertions
        assertEquals(0, missingEntities, "Entities are missing from the index");
        assertEquals(0, incorrectPositions, "Entity positions do not match");

        // Check that we can query without errors
        var allEntities = spatialIndex.getEntitiesWithPositions();
        assertNotNull(allEntities, "getAllEntities should not return null");

        System.out.println(
        "Integrity check passed. Expected entities: " + expectedCount + ", Index reports: " + stats.entityCount());
    }

    private <Key extends SpatialKey<Key>> int verifyLiveDataIntegrity(
    SpatialIndex<Key, LongEntityID, String> spatialIndex) {

        int errors = 0;

        // Sample check - verify a random subset of entities
        var states = new ArrayList<>(entityStates.values());
        Collections.shuffle(states);
        int sampleSize = Math.min(100, states.size());

        for (int i = 0; i < sampleSize; i++) {
            var state = states.get(i);
            if (!state.deleted) {
                var indexData = spatialIndex.getEntity(state.id);
                if (indexData == null) {
                    errors++;
                } else {
                    var indexPos = spatialIndex.getEntityPosition(state.id);
                    if (indexPos == null || !positionsEqual(state.position, indexPos)) {
                        errors++;
                    }
                }
            }
        }

        return errors;
    }

    private static class EntityState {
        final    LongEntityID  id;
        final    AtomicInteger modificationCount = new AtomicInteger(0);
        volatile Point3f       position;
        volatile String        content;
        volatile boolean       deleted;
        volatile long          lastModified;

        EntityState(LongEntityID id, Point3f position, String content) {
            this.id = id;
            this.position = new Point3f(position);
            this.content = content;
            this.deleted = false;
            this.lastModified = System.nanoTime();
        }

        synchronized void markDeleted() {
            this.deleted = true;
            this.lastModified = System.nanoTime();
            this.modificationCount.incrementAndGet();
        }

        synchronized void update(Point3f newPosition, String newContent) {
            this.position = new Point3f(newPosition);
            this.content = newContent;
            this.deleted = false;
            this.lastModified = System.nanoTime();
            this.modificationCount.incrementAndGet();
        }
    }
}
