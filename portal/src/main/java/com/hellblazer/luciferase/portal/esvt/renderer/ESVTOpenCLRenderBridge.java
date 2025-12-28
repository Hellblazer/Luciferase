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
import com.hellblazer.luciferase.esvt.gpu.ESVTOpenCLRenderer;
import javafx.application.Platform;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
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

/**
 * Bridges the OpenCL compute renderer with JavaFX display.
 *
 * <p>Unlike the OpenGL version (ESVTGPURenderBridge), this bridge doesn't need
 * a hidden GLFW window because OpenCL doesn't require an OpenGL context.
 * This makes it simpler and works on macOS where OpenGL 4.3+ is not available.
 *
 * <p>Threading model:
 * <ul>
 *   <li>OpenCL rendering runs on a dedicated thread</li>
 *   <li>Results are transferred to JavaFX via Platform.runLater()</li>
 *   <li>Camera updates can be requested from any thread</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class ESVTOpenCLRenderBridge implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ESVTOpenCLRenderBridge.class);

    // Frame dimensions
    private final int width;
    private final int height;

    // OpenCL renderer
    private ESVTOpenCLRenderer clRenderer;

    // Output image
    private WritableImage outputImage;
    private byte[] pixelArray;

    // Threading
    private final ExecutorService renderThread;
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean disposed = new AtomicBoolean(false);

    // Camera state (updated from any thread, read on render thread)
    private volatile Matrix4f viewMatrix = new Matrix4f();
    private volatile Matrix4f projMatrix = new Matrix4f();
    private volatile Matrix4f objectToWorld = new Matrix4f();
    private volatile Matrix4f tetreeToObject = new Matrix4f();

    /**
     * Check if OpenCL GPU rendering is available on this system.
     *
     * @return true if OpenCL GPU is available
     */
    public static boolean isAvailable() {
        return ESVTOpenCLRenderer.isOpenCLAvailable();
    }

    /**
     * Create an OpenCL render bridge with specified dimensions.
     *
     * @param width  Output image width
     * @param height Output image height
     */
    public ESVTOpenCLRenderBridge(int width, int height) {
        this.width = width;
        this.height = height;

        // Create single-threaded executor for OpenCL operations
        this.renderThread = Executors.newSingleThreadExecutor(r -> {
            var t = new Thread(r, "ESVT-OpenCL-Render");
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

        log.debug("Created ESVTOpenCLRenderBridge: {}x{}", width, height);
    }

    /**
     * Initialize the OpenCL context and renderer.
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
                clRenderer = new ESVTOpenCLRenderer(width, height);
                clRenderer.initialize();
                initialized.set(true);
                log.info("OpenCL render bridge initialized: {}x{}", width, height);
            } catch (Exception e) {
                log.error("Failed to initialize OpenCL render bridge", e);
                throw new RuntimeException("OpenCL initialization failed", e);
            }
        }, renderThread);
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
            clRenderer.uploadData(data);
            log.debug("Uploaded ESVT data to OpenCL: {} nodes", data.nodeCount());
        }, renderThread);
    }

    /**
     * Set camera parameters for rendering.
     * Thread-safe, can be called from any thread.
     *
     * @param cameraPos  Camera position in world space
     * @param lookAt     Point the camera is looking at
     * @param upVector   Up direction
     * @param fovDegrees Field of view in degrees
     * @param nearPlane  Near clipping plane
     * @param farPlane   Far clipping plane
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

        renderThread.execute(() -> {
            try {
                // Capture current camera state
                var view = new Matrix4f(viewMatrix);
                var proj = new Matrix4f(projMatrix);
                var o2w = new Matrix4f(objectToWorld);
                var t2o = new Matrix4f(tetreeToObject);

                // Render using OpenCL
                clRenderer.renderFrame(view, proj, o2w, t2o);

                // Get output pixels
                ByteBuffer pixels = clRenderer.getOutputImage();

                // Copy to byte array for JavaFX
                pixels.rewind();
                pixels.get(pixelArray);

                // Transfer to JavaFX on FX thread
                Platform.runLater(() -> {
                    transferPixelsToImage();
                    callback.accept(outputImage);
                });
            } catch (Exception e) {
                log.error("OpenCL render failed", e);
            }
        });
    }

    /**
     * Render a frame synchronously (blocking).
     * Returns immediately with the last rendered image.
     *
     * @return The rendered image
     */
    public WritableImage renderSync() {
        if (!initialized.get()) {
            throw new IllegalStateException("Bridge not initialized");
        }

        try {
            var future = new CompletableFuture<WritableImage>();
            renderAsync(future::complete);
            return future.get();
        } catch (Exception e) {
            throw new RuntimeException("Render failed", e);
        }
    }

    /**
     * Transfer pixels from render buffer to JavaFX image.
     * Must be called on JavaFX thread.
     */
    private void transferPixelsToImage() {
        // OpenCL output is already in correct orientation (top-to-bottom)
        // Convert RGBA to BGRA for JavaFX
        for (int i = 0; i < pixelArray.length; i += 4) {
            byte r = pixelArray[i];
            byte b = pixelArray[i + 2];
            pixelArray[i] = b;     // B
            pixelArray[i + 2] = r; // R
            // G and A stay in place
        }

        // Write to JavaFX image
        PixelWriter pw = outputImage.getPixelWriter();
        pw.setPixels(0, 0, width, height,
                PixelFormat.getByteBgraInstance(),
                pixelArray, 0, width * 4);
    }

    /**
     * Get the current output image.
     */
    public WritableImage getOutputImage() {
        return outputImage;
    }

    /**
     * Get frame width.
     */
    public int getWidth() {
        return width;
    }

    /**
     * Get frame height.
     */
    public int getHeight() {
        return height;
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

        // Shutdown render thread with cleanup
        renderThread.execute(() -> {
            try {
                if (clRenderer != null) {
                    clRenderer.close();
                    clRenderer = null;
                }
            } catch (Exception e) {
                log.error("Error during OpenCL cleanup", e);
            }
        });

        renderThread.shutdown();
        log.info("ESVTOpenCLRenderBridge disposed");
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
