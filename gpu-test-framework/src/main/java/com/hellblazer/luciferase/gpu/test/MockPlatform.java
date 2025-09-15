package com.hellblazer.luciferase.gpu.test;

import java.util.List;

/**
 * Mock platform implementation for CI environments where OpenCL is not available.
 * This allows tests to run gracefully without OpenCL drivers while maintaining
 * the same API surface as real platforms.
 */
public class MockPlatform {
    
    /**
     * Creates a mock platform info that represents "no OpenCL available".
     * This platform will have no devices and will be clearly identified as a mock.
     */
    public static GPUComputeHeadlessTest.PlatformInfo createMockPlatform() {
        return new GPUComputeHeadlessTest.PlatformInfo(
            0L,                                    // platformId = 0 (invalid)
            "Mock Platform (OpenCL Unavailable)", // name
            "CI Mock Vendor",                     // vendor  
            "1.0"                                 // version
        );
    }
    
    /**
     * Creates a mock device info that represents "no devices available".
     * This device will have minimal specs and will be clearly identified as a mock.
     */
    public static GPUComputeHeadlessTest.DeviceInfo createMockDevice() {
        return new GPUComputeHeadlessTest.DeviceInfo(
            0L,                                          // deviceId = 0 (invalid)
            "Mock Device (No GPU Available)",           // name
            2L,                                         // deviceType (CL_DEVICE_TYPE_CPU = 2)
            1,                                          // computeUnits (minimal)
            1024L * 1024L,                             // maxMemAllocSize (1MB)  
            1024L * 1024L                              // globalMemSize (1MB)
        );
    }
    
    /**
     * Returns a singleton list containing only the mock platform.
     * This is returned when OpenCL platform discovery fails.
     */
    public static List<GPUComputeHeadlessTest.PlatformInfo> getMockPlatforms() {
        return List.of(createMockPlatform());
    }
    
    /**
     * Returns a singleton list containing only the mock device.
     * This is returned when OpenCL device discovery fails.
     */
    public static List<GPUComputeHeadlessTest.DeviceInfo> getMockDevices() {
        return List.of(createMockDevice());
    }
    
    /**
     * Returns mock devices filtered by device type.
     * For mock platforms, we always return one mock device regardless of type.
     */
    public static List<GPUComputeHeadlessTest.DeviceInfo> getMockDevices(long deviceType) {
        return getMockDevices(); // Mock platform always returns same mock device
    }
    
    /**
     * Checks if a platform is the mock platform.
     */
    public static boolean isMockPlatform(GPUComputeHeadlessTest.PlatformInfo platform) {
        return platform != null && platform.platformId() == 0L && 
               platform.name().contains("Mock Platform");
    }
    
    /**
     * Checks if a device is the mock device.
     */
    public static boolean isMockDevice(GPUComputeHeadlessTest.DeviceInfo device) {
        return device != null && device.deviceId() == 0L && 
               device.name().contains("Mock Device");
    }
    
    /**
     * Determines if we should use mock platform based on environment.
     * This checks for CI environments or explicit mock requests.
     */
    public static boolean shouldUseMockPlatform() {
        // Check for CI environment variables
        return System.getenv("CI") != null || 
               System.getenv("GITHUB_ACTIONS") != null ||
               System.getenv("JENKINS_URL") != null ||
               System.getenv("GITLAB_CI") != null ||
               System.getenv("TRAVIS") != null ||
               // Allow explicit mock mode
               "true".equals(System.getProperty("gpu.test.mock", "false"));
    }
}
