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
import com.hellblazer.luciferase.resource.compute.ComputeKernel;
import com.hellblazer.luciferase.resource.compute.opencl.OpenCLBuffer;
import com.hellblazer.luciferase.resource.compute.opencl.OpenCLBuffer.BufferAccess;
import com.hellblazer.luciferase.resource.compute.opencl.OpenCLContext;
import com.hellblazer.luciferase.resource.compute.opencl.OpenCLKernel;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Matrix4f;
import javax.vecmath.Vector3f;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

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

    // Result: position(3) + distance = 4 floats = 16 bytes
    private static final int RESULT_SIZE_FLOATS = 4;

    // Normal: xyz + hit_flag = 4 floats = 16 bytes
    private static final int NORMAL_SIZE_FLOATS = 4;

    private final int frameWidth;
    private final int frameHeight;
    private final int rayCount;

    // OpenCL context (singleton)
    private final OpenCLContext context;

    // GPU kernel
    private OpenCLKernel kernel;

    // GPU buffers
    private OpenCLBuffer rayBuffer;
    private long clNodeBuffer;  // Raw cl_mem handle for ByteBuffer upload
    private long clContourBuffer;  // Raw cl_mem handle for ByteBuffer upload
    private OpenCLBuffer resultBuffer;
    private OpenCLBuffer normalBuffer;
    private OpenCLBuffer sceneMinBuffer;
    private OpenCLBuffer sceneMaxBuffer;

    // CPU buffers for data transfer
    private FloatBuffer cpuRayBuffer;
    private FloatBuffer cpuResultBuffer;
    private FloatBuffer cpuNormalBuffer;

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
        this.context = OpenCLContext.getInstance();
    }

    /**
     * Check if OpenCL is available on this system.
     *
     * @return true if OpenCL GPU is available
     */
    public static boolean isOpenCLAvailable() {
        try {
            var ctx = OpenCLContext.getInstance();
            if (!ctx.isInitialized()) {
                ctx.acquire();
                boolean available = ctx.isInitialized();
                ctx.release();
                return available;
            }
            return true;
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
            // Acquire OpenCL context (increments ref count)
            context.acquire();

            // Compile kernel
            compileKernel();

            // Allocate buffers
            allocateBuffers();

            initialized = true;
            log.info("Initialized ESVTOpenCLRenderer: {}x{} ({} rays)", frameWidth, frameHeight, rayCount);

        } catch (Exception e) {
            dispose();
            throw new RuntimeException("Failed to initialize OpenCL renderer", e);
        }
    }

    private void compileKernel() throws ComputeKernel.KernelCompilationException {
        // Load kernel source
        var kernelSource = ESVTKernels.getOpenCLKernel();
        if (kernelSource == null || kernelSource.isEmpty()) {
            throw new RuntimeException("Failed to load ESVT OpenCL kernel");
        }

        // Create and compile kernel
        kernel = OpenCLKernel.create("ESVT_Traversal");
        kernel.compile(kernelSource, "traverseESVT");

        log.info("ESVT OpenCL kernel compiled successfully");
    }

    private void allocateBuffers() {
        // Allocate CPU buffers
        cpuRayBuffer = memAllocFloat(rayCount * RAY_SIZE_FLOATS);
        cpuResultBuffer = memAllocFloat(rayCount * RESULT_SIZE_FLOATS);
        cpuNormalBuffer = memAllocFloat(rayCount * NORMAL_SIZE_FLOATS);
        outputImage = memAlloc(frameWidth * frameHeight * 4); // RGBA

        // Allocate GPU buffers
        rayBuffer = OpenCLBuffer.create(rayCount * RAY_SIZE_FLOATS, BufferAccess.READ_ONLY);
        resultBuffer = OpenCLBuffer.create(rayCount * RESULT_SIZE_FLOATS, BufferAccess.WRITE_ONLY);
        normalBuffer = OpenCLBuffer.create(rayCount * NORMAL_SIZE_FLOATS, BufferAccess.WRITE_ONLY);

        // Scene bounds buffers (vec4: 4 floats each)
        sceneMinBuffer = OpenCLBuffer.create(4, BufferAccess.READ_ONLY);
        sceneMaxBuffer = OpenCLBuffer.create(4, BufferAccess.READ_ONLY);

        // Upload default scene bounds (0,0,0) to (1,1,1) for normalized tetree
        sceneMinBuffer.upload(new float[]{0.0f, 0.0f, 0.0f, 0.0f});
        sceneMaxBuffer.upload(new float[]{1.0f, 1.0f, 1.0f, 0.0f});

        // Initialize empty node/contour buffers (will be replaced when data is uploaded)
        clNodeBuffer = 0;
        clContourBuffer = 0;

        log.debug("Allocated OpenCL buffers for {} rays", rayCount);
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

        // Release old node buffer if exists
        if (clNodeBuffer != 0) {
            clReleaseMemObject(clNodeBuffer);
        }

        // Upload node data using raw ByteBuffer (since gpu-support focuses on float[])
        var nodeData = data.nodesToByteBuffer();
        this.nodeCount = data.nodeCount();
        this.maxDepth = data.maxDepth();

        // Create buffer with raw OpenCL API for ByteBuffer compatibility
        try (var stack = MemoryStack.stackPush()) {
            var errcode = stack.mallocInt(1);
            clNodeBuffer = clCreateBuffer(context.getContext(),
                    CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
                    nodeData, errcode);
            if (errcode.get(0) != CL_SUCCESS) {
                throw new RuntimeException("Failed to create node buffer: " + errcode.get(0));
            }
        }

        // Upload contour data if present
        if (clContourBuffer != 0) {
            clReleaseMemObject(clContourBuffer);
        }

        if (data.hasContours()) {
            var contourData = data.contoursToByteBuffer();
            this.contourCount = data.contourCount();

            try (var stack = MemoryStack.stackPush()) {
                var errcode = stack.mallocInt(1);
                clContourBuffer = clCreateBuffer(context.getContext(),
                        CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
                        contourData, errcode);
                if (errcode.get(0) != CL_SUCCESS) {
                    throw new RuntimeException("Failed to create contour buffer: " + errcode.get(0));
                }
            }
        } else {
            this.contourCount = 0;
            // Create minimal empty buffer
            try (var stack = MemoryStack.stackPush()) {
                var errcode = stack.mallocInt(1);
                clContourBuffer = clCreateBuffer(context.getContext(),
                        CL_MEM_READ_ONLY, 4, errcode);
                if (errcode.get(0) != CL_SUCCESS) {
                    throw new RuntimeException("Failed to create empty contour buffer: " + errcode.get(0));
                }
            }
        }

        log.debug("Uploaded ESVT data: {} nodes, {} contours", nodeCount, contourCount);
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

        try {
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

        } catch (ComputeKernel.KernelExecutionException e) {
            throw new RuntimeException("Kernel execution failed", e);
        }
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

        cpuRayBuffer.clear();

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
                cpuRayBuffer.put(cameraPos.x);
                cpuRayBuffer.put(cameraPos.y);
                cpuRayBuffer.put(cameraPos.z);
                cpuRayBuffer.put(rayDir.x);
                cpuRayBuffer.put(rayDir.y);
                cpuRayBuffer.put(rayDir.z);
                cpuRayBuffer.put(0.001f);  // tmin
                cpuRayBuffer.put(1000.0f); // tmax
            }
        }

        cpuRayBuffer.flip();
    }

    private void uploadRays() {
        rayBuffer.upload(cpuRayBuffer);
    }

    private void executeKernel() throws ComputeKernel.KernelExecutionException {
        // Set kernel arguments using gpu-support framework where possible
        kernel.setBufferArg(0, rayBuffer, ComputeKernel.BufferAccess.READ);

        // For node and contour buffers, use raw LWJGL since they're ByteBuffer-based
        // Note: kernel.kernel is package-private, so we access it via reflection workaround
        setRawBufferArg(1, clNodeBuffer);
        setRawBufferArg(2, clContourBuffer);

        kernel.setBufferArg(3, resultBuffer, ComputeKernel.BufferAccess.WRITE);
        kernel.setBufferArg(4, normalBuffer, ComputeKernel.BufferAccess.WRITE);
        kernel.setIntArg(5, maxDepth);

        // Note: sceneMin/sceneMax removed from kernel - not used, was causing type mismatch

        // Execute kernel with explicit local work size
        long adjustedGlobal = ((rayCount + LOCAL_WORK_SIZE - 1) / LOCAL_WORK_SIZE) * LOCAL_WORK_SIZE;

        // Use OpenCLKernel's execute method with explicit local work size
        kernel.execute((int) adjustedGlobal, 1, 1, LOCAL_WORK_SIZE, 1, 1);

        // Wait for completion
        kernel.finish();
    }

    /**
     * Set a raw cl_mem buffer argument directly using reflection to access the kernel handle.
     * This is a workaround for ByteBuffer-based buffers that can't use OpenCLBuffer's float[] API.
     */
    private void setRawBufferArg(int index, long clMem) {
        try {
            // Access the package-private kernel field via reflection
            var kernelField = OpenCLKernel.class.getDeclaredField("kernel");
            kernelField.setAccessible(true);
            long kernelHandle = (long) kernelField.get(kernel);

            // Set the argument directly
            clSetKernelArg1p(kernelHandle, index, clMem);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set raw buffer argument", e);
        }
    }

    private void readResults() {
        cpuResultBuffer.clear();
        cpuNormalBuffer.clear();

        resultBuffer.download(cpuResultBuffer);
        normalBuffer.download(cpuNormalBuffer);

        cpuResultBuffer.rewind();
        cpuNormalBuffer.rewind();
    }

    private void convertToImage() {
        outputImage.clear();

        for (int i = 0; i < rayCount; i++) {
            // Read result
            float x = cpuResultBuffer.get();
            float y = cpuResultBuffer.get();
            float z = cpuResultBuffer.get();
            float distance = cpuResultBuffer.get();

            // Read normal
            float nx = cpuNormalBuffer.get();
            float ny = cpuNormalBuffer.get();
            float nz = cpuNormalBuffer.get();
            float hitFlag = cpuNormalBuffer.get();

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
            // Close GPU kernel and buffers
            if (kernel != null) kernel.close();
            if (rayBuffer != null) rayBuffer.close();
            if (clNodeBuffer != 0) clReleaseMemObject(clNodeBuffer);
            if (clContourBuffer != 0) clReleaseMemObject(clContourBuffer);
            if (resultBuffer != null) resultBuffer.close();
            if (normalBuffer != null) normalBuffer.close();
            if (sceneMinBuffer != null) sceneMinBuffer.close();
            if (sceneMaxBuffer != null) sceneMaxBuffer.close();

            // Free CPU buffers
            if (cpuRayBuffer != null) memFree(cpuRayBuffer);
            if (cpuResultBuffer != null) memFree(cpuResultBuffer);
            if (cpuNormalBuffer != null) memFree(cpuNormalBuffer);
            if (outputImage != null) memFree(outputImage);

            // Release OpenCL context (decrements ref count)
            context.release();

        } catch (Exception e) {
            log.error("Error disposing OpenCL resources", e);
        }

        disposed = true;
        initialized = false;
        log.info("Disposed ESVTOpenCLRenderer");
    }

    // === Helper Methods ===

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
