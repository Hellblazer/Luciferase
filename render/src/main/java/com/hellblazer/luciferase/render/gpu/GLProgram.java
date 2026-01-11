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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.opengl.GL43.*;

/**
 * OpenGL shader program wrapper.
 * Compiles vertex, fragment, and optionally compute shaders.
 */
public class GLProgram implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GLProgram.class);

    private final int handle;

    private GLProgram(int handle) {
        this.handle = handle;
    }

    /**
     * Compile a vertex + fragment shader pair.
     *
     * @param vertexPath   Path to vertex shader (e.g., "instance.vert")
     * @param fragmentPath Path to fragment shader (e.g., "basic.frag")
     * @return Compiled program
     */
    public static GLProgram compile(String vertexPath, String fragmentPath) {
        String vertexSource = loadShader(vertexPath);
        String fragmentSource = loadShader(fragmentPath);

        int vshader = compileShader(vertexSource, GL_VERTEX_SHADER, vertexPath);
        int fshader = compileShader(fragmentSource, GL_FRAGMENT_SHADER, fragmentPath);

        int program = glCreateProgram();
        glAttachShader(program, vshader);
        glAttachShader(program, fshader);
        glLinkProgram(program);

        // Check link status
        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
            String error = glGetProgramInfoLog(program);
            log.error("Program link failed: {}", error);
            throw new RuntimeException("Failed to link program: " + error);
        }

        glDeleteShader(vshader);
        glDeleteShader(fshader);

        log.debug("Compiled shader program: {} + {}", vertexPath, fragmentPath);
        return new GLProgram(program);
    }

    /**
     * Compile a compute shader.
     *
     * @param computePath Path to compute shader (e.g., "frustumCull.glsl")
     * @return Compiled program
     */
    public static GLProgram compileCompute(String computePath) {
        String computeSource = loadShader(computePath);
        int cshader = compileShader(computeSource, GL_COMPUTE_SHADER, computePath);

        int program = glCreateProgram();
        glAttachShader(program, cshader);
        glLinkProgram(program);

        // Check link status
        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
            String error = glGetProgramInfoLog(program);
            log.error("Program link failed: {}", error);
            throw new RuntimeException("Failed to link program: " + error);
        }

        glDeleteShader(cshader);

        log.debug("Compiled compute shader: {}", computePath);
        return new GLProgram(program);
    }

    /**
     * Get the OpenGL program handle.
     *
     * @return Handle
     */
    public int handle() {
        return handle;
    }

    /**
     * Cleanup: Delete program from GPU.
     */
    @Override
    public void close() {
        glDeleteProgram(handle);
    }

    private static int compileShader(String source, int type, String name) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);

        // Check compile status
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            String error = glGetShaderInfoLog(shader);
            log.error("Shader compilation failed ({}): {}", name, error);
            throw new RuntimeException("Failed to compile shader " + name + ": " + error);
        }

        return shader;
    }

    private static String loadShader(String path) {
        try {
            var classLoader = GLProgram.class.getClassLoader();
            var resource = classLoader.getResourceAsStream("shaders/" + path);
            if (resource == null) {
                throw new RuntimeException("Shader not found: " + path);
            }
            return new String(resource.readAllBytes());
        } catch (Exception e) {
            throw new RuntimeException("Failed to load shader: " + path, e);
        }
    }
}
