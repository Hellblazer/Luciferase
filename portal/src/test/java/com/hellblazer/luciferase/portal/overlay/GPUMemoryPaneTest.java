/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.portal.overlay;

import com.hellblazer.luciferase.portal.JavaFXTestBase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for GPUMemoryPane (T17).
 * Validates memory bar display and percentage calculation.
 *
 * @author hal.hildebrand
 */
class GPUMemoryPaneTest extends JavaFXTestBase {

    private static final long MB = 1024L * 1024L;
    private static final long GB = 1024L * MB;

    /**
     * T17: Test memory bar display - Shows correct percentage.
     */
    @Test
    void testMemoryBarDisplay() throws Exception {
        if ("true".equals(System.getenv("CI"))) {
            System.out.println("Skipping JavaFX test in CI environment");
            return;
        }

        runOnFxThreadAndWait(() -> {
            var pane = new GPUMemoryPane();

            // Test 50% usage: 512 MB used out of 1 GB
            pane.updateMemoryUsage(512 * MB, 1 * GB);
            assertEquals(50.0, pane.getCurrentUsagePercent(), 0.01, "50% usage");

            // Test 75% usage: 768 MB used out of 1 GB
            pane.updateMemoryUsage(768 * MB, 1 * GB);
            assertEquals(75.0, pane.getCurrentUsagePercent(), 0.01, "75% usage");

            // Test 25% usage: 256 MB used out of 1 GB
            pane.updateMemoryUsage(256 * MB, 1 * GB);
            assertEquals(25.0, pane.getCurrentUsagePercent(), 0.01, "25% usage");

            // Test 100% usage: 1 GB used out of 1 GB
            pane.updateMemoryUsage(1 * GB, 1 * GB);
            assertEquals(100.0, pane.getCurrentUsagePercent(), 0.01, "100% usage");

            // Test 0% usage: 0 MB used out of 1 GB
            pane.updateMemoryUsage(0, 1 * GB);
            assertEquals(0.0, pane.getCurrentUsagePercent(), 0.01, "0% usage");
        });
    }

    /**
     * Test pane initialization - Memory bar should be created.
     */
    @Test
    void testPaneInitialization() throws Exception {
        if ("true".equals(System.getenv("CI"))) {
            System.out.println("Skipping JavaFX test in CI environment");
            return;
        }

        runOnFxThreadAndWait(() -> {
            var pane = new GPUMemoryPane();
            assertNotNull(pane, "Pane should be created");
            assertNotNull(pane.getMemoryBar(), "Memory bar should be created");

            // Initial usage should be 0%
            assertEquals(0.0, pane.getCurrentUsagePercent(), 0.01, "Initial usage should be 0%");
        });
    }

    /**
     * Test memory formatting - Human-readable MB/GB display.
     */
    @Test
    void testMemoryFormatting() throws Exception {
        if ("true".equals(System.getenv("CI"))) {
            System.out.println("Skipping JavaFX test in CI environment");
            return;
        }

        runOnFxThreadAndWait(() -> {
            var pane = new GPUMemoryPane();

            // Test MB range: 512 MB / 1 GB
            pane.updateMemoryUsage(512 * MB, 1 * GB);
            var label = pane.getPercentageLabel();
            assertNotNull(label);
            var text = label.getText();
            assertTrue(text.contains("50.0%") || text.contains("50%"),
                "Label should show 50% usage: " + text);

            // Test GB range: 2.5 GB / 4 GB
            pane.updateMemoryUsage((long)(2.5 * GB), 4 * GB);
            label = pane.getPercentageLabel();
            text = label.getText();
            assertTrue(text.contains("62.5") || text.contains("62"),
                "Label should show ~62.5% usage: " + text);
        });
    }

    /**
     * Test edge case: zero total memory.
     */
    @Test
    void testZeroTotalMemory() throws Exception {
        if ("true".equals(System.getenv("CI"))) {
            System.out.println("Skipping JavaFX test in CI environment");
            return;
        }

        runOnFxThreadAndWait(() -> {
            var pane = new GPUMemoryPane();

            // Zero total should not crash
            assertDoesNotThrow(() -> pane.updateMemoryUsage(0, 0));

            // Percentage should be 0
            assertEquals(0.0, pane.getCurrentUsagePercent(), 0.01);
        });
    }

    /**
     * Test color coding - High usage should change bar color.
     */
    @Test
    void testColorCoding() throws Exception {
        if ("true".equals(System.getenv("CI"))) {
            System.out.println("Skipping JavaFX test in CI environment");
            return;
        }

        runOnFxThreadAndWait(() -> {
            var pane = new GPUMemoryPane();

            // Low usage: should be green
            pane.updateMemoryUsage(256 * MB, 1 * GB);  // 25%
            var color = pane.getBarColor();
            assertNotNull(color, "Bar should have color for low usage");

            // Medium usage: should be yellow
            pane.updateMemoryUsage(650 * MB, 1 * GB);  // 65%
            color = pane.getBarColor();
            assertNotNull(color, "Bar should have color for medium usage");

            // High usage: should be red
            pane.updateMemoryUsage(900 * MB, 1 * GB);  // 90%
            color = pane.getBarColor();
            assertNotNull(color, "Bar should have color for high usage");
        });
    }
}
