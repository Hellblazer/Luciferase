package com.hellblazer.luciferase.resource.opengl;

import com.hellblazer.luciferase.resource.GPUResource;

/**
 * Base interface for OpenGL-specific GPU resources.
 * Extends GPUResource with OpenGL-specific functionality.
 */
public interface OpenGLResource extends GPUResource {
    
    /**
     * Get the OpenGL object ID (buffer, texture, shader, program, etc.)
     * @return OpenGL object ID, or 0 if not allocated
     */
    int getOpenGLId();
    
    /**
     * Check if the OpenGL resource is currently bound
     * @return true if resource is bound to its target
     */
    boolean isBound();
    
    /**
     * Bind this resource to its OpenGL target
     * Implementation depends on resource type (buffer, texture, etc.)
     */
    void bind();
    
    /**
     * Unbind this resource from its OpenGL target
     */
    void unbind();
    
    /**
     * Get the OpenGL target constant (GL_ARRAY_BUFFER, GL_TEXTURE_2D, etc.)
     * @return OpenGL target constant
     */
    int getOpenGLTarget();
    
    /**
     * Default implementation of isClosed() for OpenGL resources
     */
    default boolean isClosed() {
        return !isValid();
    }
}