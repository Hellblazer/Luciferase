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
package com.hellblazer.luciferase.sparse.gpu;

import com.hellblazer.luciferase.resource.compute.ComputeKernel;
import com.hellblazer.luciferase.resource.compute.opencl.OpenCLBuffer;
import com.hellblazer.luciferase.resource.compute.opencl.OpenCLBuffer.BufferAccess;
import com.hellblazer.luciferase.resource.compute.opencl.OpenCLContext;
import com.hellblazer.luciferase.resource.compute.opencl.OpenCLKernel;
import com.hellblazer.luciferase.sparse.core.SparseVoxelData;
import com.hellblazer.luciferase.sparse.core.SparseVoxelNode;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Matrix4f;
import javax.vecmath.Vector3f;
import javax.vecmath.Vector4f;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.lwjgl.opencl.CL10.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * Abstract base class for OpenCL-based GPU renderers.
 *
 * <p>This renderer uses OpenCL 1.2 for GPU compute. Unlike OpenGL 4.3+ compute
 * shaders, OpenCL is supported on a wider range of platforms including older macOS
 * systems (Intel Macs), Linux with GPU drivers, and Windows.
 *
 * <p>Subclasses provide:
 * <ul>
 *   <li>Kernel source loading via {@link #getKernelSource()}</li>
 *   <li>Kernel entry point via {@link #getKernelEntryPoint()}</li>
 *   <li>Data upload via {@link #uploadDataBuffers(SparseVoxelData)}</li>
 *   <li>Kernel argument setup via {@link #setKernelArguments()}</li>
 *   <li>Pixel shading via {@link #computePixelColor(float, float, float, float, float, float, float, float)}</li>
 *   <li>Buffer allocation via {@link #allocateTypeSpecificBuffers()}</li>
 *   <li>Buffer cleanup via {@link #disposeTypeSpecificBuffers()}</li>
 * </ul>
 *
 * <p><b>Platform Compatibility:</b>
 * <ul>
 *   <li><b>Apple Silicon Macs (M1/M2/M3/M4)</b>: OpenCL 1.2 IS supported via LWJGL</li>
 *   <li><b>Intel Macs</b>: OpenCL available (deprecated but functional)</li>
 *   <li><b>Linux</b>: OpenCL available with appropriate GPU drivers (Mesa, NVIDIA, AMD)</li>
 *   <li><b>Windows</b>: OpenCL available with GPU vendor drivers</li>
 * </ul>
 *
 * <p><b>Thread Safety:</b> This class is <em>NOT</em> thread-safe. Each thread
 * requiring GPU rendering must use its own renderer instance. The underlying
 * OpenCL context is thread-safe via reference counting, but kernel execution
 * and buffer operations are not synchronized.
 *
 * @param <N> the concrete node type used by the data structure
 * @param <D> the type of sparse voxel data this renderer handles
 * @author hal.hildebrand
 */
public abstract class AbstractOpenCLRenderer<N extends SparseVoxelNode, D extends SparseVoxelData<N>>
        implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AbstractOpenCLRenderer.class);

    // Workgroup size (must match kernel)
    protected static final int LOCAL_WORK_SIZE = 64;

    // Ray structure: origin(3) + direction(3) + tmin + tmax = 8 floats = 32 bytes
    protected static final int RAY_SIZE_FLOATS = 8;

    // Result: position(3) + distance = 4 floats = 16 bytes
    protected static final int RESULT_SIZE_FLOATS = 4;

    protected final int frameWidth;
    protected final int frameHeight;
    protected final int rayCount;

    // OpenCL context (singleton)
    protected final OpenCLContext context;

    // GPU kernel
    protected OpenCLKernel kernel;

    // GPU buffers (common to all renderers)
    protected OpenCLBuffer rayBuffer;
    protected OpenCLBuffer resultBuffer;

    // CPU buffers for data transfer
    protected FloatBuffer cpuRayBuffer;
    protected FloatBuffer cpuResultBuffer;

    // Output image (RGBA bytes)
    protected ByteBuffer outputImage;

    protected boolean initialized = false;
    protected boolean disposed = false;

    // Current data info
    protected int nodeCount = 0;
    protected int maxDepth = 10;

    /**
     * Create OpenCL renderer with specified output resolution.
     *
     * @param frameWidth  Output width in pixels
     * @param frameHeight Output height in pixels
     */
    protected AbstractOpenCLRenderer(int frameWidth, int frameHeight) {
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

            // Phase 4.2.2b: Hook for subclasses to initialize additional kernels
            onKernelCompiled();

            // Allocate common buffers
            allocateCommonBuffers();

            // Allocate type-specific buffers
            allocateTypeSpecificBuffers();

            initialized = true;
            log.info("Initialized {}: {}x{} ({} rays)",
                    getRendererName(), frameWidth, frameHeight, rayCount);

        } catch (Exception e) {
            dispose();
            throw new RuntimeException("Failed to initialize OpenCL renderer", e);
        }
    }

    private void compileKernel() throws ComputeKernel.KernelCompilationException {
        // Load kernel source
        var kernelSource = getKernelSource();
        if (kernelSource == null || kernelSource.isEmpty()) {
            throw new RuntimeException("Failed to load OpenCL kernel for " + getRendererName());
        }

        // Create and compile kernel
        kernel = OpenCLKernel.create(getRendererName());

        // Get build options from subclass (may be null for default compilation)
        var buildOptions = getBuildOptions();
        if (buildOptions != null && !buildOptions.isEmpty()) {
            kernel.compile(kernelSource, getKernelEntryPoint(), buildOptions);
            log.info("{} kernel compiled successfully with build options: {}",
                    getRendererName(), buildOptions);
        } else {
            kernel.compile(kernelSource, getKernelEntryPoint());
            log.info("{} kernel compiled successfully", getRendererName());
        }
    }

    private void allocateCommonBuffers() {
        // Allocate CPU buffers
        cpuRayBuffer = memAllocFloat(rayCount * RAY_SIZE_FLOATS);
        cpuResultBuffer = memAllocFloat(rayCount * RESULT_SIZE_FLOATS);
        outputImage = memAlloc(frameWidth * frameHeight * 4); // RGBA

        // Allocate GPU buffers
        rayBuffer = OpenCLBuffer.create(rayCount * RAY_SIZE_FLOATS, BufferAccess.READ_ONLY);
        resultBuffer = OpenCLBuffer.create(rayCount * RESULT_SIZE_FLOATS, BufferAccess.WRITE_ONLY);

        log.debug("Allocated OpenCL buffers for {} rays", rayCount);
    }

    /**
     * Upload data to GPU.
     *
     * @param data The sparse voxel data to upload
     */
    public void uploadData(D data) {
        if (!initialized) {
            throw new IllegalStateException("Renderer not initialized");
        }

        this.nodeCount = data.nodeCount();
        this.maxDepth = data.maxDepth();

        // Delegate to subclass for data-specific upload
        uploadDataBuffers(data);

        log.debug("Uploaded data: {} nodes, depth {}", nodeCount, maxDepth);
    }

    /**
     * Render a frame.
     *
     * @param viewMatrix      Camera view matrix
     * @param projMatrix      Camera projection matrix
     * @param objectToWorld   Transform from object to world space
     * @param dataSpaceToObject Transform from data space to object space
     */
    public void renderFrame(Matrix4f viewMatrix, Matrix4f projMatrix,
                            Matrix4f objectToWorld, Matrix4f dataSpaceToObject) {
        if (!initialized) {
            throw new IllegalStateException("Renderer not initialized");
        }
        if (disposed) {
            throw new IllegalStateException("Renderer has been disposed");
        }
        if (!hasDataUploaded()) {
            throw new IllegalStateException("No data uploaded");
        }

        try {
            // Generate primary rays
            generateRays(viewMatrix, projMatrix, objectToWorld, dataSpaceToObject);

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

    /**
     * Generate primary rays for all pixels.
     */
    protected void generateRays(Matrix4f viewMatrix, Matrix4f projMatrix,
                                Matrix4f objectToWorld, Matrix4f dataSpaceToObject) {
        // Compute inverse matrices for unprojection
        var invView = new Matrix4f();
        invView.invert(viewMatrix);

        var invProj = new Matrix4f();
        invProj.invert(projMatrix);

        // Compute world-to-data-space transform
        var invObjectToWorld = new Matrix4f();
        invObjectToWorld.invert(objectToWorld);

        var invDataSpaceToObject = new Matrix4f();
        invDataSpaceToObject.invert(dataSpaceToObject);

        var worldToDataSpace = new Matrix4f();
        worldToDataSpace.mul(invDataSpaceToObject, invObjectToWorld);

        // Camera position in world space
        var cameraPosWorld = new Vector3f(invView.m03, invView.m13, invView.m23);

        // Transform camera position to data space
        var cameraPosT = new Vector4f(cameraPosWorld.x, cameraPosWorld.y, cameraPosWorld.z, 1.0f);
        var cameraPosDataSpace4 = new Vector4f();
        worldToDataSpace.transform(cameraPosT, cameraPosDataSpace4);
        var cameraPosDataSpace = new Vector3f(cameraPosDataSpace4.x, cameraPosDataSpace4.y, cameraPosDataSpace4.z);

        cpuRayBuffer.clear();

        for (int y = 0; y < frameHeight; y++) {
            for (int x = 0; x < frameWidth; x++) {
                // Normalized device coordinates [-1, 1]
                float ndcX = (2.0f * x / frameWidth) - 1.0f;
                float ndcY = 1.0f - (2.0f * y / frameHeight);

                // Unproject to view space
                var clipPos = new Vector4f(ndcX, ndcY, -1.0f, 1.0f);

                // Apply inverse projection
                var viewPos = new Vector4f();
                invProj.transform(clipPos, viewPos);
                viewPos.scale(1.0f / viewPos.w);

                // Apply inverse view to get world position
                var worldPos = new Vector4f();
                invView.transform(viewPos, worldPos);

                // Transform world position to data space
                var dataSpacePos = new Vector4f();
                worldToDataSpace.transform(worldPos, dataSpacePos);

                // Ray direction in data space
                var rayDir = new Vector3f(
                        dataSpacePos.x - cameraPosDataSpace.x,
                        dataSpacePos.y - cameraPosDataSpace.y,
                        dataSpacePos.z - cameraPosDataSpace.z
                );
                rayDir.normalize();

                // Write ray to buffer (in data space)
                cpuRayBuffer.put(cameraPosDataSpace.x);
                cpuRayBuffer.put(cameraPosDataSpace.y);
                cpuRayBuffer.put(cameraPosDataSpace.z);
                cpuRayBuffer.put(rayDir.x);
                cpuRayBuffer.put(rayDir.y);
                cpuRayBuffer.put(rayDir.z);
                cpuRayBuffer.put(0.001f);  // tmin
                cpuRayBuffer.put(1000.0f); // tmax
            }
        }

        cpuRayBuffer.flip();
    }

    protected void uploadRays() {
        rayBuffer.upload(cpuRayBuffer);
    }

    protected void executeKernel() throws ComputeKernel.KernelExecutionException {
        // Set common kernel arguments
        kernel.setBufferArg(0, rayBuffer, ComputeKernel.BufferAccess.READ);

        // Delegate to subclass for type-specific arguments
        setKernelArguments();

        // Execute kernel with explicit local work size
        long adjustedGlobal = ((rayCount + LOCAL_WORK_SIZE - 1) / LOCAL_WORK_SIZE) * LOCAL_WORK_SIZE;
        kernel.execute((int) adjustedGlobal, 1, 1, LOCAL_WORK_SIZE, 1, 1);

        // Wait for completion
        kernel.finish();
    }

    protected void readResults() {
        cpuResultBuffer.clear();
        resultBuffer.download(cpuResultBuffer);
        cpuResultBuffer.rewind();

        // Delegate to subclass for additional result reading
        readTypeSpecificResults();
    }

    /**
     * Set a raw cl_mem buffer argument directly.
     *
     * <p>This delegates to the gpu-support library's public API for setting
     * raw OpenCL memory handles as kernel arguments. Used for sparse voxel
     * data stored as packed bytes (node descriptors) that are uploaded via
     * {@code clCreateBuffer()} rather than through the float[]-based GPUBuffer API.
     *
     * @param index kernel argument index
     * @param clMem raw OpenCL memory handle from {@link #createRawBuffer}
     */
    protected void setRawBufferArg(int index, long clMem) {
        kernel.setRawBufferArg(index, clMem);
    }

    /**
     * Set a float3 vector kernel argument.
     *
     * <p>Convenience method for setting 3-component vector arguments such as
     * scene bounds, positions, or directions.
     *
     * @param index kernel argument index
     * @param x X component
     * @param y Y component
     * @param z Z component
     */
    protected void setFloat3Arg(int index, float x, float y, float z) {
        kernel.setFloat3Arg(index, x, y, z);
    }

    /**
     * Create a raw OpenCL buffer from a ByteBuffer.
     */
    protected long createRawBuffer(ByteBuffer data, int flags) {
        try (var stack = MemoryStack.stackPush()) {
            var errcode = stack.mallocInt(1);
            long clMem = clCreateBuffer(context.getContext(), flags, data, errcode);
            if (errcode.get(0) != CL_SUCCESS) {
                throw new RuntimeException("Failed to create buffer: " + errcode.get(0));
            }
            return clMem;
        }
    }

    /**
     * Create an empty raw OpenCL buffer of specified size.
     */
    protected long createEmptyRawBuffer(long size, int flags) {
        try (var stack = MemoryStack.stackPush()) {
            var errcode = stack.mallocInt(1);
            long clMem = clCreateBuffer(context.getContext(), flags, size, errcode);
            if (errcode.get(0) != CL_SUCCESS) {
                throw new RuntimeException("Failed to create empty buffer: " + errcode.get(0));
            }
            return clMem;
        }
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
     *
     * <p>This method ensures all resources are released even if errors occur
     * during disposal of individual components. Errors are logged but do not
     * prevent cleanup of remaining resources.
     */
    public void dispose() {
        if (disposed) {
            return;
        }

        // Dispose type-specific buffers first (must not throw)
        try {
            disposeTypeSpecificBuffers();
        } catch (Exception e) {
            log.error("Error disposing type-specific buffers in {}", getRendererName(), e);
        }

        // Close common GPU kernel and buffers - continue even if errors occur
        try {
            if (kernel != null) kernel.close();
        } catch (Exception e) {
            log.error("Error closing kernel", e);
        }

        try {
            if (rayBuffer != null) rayBuffer.close();
        } catch (Exception e) {
            log.error("Error closing ray buffer", e);
        }

        try {
            if (resultBuffer != null) resultBuffer.close();
        } catch (Exception e) {
            log.error("Error closing result buffer", e);
        }

        // Free common CPU buffers
        try {
            if (cpuRayBuffer != null) memFree(cpuRayBuffer);
        } catch (Exception e) {
            log.error("Error freeing CPU ray buffer", e);
        }

        try {
            if (cpuResultBuffer != null) memFree(cpuResultBuffer);
        } catch (Exception e) {
            log.error("Error freeing CPU result buffer", e);
        }

        try {
            if (outputImage != null) memFree(outputImage);
        } catch (Exception e) {
            log.error("Error freeing output image", e);
        }

        // Release OpenCL context (decrements ref count) - always attempt this
        try {
            context.release();
        } catch (Exception e) {
            log.error("Error releasing OpenCL context", e);
        }

        disposed = true;
        initialized = false;
        log.info("Disposed {}", getRendererName());
    }

    // === Matrix Helper Methods ===

    /**
     * Create a look-at view matrix.
     */
    public static Matrix4f createLookAtMatrix(Vector3f eye, Vector3f target, Vector3f up) {
        var forward = new Vector3f();
        forward.sub(target, eye);
        forward.normalize();

        // Handle case where forward is parallel to up
        var actualUp = new Vector3f(up);
        var right = new Vector3f();
        right.cross(forward, actualUp);
        if (right.lengthSquared() < 1e-6f) {
            // Forward and up are parallel, use alternative up vector
            actualUp.set(0, 0, 1);
            right.cross(forward, actualUp);
            if (right.lengthSquared() < 1e-6f) {
                actualUp.set(1, 0, 0);
                right.cross(forward, actualUp);
            }
        }
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

    /**
     * Create a perspective projection matrix.
     */
    public static Matrix4f createPerspectiveMatrix(float fovDegrees, float aspect, float near, float far) {
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

    // === Abstract Methods for Subclass Implementation ===

    /**
     * Get the renderer name for logging.
     */
    protected abstract String getRendererName();

    /**
     * Get the OpenCL kernel source code.
     */
    protected abstract String getKernelSource();

    /**
     * Get the kernel entry point function name.
     */
    protected abstract String getKernelEntryPoint();

    /**
     * Check if data has been uploaded.
     */
    protected abstract boolean hasDataUploaded();

    /**
     * Allocate type-specific GPU buffers.
     * Called during {@link #initialize()}.
     */
    protected abstract void allocateTypeSpecificBuffers();

    /**
     * Upload data-specific buffers to GPU.
     * Called by {@link #uploadData(SparseVoxelData)}.
     *
     * @param data The data to upload
     */
    protected abstract void uploadDataBuffers(D data);

    /**
     * Set type-specific kernel arguments.
     * Called during {@link #executeKernel()}.
     * Argument 0 (rayBuffer) is already set by the base class.
     */
    protected abstract void setKernelArguments();

    /**
     * Read type-specific results from GPU.
     * Called after reading common results.
     */
    protected abstract void readTypeSpecificResults();

    /**
     * Convert ray results to RGBA output image.
     *
     * <p>The default implementation iterates through all ray results and calls
     * {@link #computePixelColor} for each pixel. Subclasses can override this
     * method entirely for custom behavior, or just override {@link #computePixelColor}
     * to customize shading while keeping the common pixel loop.
     */
    protected void convertToImage() {
        outputImage.clear();

        for (int i = 0; i < rayCount; i++) {
            // Read common result: hit position + distance
            float hitX = cpuResultBuffer.get();
            float hitY = cpuResultBuffer.get();
            float hitZ = cpuResultBuffer.get();
            float distance = cpuResultBuffer.get();

            // Read type-specific data (e.g., normals)
            float[] extraData = readPixelExtraData();

            // Compute pixel color using subclass-provided shading
            int rgba = computePixelColor(hitX, hitY, hitZ, distance, extraData);

            // Write RGBA to output image
            outputImage.put((byte) ((rgba >> 24) & 0xFF)); // R
            outputImage.put((byte) ((rgba >> 16) & 0xFF)); // G
            outputImage.put((byte) ((rgba >> 8) & 0xFF));  // B
            outputImage.put((byte) (rgba & 0xFF));         // A
        }

        outputImage.flip();
    }

    /**
     * Read any type-specific data for the current pixel.
     *
     * <p>Override to read additional per-pixel data (e.g., normals from a normal buffer).
     * The returned array is passed to {@link #computePixelColor}.
     *
     * @return extra data for the current pixel, or empty array if none
     */
    protected float[] readPixelExtraData() {
        return EMPTY_EXTRA_DATA;
    }

    private static final float[] EMPTY_EXTRA_DATA = new float[0];

    /**
     * Compute the RGBA color for a single pixel.
     *
     * <p>This is the shading hook that subclasses implement to provide
     * type-specific coloring (e.g., depth-based for ESVO, normal-based for ESVT).
     *
     * @param hitX hit position X, or 0 if miss
     * @param hitY hit position Y, or 0 if miss
     * @param hitZ hit position Z, or 0 if miss
     * @param distance ray hit distance, or negative/large value if miss
     * @param extraData additional per-pixel data from {@link #readPixelExtraData}
     * @return packed RGBA color (R in high byte, A in low byte)
     */
    protected abstract int computePixelColor(float hitX, float hitY, float hitZ,
                                              float distance, float[] extraData);

    /**
     * Get OpenCL build options for kernel compilation.
     *
     * <p>Subclasses override this to provide custom build options such as
     * preprocessor defines (-DDAG_TRAVERSAL=1) or compiler flags (-cl-fast-relaxed-math).
     *
     * @return build options string, or null for default compilation
     */
    protected String getBuildOptions() {
        return null; // Default: no build options
    }

    /**
     * Recompile the kernel with new build options.
     *
     * <p>Enables runtime kernel recompilation for GPU optimization based on
     * profiler feedback or scene characteristics. The new kernel takes effect
     * on the next render call.
     *
     * @param newBuildOptions new build options for recompilation
     * @throws IllegalStateException if renderer not initialized or kernel null
     */
    public void recompileKernel(String newBuildOptions) {
        if (!initialized) {
            throw new IllegalStateException("Renderer not initialized");
        }
        if (kernel == null) {
            throw new IllegalStateException("Kernel not compiled");
        }

        try {
            var kernelSource = getKernelSource();
            kernel.recompile(kernelSource, getKernelEntryPoint(), newBuildOptions);
            log.info("{} kernel recompiled with options: {}", getRendererName(), newBuildOptions);
        } catch (ComputeKernel.KernelCompilationException e) {
            throw new RuntimeException("Kernel recompilation failed", e);
        }
    }

    /**
     * Phase 4.2.2b: Hook called after main kernel is compiled.
     *
     * <p>Subclasses can override to initialize additional kernels (e.g., batch kernels)
     * or perform other post-compilation setup. Default implementation does nothing.
     *
     * <p>Called from {@link #initialize()} after {@link #compileKernel()} completes.
     */
    protected void onKernelCompiled() {
        // Default: no additional kernels. Subclasses override to initialize batch kernels.
    }

    /**
     * Dispose type-specific GPU resources.
     *
     * <p>Called during {@link #dispose()}. Implementations should not throw
     * exceptions; errors should be logged internally.
     */
    protected abstract void disposeTypeSpecificBuffers();
}
