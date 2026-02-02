/*
 * Copyright (c) 2026 Hal Hildebrand. All rights reserved.
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

package com.hellblazer.luciferase.lucien.balancing.fault;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for ForestMonitor.
 * <p>
 * Validates:
 * <ul>
 *   <li>Health snapshot capture from FaultHandler</li>
 *   <li>Partition status aggregation</li>
 *   <li>Alert threshold checking</li>
 *   <li>Dashboard data export</li>
 *   <li>History tracking</li>
 *   <li>Periodic polling</li>
 * </ul>
 * <p>
 * Part of F4.2.5 Monitoring & Documentation (bead: Luciferase-u3hz).
 *
 * @author hal.hildebrand
 */
class ForestMonitorTest {

    private InMemoryPartitionTopology topology;
    private DefaultFaultHandler faultHandler;
    private ForestMonitor monitor;
    private AtomicLong clock;
    private ScheduledExecutorService executor;

    @BeforeEach
    void setup() {
        clock = new AtomicLong(1000L);
        topology = new InMemoryPartitionTopology();

        var config = new FaultConfiguration(
            100,   // suspectTimeoutMs
            500,   // failureConfirmationMs
            3,     // maxRecoveryRetries
            5000,  // recoveryTimeoutMs
            true,  // autoRecoveryEnabled
            3      // maxConcurrentRecoveries
        );
        faultHandler = new DefaultFaultHandler(config, topology);

        // Inject deterministic clock
        faultHandler.setClock(new Object() {
            public long currentTimeMillis() {
                return clock.get();
            }
        });

        executor = Executors.newSingleThreadScheduledExecutor();
    }

    @AfterEach
    void cleanup() {
        if (monitor != null) {
            monitor.stop();
        }
        if (faultHandler != null) {
            faultHandler.stop();
        }
        if (executor != null) {
            executor.shutdown();
        }
    }

    // ========== Health Snapshot Tests ==========

    @Test
    void testHealthSnapshot_allHealthy() {
        // Setup: 3 healthy partitions
        var partitions = setupPartitions(3);
        faultHandler.startMonitoring();

        monitor = new ForestMonitor.Builder()
            .withFaultHandler(faultHandler)
            .withTopology(topology)
            .withTimeSource(clock::get)
            .build();

        // Get snapshot
        var snapshot = monitor.getHealthSnapshot();

        // Assert
        assertThat(snapshot.totalPartitions()).isEqualTo(3);
        assertThat(snapshot.healthyPartitions()).isEqualTo(3);
        assertThat(snapshot.suspectedPartitions()).isEqualTo(0);
        assertThat(snapshot.failedPartitions()).isEqualTo(0);
        assertThat(snapshot.quorumMaintained()).isTrue();
        assertThat(snapshot.healthLevel()).isEqualTo(ForestHealthSnapshot.HealthLevel.HEALTHY);
    }

    @Test
    void testHealthSnapshot_degraded_withSuspected() {
        // Setup: 3 partitions, 1 suspected
        var partitions = setupPartitions(3);
        faultHandler.startMonitoring();

        // Trigger SUSPECTED on partition 0
        faultHandler.reportBarrierTimeout(partitions.get(0));
        faultHandler.reportBarrierTimeout(partitions.get(0));

        monitor = new ForestMonitor.Builder()
            .withFaultHandler(faultHandler)
            .withTopology(topology)
            .withTimeSource(clock::get)
            .build();

        var snapshot = monitor.getHealthSnapshot();

        assertThat(snapshot.healthyPartitions()).isEqualTo(2);
        assertThat(snapshot.suspectedPartitions()).isEqualTo(1);
        assertThat(snapshot.failedPartitions()).isEqualTo(0);
        assertThat(snapshot.quorumMaintained()).isTrue();
        assertThat(snapshot.healthLevel()).isEqualTo(ForestHealthSnapshot.HealthLevel.DEGRADED);
    }

