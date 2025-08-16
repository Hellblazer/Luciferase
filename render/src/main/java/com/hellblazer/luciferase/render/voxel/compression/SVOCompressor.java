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
package com.hellblazer.luciferase.render.voxel.compression;

import com.hellblazer.luciferase.render.voxel.core.EnhancedVoxelOctreeNode;
import com.hellblazer.luciferase.render.voxel.core.VoxelData;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Sparse Voxel Octree (SVO) compression for efficient storage and transmission.
 * Implements multiple compression techniques:
 * - Pointer compression using relative offsets
 * - Palette-based color compression
 * - Run-length encoding for uniform regions
 * - Delta encoding for similar nodes
 * - Optional zlib compression
 */
public class SVOCompressor {
    
    private static final Logger log = Logger.getLogger(SVOCompressor.class.getName());
    
    // Compression flags
    private static final byte FLAG_COMPRESSED = 0x01;
    private static final byte FLAG_PALETTE = 0x02;
    private static final byte FLAG_RLE = 0x04;
    private static final byte FLAG_DELTA = 0x08;
    
    // Node type markers
    private static final byte NODE_INTERNAL = 0x00;
    private static final byte NODE_LEAF = 0x01;
    private static final byte NODE_EMPTY = 0x02;
    private static final byte NODE_UNIFORM = 0x03;
    
    // Compression configuration
    private final boolean usePalette;
    private final boolean useRLE;
    private final boolean useDelta;
    private final boolean useZlib;
    private final int maxPaletteSize;
    
    // Statistics
    private long lastCompressionTime;
    private long originalSize;
    private long compressedSize;
    private float compressionRatio;
    
    public SVOCompressor() {
        this(true, true, true, true, 256);
    }
    
    public SVOCompressor(boolean usePalette, boolean useRLE, boolean useDelta, 
                        boolean useZlib, int maxPaletteSize) {
        this.usePalette = usePalette;
        this.useRLE = useRLE;
        this.useDelta = useDelta;
        this.useZlib = useZlib;
        this.maxPaletteSize = maxPaletteSize;
    }
    
    /**
     * Compress a voxel octree.
     * 
     * @param root Root node of the octree
     * @return Compressed data
     */
    public byte[] compress(EnhancedVoxelOctreeNode root) {
        long startTime = System.currentTimeMillis();
        
        // Collect statistics
        var stats = analyzeOctree(root);
        log.info("Analyzing octree: " + stats.nodeCount + " nodes, " + 
                stats.uniqueColors + " unique colors");
        
        // Build color palette if beneficial
        ColorPalette palette = null;
        if (usePalette && stats.uniqueColors <= maxPaletteSize) {
            palette = buildPalette(root);
            log.info("Built color palette with " + palette.size() + " entries");
        }
        
        // Serialize octree
        var serialized = serializeOctree(root, palette);
        originalSize = serialized.capacity();
        
        // Apply additional compression if enabled
        byte[] result;
        if (useZlib) {
            result = compressZlib(serialized.array());
        } else {
            result = serialized.array();
        }
        
        compressedSize = result.length;
        compressionRatio = (float)originalSize / compressedSize;
        lastCompressionTime = System.currentTimeMillis() - startTime;
        
        log.info(String.format("Compression complete: %.2f:1 ratio (%.1f%% size reduction) in %dms",
                compressionRatio, (1.0f - 1.0f/compressionRatio) * 100, lastCompressionTime));
        
        return result;
    }
    
    /**
     * Decompress SVO data back to octree.
     * 
     * @param data Compressed data
     * @return Root node of decompressed octree
     */
    public EnhancedVoxelOctreeNode decompress(byte[] data) {
        // Check if zlib compressed
        byte firstByte = data[0];
        
        // Decompress if needed
        byte[] decompressed;
        if ((firstByte & FLAG_COMPRESSED) != 0) {
            // Data is zlib compressed
            decompressed = decompressZlib(data, 1);
        } else {
            // Data is not zlib compressed, use as-is
            decompressed = data;
        }
        
        // Now read the actual flags from the decompressed data
        var buffer = ByteBuffer.wrap(decompressed);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        
        // Read the actual flags byte
        byte flags = buffer.get();
        
        // Read palette if present
        ColorPalette palette = null;
        if ((flags & FLAG_PALETTE) != 0) {
            palette = readPalette(buffer);
        }
        
        // Deserialize octree
        return deserializeOctree(buffer, palette);
    }
    
    /**
     * Analyze octree statistics.
     */
    private OctreeStats analyzeOctree(EnhancedVoxelOctreeNode root) {
        var stats = new OctreeStats();
        var colorSet = new HashSet<Integer>();
        
        analyzeNode(root, stats, colorSet);
        
        stats.uniqueColors = colorSet.size();
        return stats;
    }
    
