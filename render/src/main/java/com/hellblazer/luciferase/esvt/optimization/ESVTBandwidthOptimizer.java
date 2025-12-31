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
package com.hellblazer.luciferase.esvt.optimization;

import com.hellblazer.luciferase.esvt.core.ESVTData;
import com.hellblazer.luciferase.esvt.core.ESVTNodeUnified;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Memory bandwidth optimization for ESVT tetrahedral data structures.
 *
 * <p>Implements compression and data packing strategies to reduce memory traffic,
 * optimized for the compact 8-byte ESVT node format.
 *
 * @author hal.hildebrand
 */
public class ESVTBandwidthOptimizer {
    private static final Logger log = LoggerFactory.getLogger(ESVTBandwidthOptimizer.class);

    private static final int COMPRESSION_LEVEL = Deflater.BEST_COMPRESSION;
    private static final int NODE_SIZE = ESVTNodeUnified.SIZE_BYTES;

    /**
     * Compressed node data container.
     */
    public static class CompressedNodeData {
        private final byte[] compressedData;
        private final int originalSize;
        private final float compressionRatio;
        private final Map<String, Object> metadata;

        public CompressedNodeData(byte[] compressedData, int originalSize,
                                  Map<String, Object> metadata) {
            this.compressedData = Arrays.copyOf(compressedData, compressedData.length);
            this.originalSize = originalSize;
            this.compressionRatio = originalSize > 0 ?
                (float) compressedData.length / originalSize : 0.0f;
            this.metadata = new HashMap<>(metadata);
        }

        public byte[] getCompressedData() {
            return Arrays.copyOf(compressedData, compressedData.length);
        }
        public int getOriginalSize() { return originalSize; }
        public int getCompressedSize() { return compressedData.length; }
        public float getCompressionRatio() { return compressionRatio; }
        public Map<String, Object> getMetadata() {
            return Collections.unmodifiableMap(metadata);
        }
        public float getSpaceSavings() {
            return originalSize > 0 ? 1.0f - compressionRatio : 0.0f;
        }
    }

    /**
     * Bandwidth usage analysis result.
     */
    public static class BandwidthProfile {
        private final long totalBytes;
        private final long compressedBytes;
        private final float bandwidthReduction;
        private final Map<String, Float> metrics;

        public BandwidthProfile(long totalBytes, long compressedBytes,
                                Map<String, Float> metrics) {
            this.totalBytes = totalBytes;
            this.compressedBytes = compressedBytes;
            this.bandwidthReduction = totalBytes > 0 ?
                1.0f - ((float) compressedBytes / totalBytes) : 0.0f;
            this.metrics = new HashMap<>(metrics);
        }

        public long getTotalBytes() { return totalBytes; }
        public long getCompressedBytes() { return compressedBytes; }
        public float getBandwidthReduction() { return bandwidthReduction; }
        public Map<String, Float> getMetrics() {
            return Collections.unmodifiableMap(metrics);
        }
    }

    /**
     * Compress ESVT node data to reduce memory bandwidth.
     */
    public CompressedNodeData compressNodeData(ESVTData data) {
        var nodes = data.nodes();
        if (nodes.length == 0) {
            var metadata = Map.<String, Object>of("nodeCount", 0, "algorithm", "deflate");
            return new CompressedNodeData(new byte[0], 0, metadata);
        }

        // Serialize node data
        var originalData = serializeNodes(nodes);

        // Compress using deflate
        var deflater = new Deflater(COMPRESSION_LEVEL);
        deflater.setInput(originalData);
        deflater.finish();

        var compressedData = new byte[originalData.length * 2];
        var compressedLength = deflater.deflate(compressedData);
        deflater.end();

        var finalData = Arrays.copyOf(compressedData, compressedLength);

        var metadata = new HashMap<String, Object>();
        metadata.put("nodeCount", nodes.length);
        metadata.put("algorithm", "deflate");
        metadata.put("compressionLevel", COMPRESSION_LEVEL);
        metadata.put("nodeSize", NODE_SIZE);

        log.debug("Compressed {} nodes: {} -> {} bytes ({}% reduction)",
                nodes.length, originalData.length, compressedLength,
                String.format("%.1f", (1.0 - (float) compressedLength / originalData.length) * 100));

        return new CompressedNodeData(finalData, originalData.length, metadata);
    }

