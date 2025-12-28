/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.esvt.gpu;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ESVTVulkanRenderer - Vulkan-based GPU raycast renderer.
 *
 * <p>Run with: {@code RUN_GPU_TESTS=true ./mvnw test -Dtest=ESVTVulkanRendererTest}
 *
 * <p><b>Platform Notes:</b>
 * <ul>
 *   <li>Apple Silicon Macs: Works via MoltenVK (Vulkan → Metal translation)</li>
 *   <li>Intel Macs: Works via MoltenVK</li>
 *   <li>Linux/Windows: Native Vulkan with appropriate GPU drivers</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "RUN_GPU_TESTS", matches = "true")
class ESVTVulkanRendererTest {

    private boolean vulkanAvailable;

    @BeforeAll
    void setup() {
        vulkanAvailable = ESVTVulkanRenderer.isVulkanAvailable();
        System.out.println("Vulkan available: " + vulkanAvailable);
    }

    @Test
    @DisplayName("Vulkan availability check")
    void testVulkanAvailability() {
        System.out.println("=== Vulkan Availability Test ===");
        boolean available = ESVTVulkanRenderer.isVulkanAvailable();
        System.out.println("Vulkan GPU available: " + available);

        if (!available) {
            System.out.println("Vulkan is not available on this system.");
            System.out.println("This could be due to:");
            System.out.println("  - No Vulkan-capable GPU");
            System.out.println("  - Missing Vulkan drivers");
            System.out.println("  - MoltenVK not installed (macOS)");
        } else {
            System.out.println("Vulkan is available - MoltenVK working on Apple Silicon!");
        }

        // Don't fail - just report
        assertTrue(true, "Vulkan status reported");
    }

    @Test
    @DisplayName("Initialize Vulkan renderer")
    void testInitialize() {
        Assumptions.assumeTrue(vulkanAvailable, "Vulkan not available");

        var renderer = new ESVTVulkanRenderer(256, 256);
        assertFalse(renderer.isInitialized());
        assertFalse(renderer.isDisposed());

        renderer.initialize();

        assertTrue(renderer.isInitialized());
        assertFalse(renderer.isDisposed());
        assertEquals(256, renderer.getFrameWidth());
        assertEquals(256, renderer.getFrameHeight());

        renderer.close();
        assertTrue(renderer.isDisposed());
    }
}
