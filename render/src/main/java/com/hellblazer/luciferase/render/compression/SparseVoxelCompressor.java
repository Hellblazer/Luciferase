package com.hellblazer.luciferase.render.compression;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sparse Voxel Octree (SVO) compression using hierarchical encoding.
 * Implements efficient compression for sparse voxel data structures.
 * 
 * Features:
 * - Octree-based spatial compression
 * - Child pointer elimination for sparse regions
 * - Bit-packed node representation
 * - Delta encoding for similar nodes
 * - Run-length encoding for homogeneous regions
 */
public class SparseVoxelCompressor {
    
    private static final int OCTREE_CHILDREN = 8;
    private static final int MAX_DEPTH = 16;
    
    public enum NodeType {
        EMPTY(0),      // No voxels
        LEAF(1),       // Leaf node with data
        INTERNAL(2),   // Internal node with children
        UNIFORM(3);    // Uniform region (single value)
        
        private final int code;
        
        NodeType(int code) {
            this.code = code;
        }
        
        public int getCode() { return code; }
        
        public static NodeType fromCode(int code) {
            for (NodeType type : values()) {
                if (type.code == code) return type;
            }
            throw new IllegalArgumentException("Invalid node type code: " + code);
        }
    }
    
    public static class OctreeNode {
        public NodeType type;
        public int childMask;  // 8-bit mask indicating which children exist
        public int dataValue;  // For leaf/uniform nodes
        public OctreeNode[] children;
        public int mortonCode;
        public int level;
        
        public OctreeNode(NodeType type, int level) {
            this.type = type;
            this.level = level;
            if (type == NodeType.INTERNAL) {
                this.children = new OctreeNode[OCTREE_CHILDREN];
            }
        }
        
        public void setChild(int index, OctreeNode child) {
            if (children != null && index >= 0 && index < OCTREE_CHILDREN) {
                children[index] = child;
                if (child != null) {
                    childMask |= (1 << index);
                } else {
                    childMask &= ~(1 << index);
                }
            }
        }
        
        public OctreeNode getChild(int index) {
            return (children != null && index >= 0 && index < OCTREE_CHILDREN) 
                ? children[index] : null;
        }
        
        public boolean hasChild(int index) {
            return (childMask & (1 << index)) != 0;
        }
        
        public int getChildCount() {
            return Integer.bitCount(childMask);
        }
    }
    
    /**
     * Compress octree node to byte array (for VoxelRenderingPipeline compatibility).
     */
    public byte[] compressOctree(Object rootNode) {
        // For now, return empty array since the actual octree type is not defined
        // This method bridges the gap between VoxelRenderingPipeline and SparseVoxelCompressor
        return new byte[0];
    }
    
    /**
     * Compress voxel octree to byte buffer.
     */
    public ByteBuffer compress(OctreeNode root) {
        CompressedStream stream = new CompressedStream();
        
        // Write header
        stream.writeHeader(root);
        
        // Compress tree structure
        Queue<OctreeNode> queue = new LinkedList<>();
        queue.offer(root);
        
        while (!queue.isEmpty()) {
            OctreeNode node = queue.poll();
            compressNode(node, stream);
            
            if (node.type == NodeType.INTERNAL) {
                for (int i = 0; i < OCTREE_CHILDREN; i++) {
                    if (node.hasChild(i)) {
                        queue.offer(node.getChild(i));
                    }
                }
            }
        }
        
        return stream.toByteBuffer();
    }
    
    /**
     * Decompress byte buffer to voxel octree.
     */
    public OctreeNode decompress(ByteBuffer buffer) {
        if (buffer.remaining() == 0) {
            return new OctreeNode(NodeType.EMPTY, 0);
        }
        
        CompressedStream stream = new CompressedStream(buffer.duplicate());
        
        try {
            // Read header
            int nodeCount = stream.readHeader();
            
            if (nodeCount <= 0) {
                return new OctreeNode(NodeType.EMPTY, 0);
            }
            
            // Read root node
            OctreeNode root = decompressNode(stream, 0);
            
            if (root.type != NodeType.INTERNAL) {
                return root;
            }
            
            // Simple breadth-first reconstruction for internal nodes
            Queue<OctreeNode> queue = new LinkedList<>();
            queue.offer(root);
            
            while (!queue.isEmpty() && stream.hasMoreData()) {
                OctreeNode parent = queue.poll();
                
                if (parent.type == NodeType.INTERNAL) {
                    for (int i = 0; i < OCTREE_CHILDREN; i++) {
                        if (parent.hasChild(i)) {
                            OctreeNode child = decompressNode(stream, parent.level + 1);
                            parent.setChild(i, child);
                            
                            if (child.type == NodeType.INTERNAL) {
                                queue.offer(child);
                            }
                        }
                    }
                }
            }
            
            return root;
            
        } catch (Exception e) {
            // Return empty node on any error
            return new OctreeNode(NodeType.EMPTY, 0);
        }
    }
    
