/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.render.gpu;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Matrix4f;
import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.nio.ByteBuffer;
import java.util.List;

import static org.lwjgl.opengl.GL43.*;

/**
 * GPU renderer using direct OpenGL (no JavaFX).
 * Renders entities as instanced cubes to a framebuffer.
 *
 * Thread safety: Not thread-safe. Use from single rendering thread only.
 */
public class GPURenderer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GPURenderer.class);

    // GPU resources
    private GLProgram meshShader;
    private GLBuffer vertexBuffer;
    private GLBuffer indexBuffer;
    private GLBuffer instanceBuffer;
    private GLFramebuffer framebuffer;
    private Texture2D colorTexture;
    private Texture2D depthTexture;
    private int vao;

    // Render state
    private Camera camera;
    private int width;
    private int height;
    private boolean initialized = false;

    /**
     * Initialize the GPU renderer.
     * Must be called after OpenGL context is created.
     *
     * @param width Render target width
     * @param height Render target height
     * @throws RuntimeException if OpenGL context not available
     */
    public void initialize(int width, int height) {
        if (initialized) {
            throw new IllegalStateException("Already initialized");
        }

        this.width = width;
        this.height = height;

        try {
            // Verify OpenGL is available
            if (GL.getCapabilities() == null) {
                throw new RuntimeException("OpenGL context not available");
            }

            // Create framebuffer
            log.info("Creating framebuffer: {}x{}", width, height);
            framebuffer = new GLFramebuffer(width, height);
            colorTexture = new Texture2D(width, height, GL_RGBA8);
            depthTexture = new Texture2D(width, height, GL_DEPTH24_STENCIL8);
            framebuffer.attach(colorTexture, depthTexture);

            // Load shaders
            log.info("Compiling shaders");
            meshShader = GLProgram.compile("instance.vert", "basic.frag");

            // Create cube geometry
            log.info("Creating cube geometry");
            MeshData cube = MeshData.createCube(1.0f);
            vertexBuffer = GLBuffer.create(cube.vertices(), GL_ARRAY_BUFFER);
            indexBuffer = GLBuffer.create(cube.indices(), GL_ELEMENT_ARRAY_BUFFER);

            // Create vertex array object (VAO)
            log.info("Setting up vertex attributes");
            vao = glGenVertexArrays();
            glBindVertexArray(vao);

            // Vertex attributes
            glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer.handle());
            glEnableVertexAttribArray(0);  // Position
            glVertexAttribPointer(0, 3, GL_FLOAT, false, 24, 0);
            glEnableVertexAttribArray(1);  // Normal
            glVertexAttribPointer(1, 3, GL_FLOAT, false, 24, 12);

            // Index buffer
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indexBuffer.handle());

            glBindVertexArray(0);

            // Create instance buffer (dynamic, max 1000 entities)
            log.info("Creating instance buffer");
            instanceBuffer = GLBuffer.createDynamic(1000 * 64, GL_SHADER_STORAGE_BUFFER);

            // Setup camera
            log.info("Setting up camera");
            camera = new Camera(width, height);
            camera.setPosition(0, 50, 150);  // Bird's eye view
            camera.lookAt(0, 0, 0);

            // OpenGL settings
            glClearColor(0.1f, 0.1f, 0.15f, 1.0f);
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_LEQUAL);
            glCullFace(GL_BACK);
            glEnable(GL_CULL_FACE);

            initialized = true;
            log.info("GPU renderer initialized successfully");

        } catch (Exception e) {
            cleanup();
            throw new RuntimeException("Failed to initialize GPU renderer: " + e.getMessage(), e);
        }
    }

    /**
     * Render a frame with the given entities.
     *
     * @param entities List of entities with positions
     * @throws IllegalStateException if not initialized
     */
    public void renderFrame(List<EntityRecord> entities) {
        if (!initialized) {
            throw new IllegalStateException("Not initialized");
        }

        // Update instance buffer
        updateInstanceBuffer(entities);

        // Bind framebuffer and clear
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer.handle());
        glViewport(0, 0, width, height);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        // Use shader program
        glUseProgram(meshShader.handle());

        // Set view-projection matrix uniform
        Matrix4f viewProj = camera.getViewProj();
        glUniformMatrix4fv(0, false, matrixToBuffer(viewProj));

        // Bind vertex array and storage buffer
        glBindVertexArray(vao);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, instanceBuffer.handle());

        // Draw instanced
        glDrawElementsInstanced(GL_TRIANGLES, 36, GL_UNSIGNED_INT, 0, entities.size());

        // Unbind
        glBindVertexArray(0);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    /**
     * Read rendered frame pixels from GPU.
     *
     * @return Pixel data as RGBA bytes (OpenGL format: bottom-left origin)
     */
    public byte[] readPixels() {
        if (!initialized) {
            throw new IllegalStateException("Not initialized");
        }

        glBindFramebuffer(GL_READ_FRAMEBUFFER, framebuffer.handle());
        glReadBuffer(GL_COLOR_ATTACHMENT0);

        ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4);
        glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, buffer);
        buffer.flip();

        byte[] pixels = new byte[buffer.remaining()];
        buffer.get(pixels);

        glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);

        return pixels;
    }

    /**
     * Get render target width.
     *
     * @return Width
     */
    public int width() {
        return width;
    }

    /**
     * Get render target height.
     *
     * @return Height
     */
    public int height() {
        return height;
    }

    /**
     * Cleanup GPU resources.
     */
    @Override
    public void close() {
        cleanup();
    }

    private void cleanup() {
        if (meshShader != null) {
            meshShader.close();
            meshShader = null;
        }
        if (vertexBuffer != null) {
            vertexBuffer.close();
            vertexBuffer = null;
        }
        if (indexBuffer != null) {
            indexBuffer.close();
            indexBuffer = null;
        }
        if (instanceBuffer != null) {
            instanceBuffer.close();
            instanceBuffer = null;
        }
        if (colorTexture != null) {
            colorTexture.close();
            colorTexture = null;
        }
        if (depthTexture != null) {
            depthTexture.close();
            depthTexture = null;
        }
        if (framebuffer != null) {
            framebuffer.close();
            framebuffer = null;
        }
        if (vao >= 0) {
            glDeleteVertexArrays(vao);
            vao = -1;
        }
        initialized = false;
    }

    /**
     * Update instance buffer with entity transforms.
     *
     * @param entities Entities with positions
     */
    private void updateInstanceBuffer(List<EntityRecord> entities) {
        // Build transformation matrices
        Matrix4f[] transforms = new Matrix4f[entities.size()];
        for (int i = 0; i < entities.size(); i++) {
            EntityRecord entity = entities.get(i);
            transforms[i] = new Matrix4f();
            transforms[i].setIdentity();

            // Set translation
            Point3f pos = entity.position();
            transforms[i].m03 = pos.x;
            transforms[i].m13 = pos.y;
            transforms[i].m23 = pos.z;

            // Could add rotation/scale here later
        }

        // Upload to GPU
        instanceBuffer.update(transforms);
    }

    /**
     * Convert Matrix4f to float buffer for OpenGL.
     *
     * @param m Matrix to convert
     * @return FloatBuffer in column-major order (OpenGL format)
     */
    private static float[] matrixToBuffer(Matrix4f m) {
        return new float[] {
            m.m00, m.m10, m.m20, m.m30,  // Column 0
            m.m01, m.m11, m.m21, m.m31,  // Column 1
            m.m02, m.m12, m.m22, m.m32,  // Column 2
            m.m03, m.m13, m.m23, m.m33   // Column 3
        };
    }

    /**
     * Entity record for rendering.
     */
    public record EntityRecord(String id, Point3f position, Object content) {
    }
}
