package com.hellblazer.luciferase.gpu.test;

import org.lwjgl.PointerBuffer;
import org.lwjgl.opencl.CL;
import org.lwjgl.opencl.CL10;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opencl.CL10.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * GPU Device Detector using LWJGL OpenCL
 * 
 * Enumerates available OpenCL platforms and devices for GPU testing.
 * Provides information about GPU capabilities, memory, and compute units.
 */
public final class GPUDeviceDetector {
    private static final Logger log = LoggerFactory.getLogger(GPUDeviceDetector.class);
    
    /**
     * Represents a detected GPU device
     */
    public static class GPUDevice {
        private final long platformId;
        private final long deviceId;
        private final String name;
        private final String vendor;
        private final String version;
        private final long globalMemSize;
        private final long localMemSize;
        private final int maxComputeUnits;
        private final long maxWorkGroupSize;
        private final boolean available;
        
        GPUDevice(long platformId, long deviceId, String name, String vendor, String version,
                  long globalMemSize, long localMemSize, int maxComputeUnits, 
                  long maxWorkGroupSize, boolean available) {
            this.platformId = platformId;
            this.deviceId = deviceId;
            this.name = name;
            this.vendor = vendor;
            this.version = version;
            this.globalMemSize = globalMemSize;
            this.localMemSize = localMemSize;
            this.maxComputeUnits = maxComputeUnits;
            this.maxWorkGroupSize = maxWorkGroupSize;
            this.available = available;
        }
        
        public long getPlatformId() { return platformId; }
        public long getDeviceId() { return deviceId; }
        public String getName() { return name; }
        public String getVendor() { return vendor; }
        public String getVersion() { return version; }
        public long getGlobalMemSize() { return globalMemSize; }
        public long getLocalMemSize() { return localMemSize; }
        public int getMaxComputeUnits() { return maxComputeUnits; }
        public long getMaxWorkGroupSize() { return maxWorkGroupSize; }
        public boolean isAvailable() { return available; }
        
        @Override
        public String toString() {
            return String.format("%s (%s) - %d CUs, %.2f GB RAM, available=%s",
                name, vendor, maxComputeUnits, globalMemSize / (1024.0 * 1024.0 * 1024.0), available);
        }
    }
    
    /**
     * Detect all available GPU devices via OpenCL
     * 
     * @return List of detected GPU devices (may be empty if no GPUs available)
     */
    public static List<GPUDevice> detectGPUs() {
        List<GPUDevice> devices = new ArrayList<>();
        
        try {
            // Create OpenCL context
            CL.create();
            
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer numPlatforms = stack.mallocInt(1);
                
                // Get number of platforms
                int err = clGetPlatformIDs(null, numPlatforms);
                if (err != CL_SUCCESS) {
                    log.warn("Failed to get OpenCL platform count: {}", getErrorString(err));
                    return devices;
                }
                
                if (numPlatforms.get(0) == 0) {
                    log.warn("No OpenCL platforms found");
                    return devices;
                }
                
                // Get platform IDs
                PointerBuffer platforms = stack.mallocPointer(numPlatforms.get(0));
                err = clGetPlatformIDs(platforms, (IntBuffer) null);
                if (err != CL_SUCCESS) {
                    log.warn("Failed to get OpenCL platforms: {}", getErrorString(err));
                    return devices;
                }
                
                log.info("Found {} OpenCL platform(s)", platforms.remaining());
                
                // Enumerate devices on each platform
                for (int p = 0; p < platforms.remaining(); p++) {
                    long platform = platforms.get(p);
                    
                    IntBuffer numDevices = stack.mallocInt(1);
                    err = clGetDeviceIDs(platform, CL_DEVICE_TYPE_GPU, null, numDevices);
                    
                    if (err == CL_DEVICE_NOT_FOUND) {
                        log.debug("No GPU devices found on platform {}", p);
                        continue;
                    }
                    
                    if (err != CL_SUCCESS) {
                        log.warn("Failed to get device count on platform {}: {}", p, getErrorString(err));
                        continue;
                    }
                    
                    if (numDevices.get(0) == 0) {
                        continue;
                    }
                    
                    PointerBuffer deviceIds = stack.mallocPointer(numDevices.get(0));
                    err = clGetDeviceIDs(platform, CL_DEVICE_TYPE_GPU, deviceIds, (IntBuffer) null);
                    
                    if (err != CL_SUCCESS) {
                        log.warn("Failed to get device IDs on platform {}: {}", p, getErrorString(err));
                        continue;
                    }
                    
                    log.info("Platform {} has {} GPU device(s)", p, deviceIds.remaining());
                    
                    // Query each device
                    for (int d = 0; d < deviceIds.remaining(); d++) {
                        long deviceId = deviceIds.get(d);
                        
                        try {
                            String name = getDeviceInfoString(deviceId, CL_DEVICE_NAME);
                            String vendor = getDeviceInfoString(deviceId, CL_DEVICE_VENDOR);
                            String version = getDeviceInfoString(deviceId, CL_DEVICE_VERSION);
                            
                            long globalMem = getDeviceInfoLong(deviceId, CL_DEVICE_GLOBAL_MEM_SIZE);
                            long localMem = getDeviceInfoLong(deviceId, CL_DEVICE_LOCAL_MEM_SIZE);
                            int computeUnits = getDeviceInfoInt(deviceId, CL_DEVICE_MAX_COMPUTE_UNITS);
                            long maxWorkGroupSize = getDeviceInfoLong(deviceId, CL_DEVICE_MAX_WORK_GROUP_SIZE);
                            boolean available = getDeviceInfoInt(deviceId, CL_DEVICE_AVAILABLE) != 0;
                            
                            GPUDevice device = new GPUDevice(
                                platform, deviceId, name, vendor, version,
                                globalMem, localMem, computeUnits, maxWorkGroupSize, available
                            );
                            
                            devices.add(device);
                            log.info("Detected GPU: {}", device);
                            
                        } catch (Exception e) {
                            log.warn("Failed to query device {} on platform {}: {}", d, p, e.getMessage());
                        }
                    }
                }
            }
            
        } catch (Exception e) {
            log.error("Failed to detect GPUs via OpenCL", e);
        } finally {
            try {
                CL.destroy();
            } catch (Exception e) {
                log.debug("Error destroying OpenCL context", e);
            }
        }
        
