/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of Luciferase.
 *
 * Luciferase is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Luciferase is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Luciferase. If not, see <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.lucien.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for VectorAPISupport - SIMD infrastructure detection and configuration.
 * 
 * Part of Epic 1 (Bead 1.0): SIMD Infrastructure Setup
 * 
 * @author Hal Hildebrand
 */
class VectorAPISupportTest {

    @Test
    void testVectorAPISupportAvailability() {
        // This test verifies that VectorAPISupport loads successfully
        // The actual availability depends on whether --add-modules jdk.incubator.vector is set
        
        assertNotNull(VectorAPISupport.getCPUCapability(), 
                     "CPU capability should always be detected");
        
        assertNotNull(VectorAPISupport.getStatus(), 
                     "Status message should always be available");
        
        // Log current status for debugging
        System.out.println("VectorAPISupport Status: " + VectorAPISupport.getStatus());
        System.out.println("CPU Capability: " + VectorAPISupport.getCPUCapability());
        System.out.println("Vector API Present: " + VectorAPISupport.isVectorAPIPresent());
        System.out.println("SIMD Available: " + VectorAPISupport.isAvailable());
    }

    @Test
    void testCPUCapabilityDetection() {
        var capability = VectorAPISupport.getCPUCapability();
        
        assertNotNull(capability, "CPU capability must be detected");
        
        // Verify the capability has valid properties
        assertNotNull(capability.getDescription(), "Capability description should exist");
        assertTrue(capability.getLanes() > 0, "Lanes should be positive");
        
        System.out.println("Detected CPU Capability: " + capability);
        System.out.println("  Description: " + capability.getDescription());
        System.out.println("  Lanes (64-bit): " + capability.getLanes());
    }

    @Test
    void testVectorAPIEnableDisable() {
        // Test runtime enable/disable functionality
        boolean initialState = VectorAPISupport.isAvailable();
        
        // Try to enable
        boolean enableResult = VectorAPISupport.setEnabled(true);
        
        if (VectorAPISupport.isVectorAPIPresent()) {
            assertTrue(enableResult, "Should be able to enable when Vector API is present");
            assertTrue(VectorAPISupport.isAvailable(), "SIMD should be available after enable");
        } else {
            assertFalse(enableResult, "Should not be able to enable when Vector API is absent");
            assertFalse(VectorAPISupport.isAvailable(), "SIMD should not be available without Vector API");
        }
        
        // Try to disable
        VectorAPISupport.setEnabled(false);
        assertFalse(VectorAPISupport.isAvailable(), "SIMD should not be available after disable");
        
        // Restore initial state
        VectorAPISupport.setEnabled(initialState);
    }

    @Test
    void testVectorAPIStatusMessage() {
        String status = VectorAPISupport.getStatus();
        
        assertNotNull(status, "Status message should not be null");
        assertFalse(status.isEmpty(), "Status message should not be empty");
        
        // Status should mention key information
        assertTrue(status.contains("Vector API") || status.contains("SIMD"), 
                  "Status should mention Vector API or SIMD");
        
        System.out.println("Full Status: " + status);
    }

    @Test
    void testCPUCapabilityEnum() {
        // Test all CPU capability enum values
        for (var capability : VectorAPISupport.VectorCapability.values()) {
            assertNotNull(capability.getDescription(), 
                         "Capability " + capability + " should have description");
            assertTrue(capability.getLanes() > 0, 
                      "Capability " + capability + " should have positive lanes");
            assertNotNull(capability.toString(), 
                         "Capability " + capability + " should have toString()");
        }
    }

    @Test
    void testArchitectureDetection() {
        // This test documents what architecture we're running on
        String arch = System.getProperty("os.arch", "unknown");
        String os = System.getProperty("os.name", "unknown");
        String vmName = System.getProperty("java.vm.name", "unknown");
        
        System.out.println("=== Platform Information ===");
        System.out.println("OS: " + os);
        System.out.println("Architecture: " + arch);
        System.out.println("JVM: " + vmName);
        System.out.println("Java Version: " + System.getProperty("java.version"));
        System.out.println("CPU Capability: " + VectorAPISupport.getCPUCapability());
        System.out.println("Vector API Present: " + VectorAPISupport.isVectorAPIPresent());
        
        // Verify architecture-specific expectations
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            assertEquals(VectorAPISupport.VectorCapability.ARM_NEON, 
                        VectorAPISupport.getCPUCapability(),
                        "ARM64 should detect NEON capability");
        } else if (arch.contains("amd64") || arch.contains("x86_64")) {
            var capability = VectorAPISupport.getCPUCapability();
            assertTrue(capability == VectorAPISupport.VectorCapability.AVX2 ||
                      capability == VectorAPISupport.VectorCapability.AVX512,
                      "x86_64 should detect AVX2 or AVX512");
        }
    }
}
