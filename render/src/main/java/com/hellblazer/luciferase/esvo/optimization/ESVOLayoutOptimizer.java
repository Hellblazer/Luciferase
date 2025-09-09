package com.hellblazer.luciferase.esvo.optimization;

import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.esvo.core.ESVOOctreeNode;
import javax.vecmath.Vector3f;
import java.util.*;

/**
 * Layout optimization for ESVO octree data structures.
 * Optimizes spatial locality and cache-friendly data organization.
 */
public class ESVOLayoutOptimizer {
    
    public static class SpatialLocality {
        private final float averageDistance;
        private final float coherenceScore;
        private final int totalNodes;
        private final Map<String, Float> metrics;
        
        public SpatialLocality(float averageDistance, float coherenceScore, 
                             int totalNodes, Map<String, Float> metrics) {
            this.averageDistance = averageDistance;
            this.coherenceScore = coherenceScore;
            this.totalNodes = totalNodes;
            this.metrics = new HashMap<>(metrics);
        }
        
        public float getAverageDistance() { return averageDistance; }
        public float getCoherenceScore() { return coherenceScore; }
        public int getTotalNodes() { return totalNodes; }
        public Map<String, Float> getMetrics() { return Collections.unmodifiableMap(metrics); }
    }
    
    public static class LayoutStrategy {
        private final String strategyName;
        private final float expectedImprovement;
        private final Map<String, Object> parameters;
        
        public LayoutStrategy(String strategyName, float expectedImprovement,
                            Map<String, Object> parameters) {
            this.strategyName = strategyName;
            this.expectedImprovement = expectedImprovement;
            this.parameters = new HashMap<>(parameters);
        }
        
        public String getStrategyName() { return strategyName; }
        public float getExpectedImprovement() { return expectedImprovement; }
        public Map<String, Object> getParameters() { 
            return Collections.unmodifiableMap(parameters); 
        }
    }
    
    /**
     * Analyzes spatial locality of the current octree layout
     */
    public SpatialLocality analyzeSpatialLocality(ESVOOctreeData octreeData) {
        var nodeIndices = octreeData.getNodeIndices();
        if (nodeIndices.length < 2) {
            var metrics = Map.of("nodeCount", (float) nodeIndices.length);
            return new SpatialLocality(0.0f, 1.0f, nodeIndices.length, metrics);
        }
        
        // Calculate spatial positions for nodes based on their index
        var nodePositions = calculateNodePositions(octreeData);
        
        var totalDistance = 0.0f;
        var comparisons = 0;
        
        // Calculate average distance between consecutive nodes
        for (int i = 0; i < nodeIndices.length - 1; i++) {
            var currentIndex = nodeIndices[i];
            var nextIndex = nodeIndices[i + 1];
            
            var currentPos = nodePositions.get(currentIndex);
            var nextPos = nodePositions.get(nextIndex);
            
            if (currentPos != null && nextPos != null) {
                totalDistance += calculateDistance(currentPos, nextPos);
                comparisons++;
            }
        }
        
        var averageDistance = comparisons > 0 ? totalDistance / comparisons : 0.0f;
        
        // Calculate coherence score (lower distance = higher coherence)
        var coherenceScore = Math.max(0.0f, 1.0f - (averageDistance / 10.0f));
        
        // Calculate additional metrics
        var metrics = new HashMap<String, Float>();
        metrics.put("nodeCount", (float) nodeIndices.length);
        metrics.put("comparisons", (float) comparisons);
        metrics.put("maxDistance", getMaxDistance(nodePositions, nodeIndices));
        metrics.put("minDistance", getMinDistance(nodePositions, nodeIndices));
        
        return new SpatialLocality(averageDistance, coherenceScore, nodeIndices.length, metrics);
    }
    
    /**
     * Optimizes layout using breadth-first ordering for better cache locality
     */
    public ESVOOctreeData optimizeBreadthFirst(ESVOOctreeData originalData) {
        var nodeIndices = originalData.getNodeIndices();
        if (nodeIndices.length == 0) {
            return new ESVOOctreeData(originalData.getMaxSizeBytes());
        }
        
        // Build tree structure from existing data
        var nodeMap = new HashMap<Integer, ESVOOctreeNode>();
        var childrenMap = new HashMap<Integer, List<Integer>>();
        
        for (int index : nodeIndices) {
            var node = originalData.getNode(index);
            if (node != null) {
                nodeMap.put(index, node);
                childrenMap.put(index, findChildren(index, originalData));
            }
        }
        
        // Find root nodes (nodes without parents)
        var rootNodes = findRootNodes(nodeMap, childrenMap);
        
        // Perform breadth-first traversal to determine optimal ordering
        var optimizedOrder = new ArrayList<Integer>();
        var queue = new LinkedList<>(rootNodes);
        var visited = new HashSet<Integer>();
        
        while (!queue.isEmpty()) {
            var currentIndex = queue.poll();
            if (!visited.contains(currentIndex)) {
                visited.add(currentIndex);
                optimizedOrder.add(currentIndex);
                
                // Add children to queue
                var children = childrenMap.getOrDefault(currentIndex, List.of());
                queue.addAll(children);
            }
        }
        
        // Create new octree with optimized layout
        var optimizedData = new ESVOOctreeData(originalData.getMaxSizeBytes());
        
        // Map old indices to new indices based on optimized order
        var indexMapping = new HashMap<Integer, Integer>();
        for (int i = 0; i < optimizedOrder.size(); i++) {
            indexMapping.put(optimizedOrder.get(i), i);
        }
        
        // Copy nodes in optimized order
        for (int i = 0; i < optimizedOrder.size(); i++) {
            var originalIndex = optimizedOrder.get(i);
            var node = nodeMap.get(originalIndex);
            if (node != null) {
                optimizedData.setNode(i, node);
            }
        }
        
        return optimizedData;
    }
    