    /**
     * Decompress node data back to ESVTData format.
     */
    public ESVTData decompressNodeData(CompressedNodeData compressed, ESVTData template) {
        if (compressed.getCompressedSize() == 0) {
            return template;
        }

        try {
            var inflater = new Inflater();
            inflater.setInput(compressed.getCompressedData());

            var decompressedData = new byte[compressed.getOriginalSize()];
            var decompressedLength = inflater.inflate(decompressedData);
            inflater.end();

            if (decompressedLength != compressed.getOriginalSize()) {
                throw new RuntimeException("Decompression size mismatch");
            }

            var nodeCount = (Integer) compressed.getMetadata().get("nodeCount");
            var nodes = deserializeNodes(decompressedData, nodeCount);

            return new ESVTData(
                nodes,
                template.contours(),
                template.farPointers(),
                template.rootType(),
                template.maxDepth(),
                template.leafCount(),
                template.internalCount(),
                template.gridResolution(),
                template.leafVoxelCoords()
            );

        } catch (Exception e) {
            throw new RuntimeException("Failed to decompress node data", e);
        }
    }

    /**
     * Analyze bandwidth usage patterns.
     */
    public BandwidthProfile analyzeBandwidthUsage(ESVTData data, int[] accessPattern) {
        var nodes = data.nodes();
        if (nodes.length == 0) {
            return new BandwidthProfile(0L, 0L, Map.of());
        }

        // Total bandwidth for uncompressed access
        var totalBytes = (long) (accessPattern.length > 0 ? accessPattern.length : nodes.length) * NODE_SIZE;

        // Analyze access patterns for compression opportunities
        var uniqueAccesses = new HashSet<Integer>();
        var accessCounts = new HashMap<Integer, Integer>();

        if (accessPattern.length > 0) {
            for (int index : accessPattern) {
                uniqueAccesses.add(index);
                accessCounts.merge(index, 1, Integer::sum);
            }
        } else {
            for (int i = 0; i < nodes.length; i++) {
                uniqueAccesses.add(i);
                accessCounts.put(i, 1);
            }
        }

        // Estimate compressed bandwidth
        var compressedBytes = calculateCompressedBandwidth(accessCounts);

        var metrics = new HashMap<String, Float>();
        metrics.put("accessCount", (float) accessCounts.size());
        metrics.put("uniqueNodes", (float) uniqueAccesses.size());
        metrics.put("averageReuse", accessCounts.isEmpty() ? 1.0f :
            (float) accessCounts.values().stream().mapToInt(Integer::intValue).sum() / accessCounts.size());
        metrics.put("cacheHitRatio", accessCounts.isEmpty() ? 0.0f :
            1.0f - ((float) uniqueAccesses.size() / accessCounts.values().stream().mapToInt(Integer::intValue).sum()));

        return new BandwidthProfile(totalBytes, compressedBytes, metrics);
    }

    /**
     * Optimize data layout for streaming access patterns.
     */
    public ESVTData optimizeForStreaming(ESVTData originalData, int streamingBufferSize) {
        var nodes = originalData.nodes();
        if (nodes.length <= streamingBufferSize / NODE_SIZE) {
            return originalData;
        }

        // Group nodes for optimal streaming (spatial locality)
        var nodesPerGroup = streamingBufferSize / NODE_SIZE;
        var streamingGroups = createStreamingGroups(nodes, nodesPerGroup);

        // Create new layout with streaming-optimized ordering
        var optimizedNodes = new ESVTNodeUnified[nodes.length];
        var newIndex = 0;

        for (var group : streamingGroups) {
            for (var node : group) {
                if (newIndex < optimizedNodes.length) {
                    optimizedNodes[newIndex++] = node;
                }
            }
        }

        log.debug("Optimized for streaming: {} groups of ~{} nodes",
                streamingGroups.size(), nodesPerGroup);

        return new ESVTData(
            optimizedNodes,
            originalData.contours(),
            originalData.farPointers(),
            originalData.rootType(),
            originalData.maxDepth(),
            originalData.leafCount(),
            originalData.internalCount(),
            originalData.gridResolution(),
            originalData.leafVoxelCoords()
        );
    }

