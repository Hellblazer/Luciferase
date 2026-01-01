package com.hellblazer.luciferase.esvo.optimization;

import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.esvo.core.ESVONodeUnified;
import com.hellblazer.luciferase.sparse.optimization.Optimizer;

import java.util.*;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Memory bandwidth optimization for ESVO data structures.
 * Implements compression and data packing strategies to reduce memory traffic.
 */
public class ESVOBandwidthOptimizer implements Optimizer<ESVOOctreeData> {

    private static final int COMPRESSION_LEVEL = Deflater.BEST_COMPRESSION;
    private static final int DEFAULT_STREAMING_BUFFER_SIZE = 1024;

    /** Implements {@link Optimizer#optimize} by delegating to {@link #optimizeForStreaming}. */
    @Override
    public ESVOOctreeData optimize(ESVOOctreeData input) {
        return optimizeForStreaming(input, DEFAULT_STREAMING_BUFFER_SIZE);
    }
    
    public static class CompressedNodeData {
        private final byte[] compressedData;
        private final int originalSize;
        private final float compressionRatio;
        private final Map<String, Object> compressionMetadata;
        
        public CompressedNodeData(byte[] compressedData, int originalSize, 
                                Map<String, Object> metadata) {
            this.compressedData = Arrays.copyOf(compressedData, compressedData.length);
            this.originalSize = originalSize;
            this.compressionRatio = compressedData.length > 0 ? 
                (float) compressedData.length / originalSize : 0.0f;
            this.compressionMetadata = new HashMap<>(metadata);
        }
        
        public byte[] getCompressedData() { 
            return Arrays.copyOf(compressedData, compressedData.length); 
        }
        public int getOriginalSize() { return originalSize; }
        public int getCompressedSize() { return compressedData.length; }
        public float getCompressionRatio() { return compressionRatio; }
        public Map<String, Object> getCompressionMetadata() { 
            return Collections.unmodifiableMap(compressionMetadata); 
        }
        
        public float getSpaceSavings() {
            return originalSize > 0 ? 1.0f - compressionRatio : 0.0f;
        }
    }
    
    public static class BandwidthProfile {
        private final long totalBytes;
        private final long compressedBytes;
        private final float bandwidthReduction;
        private final Map<String, Float> optimizationMetrics;
        
        public BandwidthProfile(long totalBytes, long compressedBytes, 
                              Map<String, Float> metrics) {
            this.totalBytes = totalBytes;
            this.compressedBytes = compressedBytes;
            this.bandwidthReduction = totalBytes > 0 ? 
                1.0f - ((float) compressedBytes / totalBytes) : 0.0f;
            this.optimizationMetrics = new HashMap<>(metrics);
        }
        
        public long getTotalBytes() { return totalBytes; }
        public long getCompressedBytes() { return compressedBytes; }
        public float getBandwidthReduction() { return bandwidthReduction; }
        public Map<String, Float> getOptimizationMetrics() { 
            return Collections.unmodifiableMap(optimizationMetrics); 
        }
    }
    
    public static class PackedNodeFormat {
        private final byte[] packedData;
        private final int nodeCount;
        private final float packingEfficiency;
        
        public PackedNodeFormat(byte[] packedData, int nodeCount, float packingEfficiency) {
            this.packedData = Arrays.copyOf(packedData, packedData.length);
            this.nodeCount = nodeCount;
            this.packingEfficiency = packingEfficiency;
        }
        
        public byte[] getPackedData() { 
            return Arrays.copyOf(packedData, packedData.length); 
        }
        public int getNodeCount() { return nodeCount; }
        public float getPackingEfficiency() { return packingEfficiency; }
    }
    
