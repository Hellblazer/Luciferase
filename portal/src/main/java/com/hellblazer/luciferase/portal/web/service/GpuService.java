package com.hellblazer.luciferase.portal.web.service;

import com.hellblazer.luciferase.esvt.core.ESVTData;
import com.hellblazer.luciferase.esvt.gpu.ESVTOpenCLRenderer;
import com.hellblazer.luciferase.portal.web.dto.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Vector3f;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Base64;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.lwjgl.opencl.CL10.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * Service for GPU OpenCL operations via REST API.
 * Provides GPU info, rendering, and benchmarking capabilities.
 */
public class GpuService {

    private static final Logger log = LoggerFactory.getLogger(GpuService.class);

    // Session ID -> GPU session state
    private final Map<String, GpuSessionState> sessions = new ConcurrentHashMap<>();

    // Cached GPU info (device doesn't change during runtime)
    private volatile GpuInfo cachedGpuInfo;

    /**
     * Get GPU device information.
     */
    public GpuInfo getGpuInfo() {
        if (cachedGpuInfo != null) {
            return cachedGpuInfo;
        }

        // Check basic OpenCL availability first
        if (!ESVTOpenCLRenderer.isOpenCLAvailable()) {
            cachedGpuInfo = GpuInfo.unavailable();
            return cachedGpuInfo;
        }

        // Get detailed device info
        try {
            cachedGpuInfo = queryDeviceInfo();
            return cachedGpuInfo;
        } catch (Exception e) {
            log.warn("Failed to query GPU device info: {}", e.getMessage());
            cachedGpuInfo = GpuInfo.unavailable();
            return cachedGpuInfo;
        }
    }

    /**
     * Enable GPU mode for a session.
     */
    public GpuStats enableGpu(String sessionId, ESVTData esvtData, GpuEnableRequest request) {
        if (sessions.containsKey(sessionId)) {
            throw new IllegalStateException("GPU already enabled for session. Disable first.");
        }

        if (!ESVTOpenCLRenderer.isOpenCLAvailable()) {
            throw new IllegalStateException("OpenCL is not available on this system");
        }

        var width = request.getFrameWidthOrDefault();
        var height = request.getFrameHeightOrDefault();

        var renderer = new ESVTOpenCLRenderer(width, height);
        try {
            renderer.initialize();
            renderer.uploadData(esvtData);

            var state = new GpuSessionState(renderer, width, height);
            sessions.put(sessionId, state);

            log.info("Enabled GPU for session {} at {}x{}", sessionId, width, height);
            return getStats(sessionId);

        } catch (Exception e) {
            renderer.dispose();
            throw new RuntimeException("Failed to enable GPU: " + e.getMessage(), e);
        }
    }

    /**
     * Disable GPU mode for a session.
     */
    public void disableGpu(String sessionId) {
        var state = sessions.remove(sessionId);
        if (state == null) {
            throw new NoSuchElementException("GPU not enabled for session: " + sessionId);
        }

        state.renderer.dispose();
        log.info("Disabled GPU for session {}", sessionId);
    }

    /**
     * Check if GPU is enabled for a session.
     */
    public boolean isGpuEnabled(String sessionId) {
        return sessions.containsKey(sessionId);
    }

