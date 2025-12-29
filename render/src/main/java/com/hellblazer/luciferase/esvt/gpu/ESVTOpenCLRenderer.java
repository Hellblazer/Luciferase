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

import com.hellblazer.luciferase.esvt.core.ESVTData;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opencl.CL;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Matrix4f;
import javax.vecmath.Vector3f;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opencl.CL10.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * OpenCL-based GPU Renderer for ESVT (Efficient Sparse Voxel Tetrahedra).
 *
 * <p>This renderer uses OpenCL 1.2 for GPU compute. Unlike OpenGL 4.3+ compute
 * shaders, OpenCL is supported on a wider range of platforms including older macOS
 * systems (Intel Macs), Linux with GPU drivers, and Windows.
 *
 * <p><b>Platform Compatibility:</b>
 * <ul>
 *   <li><b>Intel Macs (macOS 10.13-10.15)</b>: OpenCL available but deprecated</li>
 *   <li><b>Apple Silicon Macs</b>: OpenCL NOT available (Apple removed support, use Metal)</li>
 *   <li><b>Linux</b>: OpenCL available with appropriate GPU drivers (Mesa, NVIDIA, AMD)</li>
 *   <li><b>Windows</b>: OpenCL available with GPU vendor drivers</li>
 * </ul>
 *
 * <p><b>Key features:</b>
 * <ul>
 *   <li>Stack-based tetrahedral ray traversal</li>
 *   <li>Moller-Trumbore intersection</li>
 *   <li>Contour refinement</li>
 * </ul>
 *
 * <p>For Apple Silicon Macs, use CPU-based rendering via ESVTTraversal or consider
 * a future Metal implementation.
 *
 * @author hal.hildebrand
 * @see ESVTTraversal CPU-based ray traversal
 */
