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
package com.hellblazer.luciferase.esvo.gpu;

import com.hellblazer.luciferase.esvo.core.ESVONodeUnified;
import com.hellblazer.luciferase.esvo.dag.DAGOctreeData;
import com.hellblazer.luciferase.esvo.gpu.beam.BeamKernelSelector;
import com.hellblazer.luciferase.esvo.gpu.beam.BeamOptimizationGate;
import com.hellblazer.luciferase.esvo.gpu.beam.BeamTree;
import com.hellblazer.luciferase.esvo.gpu.beam.BeamTreeBuilder;
import com.hellblazer.luciferase.esvo.gpu.beam.Ray;
import com.hellblazer.luciferase.esvo.gpu.beam.RayCoherenceAnalyzer;
import com.hellblazer.luciferase.esvo.gpu.beam.StreamCActivationDecision;
import com.hellblazer.luciferase.resource.compute.ComputeKernel;
import com.hellblazer.luciferase.resource.compute.opencl.OpenCLKernel;
import com.hellblazer.luciferase.sparse.core.CoordinateSpace;
import com.hellblazer.luciferase.sparse.core.PointerAddressingMode;
import com.hellblazer.luciferase.sparse.gpu.AbstractOpenCLRenderer;
import com.hellblazer.luciferase.sparse.gpu.GPUAutoTuner;
import com.hellblazer.luciferase.sparse.gpu.GPUTuningProfileLoader;
import com.hellblazer.luciferase.sparse.gpu.GPUVendor;
import com.hellblazer.luciferase.sparse.gpu.WorkgroupConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.lwjgl.opencl.CL10.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * F3.1: GPU-Accelerated DAG Ray Traversal Renderer
 *
 * Implements ray traversal for Sparse Voxel DAGs using OpenCL.
 * Uses absolute addressing (direct child pointer lookup) for performance.
 *
 * @author hal.hildebrand
 */
public class DAGOpenCLRenderer extends AbstractOpenCLRenderer<ESVONodeUnified, DAGOctreeData> {
    private static final Logger log = LoggerFactory.getLogger(DAGOpenCLRenderer.class);

    // Stream B: GPU Auto-Tuning Infrastructure
    private com.hellblazer.luciferase.sparse.gpu.GPUCapabilities gpuCapabilities;
    private WorkgroupConfig tuningConfig;
    private GPUAutoTuner autoTuner;
    private GPUTuningProfileLoader profileLoader;
    private final String cacheDirectory;

    // Raw cl_mem handles for ByteBuffer upload
    private long clNodeBuffer;
    private long clDummyChildPointersBuffer; // Minimal buffer for absolute addressing (unused in kernel)
    private int nodeCount; // Number of nodes in DAG (for kernel arg2)

    // Scene bounds derived from coordinate space (ESVO uses [0,1] normalized coordinates)
    private static final CoordinateSpace COORD_SPACE = CoordinateSpace.UNIT_CUBE;
    private final float[] sceneMin = {COORD_SPACE.getMin(), COORD_SPACE.getMin(), COORD_SPACE.getMin()};
    private final float[] sceneMax = {COORD_SPACE.getMax(), COORD_SPACE.getMax(), COORD_SPACE.getMax()};

    // Phase 4.2.2b: Batch kernel support
    protected OpenCLKernel batchKernel;
    private volatile boolean useBatchKernel = false;
    private volatile int currentRaysPerItem = 1;

    // Phase 4.2.2c: Coherence analysis infrastructure
    private RayCoherenceAnalyzer coherenceAnalyzer;
    private StreamCActivationDecision activationDecision;
    private DAGOctreeData lastDAGData;
    private int coherenceSampleInterval = 10;
    private int framesSinceCoherenceSample = 0;

    // Phase 5a.2: Beam Tree and Kernel Selection
    private BeamKernelSelector beamKernelSelector;
    private BeamTree beamTree;
    private volatile boolean useBeamOptimization = false;

    /**
     * Create a DAG-aware GPU renderer with specified output dimensions
     */
    public DAGOpenCLRenderer(int width, int height) {
        this(width, height, System.getProperty("user.home") + "/.cache/luciferase/gpu-tuning");
    }

