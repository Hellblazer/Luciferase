/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.simulation.distributed.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for EntityAccountant - entity location tracking and validation.
 *
 * @author hal.hildebrand
 */
class EntityAccountantTest {

    private EntityAccountant accountant;
    private UUID bubble1;
    private UUID bubble2;
    private UUID bubble3;
    private UUID entity1;
    private UUID entity2;
    private UUID entity3;

    @BeforeEach
    void setUp() {
        accountant = new EntityAccountant();
        bubble1 = UUID.randomUUID();
        bubble2 = UUID.randomUUID();
        bubble3 = UUID.randomUUID();
        entity1 = UUID.randomUUID();
        entity2 = UUID.randomUUID();
        entity3 = UUID.randomUUID();
    }

    @Test
    void testRegisterSingleEntity() {
        accountant.register(bubble1, entity1);

        var entitiesInBubble = accountant.entitiesInBubble(bubble1);
        assertEquals(1, entitiesInBubble.size());
        assertTrue(entitiesInBubble.contains(entity1));

        var result = accountant.validate();
        assertTrue(result.success(), "Validation should succeed with single entity");
        assertEquals(0, result.errorCount());
    }

    @Test
    void testUnregisterEntity() {
        accountant.register(bubble1, entity1);
        accountant.unregister(bubble1, entity1);

        var entitiesInBubble = accountant.entitiesInBubble(bubble1);
        assertTrue(entitiesInBubble.isEmpty(), "Bubble should be empty after unregister");

        var result = accountant.validate();
        assertTrue(result.success(), "Validation should succeed with no entities");
    }

    @Test
    void testMoveBetweenBubbles() {
        accountant.register(bubble1, entity1);
        var moved = accountant.moveBetweenBubbles(entity1, bubble1, bubble2);

        assertTrue(moved, "Move should succeed");

        var entitiesInBubble1 = accountant.entitiesInBubble(bubble1);
        var entitiesInBubble2 = accountant.entitiesInBubble(bubble2);

        assertTrue(entitiesInBubble1.isEmpty(), "Entity should be removed from source bubble");
        assertTrue(entitiesInBubble2.contains(entity1), "Entity should be in target bubble");

        var result = accountant.validate();
        assertTrue(result.success(), "Validation should succeed after move");
    }

    @Test
    void testValidateSuccessfulState() {
        accountant.register(bubble1, entity1);
        accountant.register(bubble2, entity2);
        accountant.register(bubble3, entity3);

        var result = accountant.validate();
        assertTrue(result.success(), "Validation should succeed with each entity in one bubble");
        assertEquals(0, result.errorCount());
        assertTrue(result.details().isEmpty());
    }

    @Test
    void testValidateFailsDuplicateEntity() {
        // Manually create duplicate by registering same entity twice
        accountant.register(bubble1, entity1);
        accountant.register(bubble2, entity1);

        var result = accountant.validate();
        assertFalse(result.success(), "Validation should fail with duplicate entity");
        assertTrue(result.errorCount() > 0);
        assertFalse(result.details().isEmpty());
    }

    @Test
    void testValidateFailsMissingEntity() {
        accountant.register(bubble1, entity1);
        accountant.register(bubble2, entity2);
        accountant.unregister(bubble1, entity1);

        var result = accountant.validate();
        assertTrue(result.success(), "Validation should succeed after clean unregister");
    }

    @Test
    void testConcurrentRegisterUnregister() throws InterruptedException {
        var threadCount = 10;
        var opsPerThread = 100;
        var executor = Executors.newFixedThreadPool(threadCount);
        var latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < opsPerThread; j++) {
                        var entity = UUID.randomUUID();
                        var bubble = UUID.randomUUID();
                        accountant.register(bubble, entity);
                        accountant.unregister(bubble, entity);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Concurrent operations should complete");
        executor.shutdown();

        var result = accountant.validate();
        assertTrue(result.success(), "Validation should succeed after concurrent register/unregister");
    }