    /**
     * Optimizes layout using Z-order (Morton order) for spatial locality
     */
    public ESVOOctreeData optimizeZOrder(ESVOOctreeData originalData) {
        var nodeIndices = originalData.getNodeIndices();
        if (nodeIndices.length == 0) {
            return new ESVOOctreeData(originalData.getMaxSizeBytes());
        }
        
        // Calculate Morton codes for each node based on spatial position
        var mortonCodes = new ArrayList<MortonEntry>();
        
        for (int index : nodeIndices) {
            var node = originalData.getNode(index);
            if (node != null) {
                var position = calculateNodeSpatialPosition(index);
                var mortonCode = calculateMortonCode(position);
                mortonCodes.add(new MortonEntry(index, mortonCode, node));
            }
        }
        
        // Sort by Morton code for Z-order
        mortonCodes.sort(Comparator.comparing(MortonEntry::getMortonCode));
        
        // Create new octree with Z-ordered layout
        var optimizedData = new ESVOOctreeData(originalData.getMaxSizeBytes());
        
        for (int i = 0; i < mortonCodes.size(); i++) {
            var entry = mortonCodes.get(i);
            optimizedData.setNode(i, entry.getNode());
        }
        
        return optimizedData;
    }
    
    /**
     * Optimizes layout for specific access patterns
     */
    public ESVOOctreeData optimizeForAccessPattern(ESVOOctreeData originalData, 
                                                   int[] accessPattern) {
        if (accessPattern.length == 0) {
            return originalData;
        }
        
        // Count access frequencies
        var accessCounts = new HashMap<Integer, Integer>();
        for (int index : accessPattern) {
            accessCounts.merge(index, 1, Integer::sum);
        }
        
        // Create frequency-based ordering (most accessed first)
        var nodesByFrequency = new ArrayList<Map.Entry<Integer, Integer>>(accessCounts.entrySet());
        nodesByFrequency.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        
        // Create optimized layout
        var optimizedData = new ESVOOctreeData(originalData.getMaxSizeBytes());
        var newIndex = 0;
        
        // Place frequently accessed nodes first
        for (var entry : nodesByFrequency) {
            var originalIndex = entry.getKey();
            var node = originalData.getNode(originalIndex);
            if (node != null) {
                optimizedData.setNode(newIndex++, node);
            }
        }
        
        // Add remaining nodes
        var nodeIndices = originalData.getNodeIndices();
        for (int originalIndex : nodeIndices) {
            if (!accessCounts.containsKey(originalIndex)) {
                var node = originalData.getNode(originalIndex);
                if (node != null) {
                    optimizedData.setNode(newIndex++, node);
                }
            }
        }
        
        return optimizedData;
    }
    
    /**
     * Analyzes cache line utilization for the current layout
     */
    public Map<String, Float> analyzeCacheLineUtilization(ESVOOctreeData octreeData, 
                                                         int cacheLineSize) {
        var metrics = new HashMap<String, Float>();
        var nodeIndices = octreeData.getNodeIndices();
        
        if (nodeIndices.length == 0) {
            metrics.put("utilization", 0.0f);
            metrics.put("efficiency", 0.0f);
            return metrics;
        }
        
        // Assume each node takes a fixed amount of space
        var nodeSize = 16; // bytes (childMask + contour + data)
        var nodesPerCacheLine = Math.max(1, cacheLineSize / nodeSize);
        
        // Calculate how many cache lines are partially filled
        var totalCacheLines = (nodeIndices.length + nodesPerCacheLine - 1) / nodesPerCacheLine;
        var lastCacheLineNodes = nodeIndices.length % nodesPerCacheLine;
        var lastCacheLineUtilization = lastCacheLineNodes == 0 ? 1.0f : 
            (float) lastCacheLineNodes / nodesPerCacheLine;
        
        // Calculate overall utilization
        var totalUtilization = totalCacheLines > 1 ? 
            ((totalCacheLines - 1) + lastCacheLineUtilization) / totalCacheLines :
            lastCacheLineUtilization;
        
        metrics.put("utilization", totalUtilization);
        metrics.put("efficiency", totalUtilization);
        metrics.put("totalCacheLines", (float) totalCacheLines);
        metrics.put("nodesPerCacheLine", (float) nodesPerCacheLine);
        
        return metrics;
    }
    
