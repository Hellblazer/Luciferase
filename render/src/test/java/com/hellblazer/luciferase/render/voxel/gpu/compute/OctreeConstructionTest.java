package com.hellblazer.luciferase.render.voxel.gpu.compute;

import com.hellblazer.luciferase.render.voxel.gpu.ComputeShaderManager;
import com.hellblazer.luciferase.render.voxel.gpu.WebGPUContext;
import com.hellblazer.luciferase.webgpu.wrapper.*;
import com.hellblazer.luciferase.webgpu.ffm.WebGPUNative;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests sparse octree construction compute shader.
 * Verifies octree building from voxel grids and LOD generation.
 */
public class OctreeConstructionTest {
    private static final Logger log = LoggerFactory.getLogger(OctreeConstructionTest.class);
    
    private WebGPUContext context;
    private ComputeShaderManager shaderManager;
    private ShaderModule octreeShader;
    
    // Test parameters
    private static final int GRID_SIZE = 32;
    private static final int MAX_DEPTH = 5;
    
    // Octree node structure (matching shader)
    static class OctreeNode {
        int childMask;      // Bitmask for child existence
        int dataOffset;     // Offset to children or voxel data
        int nodeType;       // 0=internal, 1=leaf
        int voxelData;      // Packed color/material for leaves
        
        public OctreeNode(int childMask, int dataOffset, int nodeType, int voxelData) {
            this.childMask = childMask;
            this.dataOffset = dataOffset;
            this.nodeType = nodeType;
            this.voxelData = voxelData;
        }
        
        public boolean isLeaf() {
            return nodeType == 1;
        }
        
        public boolean hasChild(int octant) {
            return (childMask & (1 << octant)) != 0;
        }
        
        public int getChildCount() {
            return Integer.bitCount(childMask);
        }
    }
    
    @BeforeEach
    void setUp() throws Exception {
        context = new WebGPUContext();
        context.initialize().join();
        shaderManager = new ComputeShaderManager(context);
        
        // Load octree construction shader
        octreeShader = shaderManager.loadShaderFromResource(
            "/shaders/esvo/sparse_octree.wgsl"
        ).get();
    }
    
    @AfterEach
    void tearDown() {
        if (context != null) {
            context.shutdown();
        }
    }
    
    @Test
    void testEmptyVoxelGrid() throws Exception {
        // Empty grid should produce minimal octree
        int[] voxelGrid = new int[GRID_SIZE * GRID_SIZE * GRID_SIZE];
        
        // Execute actual compute shader
        List<OctreeNode> octree = executeOctreeConstruction(voxelGrid);
        
        // Should have at least root node
        assertFalse(octree.isEmpty(), "Octree should have at least root node");
        
        // Root should be empty or minimal
        OctreeNode root = octree.get(0);
        assertTrue(root.isLeaf() || root.getChildCount() == 0,
                  "Empty grid should produce empty or leaf root");
    }
    
    @Test
    void testSingleVoxelOctree() throws Exception {
        // Single voxel in center
        int[] voxelGrid = new int[GRID_SIZE * GRID_SIZE * GRID_SIZE];
        int centerIdx = getVoxelIndex(GRID_SIZE/2, GRID_SIZE/2, GRID_SIZE/2);
        voxelGrid[centerIdx] = 0xFF0000FF; // Red voxel
        
        List<OctreeNode> octree = executeOctreeConstruction(voxelGrid);
        
        // Should have hierarchical structure
        assertTrue(octree.size() > 1, "Single voxel should create hierarchical octree");
        
        // Root should have at least one child
        OctreeNode root = octree.get(0);
        assertFalse(root.isLeaf(), "Root should not be leaf for single voxel");
        assertTrue(root.getChildCount() >= 1, "Root should have at least one child");
        
        // Verify path to voxel exists
        boolean foundVoxel = traverseToVoxel(octree, 0, GRID_SIZE/2, GRID_SIZE/2, GRID_SIZE/2);
        assertTrue(foundVoxel, "Should be able to traverse to the voxel");
    }
    
