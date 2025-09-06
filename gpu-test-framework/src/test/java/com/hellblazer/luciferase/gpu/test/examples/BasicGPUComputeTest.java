package com.hellblazer.luciferase.gpu.test.examples;

import com.hellblazer.luciferase.gpu.test.CICompatibleGPUTest;
import com.hellblazer.luciferase.gpu.test.MockPlatform;
import com.hellblazer.luciferase.gpu.test.PlatformTestSupport;
import com.hellblazer.luciferase.gpu.test.OpenCLHeadlessTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.opencl.CL11.CL_DEVICE_TYPE_ALL;
import static org.lwjgl.opencl.CL11.CL_DEVICE_TYPE_GPU;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Example test class demonstrating the GPU testing framework.
 * Shows how to use the framework for practical GPU compute testing.
 */
class BasicGPUComputeTest extends CICompatibleGPUTest {
    
    private static final Logger log = LoggerFactory.getLogger(BasicGPUComputeTest.class);
    
    @Test
    void testPlatformDiscovery() {
        log.info("Testing OpenCL platform discovery on {}", PlatformTestSupport.getCurrentPlatformDescription());
        
        var platforms = discoverPlatforms();
        assertNotNull(platforms, "Platforms list should not be null");
        
        if (platforms.isEmpty()) {
            log.warn("No OpenCL platforms found - this is expected on some CI systems");
            assumeTrue(false, "No OpenCL platforms available for testing");
            return;
        }
        
        log.info("Found {} OpenCL platform(s):", platforms.size());
        for (var platform : platforms) {
            log.info("  {} - {} ({})", platform.name(), platform.vendor(), platform.version());
            assertNotNull(platform.name(), "Platform name should not be null");
            assertNotNull(platform.vendor(), "Platform vendor should not be null");
            
            // Mock platform has platformId = 0, which is valid for testing
            if (MockPlatform.isMockPlatform(platform)) {
                log.info("******    Using mock platform for CI compatibility");
            } else {
                assertTrue(platform.platformId() != 0, "Real platform ID should be valid");
            }
        }
        
        assertTrue(platforms.size() > 0, "Should find at least one OpenCL platform");
    }
    
    @Test
    void testDeviceDiscovery() {
        var platforms = discoverPlatforms();
        assumeTrue(!platforms.isEmpty(), "No OpenCL platforms available");
        
        var firstPlatform = platforms.get(0);
        log.info("Testing device discovery on platform: {}", firstPlatform.name());
        
        var allDevices = discoverDevices(firstPlatform.platformId(), CL_DEVICE_TYPE_ALL);
        assertNotNull(allDevices, "Devices list should not be null");
        
        if (allDevices.isEmpty()) {
            log.warn("No devices found on platform {}", firstPlatform.name());
            assumeTrue(false, "No devices available for testing");
            return;
        }
        
        log.info("Found {} device(s) on platform {}:", allDevices.size(), firstPlatform.name());
        for (var device : allDevices) {
            log.info("  {}", device);
            assertNotNull(device.name(), "Device name should not be null");
            
            // Mock device has deviceId = 0, which is valid for testing
            if (MockPlatform.isMockDevice(device)) {
                log.info("    Using mock device for CI compatibility");
            } else {
                assertTrue(device.deviceId() != 0, "Real device ID should be valid");
            }
            
            assertTrue(device.computeUnits() > 0, "Should have compute units");
            assertTrue(device.globalMemSize() > 0, "Should have global memory");
        }
        
        assertTrue(allDevices.size() > 0, "Should find at least one device");
    }
    
    @Test
    @EnabledIf("hasGPUDevice")
    void testGPUVectorAddition() {
        var platforms = discoverPlatforms();
        assumeTrue(!platforms.isEmpty(), "No OpenCL platforms available");
        
        PlatformInfo targetPlatform = null;
        DeviceInfo targetDevice = null;
        
        // Find first GPU device
        for (var platform : platforms) {
            var gpuDevices = discoverDevices(platform.platformId(), CL_DEVICE_TYPE_GPU);
            if (!gpuDevices.isEmpty()) {
                targetPlatform = platform;
                targetDevice = gpuDevices.get(0);
                break;
            }
        }
        
        assumeTrue(targetPlatform != null && targetDevice != null, "No GPU device found for testing");
        
        log.info("Testing GPU vector addition on: {}", targetDevice);
        
        // This will throw an exception if the test fails
        testGPUVectorAddition(targetPlatform.platformId(), targetDevice.deviceId());
        
        log.info("✅ GPU vector addition test completed successfully");
    }
    
    @Test
    void testFrameworkConfigurationLogging() {
        var platformInfo = getPlatformInfo();
        assertNotNull(platformInfo, "Platform info should be available");
        
        log.info("Framework configuration:");
        log.info("  Platform: {}", platformInfo);
        log.info("  64-bit: {}", platformInfo.is64Bit());
        log.info("  ARM architecture: {}", platformInfo.isARM());
        log.info("  Headless AWT: {}", System.getProperty("java.awt.headless", "false"));
        
        if (platformInfo.isMacOS()) {
            log.info("  macOS StartOnFirstThread support: {}", platformInfo.hasStartOnFirstThreadSupport());
        }
        
        assertTrue(platformInfo.is64Bit(), "Framework requires 64-bit architecture");
        assertEquals("true", System.getProperty("java.awt.headless"), "Should be running in headless mode");
    }
    
    /**
     * Condition method for @EnabledIf - checks if GPU device is available.
     */
    static boolean hasGPUDevice() {
        try {
            var testInstance = new BasicGPUComputeTest();
            testInstance.configureTestEnvironment();
            
            try {
                testInstance.loadRequiredNativeLibraries();
            } catch (OpenCLHeadlessTest.OpenCLUnavailableException e) {
                // OpenCL not available - this is normal in CI environments
                return false;
            }
            
            try {
                var platforms = testInstance.discoverPlatforms();
                for (var platform : platforms) {
                    // Skip mock platforms for GPU tests - they don't have real GPUs
                    if (MockPlatform.isMockPlatform(platform)) {
                        continue;
                    }
                    
                    var gpuDevices = testInstance.discoverDevices(platform.platformId(), CL_DEVICE_TYPE_GPU);
                    if (!gpuDevices.isEmpty()) {
                        return true;
                    }
                }
                return false;
            } finally {
                testInstance.cleanupTestEnvironment();
            }
        } catch (Exception e) {
            // Any other exception means no GPU available
            return false;
        }
    }
}
