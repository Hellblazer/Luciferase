/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.esvo.performance;

import com.hellblazer.luciferase.esvt.gpu.ESVTKernels;
import com.hellblazer.luciferase.esvo.gpu.ESVOKernels;
import com.hellblazer.luciferase.gpu.test.support.TestSupportMatrix;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lwjgl.opencl.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.lwjgl.opencl.CL10.*;

/**
 * GPU Performance Comparison: ESVO (Efficient Sparse Voxel Octrees) vs ESVT (Efficient Sparse Voxel Tetrahedra)
 *
 * <p>Benchmarks both algorithms using OpenCL on Apple M-series GPU.
 * Tests ray traversal performance at various scales to compare:
 * <ul>
 *   <li>ESVO: 8-way octree subdivision with contours</li>
 *   <li>ESVT: 8-way Bey tetrahedral subdivision with contours</li>
 * </ul>
 *
 * <p>Run with: {@code RUN_GPU_TESTS=true mvn test -Dtest=ESVOvsESVTGPUBenchmark}
 *
 * @author hal.hildebrand
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "RUN_GPU_TESTS", matches = "true")
public class ESVOvsESVTGPUBenchmark {

    private static final Logger log = LoggerFactory.getLogger(ESVOvsESVTGPUBenchmark.class);

    private static final int[] RAY_COUNTS = {1_000, 10_000, 100_000, 1_000_000};
    private static final int[] TREE_DEPTHS = {4, 6, 8};
    private static final int WARMUP_ITERATIONS = 3;
    private static final int BENCHMARK_ITERATIONS = 10;

    private TestSupportMatrix supportMatrix;
    private long clContext;
    private long clQueue;
    private long clDevice;

    // ESVO resources
    private long esvoProgram;
    private long esvoKernel;

    // ESVT resources
    private long esvtProgram;
    private long esvtKernel;

    private boolean gpuAvailable;

    @BeforeAll
    void setup() {
        supportMatrix = new TestSupportMatrix();
        supportMatrix.printMatrix();
        gpuAvailable = initializeGPU();

        if (!gpuAvailable) {
            log.warn("GPU not available, skipping benchmark");
        }
    }

    @AfterAll
    void cleanup() {
        if (gpuAvailable) {
            if (esvoKernel != 0) clReleaseKernel(esvoKernel);
            if (esvoProgram != 0) clReleaseProgram(esvoProgram);
            if (esvtKernel != 0) clReleaseKernel(esvtKernel);
            if (esvtProgram != 0) clReleaseProgram(esvtProgram);
            if (clQueue != 0) clReleaseCommandQueue(clQueue);
            if (clContext != 0) clReleaseContext(clContext);
        }
    }

    private boolean initializeGPU() {
        try {
            if (supportMatrix.getBackendSupport(TestSupportMatrix.Backend.OPENCL)
                == TestSupportMatrix.SupportLevel.NOT_AVAILABLE) {
                return false;
            }

            try (var stack = MemoryStack.stackPush()) {
                IntBuffer errcode = stack.mallocInt(1);

                // Get platform
                IntBuffer numPlatforms = stack.mallocInt(1);
                clGetPlatformIDs(null, numPlatforms);
                if (numPlatforms.get(0) == 0) {
                    log.warn("No OpenCL platforms found");
                    return false;
                }

                var platforms = stack.mallocPointer(numPlatforms.get(0));
                clGetPlatformIDs(platforms, (IntBuffer) null);
                long platform = platforms.get(0);

                // Get device
                IntBuffer numDevices = stack.mallocInt(1);
                int err = clGetDeviceIDs(platform, CL_DEVICE_TYPE_GPU, null, numDevices);
                if (err != CL_SUCCESS || numDevices.get(0) == 0) {
                    log.warn("No GPU devices found");
                    return false;
                }

                var devices = stack.mallocPointer(1);
                clGetDeviceIDs(platform, CL_DEVICE_TYPE_GPU, devices, (IntBuffer) null);
                clDevice = devices.get(0);

                // Print device info
                printDeviceInfo(clDevice);

                // Create context
                var contextProps = stack.mallocPointer(3);
                contextProps.put(CL_CONTEXT_PLATFORM).put(platform).put(0);
                contextProps.flip();

                clContext = clCreateContext(contextProps, clDevice, null, 0, errcode);
                checkError(errcode.get(0), "clCreateContext");

                // Create queue
                clQueue = clCreateCommandQueue(clContext, clDevice, CL_QUEUE_PROFILING_ENABLE, errcode);
                checkError(errcode.get(0), "clCreateCommandQueue");

                // Compile ESVO kernel
                var esvoSource = ESVOKernels.getOpenCLKernel();
                esvoProgram = compileProgram(clContext, clDevice, esvoSource, "ESVO");
                if (esvoProgram != 0) {
                    esvoKernel = clCreateKernel(esvoProgram, "traverseOctree", errcode);
                    if (errcode.get(0) != CL_SUCCESS) {
                        log.warn("Failed to create ESVO kernel: {}", errcode.get(0));
                        esvoKernel = 0;
                    }
                }

                // Compile ESVT kernel
                var esvtSource = ESVTKernels.getOpenCLKernel();
                esvtProgram = compileProgram(clContext, clDevice, esvtSource, "ESVT");
                if (esvtProgram != 0) {
                    esvtKernel = clCreateKernel(esvtProgram, "traverseESVT", errcode);
                    if (errcode.get(0) != CL_SUCCESS) {
                        log.warn("Failed to create ESVT kernel: {}", errcode.get(0));
                        esvtKernel = 0;
                    }
                }

                return esvoKernel != 0 || esvtKernel != 0;
            }
        } catch (Exception e) {
            log.warn("GPU initialization failed: {}", e.getMessage(), e);
            return false;
        }
    }

    private long compileProgram(long context, long device, String source, String name) {
        try (var stack = MemoryStack.stackPush()) {
            IntBuffer errcode = stack.mallocInt(1);

            long program = clCreateProgramWithSource(context, source, errcode);
            if (errcode.get(0) != CL_SUCCESS) {
                log.warn("{} program creation failed: {}", name, errcode.get(0));
                return 0;
            }

            int buildErr = clBuildProgram(program, device, "", null, 0);
            if (buildErr != CL_SUCCESS) {
                // Get build log
                var logSize = stack.mallocPointer(1);
                clGetProgramBuildInfo(program, device, CL_PROGRAM_BUILD_LOG, (ByteBuffer) null, logSize);

                ByteBuffer logBuffer = stack.malloc((int) logSize.get(0));
                clGetProgramBuildInfo(program, device, CL_PROGRAM_BUILD_LOG, logBuffer, null);

                String buildLog = MemoryUtil.memUTF8(logBuffer);
                log.warn("{} build failed:\n{}", name, buildLog);

                clReleaseProgram(program);
                return 0;
            }

            log.info("{} kernel compiled successfully", name);
            return program;
        }
    }

    private void printDeviceInfo(long device) {
        try (var stack = MemoryStack.stackPush()) {
            var size = stack.mallocPointer(1);

            // Device name
            clGetDeviceInfo(device, CL_DEVICE_NAME, (ByteBuffer) null, size);
            ByteBuffer nameBuffer = stack.malloc((int) size.get(0));
            clGetDeviceInfo(device, CL_DEVICE_NAME, nameBuffer, null);
            log.info("GPU Device: {}", MemoryUtil.memUTF8(nameBuffer));

            // Compute units
            IntBuffer cuBuffer = stack.mallocInt(1);
            clGetDeviceInfo(device, CL_DEVICE_MAX_COMPUTE_UNITS, cuBuffer, null);
            log.info("Compute Units: {}", cuBuffer.get(0));

            // Global memory
            var memBuffer = stack.mallocLong(1);
            clGetDeviceInfo(device, CL_DEVICE_GLOBAL_MEM_SIZE, memBuffer, null);
            log.info("Global Memory: {} MB", memBuffer.get(0) / (1024 * 1024));

            // Max work group size
            clGetDeviceInfo(device, CL_DEVICE_MAX_WORK_GROUP_SIZE, size, null);
            log.info("Max Work Group Size: {}", size.get(0));
        }
    }

    @Test
    @DisplayName("ESVO vs ESVT GPU Performance Comparison")
    void benchmarkESVOvsESVT() {
        Assumptions.assumeTrue(gpuAvailable, "GPU not available");

        System.out.println("\n" + "=".repeat(80));
        System.out.println("ESVO vs ESVT GPU PERFORMANCE COMPARISON");
        System.out.println("=".repeat(80));
        System.out.println();

        var results = new ArrayList<ComparisonResult>();

        for (int depth : TREE_DEPTHS) {
            System.out.println("-".repeat(60));
            System.out.printf("Tree Depth: %d%n", depth);
            System.out.println("-".repeat(60));

            for (int rayCount : RAY_COUNTS) {
                var result = runComparison(rayCount, depth);
                if (result != null) {
                    results.add(result);
                    printResult(result);
                }
            }
            System.out.println();
        }

        // Print summary
        printSummary(results);
    }

    private ComparisonResult runComparison(int rayCount, int depth) {
        try (var stack = MemoryStack.stackPush()) {
            IntBuffer errcode = stack.mallocInt(1);

            // Generate rays
            float[] rayData = generateRays(rayCount);

            // Create ray buffer
            FloatBuffer rayBuffer = MemoryUtil.memAllocFloat(rayData.length);
            rayBuffer.put(rayData).flip();
            long clRayBuffer = clCreateBuffer(clContext, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
                rayBuffer, errcode);
            checkError(errcode.get(0), "create ray buffer");

            // Generate and upload ESVO octree
            int[] octreeData = generateOctree(depth);
            IntBuffer octreeBuffer = MemoryUtil.memAllocInt(octreeData.length);
            octreeBuffer.put(octreeData).flip();
            long clOctreeBuffer = clCreateBuffer(clContext, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
                octreeBuffer, errcode);
            checkError(errcode.get(0), "create octree buffer");

            // Generate and upload ESVT tree
            int[] esvtData = generateESVTTree(depth);
            IntBuffer esvtBuffer = MemoryUtil.memAllocInt(esvtData.length);
            esvtBuffer.put(esvtData).flip();
            long clEsvtBuffer = clCreateBuffer(clContext, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
                esvtBuffer, errcode);
            checkError(errcode.get(0), "create ESVT buffer");

            // Create result buffers
            long clResultBuffer = clCreateBuffer(clContext, CL_MEM_WRITE_ONLY,
                (long) rayCount * 16, errcode); // float4 per ray
            long clNormalBuffer = clCreateBuffer(clContext, CL_MEM_WRITE_ONLY,
                (long) rayCount * 16, errcode); // float4 per ray

            // Contour buffer (empty for benchmark)
            long clContourBuffer = clCreateBuffer(clContext, CL_MEM_READ_ONLY, 4, errcode);

            long esvoTimeNs = 0;
            long esvtTimeNs = 0;

            // Benchmark ESVO
            if (esvoKernel != 0) {
                esvoTimeNs = benchmarkKernel(esvoKernel, clRayBuffer, clOctreeBuffer,
                    clContourBuffer, clResultBuffer, clNormalBuffer, rayCount, depth);
            }

            // Benchmark ESVT
            if (esvtKernel != 0) {
                esvtTimeNs = benchmarkKernel(esvtKernel, clRayBuffer, clEsvtBuffer,
                    clContourBuffer, clResultBuffer, clNormalBuffer, rayCount, depth);
            }

            // Cleanup
            clReleaseMemObject(clRayBuffer);
            clReleaseMemObject(clOctreeBuffer);
            clReleaseMemObject(clEsvtBuffer);
            clReleaseMemObject(clResultBuffer);
            clReleaseMemObject(clNormalBuffer);
            clReleaseMemObject(clContourBuffer);
            MemoryUtil.memFree(rayBuffer);
            MemoryUtil.memFree(octreeBuffer);
            MemoryUtil.memFree(esvtBuffer);

            return new ComparisonResult(rayCount, depth, octreeData.length / 2, esvtData.length / 2,
                esvoTimeNs, esvtTimeNs);

        } catch (Exception e) {
            log.warn("Benchmark failed for rays={}, depth={}: {}", rayCount, depth, e.getMessage());
            return null;
        }
    }

    private long benchmarkKernel(long kernel, long rayBuffer, long treeBuffer,
                                  long contourBuffer, long resultBuffer, long normalBuffer,
                                  int rayCount, int depth) {
        try (var stack = MemoryStack.stackPush()) {
            // Set kernel arguments
            clSetKernelArg1p(kernel, 0, rayBuffer);
            clSetKernelArg1p(kernel, 1, treeBuffer);
            clSetKernelArg1p(kernel, 2, contourBuffer);
            clSetKernelArg1p(kernel, 3, resultBuffer);
            clSetKernelArg1p(kernel, 4, normalBuffer);
            clSetKernelArg1i(kernel, 5, depth);

            var globalSize = stack.mallocPointer(1);
            globalSize.put(0, rayCount);

            // Warmup
            for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                int err = clEnqueueNDRangeKernel(clQueue, kernel, 1, null, globalSize, null, null, null);
                clFinish(clQueue);
            }

            // Benchmark
            long totalTimeNs = 0;
            for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
                long startNs = System.nanoTime();
                int err = clEnqueueNDRangeKernel(clQueue, kernel, 1, null, globalSize, null, null, null);
                clFinish(clQueue);
                long endNs = System.nanoTime();
                totalTimeNs += (endNs - startNs);
            }

            return totalTimeNs / BENCHMARK_ITERATIONS;
        }
    }

    private float[] generateRays(int count) {
        var random = new Random(42);
        float[] rays = new float[count * 8]; // origin(3) + direction(3) + tmin + tmax

        for (int i = 0; i < count; i++) {
            int offset = i * 8;

            // Origin on a sphere around the scene
            float theta = random.nextFloat() * 2 * (float) Math.PI;
            float phi = (float) Math.acos(2 * random.nextFloat() - 1);
            float r = 2.0f;

            rays[offset] = 0.5f + r * (float) (Math.sin(phi) * Math.cos(theta));     // origin.x
            rays[offset + 1] = 0.5f + r * (float) (Math.sin(phi) * Math.sin(theta)); // origin.y
            rays[offset + 2] = 0.5f + r * (float) Math.cos(phi);                     // origin.z

            // Direction toward center with some variation
            float dx = 0.5f - rays[offset] + (random.nextFloat() - 0.5f) * 0.2f;
            float dy = 0.5f - rays[offset + 1] + (random.nextFloat() - 0.5f) * 0.2f;
            float dz = 0.5f - rays[offset + 2] + (random.nextFloat() - 0.5f) * 0.2f;
            float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);

            rays[offset + 3] = dx / len; // direction.x
            rays[offset + 4] = dy / len; // direction.y
            rays[offset + 5] = dz / len; // direction.z
            rays[offset + 6] = 0.0f;     // tmin
            rays[offset + 7] = 10.0f;    // tmax
        }

        return rays;
    }

    private int[] generateOctree(int maxDepth) {
        // Generate a balanced octree with 2 ints per node (childDescriptor, contourDescriptor)
        var nodeList = new ArrayList<int[]>();
        var random = new Random(42);

        // Root node
        nodeList.add(new int[]{0, 0});

        // BFS to build tree
        var queue = new ArrayList<int[]>(); // [nodeIndex, depth]
        queue.add(new int[]{0, 0});

        int maxNodes = 50_000;

        while (!queue.isEmpty() && nodeList.size() < maxNodes) {
            var entry = queue.remove(0);
            int nodeIndex = entry[0];
            int depth = entry[1];

            boolean makeLeaf = depth >= maxDepth ||
                              random.nextFloat() > 0.85 ||
                              nodeList.size() + 8 > maxNodes;

            if (makeLeaf) {
                // Leaf node
                nodeList.get(nodeIndex)[0] = 0x80000000; // valid + leaf
                nodeList.get(nodeIndex)[1] = random.nextInt(255) + 1;
            } else {
                // Internal node with 8 children
                int firstChildIndex = nodeList.size();
                int childPtr = firstChildIndex - nodeIndex;

                nodeList.get(nodeIndex)[0] = 0x80000000 | (childPtr << 17) | (0xFF << 8) | 0x00;
                nodeList.get(nodeIndex)[1] = 0;

                for (int j = 0; j < 8; j++) {
                    nodeList.add(new int[]{0, 0});
                    queue.add(new int[]{firstChildIndex + j, depth + 1});
                }
            }
        }

        // Mark remaining as leaves
        for (var entry : queue) {
            int nodeIndex = entry[0];
            if (nodeIndex < nodeList.size()) {
                nodeList.get(nodeIndex)[0] = 0x80000000;
                nodeList.get(nodeIndex)[1] = random.nextInt(255) + 1;
            }
        }

        // Flatten to int array
        int[] result = new int[nodeList.size() * 2];
        for (int i = 0; i < nodeList.size(); i++) {
            result[i * 2] = nodeList.get(i)[0];
            result[i * 2 + 1] = nodeList.get(i)[1];
        }

        return result;
    }

    private int[] generateESVTTree(int maxDepth) {
        // Generate ESVT tree (same structure as octree but with tet type in contour descriptor)
        var nodeList = new ArrayList<int[]>();
        var random = new Random(42);

        // Root node with type 0
        nodeList.add(new int[]{0x80000000, 0}); // valid, type 0

        var queue = new ArrayList<int[]>(); // [nodeIndex, depth, parentType]
        queue.add(new int[]{0, 0, 0});

        int maxNodes = 50_000;

        while (!queue.isEmpty() && nodeList.size() < maxNodes) {
            var entry = queue.remove(0);
            int nodeIndex = entry[0];
            int depth = entry[1];
            int parentType = entry[2];

            boolean makeLeaf = depth >= maxDepth ||
                              random.nextFloat() > 0.85 ||
                              nodeList.size() + 8 > maxNodes;

            if (makeLeaf) {
                // Leaf node - keep valid flag, set type
                nodeList.get(nodeIndex)[0] = 0x80000000;
                nodeList.get(nodeIndex)[1] = (parentType & 0x7) << 1; // type in bits 1-3
            } else {
                // Internal node with 8 children (Bey subdivision)
                int firstChildIndex = nodeList.size();
                int childPtr = firstChildIndex - nodeIndex;

                // childDescriptor: valid(1) | childptr(14) | far(1) | childmask(8) | leafmask(8)
                nodeList.get(nodeIndex)[0] = 0x80000000 | (childPtr << 17) | (0xFF << 8) | 0x00;
                // contourDescriptor: type in bits 1-3
                nodeList.get(nodeIndex)[1] = (parentType & 0x7) << 1;

                // Child type derivation based on Bey subdivision
                int[] childTypes = getChildTypes(parentType);

                for (int j = 0; j < 8; j++) {
                    nodeList.add(new int[]{0x80000000, (childTypes[j] & 0x7) << 1});
                    queue.add(new int[]{firstChildIndex + j, depth + 1, childTypes[j]});
                }
            }
        }

        // Mark remaining as leaves
        for (var entry : queue) {
            int nodeIndex = entry[0];
            if (nodeIndex < nodeList.size()) {
                nodeList.get(nodeIndex)[0] = 0x80000000;
            }
        }

        // Flatten to int array
        int[] result = new int[nodeList.size() * 2];
        for (int i = 0; i < nodeList.size(); i++) {
            result[i * 2] = nodeList.get(i)[0];
            result[i * 2 + 1] = nodeList.get(i)[1];
        }

        return result;
    }

    private int[] getChildTypes(int parentType) {
        // PARENT_TYPE_TO_CHILD_TYPE from TetreeConnectivity
        return switch (parentType) {
            case 0 -> new int[]{0, 0, 0, 0, 4, 5, 2, 1};
            case 1 -> new int[]{1, 1, 1, 1, 3, 2, 5, 0};
            case 2 -> new int[]{2, 2, 2, 2, 0, 1, 4, 3};
            case 3 -> new int[]{3, 3, 3, 3, 5, 4, 1, 2};
            case 4 -> new int[]{4, 4, 4, 4, 2, 3, 0, 5};
            case 5 -> new int[]{5, 5, 5, 5, 1, 0, 3, 4};
            default -> new int[]{0, 0, 0, 0, 0, 0, 0, 0};
        };
    }

    private void printResult(ComparisonResult result) {
        double esvoRaysPerMs = result.esvoTimeNs > 0 ?
            (result.rayCount * 1_000_000.0) / result.esvoTimeNs : 0;
        double esvtRaysPerMs = result.esvtTimeNs > 0 ?
            (result.rayCount * 1_000_000.0) / result.esvtTimeNs : 0;

        double ratio = result.esvoTimeNs > 0 && result.esvtTimeNs > 0 ?
            (double) result.esvoTimeNs / result.esvtTimeNs : 0;

        System.out.printf("  %,8d rays | ESVO: %,.0f rays/ms (%,d nodes) | ESVT: %,.0f rays/ms (%,d nodes) | Ratio: %.2fx%n",
            result.rayCount,
            esvoRaysPerMs, result.esvoNodes,
            esvtRaysPerMs, result.esvtNodes,
            ratio);
    }

    private void printSummary(ArrayList<ComparisonResult> results) {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("SUMMARY");
        System.out.println("=".repeat(80));

        if (results.isEmpty()) {
            System.out.println("No results to summarize");
            return;
        }

        // Calculate averages
        double avgRatio = 0;
        int validResults = 0;

        for (var result : results) {
            if (result.esvoTimeNs > 0 && result.esvtTimeNs > 0) {
                avgRatio += (double) result.esvoTimeNs / result.esvtTimeNs;
                validResults++;
            }
        }

        if (validResults > 0) {
            avgRatio /= validResults;
        }

        System.out.printf("Average ESVO/ESVT ratio: %.2fx%n", avgRatio);

        if (avgRatio > 1.0) {
            System.out.printf("ESVT is %.1f%% faster than ESVO on GPU%n", (avgRatio - 1) * 100);
        } else if (avgRatio < 1.0) {
            System.out.printf("ESVO is %.1f%% faster than ESVT on GPU%n", (1 - avgRatio) * 100);
        } else {
            System.out.println("ESVO and ESVT have equivalent GPU performance");
        }

        System.out.println();
        System.out.println("Performance Notes:");
        System.out.println("  - ESVT uses Möller-Trumbore ray-triangle for 4 faces per tet");
        System.out.println("  - ESVO uses slab-based AABB intersection");
        System.out.println("  - ESVT handles 6 tetrahedron types vs ESVO single cube type");
        System.out.println("  - Both use 8-byte node format with contour support");
        System.out.println("=".repeat(80));
    }

    private void checkError(int error, String operation) {
        if (error != CL_SUCCESS) {
            throw new RuntimeException(operation + " failed with error: " + error);
        }
    }

    private record ComparisonResult(
        int rayCount,
        int depth,
        int esvoNodes,
        int esvtNodes,
        long esvoTimeNs,
        long esvtTimeNs
    ) {}
}