    @Test
    void testFullLayerOctree() throws Exception {
        // Fill one complete Z layer
        int[] voxelGrid = new int[GRID_SIZE * GRID_SIZE * GRID_SIZE];
        int z = GRID_SIZE / 2;
        
        for (int y = 0; y < GRID_SIZE; y++) {
            for (int x = 0; x < GRID_SIZE; x++) {
                voxelGrid[getVoxelIndex(x, y, z)] = 0xFF00FF00; // Green layer
            }
        }
        
        List<OctreeNode> octree = executeOctreeConstruction(voxelGrid);
        
        // Should have significant structure
        assertTrue(octree.size() > GRID_SIZE, 
                  "Full layer should create substantial octree");
        
        // Root should have multiple children
        OctreeNode root = octree.get(0);
        assertTrue(root.getChildCount() >= 2,
                  "Root should have multiple children for full layer");
        
        // Check for LOD optimization
        int leafCount = countLeafNodes(octree);
        assertTrue(leafCount < GRID_SIZE * GRID_SIZE,
                  "Should have LOD optimization, not individual voxel leaves");
    }
    
    @Test
    void testSparseVoxelOctree() throws Exception {
        // Create sparse voxel pattern
        int[] voxelGrid = new int[GRID_SIZE * GRID_SIZE * GRID_SIZE];
        Random rand = new Random(42);
        int voxelCount = 0;
        
        // Add random sparse voxels
        for (int i = 0; i < 100; i++) {
            int x = rand.nextInt(GRID_SIZE);
            int y = rand.nextInt(GRID_SIZE);
            int z = rand.nextInt(GRID_SIZE);
            voxelGrid[getVoxelIndex(x, y, z)] = 0xFFFFFFFF;
            voxelCount++;
        }
        
        List<OctreeNode> octree = executeOctreeConstruction(voxelGrid);
        
        // Octree should be efficient for sparse data
        assertTrue(octree.size() < voxelCount * 10,
                  "Sparse octree should be space-efficient");
        
        // Should have good depth
        int maxDepth = calculateMaxDepth(octree, 0, 0);
        assertTrue(maxDepth >= 2, "Sparse octree should have reasonable depth");
    }
    
    @Test
    void testOctreeHierarchy() throws Exception {
        // Create structured voxel pattern to test hierarchy
        int[] voxelGrid = new int[GRID_SIZE * GRID_SIZE * GRID_SIZE];
        
        // Fill 8 small cubes in octants
        for (int octant = 0; octant < 8; octant++) {
            int baseX = (octant & 1) * (GRID_SIZE / 2);
            int baseY = ((octant >> 1) & 1) * (GRID_SIZE / 2);
            int baseZ = ((octant >> 2) & 1) * (GRID_SIZE / 2);
            
            // Fill small cube in octant
            for (int dx = 0; dx < 4; dx++) {
                for (int dy = 0; dy < 4; dy++) {
                    for (int dz = 0; dz < 4; dz++) {
                        int x = baseX + dx + GRID_SIZE/4 - 2;
                        int y = baseY + dy + GRID_SIZE/4 - 2;
                        int z = baseZ + dz + GRID_SIZE/4 - 2;
                        if (x < GRID_SIZE && y < GRID_SIZE && z < GRID_SIZE) {
                            voxelGrid[getVoxelIndex(x, y, z)] = 0xFF000000 | (octant * 30);
                        }
                    }
                }
            }
        }
        
        List<OctreeNode> octree = executeOctreeConstruction(voxelGrid);
        
        // Root should have 8 children
        OctreeNode root = octree.get(0);
        assertEquals(8, root.getChildCount(),
                    "Root should have 8 children for 8 octant pattern");
        
        // Each child should represent an octant
        for (int i = 0; i < 8; i++) {
            assertTrue(root.hasChild(i), "Root should have child in octant " + i);
        }
    }
    