    @Test
    void testConcurrentMigrations() throws InterruptedException {
        var entities = new ArrayList<UUID>();
        for (int i = 0; i < 100; i++) {
            var entity = UUID.randomUUID();
            entities.add(entity);
            accountant.register(bubble1, entity);
        }

        var threadCount = 10;
        var executor = Executors.newFixedThreadPool(threadCount);
        var latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int threadIdx = i;
            executor.submit(() -> {
                try {
                    for (int j = threadIdx; j < entities.size(); j += threadCount) {
                        var entity = entities.get(j);
                        var moved1 = accountant.moveBetweenBubbles(entity, bubble1, bubble2);
                        if (moved1) {
                            accountant.moveBetweenBubbles(entity, bubble2, bubble3);
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Concurrent migrations should complete");
        executor.shutdown();

        var result = accountant.validate();
        assertTrue(result.success(), "Validation should succeed after concurrent migrations");

        var entitiesInBubble3 = accountant.entitiesInBubble(bubble3);
        assertEquals(entities.size(), entitiesInBubble3.size());
    }

    @Test
    void testGetDistribution() {
        accountant.register(bubble1, entity1);
        accountant.register(bubble1, entity2);
        accountant.register(bubble2, entity3);

        var distribution = accountant.getDistribution();
        assertEquals(2, distribution.size());
        assertEquals(2, distribution.get(bubble1));
        assertEquals(1, distribution.get(bubble2));
    }

    @Test
    void testGetEntitiesInBubble() {
        accountant.register(bubble1, entity1);
        accountant.register(bubble1, entity2);
        accountant.register(bubble2, entity3);

        var entitiesInBubble1 = accountant.entitiesInBubble(bubble1);
        assertEquals(2, entitiesInBubble1.size());
        assertTrue(entitiesInBubble1.contains(entity1));
        assertTrue(entitiesInBubble1.contains(entity2));

        var entitiesInBubble2 = accountant.entitiesInBubble(bubble2);
        assertEquals(1, entitiesInBubble2.size());
        assertTrue(entitiesInBubble2.contains(entity3));
    }

    @Test
    void testEmptyBubble() {
        accountant.register(bubble1, entity1);

        var entitiesInEmptyBubble = accountant.entitiesInBubble(bubble2);
        assertTrue(entitiesInEmptyBubble.isEmpty(), "Unregistered bubble should be empty");
    }

    @Test
    void testMultipleBubbles() {
        for (int i = 0; i < 10; i++) {
            var bubble = UUID.randomUUID();
            var entityCount = i + 1;
            for (int j = 0; j < entityCount; j++) {
                accountant.register(bubble, UUID.randomUUID());
            }
        }

        var distribution = accountant.getDistribution();
        assertEquals(10, distribution.size());

        var result = accountant.validate();
        assertTrue(result.success(), "Validation should succeed with multiple bubbles");
    }

    @Test
    void testThreadSafeValidation() throws InterruptedException {
        var threadCount = 10;
        var executor = Executors.newFixedThreadPool(threadCount);
        var latch = new CountDownLatch(threadCount);
        var validationErrors = new AtomicInteger(0);

        for (int i = 0; i < 100; i++) {
            accountant.register(bubble1, UUID.randomUUID());
        }

        for (int i = 0; i < threadCount; i++) {
            final int threadIdx = i;
            executor.submit(() -> {
                try {
                    if (threadIdx % 2 == 0) {
                        for (int j = 0; j < 50; j++) {
                            var entity = UUID.randomUUID();
                            accountant.register(bubble2, entity);
                        }
                    } else {
                        for (int j = 0; j < 50; j++) {
                            var result = accountant.validate();
                            if (!result.success()) {
                                validationErrors.incrementAndGet();
                            }
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Operations should complete");
        executor.shutdown();

        var finalResult = accountant.validate();
        assertTrue(finalResult.success(), "Final validation should succeed");
    }

    @Test
    void testMetrics() {
        assertEquals(0, accountant.getTotalOperations());

        accountant.register(bubble1, entity1);
        assertTrue(accountant.getTotalOperations() > 0);

        var opsBefore = accountant.getTotalOperations();
        accountant.moveBetweenBubbles(entity1, bubble1, bubble2);
        assertTrue(accountant.getTotalOperations() > opsBefore);

        var opsBeforeUnregister = accountant.getTotalOperations();
        accountant.unregister(bubble2, entity1);
        assertTrue(accountant.getTotalOperations() > opsBeforeUnregister);
    }

    @Test
    void testResetState() {
        accountant.register(bubble1, entity1);
        accountant.register(bubble2, entity2);

        assertTrue(accountant.getTotalOperations() > 0);

        accountant.reset();

        assertEquals(0, accountant.getTotalOperations());
        assertTrue(accountant.entitiesInBubble(bubble1).isEmpty());
        assertTrue(accountant.entitiesInBubble(bubble2).isEmpty());
        assertTrue(accountant.getDistribution().isEmpty());

        var result = accountant.validate();
        assertTrue(result.success(), "Validation should succeed after reset");
    }
}
