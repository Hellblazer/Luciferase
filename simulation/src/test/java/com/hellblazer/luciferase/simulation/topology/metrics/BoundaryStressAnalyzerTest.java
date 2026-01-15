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
package com.hellblazer.luciferase.simulation.topology.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for BoundaryStressAnalyzer sliding window tracking.
 *
 * @author hal.hildebrand
 */
class BoundaryStressAnalyzerTest {

    private BoundaryStressAnalyzer analyzer;
    private UUID bubble1;
    private UUID bubble2;

    @BeforeEach
    void setUp() {
        analyzer = new BoundaryStressAnalyzer(60000); // 60-second window
        bubble1 = UUID.randomUUID();
        bubble2 = UUID.randomUUID();
    }

    @Test
    void testMigrationRateCalculation() {
        long now = System.currentTimeMillis();

        // Record 20 migrations over 2 seconds (10 per second)
        for (int i = 0; i < 20; i++) {
            analyzer.recordMigration(bubble1, now + i * 100); // 100ms intervals = 10/sec
        }

        float rate = analyzer.getMigrationRate(bubble1);
        assertTrue(rate >= 9.0f && rate <= 11.0f, "Migration rate should be ~10/sec, got: " + rate);
    }

    @Test
    void testSlidingWindowExpiration() {
        long now = System.currentTimeMillis();

        // Record migrations at T=0
        for (int i = 0; i < 10; i++) {
            analyzer.recordMigration(bubble1, now + i * 100);
        }

        // Check rate immediately
        float rate1 = analyzer.getMigrationRate(bubble1);
        assertTrue(rate1 > 0, "Should have non-zero rate immediately after migrations");

        // Simulate time passing (61 seconds - outside window)
        analyzer.cleanOldEntries(now + 61000);

        // Check rate after expiration
        float rate2 = analyzer.getMigrationRate(bubble1);
        assertEquals(0.0f, rate2, 0.01f, "Rate should be 0 after window expiration");
    }

    @Test
    void testHotspotDetection() {
        long now = System.currentTimeMillis();

        // Bubble 1: High stress (15 migrations/second)
        for (int i = 0; i < 150; i++) {
            analyzer.recordMigration(bubble1, now + i * 66); // ~66ms intervals = 15/sec
        }

        // Bubble 2: Normal stress (5 migrations/second)
        for (int i = 0; i < 50; i++) {
            analyzer.recordMigration(bubble2, now + i * 200); // 200ms intervals = 5/sec
        }

        assertTrue(analyzer.hasHighBoundaryStress(bubble1, 10.0f),
                  "Bubble 1 should have high stress (>10/sec)");
        assertFalse(analyzer.hasHighBoundaryStress(bubble2, 10.0f),
                   "Bubble 2 should not have high stress (<10/sec)");
    }

    @Test
    void testMultipleBubbleTracking() {
        long now = System.currentTimeMillis();

        var bubble3 = UUID.randomUUID();

        // Record different rates for each bubble (different intervals = different rates)
        for (int i = 0; i < 30; i++) {
            analyzer.recordMigration(bubble1, now + i * 50); // 50ms intervals = 20/sec
        }
        for (int i = 0; i < 20; i++) {
            analyzer.recordMigration(bubble2, now + i * 100); // 100ms intervals = 10/sec
        }
        for (int i = 0; i < 10; i++) {
            analyzer.recordMigration(bubble3, now + i * 200); // 200ms intervals = 5/sec
        }

        float rate1 = analyzer.getMigrationRate(bubble1);
        float rate2 = analyzer.getMigrationRate(bubble2);
        float rate3 = analyzer.getMigrationRate(bubble3);

        assertTrue(rate1 > 0, "Bubble 1 should have migrations");
        assertTrue(rate2 > 0, "Bubble 2 should have migrations");
        assertTrue(rate3 > 0, "Bubble 3 should have migrations");

        // Verify rate ordering (bubble1: 20/sec > bubble2: 10/sec > bubble3: 5/sec)
        assertTrue(rate1 > rate2,
                  "Bubble 1 should have higher rate than Bubble 2 (got " + rate1 + " vs " + rate2 + ")");
        assertTrue(rate2 > rate3,
                  "Bubble 2 should have higher rate than Bubble 3 (got " + rate2 + " vs " + rate3 + ")");
    }

    @Test
    void testEmptyBubble() {
        var emptyBubble = UUID.randomUUID();

        float rate = analyzer.getMigrationRate(emptyBubble);
        assertEquals(0.0f, rate, 0.01f, "Empty bubble should have 0 migration rate");

        assertFalse(analyzer.hasHighBoundaryStress(emptyBubble, 10.0f),
                   "Empty bubble should not have high stress");
    }

    @Test
    void testRecentWindow() {
        long now = System.currentTimeMillis();

        // Old migrations (outside window)
        for (int i = 0; i < 10; i++) {
            analyzer.recordMigration(bubble1, now - 70000 + i * 100); // 70 seconds ago
        }

        // Recent migrations (inside window)
        for (int i = 0; i < 20; i++) {
            analyzer.recordMigration(bubble1, now + i * 100);
        }

        // Clean old entries
        analyzer.cleanOldEntries(now);

        // Rate should only reflect recent migrations
        float rate = analyzer.getMigrationRate(bubble1);
        assertTrue(rate >= 9.0f && rate <= 11.0f,
                  "Should only count recent migrations, got rate: " + rate);
    }
}