    /**
     * Create renderer with custom cache directory (for testing)
     */
    public DAGOpenCLRenderer(int width, int height, String cacheDirectory) {
        super(width, height);
        this.cacheDirectory = cacheDirectory;
        this.profileLoader = new GPUTuningProfileLoader();
    }

    @Override
    protected String getRendererName() {
        return "DAGOpenCLRenderer";
    }

    @Override
    protected String getKernelSource() {
        return DAGKernels.getOpenCLKernel();
    }

    @Override
    protected String getKernelEntryPoint() {
        return "rayTraverseDAG";  // Primary kernel; batch kernel created separately
    }

    /**
     * Phase 4.2.2b: Hook called after main kernel compilation.
     * Initializes batch kernel for coherence-based switching.
     */
    @Override
    protected void onKernelCompiled() {
        initializeBatchKernel();
    }

    /**
     * Phase 4.2.2b: Initialize batch kernel after main kernel is compiled.
     * Called from onKernelCompiled() hook.
     */
    protected void initializeBatchKernel() {
        if (kernel == null) {
            throw new IllegalStateException("Main kernel must be initialized first");
        }

        try {
            // Create batch kernel from same source with same build options
            // Both kernels come from the same source file (dag_ray_traversal.cl)
            var kernelSource = getKernelSource();
            var buildOptions = getBuildOptions();

            // Create a new kernel instance for batch processing
            batchKernel = com.hellblazer.luciferase.resource.compute.opencl.OpenCLKernel.create("rayTraverseDAGBatch");
            if (buildOptions != null && !buildOptions.isEmpty()) {
                batchKernel.compile(kernelSource, "rayTraverseDAGBatch", buildOptions);
            } else {
                batchKernel.compile(kernelSource, "rayTraverseDAGBatch");
            }
            log.info("Batch kernel compiled successfully");
        } catch (Exception e) {
            log.warn("Failed to compile batch kernel, batch mode disabled", e);
            batchKernel = null;
            useBatchKernel = false;
        }
    }

    /**
     * Phase 4.2.2b: Check if batch kernel is available for dynamic selection.
     * Used for testing and debugging kernel initialization.
     *
     * @return true if batch kernel compiled successfully
     */
    public boolean isBatchKernelAvailable() {
        return batchKernel != null;
    }

    /**
     * Get the main kernel for testing and debugging.
     *
     * @return main ray traversal kernel (never null after initialization)
     */
    public ComputeKernel getKernel() {
        return kernel;
    }

    /**
     * Check if renderer has been initialized and is ready for rendering.
     *
     * @return true if initialize() completed successfully
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Phase 4.2.2c: Initialize coherence analysis infrastructure.
     * Must be called after DAG data is available.
     */
    protected void initializeCoherenceAnalysis() {
        if (coherenceAnalyzer == null) {
            coherenceAnalyzer = new RayCoherenceAnalyzer();
            activationDecision = new StreamCActivationDecision();
            log.info("Coherence analysis infrastructure initialized");
        }
    }