    /**
     * Compresses octree node data to reduce memory bandwidth
     */
    public CompressedNodeData compressNodeData(ESVOOctreeData octreeData) {
        var nodeIndices = octreeData.getNodeIndices();
        if (nodeIndices.length == 0) {
            var metadata = Map.<String, Object>of("nodeCount", 0, "compressionAlgorithm", "deflate");
            return new CompressedNodeData(new byte[0], 0, metadata);
        }
        
        // Serialize node data to byte array
        var originalData = serializeNodeData(octreeData, nodeIndices);
        
        // Compress using deflate algorithm
        var deflater = new Deflater(COMPRESSION_LEVEL);
        deflater.setInput(originalData);
        deflater.finish();
        
        var compressedData = new byte[originalData.length * 2]; // Worst case
        var compressedLength = deflater.deflate(compressedData);
        deflater.end();
        
        // Trim to actual size
        var finalCompressedData = Arrays.copyOf(compressedData, compressedLength);
        
        // Create metadata
        var metadata = new HashMap<String, Object>();
        metadata.put("nodeCount", nodeIndices.length);
        metadata.put("compressionAlgorithm", "deflate");
        metadata.put("compressionLevel", COMPRESSION_LEVEL);
        metadata.put("originalNodes", nodeIndices.length);
        
        return new CompressedNodeData(finalCompressedData, originalData.length, metadata);
    }
    
    /**
     * Decompresses node data back to octree format
     */
    public ESVOOctreeData decompressNodeData(CompressedNodeData compressedData) {
        if (compressedData.getCompressedSize() == 0) {
            return new ESVOOctreeData(64); // Default capacity
        }
        
        try {
            // Decompress data
            var inflater = new Inflater();
            inflater.setInput(compressedData.getCompressedData());
            
            var decompressedData = new byte[compressedData.getOriginalSize()];
            var decompressedLength = inflater.inflate(decompressedData);
            inflater.end();
            
            if (decompressedLength != compressedData.getOriginalSize()) {
                throw new RuntimeException("Decompression size mismatch");
            }
            
            // Deserialize back to octree data
            return deserializeNodeData(decompressedData, compressedData.getCompressionMetadata());
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to decompress node data", e);
        }
    }
    
    /**
     * Analyzes bandwidth usage patterns
     */
    public BandwidthProfile analyzeBandwidthUsage(ESVOOctreeData octreeData,
                                                 int[] accessPattern) {
        var nodeIndices = octreeData.getNodeIndices();
        if (nodeIndices.length == 0) {
            var metrics = Map.of("accessCount", 0.0f, "uniqueNodes", 0.0f);
            return new BandwidthProfile(0L, 0L, metrics);
        }
        
        // Calculate total bandwidth for uncompressed access
        var nodeSize = 16; // bytes per node (childMask + contour + data)
        var totalAccesses = accessPattern.length;
        var totalBytes = (long) totalAccesses * nodeSize;
        
        // Analyze access patterns for compression opportunities
        var uniqueAccesses = new HashSet<Integer>();
        var accessCounts = new HashMap<Integer, Integer>();
        
        for (int nodeIndex : accessPattern) {
            uniqueAccesses.add(nodeIndex);
            accessCounts.merge(nodeIndex, 1, Integer::sum);
        }
        
        // Estimate compressed bandwidth (nodes accessed multiple times benefit more)
        var compressedBytes = calculateCompressedBandwidth(octreeData, accessCounts, nodeSize);
        
        // Calculate metrics
        var metrics = new HashMap<String, Float>();
        metrics.put("accessCount", (float) totalAccesses);
        metrics.put("uniqueNodes", (float) uniqueAccesses.size());
        metrics.put("averageReuse", (float) totalAccesses / uniqueAccesses.size());
        metrics.put("cacheHitRatio", 1.0f - ((float) uniqueAccesses.size() / totalAccesses));
        
        return new BandwidthProfile(totalBytes, compressedBytes, metrics);
    }
    
