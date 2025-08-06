package com.hellblazer.luciferase.render.compression;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for sparse voxel octree compression.
 */
class SparseVoxelCompressorTest {
    
    private SparseVoxelCompressor compressor;
    private Random random;
    
    @BeforeEach
    void setUp() {
        compressor = new SparseVoxelCompressor();
        random = new Random(42);
    }
    
    @Test
    void testEmptyOctreeCompression() {
        SparseVoxelCompressor.OctreeNode root = 
            new SparseVoxelCompressor.OctreeNode(SparseVoxelCompressor.NodeType.EMPTY, 0);
        
        ByteBuffer compressed = compressor.compress(root);
        assertNotNull(compressed);
        assertTrue(compressed.remaining() > 0);
        
        SparseVoxelCompressor.OctreeNode decompressed = compressor.decompress(compressed);
        assertEquals(SparseVoxelCompressor.NodeType.EMPTY, decompressed.type);
    }
    
    @Test
    void testLeafNodeCompression() {
        SparseVoxelCompressor.OctreeNode root = 
            new SparseVoxelCompressor.OctreeNode(SparseVoxelCompressor.NodeType.LEAF, 0);
        root.dataValue = 0x12345678;
        
        ByteBuffer compressed = compressor.compress(root);
        SparseVoxelCompressor.OctreeNode decompressed = compressor.decompress(compressed);
        
        assertEquals(SparseVoxelCompressor.NodeType.LEAF, decompressed.type);
        assertEquals(0x12345678, decompressed.dataValue);
    }
    
    @Test
    void testInternalNodeWithChildren() {
        SparseVoxelCompressor.OctreeNode root = createTestOctree(3);
        
        ByteBuffer compressed = compressor.compress(root);
        SparseVoxelCompressor.OctreeNode decompressed = compressor.decompress(compressed);
        
        // Verify structure
        assertEquals(SparseVoxelCompressor.NodeType.INTERNAL, decompressed.type);
        int childCount = Integer.bitCount(decompressed.childMask);
        assertTrue(childCount > 0);
        
        // Verify compression ratio
        float ratio = compressor.getCompressionRatio(root, compressed);
        assertTrue(ratio > 1.0f, "Should achieve compression, got ratio: " + ratio);
    }
    
    @Test
    void testUniformRegionCompression() {
        SparseVoxelCompressor.OctreeNode root = 
            new SparseVoxelCompressor.OctreeNode(SparseVoxelCompressor.NodeType.UNIFORM, 0);
        root.dataValue = 0xFF00FF00;
        
        ByteBuffer compressed = compressor.compress(root);
        SparseVoxelCompressor.OctreeNode decompressed = compressor.decompress(compressed);
        
        assertEquals(SparseVoxelCompressor.NodeType.UNIFORM, decompressed.type);
        assertEquals(0xFF00FF00, decompressed.dataValue);
    }
    
    @Test
    void testDeltaCompression() {
        List<SparseVoxelCompressor.OctreeNode> nodes = new ArrayList<>();
        
        // Create similar nodes
        for (int i = 0; i < 10; i++) {
            SparseVoxelCompressor.OctreeNode node = 
                new SparseVoxelCompressor.OctreeNode(SparseVoxelCompressor.NodeType.LEAF, i);
            node.dataValue = 1000 + i;
            node.mortonCode = i * 8;
            nodes.add(node);
        }
        
        ByteBuffer deltaCompressed = compressor.deltaCompress(nodes);
        
        // Delta compression should be more efficient than raw storage
        int rawSize = nodes.size() * 13; // Approximate raw size per node
        assertTrue(deltaCompressed.remaining() < rawSize);
    }
    
    @Test
    void testRunLengthEncoding() {
        // Create data with runs
        byte[] data = new byte[1000];
        for (int i = 0; i < 100; i++) {
            byte value = (byte)(i % 10);
            for (int j = 0; j < 10; j++) {
                data[i * 10 + j] = value;
            }
        }
        
        ByteBuffer encoded = compressor.runLengthEncode(data);
        
        // RLE should compress repeated values
        assertTrue(encoded.remaining() < data.length);
        assertEquals(200, encoded.remaining()); // 100 runs * 2 bytes each
    }
    
    @Test
    void testLargeOctreeCompression() {
        SparseVoxelCompressor.OctreeNode root = createTestOctree(8);
        
        long startTime = System.nanoTime();
        ByteBuffer compressed = compressor.compress(root);
        long compressTime = System.nanoTime() - startTime;
        
        startTime = System.nanoTime();
        SparseVoxelCompressor.OctreeNode decompressed = compressor.decompress(compressed);
        long decompressTime = System.nanoTime() - startTime;
        
        // Verify correctness
        verifyOctreeStructure(root, decompressed);
        
        // Check performance
        assertTrue(compressTime < 100_000_000L, "Compression too slow: " + compressTime);
        assertTrue(decompressTime < 50_000_000L, "Decompression too slow: " + decompressTime);
        
        // Check compression ratio
        float ratio = compressor.getCompressionRatio(root, compressed);
        assertTrue(ratio > 2.0f, "Should achieve good compression for sparse data");
    }
    