    @Test
    void testHealthSnapshot_critical_withFailed() {
        // Setup: 3 partitions, 1 failed
        var partitions = setupPartitions(3);
        faultHandler.startMonitoring();

        // Trigger SUSPECTED then FAILED
        faultHandler.reportBarrierTimeout(partitions.get(0));
        faultHandler.reportBarrierTimeout(partitions.get(0));
        clock.addAndGet(600); // Past failureConfirmationMs
        faultHandler.checkTimeouts();

        monitor = new ForestMonitor.Builder()
            .withFaultHandler(faultHandler)
            .withTopology(topology)
            .withTimeSource(clock::get)
            .build();

        var snapshot = monitor.getHealthSnapshot();

        assertThat(snapshot.healthyPartitions()).isEqualTo(2);
        assertThat(snapshot.suspectedPartitions()).isEqualTo(0);
        assertThat(snapshot.failedPartitions()).isEqualTo(1);
        assertThat(snapshot.quorumMaintained()).isTrue();
        assertThat(snapshot.healthLevel()).isEqualTo(ForestHealthSnapshot.HealthLevel.CRITICAL);
    }

    @Test
    void testHealthSnapshot_quorumLost() {
        // Setup: 3 partitions, 2 failed (quorum lost)
        var partitions = setupPartitions(3);
        faultHandler.startMonitoring();

        // Fail 2 partitions
        for (int i = 0; i < 2; i++) {
            faultHandler.reportBarrierTimeout(partitions.get(i));
            faultHandler.reportBarrierTimeout(partitions.get(i));
        }
        clock.addAndGet(600);
        faultHandler.checkTimeouts();

        monitor = new ForestMonitor.Builder()
            .withFaultHandler(faultHandler)
            .withTopology(topology)
            .withTimeSource(clock::get)
            .build();

        var snapshot = monitor.getHealthSnapshot();

        assertThat(snapshot.healthyPartitions()).isEqualTo(1);
        assertThat(snapshot.failedPartitions()).isEqualTo(2);
        assertThat(snapshot.quorumMaintained()).isFalse();
        assertThat(snapshot.healthLevel()).isEqualTo(ForestHealthSnapshot.HealthLevel.QUORUM_LOST);
    }

    // ========== Alert Threshold Tests ==========