    /**
     * Phase 4.2.2c + Phase 5a.2: Update coherence analysis and kernel selection if needed.
     * Samples every N frames to avoid overhead.
     *
     * Phase 5a.2 enhancement: Builds BeamTree and uses BeamKernelSelector for intelligent
     * kernel choice based on ray coherence and spatial organization.
     */
    private void updateCoherenceIfNeeded() {
        if (coherenceAnalyzer == null || lastDAGData == null || cpuRayBuffer == null) {
            return;
        }

        framesSinceCoherenceSample++;
        if (framesSinceCoherenceSample < coherenceSampleInterval) {
            return;
        }
        framesSinceCoherenceSample = 0;

        // Convert FloatBuffer to ray arrays for both coherence analysis and beam tree
        // Buffer layout: originX, originY, originZ, directionX, directionY, directionZ, tmin, tmax
        cpuRayBuffer.rewind();
        var esvoRays = new com.hellblazer.luciferase.esvo.core.ESVORay[rayCount];
        var beamRays = new Ray[rayCount];

        for (int i = 0; i < rayCount; i++) {
            float originX = cpuRayBuffer.get();
            float originY = cpuRayBuffer.get();
            float originZ = cpuRayBuffer.get();
            float directionX = cpuRayBuffer.get();
            float directionY = cpuRayBuffer.get();
            float directionZ = cpuRayBuffer.get();
            cpuRayBuffer.get(); // skip tmin
            cpuRayBuffer.get(); // skip tmax

            // Create ESVO ray for legacy coherence analysis
            esvoRays[i] = new com.hellblazer.luciferase.esvo.core.ESVORay(
                originX, originY, originZ,
                directionX, directionY, directionZ
            );
            esvoRays[i].prepareForTraversal();

            // Create beam ray for BeamTree (Phase 5a.2)
            beamRays[i] = new Ray(
                new javax.vecmath.Point3f(originX, originY, originZ),
                new javax.vecmath.Vector3f(directionX, directionY, directionZ)
            );
        }

        // Measure coherence of ray batch (existing infrastructure)
        double coherence = coherenceAnalyzer.analyzeCoherence(esvoRays, lastDAGData);

        // Phase 5a.2: Build BeamTree and use BeamKernelSelector for kernel decision
        try {
            beamTree = BeamTreeBuilder.from(beamRays)
                    .withCoherenceThreshold(0.3)
                    .withMaxBatchSize(currentRaysPerItem > 0 ? currentRaysPerItem : 16)
                    .build();

            if (beamKernelSelector == null) {
                beamKernelSelector = new BeamKernelSelector();
            }

            var kernelChoice = beamKernelSelector.selectKernel(beamTree);
            useBatchKernel = (kernelChoice == BeamKernelSelector.KernelChoice.BATCH);
            useBeamOptimization = useBatchKernel;

            // Calculate optimal rays per item based on coherence
            if (useBatchKernel) {
                currentRaysPerItem = calculateRaysPerItem(coherence);
            } else {
                currentRaysPerItem = 1; // Single-ray mode
            }

            log.debug("Coherence + BeamTree analysis: score={:.3f}, kernel={}, raysPerItem={}, beams={}",
                    coherence, useBatchKernel ? "BATCH" : "SINGLE_RAY", currentRaysPerItem,
                    beamTree.getStatistics().totalBeams());

        } catch (Exception e) {
            log.warn("BeamTree construction failed, falling back to threshold-based selection", e);
            // Fallback to simple threshold-based selection
            useBatchKernel = (coherence >= 0.5);
            if (useBatchKernel) {
                currentRaysPerItem = calculateRaysPerItem(coherence);
            } else {
                currentRaysPerItem = 1;
            }
            useBeamOptimization = false;
        }
    }

    /**
     * Phase 5a.2: Get current BeamTree for testing and metrics collection.
     *
     * @return current BeamTree or null if not yet built
     */
    public BeamTree getBeamTree() {
        return beamTree;
    }

    /**
     * Phase 5a.2: Get kernel selection metrics.
     *
     * @return selection metrics or null if selector not initialized
     */
    public BeamKernelSelector.SelectionMetrics getBeamKernelMetrics() {
        return beamKernelSelector != null ? beamKernelSelector.getMetrics() : null;
    }

    /**
     * Phase 5a.2: Check if beam optimization is enabled.
     *
     * @return true if BeamTree optimization is active
     */
    public boolean isBeamOptimizationEnabled() {
        return useBeamOptimization;
    }

    /**
     * Phase 4.2.2c: Calculate optimal raysPerItem from coherence score.
     * Linear scaling: 0.5 coherence -> 8 rays/item, 1.0 coherence -> 16 rays/item
     */
    private int calculateRaysPerItem(double coherenceScore) {
        return Math.max(1, Math.min(16, (int) Math.ceil(coherenceScore * 16.0)));
    }

    @Override
    protected boolean hasDataUploaded() {
        return clNodeBuffer != 0;
    }

    @Override
    protected void allocateTypeSpecificBuffers() {
        // Initialize empty buffers
        clNodeBuffer = 0;
        clDummyChildPointersBuffer = 0;
    }

