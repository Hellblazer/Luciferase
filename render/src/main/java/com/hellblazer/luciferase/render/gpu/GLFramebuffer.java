/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.render.gpu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.opengl.GL43.*;

/**
 * OpenGL framebuffer object (FBO) wrapper.
 * Attaches color and depth textures for offscreen rendering.
 */
public class GLFramebuffer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GLFramebuffer.class);

    private final int handle;
    private final int width;
    private final int height;

    /**
     * Create a framebuffer with specified dimensions.
     *
     * @param width  Width in pixels
     * @param height Height in pixels
     */
    public GLFramebuffer(int width, int height) {
        this.width = width;
        this.height = height;

        handle = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, handle);

        // Check status (will be incomplete until we attach textures)
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    /**
     * Attach color and depth textures to this framebuffer.
     *
     * @param color Color texture (GL_RGBA8, GL_RGBA16F, etc.)
     * @param depth Depth texture (GL_DEPTH24_STENCIL8, GL_DEPTH32F, etc.)
     */
    public void attach(Texture2D color, Texture2D depth) {
        glBindFramebuffer(GL_FRAMEBUFFER, handle);

        // Attach color texture
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, color.handle(), 0);

        // Attach depth texture
        if (depth.format() == GL_DEPTH24_STENCIL8) {
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_TEXTURE_2D, depth.handle(), 0);
        } else {
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depth.handle(), 0);
        }

        // Check status
        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            String statusMsg = switch (status) {
                case GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT -> "INCOMPLETE_ATTACHMENT";
                case GL_FRAMEBUFFER_INCOMPLETE_MISSING_ATTACHMENT -> "INCOMPLETE_MISSING_ATTACHMENT";
                case GL_FRAMEBUFFER_INCOMPLETE_DRAW_BUFFER -> "INCOMPLETE_DRAW_BUFFER";
                case GL_FRAMEBUFFER_INCOMPLETE_READ_BUFFER -> "INCOMPLETE_READ_BUFFER";
                case GL_FRAMEBUFFER_INCOMPLETE_MULTISAMPLE -> "INCOMPLETE_MULTISAMPLE";
                case GL_FRAMEBUFFER_INCOMPLETE_LAYER_TARGETS -> "INCOMPLETE_LAYER_TARGETS";
                case GL_FRAMEBUFFER_UNSUPPORTED -> "UNSUPPORTED";
                default -> "UNKNOWN (" + status + ")";
            };
            throw new RuntimeException("Framebuffer incomplete: " + statusMsg);
        }

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        log.debug("Framebuffer created: {}x{}", width, height);
    }

    /**
     * Get the OpenGL framebuffer handle.
     *
     * @return Handle
     */
    public int handle() {
        return handle;
    }

    /**
     * Get framebuffer width.
     *
     * @return Width
     */
    public int width() {
        return width;
    }

    /**
     * Get framebuffer height.
     *
     * @return Height
     */
    public int height() {
        return height;
    }

    /**
     * Cleanup: Delete framebuffer from GPU.
     */
    @Override
    public void close() {
        glDeleteFramebuffers(handle);
    }
}