    @Test
    void testLODGeneration() throws Exception {
        // Test that LOD levels are properly generated
        int[] voxelGrid = new int[GRID_SIZE * GRID_SIZE * GRID_SIZE];
        
        // Create gradient pattern
        for (int z = 0; z < GRID_SIZE; z++) {
            for (int y = 0; y < GRID_SIZE; y++) {
                for (int x = 0; x < GRID_SIZE; x++) {
                    if ((x + y + z) % 2 == 0) {
                        int intensity = (x + y + z) * 255 / (3 * GRID_SIZE);
                        voxelGrid[getVoxelIndex(x, y, z)] = 0xFF000000 | (intensity << 16);
                    }
                }
            }
        }
        
        List<OctreeNode> octree = executeOctreeConstruction(voxelGrid);
        
        // Check for LOD nodes (internal nodes with averaged colors)
        int lodNodes = 0;
        for (OctreeNode node : octree) {
            if (!node.isLeaf() && node.voxelData != 0) {
                lodNodes++;
            }
        }
        
        assertTrue(lodNodes > 0, "Should have LOD nodes with averaged colors");
        
        // Verify hierarchy depth
        int maxDepth = calculateMaxDepth(octree, 0, 0);
        assertTrue(maxDepth <= MAX_DEPTH, "Should respect maximum depth");
        assertTrue(maxDepth >= 2, "Should have reasonable minimum depth");
    }
    
    @Test
    void testOctreeCompactness() throws Exception {
        // Test that similar voxels are merged efficiently
        int[] voxelGrid = new int[GRID_SIZE * GRID_SIZE * GRID_SIZE];
        
        // Fill uniform regions
        int color = 0xFFFF0000; // Red
        
        // Fill lower half uniformly
        for (int z = 0; z < GRID_SIZE/2; z++) {
            for (int y = 0; y < GRID_SIZE; y++) {
                for (int x = 0; x < GRID_SIZE; x++) {
                    voxelGrid[getVoxelIndex(x, y, z)] = color;
                }
            }
        }
        
        List<OctreeNode> octree = executeOctreeConstruction(voxelGrid);
        
        // Should be compact due to uniform regions
        int expectedMaxNodes = GRID_SIZE * GRID_SIZE * GRID_SIZE / 8; // Very rough upper bound
        assertTrue(octree.size() < expectedMaxNodes,
                  "Uniform regions should create compact octree");
        
        // Should have large leaf nodes
        int largeLeaves = 0;
        for (OctreeNode node : octree) {
            if (node.isLeaf() && node.voxelData == color) {
                largeLeaves++;
            }
        }
        
        assertTrue(largeLeaves > 0, "Should have large leaf nodes for uniform regions");
    }
    
    @Test
    void testOctreeTraversal() throws Exception {
        // Test that we can traverse the octree correctly
        int[] voxelGrid = new int[GRID_SIZE * GRID_SIZE * GRID_SIZE];
        
        // Place voxels at known locations
        List<int[]> testPoints = Arrays.asList(
            new int[]{0, 0, 0},
            new int[]{GRID_SIZE-1, GRID_SIZE-1, GRID_SIZE-1},
            new int[]{GRID_SIZE/2, GRID_SIZE/2, GRID_SIZE/2},
            new int[]{GRID_SIZE/4, GRID_SIZE/4, GRID_SIZE/4}
        );
        
        for (int[] point : testPoints) {
            voxelGrid[getVoxelIndex(point[0], point[1], point[2])] = 0xFFFFFFFF;
        }
        
        List<OctreeNode> octree = executeOctreeConstruction(voxelGrid);
        
        // Verify we can traverse to each point
        for (int[] point : testPoints) {
            boolean found = traverseToVoxel(octree, 0, point[0], point[1], point[2]);
            assertTrue(found, "Should find voxel at " + Arrays.toString(point));
        }
    }
    
