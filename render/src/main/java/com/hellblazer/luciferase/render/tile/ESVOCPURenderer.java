/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.render.tile;

import com.hellblazer.luciferase.esvo.cpu.ESVOCPUTraversal;
import com.hellblazer.luciferase.esvo.cpu.ESVOCPUTraversal.IntersectionResult;
import com.hellblazer.luciferase.esvo.cpu.ESVOCPUTraversal.OctreeNode;
import com.hellblazer.luciferase.esvo.gpu.beam.Ray;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CPU renderer implementing HybridKernelExecutor using ESVOCPUTraversal.
 *
 * <p>This class bridges the tile-based dispatcher with the CPU traversal
 * algorithm, providing unified ray result handling for hybrid rendering.
 *
 * <p>Key features:
 * <ul>
 *   <li>Converts beam.Ray to ESVOCPUTraversal.Ray format</li>
 *   <li>Manages ray results in thread-safe storage</li>
 *   <li>Tracks CPU execution metrics</li>
 *   <li>Provides GPU saturation estimation</li>
 * </ul>
 *
 * @see HybridKernelExecutor
 * @see ESVOCPUTraversal
 */
public class ESVOCPURenderer implements HybridKernelExecutor {

    private final OctreeNode[] octree;
    private final float[] sceneMin;
    private final float[] sceneMax;
    private final int maxDepth;
    private final Map<Integer, RayResult> results;
    private final AtomicLong cpuTimeNs;
    private final double cpuGpuRatio;

    // For GPU saturation estimation
    private volatile double gpuSaturation = 0.0;

    /**
     * Creates a CPU renderer with the given octree data.
     *
     * @param octree   octree nodes for traversal
     * @param sceneMin scene minimum bounds [x, y, z]
     * @param sceneMax scene maximum bounds [x, y, z]
     * @param maxDepth maximum traversal depth
     */
    public ESVOCPURenderer(OctreeNode[] octree, float[] sceneMin, float[] sceneMax, int maxDepth) {
        this(octree, sceneMin, sceneMax, maxDepth, 2.0);
    }

    /**
     * Creates a CPU renderer with custom CPU/GPU ratio.
     *
     * @param octree      octree nodes for traversal
     * @param sceneMin    scene minimum bounds [x, y, z]
     * @param sceneMax    scene maximum bounds [x, y, z]
     * @param maxDepth    maximum traversal depth
     * @param cpuGpuRatio estimated CPU/GPU cost ratio
     */
    public ESVOCPURenderer(OctreeNode[] octree, float[] sceneMin, float[] sceneMax, int maxDepth, double cpuGpuRatio) {
        if (octree == null || octree.length == 0) {
            throw new IllegalArgumentException("Octree cannot be null or empty");
        }
        if (sceneMin == null || sceneMin.length != 3) {
            throw new IllegalArgumentException("Scene min must be a 3-element array");
        }
        if (sceneMax == null || sceneMax.length != 3) {
            throw new IllegalArgumentException("Scene max must be a 3-element array");
        }
        if (maxDepth < 1) {
            throw new IllegalArgumentException("Max depth must be at least 1");
        }
        if (cpuGpuRatio <= 0) {
            throw new IllegalArgumentException("CPU/GPU ratio must be positive");
        }

        this.octree = octree;
        this.sceneMin = sceneMin.clone();
        this.sceneMax = sceneMax.clone();
        this.maxDepth = maxDepth;
        this.cpuGpuRatio = cpuGpuRatio;
        this.results = new ConcurrentHashMap<>();
        this.cpuTimeNs = new AtomicLong(0);
    }

    @Override
    public void executeBatch(Ray[] rays, int[] rayIndices, int raysPerItem) {
        // CPU doesn't have true batch mode, fall through to sequential
        executeCPU(rays, rayIndices);
    }

    @Override
    public void executeSingleRay(Ray[] rays, int[] rayIndices) {
        // Same as CPU for this renderer
        executeCPU(rays, rayIndices);
    }

    @Override
    public void executeCPU(Ray[] rays, int[] rayIndices) {
        long startTime = System.nanoTime();

        for (int rayIndex : rayIndices) {
            var ray = rays[rayIndex];
            var cpuRay = convertRay(ray);
            var intersection = ESVOCPUTraversal.traverseRay(cpuRay, octree, sceneMin, sceneMax, maxDepth);
            var result = convertResult(intersection, ray);
            results.put(rayIndex, result);
        }

        cpuTimeNs.addAndGet(System.nanoTime() - startTime);
    }

    @Override
    public RayResult getResult(int rayIndex) {
        return results.getOrDefault(rayIndex, RayResult.miss());
    }

    @Override
    public boolean supportsCPU() {
        return true;
    }

    @Override
    public double getCPUGPURatio() {
        return cpuGpuRatio;
    }

    @Override
    public double getGPUSaturation() {
        return gpuSaturation;
    }

    /**
     * Updates the GPU saturation estimate. Called by the dispatcher
     * to inform work distribution decisions.
     *
     * @param saturation GPU saturation level (0.0 to 1.0)
     */
    public void setGPUSaturation(double saturation) {
        this.gpuSaturation = Math.max(0.0, Math.min(1.0, saturation));
    }

    /**
     * Returns total CPU execution time in nanoseconds.
     */
    public long getCPUTimeNs() {
        return cpuTimeNs.get();
    }

    /**
     * Resets CPU time counter.
     */
    public void resetCPUTime() {
        cpuTimeNs.set(0);
    }

    /**
     * Clears all cached ray results.
     */
    public void clearResults() {
        results.clear();
    }

    /**
     * Converts a beam.Ray to ESVOCPUTraversal.Ray format.
     */
    private ESVOCPUTraversal.Ray convertRay(Ray ray) {
        return new ESVOCPUTraversal.Ray(
            ray.origin().x, ray.origin().y, ray.origin().z,
            ray.direction().x, ray.direction().y, ray.direction().z,
            0.0f,  // tMin
            Float.MAX_VALUE  // tMax
        );
    }

    /**
     * Converts an IntersectionResult to RayResult.
     */
    private RayResult convertResult(IntersectionResult intersection, Ray ray) {
        if (intersection.hit == 0) {
            return RayResult.miss();
        }

        // Calculate hit point from ray and distance
        float hitX = ray.origin().x + ray.direction().x * intersection.t;
        float hitY = ray.origin().y + ray.direction().y * intersection.t;
        float hitZ = ray.origin().z + ray.direction().z * intersection.t;

        return new RayResult(hitX, hitY, hitZ, intersection.t);
    }

    /**
     * Creates a CPU renderer from DAG octree data (for testing).
     *
     * @param dagNodes  DAG node descriptors
     * @param sceneMin  scene minimum bounds
     * @param sceneMax  scene maximum bounds
     * @param maxDepth  maximum traversal depth
     * @return configured CPU renderer
     */
    public static ESVOCPURenderer fromDAG(int[] dagNodes, float[] sceneMin, float[] sceneMax, int maxDepth) {
        // Convert DAG nodes to OctreeNode format
        var octree = new OctreeNode[dagNodes.length / 2];
        for (int i = 0; i < octree.length; i++) {
            octree[i] = new OctreeNode(dagNodes[i * 2], dagNodes[i * 2 + 1]);
        }
        return new ESVOCPURenderer(octree, sceneMin, sceneMax, maxDepth);
    }
}
