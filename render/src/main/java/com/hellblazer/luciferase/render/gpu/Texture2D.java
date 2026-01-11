/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.render.gpu;

import static org.lwjgl.opengl.GL43.*;

/**
 * OpenGL 2D texture wrapper.
 */
public class Texture2D implements AutoCloseable {

    private final int handle;
    private final int width;
    private final int height;
    private final int format;

    /**
     * Create a 2D texture.
     *
     * @param width  Width in pixels
     * @param height Height in pixels
     * @param format GL_RGBA8, GL_RGBA16F, GL_DEPTH24_STENCIL8, etc.
     */
    public Texture2D(int width, int height, int format) {
        this.width = width;
        this.height = height;
        this.format = format;

        handle = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, handle);

        // Determine internal format and data format
        int internalFormat = format;
        int dataFormat = GL_RGBA;
        int dataType = GL_UNSIGNED_BYTE;

        if (format == GL_DEPTH24_STENCIL8) {
            dataFormat = GL_DEPTH_STENCIL;
            dataType = GL_UNSIGNED_INT_24_8;
        } else if (format == GL_RGBA16F) {
            dataType = GL_HALF_FLOAT;
        }

        // Allocate texture storage
        glTexImage2D(GL_TEXTURE_2D, 0, internalFormat, width, height, 0, dataFormat, dataType, 0);

        // Set texture parameters
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        glBindTexture(GL_TEXTURE_2D, 0);
    }

    /**
     * Get the OpenGL texture handle.
     *
     * @return Handle
     */
    public int handle() {
        return handle;
    }

    /**
     * Get width in pixels.
     *
     * @return Width
     */
    public int width() {
        return width;
    }

    /**
     * Get height in pixels.
     *
     * @return Height
     */
    public int height() {
        return height;
    }

    /**
     * Get the texture format.
     *
     * @return Format
     */
    public int format() {
        return format;
    }

    /**
     * Bind this texture to a unit.
     *
     * @param unit Texture unit (0-15)
     */
    public void bind(int unit) {
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_2D, handle);
    }

    /**
     * Cleanup: Delete texture from GPU.
     */
    @Override
    public void close() {
        glDeleteTextures(handle);
    }
}
