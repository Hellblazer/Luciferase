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
package com.hellblazer.luciferase.esvo.gpu;

import org.lwjgl.PointerBuffer;
import org.lwjgl.opencl.CL;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.IntBuffer;

import static org.lwjgl.opencl.CL10.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * F3.1.4 D1: GPU Vendor Detector
 *
 * Singleton class for detecting GPU vendor and capabilities from OpenCL devices.
 * Performs detection once on first access and caches results.
 *
 * Thread-safe singleton with lazy initialization.
 *
 * @author hal.hildebrand
 */
public class GPUVendorDetector {
    private static final Logger log = LoggerFactory.getLogger(GPUVendorDetector.class);

    private static volatile GPUVendorDetector instance;

    private final GPUVendor vendor;
    private final String deviceName;
    private final GPUCapabilities capabilities;

    /**
     * Private constructor - performs GPU detection
     */
    private GPUVendorDetector() {
        GPUCapabilities detected;
        try {
            detected = detectGPU();
        } catch (Exception e) {
            log.debug("GPU detection failed: {}", e.getMessage());
            detected = GPUCapabilities.none();
        }

        this.capabilities = detected;
        this.vendor = capabilities.vendor();
        this.deviceName = capabilities.deviceName();

        if (capabilities.isValid()) {
            log.info("Detected GPU: {}", capabilities.summary());
        } else {
            log.debug("No GPU detected or OpenCL unavailable");
        }
    }

    /**
     * Get singleton instance
     */
    public static GPUVendorDetector getInstance() {
        if (instance == null) {
            synchronized (GPUVendorDetector.class) {
                if (instance == null) {
                    instance = new GPUVendorDetector();
                }
            }
        }
        return instance;
    }

    /**
     * Get detected GPU vendor
     */
    public GPUVendor getVendor() {
        return vendor;
    }

    /**
     * Get GPU device name
     */
    public String getDeviceName() {
        return deviceName;
    }

    /**
     * Get full GPU capabilities
     */
    public GPUCapabilities getCapabilities() {
        return capabilities;
    }

    /**
     * Detect GPU vendor and capabilities from OpenCL
     */
    private GPUCapabilities detectGPU() {
        try (var stack = MemoryStack.stackPush()) {
            // Initialize OpenCL if not already done
            var platforms = getPlatforms(stack);
            if (platforms == null || platforms.remaining() == 0) {
                log.debug("No OpenCL platforms found");
                return GPUCapabilities.none();
            }

            // Try each platform to find a GPU device
            while (platforms.hasRemaining()) {
                var platform = platforms.get();

                var devices = getGPUDevices(stack, platform);
                if (devices != null && devices.remaining() > 0) {
                    // Use first GPU device found
                    var device = devices.get(0);
                    return queryDeviceCapabilities(device);
                }
            }

            log.debug("No GPU devices found on any platform");
            return GPUCapabilities.none();

        } catch (Exception e) {
            log.warn("Error detecting GPU: {}", e.getMessage(), e);
            return GPUCapabilities.none();
        }
    }

    /**
     * Get available OpenCL platforms
     */
    private PointerBuffer getPlatforms(MemoryStack stack) {
        var numPlatforms = stack.mallocInt(1);

        var err = clGetPlatformIDs(null, numPlatforms);
        if (err != CL_SUCCESS || numPlatforms.get(0) == 0) {
            return null;
        }

        var platforms = stack.mallocPointer(numPlatforms.get(0));
        err = clGetPlatformIDs(platforms, (IntBuffer) null);

        return (err == CL_SUCCESS) ? platforms : null;
    }

    /**
     * Get GPU devices for a platform
     */
    private PointerBuffer getGPUDevices(MemoryStack stack, long platform) {
        var numDevices = stack.mallocInt(1);

        var err = clGetDeviceIDs(platform, CL_DEVICE_TYPE_GPU, null, numDevices);
        if (err != CL_SUCCESS || numDevices.get(0) == 0) {
            return null;
        }

        var devices = stack.mallocPointer(numDevices.get(0));
        err = clGetDeviceIDs(platform, CL_DEVICE_TYPE_GPU, devices, (IntBuffer) null);

        return (err == CL_SUCCESS) ? devices : null;
    }

    /**
     * Query device capabilities from OpenCL
     */
    private GPUCapabilities queryDeviceCapabilities(long device) {
        try {
            var deviceName = getDeviceInfoString(device, CL_DEVICE_NAME);
            var vendorString = getDeviceInfoString(device, CL_DEVICE_VENDOR);
            var openCLVersion = getDeviceInfoString(device, CL_DEVICE_VERSION);

            var computeUnits = getDeviceInfoInt(device, CL_DEVICE_MAX_COMPUTE_UNITS);
            var maxWorkGroupSize = getDeviceInfoLong(device, CL_DEVICE_MAX_WORK_GROUP_SIZE);
            var globalMemorySize = getDeviceInfoLong(device, CL_DEVICE_GLOBAL_MEM_SIZE);
            var localMemorySize = getDeviceInfoLong(device, CL_DEVICE_LOCAL_MEM_SIZE);
            var maxClockFrequency = getDeviceInfoInt(device, CL_DEVICE_MAX_CLOCK_FREQUENCY);

            // Detect vendor from device name and vendor string
            var vendor = detectVendorFromStrings(deviceName, vendorString);

            return new GPUCapabilities(
                vendor,
                deviceName,
                vendorString,
                computeUnits,
                maxWorkGroupSize,
                globalMemorySize,
                localMemorySize,
                maxClockFrequency,
                openCLVersion
            );
        } catch (Exception e) {
            log.warn("Error querying device capabilities: {}", e.getMessage());
            return GPUCapabilities.none();
        }
    }

    /**
     * Detect vendor from device name and vendor string
     */
    private GPUVendor detectVendorFromStrings(String deviceName, String vendorString) {
        // Try device name first (more specific)
        var vendorFromDevice = GPUVendor.fromString(deviceName);
        if (vendorFromDevice != GPUVendor.UNKNOWN) {
            return vendorFromDevice;
        }

        // Fallback to vendor string
        return GPUVendor.fromString(vendorString);
    }

    /**
     * Get string device info from OpenCL
     */
    private String getDeviceInfoString(long device, int paramName) {
        try (var stack = MemoryStack.stackPush()) {
            var size = stack.mallocPointer(1);
            clGetDeviceInfo(device, paramName, (long[]) null, size);

            var buffer = stack.malloc((int) size.get(0));
            clGetDeviceInfo(device, paramName, buffer, null);

            return memUTF8(buffer, buffer.remaining() - 1); // -1 to skip null terminator
        }
    }

    /**
     * Get int device info from OpenCL
     */
    private int getDeviceInfoInt(long device, int paramName) {
        try (var stack = MemoryStack.stackPush()) {
            var buffer = stack.mallocInt(1);
            clGetDeviceInfo(device, paramName, buffer, null);
            return buffer.get(0);
        }
    }

    /**
     * Get long device info from OpenCL
     */
    private long getDeviceInfoLong(long device, int paramName) {
        try (var stack = MemoryStack.stackPush()) {
            var buffer = stack.mallocLong(1);
            clGetDeviceInfo(device, paramName, buffer, null);
            return buffer.get(0);
        }
    }
}
