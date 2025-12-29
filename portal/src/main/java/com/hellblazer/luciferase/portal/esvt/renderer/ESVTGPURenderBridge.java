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
package com.hellblazer.luciferase.portal.esvt.renderer;

import com.hellblazer.luciferase.esvt.core.ESVTData;
import com.hellblazer.luciferase.esvt.gpu.ESVTComputeRenderer;
import com.hellblazer.luciferase.esvt.gpu.ESVTGPUMemory;
import javafx.application.Platform;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Matrix4f;
import javax.vecmath.Vector3f;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.system.MemoryUtil.memAlloc;
import static org.lwjgl.system.MemoryUtil.memFree;

/**
 * Bridges the GPU compute renderer with JavaFX display.
 *
 * <p>Creates a hidden GLFW window for OpenGL context, renders ESVT data using
 * the compute shader, and transfers the result to a JavaFX WritableImage for display.
 *
 * <p>Threading model:
 * <ul>
 *   <li>GPU rendering runs on a dedicated OpenGL context thread</li>
 *   <li>Results are transferred to JavaFX via Platform.runLater()</li>
 *   <li>Camera updates can be requested from any thread</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class ESVTGPURenderBridge implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ESVTGPURenderBridge.class);

    // Frame dimensions
    private final int width;
    private final int height;

    // OpenGL resources (managed on GL thread)
    private long glfwWindow = NULL;
    private ESVTComputeRenderer gpuRenderer;
    private ESVTGPUMemory gpuMemory;

    // Pixel transfer buffers
    private ByteBuffer pixelBuffer;
    private WritableImage outputImage;
    private byte[] pixelArray;

    // Threading
    private final ExecutorService glThread;
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean disposed = new AtomicBoolean(false);

    // Camera state (updated from any thread, read on GL thread)
    private volatile Matrix4f viewMatrix = new Matrix4f();
    private volatile Matrix4f projMatrix = new Matrix4f();
    private volatile Matrix4f objectToWorld = new Matrix4f();
    private volatile Matrix4f tetreeToObject = new Matrix4f();

    /**
     * Create a GPU render bridge with specified dimensions.
     *
     * @param width Output image width
     * @param height Output image height
     */
    public ESVTGPURenderBridge(int width, int height) {
        this.width = width;
        this.height = height;

        // Create single-threaded executor for OpenGL operations
        this.glThread = Executors.newSingleThreadExecutor(r -> {
            var t = new Thread(r, "ESVT-GPU-Render");
            t.setDaemon(true);
            return t;
        });

        // Initialize identity matrices
        viewMatrix.setIdentity();
        projMatrix.setIdentity();
        objectToWorld.setIdentity();
        tetreeToObject.setIdentity();

        // Create output image on JavaFX thread
        outputImage = new WritableImage(width, height);
        pixelArray = new byte[width * height * 4]; // RGBA

        log.debug("Created ESVTGPURenderBridge: {}x{}", width, height);
    }

    /**
     * Initialize the GPU context and renderer.
     * Must be called before rendering.
     *
     * @return CompletableFuture that completes when initialization is done
     */
    public CompletableFuture<Void> initialize() {
        if (initialized.get()) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            try {
                initializeGLContext();
                initialized.set(true);
                log.info("GPU render bridge initialized: {}x{}", width, height);
            } catch (Exception e) {
                log.error("Failed to initialize GPU render bridge", e);
                throw new RuntimeException("GPU initialization failed", e);
            }
        }, glThread);
    }

    /**
     * Initialize GLFW and OpenGL context.
     */
    private void initializeGLContext() {
        // Set up error callback
        GLFWErrorCallback.createPrint(System.err).set();

        // Initialize GLFW
        if (!glfwInit()) {
            throw new RuntimeException("Failed to initialize GLFW");
        }

        // Configure window hints for offscreen rendering
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE); // Hidden window
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 5);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);

        // Create hidden window
        glfwWindow = glfwCreateWindow(width, height, "ESVT GPU Render", NULL, NULL);
        if (glfwWindow == NULL) {
            throw new RuntimeException("Failed to create GLFW window");
        }

        // Make context current on this thread
        glfwMakeContextCurrent(glfwWindow);

        // Create OpenGL capabilities
        GL.createCapabilities();

        // Log OpenGL info
        log.info("OpenGL: {} - {}", glGetString(GL_VENDOR), glGetString(GL_VERSION));

        // Allocate pixel transfer buffer
        pixelBuffer = memAlloc(width * height * 4); // RGBA

        // Create compute renderer
        gpuRenderer = new ESVTComputeRenderer(width, height);
        gpuRenderer.initialize();
    }

    /**
     * Upload ESVT data to GPU.
     *
     * @param data The ESVT data to upload
     * @return CompletableFuture that completes when upload is done
     */
    public CompletableFuture<Void> uploadData(ESVTData data) {
        if (!initialized.get()) {
            throw new IllegalStateException("Bridge not initialized");
        }

        return CompletableFuture.runAsync(() -> {
            // Dispose old memory if exists
            if (gpuMemory != null) {
                gpuMemory.dispose();
            }

            // Create new GPU memory from data
            gpuMemory = new ESVTGPUMemory(data);
            gpuMemory.uploadToGPU();

            log.debug("Uploaded ESVT data to GPU: {} nodes", data.nodeCount());
        }, glThread);
    }

    /**
     * Set camera parameters for rendering.
     * Thread-safe, can be called from any thread.
     *
     * @param cameraPos Camera position in world space
     * @param lookAt Point the camera is looking at
     * @param upVector Up direction
     * @param fovDegrees Field of view in degrees
     * @param nearPlane Near clipping plane
     * @param farPlane Far clipping plane
     */
    public void setCamera(Vector3f cameraPos, Vector3f lookAt, Vector3f upVector,
                          float fovDegrees, float nearPlane, float farPlane) {
        // Create view matrix (look-at)
        var view = createLookAtMatrix(cameraPos, lookAt, upVector);

        // Create projection matrix
        float aspect = (float) width / height;
        var proj = createPerspectiveMatrix(fovDegrees, aspect, nearPlane, farPlane);

        this.viewMatrix = view;
        this.projMatrix = proj;
    }

    /**
     * Set camera matrices directly.
     * Thread-safe, can be called from any thread.
     */
    public void setCameraMatrices(Matrix4f view, Matrix4f proj) {
        this.viewMatrix = new Matrix4f(view);
        this.projMatrix = new Matrix4f(proj);
    }

    /**
     * Set world transform matrices.
     * Thread-safe, can be called from any thread.
     */
    public void setTransforms(Matrix4f objToWorld, Matrix4f tetToObj) {
        this.objectToWorld = new Matrix4f(objToWorld);
        this.tetreeToObject = new Matrix4f(tetToObj);
    }

    /**
     * Render a frame and call the callback with the result on the JavaFX thread.
     *
     * @param callback Called with the rendered image on JavaFX thread
     */
    public void renderAsync(Consumer<WritableImage> callback) {
        if (!initialized.get()) {
            throw new IllegalStateException("Bridge not initialized");
        }

        if (gpuMemory == null) {
            throw new IllegalStateException("No ESVT data uploaded");
        }

        glThread.execute(() -> {
            try {
                renderFrame();

                // Transfer pixels to JavaFX on FX thread
                Platform.runLater(() -> {
                    transferPixelsToImage();
                    callback.accept(outputImage);
                });
            } catch (Exception e) {
                log.error("GPU render failed", e);
            }
        });
    }

    /**
     * Render a frame synchronously and return the image.
     * Must be called from the GL thread.
     */
    private void renderFrame() {
        // Capture current camera state
        var view = new Matrix4f(viewMatrix);
        var proj = new Matrix4f(projMatrix);
        var o2w = new Matrix4f(objectToWorld);
        var t2o = new Matrix4f(tetreeToObject);

        // Render using compute shader
        gpuRenderer.renderFrame(gpuMemory, view, proj, o2w, t2o);

        // Read pixels from output texture
        int textureId = gpuRenderer.getOutputTexture();
        glBindTexture(GL_TEXTURE_2D, textureId);
        pixelBuffer.clear();
        glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixelBuffer);
        pixelBuffer.rewind();
    }

    /**
     * Transfer pixels from GPU buffer to JavaFX image.
     * Must be called on JavaFX thread.
     */
    private void transferPixelsToImage() {
        // Copy to byte array (flip Y because OpenGL has origin at bottom-left)
        pixelBuffer.rewind();
        for (int y = 0; y < height; y++) {
            int srcRow = (height - 1 - y) * width * 4;
            int dstRow = y * width * 4;
            pixelBuffer.position(srcRow);
            pixelBuffer.get(pixelArray, dstRow, width * 4);
        }

        // Write to JavaFX image
        PixelWriter pw = outputImage.getPixelWriter();
        pw.setPixels(0, 0, width, height,
                    PixelFormat.getByteBgraInstance(),
                    convertRGBAtoBGRA(pixelArray),
                    0, width * 4);
    }

    /**
     * Convert RGBA to BGRA (JavaFX expects BGRA on most platforms).
     */
    private byte[] convertRGBAtoBGRA(byte[] rgba) {
        for (int i = 0; i < rgba.length; i += 4) {
            byte r = rgba[i];
            byte b = rgba[i + 2];
            rgba[i] = b;     // B
            rgba[i + 2] = r; // R
            // G and A stay in place
        }
        return rgba;
    }

    /**
     * Get the current output image.
     */
    public WritableImage getOutputImage() {
        return outputImage;
    }

    /**
     * Check if the bridge is initialized.
     */
    public boolean isInitialized() {
        return initialized.get();
    }

    /**
     * Check if the bridge is disposed.
     */
    public boolean isDisposed() {
        return disposed.get();
    }

    @Override
    public void close() {
        if (disposed.getAndSet(true)) {
            return;
        }

        // Shutdown GL thread with cleanup
        glThread.execute(() -> {
            try {
                if (gpuRenderer != null) {
                    gpuRenderer.dispose();
                    gpuRenderer = null;
                }

                if (gpuMemory != null) {
                    gpuMemory.dispose();
                    gpuMemory = null;
                }

                if (pixelBuffer != null) {
                    memFree(pixelBuffer);
                    pixelBuffer = null;
                }

                if (glfwWindow != NULL) {
                    glfwDestroyWindow(glfwWindow);
                    glfwWindow = NULL;
                }

                glfwTerminate();
                var cb = glfwSetErrorCallback(null);
                if (cb != null) {
                    cb.free();
                }
            } catch (Exception e) {
                log.error("Error during GPU cleanup", e);
            }
        });

        glThread.shutdown();
        log.info("ESVTGPURenderBridge disposed");
    }

    // === Matrix utility methods ===

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