    /**
     * Perform GPU-accelerated render.
     */
    public GpuRenderResult render(String sessionId, GpuRenderRequest request) {
        var state = getState(sessionId);

        var cameraPos = new Vector3f(request.cameraPosX(), request.cameraPosY(), request.cameraPosZ());
        var lookAt = new Vector3f(request.lookAtX(), request.lookAtY(), request.lookAtZ());

        long startTime = System.nanoTime();
        state.renderer.renderFrame(cameraPos, lookAt, request.getFovOrDefault());
        long renderTime = System.nanoTime() - startTime;

        // Update stats
        state.framesRendered.incrementAndGet();
        state.totalRenderTimeNs.addAndGet(renderTime);
        state.totalRays.addAndGet((long) state.width * state.height);

        // Get output image
        var imageBuffer = state.renderer.getOutputImage();
        var imageBytes = new byte[state.width * state.height * 4];
        imageBuffer.get(imageBytes);
        imageBuffer.rewind();

        String imageData;
        String encoding;
        if (request.isBase64()) {
            imageData = Base64.getEncoder().encodeToString(imageBytes);
            encoding = "base64";
        } else {
            imageData = Base64.getEncoder().encodeToString(imageBytes); // Always base64 for JSON
            encoding = "base64";
        }

        return new GpuRenderResult(
                state.width,
                state.height,
                "RGBA",
                encoding,
                imageData,
                renderTime,
                state.width * state.height
        );
    }

    /**
     * Run GPU benchmark.
     */
    public GpuBenchmarkResult benchmark(String sessionId, int iterations) {
        var state = getState(sessionId);

        if (iterations < 1) iterations = 10;
        if (iterations > 100) iterations = 100;

        var cameraPos = new Vector3f(1.5f, 1.5f, 1.5f);
        var lookAt = new Vector3f(0.5f, 0.5f, 0.5f);
        float fov = 60.0f;

        // Warmup
        for (int i = 0; i < 3; i++) {
            state.renderer.renderFrame(cameraPos, lookAt, fov);
        }

        // Benchmark
        var times = new double[iterations];
        double minTime = Double.MAX_VALUE;
        double maxTime = 0;
        double totalTime = 0;

        for (int i = 0; i < iterations; i++) {
            long startTime = System.nanoTime();
            state.renderer.renderFrame(cameraPos, lookAt, fov);
            long elapsed = System.nanoTime() - startTime;

            double timeMs = elapsed / 1_000_000.0;
            times[i] = timeMs;
            totalTime += timeMs;
            minTime = Math.min(minTime, timeMs);
            maxTime = Math.max(maxTime, timeMs);
        }

        double avgTime = totalTime / iterations;
        int rayCount = state.width * state.height;
        long totalRays = (long) rayCount * iterations;
        double raysPerSecond = (totalRays / totalTime) * 1000.0;

        var gpuInfo = getGpuInfo();

        return new GpuBenchmarkResult(
                state.width,
                state.height,
                iterations,
                avgTime,
                minTime,
                maxTime,
                raysPerSecond,
                totalRays,
                gpuInfo.deviceName()
        );
    }

    /**
     * Get GPU statistics for a session.
     */
    public GpuStats getStats(String sessionId) {
        var state = sessions.get(sessionId);
        if (state == null) {
            return GpuStats.disabled();
        }

        long frames = state.framesRendered.get();
        long totalTime = state.totalRenderTimeNs.get();
        long totalRays = state.totalRays.get();

        double avgTimeMs = frames > 0 ? (totalTime / 1_000_000.0) / frames : 0;
        double raysPerSec = totalTime > 0 ? (totalRays / (totalTime / 1_000_000_000.0)) : 0;

        return new GpuStats(
                true,
                state.width,
                state.height,
                frames,
                totalTime,
                avgTimeMs,
                totalRays,
                raysPerSec
        );
    }

    /**
     * Re-upload ESVT data to GPU (call after regenerating ESVT).
     */
    public void reuploadData(String sessionId, ESVTData esvtData) {
        var state = getState(sessionId);
        state.renderer.uploadData(esvtData);
        log.debug("Re-uploaded ESVT data for session {}", sessionId);
    }

    // ===== Private Helpers =====

    private GpuSessionState getState(String sessionId) {
        var state = sessions.get(sessionId);
        if (state == null) {
            throw new NoSuchElementException("GPU not enabled for session: " + sessionId);
        }
        return state;
    }

