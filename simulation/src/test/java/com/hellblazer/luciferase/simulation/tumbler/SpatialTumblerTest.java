/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.tumbler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for SpatialTumbler power-of-2 random choices load balancing.
 */
class SpatialTumblerTest {

    private static final byte REGION_LEVEL = 5;
    private static final double TARGET_FRAME_MS = 16.0;

    private SpatialTumbler tumbler;

    @BeforeEach
    void setUp() {
        tumbler = new SpatialTumbler(REGION_LEVEL, TARGET_FRAME_MS);
    }

    @Test
    void testRegisterServer() {
        var serverId = UUID.randomUUID();

        var metrics = tumbler.registerServer(serverId);

        assertThat(metrics).isNotNull();
        assertThat(metrics.serverId()).isEqualTo(serverId);
        assertThat(tumbler.getAllServers()).contains(serverId);
    }

    @Test
    void testServerMetrics_utilizationTracking() {
        var serverId = UUID.randomUUID();
        var metrics = tumbler.registerServer(serverId);

        // Initially zero
        assertThat(metrics.utilizationPercent()).isEqualTo(0.0);

        // Record frame times
        metrics.recordFrameTime(16.0);  // 100% utilization
        assertThat(metrics.utilizationPercent()).isCloseTo(30.0, within(1.0)); // EWMA alpha=0.3

        metrics.recordFrameTime(16.0);
        assertThat(metrics.utilizationPercent()).isCloseTo(51.0, within(1.0)); // EWMA continues

        metrics.recordFrameTime(32.0);  // 200% (overloaded)
        assertThat(metrics.utilizationPercent()).isGreaterThan(90.0);
    }

    @Test
    void testGetRegion_createsNewRegion() {
        var position = new Point3f(50.0f, 50.0f, 50.0f);

        var region = tumbler.getRegion(position);

        assertThat(region).isNotNull();
        // Grid size = 2^5 = 32
        assertThat(region.gridSize()).isEqualTo(1 << REGION_LEVEL);
    }

    @Test
    void testGetRegion_samePositionReturnsSameRegion() {
        var pos1 = new Point3f(50.0f, 50.0f, 50.0f);
        var pos2 = new Point3f(51.0f, 51.0f, 51.0f);

        var region1 = tumbler.getRegion(pos1);
        var region2 = tumbler.getRegion(pos2);

        // Nearby positions should be in the same region (grid size = 2^5 = 32)
        assertThat(region1.regionId()).isEqualTo(region2.regionId());
    }

    @Test
    void testSelectServer_singleServer() {
        var serverId = UUID.randomUUID();
        tumbler.registerServer(serverId);

        var selected = tumbler.selectServer(new Point3f(50.0f, 50.0f, 50.0f));

        // With only one server, it must be selected (from global fallback)
        assertThat(selected).isPresent();
        assertThat(selected.get()).isEqualTo(serverId);
    }

    @Test
    void testSelectServer_powerOf2_prefersLowerUtilization() {
        // Register 2 servers with different loads
        var lowLoadServer = UUID.randomUUID();
        var highLoadServer = UUID.randomUUID();

        var lowMetrics = tumbler.registerServer(lowLoadServer);
        var highMetrics = tumbler.registerServer(highLoadServer);

        // Set utilization
        lowMetrics.recordFrameTime(8.0);   // 50% of target
        highMetrics.recordFrameTime(24.0); // 150% of target

        // Assign both to a region
        var position = new Point3f(50.0f, 50.0f, 50.0f);
        var region = tumbler.getRegion(position);
        region.addServer(lowLoadServer, lowMetrics);
        region.addServer(highLoadServer, highMetrics);

        // Run many selections - should prefer low load server
        int lowSelected = 0;
        int highSelected = 0;
        for (int i = 0; i < 1000; i++) {
            var selected = tumbler.selectServer(position);
            if (selected.isPresent()) {
                if (selected.get().equals(lowLoadServer)) {
                    lowSelected++;
                } else {
                    highSelected++;
                }
            }
        }

        // Power-of-2 should favor lower utilization
        // With 50% vs 150%, low should be selected much more often
        assertThat(lowSelected).isGreaterThan(highSelected);
        assertThat((double) lowSelected / 1000).isGreaterThan(0.6);
    }

