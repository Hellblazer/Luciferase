/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.distributed;

import com.hellblazer.luciferase.simulation.von.LocalServerTransport;
import com.hellblazer.luciferase.simulation.von.VonBubble;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for SimulationTickMetrics.
 */
class SimulationTickMetricsTest {

    private LocalServerTransport.Registry registry;
    private VonBubble bubble1;
    private VonBubble bubble2;
    private SimulationTickMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = LocalServerTransport.Registry.create();

        var uuid1 = UUID.randomUUID();
        var uuid2 = UUID.randomUUID();
        var transport1 = registry.register(uuid1);
        var transport2 = registry.register(uuid2);

        bubble1 = new VonBubble(uuid1, (byte) 10, 16, transport1);
        bubble2 = new VonBubble(uuid2, (byte) 10, 16, transport2);

        // Add some test entities
        bubble1.addEntity("entity1", new Point3f(10, 10, 10), null);
        bubble1.addEntity("entity2", new Point3f(20, 20, 20), null);
        bubble2.addEntity("entity3", new Point3f(30, 30, 30), null);

        metrics = new SimulationTickMetrics(bubble1, bubble2);
    }

    @AfterEach
    void tearDown() {
        if (bubble1 != null) {
            bubble1.close();
        }
        if (bubble2 != null) {
            bubble2.close();
        }
        if (registry != null) {
            registry.close();
        }
    }

    @Test
    void testInitialState() {
        assertThat(metrics.getMigrationsTo1()).isZero();
        assertThat(metrics.getMigrationsTo2()).isZero();
        assertThat(metrics.getMigrationFailures()).isZero();
        assertThat(metrics.getMetrics()).isNotNull();
    }

    @Test
    void testRecordMigrationTo1() {
        metrics.recordMigrationTo1();
        assertThat(metrics.getMigrationsTo1()).isEqualTo(1);
        assertThat(metrics.getMigrationsTo2()).isZero();

        metrics.recordMigrationTo1();
        assertThat(metrics.getMigrationsTo1()).isEqualTo(2);
    }

    @Test
    void testRecordMigrationTo2() {
        metrics.recordMigrationTo2();
        assertThat(metrics.getMigrationsTo2()).isEqualTo(1);
        assertThat(metrics.getMigrationsTo1()).isZero();

        metrics.recordMigrationTo2();
        assertThat(metrics.getMigrationsTo2()).isEqualTo(2);
    }

    @Test
    void testRecordMigrationFailure() {
        metrics.recordMigrationFailure();
        assertThat(metrics.getMigrationFailures()).isEqualTo(1);

        metrics.recordMigrationFailure();
        assertThat(metrics.getMigrationFailures()).isEqualTo(2);
    }

    @Test
    void testRecordTick() {
        long frameTimeNs = 16_000_000; // 16ms in nanoseconds

        metrics.recordTick(frameTimeNs);

        var simulationMetrics = metrics.getMetrics();
        assertThat(simulationMetrics.getTotalTicks()).isEqualTo(1);
        assertThat(simulationMetrics.getAverageFrameTimeMs()).isGreaterThan(0);
    }

    @Test
    void testGetDebugState() {
        metrics.recordMigrationTo1();
        metrics.recordMigrationTo2();
        metrics.recordMigrationTo2();
        metrics.recordMigrationFailure();

        var debugState = metrics.getDebugState(100, 5, 3, 2);

        assertThat(debugState.tickCount()).isEqualTo(100);
        assertThat(debugState.bubble1EntityCount()).isEqualTo(2); // from setUp
        assertThat(debugState.bubble2EntityCount()).isEqualTo(1); // from setUp
        assertThat(debugState.bubble1GhostCount()).isEqualTo(5);
        assertThat(debugState.bubble2GhostCount()).isEqualTo(3);
        assertThat(debugState.migrationsTo1()).isEqualTo(1);
        assertThat(debugState.migrationsTo2()).isEqualTo(2);
        assertThat(debugState.migrationFailures()).isEqualTo(1);
        assertThat(debugState.cooldownsActive()).isEqualTo(2);
        assertThat(debugState.metrics()).isNotNull();
    }

    @Test
    void testToString() {
        var str = metrics.toString();
        assertThat(str).contains("SimulationTickMetrics");
        assertThat(str).contains("bubble1=2");
        assertThat(str).contains("bubble2=1");
        assertThat(str).contains("migrations");
    }

    @Test
    void testLogPeriodicDoesNotThrow() {
        // logPeriodic should only log every 600 ticks, so this won't log
        metrics.logPeriodic(1, 5, 3, 2);

        // This should log (tick 600)
        metrics.logPeriodic(600, 5, 3, 2);

        // Verify no exceptions were thrown
        assertThat(metrics.getMigrationsTo1()).isZero();
    }

    @Test
    void testThreadSafety() throws InterruptedException {
        // Test that concurrent updates work correctly
        var thread1 = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                metrics.recordMigrationTo1();
            }
        });

        var thread2 = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                metrics.recordMigrationTo2();
            }
        });

        var thread3 = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                metrics.recordMigrationFailure();
            }
        });

        thread1.start();
        thread2.start();
        thread3.start();

        thread1.join();
        thread2.join();
        thread3.join();

        assertThat(metrics.getMigrationsTo1()).isEqualTo(100);
        assertThat(metrics.getMigrationsTo2()).isEqualTo(100);
        assertThat(metrics.getMigrationFailures()).isEqualTo(100);
    }
}