public final class ESVTOpenCLRenderer implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ESVTOpenCLRenderer.class);

    // Workgroup size (must match kernel)
    private static final int LOCAL_WORK_SIZE = 64;

    // Ray structure: origin(3) + direction(3) + tmin + tmax = 8 floats = 32 bytes
    private static final int RAY_SIZE_FLOATS = 8;
    private static final int RAY_SIZE_BYTES = RAY_SIZE_FLOATS * Float.BYTES;

    // Result: position(3) + distance = 4 floats = 16 bytes
    private static final int RESULT_SIZE_FLOATS = 4;
    private static final int RESULT_SIZE_BYTES = RESULT_SIZE_FLOATS * Float.BYTES;

    // Normal: xyz + hit_flag = 4 floats = 16 bytes
    private static final int NORMAL_SIZE_FLOATS = 4;
    private static final int NORMAL_SIZE_BYTES = NORMAL_SIZE_FLOATS * Float.BYTES;

    private final int frameWidth;
    private final int frameHeight;
    private final int rayCount;

    // OpenCL handles
    private long clContext;
    private long clQueue;
    private long clDevice;
    private long clProgram;
    private long clKernel;

    // GPU buffers
    private long clRayBuffer;
    private long clNodeBuffer;
    private long clContourBuffer;
    private long clResultBuffer;
    private long clNormalBuffer;

    // CPU buffers for data transfer
    private FloatBuffer rayBuffer;
    private FloatBuffer resultBuffer;
    private FloatBuffer normalBuffer;

    // Output image (RGBA bytes)
    private ByteBuffer outputImage;

    private boolean initialized = false;
    private boolean disposed = false;

    // Current ESVT data info
    private int nodeCount = 0;
    private int contourCount = 0;
    private int maxDepth = 21;

    /**
     * Create OpenCL renderer with specified output resolution.
     *
     * @param frameWidth  Output width in pixels
     * @param frameHeight Output height in pixels
     */
    public ESVTOpenCLRenderer(int frameWidth, int frameHeight) {
        this.frameWidth = frameWidth;
        this.frameHeight = frameHeight;
        this.rayCount = frameWidth * frameHeight;
    }

    /**
     * Check if OpenCL is available on this system.
     *
     * @return true if OpenCL GPU is available
     */
    public static boolean isOpenCLAvailable() {
        try {
            CL.create();
            try (var stack = MemoryStack.stackPush()) {
                IntBuffer numPlatforms = stack.mallocInt(1);
                int err = clGetPlatformIDs(null, numPlatforms);
                if (err != CL_SUCCESS || numPlatforms.get(0) == 0) {
                    return false;
                }

                var platforms = stack.mallocPointer(numPlatforms.get(0));
                clGetPlatformIDs(platforms, (IntBuffer) null);

                IntBuffer numDevices = stack.mallocInt(1);
                err = clGetDeviceIDs(platforms.get(0), CL_DEVICE_TYPE_GPU, null, numDevices);
                return err == CL_SUCCESS && numDevices.get(0) > 0;
            } finally {
                CL.destroy();
            }
        } catch (Exception e) {
            log.debug("OpenCL not available: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Initialize the OpenCL context and compile the kernel.
     *
     * @throws RuntimeException if initialization fails
     */
    public void initialize() {
        if (initialized) {
            return;
        }

        try {
            CL.create();
            initializeContext();
            compileKernel();
            allocateBuffers();

            initialized = true;
            log.info("Initialized ESVTOpenCLRenderer: {}x{} ({} rays)", frameWidth, frameHeight, rayCount);

        } catch (Exception e) {
            dispose();
            throw new RuntimeException("Failed to initialize OpenCL renderer", e);
        }
    }

    private void initializeContext() {
        try (var stack = MemoryStack.stackPush()) {
            IntBuffer errcode = stack.mallocInt(1);

            // Get platform
            IntBuffer numPlatforms = stack.mallocInt(1);
            checkError(clGetPlatformIDs(null, numPlatforms), "get platform count");

            if (numPlatforms.get(0) == 0) {
                throw new RuntimeException("No OpenCL platforms found");
            }

            var platforms = stack.mallocPointer(numPlatforms.get(0));
            checkError(clGetPlatformIDs(platforms, (IntBuffer) null), "get platforms");
            long platform = platforms.get(0);

            // Log platform info
            logPlatformInfo(platform);

            // Get GPU device
            IntBuffer numDevices = stack.mallocInt(1);
            int err = clGetDeviceIDs(platform, CL_DEVICE_TYPE_GPU, null, numDevices);
            if (err != CL_SUCCESS || numDevices.get(0) == 0) {
                throw new RuntimeException("No GPU devices found");
            }

            var devices = stack.mallocPointer(1);
            checkError(clGetDeviceIDs(platform, CL_DEVICE_TYPE_GPU, devices, (IntBuffer) null), "get devices");
            clDevice = devices.get(0);

            // Log device info
            logDeviceInfo(clDevice);

            // Create context
            var contextProps = stack.mallocPointer(3);
            contextProps.put(CL_CONTEXT_PLATFORM).put(platform).put(0);
            contextProps.flip();

            clContext = clCreateContext(contextProps, clDevice, null, 0, errcode);
            checkError(errcode.get(0), "create context");

            // Create command queue
            clQueue = clCreateCommandQueue(clContext, clDevice, 0, errcode);
            checkError(errcode.get(0), "create command queue");
        }
    }

    private void compileKernel() {
        try (var stack = MemoryStack.stackPush()) {
            IntBuffer errcode = stack.mallocInt(1);

            // Load kernel source
            String kernelSource = ESVTKernels.getOpenCLKernel();
            if (kernelSource == null || kernelSource.isEmpty()) {
                throw new RuntimeException("Failed to load ESVT OpenCL kernel");
            }

            // Create program
            clProgram = clCreateProgramWithSource(clContext, kernelSource, errcode);
            checkError(errcode.get(0), "create program");

            // Build program
            int buildErr = clBuildProgram(clProgram, clDevice, "", null, 0);
            if (buildErr != CL_SUCCESS) {
                // Get build log
                var logSize = stack.mallocPointer(1);
                clGetProgramBuildInfo(clProgram, clDevice, CL_PROGRAM_BUILD_LOG, (ByteBuffer) null, logSize);

                ByteBuffer logBuffer = stack.malloc((int) logSize.get(0));
                clGetProgramBuildInfo(clProgram, clDevice, CL_PROGRAM_BUILD_LOG, logBuffer, null);

                String buildLog = memUTF8(logBuffer);
                throw new RuntimeException("Kernel build failed:\n" + buildLog);
            }

            // Create kernel
            clKernel = clCreateKernel(clProgram, "traverseESVT", errcode);
            checkError(errcode.get(0), "create kernel");

            log.info("ESVT OpenCL kernel compiled successfully");
        }
    }

    private void allocateBuffers() {
        try (var stack = MemoryStack.stackPush()) {
            IntBuffer errcode = stack.mallocInt(1);

            // Allocate CPU buffers
            rayBuffer = memAllocFloat(rayCount * RAY_SIZE_FLOATS);
            resultBuffer = memAllocFloat(rayCount * RESULT_SIZE_FLOATS);
            normalBuffer = memAllocFloat(rayCount * NORMAL_SIZE_FLOATS);
            outputImage = memAlloc(frameWidth * frameHeight * 4); // RGBA

            // Allocate GPU ray buffer
            clRayBuffer = clCreateBuffer(clContext, CL_MEM_READ_ONLY,
                    (long) rayCount * RAY_SIZE_BYTES, errcode);
            checkError(errcode.get(0), "create ray buffer");

            // Allocate GPU result buffers
            clResultBuffer = clCreateBuffer(clContext, CL_MEM_WRITE_ONLY,
                    (long) rayCount * RESULT_SIZE_BYTES, errcode);
            checkError(errcode.get(0), "create result buffer");

            clNormalBuffer = clCreateBuffer(clContext, CL_MEM_WRITE_ONLY,
                    (long) rayCount * NORMAL_SIZE_BYTES, errcode);
            checkError(errcode.get(0), "create normal buffer");

            // Contour buffer (empty initially)
            clContourBuffer = clCreateBuffer(clContext, CL_MEM_READ_ONLY, 4, errcode);
            checkError(errcode.get(0), "create contour buffer");

            log.debug("Allocated OpenCL buffers for {} rays", rayCount);
        }
    }

    /**
     * Upload ESVT data to GPU.
     *
     * @param data The ESVT data to upload
     */
    public void uploadData(ESVTData data) {
        if (!initialized) {
            throw new IllegalStateException("Renderer not initialized");
        }

        try (var stack = MemoryStack.stackPush()) {
            IntBuffer errcode = stack.mallocInt(1);

            // Release old node buffer if exists
            if (clNodeBuffer != 0) {
                clReleaseMemObject(clNodeBuffer);
            }

            // Upload node data
            var nodeData = data.nodesToByteBuffer();
            this.nodeCount = data.nodeCount();
            this.maxDepth = data.maxDepth();

            clNodeBuffer = clCreateBuffer(clContext, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
                    nodeData, errcode);
            checkError(errcode.get(0), "create node buffer");

            // Upload contour data if present
            if (clContourBuffer != 0) {
                clReleaseMemObject(clContourBuffer);
            }

            if (data.hasContours()) {
                var contourData = data.contoursToByteBuffer();
                this.contourCount = data.contourCount();

                clContourBuffer = clCreateBuffer(clContext, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
                        contourData, errcode);
                checkError(errcode.get(0), "create contour buffer");
            } else {
                this.contourCount = 0;
                clContourBuffer = clCreateBuffer(clContext, CL_MEM_READ_ONLY, 4, errcode);
                checkError(errcode.get(0), "create empty contour buffer");
            }

            log.debug("Uploaded ESVT data: {} nodes, {} contours", nodeCount, contourCount);
        }
    }

    /**
     * Render a frame.
     *
     * @param viewMatrix     Camera view matrix
     * @param projMatrix     Camera projection matrix
     * @param objectToWorld  Transform from object to world space
     * @param tetreeToObject Transform from tetree [0,1] to object space
     */
    public void renderFrame(Matrix4f viewMatrix, Matrix4f projMatrix,
                            Matrix4f objectToWorld, Matrix4f tetreeToObject) {
        if (!initialized) {
            throw new IllegalStateException("Renderer not initialized");
        }
        if (disposed) {
            throw new IllegalStateException("Renderer has been disposed");
        }
        if (clNodeBuffer == 0) {
            throw new IllegalStateException("No ESVT data uploaded");
        }

        // Generate primary rays
        generateRays(viewMatrix, projMatrix, objectToWorld, tetreeToObject);

        // Upload rays to GPU
        uploadRays();

        // Execute kernel
        executeKernel();

        // Read results
        readResults();

        // Convert to RGBA image
        convertToImage();
    }

    /**
     * Render a frame with simplified camera parameters.
     *
     * @param cameraPosition Camera position in world space
     * @param lookAt         Point the camera is looking at
     * @param fovDegrees     Field of view in degrees
     */
    public void renderFrame(Vector3f cameraPosition, Vector3f lookAt, float fovDegrees) {
        var viewMatrix = createLookAtMatrix(cameraPosition, lookAt, new Vector3f(0, 1, 0));
        float aspect = (float) frameWidth / frameHeight;
        var projMatrix = createPerspectiveMatrix(fovDegrees, aspect, 0.1f, 100.0f);

        var identity = new Matrix4f();
        identity.setIdentity();

        renderFrame(viewMatrix, projMatrix, identity, identity);
    }

    private void generateRays(Matrix4f viewMatrix, Matrix4f projMatrix,
                              Matrix4f objectToWorld, Matrix4f tetreeToObject) {
        // Compute inverse view-projection matrix
        var invView = new Matrix4f();
        invView.invert(viewMatrix);

        var invProj = new Matrix4f();
        invProj.invert(projMatrix);

        // Camera position
        var cameraPos = new Vector3f(invView.m03, invView.m13, invView.m23);

        rayBuffer.clear();

        for (int y = 0; y < frameHeight; y++) {
            for (int x = 0; x < frameWidth; x++) {
                // Normalized device coordinates [-1, 1]
                float ndcX = (2.0f * x / frameWidth) - 1.0f;
                float ndcY = 1.0f - (2.0f * y / frameHeight);

                // Unproject to view space
                var clipPos = new javax.vecmath.Vector4f(ndcX, ndcY, -1.0f, 1.0f);

                // Apply inverse projection
                var viewPos = new javax.vecmath.Vector4f();
                invProj.transform(clipPos, viewPos);
                viewPos.scale(1.0f / viewPos.w);

                // Apply inverse view
                var worldPos = new javax.vecmath.Vector4f();
                invView.transform(viewPos, worldPos);

                // Ray direction
                var rayDir = new Vector3f(
                        worldPos.x - cameraPos.x,
                        worldPos.y - cameraPos.y,
                        worldPos.z - cameraPos.z
                );
                rayDir.normalize();

                // Write ray to buffer
                rayBuffer.put(cameraPos.x);
                rayBuffer.put(cameraPos.y);
                rayBuffer.put(cameraPos.z);
                rayBuffer.put(rayDir.x);
                rayBuffer.put(rayDir.y);
                rayBuffer.put(rayDir.z);
                rayBuffer.put(0.001f);  // tmin
                rayBuffer.put(1000.0f); // tmax
            }
        }

        rayBuffer.flip();
    }

    private void uploadRays() {
        checkError(clEnqueueWriteBuffer(clQueue, clRayBuffer, true, 0, rayBuffer, null, null),
                "upload rays");
    }

    private void executeKernel() {
        try (var stack = MemoryStack.stackPush()) {
            // Set kernel arguments
            clSetKernelArg1p(clKernel, 0, clRayBuffer);
            clSetKernelArg1p(clKernel, 1, clNodeBuffer);
            clSetKernelArg1p(clKernel, 2, clContourBuffer);
            clSetKernelArg1p(clKernel, 3, clResultBuffer);
            clSetKernelArg1p(clKernel, 4, clNormalBuffer);
            clSetKernelArg1i(clKernel, 5, maxDepth);

            // Scene bounds (0,0,0) to (1,1,1) for normalized tetree
            var sceneMin = stack.floats(0.0f, 0.0f, 0.0f, 0.0f);
            var sceneMax = stack.floats(1.0f, 1.0f, 1.0f, 0.0f);
            clSetKernelArg(clKernel, 6, sceneMin);
            clSetKernelArg(clKernel, 7, sceneMax);

            // Execute kernel
            PointerBuffer globalWork = stack.pointers(rayCount);
            PointerBuffer localWork = stack.pointers(LOCAL_WORK_SIZE);

            // Round up global work size to be divisible by local work size
            long adjustedGlobal = ((rayCount + LOCAL_WORK_SIZE - 1) / LOCAL_WORK_SIZE) * LOCAL_WORK_SIZE;
            globalWork.put(0, adjustedGlobal);

            checkError(clEnqueueNDRangeKernel(clQueue, clKernel, 1, null, globalWork, localWork, null, null),
                    "execute kernel");

            // Wait for completion
            checkError(clFinish(clQueue), "finish queue");
        }
    }

    private void readResults() {
        resultBuffer.clear();
        normalBuffer.clear();

        checkError(clEnqueueReadBuffer(clQueue, clResultBuffer, true, 0, resultBuffer, null, null),
                "read results");
        checkError(clEnqueueReadBuffer(clQueue, clNormalBuffer, true, 0, normalBuffer, null, null),
                "read normals");

        resultBuffer.rewind();
        normalBuffer.rewind();
    }

    private void convertToImage() {
        outputImage.clear();

        for (int i = 0; i < rayCount; i++) {
            // Read result
            float x = resultBuffer.get();
            float y = resultBuffer.get();
            float z = resultBuffer.get();
            float distance = resultBuffer.get();

            // Read normal
            float nx = normalBuffer.get();
            float ny = normalBuffer.get();
            float nz = normalBuffer.get();
            float hitFlag = normalBuffer.get();

            byte r, g, b, a;

            if (hitFlag > 0.5f && distance > 0) {
                // Hit - shade based on normal
                // Simple directional lighting
                float light = Math.max(0.2f, 0.5f * (nx + ny + nz) + 0.5f);

                // Depth-based color
                float depthNorm = Math.min(1.0f, distance / 2.0f);
                r = (byte) (255 * light * (1.0f - depthNorm * 0.3f));
                g = (byte) (255 * light * (0.8f - depthNorm * 0.2f));
                b = (byte) (255 * light * (0.6f + depthNorm * 0.2f));
                a = (byte) 255;
            } else {
                // Miss - background color
                r = (byte) 20;
                g = (byte) 20;
                b = (byte) 30;
                a = (byte) 255;
            }

            outputImage.put(r);
            outputImage.put(g);
            outputImage.put(b);
            outputImage.put(a);
        }

        outputImage.flip();
    }

    /**
     * Get the rendered output image.
     *
     * @return ByteBuffer containing RGBA pixel data
     */
    public ByteBuffer getOutputImage() {
        return outputImage.asReadOnlyBuffer();
    }

    /**
     * Get frame width.
     */
    public int getFrameWidth() {
        return frameWidth;
    }

    /**
     * Get frame height.
     */
    public int getFrameHeight() {
        return frameHeight;
    }

    /**
     * Check if renderer is initialized.
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Check if renderer is disposed.
     */
    public boolean isDisposed() {
        return disposed;
    }

    @Override
    public void close() {
        dispose();
    }

    /**
     * Dispose all resources.
     */
    public void dispose() {
        if (disposed) {
            return;
        }

        try {
            // Release OpenCL resources
            if (clKernel != 0) clReleaseKernel(clKernel);
            if (clProgram != 0) clReleaseProgram(clProgram);
            if (clRayBuffer != 0) clReleaseMemObject(clRayBuffer);
            if (clNodeBuffer != 0) clReleaseMemObject(clNodeBuffer);
            if (clContourBuffer != 0) clReleaseMemObject(clContourBuffer);
            if (clResultBuffer != 0) clReleaseMemObject(clResultBuffer);
            if (clNormalBuffer != 0) clReleaseMemObject(clNormalBuffer);
            if (clQueue != 0) clReleaseCommandQueue(clQueue);
            if (clContext != 0) clReleaseContext(clContext);

            // Free CPU buffers
            if (rayBuffer != null) memFree(rayBuffer);
            if (resultBuffer != null) memFree(resultBuffer);
            if (normalBuffer != null) memFree(normalBuffer);
            if (outputImage != null) memFree(outputImage);

            CL.destroy();

        } catch (Exception e) {
            log.error("Error disposing OpenCL resources", e);
        }

        disposed = true;
        initialized = false;
        log.info("Disposed ESVTOpenCLRenderer");
    }

    // === Helper Methods ===

    private void logPlatformInfo(long platform) {
        try (var stack = MemoryStack.stackPush()) {
            var size = stack.mallocPointer(1);

            clGetPlatformInfo(platform, CL_PLATFORM_NAME, (ByteBuffer) null, size);
            ByteBuffer nameBuffer = stack.malloc((int) size.get(0));
            clGetPlatformInfo(platform, CL_PLATFORM_NAME, nameBuffer, null);
            log.info("OpenCL Platform: {}", memUTF8(nameBuffer));

            clGetPlatformInfo(platform, CL_PLATFORM_VERSION, (ByteBuffer) null, size);
            ByteBuffer versionBuffer = stack.malloc((int) size.get(0));
            clGetPlatformInfo(platform, CL_PLATFORM_VERSION, versionBuffer, null);
            log.info("OpenCL Version: {}", memUTF8(versionBuffer));
        }
    }

    private void logDeviceInfo(long device) {
        try (var stack = MemoryStack.stackPush()) {
            var size = stack.mallocPointer(1);

            clGetDeviceInfo(device, CL_DEVICE_NAME, (ByteBuffer) null, size);
            ByteBuffer nameBuffer = stack.malloc((int) size.get(0));
            clGetDeviceInfo(device, CL_DEVICE_NAME, nameBuffer, null);
            log.info("GPU Device: {}", memUTF8(nameBuffer));

            IntBuffer cuBuffer = stack.mallocInt(1);
            clGetDeviceInfo(device, CL_DEVICE_MAX_COMPUTE_UNITS, cuBuffer, null);
            log.info("Compute Units: {}", cuBuffer.get(0));

            var memBuffer = stack.mallocLong(1);
            clGetDeviceInfo(device, CL_DEVICE_GLOBAL_MEM_SIZE, memBuffer, null);
            log.info("Global Memory: {} MB", memBuffer.get(0) / (1024 * 1024));
        }
    }

    private void checkError(int error, String operation) {
        if (error != CL_SUCCESS) {
            throw new RuntimeException(String.format("OpenCL error during %s: %d", operation, error));
        }
    }

    private Matrix4f createLookAtMatrix(Vector3f eye, Vector3f target, Vector3f up) {
        var forward = new Vector3f();
        forward.sub(target, eye);
        forward.normalize();

        var right = new Vector3f();
        right.cross(forward, up);
        right.normalize();

        var newUp = new Vector3f();
        newUp.cross(right, forward);

        var matrix = new Matrix4f();
        matrix.m00 = right.x;
        matrix.m01 = right.y;
        matrix.m02 = right.z;
        matrix.m03 = -right.dot(eye);
        matrix.m10 = newUp.x;
        matrix.m11 = newUp.y;
        matrix.m12 = newUp.z;
        matrix.m13 = -newUp.dot(eye);
        matrix.m20 = -forward.x;
        matrix.m21 = -forward.y;
        matrix.m22 = -forward.z;
        matrix.m23 = forward.dot(eye);
        matrix.m30 = 0;
        matrix.m31 = 0;
        matrix.m32 = 0;
        matrix.m33 = 1;

        return matrix;
    }

    private Matrix4f createPerspectiveMatrix(float fovDegrees, float aspect, float near, float far) {
        float fovRad = (float) Math.toRadians(fovDegrees);
        float f = 1.0f / (float) Math.tan(fovRad / 2.0f);

        var matrix = new Matrix4f();
        matrix.m00 = f / aspect;
        matrix.m11 = f;
        matrix.m22 = (far + near) / (near - far);
        matrix.m23 = (2 * far * near) / (near - far);
        matrix.m32 = -1;
        matrix.m33 = 0;

        return matrix;
    }
}
