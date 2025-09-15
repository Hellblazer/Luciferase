package com.hellblazer.luciferase.esvo.gpu;

/**
 * ESVO (Efficient Sparse Voxel Octrees) GPU kernel definitions.
 * Loads shader and kernel code from external resource files.
 */
public class ESVOKernels {
    
    /**
     * OpenCL kernel for ESVO ray traversal through sparse voxel octrees.
     * Based on the Laine & Karras 2010 algorithm.
     */
    public static final String OPENCL_RAY_TRAVERSAL = ShaderResourceLoader.loadShader("kernels/esvo_ray_traversal.cl");
    
    /**
     * GLSL compute shader for ESVO ray traversal.
     */
    public static final String GLSL_RAY_TRAVERSAL = ShaderResourceLoader.loadShader("shaders/esvo_ray_traversal.comp");
    
    /**
     * Metal shader for ESVO ray traversal (Metal Shading Language).
     */
    public static final String METAL_RAY_TRAVERSAL = ShaderResourceLoader.loadShader("shaders/esvo_ray_traversal.metal");
    
    /**
     * Returns the OpenCL kernel source code for ESVO ray traversal.
     */
    public static String getOpenCLKernel() {
        return OPENCL_RAY_TRAVERSAL;
    }
    
    /**
     * Returns the GLSL compute shader source code for ESVO ray traversal.
     */
    public static String getGLSLKernel() {
        return GLSL_RAY_TRAVERSAL;
    }
    
    /**
     * Returns the Metal compute shader source code for ESVO ray traversal.
     */
    public static String getMetalKernel() {
        return METAL_RAY_TRAVERSAL;
    }
}