/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.simulation.distributed.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests that validate all Phase 6B5.1 utilities working together.
 * <p>
 * This test ensures:
 * <ul>
 *   <li>Clock - InjectableClock for deterministic time control</li>
 *   <li>EntityAccountant - Entity tracking across bubbles</li>
 *   <li>HeapMonitor - Memory usage and leak detection</li>
 *   <li>EntityMigrationLoadGenerator - Migration load generation</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
class IntegrationInfrastructureTest {

    private EntityAccountant accountant;
    private HeapMonitor heapMonitor;
    private EntityMigrationLoadGenerator loadGenerator;
    private TestClock testClock;

    @BeforeEach
    void setUp() {
        testClock = new TestClock();
        accountant = new EntityAccountant();
        heapMonitor = new HeapMonitor();
    }

    @AfterEach
    void tearDown() {
        if (loadGenerator != null) {
            loadGenerator.stop();
        }
        if (heapMonitor != null) {
            heapMonitor.stop();
        }
    }

    /**
     * Test that all utilities can be instantiated and basic operations work.
     */
    @Test
    void testAllUtilitiesInstantiation() {
        // All utilities should be non-null
        assertNotNull(testClock, "TestClock should be instantiated");
        assertNotNull(accountant, "EntityAccountant should be instantiated");
        assertNotNull(heapMonitor, "HeapMonitor should be instantiated");
    }

    /**
     * Test clock with entity tracking integration.
     */
    @Test
    void testClockWithEntityTracking() {
        var bubble1 = UUID.randomUUID();
        var bubble2 = UUID.randomUUID();

        // Register entities at time 0
        var currentTime = testClock.currentTimeMillis();
        var entity1 = UUID.randomUUID();
        accountant.register(bubble1, entity1);

        // Advance time
        testClock.advance(100);
        var newTime = testClock.currentTimeMillis();
        assertTrue(newTime > currentTime, "Time should advance");

        // Move entity and validate
        var moved = accountant.moveBetweenBubbles(entity1, bubble1, bubble2);
        assertTrue(moved, "Move should succeed");
        var distribution = accountant.getDistribution();
        assertNotNull(distribution);
        assertEquals(1, distribution.get(bubble2).intValue());
        assertEquals(0, distribution.getOrDefault(bubble1, 0).intValue());
    }

    /**
     * Test heap monitor with entity tracking.
     */
    @Test
    void testHeapMonitorWithEntityTracking() {
        var bubble1 = UUID.randomUUID();

        // Start heap monitoring
        heapMonitor.start(100);

        // Create and register entities
        var entities = IntStream.range(0, 10)
                               .mapToObj(i -> UUID.randomUUID())
                               .toList();

        for (var entity : entities) {
            accountant.register(bubble1, entity);
        }

        // Let monitor collect some snapshots
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Validate heap monitor collected data
        var peakMemory = heapMonitor.getPeakMemory();
        assertTrue(peakMemory > 0, "Peak memory should be tracked");

        // Validate entity tracking still works
        var entitiesInBubble = accountant.entitiesInBubble(bubble1);
        assertEquals(10, entitiesInBubble.size(), "All entities should be tracked");
    }

    /**
     * Test load generator with entity tracking.
     */
    @Test
    void testLoadGeneratorWithEntityTracking() throws InterruptedException {
        var bubble1 = UUID.randomUUID();
        var bubble2 = UUID.randomUUID();

        // Create entities
        var entities = IntStream.range(0, 5)
                               .mapToObj(i -> {
                                   var entity = UUID.randomUUID();
                                   accountant.register(bubble1, entity);
                                   return entity;
                               })
                               .toList();

        // Create load generator
        var requestCount = new java.util.concurrent.atomic.AtomicInteger(0);
        loadGenerator = new EntityMigrationLoadGenerator(
            10, // 10 TPS target
            entities.size(),
            entityId -> {
                requestCount.incrementAndGet();
                try {
                    // Move from bubble1 to bubble2 (atomic check inside moveBetweenBubbles)
                    accountant.moveBetweenBubbles(entityId, bubble1, bubble2);
                } catch (Exception e) {
                    // Handle unexpected errors
                }
            }
        );

        // Start and let it generate some load
        loadGenerator.start();
        Thread.sleep(500);
        loadGenerator.stop();

        // Should have generated some requests
        assertTrue(requestCount.get() > 0, "Load generator should have made requests");

        // Validate entities are properly tracked
        var result = accountant.validate();
        assertTrue(result.success(), "Entity tracking should be valid: " + result.details());
    }

    /**
     * Test all utilities together in realistic scenario.
     */
    @Test
    void testIntegratedScenario() throws InterruptedException {
        var bubble1 = UUID.randomUUID();
        var bubble2 = UUID.randomUUID();

        // Start monitoring
        testClock.setSkew(0);
        heapMonitor.start(50);

        // Register initial entities
        var entities = IntStream.range(0, 10)
                               .mapToObj(i -> {
                                   var entity = UUID.randomUUID();
                                   accountant.register(bubble1, entity);
                                   return entity;
                               })
                               .toList();

        // Create load generator
        var migrationCount = new java.util.concurrent.atomic.AtomicInteger(0);
        loadGenerator = new EntityMigrationLoadGenerator(
            5, // 5 TPS
            10, // 10 entities
            entityId -> {
                migrationCount.incrementAndGet();
                try {
                    // Move from bubble1 to bubble2 (atomic check inside moveBetweenBubbles)
                    accountant.moveBetweenBubbles(entityId, bubble1, bubble2);
                } catch (Exception e) {
                    // Handle unexpected errors
                }
            }
        );

        // Run load generator
        loadGenerator.start();
        Thread.sleep(300);
        loadGenerator.stop();
        heapMonitor.stop();

        // Validate results
        assertTrue(migrationCount.get() > 0, "Migrations should have occurred");

        // Validate entity accounting is still valid
        var validation = accountant.validate();
        assertTrue(validation.success(), "Entity accounting should be valid: " + validation.details());

        var distribution = accountant.getDistribution();
        assertNotNull(distribution);

        // Verify all entities are still tracked
        var totalEntities = distribution.values().stream()
                                       .mapToInt(Integer::intValue)
                                       .sum();
        assertEquals(10, totalEntities, "All 10 entities should still be tracked");
    }
}
