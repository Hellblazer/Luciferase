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

    // GLSL_RAY_TRAVERSAL / METAL_RAY_TRAVERSAL (and their getGLSLKernel/getMetalKernel accessors) were
    // removed as dead code (Luciferase-2hl2x): they had no callers, and their backing resources
    // (shaders/esvo_ray_traversal.comp, .metal) were loaded only here, so both were deleted. The OpenCL
    // path below is the only live ESVO ray-traversal kernel (see ESVOOpenCLRenderer + benchmarks/validation).

    /**
     * Returns the OpenCL kernel source code for ESVO ray traversal.
     */
    public static String getOpenCLKernel() {
        return OPENCL_RAY_TRAVERSAL;
    }
}