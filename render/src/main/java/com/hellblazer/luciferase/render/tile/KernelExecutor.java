package com.hellblazer.luciferase.render.tile;

import com.hellblazer.luciferase.esvo.gpu.beam.Ray;

/**
 * Abstraction for executing ray intersection kernels. Supports both batch and single-ray execution
 * modes for adaptive tile-based rendering strategies.
 */
public interface KernelExecutor {

    /**
     * Executes kernel in batch mode on specified rays. Used for high-coherence tiles
     * where ray behavior is similar.
     *
     * @param rays        Global ray array
     * @param rayIndices  Indices of rays to process
     * @param raysPerItem Number of rays per work item (SIMD factor)
     */
    void executeBatch(Ray[] rays, int[] rayIndices, int raysPerItem);

    /**
     * Executes kernel in single-ray mode. Used for low-coherence tiles where rays
     * diverge significantly.
     *
     * @param rays       Global ray array
     * @param rayIndices Indices of rays to process
     */
    void executeSingleRay(Ray[] rays, int[] rayIndices);

    /**
     * Retrieves the result for a specific ray after kernel execution.
     *
     * @param rayIndex Index of the ray in the global ray array
     * @return RayResult containing intersection data
     */
    RayResult getResult(int rayIndex);
}
