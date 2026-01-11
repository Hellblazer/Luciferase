/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.render.gpu;

import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryUtil;

import javax.vecmath.Matrix4f;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL43.*;

/**
 * OpenGL GPU buffer wrapper (vertex, index, instance, or storage buffer).
 * Handles allocation, upload, and cleanup of GPU memory.
 */
public class GLBuffer implements AutoCloseable {

    private final int handle;
    private final long capacity;
    private final int target;
    private final int usage;

    private GLBuffer(int handle, long capacity, int target, int usage) {
        this.handle = handle;
        this.capacity = capacity;
        this.target = target;
        this.usage = usage;
    }

    /**
     * Create a buffer with float data.
     *
     * @param data   Float array to upload
     * @param target GL_ARRAY_BUFFER, GL_ELEMENT_ARRAY_BUFFER, GL_SHADER_STORAGE_BUFFER, etc.
     * @return New GLBuffer
     */
    public static GLBuffer create(float[] data, int target) {
        return create(data, target, GL_STATIC_DRAW);
    }

    /**
     * Create a buffer with int data (for indices).
     *
     * @param data   Integer array to upload
     * @param target GL_ELEMENT_ARRAY_BUFFER or similar
     * @return New GLBuffer
     */
    public static GLBuffer create(int[] data, int target) {
        return create(data, target, GL_STATIC_DRAW);
    }

    /**
     * Create a buffer with dynamic usage (for instance data).
     *
     * @param sizeBytes Size in bytes
     * @param target    GL_ARRAY_BUFFER, GL_SHADER_STORAGE_BUFFER, etc.
     * @return New GLBuffer
     */
    public static GLBuffer createDynamic(long sizeBytes, int target) {
        return create(null, sizeBytes, target, GL_DYNAMIC_DRAW);
    }

    /**
     * Create a buffer with static usage.
     *
     * @param sizeBytes Size in bytes
     * @param target    GL_ARRAY_BUFFER, GL_SHADER_STORAGE_BUFFER, etc.
     * @return New GLBuffer
     */
    public static GLBuffer create(long sizeBytes, int target) {
        return create(null, sizeBytes, target, GL_STATIC_DRAW);
    }

    private static GLBuffer create(float[] data, int target, int usage) {
        int handle = glGenBuffers();
        glBindBuffer(target, handle);
        if (data != null) {
            FloatBuffer fb = MemoryUtil.memAllocFloat(data.length);
            fb.put(data).flip();
            glBufferData(target, fb, usage);
            MemoryUtil.memFree(fb);
            return new GLBuffer(handle, data.length * 4L, target, usage);
        } else {
            return new GLBuffer(handle, 0, target, usage);
        }
    }

    private static GLBuffer create(int[] data, int target, int usage) {
        int handle = glGenBuffers();
        glBindBuffer(target, handle);
        if (data != null) {
            IntBuffer ib = MemoryUtil.memAllocInt(data.length);
            ib.put(data).flip();
            glBufferData(target, ib, usage);
            MemoryUtil.memFree(ib);
            return new GLBuffer(handle, (long) data.length * 4, target, usage);
        } else {
            return new GLBuffer(handle, 0, target, usage);
        }
    }

    private static GLBuffer create(Object data, long sizeBytes, int target, int usage) {
        int handle = glGenBuffers();
        glBindBuffer(target, handle);
        if (data != null) {
            if (data instanceof float[] floats) {
                FloatBuffer fb = MemoryUtil.memAllocFloat(floats.length);
                fb.put(floats).flip();
                glBufferData(target, fb, usage);
                MemoryUtil.memFree(fb);
                return new GLBuffer(handle, (long) floats.length * 4, target, usage);
            } else if (data instanceof int[] ints) {
                IntBuffer ib = MemoryUtil.memAllocInt(ints.length);
                ib.put(ints).flip();
                glBufferData(target, ib, usage);
                MemoryUtil.memFree(ib);
                return new GLBuffer(handle, (long) ints.length * 4, target, usage);
            }
        } else {
            glBufferData(target, sizeBytes, usage);
            return new GLBuffer(handle, sizeBytes, target, usage);
        }
        return null;
    }

    /**
     * Update buffer data.
     *
     * @param data Matrix4f array to upload
     */
    public void update(Matrix4f[] data) {
        FloatBuffer fb = MemoryUtil.memAllocFloat(data.length * 16);
        for (Matrix4f m : data) {
            fb.put(m.m00).put(m.m01).put(m.m02).put(m.m03);
            fb.put(m.m10).put(m.m11).put(m.m12).put(m.m13);
            fb.put(m.m20).put(m.m21).put(m.m22).put(m.m23);
            fb.put(m.m30).put(m.m31).put(m.m32).put(m.m33);
        }
        fb.flip();
        glBindBuffer(target, handle);
        glBufferSubData(target, 0, fb);
        MemoryUtil.memFree(fb);
    }

    /**
     * Update buffer with float data.
     *
     * @param data Float array
     */
    public void update(float[] data) {
        FloatBuffer fb = MemoryUtil.memAllocFloat(data.length);
        fb.put(data).flip();
        glBindBuffer(target, handle);
        glBufferSubData(target, 0, fb);
        MemoryUtil.memFree(fb);
    }

    /**
     * Get the OpenGL buffer handle.
     *
     * @return Handle
     */
    public int handle() {
        return handle;
    }

    /**
     * Get buffer capacity in bytes.
     *
     * @return Capacity
     */
    public long capacity() {
        return capacity;
    }

    /**
     * Cleanup: Delete buffer from GPU.
     */
    @Override
    public void close() {
        glDeleteBuffers(handle);
    }
}