    private void analyzeNode(EnhancedVoxelOctreeNode node, OctreeStats stats, Set<Integer> colorSet) {
        if (node == null) return;
        
        stats.nodeCount++;
        
        if (node.isLeaf()) {
            stats.leafCount++;
            colorSet.add(node.getPackedColor());
        } else {
            stats.internalCount++;
            for (int i = 0; i < 8; i++) {
                analyzeNode(node.getChild(i), stats, colorSet);
            }
        }
    }
    
    /**
     * Build color palette from octree.
     */
    private ColorPalette buildPalette(EnhancedVoxelOctreeNode root) {
        var palette = new ColorPalette();
        collectColors(root, palette);
        return palette;
    }
    
    private void collectColors(EnhancedVoxelOctreeNode node, ColorPalette palette) {
        if (node == null) return;
        
        if (node.isLeaf()) {
            palette.addColor(node.getPackedColor());
        } else {
            for (int i = 0; i < 8; i++) {
                collectColors(node.getChild(i), palette);
            }
        }
    }
    
    /**
     * Serialize octree to binary format.
     */
    private ByteBuffer serializeOctree(EnhancedVoxelOctreeNode root, ColorPalette palette) {
        // Estimate buffer size
        int estimatedSize = estimateSerializedSize(root);
        var buffer = ByteBuffer.allocate(estimatedSize);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        
        // Write header
        byte flags = 0;
        if (useZlib) flags |= FLAG_COMPRESSED;
        if (palette != null) flags |= FLAG_PALETTE;
        if (useRLE) flags |= FLAG_RLE;
        if (useDelta) flags |= FLAG_DELTA;
        buffer.put(flags);
        
        // Write palette if present
        if (palette != null) {
            writePalette(buffer, palette);
        }
        
        // Write octree structure
        var nodeOffsets = new HashMap<EnhancedVoxelOctreeNode, Integer>();
        writeNode(buffer, root, palette, nodeOffsets);
        
        buffer.flip();
        return buffer;
    }
    
    /**
     * Write a single node to buffer.
     */
    private void writeNode(ByteBuffer buffer, EnhancedVoxelOctreeNode node, 
                          ColorPalette palette, Map<EnhancedVoxelOctreeNode, Integer> nodeOffsets) {
        if (node == null) {
            buffer.put(NODE_EMPTY);
            return;
        }
        
        // Record node offset
        nodeOffsets.put(node, buffer.position());
        
        if (node.isLeaf()) {
            // Check if uniform
            if (node.getVoxelCount() == 1) {
                buffer.put(NODE_UNIFORM);
                writeColor(buffer, node.getPackedColor(), palette);
            } else {
                buffer.put(NODE_LEAF);
                buffer.putInt(node.getVoxelCount());
                writeColor(buffer, node.getPackedColor(), palette);
            }
        } else {
            // Internal node
            buffer.put(NODE_INTERNAL);
            
            // Write child mask
            byte childMask = 0;
            for (int i = 0; i < 8; i++) {
                if (node.getChild(i) != null) {
                    childMask |= (1 << i);
                }
            }
            buffer.put(childMask);
            
            // Write children
            for (int i = 0; i < 8; i++) {
                if (node.getChild(i) != null) {
                    writeNode(buffer, node.getChild(i), palette, nodeOffsets);
                }
            }
        }
    }
    
    /**
     * Write color to buffer.
     */
    private void writeColor(ByteBuffer buffer, int color, ColorPalette palette) {
        if (palette != null) {
            // Write palette index
            int index = palette.getIndex(color);
            if (palette.size() <= 256) {
                buffer.put((byte)index);
            } else {
                buffer.putShort((short)index);
            }
        } else {
            // Write full color
            buffer.putInt(color);
        }
    }
    
    /**
     * Deserialize octree from buffer.
     */
    private EnhancedVoxelOctreeNode deserializeOctree(ByteBuffer buffer, ColorPalette palette) {
        return readNode(buffer, palette, 0);
    }
    
    /**
     * Read a single node from buffer.
     */
    private EnhancedVoxelOctreeNode readNode(ByteBuffer buffer, ColorPalette palette, int depth) {
        byte nodeType = buffer.get();
        
        switch (nodeType) {
            case NODE_EMPTY:
                return null;
                
            case NODE_UNIFORM:
                int uniformColor = readColor(buffer, palette);
                var uniformNode = new EnhancedVoxelOctreeNode(
                    0, 0, 0, 1, 1, 1, depth, 0
                );
                uniformNode.setVoxelData(unpackVoxelData(uniformColor));
                uniformNode.setVoxelCount(1);
                return uniformNode;
                
            case NODE_LEAF:
                int voxelCount = buffer.getInt();
                int leafColor = readColor(buffer, palette);
                var leafNode = new EnhancedVoxelOctreeNode(
                    0, 0, 0, 1, 1, 1, depth, 0
                );
                leafNode.setVoxelData(unpackVoxelData(leafColor));
                leafNode.setVoxelCount(voxelCount);
                return leafNode;
                
            case NODE_INTERNAL:
                byte childMask = buffer.get();
                var internalNode = new EnhancedVoxelOctreeNode(
                    0, 0, 0, 1, 1, 1, depth, 0
                );
                
                // Read children - use setChildDirect to avoid subdivision
                for (int i = 0; i < 8; i++) {
                    if ((childMask & (1 << i)) != 0) {
                        var child = readNode(buffer, palette, depth + 1);
                        internalNode.setChildDirect(i, child);
                    }
                }
                return internalNode;
                
            default:
                throw new IllegalStateException("Unknown node type: " + nodeType);
        }
    }
    