    @Test
    void testSparseOctreeCompression() {
        // Create very sparse octree (only 10% filled)
        SparseVoxelCompressor.OctreeNode root = createSparseOctree(6, 0.1f);
        
        ByteBuffer compressed = compressor.compress(root);
        float ratio = compressor.getCompressionRatio(root, compressed);
        
        // Sparse data should compress very well
        assertTrue(ratio > 5.0f, "Sparse data should compress well, got ratio: " + ratio);
    }
    
    @Test
    void testMixedNodeTypes() {
        SparseVoxelCompressor.OctreeNode root = 
            new SparseVoxelCompressor.OctreeNode(SparseVoxelCompressor.NodeType.INTERNAL, 0);
        
        // Add mixed child types
        root.setChild(0, new SparseVoxelCompressor.OctreeNode(
            SparseVoxelCompressor.NodeType.EMPTY, 1));
        root.setChild(1, new SparseVoxelCompressor.OctreeNode(
            SparseVoxelCompressor.NodeType.LEAF, 1));
        root.setChild(2, new SparseVoxelCompressor.OctreeNode(
            SparseVoxelCompressor.NodeType.UNIFORM, 1));
        
        ByteBuffer compressed = compressor.compress(root);
        SparseVoxelCompressor.OctreeNode decompressed = compressor.decompress(compressed);
        
        // Verify all node types preserved
        assertEquals(3, Integer.bitCount(decompressed.childMask));
    }
    
    private SparseVoxelCompressor.OctreeNode createTestOctree(int depth) {
        return createOctreeRecursive(depth, 0);
    }
    
    private SparseVoxelCompressor.OctreeNode createOctreeRecursive(int remainingDepth, int level) {
        if (remainingDepth == 0) {
            // Leaf node
            SparseVoxelCompressor.OctreeNode leaf = 
                new SparseVoxelCompressor.OctreeNode(SparseVoxelCompressor.NodeType.LEAF, level);
            leaf.dataValue = random.nextInt();
            return leaf;
        }
        
        // Internal node
        SparseVoxelCompressor.OctreeNode internal = 
            new SparseVoxelCompressor.OctreeNode(SparseVoxelCompressor.NodeType.INTERNAL, level);
        
        // Randomly add children
        for (int i = 0; i < 8; i++) {
            if (random.nextFloat() < 0.6f) { // 60% chance of child
                internal.setChild(i, createOctreeRecursive(remainingDepth - 1, level + 1));
            }
        }
        
        return internal;
    }
    
    private SparseVoxelCompressor.OctreeNode createSparseOctree(int depth, float fillRate) {
        return createSparseOctreeRecursive(depth, 0, fillRate);
    }
    
    private SparseVoxelCompressor.OctreeNode createSparseOctreeRecursive(
            int remainingDepth, int level, float fillRate) {
        
        if (remainingDepth == 0) {
            if (random.nextFloat() < fillRate) {
                SparseVoxelCompressor.OctreeNode leaf = 
                    new SparseVoxelCompressor.OctreeNode(SparseVoxelCompressor.NodeType.LEAF, level);
                leaf.dataValue = random.nextInt();
                return leaf;
            } else {
                return new SparseVoxelCompressor.OctreeNode(
                    SparseVoxelCompressor.NodeType.EMPTY, level);
            }
        }
        
        // Check if entire subtree is empty
        if (random.nextFloat() > fillRate * 2) {
            return new SparseVoxelCompressor.OctreeNode(
                SparseVoxelCompressor.NodeType.EMPTY, level);
        }
        
        SparseVoxelCompressor.OctreeNode internal = 
            new SparseVoxelCompressor.OctreeNode(SparseVoxelCompressor.NodeType.INTERNAL, level);
        
        for (int i = 0; i < 8; i++) {
            if (random.nextFloat() < fillRate) {
                internal.setChild(i, createSparseOctreeRecursive(
                    remainingDepth - 1, level + 1, fillRate));
            }
        }
        
        return internal;
    }
    
    private void verifyOctreeStructure(SparseVoxelCompressor.OctreeNode original,
                                      SparseVoxelCompressor.OctreeNode decompressed) {
        assertEquals(original.type, decompressed.type);
        assertEquals(original.level, decompressed.level);
        
        if (original.type == SparseVoxelCompressor.NodeType.LEAF ||
            original.type == SparseVoxelCompressor.NodeType.UNIFORM) {
            assertEquals(original.dataValue, decompressed.dataValue);
        }
        
        if (original.type == SparseVoxelCompressor.NodeType.INTERNAL) {
            assertEquals(original.childMask, decompressed.childMask);
            
            for (int i = 0; i < 8; i++) {
                if (original.hasChild(i)) {
                    assertTrue(decompressed.hasChild(i));
                }
            }
        }
    }
}