    @Test
    void testSelectServer_equalLoad_approximatelyEqual() {
        var server1 = UUID.randomUUID();
        var server2 = UUID.randomUUID();

        var metrics1 = tumbler.registerServer(server1);
        var metrics2 = tumbler.registerServer(server2);

        // Set equal utilization
        metrics1.recordFrameTime(16.0);
        metrics2.recordFrameTime(16.0);

        var position = new Point3f(50.0f, 50.0f, 50.0f);
        var region = tumbler.getRegion(position);
        region.addServer(server1, metrics1);
        region.addServer(server2, metrics2);

        // Run selections
        int server1Selected = 0;
        for (int i = 0; i < 1000; i++) {
            var selected = tumbler.selectServer(position);
            if (selected.isPresent() && selected.get().equals(server1)) {
                server1Selected++;
            }
        }

        // Should be approximately 50/50
        assertThat((double) server1Selected / 1000).isBetween(0.4, 0.6);
    }

    @Test
    void testFindMigrationCandidates_noOverload() {
        var server1 = UUID.randomUUID();
        var server2 = UUID.randomUUID();

        var metrics1 = tumbler.registerServer(server1);
        var metrics2 = tumbler.registerServer(server2);

        // Both servers at normal load
        metrics1.recordFrameTime(12.0);
        metrics2.recordFrameTime(14.0);

        var position = new Point3f(50.0f, 50.0f, 50.0f);
        var region = tumbler.getRegion(position);
        region.addServer(server1, metrics1);
        region.addServer(server2, metrics2);

        var candidates = tumbler.findMigrationCandidates();

        assertThat(candidates).isEmpty();
    }

    @Test
    void testFindMigrationCandidates_withOverloadAndUnderload() {
        var overloadedServer = UUID.randomUUID();
        var underloadedServer = UUID.randomUUID();

        var overloadedMetrics = tumbler.registerServer(overloadedServer);
        var underloadedMetrics = tumbler.registerServer(underloadedServer);

        // Simulate utilization buildup via EWMA
        for (int i = 0; i < 10; i++) {
            overloadedMetrics.recordFrameTime(28.0);  // High load
            underloadedMetrics.recordFrameTime(6.0);  // Low load
        }

        var position = new Point3f(50.0f, 50.0f, 50.0f);
        var region = tumbler.getRegion(position);
        region.addServer(overloadedServer, overloadedMetrics);
        region.addServer(underloadedServer, underloadedMetrics);

        var candidates = tumbler.findMigrationCandidates();

        assertThat(candidates).hasSize(1);
        var candidate = candidates.get(0);
        assertThat(candidate.sourceServer()).isEqualTo(overloadedServer);
        assertThat(candidate.targetServer()).isEqualTo(underloadedServer);
    }

    @Test
    void testCalculateOverallImbalance() {
        var server1 = UUID.randomUUID();
        var server2 = UUID.randomUUID();
        var server3 = UUID.randomUUID();

        var metrics1 = tumbler.registerServer(server1);
        var metrics2 = tumbler.registerServer(server2);
        var metrics3 = tumbler.registerServer(server3);

        // Different loads
        for (int i = 0; i < 10; i++) {
            metrics1.recordFrameTime(8.0);   // Low
            metrics2.recordFrameTime(16.0);  // Medium
            metrics3.recordFrameTime(24.0);  // High
        }

        double imbalance = tumbler.calculateOverallImbalance();

        // Imbalance = (max - min) / avg
        assertThat(imbalance).isGreaterThan(0.0);
        assertThat(imbalance).isLessThan(2.0);
    }