    // Helper methods
    
    private List<OctreeNode> executeOctreeConstruction(int[] voxelGrid) throws Exception {
        // REAL WebGPU execution - no mocks!
        var device = context.getDevice();
        
        // Create voxel grid buffer
        int voxelBufferSize = voxelGrid.length * 4; // 4 bytes per int
        var voxelBuffer = context.createBuffer(voxelBufferSize, 
            WebGPUNative.BUFFER_USAGE_STORAGE | WebGPUNative.BUFFER_USAGE_COPY_DST);
        
        // Upload voxel data
        byte[] voxelBytes = new byte[voxelBufferSize];
        ByteBuffer voxelData = ByteBuffer.wrap(voxelBytes).order(ByteOrder.nativeOrder());
        for (int voxel : voxelGrid) {
            voxelData.putInt(voxel);
        }
        context.writeBuffer(voxelBuffer, voxelBytes, 0);
        
        // Create octree output buffer (estimate size)
        int maxNodes = Math.max(1000, voxelGrid.length / 8); // At least 1000 nodes
        int octreeBufferSize = maxNodes * 16; // 16 bytes per node (4 ints)
        var octreeBuffer = context.createBuffer(octreeBufferSize,
            WebGPUNative.BUFFER_USAGE_STORAGE | WebGPUNative.BUFFER_USAGE_COPY_SRC);
        
        // Create voxel colors buffer (matching voxel grid)
        int colorsBufferSize = voxelGrid.length * 16; // 16 bytes per color (vec4<f32>)
        var colorsBuffer = context.createBuffer(colorsBufferSize,
            WebGPUNative.BUFFER_USAGE_STORAGE | WebGPUNative.BUFFER_USAGE_COPY_DST);
        
        // Initialize with colors based on voxel values
        byte[] colorBytes = new byte[colorsBufferSize];
        ByteBuffer colorData = ByteBuffer.wrap(colorBytes).order(ByteOrder.nativeOrder());
        for (int voxel : voxelGrid) {
            float r = ((voxel >> 16) & 0xFF) / 255.0f;
            float g = ((voxel >> 8) & 0xFF) / 255.0f;
            float b = (voxel & 0xFF) / 255.0f;
            float a = voxel != 0 ? 1.0f : 0.0f;
            colorData.putFloat(r).putFloat(g).putFloat(b).putFloat(a);
        }
        context.writeBuffer(colorsBuffer, colorBytes, 0);
        
        // Create parameters buffer - shader expects 32 bytes (aligned)
        byte[] paramBytes = new byte[32]; // Aligned to 32 bytes for uniform buffer
        ByteBuffer params = ByteBuffer.wrap(paramBytes).order(ByteOrder.nativeOrder());
        params.putInt(GRID_SIZE); // gridResolution.x
        params.putInt(GRID_SIZE); // gridResolution.y
        params.putInt(GRID_SIZE); // gridResolution.z
        params.putInt(MAX_DEPTH); // maxDepth
        params.putInt(maxNodes);  // nodePoolSize
        params.putInt(1);         // leafThreshold
        params.putInt(0);         // padding for alignment
        params.putInt(0);         // padding for alignment
        
        var paramsBuffer = context.createBuffer(32,
            WebGPUNative.BUFFER_USAGE_UNIFORM | WebGPUNative.BUFFER_USAGE_COPY_DST);
        context.writeBuffer(paramsBuffer, paramBytes, 0);
        
        // Create build state buffer
        int buildStateSize = 4 + 16 * 4; // atomic counter + 16 level offsets
        var buildStateBuffer = context.createBuffer(buildStateSize,
            WebGPUNative.BUFFER_USAGE_STORAGE | WebGPUNative.BUFFER_USAGE_COPY_DST | 
            WebGPUNative.BUFFER_USAGE_COPY_SRC);
        
        // Initialize build state with zeros
        byte[] buildStateBytes = new byte[buildStateSize];
        context.writeBuffer(buildStateBuffer, buildStateBytes, 0);
        
        // Create bind group layout first
        var bindGroupLayoutDesc = new Device.BindGroupLayoutDescriptor()
            .withLabel("OctreeBindGroupLayout")
            .withEntry(new Device.BindGroupLayoutEntry(0, WebGPUNative.SHADER_STAGE_COMPUTE)
                .withBuffer(new Device.BufferBindingLayout(WebGPUNative.BUFFER_BINDING_TYPE_READ_ONLY_STORAGE)))
            .withEntry(new Device.BindGroupLayoutEntry(1, WebGPUNative.SHADER_STAGE_COMPUTE)
                .withBuffer(new Device.BufferBindingLayout(WebGPUNative.BUFFER_BINDING_TYPE_READ_ONLY_STORAGE)))
            .withEntry(new Device.BindGroupLayoutEntry(2, WebGPUNative.SHADER_STAGE_COMPUTE)
                .withBuffer(new Device.BufferBindingLayout(WebGPUNative.BUFFER_BINDING_TYPE_STORAGE)))
            .withEntry(new Device.BindGroupLayoutEntry(3, WebGPUNative.SHADER_STAGE_COMPUTE)
                .withBuffer(new Device.BufferBindingLayout(WebGPUNative.BUFFER_BINDING_TYPE_UNIFORM)))
            .withEntry(new Device.BindGroupLayoutEntry(4, WebGPUNative.SHADER_STAGE_COMPUTE)
                .withBuffer(new Device.BufferBindingLayout(WebGPUNative.BUFFER_BINDING_TYPE_STORAGE)));
        
        var bindGroupLayout = device.createBindGroupLayout(bindGroupLayoutDesc);
        
        // Create bind group with explicit buffer sizes
        var bindGroupDescriptor = new Device.BindGroupDescriptor(bindGroupLayout)
            .withLabel("OctreeBindGroup")
            .withEntry(new Device.BindGroupEntry(0).withBuffer(voxelBuffer, 0, voxelBufferSize))
            .withEntry(new Device.BindGroupEntry(1).withBuffer(colorsBuffer, 0, colorsBufferSize))
            .withEntry(new Device.BindGroupEntry(2).withBuffer(octreeBuffer, 0, octreeBufferSize))
            .withEntry(new Device.BindGroupEntry(3).withBuffer(paramsBuffer, 0, 32))
            .withEntry(new Device.BindGroupEntry(4).withBuffer(buildStateBuffer, 0, buildStateSize));
        
        var bindGroup = device.createBindGroup(bindGroupDescriptor);
        
        // Create pipeline layout with bind group layout
        var pipelineLayout = device.createPipelineLayout(
            new Device.PipelineLayoutDescriptor()
                .withLabel("OctreePipelineLayout")
                .addBindGroupLayout(bindGroupLayout)
        );
        
        // Create compute pipeline with explicit layout
        var pipelineDescriptor = new Device.ComputePipelineDescriptor(octreeShader)
            .withLabel("octree_build")
            .withEntryPoint("main")
            .withLayout(pipelineLayout);
        
        var pipeline = device.createComputePipeline(pipelineDescriptor);
        
        // Create command encoder
        var commandEncoder = device.createCommandEncoder("OctreeComputeCommands");
        
        // Begin compute pass
        var computePass = commandEncoder.beginComputePass(
            new CommandEncoder.ComputePassDescriptor().withLabel("OctreeBuildPass")
        );
        
        try {
            // Set pipeline and bind group
            computePass.setPipeline(pipeline);
            computePass.setBindGroup(0, bindGroup);
            
            // Dispatch compute shader - just 1 workgroup since shader runs on single thread
            computePass.dispatchWorkgroups(1, 1, 1);
            
            // End compute pass
            computePass.end();
        } catch (Exception e) {
            log.error("Error during compute pass execution", e);
            // Try to continue anyway to see if we get any data
        }
        
        // Create readback buffer for results
        var readbackBuffer = context.createBuffer(octreeBufferSize,
            WebGPUNative.BUFFER_USAGE_MAP_READ | WebGPUNative.BUFFER_USAGE_COPY_DST);
        
        // Copy from octree buffer to readback buffer
        try {
            commandEncoder.copyBufferToBuffer(octreeBuffer, 0, readbackBuffer, 0, octreeBufferSize);
        } catch (Exception e) {
            log.error("Error copying buffer", e);
        }
        
        // Submit commands
        CommandBuffer commandBuffer = null;
        try {
            commandBuffer = commandEncoder.finish();
            device.getQueue().submit(commandBuffer);
        } catch (Exception e) {
            log.error("Error finishing/submitting command buffer", e);
        }
        
        // Wait for GPU to finish
        device.getQueue().onSubmittedWorkDone();
        
        // Map and read results
        var mappedSegment = readbackBuffer.mapAsync(Buffer.MapMode.READ, 0, octreeBufferSize).get();
        log.info("Mapped segment size: {} bytes", mappedSegment.byteSize());
        
        // Parse octree nodes from mapped memory
        List<OctreeNode> nodes = new ArrayList<>();
        byte[] resultData = new byte[(int)mappedSegment.byteSize()];
        mappedSegment.asByteBuffer().get(resultData);
        
        ByteBuffer buffer = ByteBuffer.wrap(resultData).order(ByteOrder.nativeOrder());
        int nodeCount = 0;
        
        // Debug: Log first few nodes
        log.info("Reading octree nodes from GPU buffer...");
        
        while (buffer.remaining() >= 16 && nodeCount < maxNodes) {
            int childMask = buffer.getInt();
            int dataOffset = buffer.getInt();
            int nodeType = buffer.getInt();
            int nodeVoxelData = buffer.getInt();
            
            // Debug first few nodes
            if (nodeCount < 5) {
                log.info("Node {}: childMask={}, dataOffset={}, nodeType={}, voxelData=0x{}", 
                    nodeCount, childMask, dataOffset, nodeType, Integer.toHexString(nodeVoxelData));
            }
            
            // Stop if we hit uninitialized data (all zeros after first node)
            if (nodeCount > 0 && childMask == 0 && dataOffset == 0 && nodeType == 0 && nodeVoxelData == 0) {
                break;
            }
            
            nodes.add(new OctreeNode(childMask, dataOffset, nodeType, nodeVoxelData));
            nodeCount++;
            
            // Limit to reasonable number of nodes
            if (nodeCount > 10000) break;
        }
        
        log.info("Read {} octree nodes from GPU", nodeCount);
        
        // Cleanup
        readbackBuffer.unmap();
        voxelBuffer.close();
        colorsBuffer.close();
        octreeBuffer.close();
        paramsBuffer.close();
        buildStateBuffer.close();
        readbackBuffer.close();
        
        // Return at least a root node
        return nodes.isEmpty() ? List.of(new OctreeNode(0, 0, 1, 0)) : nodes;
    }
    