    /**
     * Estimate bandwidth savings from various optimizations.
     */
    public Map<String, Float> estimateBandwidthSavings(ESVTData data, int[] accessPattern) {
        var savings = new HashMap<String, Float>();
        var baselineBandwidth = (float) (accessPattern.length > 0 ?
            accessPattern.length : data.nodeCount()) * NODE_SIZE;

        // Estimate compression savings
        var compressed = compressNodeData(data);
        var compressionSaving = compressed.getSpaceSavings();
        savings.put("compression", compressionSaving * baselineBandwidth);

        // Estimate streaming savings
        var uniqueAccesses = accessPattern.length > 0 ?
            Arrays.stream(accessPattern).distinct().count() : data.nodeCount();
        var streamingSaving = accessPattern.length > 0 ?
            Math.max(0.0f, 1.0f - ((float) uniqueAccesses / accessPattern.length)) : 0.0f;
        savings.put("streaming", streamingSaving * baselineBandwidth * 0.3f);

        // Total savings
        var totalSaving = savings.values().stream()
            .map(Math::abs)
            .reduce(0.0f, Float::sum);
        savings.put("total", Math.min(totalSaving, baselineBandwidth * 0.8f));

        return savings;
    }

    private byte[] serializeNodes(ESVTNodeUnified[] nodes) {
        var output = new ByteArrayOutputStream();

        for (var node : nodes) {
            // Write 8-byte node data
            int childDescriptor = node.getChildDescriptor();
            int contourDescriptor = node.getContourDescriptor();

            // Write as little-endian
            output.write(childDescriptor & 0xFF);
            output.write((childDescriptor >> 8) & 0xFF);
            output.write((childDescriptor >> 16) & 0xFF);
            output.write((childDescriptor >> 24) & 0xFF);
            output.write(contourDescriptor & 0xFF);
            output.write((contourDescriptor >> 8) & 0xFF);
            output.write((contourDescriptor >> 16) & 0xFF);
            output.write((contourDescriptor >> 24) & 0xFF);
        }

        return output.toByteArray();
    }

    private ESVTNodeUnified[] deserializeNodes(byte[] data, int nodeCount) {
        var nodes = new ESVTNodeUnified[nodeCount];
        int offset = 0;

        for (int i = 0; i < nodeCount && offset + NODE_SIZE <= data.length; i++) {
            int childDescriptor = (data[offset] & 0xFF) |
                                  ((data[offset + 1] & 0xFF) << 8) |
                                  ((data[offset + 2] & 0xFF) << 16) |
                                  ((data[offset + 3] & 0xFF) << 24);
            int contourDescriptor = (data[offset + 4] & 0xFF) |
                                    ((data[offset + 5] & 0xFF) << 8) |
                                    ((data[offset + 6] & 0xFF) << 16) |
                                    ((data[offset + 7] & 0xFF) << 24);

            nodes[i] = new ESVTNodeUnified(childDescriptor, contourDescriptor);
            offset += NODE_SIZE;
        }

        return nodes;
    }

    private long calculateCompressedBandwidth(Map<Integer, Integer> accessCounts) {
        long totalCompressedBytes = 0L;

        for (var entry : accessCounts.entrySet()) {
            var accessCount = entry.getValue();

            // First access requires full node transfer
            totalCompressedBytes += NODE_SIZE;

            // Subsequent accesses benefit from caching (reduced cost)
            if (accessCount > 1) {
                totalCompressedBytes += (accessCount - 1) * (NODE_SIZE / 4);
            }
        }

        return totalCompressedBytes;
    }

    private List<List<ESVTNodeUnified>> createStreamingGroups(ESVTNodeUnified[] nodes,
                                                              int nodesPerGroup) {
        var groups = new ArrayList<List<ESVTNodeUnified>>();

        for (int i = 0; i < nodes.length; i += nodesPerGroup) {
            var group = new ArrayList<ESVTNodeUnified>();
            for (int j = i; j < Math.min(i + nodesPerGroup, nodes.length); j++) {
                group.add(nodes[j]);
            }
            if (!group.isEmpty()) {
                groups.add(group);
            }
        }

        return groups;
    }
}