        return devices;
    }
    
    /**
     * Check if at least one GPU is available
     */
    public static boolean hasGPU() {
        List<GPUDevice> devices = detectGPUs();
        return !devices.isEmpty() && devices.stream().anyMatch(GPUDevice::isAvailable);
    }
    
    /**
     * Get the first available GPU device
     * 
     * @return First available GPU or null if none found
     */
    public static GPUDevice getFirstGPU() {
        return detectGPUs().stream()
            .filter(GPUDevice::isAvailable)
            .findFirst()
            .orElse(null);
    }
    
    // === Helper Methods ===
    
    private static String getDeviceInfoString(long device, int param) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pp = stack.mallocPointer(1);
            clGetDeviceInfo(device, param, (PointerBuffer) null, pp);
            
            int bytes = (int) pp.get(0);
            var buffer = stack.malloc(bytes);
            clGetDeviceInfo(device, param, buffer, null);
            
            return memASCII(buffer, bytes - 1);
        }
    }
    
    private static long getDeviceInfoLong(long device, int param) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pp = stack.mallocPointer(1);
            clGetDeviceInfo(device, param, pp, null);
            return pp.get(0);
        }
    }
    
    private static int getDeviceInfoInt(long device, int param) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer buffer = stack.mallocInt(1);
            clGetDeviceInfo(device, param, buffer, null);
            return buffer.get(0);
        }
    }
    
    private static String getErrorString(int error) {
        switch (error) {
            case CL_SUCCESS: return "CL_SUCCESS";
            case CL_DEVICE_NOT_FOUND: return "CL_DEVICE_NOT_FOUND";
            case CL_DEVICE_NOT_AVAILABLE: return "CL_DEVICE_NOT_AVAILABLE";
            case CL_COMPILER_NOT_AVAILABLE: return "CL_COMPILER_NOT_AVAILABLE";
            case CL_OUT_OF_RESOURCES: return "CL_OUT_OF_RESOURCES";
            case CL_OUT_OF_HOST_MEMORY: return "CL_OUT_OF_HOST_MEMORY";
            case CL_INVALID_VALUE: return "CL_INVALID_VALUE";
            case CL_INVALID_PLATFORM: return "CL_INVALID_PLATFORM";
            default: return "UNKNOWN_ERROR_" + error;
        }
    }
}
