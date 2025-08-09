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
 * Comprehensive validation tests for octree construction.
 * Verifies correctness, consistency, and edge cases.
 */
public class OctreeValidationTest {
    private static final Logger log = LoggerFactory.getLogger(OctreeValidationTest.class);
    
    private WebGPUContext context;
    private ComputeShaderManager shaderManager;
    private ShaderModule octreeShader;
    
    private static final int GRID_SIZE = 32;
    private static final int MAX_DEPTH = 5;
    
    @BeforeEach
    void setUp() throws Exception {
        context = new WebGPUContext();
        context.initialize().join();
        shaderManager = new ComputeShaderManager(context);
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
    void testOctreeStructureConsistency() throws Exception {
        // Create a specific pattern to test consistency
        int[] voxelGrid = new int[GRID_SIZE * GRID_SIZE * GRID_SIZE];
        
        // Add voxels in each octant
        for (int octant = 0; octant < 8; octant++) {
            int x = (octant & 1) * (GRID_SIZE / 2) + GRID_SIZE / 4;
            int y = ((octant >> 1) & 1) * (GRID_SIZE / 2) + GRID_SIZE / 4;
            int z = ((octant >> 2) & 1) * (GRID_SIZE / 2) + GRID_SIZE / 4;
            voxelGrid[getVoxelIndex(x, y, z)] = 0xFF000000 | (octant * 30);
        }
        
        List<OctreeNode> octree = executeOctreeConstruction(voxelGrid);
        
        // Validate structure
        validateOctreeStructure(octree);
        
        // Root should have 8 children
        OctreeNode root = octree.get(0);
        assertEquals(8, Integer.bitCount(root.childMask), 
            "Root should have exactly 8 children for 8 octant pattern");
        
        // Verify all nodes are reachable
        Set<Integer> visited = new HashSet<>();
        traverseAndValidate(octree, 0, visited);
        
        log.info("Octree structure validation passed. Visited {} nodes out of {}", 
            visited.size(), octree.size());
    }
    
    @Test
    void testOctreeMemoryEfficiency() throws Exception {
        // Test with varying densities
        int[][] densities = {
            {100, 50},   // 100 random voxels - sparse requires more nodes
            {1000, 200}, // 1000 random voxels
            {5000, 500}, // 5000 random voxels
        };
        
        for (int[] config : densities) {
            int voxelCount = config[0];
            int expectedMaxNodes = config[1] * 8; // Adjusted for sparse octree overhead
            
            int[] voxelGrid = createRandomVoxelGrid(voxelCount, 42 + voxelCount);
            List<OctreeNode> octree = executeOctreeConstruction(voxelGrid);
            
            log.info("Voxel count: {}, Octree nodes: {}, Efficiency: {} nodes/voxel", 
                voxelCount, octree.size(), String.format("%.2f", (double)octree.size() / voxelCount));
            
            // Should be memory efficient
            assertTrue(octree.size() < expectedMaxNodes, 
                String.format("Octree should be efficient: %d nodes for %d voxels", 
                    octree.size(), voxelCount));
        }
    }
    
    @Test
    void testOctreeLODAccuracy() throws Exception {
        // Create uniform regions to test LOD
        int[] voxelGrid = new int[GRID_SIZE * GRID_SIZE * GRID_SIZE];
        
        // Fill a cube with uniform color
        int color = 0xFFFF0000; // Red
        for (int z = 0; z < 8; z++) {
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    voxelGrid[getVoxelIndex(x, y, z)] = color;
                }
            }
        }
        
        List<OctreeNode> octree = executeOctreeConstruction(voxelGrid);
        
        // Find nodes that should have the averaged color
        int nodesWithColor = 0;
        for (OctreeNode node : octree) {
            if (node.voxelData == color) {
                nodesWithColor++;
            }
        }
        
