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

import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.esvo.core.ESVONodeUnified;
import com.hellblazer.luciferase.resource.compute.ComputeKernel;
import com.hellblazer.luciferase.resource.compute.opencl.OpenCLBuffer;
import com.hellblazer.luciferase.resource.compute.opencl.OpenCLBuffer.BufferAccess;
import com.hellblazer.luciferase.resource.compute.opencl.OpenCLContext;
import com.hellblazer.luciferase.resource.compute.opencl.OpenCLKernel;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Matrix4f;
import javax.vecmath.Vector3f;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import static org.lwjgl.opencl.CL10.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * OpenCL-based GPU Renderer for ESVO (Efficient Sparse Voxel Octrees).
 *
 * <p>This renderer uses OpenCL 1.2 for GPU compute. Unlike OpenGL 4.3+ compute
 * shaders, OpenCL is supported on a wider range of platforms including older macOS
 * systems (Intel Macs), Linux with GPU drivers, and Windows.
 *
 * <p><b>Platform Compatibility:</b>
 * <ul>
 *   <li><b>Apple Silicon Macs (M1/M2/M3/M4)</b>: OpenCL 1.2 IS supported via LWJGL</li>
 *   <li><b>Intel Macs</b>: OpenCL available (deprecated but functional)</li>
 *   <li><b>Linux</b>: OpenCL available with appropriate GPU drivers (Mesa, NVIDIA, AMD)</li>
 *   <li><b>Windows</b>: OpenCL available with GPU vendor drivers</li>
 * </ul>
 *
 * <p><b>Key features:</b>
 * <ul>
 *   <li>Stack-based octree ray traversal</li>
 *   <li>Sparse voxel indexing via child masks</li>
 *   <li>Far pointer support for large octrees</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public final class ESVOOpenCLRenderer implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ESVOOpenCLRenderer.class);

    // Workgroup size (must match kernel)
    private static final int LOCAL_WORK_SIZE = 64;

    // Ray structure: origin(3) + direction(3) + tmin + tmax = 8 floats = 32 bytes
    private static final int RAY_SIZE_FLOATS = 8;

    // Result: position(3) + distance = 4 floats = 16 bytes
    private static final int RESULT_SIZE_FLOATS = 4;

    // Hit info: voxel data (uint)
    private static final int HIT_SIZE_INTS = 1;

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
    private OpenCLBuffer resultBuffer;

    // CPU buffers for data transfer
    private FloatBuffer cpuRayBuffer;
    private FloatBuffer cpuResultBuffer;

    // Output image (RGBA bytes)
    private ByteBuffer outputImage;

    private boolean initialized = false;
    private boolean disposed = false;

    // Current ESVO data info
    private int nodeCount = 0;
    private int maxDepth = 10;

    // Scene bounds (in [1,2] normalized coordinates for ESVO)
    private float[] sceneMin = {1.0f, 1.0f, 1.0f};
    private float[] sceneMax = {2.0f, 2.0f, 2.0f};

    /**
     * Create OpenCL renderer with specified output resolution.
     *
     * @param frameWidth  Output width in pixels
     * @param frameHeight Output height in pixels
     */
    public ESVOOpenCLRenderer(int frameWidth, int frameHeight) {
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
            log.info("Initialized ESVOOpenCLRenderer: {}x{} ({} rays)", frameWidth, frameHeight, rayCount);

        } catch (Exception e) {
            dispose();
            throw new RuntimeException("Failed to initialize OpenCL renderer", e);
        }
    }

    private void compileKernel() throws ComputeKernel.KernelCompilationException {
        // Load kernel source
        var kernelSource = ESVOKernels.getOpenCLKernel();
        if (kernelSource == null || kernelSource.isEmpty()) {
            throw new RuntimeException("Failed to load ESVO OpenCL kernel");
        }

        // Create and compile kernel
        kernel = OpenCLKernel.create("ESVO_Traversal");
        kernel.compile(kernelSource, "traverseOctree");

        log.info("ESVO OpenCL kernel compiled successfully");
    }

    private void allocateBuffers() {
        // Allocate CPU buffers
        cpuRayBuffer = memAllocFloat(rayCount * RAY_SIZE_FLOATS);
        cpuResultBuffer = memAllocFloat(rayCount * RESULT_SIZE_FLOATS);
        outputImage = memAlloc(frameWidth * frameHeight * 4); // RGBA

        // Allocate GPU buffers
        rayBuffer = OpenCLBuffer.create(rayCount * RAY_SIZE_FLOATS, BufferAccess.READ_ONLY);
        resultBuffer = OpenCLBuffer.create(rayCount * RESULT_SIZE_FLOATS, BufferAccess.WRITE_ONLY);

        // Initialize empty node buffer
        clNodeBuffer = 0;

        log.debug("Allocated OpenCL buffers for {} rays", rayCount);
    }

    /**
     * Upload ESVO octree data to GPU.
     *
     * @param data The ESVO octree data to upload
     */
    public void uploadData(ESVOOctreeData data) {
        if (!initialized) {
            throw new IllegalStateException("Renderer not initialized");
        }

        // Release old node buffer if exists
        if (clNodeBuffer != 0) {
            clReleaseMemObject(clNodeBuffer);
        }

        // Convert octree data to ByteBuffer
        var nodeData = octreeToByteBuffer(data);
        this.nodeCount = data.getNodeCount();
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

        log.debug("Uploaded ESVO data: {} nodes, depth {}", nodeCount, maxDepth);
    }

    /**
     * Convert ESVOOctreeData to ByteBuffer for GPU upload.
     * Each node is 8 bytes (2 ints: childDescriptor + contourDescriptor).
     */
    private ByteBuffer octreeToByteBuffer(ESVOOctreeData data) {
        int nodeCount = data.getNodeCount();
        var buffer = memAlloc(nodeCount * ESVONodeUnified.SIZE_BYTES);
        buffer.order(ByteOrder.nativeOrder());

        // Get sorted node indices
        var indices = data.getNodeIndices();

        for (int idx : indices) {
            var node = data.getNode(idx);
            if (node != null) {
                buffer.putInt(node.getChildDescriptor());
                buffer.putInt(node.getContourDescriptor());
            } else {
                // Empty node
                buffer.putInt(0);
                buffer.putInt(0);
            }
        }

        buffer.flip();
        return buffer;
    }

    /**
     * Render a frame.
     *
     * @param viewMatrix    Camera view matrix
     * @param projMatrix    Camera projection matrix
     * @param objectToWorld Transform from object to world space
     * @param octreeToObject Transform from octree [1,2] to object space
     */
    public void renderFrame(Matrix4f viewMatrix, Matrix4f projMatrix,
                            Matrix4f objectToWorld, Matrix4f octreeToObject) {
        if (!initialized) {
            throw new IllegalStateException("Renderer not initialized");
        }
        if (disposed) {
            throw new IllegalStateException("Renderer has been disposed");
        }
        if (clNodeBuffer == 0) {
            throw new IllegalStateException("No ESVO data uploaded");
        }

        try {
            // Generate primary rays
            generateRays(viewMatrix, projMatrix, objectToWorld, octreeToObject);

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
                              Matrix4f objectToWorld, Matrix4f octreeToObject) {
        // Compute inverse matrices for unprojection
        var invView = new Matrix4f();
        invView.invert(viewMatrix);

        var invProj = new Matrix4f();
        invProj.invert(projMatrix);

        // Compute world-to-octree transform: inverse(octreeToObject) * inverse(objectToWorld)
        var invObjectToWorld = new Matrix4f();
        invObjectToWorld.invert(objectToWorld);

        var invOctreeToObject = new Matrix4f();
        invOctreeToObject.invert(octreeToObject);

        var worldToOctree = new Matrix4f();
        worldToOctree.mul(invOctreeToObject, invObjectToWorld);

        // Camera position in world space
        var cameraPosWorld = new Vector3f(invView.m03, invView.m13, invView.m23);

        // Transform camera position to octree space
        var cameraPosT = new javax.vecmath.Vector4f(cameraPosWorld.x, cameraPosWorld.y, cameraPosWorld.z, 1.0f);
        var cameraPosOctree4 = new javax.vecmath.Vector4f();
        worldToOctree.transform(cameraPosT, cameraPosOctree4);
        var cameraPosOctree = new Vector3f(cameraPosOctree4.x, cameraPosOctree4.y, cameraPosOctree4.z);

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

                // Apply inverse view to get world position
                var worldPos = new javax.vecmath.Vector4f();
                invView.transform(viewPos, worldPos);

                // Transform world position to octree space
                var octreePos = new javax.vecmath.Vector4f();
                worldToOctree.transform(worldPos, octreePos);

                // Ray direction in octree space
                var rayDir = new Vector3f(
                        octreePos.x - cameraPosOctree.x,
                        octreePos.y - cameraPosOctree.y,
                        octreePos.z - cameraPosOctree.z
                );
                rayDir.normalize();

                // Write ray to buffer (in octree space)
                cpuRayBuffer.put(cameraPosOctree.x);
                cpuRayBuffer.put(cameraPosOctree.y);
                cpuRayBuffer.put(cameraPosOctree.z);
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
        // Set kernel arguments
        kernel.setBufferArg(0, rayBuffer, ComputeKernel.BufferAccess.READ);

        // Node buffer (raw cl_mem)
        setRawBufferArg(1, clNodeBuffer);

        kernel.setBufferArg(2, resultBuffer, ComputeKernel.BufferAccess.WRITE);
        kernel.setIntArg(3, maxDepth);

        // Scene bounds (vec3 as separate floats)
        try {
            var kernelField = OpenCLKernel.class.getDeclaredField("kernel");
            kernelField.setAccessible(true);
            long kernelHandle = (long) kernelField.get(kernel);

            // Set sceneMin (arg 4) and sceneMax (arg 5) as float3
            try (var stack = MemoryStack.stackPush()) {
                var minBuf = stack.floats(sceneMin[0], sceneMin[1], sceneMin[2]);
                var maxBuf = stack.floats(sceneMax[0], sceneMax[1], sceneMax[2]);
                clSetKernelArg(kernelHandle, 4, minBuf);
                clSetKernelArg(kernelHandle, 5, maxBuf);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to set scene bounds arguments", e);
        }

        // Execute kernel with explicit local work size
        long adjustedGlobal = ((rayCount + LOCAL_WORK_SIZE - 1) / LOCAL_WORK_SIZE) * LOCAL_WORK_SIZE;

        kernel.execute((int) adjustedGlobal, 1, 1, LOCAL_WORK_SIZE, 1, 1);

        // Wait for completion
        kernel.finish();
    }

    /**
     * Set a raw cl_mem buffer argument directly using reflection.
     */
    private void setRawBufferArg(int index, long clMem) {
        try {
            var kernelField = OpenCLKernel.class.getDeclaredField("kernel");
            kernelField.setAccessible(true);
            long kernelHandle = (long) kernelField.get(kernel);
            clSetKernelArg1p(kernelHandle, index, clMem);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set raw buffer argument", e);
        }
    }

    private void readResults() {
        cpuResultBuffer.clear();
        resultBuffer.download(cpuResultBuffer);
        cpuResultBuffer.rewind();
    }

    private void convertToImage() {
        outputImage.clear();

        for (int i = 0; i < rayCount; i++) {
            // Read result
            float hitX = cpuResultBuffer.get();
            float hitY = cpuResultBuffer.get();
            float hitZ = cpuResultBuffer.get();
            float distance = cpuResultBuffer.get();

            byte r, g, b, a;

            if (distance > 0 && distance < 100.0f) {
                // Hit - compute color based on depth/distance
                // Use depth coloring: close = red, far = blue
                float normalizedDist = Math.min(1.0f, distance / 2.0f);
                r = (byte) (255 * (1.0f - normalizedDist));
                g = (byte) (100 * (1.0f - normalizedDist * 0.5f));
                b = (byte) (255 * normalizedDist);
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
            if (resultBuffer != null) resultBuffer.close();

            // Free CPU buffers
            if (cpuRayBuffer != null) memFree(cpuRayBuffer);
            if (cpuResultBuffer != null) memFree(cpuResultBuffer);
            if (outputImage != null) memFree(outputImage);

            // Release OpenCL context (decrements ref count)
            context.release();

        } catch (Exception e) {
            log.error("Error disposing OpenCL resources", e);
        }

        disposed = true;
        initialized = false;
        log.info("Disposed ESVOOpenCLRenderer");
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