    private void compressNode(OctreeNode node, CompressedStream stream) {
        // Write node type (2 bits)
        stream.writeBits(node.type.getCode(), 2);
        
        switch (node.type) {
            case EMPTY:
                // No additional data
                break;
                
            case LEAF:
                // Write voxel data (variable length based on encoding)
                stream.writeVoxelData(node.dataValue);
                break;
                
            case INTERNAL:
                // Write child mask (8 bits)
                stream.writeBits(node.childMask, 8);
                break;
                
            case UNIFORM:
                // Write uniform value
                stream.writeVoxelData(node.dataValue);
                break;
        }
    }
    
    private OctreeNode decompressNode(CompressedStream stream, int level) {
        // Read node type
        NodeType type = NodeType.fromCode(stream.readBits(2));
        OctreeNode node = new OctreeNode(type, level);
        
        switch (type) {
            case EMPTY:
                // No additional data
                break;
                
            case LEAF:
                // Read voxel data
                node.dataValue = stream.readVoxelData();
                break;
                
            case INTERNAL:
                // Read child mask
                node.childMask = stream.readBits(8);
                break;
                
            case UNIFORM:
                // Read uniform value
                node.dataValue = stream.readVoxelData();
                break;
        }
        
        return node;
    }
    
    private void reconstructTree(OctreeNode root, Map<Integer, OctreeNode> nodeMap) {
        Queue<OctreeNode> queue = new LinkedList<>();
        queue.offer(root);
        int nodeIndex = 1;
        
        while (!queue.isEmpty()) {
            OctreeNode parent = queue.poll();
            
            if (parent.type == NodeType.INTERNAL) {
                for (int i = 0; i < OCTREE_CHILDREN; i++) {
                    if (parent.hasChild(i)) {
                        OctreeNode child = nodeMap.get(nodeIndex++);
                        parent.setChild(i, child);
                        queue.offer(child);
                    }
                }
            }
        }
    }
    
    /**
     * Delta compression for similar adjacent nodes.
     */
    public ByteBuffer deltaCompress(List<OctreeNode> nodes) {
        if (nodes.isEmpty()) {
            return ByteBuffer.allocate(0);
        }
        
        ByteBuffer buffer = ByteBuffer.allocateDirect(nodes.size() * 16);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        
        // Write first node completely
        OctreeNode prev = nodes.get(0);
        writeFullNode(buffer, prev);
        
        // Write deltas for subsequent nodes
        for (int i = 1; i < nodes.size(); i++) {
            OctreeNode curr = nodes.get(i);
            writeDeltaNode(buffer, prev, curr);
            prev = curr;
        }
        
        buffer.flip();
        return buffer;
    }
    
    /**
     * Run-length encoding for homogeneous regions.
     */
    public ByteBuffer runLengthEncode(byte[] voxelData) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(voxelData.length);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        
        int i = 0;
        while (i < voxelData.length) {
            byte value = voxelData[i];
            int runLength = 1;
            
            // Count consecutive same values
            while (i + runLength < voxelData.length && 
                   voxelData[i + runLength] == value && 
                   runLength < 255) {
                runLength++;
            }
            
            // Write run
            buffer.put((byte)runLength);
            buffer.put(value);
            i += runLength;
        }
        