    @Override
    protected void uploadDataBuffers(DAGOctreeData data) {
        // Validate absolute addressing
        if (data.getAddressingMode() != PointerAddressingMode.ABSOLUTE) {
            throw new IllegalArgumentException("DAG must use absolute addressing");
        }

        // Release old buffers if exist
        if (clNodeBuffer != 0) {
            clReleaseMemObject(clNodeBuffer);
        }
        if (clDummyChildPointersBuffer != 0) {
            clReleaseMemObject(clDummyChildPointersBuffer);
        }

        // Store node count for kernel arguments (Phase 4.2.2a)
        this.nodeCount = data.nodeCount();

        // Store DAG data for coherence analysis (Phase 4.2.2c)
        this.lastDAGData = data;

        // Convert DAG data to ByteBuffer
        var nodeData = dagToByteBuffer(data);

        // Create buffer with raw OpenCL API for ByteBuffer compatibility
        clNodeBuffer = createRawBuffer(nodeData, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR);

        // Phase 4.2.2a: Create minimal dummy buffer for childPointers parameter
        // For absolute addressing, childPointers is not actually used by the kernel
        // (child pointers come from node.childDescriptor), but the kernel signature
        // expects it. This minimal 1-uint buffer satisfies the parameter requirement.
        var dummyPointerData = memAlloc(4);
        dummyPointerData.putInt(0);
        dummyPointerData.flip();
        clDummyChildPointersBuffer = createRawBuffer(dummyPointerData, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR);
    }

    /**
     * Convert DAGOctreeData to ByteBuffer for GPU upload.
     * Each node is 8 bytes (2 ints: childDescriptor + attributes).
     */
    private ByteBuffer dagToByteBuffer(DAGOctreeData data) {
        int nodeCount = data.nodeCount();
        var buffer = memAlloc(nodeCount * ESVONodeUnified.SIZE_BYTES);
        buffer.order(ByteOrder.nativeOrder());

        // Get all nodes from DAG
        var nodes = data.nodes();

        for (var node : nodes) {
            if (node != null) {
                buffer.putInt(node.getChildDescriptor());
                buffer.putInt(node.getContourDescriptor());
            } else {
                // Empty node
                buffer.putInt(0);
                buffer.putInt(0);
            }
        }

        buffer.flip();
        return buffer;
    }

    @Override
    protected void executeKernel() throws ComputeKernel.KernelExecutionException {
        // Phase 4.2.2a: Fix kernel argument alignment - override to set correct order
        // Kernel expects: nodePool, childPointers, nodeCount, rays, rayCount, results[, raysPerItem for batch]

        // Phase 4.2.2c: Initialize and update coherence analysis
        if (coherenceAnalyzer == null && batchKernel != null) {
            initializeCoherenceAnalysis();
        }
        updateCoherenceIfNeeded();

        var activeKernel = useBatchKernel ? batchKernel : kernel;
        if (activeKernel == null) {
            throw new IllegalStateException("Kernel not initialized");
        }

        // Phase 4.2.2a: Set kernel arguments in correct order for dag_ray_traversal.cl
        // Use temporary kernel swap to set arguments on both kernels with same interface
        var originalKernel = kernel;
        try {
            // Temporarily swap kernel to set arguments on the active one
            kernel = activeKernel;

            // Set common arguments for both kernels
            setRawBufferArg(0, clNodeBuffer);                      // arg0: nodePool
            setRawBufferArg(1, clDummyChildPointersBuffer);        // arg1: childPointers (minimal dummy for absolute addressing)
            activeKernel.setIntArg(2, nodeCount);                  // arg2: nodeCount
            activeKernel.setBufferArg(3, rayBuffer, ComputeKernel.BufferAccess.READ);  // arg3: rays
            activeKernel.setIntArg(4, rayCount);                   // arg4: rayCount

            if (useBatchKernel) {
                // Batch kernel: raysPerItem then results
                activeKernel.setIntArg(5, currentRaysPerItem);     // arg5: raysPerItem
                activeKernel.setBufferArg(6, resultBuffer, ComputeKernel.BufferAccess.WRITE); // arg6: results

                // Adjust global work size for batch kernel
                int workItems = (rayCount + currentRaysPerItem - 1) / currentRaysPerItem;
                long adjustedGlobal = ((workItems + LOCAL_WORK_SIZE - 1) / LOCAL_WORK_SIZE) * LOCAL_WORK_SIZE;
                activeKernel.execute((int) adjustedGlobal, 1, 1, LOCAL_WORK_SIZE, 1, 1);
            } else {
                // Single-ray kernel
                activeKernel.setBufferArg(5, resultBuffer, ComputeKernel.BufferAccess.WRITE); // arg5: results

                long adjustedGlobal = ((rayCount + LOCAL_WORK_SIZE - 1) / LOCAL_WORK_SIZE) * LOCAL_WORK_SIZE;
                activeKernel.execute((int) adjustedGlobal, 1, 1, LOCAL_WORK_SIZE, 1, 1);
            }

            activeKernel.finish();
        } finally {
            // Restore original kernel
            kernel = originalKernel;
        }
    }

