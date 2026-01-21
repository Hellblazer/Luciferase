package com.hellblazer.luciferase.esvo.gpu;

/**
 * DAG (Directed Acyclic Graph) GPU kernel definitions.
 * Loads kernel code from external resource files.
 */
public class DAGKernels {

    /**
     * OpenCL kernel for DAG ray traversal using absolute addressing.
     */
    public static final String OPENCL_RAY_TRAVERSAL = ShaderResourceLoader.loadShader("kernels/dag_ray_traversal.cl");

    /**
     * Returns the OpenCL kernel source code for DAG ray traversal.
     */
    public static String getOpenCLKernel() {
        return OPENCL_RAY_TRAVERSAL;
    }
}