        buffer.flip();
        return buffer;
    }
    
    /**
     * Bit-packed representation for compact storage.
     */
    private static class CompressedStream {
        private ByteBuffer buffer;
        private int bitBuffer;
        private int bitCount;
        private static final int INITIAL_SIZE = 4096;
        
        public CompressedStream() {
            this.buffer = ByteBuffer.allocateDirect(INITIAL_SIZE);
            this.buffer.order(ByteOrder.LITTLE_ENDIAN);
            this.bitBuffer = 0;
            this.bitCount = 0;
        }
        
        public CompressedStream(ByteBuffer buffer) {
            this.buffer = buffer;
            this.buffer.order(ByteOrder.LITTLE_ENDIAN);
            this.bitBuffer = 0;
            this.bitCount = 0;
        }
        
        public void writeBits(int value, int bits) {
            bitBuffer |= (value & ((1 << bits) - 1)) << bitCount;
            bitCount += bits;
            
            while (bitCount >= 8) {
                buffer.put((byte)(bitBuffer & 0xFF));
                bitBuffer >>= 8;
                bitCount -= 8;
            }
        }
        
        public int readBits(int bits) {
            while (bitCount < bits) {
                bitBuffer |= (buffer.get() & 0xFF) << bitCount;
                bitCount += 8;
            }
            
            int value = bitBuffer & ((1 << bits) - 1);
            bitBuffer >>= bits;
            bitCount -= bits;
            
            return value;
        }
        
        public void writeVoxelData(int data) {
            // Use variable-length encoding based on value
            if (data == 0) {
                writeBits(0, 1);  // Flag: empty
            } else if (data < 256) {
                writeBits(1, 1);  // Flag: non-empty
                writeBits(0, 1);  // Flag: 8-bit
                writeBits(data, 8);
            } else if (data < 65536) {
                writeBits(1, 1);  // Flag: non-empty
                writeBits(1, 1);  // Flag: not 8-bit
                writeBits(0, 1);  // Flag: 16-bit
                writeBits(data, 16);
            } else {
                writeBits(1, 1);  // Flag: non-empty
                writeBits(1, 1);  // Flag: not 8-bit
                writeBits(1, 1);  // Flag: not 16-bit (32-bit)
                // Align to byte boundary for 32-bit write
                while (bitCount % 8 != 0) {
                    writeBits(0, 1);
                }
                flush();
                buffer.putInt(data);
            }
        }
        
        public int readVoxelData() {
            int flag = readBits(1);
            if (flag == 0) {
                return 0;  // Empty
            }
            
            // Read second bit to determine size
            flag = readBits(1);
            if (flag == 0) {
                return readBits(8);  // 8-bit value
            }
            
            // For multi-bit flags, read the size indicator
            int sizeFlag = readBits(1);
            if (sizeFlag == 0) {
                return readBits(16);  // 16-bit value
            }
            
            // Align to byte boundary for 32-bit read
            while (bitCount % 8 != 0) {
                readBits(1);
            }
            return buffer.getInt();  // 32-bit value
        }
        
        public void writeHeader(OctreeNode root) {
            buffer.putInt(0x53564F43);        // Magic: "SVOC" first
            buffer.putInt(1);                 // Version
            buffer.putInt(countNodes(root));  // Total node count
            buffer.putInt(root.level);        // Max depth
        }
        
        public int readHeader() {
            int magic = buffer.getInt();
            int version = buffer.getInt();
            int nodeCount = buffer.getInt();
            int maxDepth = buffer.getInt();
            
            if (magic != 0x53564F43) {
                throw new IllegalArgumentException("Invalid file format");
            }
            
            return nodeCount;
        }
        
        private int countNodes(OctreeNode node) {
            if (node == null) return 0;
            
            int count = 1;
            if (node.type == NodeType.INTERNAL) {
                for (int i = 0; i < OCTREE_CHILDREN; i++) {
                    if (node.hasChild(i)) {
                        count += countNodes(node.getChild(i));
                    }
                }
            }
            return count;
        }
        
        public void flush() {
            if (bitCount > 0) {
                buffer.put((byte)bitBuffer);
                bitBuffer = 0;
                bitCount = 0;
            }
        }
        
        public ByteBuffer toByteBuffer() {
            flush();
            buffer.flip();
            return buffer;
        }
        
        public boolean hasMoreData() {
            return buffer.hasRemaining();
        }
    }
    
    private void writeFullNode(ByteBuffer buffer, OctreeNode node) {
        buffer.put((byte)node.type.getCode());
        buffer.put((byte)node.childMask);
        buffer.putInt(node.dataValue);
        buffer.putInt(node.mortonCode);
        buffer.put((byte)node.level);
    }
    
    private void writeDeltaNode(ByteBuffer buffer, OctreeNode prev, OctreeNode curr) {
        // Write type delta
        buffer.put((byte)(curr.type.getCode() - prev.type.getCode()));
        
        // Write child mask delta
        buffer.put((byte)(curr.childMask ^ prev.childMask));
        
        // Write data value delta
        buffer.putInt(curr.dataValue - prev.dataValue);
        
        // Write morton code delta
        buffer.putInt(curr.mortonCode - prev.mortonCode);
        
        // Level is usually the same, use single bit
        buffer.put((byte)(curr.level == prev.level ? 0 : curr.level));
    }
    
    /**
     * Calculate compression ratio.
     */
    public float getCompressionRatio(OctreeNode root, ByteBuffer compressed) {
        int uncompressedSize = calculateUncompressedSize(root);
        int compressedSize = compressed.remaining();
        return (float)uncompressedSize / compressedSize;
    }
    
    private int calculateUncompressedSize(OctreeNode node) {
        if (node == null) return 0;
        
        // Basic node structure: type(4) + childMask(4) + data(4) + morton(4) + level(4) = 20 bytes
        int size = 20;
        
        // Add children pointers (8 * 8 bytes for 64-bit pointers)
        if (node.type == NodeType.INTERNAL) {
            size += 64;
            for (int i = 0; i < OCTREE_CHILDREN; i++) {
                if (node.hasChild(i)) {
                    size += calculateUncompressedSize(node.getChild(i));
                }
            }
        }
        
        return size;
    }
}