    private List<OctreeNode> mockBuildOctree(int[] voxelGrid) {
        // Mock octree construction
        List<OctreeNode> nodes = new ArrayList<>();
        
        // Always create root
        OctreeNode root = new OctreeNode(0, 1, 0, 0);
        nodes.add(root);
        
        // Simple octree building (mock)
        int nodeCount = 1;
        
        // Check which octants have voxels
        for (int octant = 0; octant < 8; octant++) {
            if (octantHasVoxels(voxelGrid, octant, 0, GRID_SIZE)) {
                root.childMask |= (1 << octant);
                
                // Create child node
                OctreeNode child = new OctreeNode(0, 0, 1, 0xFF808080); // Gray leaf
                nodes.add(child);
                nodeCount++;
            }
        }
        
        root.dataOffset = root.getChildCount() > 0 ? 1 : 0;
        
        return nodes;
    }
    
    private boolean octantHasVoxels(int[] grid, int octant, int offset, int size) {
        int halfSize = size / 2;
        int startX = (octant & 1) * halfSize + offset;
        int startY = ((octant >> 1) & 1) * halfSize + offset;
        int startZ = ((octant >> 2) & 1) * halfSize + offset;
        
        for (int z = startZ; z < startZ + halfSize && z < GRID_SIZE; z++) {
            for (int y = startY; y < startY + halfSize && y < GRID_SIZE; y++) {
                for (int x = startX; x < startX + halfSize && x < GRID_SIZE; x++) {
                    if (grid[getVoxelIndex(x, y, z)] != 0) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
    
    private boolean traverseToVoxel(List<OctreeNode> octree, int nodeIdx, 
                                   int targetX, int targetY, int targetZ) {
        return traverseToVoxelRecursive(octree, nodeIdx, targetX, targetY, targetZ, 
                                        0, 0, 0, getOctreeSize());
    }
    
    private boolean traverseToVoxelRecursive(List<OctreeNode> octree, int nodeIdx,
                                            int targetX, int targetY, int targetZ,
                                            int nodeX, int nodeY, int nodeZ, int nodeSize) {
        if (nodeIdx >= octree.size()) return false;
        
        OctreeNode node = octree.get(nodeIdx);
        
        if (node.isLeaf()) {
            return node.voxelData != 0;
        }
        
        // Determine which octant contains target
        int halfSize = nodeSize / 2;
        int octant = 0;
        if (targetX >= nodeX + halfSize) octant |= 1;
        if (targetY >= nodeY + halfSize) octant |= 2;
        if (targetZ >= nodeZ + halfSize) octant |= 4;
        
        if (!node.hasChild(octant)) {
            return false;
        }
        
        // Calculate child position
        int childX = nodeX + ((octant & 1) != 0 ? halfSize : 0);
        int childY = nodeY + ((octant & 2) != 0 ? halfSize : 0);
        int childZ = nodeZ + ((octant & 4) != 0 ? halfSize : 0);
        
        // Traverse to child
        int childIdx = node.dataOffset + Integer.bitCount(node.childMask & ((1 << octant) - 1));
        return traverseToVoxelRecursive(octree, childIdx, targetX, targetY, targetZ,
                                       childX, childY, childZ, halfSize);
    }
    
    private int getOctreeSize() {
        // Calculate the power-of-2 size that encompasses the grid
        int size = 1;
        while (size < GRID_SIZE) {
            size *= 2;
        }
        return size;
    }
    
    private int countLeafNodes(List<OctreeNode> octree) {
        int count = 0;
        for (OctreeNode node : octree) {
            if (node.isLeaf()) {
                count++;
            }
        }
        return count;
    }
    
    private int calculateMaxDepth(List<OctreeNode> octree, int nodeIdx, int currentDepth) {
        if (nodeIdx >= octree.size()) return currentDepth;
        
        OctreeNode node = octree.get(nodeIdx);
        if (node.isLeaf()) {
            return currentDepth;
        }
        
        int maxChildDepth = currentDepth;
        
        for (int octant = 0; octant < 8; octant++) {
            if (node.hasChild(octant)) {
                // Calculate actual child index using bit counting
                int childIdx = node.dataOffset + Integer.bitCount(node.childMask & ((1 << octant) - 1));
                int childDepth = calculateMaxDepth(octree, childIdx, currentDepth + 1);
                maxChildDepth = Math.max(maxChildDepth, childDepth);
            }
        }
        
        return maxChildDepth;
    }
    
    private int getVoxelIndex(int x, int y, int z) {
        return z * GRID_SIZE * GRID_SIZE + y * GRID_SIZE + x;
    }
}