    private GpuInfo queryDeviceInfo() {
        try (var stack = MemoryStack.stackPush()) {
            // Get platforms
            var numPlatforms = stack.mallocInt(1);
            int err = clGetPlatformIDs(null, numPlatforms);
            if (err != CL_SUCCESS || numPlatforms.get(0) == 0) {
                return GpuInfo.unavailable();
            }

            var platforms = stack.mallocPointer(numPlatforms.get(0));
            clGetPlatformIDs(platforms, (IntBuffer) null);

            // Find first GPU device
            for (int p = 0; p < numPlatforms.get(0); p++) {
                long platform = platforms.get(p);

                var numDevices = stack.mallocInt(1);
                err = clGetDeviceIDs(platform, CL_DEVICE_TYPE_GPU, null, numDevices);
                if (err != CL_SUCCESS || numDevices.get(0) == 0) {
                    continue;
                }

                var devices = stack.mallocPointer(numDevices.get(0));
                clGetDeviceIDs(platform, CL_DEVICE_TYPE_GPU, devices, (IntBuffer) null);

                long device = devices.get(0);
                return queryDevice(device);
            }

            return GpuInfo.unavailable();
        }
    }

    private GpuInfo queryDevice(long device) {
        String deviceName = getDeviceString(device, CL_DEVICE_NAME);
        String vendor = getDeviceString(device, CL_DEVICE_VENDOR);
        String version = getDeviceString(device, CL_DEVICE_VERSION);

        int computeUnits = getDeviceInt(device, CL_DEVICE_MAX_COMPUTE_UNITS);
        long globalMemory = getDeviceLong(device, CL_DEVICE_GLOBAL_MEM_SIZE);
        long maxWorkGroupSize = getDeviceSizeT(device, CL_DEVICE_MAX_WORK_GROUP_SIZE);

        long deviceType = getDeviceLong(device, CL_DEVICE_TYPE);
        String typeStr = (deviceType & CL_DEVICE_TYPE_GPU) != 0 ? "GPU" :
                         (deviceType & CL_DEVICE_TYPE_CPU) != 0 ? "CPU" : "Unknown";

        return new GpuInfo(
                true,
                deviceName,
                vendor,
                version,
                globalMemory,
                computeUnits,
                maxWorkGroupSize,
                typeStr
        );
    }

    private String getDeviceString(long device, int param) {
        try (var stack = MemoryStack.stackPush()) {
            var size = stack.mallocPointer(1);
            clGetDeviceInfo(device, param, (ByteBuffer) null, size);

            var buffer = stack.malloc((int) size.get(0));
            clGetDeviceInfo(device, param, buffer, null);

            return memASCII(buffer, (int) size.get(0) - 1);
        }
    }

    private int getDeviceInt(long device, int param) {
        try (var stack = MemoryStack.stackPush()) {
            var buffer = stack.mallocInt(1);
            clGetDeviceInfo(device, param, buffer, null);
            return buffer.get(0);
        }
    }

    private long getDeviceLong(long device, int param) {
        try (var stack = MemoryStack.stackPush()) {
            var buffer = stack.mallocLong(1);
            clGetDeviceInfo(device, param, buffer, null);
            return buffer.get(0);
        }
    }

    private long getDeviceSizeT(long device, int param) {
        try (var stack = MemoryStack.stackPush()) {
            var size = stack.mallocPointer(1);
            clGetDeviceInfo(device, param, size, null);
            return size.get(0);
        }
    }

    /**
     * Internal state for a GPU-enabled session.
     */
    private static class GpuSessionState {
        final ESVTOpenCLRenderer renderer;
        final int width;
        final int height;
        final AtomicLong framesRendered = new AtomicLong();
        final AtomicLong totalRenderTimeNs = new AtomicLong();
        final AtomicLong totalRays = new AtomicLong();

        GpuSessionState(ESVTOpenCLRenderer renderer, int width, int height) {
            this.renderer = renderer;
            this.width = width;
            this.height = height;
        }
    }
}