    /**
     * Packs multiple nodes into cache-line sized chunks
     */
    public PackedNodeFormat packNodesForCacheLines(ESVOOctreeData octreeData, 
                                                  int cacheLineSize) {
        var nodeIndices = octreeData.getNodeIndices();
        if (nodeIndices.length == 0) {
            return new PackedNodeFormat(new byte[0], 0, 1.0f);
        }
        
        var nodeSize = 16; // bytes per node
        var nodesPerCacheLine = Math.max(1, cacheLineSize / nodeSize);
        
        // Pack nodes into cache-line sized chunks
        var packedData = new ByteArrayOutputStream();
        var totalNodes = 0;
        var totalCacheLines = 0;
        
        try {
            for (int i = 0; i < nodeIndices.length; i += nodesPerCacheLine) {
                var cacheLineData = new byte[cacheLineSize];
                var nodesInThisLine = 0;
                
                // Fill cache line with nodes
                for (int j = 0; j < nodesPerCacheLine && (i + j) < nodeIndices.length; j++) {
                    var nodeIndex = nodeIndices[i + j];
                    var node = octreeData.getNode(nodeIndex);
                    
                    if (node != null) {
                        // Pack node data into cache line
                        var nodeBytes = serializeNode(node);
                        System.arraycopy(nodeBytes, 0, cacheLineData, j * nodeSize, 
                                       Math.min(nodeBytes.length, nodeSize));
                        nodesInThisLine++;
                    }
                }
                
                packedData.write(cacheLineData);
                totalNodes += nodesInThisLine;
                totalCacheLines++;
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to pack nodes", e);
        }
        
        // Calculate packing efficiency
        var theoreticalOptimal = (totalNodes * nodeSize);
        var actualSize = totalCacheLines * cacheLineSize;
        var packingEfficiency = actualSize > 0 ? (float) theoreticalOptimal / actualSize : 1.0f;
        
        return new PackedNodeFormat(packedData.toByteArray(), totalNodes, packingEfficiency);
    }
    
    /**
     * Optimizes data layout for streaming access patterns
     */
    public ESVOOctreeData optimizeForStreaming(ESVOOctreeData originalData,
                                             int streamingBufferSize) {
        var nodeIndices = originalData.getNodeIndices();
        if (nodeIndices.length <= streamingBufferSize) {
            return originalData; // Already fits in streaming buffer
        }
        
        // Group nodes for optimal streaming (spatial locality)
        var streamingGroups = createStreamingGroups(originalData, streamingBufferSize);
        
        // Create new layout with streaming-optimized ordering
        var optimizedData = new ESVOOctreeData(originalData.getMaxSizeBytes());
        var newIndex = 0;
        
        for (var group : streamingGroups) {
            for (int originalIndex : group) {
                var node = originalData.getNode(originalIndex);
                if (node != null) {
                    optimizedData.setNode(newIndex++, node);
                }
            }
        }
        
        return optimizedData;
    }
    
    /**
     * Estimates bandwidth savings from various optimizations
     */
    public Map<String, Float> estimateBandwidthSavings(ESVOOctreeData octreeData,
                                                      int[] accessPattern) {
        var savings = new HashMap<String, Float>();
        
        // Calculate baseline bandwidth
        var baselineBandwidth = (float) accessPattern.length * 16; // 16 bytes per access
        
        // Estimate compression savings
        var compressed = compressNodeData(octreeData);
        var compressionSaving = compressed.getSpaceSavings();
        savings.put("compression", compressionSaving * baselineBandwidth);
        
        // Estimate cache-line packing savings
        var packed = packNodesForCacheLines(octreeData, 64); // 64-byte cache lines
        var packingSaving = 1.0f - packed.getPackingEfficiency();
        savings.put("packing", packingSaving * baselineBandwidth);
        
        // Estimate streaming savings (reduce random access overhead)
        var uniqueAccesses = (int) Arrays.stream(accessPattern).distinct().count();
        var streamingSaving = Math.max(0.0f, 1.0f - ((float) uniqueAccesses / accessPattern.length));
        savings.put("streaming", streamingSaving * baselineBandwidth * 0.3f); // 30% of reuse benefit
        
        // Total estimated savings
        var totalSaving = savings.values().stream()
            .map(Math::abs)
            .reduce(0.0f, Float::sum);
        savings.put("total", Math.min(totalSaving, baselineBandwidth * 0.8f)); // Cap at 80% savings
        
        return savings;
    }
    
    // Private helper methods
    
    private byte[] serializeNodeData(ESVOOctreeData octreeData, int[] nodeIndices) {
        var output = new ByteArrayOutputStream();
        
        try {
            // Write node count
            output.write(intToBytes(nodeIndices.length));
            
            // Write each node
            for (int index : nodeIndices) {
                var node = octreeData.getNode(index);
                if (node != null) {
                    output.write(intToBytes(index)); // Node index
                    output.write(serializeNode(node)); // Node data
                } else {
                    output.write(intToBytes(index));
                    output.write(new byte[12]); // Empty node data
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize node data", e);
        }
        
        return output.toByteArray();
    }
    
    private byte[] serializeNode(ESVONodeUnified node) {
        var nodeData = new byte[12]; // childMask(1) + childPtr(2) + contour(4) + reserved(5)
        nodeData[0] = (byte)node.getChildMask();
        
        // Pack child pointer as 2 bytes (14 bits)
        var childPtr = node.getChildPtr();
        nodeData[1] = (byte)(childPtr >> 8);
        nodeData[2] = (byte)childPtr;
        
        // Pack full contourDescriptor as 4 bytes (includes mask and ptr)
        var contourBytes = intToBytes(node.getContourDescriptor());
        System.arraycopy(contourBytes, 0, nodeData, 3, 4);
        
        // Reserved space for future data
        // nodeData[7-11] remain zero
        
        return nodeData;
    }
    
    private ESVOOctreeData deserializeNodeData(byte[] data, Map<String, Object> metadata) {
        var nodeCount = (Integer) metadata.get("nodeCount");
        var octreeData = new ESVOOctreeData(Math.max(64, nodeCount * 2));
        
        if (data.length < 4 || nodeCount == 0) {
            return octreeData;
        }
        
        var index = 0;
        
        // Read node count
        var storedNodeCount = bytesToInt(data, index);
        index += 4;
        
        // Read nodes
        for (int i = 0; i < storedNodeCount && index + 16 <= data.length; i++) {
            var nodeIndex = bytesToInt(data, index);
            index += 4;
            
            var childMask = data[index++] & 0xFF;
            var childPtr = ((data[index] & 0xFF) << 8) | (data[index + 1] & 0xFF);
            index += 2;
            
            var contourDescriptor = bytesToInt(data, index);
            index += 4;
            
            // Skip reserved bytes
            index += 5;
            
            if (childMask != 0 || contourDescriptor != 0) {
                // Reconstruct node with proper bit packing
                int childDescriptor = (childMask << 8) | (childPtr << 17);
                octreeData.setNode(nodeIndex, new ESVONodeUnified(childDescriptor, contourDescriptor));
            }
        }
        
        return octreeData;
    }
    
    private long calculateCompressedBandwidth(ESVOOctreeData octreeData, 
                                            Map<Integer, Integer> accessCounts,
                                            int nodeSize) {
        var totalCompressedBytes = 0L;
        
        for (var entry : accessCounts.entrySet()) {
            var nodeIndex = entry.getKey();
            var accessCount = entry.getValue();
            
            // First access requires full node transfer
            totalCompressedBytes += nodeSize;
            
            // Subsequent accesses benefit from caching/compression (reduced cost)
            if (accessCount > 1) {
                totalCompressedBytes += (accessCount - 1) * (nodeSize / 4); // 25% of full cost
            }
        }
        
        return totalCompressedBytes;
    }
    
    private List<List<Integer>> createStreamingGroups(ESVOOctreeData octreeData,
                                                     int groupSize) {
        var nodeIndices = octreeData.getNodeIndices();
        var groups = new ArrayList<List<Integer>>();
        
        // Simple sequential grouping (could be improved with spatial analysis)
        for (int i = 0; i < nodeIndices.length; i += groupSize) {
            var group = new ArrayList<Integer>();
            for (int j = i; j < Math.min(i + groupSize, nodeIndices.length); j++) {
                group.add(nodeIndices[j]);
            }
            if (!group.isEmpty()) {
                groups.add(group);
            }
        }
        
        return groups;
    }
    
    private byte[] intToBytes(int value) {
        return new byte[] {
            (byte) (value >>> 24),
            (byte) (value >>> 16),
            (byte) (value >>> 8),
            (byte) value
        };
    }
    
    private int bytesToInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24) |
               ((bytes[offset + 1] & 0xFF) << 16) |
               ((bytes[offset + 2] & 0xFF) << 8) |
               (bytes[offset + 3] & 0xFF);
    }
}