    @Test
    void testLoadImbalance_targetUnder20Percent() {
        // Create 5 servers
        var servers = new ArrayList<UUID>();
        var metricsList = new ArrayList<ServerMetrics>();
        for (int i = 0; i < 5; i++) {
            var id = UUID.randomUUID();
            servers.add(id);
            metricsList.add(tumbler.registerServer(id));
        }

        // Set similar loads (within 20%)
        double baseFrameTime = 14.0;
        for (int j = 0; j < 20; j++) {
            for (int i = 0; i < 5; i++) {
                // Vary frame time by up to 10%
                double variance = 1.0 + (i - 2) * 0.05;  // 0.9 to 1.1
                metricsList.get(i).recordFrameTime(baseFrameTime * variance);
            }
        }

        double imbalance = tumbler.calculateOverallImbalance();

        // Target: imbalance < 20% (with small tolerance for floating point)
        assertThat(imbalance).isLessThanOrEqualTo(0.21);
    }

    @Test
    void testServerMetrics_bubbleTracking() {
        var serverId = UUID.randomUUID();
        var metrics = tumbler.registerServer(serverId);

        assertThat(metrics.bubbleCount()).isEqualTo(0);
        assertThat(metrics.entityCount()).isEqualTo(0);

        metrics.addBubble(50);
        assertThat(metrics.bubbleCount()).isEqualTo(1);
        assertThat(metrics.entityCount()).isEqualTo(50);

        metrics.addBubble(30);
        assertThat(metrics.bubbleCount()).isEqualTo(2);
        assertThat(metrics.entityCount()).isEqualTo(80);

        metrics.removeBubble(50);
        assertThat(metrics.bubbleCount()).isEqualTo(1);
        assertThat(metrics.entityCount()).isEqualTo(30);
    }

    @Test
    void testTumblerRegion_selectLeastLoaded() {
        var region = tumbler.getRegion(new Point3f(50.0f, 50.0f, 50.0f));

        var server1 = UUID.randomUUID();
        var server2 = UUID.randomUUID();
        var metrics1 = new ServerMetrics(server1, TARGET_FRAME_MS);
        var metrics2 = new ServerMetrics(server2, TARGET_FRAME_MS);

        for (int i = 0; i < 10; i++) {
            metrics1.recordFrameTime(20.0);  // Higher load
            metrics2.recordFrameTime(10.0);  // Lower load
        }

        region.addServer(server1, metrics1);
        region.addServer(server2, metrics2);

        var leastLoaded = region.findLeastLoadedServer();

        assertThat(leastLoaded).isPresent();
        assertThat(leastLoaded.get()).isEqualTo(server2);
    }

    @Test
    void testTumblerRegion_calculateImbalance() {
        var region = tumbler.getRegion(new Point3f(50.0f, 50.0f, 50.0f));

        var server1 = UUID.randomUUID();
        var server2 = UUID.randomUUID();
        var metrics1 = new ServerMetrics(server1, TARGET_FRAME_MS);
        var metrics2 = new ServerMetrics(server2, TARGET_FRAME_MS);

        // High imbalance: 50% vs 150%
        for (int i = 0; i < 10; i++) {
            metrics1.recordFrameTime(8.0);   // 50%
            metrics2.recordFrameTime(24.0);  // 150%
        }

        region.addServer(server1, metrics1);
        region.addServer(server2, metrics2);

        double imbalance = region.calculateImbalance();

        // (max - min) / avg = (1.5 - 0.5) / 1.0 = 1.0
        assertThat(imbalance).isGreaterThan(0.5);
    }

    @Test
    void testUnregisterServer() {
        var serverId = UUID.randomUUID();
        tumbler.registerServer(serverId);

        var position = new Point3f(50.0f, 50.0f, 50.0f);
        var region = tumbler.getRegion(position);
        region.addServer(serverId, tumbler.getServerMetrics(serverId));

        assertThat(region.getServers()).contains(serverId);

        tumbler.unregisterServer(serverId);

        assertThat(tumbler.getAllServers()).doesNotContain(serverId);
        assertThat(region.getServers()).doesNotContain(serverId);
    }
}
