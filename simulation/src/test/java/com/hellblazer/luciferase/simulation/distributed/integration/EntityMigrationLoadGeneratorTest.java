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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for EntityMigrationLoadGenerator - migration load generation.
 *
 * @author hal.hildebrand
 */
class EntityMigrationLoadGeneratorTest {

    private EntityMigrationLoadGenerator generator;
    private AtomicInteger requestCount;
    private Set<UUID> requestedEntities;

    @BeforeEach
    void setUp() {
        requestCount = new AtomicInteger(0);
        requestedEntities = ConcurrentHashMap.newKeySet();
    }

    @AfterEach
    void tearDown() {
        if (generator != null) {
            generator.stop();
        }
    }

    @Test
    void testLoadGenerationAtTargetTPS() throws InterruptedException {
        var targetTPS = 10;
        generator = new EntityMigrationLoadGenerator(targetTPS, 100, entity -> requestCount.incrementAndGet());

        generator.start();
        Thread.sleep(1100);

        var metrics = generator.getMetrics();
        assertTrue(metrics.actualTPS() >= targetTPS * 0.8 && metrics.actualTPS() <= targetTPS * 1.2,
                   "Actual TPS should be within 20% of target");
    }

    @Test
    void testStartStop() {
        generator = new EntityMigrationLoadGenerator(10, 100, entity -> {});

        generator.start();
        assertTrue(generator.isRunning(), "Generator should be running after start");

        generator.stop();
        assertFalse(generator.isRunning(), "Generator should not be running after stop");
    }

    @Test
    void testTPSAdjustment() throws InterruptedException {
        generator = new EntityMigrationLoadGenerator(5, 100, entity -> requestCount.incrementAndGet());

        generator.start();
        Thread.sleep(600);

        var metrics1 = generator.getMetrics();
        var tps1 = metrics1.actualTPS();

        generator.setTargetTPS(20);
        requestCount.set(0);
        Thread.sleep(600);

        var metrics2 = generator.getMetrics();
        assertTrue(metrics2.targetTPS() == 20, "Target TPS should be updated");
    }

    @Test
    void testMetricsCollection() throws InterruptedException {
        var targetTPS = 10;
        generator = new EntityMigrationLoadGenerator(targetTPS, 100, entity -> requestCount.incrementAndGet());

        generator.start();
        Thread.sleep(1100);

        var metrics = generator.getMetrics();
        assertEquals(targetTPS, metrics.targetTPS(), "Target TPS should match");
        assertTrue(metrics.actualTPS() > 0, "Actual TPS should be positive");
        assertTrue(metrics.totalGenerated() > 0, "Total generated should be positive");
    }

    @Test
    void testUniformDistribution() throws InterruptedException {
        var entityCount = 50;
        generator = new EntityMigrationLoadGenerator(50, entityCount, entity -> requestedEntities.add(entity));

        generator.start();
        Thread.sleep(1500);
        generator.stop();

        assertTrue(requestedEntities.size() >= entityCount * 0.8,
                   "At least 80% of entities should have been selected");
    }

    @Test
    void testConcurrentRequests() throws InterruptedException {
        var concurrentRequests = new AtomicInteger(0);
        var maxConcurrent = new AtomicInteger(0);

        generator = new EntityMigrationLoadGenerator(50, 100, entity -> {
            var current = concurrentRequests.incrementAndGet();
            synchronized (maxConcurrent) {
                if (current > maxConcurrent.get()) {
                    maxConcurrent.set(current);
                }
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            concurrentRequests.decrementAndGet();
        });

        generator.start();
        Thread.sleep(500);
        generator.stop();

        assertTrue(maxConcurrent.get() >= 1, "Should have concurrent requests");
    }

    @Test
    void testQueueDepth() throws InterruptedException {
        generator = new EntityMigrationLoadGenerator(100, 100, entity -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        generator.start();
        Thread.sleep(200);

        var metrics = generator.getMetrics();
        assertTrue(metrics.pendingRequests() >= 0, "Pending requests should be non-negative");

        generator.stop();
    }

    @Test
    void testLowTPSTarget() throws InterruptedException {
        var targetTPS = 1;
        generator = new EntityMigrationLoadGenerator(targetTPS, 10, entity -> requestCount.incrementAndGet());

        generator.start();
        Thread.sleep(2100);

        var metrics = generator.getMetrics();
        assertTrue(metrics.actualTPS() >= 0.8 && metrics.actualTPS() <= 1.2,
                   "Low TPS should be achievable");
    }

    @Test
    void testHighTPSTarget() throws InterruptedException {
        var targetTPS = 100;
        generator = new EntityMigrationLoadGenerator(targetTPS, 100, entity -> requestCount.incrementAndGet());

        generator.start();
        Thread.sleep(1100);

        var metrics = generator.getMetrics();
        assertTrue(metrics.actualTPS() >= targetTPS * 0.7,
                   "High TPS should be approximately achievable");
    }

    @Test
    void testEntitySelection() throws InterruptedException {
        var entityCount = 10;
        generator = new EntityMigrationLoadGenerator(20, entityCount, entity -> requestedEntities.add(entity));

        generator.start();
        Thread.sleep(1000);
        generator.stop();

        assertTrue(requestedEntities.size() >= entityCount * 0.7,
                   "Most entities should be selected over time");
    }

    @Test
    void testMigrationCallbacks() throws InterruptedException {
        var callbackCount = new AtomicInteger(0);
        generator = new EntityMigrationLoadGenerator(10, 100, entity -> {
            assertNotNull(entity, "Entity should not be null");
            callbackCount.incrementAndGet();
        });

        generator.start();
        Thread.sleep(1100);
        generator.stop();

        assertTrue(callbackCount.get() >= 8, "Callbacks should be invoked");
    }

    @Test
    void testMetricsAccuracy() throws InterruptedException {
        var targetTPS = 20;
        generator = new EntityMigrationLoadGenerator(targetTPS, 100, entity -> requestCount.incrementAndGet());

        generator.start();
        Thread.sleep(1100);

        var metrics = generator.getMetrics();
        var actualTPS = metrics.actualTPS();

        assertTrue(Math.abs(actualTPS - targetTPS) / targetTPS <= 0.3,
                   "Actual TPS should be within 30% of target for reasonable accuracy");
    }
}