    @Test
    void testAlertThresholds_defaultThresholds_alertOnFailed() {
        // Setup: 3 partitions, 1 failed
        var partitions = setupPartitions(3);
        faultHandler.startMonitoring();

        // Fail 1 partition
        faultHandler.reportBarrierTimeout(partitions.get(0));
        faultHandler.reportBarrierTimeout(partitions.get(0));
        clock.addAndGet(600);
        faultHandler.checkTimeouts();

        var alerts = new CopyOnWriteArrayList<ForestHealthSnapshot>();

        monitor = new ForestMonitor.Builder()
            .withFaultHandler(faultHandler)
            .withTopology(topology)
            .withTimeSource(clock::get)
            .withAlertThresholds(ForestHealthSnapshot.AlertThresholds.defaultThresholds())
            .withAlertCallback(alerts::add)
            .build();

        // Poll to trigger alert
        monitor.poll();

        // Alert should be fired (default threshold: maxFailedPartitions=0)
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).failedPartitions()).isEqualTo(1);
    }

    @Test
    void testAlertThresholds_relaxedThresholds_noAlertOnOneFailed() {
        // Setup: 3 partitions, 1 failed
        var partitions = setupPartitions(3);
        faultHandler.startMonitoring();

        // Fail 1 partition
        faultHandler.reportBarrierTimeout(partitions.get(0));
        faultHandler.reportBarrierTimeout(partitions.get(0));
        clock.addAndGet(600);
        faultHandler.checkTimeouts();

        var alerts = new CopyOnWriteArrayList<ForestHealthSnapshot>();

        monitor = new ForestMonitor.Builder()
            .withFaultHandler(faultHandler)
            .withTopology(topology)
            .withTimeSource(clock::get)
            .withAlertThresholds(ForestHealthSnapshot.AlertThresholds.relaxedThresholds())
            .withAlertCallback(alerts::add)
            .build();

        // Poll
        monitor.poll();

        // No alert (relaxed threshold allows 1 failed)
        assertThat(alerts).isEmpty();
    }

    // ========== Dashboard Export Tests ==========

    @Test
    void testDashboardExport_containsAllSections() {
        // Setup
        var partitions = setupPartitions(3);
        faultHandler.startMonitoring();

        monitor = new ForestMonitor.Builder()
            .withFaultHandler(faultHandler)
            .withTopology(topology)
            .withTimeSource(clock::get)
            .build();

        var data = monitor.exportDashboardData();

        // Verify all sections present
        assertThat(data).containsKey("timestamp");
        assertThat(data).containsKey("healthLevel");
        assertThat(data).containsKey("summary");
        assertThat(data).containsKey("partitions");
        assertThat(data).containsKey("forest");
        assertThat(data).containsKey("performance");
        assertThat(data).containsKey("recovery");
        assertThat(data).containsKey("partitionStatuses");

        // Verify partition section
        @SuppressWarnings("unchecked")
        var partitionsData = (java.util.Map<String, Object>) data.get("partitions");
        assertThat(partitionsData.get("total")).isEqualTo(3);
        assertThat(partitionsData.get("healthy")).isEqualTo(3);
        assertThat(partitionsData.get("quorumMaintained")).isEqualTo(true);
    }

    // ========== History Tracking Tests ==========

    @Test
    void testHistory_tracksSnapshots() {
        var partitions = setupPartitions(3);
        faultHandler.startMonitoring();

        monitor = new ForestMonitor.Builder()
            .withFaultHandler(faultHandler)
            .withTopology(topology)
            .withTimeSource(clock::get)
            .withHistorySize(5)
            .build();

        // Poll 3 times
        for (int i = 0; i < 3; i++) {
            clock.addAndGet(100);
            monitor.poll();
        }

        var history = monitor.getHistory();
        assertThat(history).hasSize(3);

        // Verify timestamps are ordered
        for (int i = 1; i < history.size(); i++) {
            assertThat(history.get(i).timestampMs())
                .isGreaterThan(history.get(i - 1).timestampMs());
        }
    }

    @Test
    void testHistory_respectsMaxSize() {
        var partitions = setupPartitions(3);
        faultHandler.startMonitoring();

        monitor = new ForestMonitor.Builder()
            .withFaultHandler(faultHandler)
            .withTopology(topology)
            .withTimeSource(clock::get)
            .withHistorySize(3)
            .build();

        // Poll 5 times
        for (int i = 0; i < 5; i++) {
            clock.addAndGet(100);
            monitor.poll();
        }

        var history = monitor.getHistory();
        assertThat(history).hasSize(3);

        // Verify oldest snapshots are removed
        assertThat(history.get(0).timestampMs()).isEqualTo(1300L); // 1000 + 100*3
    }

    // ========== Periodic Polling Tests ==========

    @Test
    void testPeriodicPolling_capturesSnapshots() throws Exception {
        var partitions = setupPartitions(3);
        faultHandler.startMonitoring();

        var snapshots = new CopyOnWriteArrayList<ForestHealthSnapshot>();

        monitor = new ForestMonitor.Builder()
            .withFaultHandler(faultHandler)
            .withTopology(topology)
            .withTimeSource(System::currentTimeMillis) // Use real time for polling
            .withAlertCallback(snapshots::add) // Won't fire, just counting
            .build();

        // Start polling every 50ms
        monitor.start(executor, 50, TimeUnit.MILLISECONDS);

        // Wait for a few polls
        Thread.sleep(200);

        monitor.stop();

        // Verify snapshots were captured
        var history = monitor.getHistory();
        assertThat(history.size()).isGreaterThanOrEqualTo(2);
    }

    // ========== Summary String Tests ==========

    @Test
    void testSummary_formatValid() {
        var partitions = setupPartitions(3);
        faultHandler.startMonitoring();

        monitor = new ForestMonitor.Builder()
            .withFaultHandler(faultHandler)
            .withTopology(topology)
            .withTimeSource(clock::get)
            .build();

        var snapshot = monitor.getHealthSnapshot();
        var summary = snapshot.toSummary();

        assertThat(summary).contains("Health: HEALTHY");
        assertThat(summary).contains("Partitions: 3/3 healthy");
        assertThat(summary).contains("Recovery:");
        assertThat(summary).contains("Latency:");
    }

    // ========== Helper Methods ==========

    private java.util.List<UUID> setupPartitions(int count) {
        var partitions = new ArrayList<UUID>();
        for (int i = 0; i < count; i++) {
            var id = UUID.randomUUID();
            partitions.add(id);
            topology.register(id, i);
        }
        return partitions;
    }
}
