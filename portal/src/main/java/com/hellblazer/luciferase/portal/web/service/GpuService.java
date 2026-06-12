package com.hellblazer.luciferase.portal.web.service;

import com.hellblazer.luciferase.common.time.Clock;
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
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.lwjgl.opencl.CL10.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * Service for GPU OpenCL operations via REST API.
 * Provides GPU info, rendering, and benchmarking capabilities.
 *
 * <p><b>Concurrency:</b> {@link #enableGpu} is safe against concurrent duplicate enables for
 * the same session — the session slot is claimed atomically ({@code putIfAbsent}); exactly one
 * caller wins and the loser's renderer (a live native OpenCL resource) is disposed before the
 * loser is rejected with {@link IllegalStateException}. {@link #disableGpu} uses an atomic
 * remove. Other per-session operations assume the REST layer's per-session serialization.
 */
public class GpuService {

    private static final Logger log = LoggerFactory.getLogger(GpuService.class);

    // Session ID -> GPU session state
    private final Map<String, GpuSessionState> sessions = new ConcurrentHashMap<>();

    /**
     * Test seam: how a renderer is disposed when its enableGpu call loses the session-claim
     * race. Production default is {@link #safeDispose}; headless tests substitute a recorder
     * since a real ESVTOpenCLRenderer cannot be constructed without OpenCL natives (the
     * enableGpu → claimSession routing itself is exercised by the RUN_GPU_TESTS-gated
     * GpuEndpointTest integration suite, not headless tests — the availability probe exits
     * enableGpu before the claim on headless hosts). Inject before any concurrent use;
     * volatile so a setup-thread write is visible to claiming threads.
     */
    volatile Consumer<ESVTOpenCLRenderer> loserDisposer = GpuService::safeDispose;

    // Cached GPU info (device doesn't change during runtime)
    private volatile GpuInfo cachedGpuInfo;

    /**
     * Clock used for render/benchmark elapsed-time measurement.
     * Default is {@link Clock#system()} (backed by {@link System#nanoTime()}).
     * Injecting a TestClock via {@link #setClock} lets tests control elapsed values —
     * this is intentional per Luciferase-7wzml.221.
     */
    private volatile Clock clock = Clock.system();

    /**
     * Replaces the clock used for render/benchmark timing.
     * Default is {@link Clock#system()}. Override in tests for deterministic elapsed values.
     */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

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
        // Validate dimensions first — before any state checks so invalid input is always rejected.
        var width = request.getFrameWidthOrDefault();
        var height = request.getFrameHeightOrDefault();

        if (sessions.containsKey(sessionId)) {
            throw new IllegalStateException("GPU already enabled for session. Disable first.");
        }

        // OpenCL availability probe. isOpenCLAvailable() catches Exception but not Error;
        // on a headless runner with no OpenCL native binding, loading the LWJGL native can
        // throw UnsatisfiedLinkError / NoClassDefFoundError (subclasses of Error), which would
        // otherwise escape to the generic Exception handler as a 500. Treat any Throwable here
        // as "GPU unavailable" and surface it as a graceful 409 (IllegalStateException), never 500.
        boolean openCLAvailable;
        try {
            openCLAvailable = ESVTOpenCLRenderer.isOpenCLAvailable();
        } catch (Throwable t) {
            log.warn("OpenCL availability probe failed; treating GPU as unavailable: {}", t.toString());
            openCLAvailable = false;
        }
        if (!openCLAvailable) {
            throw new IllegalStateException("OpenCL is not available on this system");
        }

        var renderer = new ESVTOpenCLRenderer(width, height);
        GpuSessionState state;
        try {
            renderer.initialize();
            renderer.uploadData(esvtData);
            state = new GpuSessionState(renderer, width, height);
        } catch (Throwable t) {
            // Catch Throwable: native GPU init can fail with Error (UnsatisfiedLinkError etc.)
            // on runners where the OpenCL ICD reports available but no usable device exists.
            // Surface as a graceful 409 (GPU could not be initialized), not a 500 crash.
            safeDispose(renderer);
            throw new IllegalStateException("GPU could not be initialized on this system: " + t.getMessage());
        }

        claimSession(sessionId, state);

        log.info("Enabled GPU for session {} at {}x{}", sessionId, width, height);
        return getStats(sessionId);
    }

    /**
     * Atomically claim the session slot. The containsKey fast-fail in {@link #enableGpu} is a
     * cheap pre-init short-circuit only — a concurrent enable for the same session can race
     * past it during native init. Exactly one caller wins; the loser's renderer is disposed
     * before rejecting so a lost race cannot leak native CL objects (the map entry is the
     * winner's renderer, never the loser's). Package-private for deterministic headless
     * regression testing.
     */
    void claimSession(String sessionId, GpuSessionState state) {
        Objects.requireNonNull(loserDisposer, "loserDisposer");
        if (sessions.putIfAbsent(sessionId, state) != null) {
            log.debug("Concurrent GPU enable lost race for session {}; disposing loser's renderer", sessionId);
            loserDisposer.accept(state.renderer);
            throw new IllegalStateException("GPU already enabled for session. Disable first.");
        }
    }

    private static void safeDispose(ESVTOpenCLRenderer renderer) {
        try {
            renderer.dispose();
        } catch (Throwable t) {
            log.warn("Renderer dispose threw: {}", t.toString());
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

        // Throwable-fenced like every other dispose path: a native Error escaping here would
        // abort reaper/session-delete cleanup loops with the session already removed.
        safeDispose(state.renderer);
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

        long startTime = clock.nanoTime();
        state.renderer.renderFrame(cameraPos, lookAt, request.getFovOrDefault());
        long renderTime = clock.nanoTime() - startTime;

        // Update stats
        state.framesRendered.incrementAndGet();
        state.totalRenderTimeNs.addAndGet(renderTime);
        state.totalRays.addAndGet((long) state.width * state.height);

        // Get output image
        var imageBuffer = state.renderer.getOutputImage();
        long bufferSize = (long) state.width * state.height * 4;
        if (bufferSize > Integer.MAX_VALUE) {
            throw new IllegalStateException(
                    "Render buffer size " + bufferSize + " exceeds Integer.MAX_VALUE");
        }
        var imageBytes = new byte[(int) bufferSize];
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
            long startTime = clock.nanoTime();
            state.renderer.renderFrame(cameraPos, lookAt, fov);
            long elapsed = clock.nanoTime() - startTime;

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
     * Package-private so headless tests can exercise {@link #claimSession} directly.
     */
    static class GpuSessionState {
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