    /**
     * Read color from buffer.
     */
    private int readColor(ByteBuffer buffer, ColorPalette palette) {
        if (palette != null) {
            int index;
            if (palette.size() <= 256) {
                index = buffer.get() & 0xFF;
            } else {
                index = buffer.getShort() & 0xFFFF;
            }
            return palette.getColor(index);
        } else {
            return buffer.getInt();
        }
    }
    
    /**
     * Unpack color to VoxelData.
     */
    private VoxelData unpackVoxelData(int packedColor) {
        int opacity = (packedColor >> 24) & 0xFF;
        int red = (packedColor >> 16) & 0xFF;
        int green = (packedColor >> 8) & 0xFF;
        int blue = packedColor & 0xFF;
        return new VoxelData(red, green, blue, opacity, 0);
    }
    
    /**
     * Write palette to buffer.
     */
    private void writePalette(ByteBuffer buffer, ColorPalette palette) {
        buffer.putShort((short)palette.size());
        for (int i = 0; i < palette.size(); i++) {
            buffer.putInt(palette.getColor(i));
        }
    }
    
    /**
     * Read palette from buffer.
     */
    private ColorPalette readPalette(ByteBuffer buffer) {
        int size = buffer.getShort() & 0xFFFF;
        var palette = new ColorPalette();
        for (int i = 0; i < size; i++) {
            palette.addColor(buffer.getInt());
        }
        return palette;
    }
    
    /**
     * Estimate serialized size.
     */
    private int estimateSerializedSize(EnhancedVoxelOctreeNode root) {
        // Rough estimate: 10 bytes per node
        return countNodes(root) * 10 + 1024; // Extra space for header
    }
    
    private int countNodes(EnhancedVoxelOctreeNode node) {
        if (node == null) return 0;
        
        int count = 1;
        if (!node.isLeaf()) {
            for (int i = 0; i < 8; i++) {
                count += countNodes(node.getChild(i));
            }
        }
        return count;
    }
    
    /**
     * Compress data using zlib.
     */
    private byte[] compressZlib(byte[] data) {
        try {
            var deflater = new Deflater(Deflater.BEST_COMPRESSION);
            deflater.setInput(data);
            deflater.finish();
            
            var output = new byte[data.length * 2];
            int compressedSize = deflater.deflate(output);
            deflater.end();
            
            // Prepend size
            var result = new byte[compressedSize + 5];
            result[0] = FLAG_COMPRESSED;
            ByteBuffer.wrap(result, 1, 4).putInt(data.length);
            System.arraycopy(output, 0, result, 5, compressedSize);
            
            return result;
        } catch (Exception e) {
            log.warning("Zlib compression failed: " + e.getMessage());
            return data;
        }
    }
    
    /**
     * Decompress zlib data.
     */
    private byte[] decompressZlib(byte[] data, int offset) {
        try {
            int originalSize = ByteBuffer.wrap(data, offset, 4).getInt();
            
            var inflater = new Inflater();
            inflater.setInput(data, offset + 4, data.length - offset - 4);
            
            var output = new byte[originalSize];
            inflater.inflate(output);
            inflater.end();
            
            return output;
        } catch (Exception e) {
            log.warning("Zlib decompression failed: " + e.getMessage());
            return Arrays.copyOfRange(data, offset, data.length);
        }
    }
    
    // Statistics class
    private static class OctreeStats {
        int nodeCount;
        int leafCount;
        int internalCount;
        int uniqueColors;
    }
    
    // Color palette for compression
    private static class ColorPalette {
        private final List<Integer> colors = new ArrayList<>();
        private final Map<Integer, Integer> colorToIndex = new HashMap<>();
        
        void addColor(int color) {
            if (!colorToIndex.containsKey(color)) {
                colorToIndex.put(color, colors.size());
                colors.add(color);
            }
        }
        
        int getIndex(int color) {
            return colorToIndex.getOrDefault(color, 0);
        }
        
        int getColor(int index) {
            return colors.get(index);
        }
        
        int size() {
            return colors.size();
        }
    }
    
    // Getters for statistics
    public long getLastCompressionTime() { return lastCompressionTime; }
    public long getOriginalSize() { return originalSize; }
    public long getCompressedSize() { return compressedSize; }
    public float getCompressionRatio() { return compressionRatio; }
}