    @Override
    protected void setKernelArguments() {
        // Phase 4.2.2a: Arguments now set in executeKernel() - this hook is unused
        // but must remain for compatibility with AbstractOpenCLRenderer contract
    }

    @Override
    protected void readTypeSpecificResults() {
        // DAG traversal produces same result format as ESVO
        // No additional per-pixel data to read
    }

    @Override
    protected int computePixelColor(float hitX, float hitY, float hitZ, float distance, float[] extraData) {
        // Depth-based coloring similar to ESVO
        if (distance < 0.0f || distance > 1000.0f) {
            // Miss: return black with semi-transparent
            return 0x00000080;
        }

        // Normalize distance to [0, 1] range
        float normalizedDist = Math.min(1.0f, distance / 100.0f);

        // Color based on depth: closer = brighter
        float brightness = 1.0f - normalizedDist;
        int colorValue = (int) (brightness * 255);
        colorValue = Math.max(0, Math.min(255, colorValue));

        // Return as RGBA (R, G, B, A)
        return (colorValue << 24) | (colorValue << 16) | (colorValue << 8) | 0xFF;
    }

    @Override
    protected void disposeTypeSpecificBuffers() {
        if (clNodeBuffer != 0) {
            clReleaseMemObject(clNodeBuffer);
            clNodeBuffer = 0;
        }

        // Phase 4.2.2a: Release dummy childPointers buffer
        if (clDummyChildPointersBuffer != 0) {
            clReleaseMemObject(clDummyChildPointersBuffer);
            clDummyChildPointersBuffer = 0;
        }

        // Phase 4.2.2b: Dispose batch kernel
        if (batchKernel != null) {
            try {
                batchKernel.close();
            } catch (Exception e) {
                log.error("Error closing batch kernel", e);
            }
            batchKernel = null;
        }
    }

    /**
     * Build OpenCL options for DAG traversal kernel.
     *
     * Returns preprocessor defines and compiler flags optimized for DAG absolute addressing:
     * - DAG_TRAVERSAL=1 (enables DAG-specific code paths in kernel)
     * - ABSOLUTE_ADDRESSING=1 (uses childPtr directly without parent offset)
     * - MAX_DEPTH=maxDepth (configures stack depth)
     * - Workgroup size from tuning config if available
     * - Vendor-specific compiler flags (e.g., -D__CUDA_ARCH__ for NVIDIA)
     *
     * @return OpenCL build options string for DAG-optimized kernel
     */
    public String buildOptionsForDAGTraversal() {
        var options = new StringBuilder();

        // DAG-specific defines
        options.append("-DDAG_TRAVERSAL=1 ");
        options.append("-DABSOLUTE_ADDRESSING=1 ");
        options.append("-DMAX_DEPTH=").append(maxDepth).append(" ");

        // Add workgroup size from tuning config if available
        if (tuningConfig != null) {
            options.append("-DWORKGROUP_SIZE=").append(tuningConfig.workgroupSize()).append(" ");
        }

        // Add vendor-specific compiler flags
        if (gpuCapabilities != null) {
            switch (gpuCapabilities.vendor()) {
                case NVIDIA:
                    options.append("-D__CUDA_ARCH__=700 ");
                    options.append("-cl-mad-enable ");
                    break;
                case AMD:
                    options.append("-D__GCN__ ");
                    options.append("-cl-fast-relaxed-math ");
                    break;
                case INTEL:
                    options.append("-cl-fast-relaxed-math ");
                    break;
                default:
                    break;
            }
        }

        return options.toString().trim();
    }