    // Private helper classes and methods
    
    private static class MortonEntry {
        private final int originalIndex;
        private final long mortonCode;
        private final ESVOOctreeNode node;
        
        public MortonEntry(int originalIndex, long mortonCode, ESVOOctreeNode node) {
            this.originalIndex = originalIndex;
            this.mortonCode = mortonCode;
            this.node = node;
        }
        
        public int getOriginalIndex() { return originalIndex; }
        public long getMortonCode() { return mortonCode; }
        public ESVOOctreeNode getNode() { return node; }
    }
    
    private Map<Integer, Vector3f> calculateNodePositions(ESVOOctreeData octreeData) {
        var positions = new HashMap<Integer, Vector3f>();
        var nodeIndices = octreeData.getNodeIndices();
        
        for (int index : nodeIndices) {
            positions.put(index, calculateNodeSpatialPosition(index));
        }
        
        return positions;
    }
    
    private Vector3f calculateNodeSpatialPosition(int nodeIndex) {
        // Calculate 3D position from linear index (simplified mapping)
        // This assumes a regular 3D grid mapping
        var size = 32; // Assume 32x32x32 grid
        
        var x = nodeIndex % size;
        var y = (nodeIndex / size) % size;
        var z = nodeIndex / (size * size);
        
        return new Vector3f(x, y, z);
    }
    
    private List<Integer> findChildren(int parentIndex, ESVOOctreeData octreeData) {
        var children = new ArrayList<Integer>();
        var parentNode = octreeData.getNode(parentIndex);
        
        if (parentNode != null && parentNode.childMask != 0) {
            // Calculate child indices using CUDA reference sparse indexing
            for (int i = 0; i < 8; i++) {
                if ((parentNode.childMask & (1 << i)) != 0) {
                    // CUDA reference: parent_ptr + popcount(child_masks & ((1 << i) - 1))
                    int popCount = Integer.bitCount(parentNode.childMask & ((1 << i) - 1));
                    var childIndex = parentNode.farPointer + popCount;
                    if (octreeData.hasNode(childIndex)) {
                        children.add(childIndex);
                    }
                }
            }
        }
        
        return children;
    }
    
    private List<Integer> findRootNodes(Map<Integer, ESVOOctreeNode> nodeMap,
                                       Map<Integer, List<Integer>> childrenMap) {
        var allNodes = new HashSet<>(nodeMap.keySet());
        var childNodes = new HashSet<Integer>();
        
        // Collect all child nodes
        for (var children : childrenMap.values()) {
            childNodes.addAll(children);
        }
        
        // Root nodes are those that are not children of any other node
        allNodes.removeAll(childNodes);
        
        return new ArrayList<>(allNodes);
    }
    
    private long calculateMortonCode(Vector3f position) {
        // Convert position to integer coordinates
        var x = (int) (position.x * 1000) & 0x3FF; // 10 bits
        var y = (int) (position.y * 1000) & 0x3FF; // 10 bits
        var z = (int) (position.z * 1000) & 0x3FF; // 10 bits
        
        // Interleave bits to create Morton code
        return interleaveBits(x, y, z);
    }
    
    private long interleaveBits(int x, int y, int z) {
        long result = 0;
        
        for (int i = 0; i < 10; i++) {
            result |= ((long) (x & (1 << i))) << (2 * i);
            result |= ((long) (y & (1 << i))) << (2 * i + 1);
            result |= ((long) (z & (1 << i))) << (2 * i + 2);
        }
        
        return result;
    }
    
    private float getMaxDistance(Map<Integer, Vector3f> positions, int[] nodeIndices) {
        var maxDistance = 0.0f;
        
        for (int i = 0; i < nodeIndices.length - 1; i++) {
            var pos1 = positions.get(nodeIndices[i]);
            var pos2 = positions.get(nodeIndices[i + 1]);
            
            if (pos1 != null && pos2 != null) {
                var distance = calculateDistance(pos1, pos2);
                maxDistance = Math.max(maxDistance, distance);
            }
        }
        
        return maxDistance;
    }
    
    private float getMinDistance(Map<Integer, Vector3f> positions, int[] nodeIndices) {
        var minDistance = Float.MAX_VALUE;
        
        for (int i = 0; i < nodeIndices.length - 1; i++) {
            var pos1 = positions.get(nodeIndices[i]);
            var pos2 = positions.get(nodeIndices[i + 1]);
            
            if (pos1 != null && pos2 != null) {
                var distance = calculateDistance(pos1, pos2);
                minDistance = Math.min(minDistance, distance);
            }
        }
        
        return minDistance == Float.MAX_VALUE ? 0.0f : minDistance;
    }
    
    private float calculateDistance(Vector3f pos1, Vector3f pos2) {
        var dx = pos1.x - pos2.x;
        var dy = pos1.y - pos2.y;
        var dz = pos1.z - pos2.z;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}