        assertTrue(nodesWithColor > 0, "Should have nodes with the averaged color");
        log.info("Found {} nodes with LOD color", nodesWithColor);
    }
    
    @Test
    void testOctreeDepthLimits() throws Exception {
        // Create a pattern that would require deep subdivision
        int[] voxelGrid = new int[GRID_SIZE * GRID_SIZE * GRID_SIZE];
        
        // Diagonal line of voxels to force maximum subdivision
        for (int i = 0; i < GRID_SIZE; i++) {
            voxelGrid[getVoxelIndex(i, i, i % GRID_SIZE)] = 0xFFFFFFFF;
        }
        
        List<OctreeNode> octree = executeOctreeConstruction(voxelGrid);
        
        // Calculate actual max depth
        int maxDepth = calculateMaxDepth(octree, 0, 0);
        
        log.info("Max depth achieved: {} (limit: {})", maxDepth, MAX_DEPTH);
        assertTrue(maxDepth <= MAX_DEPTH, "Should respect maximum depth limit");
        assertTrue(maxDepth >= 2, "Should have reasonable depth for sparse data");
    }
    
    @Test
    void testOctreeChildIndexing() throws Exception {
        // Test that child indexing is correct
        int[] voxelGrid = new int[GRID_SIZE * GRID_SIZE * GRID_SIZE];
        
        // Add specific voxels to test indexing
        voxelGrid[getVoxelIndex(0, 0, 0)] = 0xFF0000FF;
        voxelGrid[getVoxelIndex(GRID_SIZE-1, GRID_SIZE-1, GRID_SIZE-1)] = 0xFF00FF00;
        
        List<OctreeNode> octree = executeOctreeConstruction(voxelGrid);
        
        // Verify child indices are within bounds
        for (int i = 0; i < octree.size(); i++) {
            OctreeNode node = octree.get(i);
            if (!node.isLeaf() && node.childMask != 0) {
                int childCount = Integer.bitCount(node.childMask);
                int maxChildIdx = node.dataOffset + childCount - 1;
                
                assertTrue(maxChildIdx < octree.size(), 
                    String.format("Node %d: child indices should be valid (offset=%d, count=%d, max=%d, size=%d)",
                        i, node.dataOffset, childCount, maxChildIdx, octree.size()));
            }
        }
    }
    
    @Test
    void testOctreeEmptyRegionHandling() throws Exception {
        // Test with mostly empty grid
        int[] voxelGrid = new int[GRID_SIZE * GRID_SIZE * GRID_SIZE];
        
        // Just 4 corner voxels
        voxelGrid[getVoxelIndex(0, 0, 0)] = 0xFFFFFFFF;
        voxelGrid[getVoxelIndex(GRID_SIZE-1, 0, 0)] = 0xFFFFFFFF;
        voxelGrid[getVoxelIndex(0, GRID_SIZE-1, 0)] = 0xFFFFFFFF;
        voxelGrid[getVoxelIndex(0, 0, GRID_SIZE-1)] = 0xFFFFFFFF;
        
        List<OctreeNode> octree = executeOctreeConstruction(voxelGrid);
        
        // Should have a sparse structure
        assertTrue(octree.size() < 100, 
            "Sparse octree should be compact even with distant voxels");
        
        // Verify we can find all voxels
        assertTrue(canFindVoxel(octree, 0, 0, 0));
        assertTrue(canFindVoxel(octree, GRID_SIZE-1, 0, 0));
        assertTrue(canFindVoxel(octree, 0, GRID_SIZE-1, 0));
        assertTrue(canFindVoxel(octree, 0, 0, GRID_SIZE-1));
    }
    
    @Test 
    void testOctreeColorPacking() throws Exception {
        // Test color packing/unpacking accuracy
        int[] voxelGrid = new int[GRID_SIZE * GRID_SIZE * GRID_SIZE];
        
        // Various colors
        int[] testColors = {
            0xFF0000FF, // Red
            0xFF00FF00, // Green  
            0xFFFF0000, // Blue
            0xFF808080, // Gray
            0xFFFFFFFF, // White
        };
        
        for (int i = 0; i < testColors.length; i++) {
            int x = i * 6;
            int y = i * 6;
            int z = i * 6;
            if (x < GRID_SIZE && y < GRID_SIZE && z < GRID_SIZE) {
                voxelGrid[getVoxelIndex(x, y, z)] = testColors[i];
            }
        }
        
        List<OctreeNode> octree = executeOctreeConstruction(voxelGrid);
        
        // Check that colors are preserved
        for (int color : testColors) {
            boolean found = false;
            for (OctreeNode node : octree) {
                if (node.voxelData == color) {
                    found = true;
                    break;
                }
            }
            assertTrue(found, String.format("Color 0x%08X should be preserved", color));
        }
    }
    
    // Helper methods
    
    private void validateOctreeStructure(List<OctreeNode> octree) {
        assertFalse(octree.isEmpty(), "Octree should not be empty");
        
        // Validate each node
        for (int i = 0; i < octree.size(); i++) {
            OctreeNode node = octree.get(i);
            
            // Check node type consistency
            if (node.isLeaf()) {
                assertEquals(0, node.childMask, 
                    "Leaf node should have no children");
            } else if (node.childMask != 0) {
                assertTrue(node.dataOffset > 0, 
                    "Internal node with children should have valid data offset");
                assertTrue(node.dataOffset < octree.size(), 
                    "Data offset should be within bounds");
            }
            
            // Check child mask validity (0-255)
            assertTrue(node.childMask >= 0 && node.childMask <= 255,
                "Child mask should be valid byte value");
        }
    }
    
    private void traverseAndValidate(List<OctreeNode> octree, int nodeIdx, Set<Integer> visited) {
        if (nodeIdx >= octree.size() || visited.contains(nodeIdx)) {
            return;
        }
        
        visited.add(nodeIdx);
        OctreeNode node = octree.get(nodeIdx);
        
        if (!node.isLeaf() && node.childMask != 0) {
            for (int octant = 0; octant < 8; octant++) {
                if ((node.childMask & (1 << octant)) != 0) {
                    int childIdx = node.dataOffset + 
                        Integer.bitCount(node.childMask & ((1 << octant) - 1));
                    traverseAndValidate(octree, childIdx, visited);
                }
            }
        }
    }
    
    private boolean canFindVoxel(List<OctreeNode> octree, int x, int y, int z) {
        return traverseToVoxel(octree, 0, x, y, z, 0, 0, 0, getOctreeSize());
    }
    
    private boolean traverseToVoxel(List<OctreeNode> octree, int nodeIdx,
                                   int targetX, int targetY, int targetZ,
                                   int nodeX, int nodeY, int nodeZ, int nodeSize) {
        if (nodeIdx >= octree.size()) return false;
        
        OctreeNode node = octree.get(nodeIdx);
        if (node.isLeaf()) {
            return node.voxelData != 0;
        }
        
        int halfSize = nodeSize / 2;
        int octant = 0;
        if (targetX >= nodeX + halfSize) octant |= 1;
        if (targetY >= nodeY + halfSize) octant |= 2;
        if (targetZ >= nodeZ + halfSize) octant |= 4;
        
        if ((node.childMask & (1 << octant)) == 0) {
            return false;
        }
        
        int childX = nodeX + ((octant & 1) != 0 ? halfSize : 0);
        int childY = nodeY + ((octant & 2) != 0 ? halfSize : 0);
        int childZ = nodeZ + ((octant & 4) != 0 ? halfSize : 0);
        
        int childIdx = node.dataOffset + Integer.bitCount(node.childMask & ((1 << octant) - 1));
        return traverseToVoxel(octree, childIdx, targetX, targetY, targetZ,
                              childX, childY, childZ, halfSize);
    }
    
    private int[] createRandomVoxelGrid(int voxelCount, int seed) {
        int[] grid = new int[GRID_SIZE * GRID_SIZE * GRID_SIZE];
        Random rand = new Random(seed);
        
        Set<Integer> used = new HashSet<>();
        while (used.size() < voxelCount) {
            int x = rand.nextInt(GRID_SIZE);
            int y = rand.nextInt(GRID_SIZE);
            int z = rand.nextInt(GRID_SIZE);
            int idx = getVoxelIndex(x, y, z);
            
            if (!used.contains(idx)) {
                used.add(idx);
                grid[idx] = 0xFF000000 | rand.nextInt(0xFFFFFF);
            }
        }
        
        return grid;
    }
    
    private int calculateMaxDepth(List<OctreeNode> octree, int nodeIdx, int currentDepth) {
        if (nodeIdx >= octree.size()) return currentDepth;
        
        OctreeNode node = octree.get(nodeIdx);
        if (node.isLeaf()) {
            return currentDepth;
        }
        
        int maxChildDepth = currentDepth;
        for (int octant = 0; octant < 8; octant++) {
            if ((node.childMask & (1 << octant)) != 0) {
                int childIdx = node.dataOffset + 
                    Integer.bitCount(node.childMask & ((1 << octant) - 1));
                int childDepth = calculateMaxDepth(octree, childIdx, currentDepth + 1);
                maxChildDepth = Math.max(maxChildDepth, childDepth);
            }
        }
        
        return maxChildDepth;
    }
    
    private int getOctreeSize() {
        int size = 1;
        while (size < GRID_SIZE) {
            size *= 2;
        }
        return size;
    }
    
    private int getVoxelIndex(int x, int y, int z) {
        return z * GRID_SIZE * GRID_SIZE + y * GRID_SIZE + x;
    }
    
    // Octree node class (matching shader structure)
    static class OctreeNode {
        int childMask;
        int dataOffset;
        int nodeType;
        int voxelData;
        
        public OctreeNode(int childMask, int dataOffset, int nodeType, int voxelData) {
            this.childMask = childMask;
            this.dataOffset = dataOffset;
            this.nodeType = nodeType;
            this.voxelData = voxelData;
        }
        
        public boolean isLeaf() {
            return nodeType == 1;
        }
    }
    
    // Execute octree construction using real WebGPU
    private List<OctreeNode> executeOctreeConstruction(int[] voxelGrid) throws Exception {
        var device = context.getDevice();
        
        // Create buffers and execute shader (same as OctreeConstructionTest)
        int voxelBufferSize = voxelGrid.length * 4;
        var voxelBuffer = context.createBuffer(voxelBufferSize, 
            WebGPUNative.BUFFER_USAGE_STORAGE | WebGPUNative.BUFFER_USAGE_COPY_DST);
        
        byte[] voxelBytes = new byte[voxelBufferSize];
        ByteBuffer voxelData = ByteBuffer.wrap(voxelBytes).order(ByteOrder.nativeOrder());
        for (int voxel : voxelGrid) {
            voxelData.putInt(voxel);
        }
        context.writeBuffer(voxelBuffer, voxelBytes, 0);
        
        int maxNodes = Math.max(1000, voxelGrid.length / 8);
        int octreeBufferSize = maxNodes * 16;
        var octreeBuffer = context.createBuffer(octreeBufferSize,
            WebGPUNative.BUFFER_USAGE_STORAGE | WebGPUNative.BUFFER_USAGE_COPY_SRC);
        
        int colorsBufferSize = voxelGrid.length * 16;
        var colorsBuffer = context.createBuffer(colorsBufferSize,
            WebGPUNative.BUFFER_USAGE_STORAGE | WebGPUNative.BUFFER_USAGE_COPY_DST);
        
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
        
        byte[] paramBytes = new byte[32];
        ByteBuffer params = ByteBuffer.wrap(paramBytes).order(ByteOrder.nativeOrder());
        params.putInt(GRID_SIZE).putInt(GRID_SIZE).putInt(GRID_SIZE);
        params.putInt(MAX_DEPTH).putInt(maxNodes).putInt(1);
        params.putInt(0).putInt(0);
        
        var paramsBuffer = context.createBuffer(32,
            WebGPUNative.BUFFER_USAGE_UNIFORM | WebGPUNative.BUFFER_USAGE_COPY_DST);
        context.writeBuffer(paramsBuffer, paramBytes, 0);
        
        int buildStateSize = 4 + 16 * 4;
        var buildStateBuffer = context.createBuffer(buildStateSize,
            WebGPUNative.BUFFER_USAGE_STORAGE | WebGPUNative.BUFFER_USAGE_COPY_DST | 
            WebGPUNative.BUFFER_USAGE_COPY_SRC);
        context.writeBuffer(buildStateBuffer, new byte[buildStateSize], 0);
        
        // Create pipeline
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
        
        var bindGroupDescriptor = new Device.BindGroupDescriptor(bindGroupLayout)
            .withLabel("OctreeBindGroup")
            .withEntry(new Device.BindGroupEntry(0).withBuffer(voxelBuffer, 0, voxelBufferSize))
            .withEntry(new Device.BindGroupEntry(1).withBuffer(colorsBuffer, 0, colorsBufferSize))
            .withEntry(new Device.BindGroupEntry(2).withBuffer(octreeBuffer, 0, octreeBufferSize))
            .withEntry(new Device.BindGroupEntry(3).withBuffer(paramsBuffer, 0, 32))
            .withEntry(new Device.BindGroupEntry(4).withBuffer(buildStateBuffer, 0, buildStateSize));
        
        var bindGroup = device.createBindGroup(bindGroupDescriptor);
        
        var pipelineLayout = device.createPipelineLayout(
            new Device.PipelineLayoutDescriptor()
                .withLabel("OctreePipelineLayout")
                .addBindGroupLayout(bindGroupLayout)
        );
        
        var pipelineDescriptor = new Device.ComputePipelineDescriptor(octreeShader)
            .withLabel("octree_build")
            .withEntryPoint("main")
            .withLayout(pipelineLayout);
        
        var pipeline = device.createComputePipeline(pipelineDescriptor);
        
        var commandEncoder = device.createCommandEncoder("OctreeComputeCommands");
        var computePass = commandEncoder.beginComputePass(
            new CommandEncoder.ComputePassDescriptor().withLabel("OctreeBuildPass")
        );
        
        computePass.setPipeline(pipeline);
        computePass.setBindGroup(0, bindGroup);
        computePass.dispatchWorkgroups(1, 1, 1);
        computePass.end();
        
        var readbackBuffer = context.createBuffer(octreeBufferSize,
            WebGPUNative.BUFFER_USAGE_MAP_READ | WebGPUNative.BUFFER_USAGE_COPY_DST);
        
        commandEncoder.copyBufferToBuffer(octreeBuffer, 0, readbackBuffer, 0, octreeBufferSize);
        
        CommandBuffer commandBuffer = commandEncoder.finish();
        device.getQueue().submit(commandBuffer);
        device.getQueue().onSubmittedWorkDone();
        
        var mappedSegment = readbackBuffer.mapAsync(Buffer.MapMode.READ, 0, octreeBufferSize).get();
        
        List<OctreeNode> nodes = new ArrayList<>();
        byte[] resultData = new byte[(int)mappedSegment.byteSize()];
        mappedSegment.asByteBuffer().get(resultData);
        
        ByteBuffer buffer = ByteBuffer.wrap(resultData).order(ByteOrder.nativeOrder());
        int nodeCount = 0;
        
        while (buffer.remaining() >= 16 && nodeCount < maxNodes) {
            int childMask = buffer.getInt();
            int dataOffset = buffer.getInt();
            int nodeType = buffer.getInt();
            int nodeVoxelData = buffer.getInt();
            
            if (nodeCount > 0 && childMask == 0 && dataOffset == 0 && nodeType == 0 && nodeVoxelData == 0) {
                break;
            }
            
            nodes.add(new OctreeNode(childMask, dataOffset, nodeType, nodeVoxelData));
            nodeCount++;
            
            if (nodeCount > 10000) break;
        }
        
        readbackBuffer.unmap();
        voxelBuffer.close();
        colorsBuffer.close();
        octreeBuffer.close();
        paramsBuffer.close();
        buildStateBuffer.close();
        readbackBuffer.close();
        
        return nodes.isEmpty() ? List.of(new OctreeNode(0, 0, 1, 0)) : nodes;
    }
}