    @Override
    protected String getBuildOptions() {
        return buildOptionsForDAGTraversal();
    }

    /**
     * Stream B Phase 8: Optimize renderer for detected GPU device
     *
     * Called during initialization to:
     * 1. Detect GPU capabilities
     * 2. Load or generate optimal tuning configuration
     * 3. Log tuning metrics
     * 4. Recompile kernel with GPU-optimized build options
     */
    public void optimizeForDevice() {
        // Detect GPU capabilities (placeholder - would use OpenCL device queries)
        gpuCapabilities = detectGPUCapabilities();
        log.info("Detected GPU: {} {}", gpuCapabilities.vendor().getDisplayName(), gpuCapabilities.model());

        // Try to load cached configuration first
        autoTuner = new GPUAutoTuner(gpuCapabilities, cacheDirectory);
        var cachedConfig = autoTuner.loadFromCache();

        if (cachedConfig.isPresent()) {
            tuningConfig = cachedConfig.get();
            log.info("Loaded tuning from cache: {}", tuningConfig.notes());
        } else {
            // Try to load from predefined profiles
            var profileConfig = profileLoader.loadProfileForDevice(gpuCapabilities);

            if (profileConfig.isPresent()) {
                tuningConfig = profileConfig.get();
                log.info("Loaded tuning from profile: {}", tuningConfig.notes());

                // Cache the profile for future use
                autoTuner.cacheConfiguration(tuningConfig);
            } else {
                // Generate configuration using occupancy calculator
                tuningConfig = autoTuner.selectOptimalConfigFromProfiles();
                log.info("Auto-tuned configuration: {}", tuningConfig.notes());

                // Cache for future use
                autoTuner.cacheConfiguration(tuningConfig);
            }
        }

        // Log tuning metrics for monitoring
        logTuningMetrics();

        // NOTE: Kernel recompilation with build options would happen here
        // Example: recompileKernelWithParameters(tuningConfig);
        // Requires gpu-support framework enhancement
    }

    /**
     * Detect GPU capabilities from OpenCL device
     *
     * This is a simplified implementation. Production code would query:
     * - CL_DEVICE_VENDOR
     * - CL_DEVICE_NAME
     * - CL_DEVICE_COMPUTE_UNITS
     * - CL_DEVICE_LOCAL_MEM_SIZE
     * - CL_DEVICE_MAX_WORK_GROUP_SIZE
     */
    private com.hellblazer.luciferase.sparse.gpu.GPUCapabilities detectGPUCapabilities() {
        // Placeholder: would use context.getDeviceInfo() from gpu-support
        // For now, return default NVIDIA configuration for demonstration
        return new com.hellblazer.luciferase.sparse.gpu.GPUCapabilities(
            32,      // compute units (placeholder)
            65536,   // local memory bytes
            65536,   // max registers
            GPUVendor.NVIDIA,
            "Generic GPU",
            32       // wavefront size
        );
    }

    /**
     * Log tuning metrics for monitoring and debugging
     */
    private void logTuningMetrics() {
        if (tuningConfig == null) {
            log.warn("No tuning configuration available");
            return;
        }

        log.info("GPU Workgroup Tuning Metrics:");
        log.info("  Workgroup Size: {}", tuningConfig.workgroupSize());
        log.info("  Max Traversal Depth: {}", tuningConfig.maxTraversalDepth());
        log.info("  Expected Occupancy: {}%", String.format("%.1f", tuningConfig.expectedOccupancy() * 100));
        log.info("  Expected Throughput: {} rays/μs", String.format("%.2f", tuningConfig.expectedThroughput()));
        log.info("  LDS Usage: {} bytes", tuningConfig.calculateLdsUsage());
    }

    /**
     * Get current tuning configuration (for testing)
     */
    public WorkgroupConfig getTuningConfig() {
        return tuningConfig;
    }

    /**
     * Get GPU capabilities (for testing)
     */
    public com.hellblazer.luciferase.sparse.gpu.GPUCapabilities getGPUCapabilities() {
        return gpuCapabilities;
    }
}
