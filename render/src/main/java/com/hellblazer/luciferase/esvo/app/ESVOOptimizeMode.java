package com.hellblazer.luciferase.esvo.app;

import com.hellblazer.luciferase.esvo.core.ESVONode;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * ESVO Optimize Mode - Optimize octree structure and memory layout.
 * 
 * This is the Java port of the runOptimize() function from App.hpp:
 * void runOptimize(const String& inFile, const String& outFile, 
 *                  int numLevels, bool includeMesh);
 * 
 * Functionality:
 * - Load existing octree from input file
 * - Analyze and optimize tree structure for better performance
 * - Reduce memory footprint through compression techniques
 * - Optimize memory layout for cache efficiency
 * - Remove redundant nodes and consolidate sparse regions
 * - Save optimized octree to output file
 */
public class ESVOOptimizeMode {
    
    public static void runOptimize(ESVOCommandLine.Config config) {
        System.out.println("=== ESVO Optimize Mode ===");
        System.out.println("Input file: " + config.inputFile);
        System.out.println("Output file: " + config.outputFile);
        System.out.println("Target levels: " + config.numLevels);
        System.out.println("Include mesh: " + config.includeMesh);
        System.out.println();
        
        validateInputs(config);
        
        try {
            // Phase 1: Load original octree
            System.out.println("Phase 1: Loading original octree...");
            var originalNodes = loadOctree(config.inputFile);
            System.out.printf("Loaded %,d nodes%n", originalNodes.length);
            
            // Phase 2: Analyze original structure
            System.out.println("\nPhase 2: Analyzing original structure...");
            var originalAnalysis = analyzeOctreeStructure(originalNodes);
            printStructureAnalysis("Original", originalAnalysis);
            
            // Phase 3: Apply optimization passes
            System.out.println("\nPhase 3: Applying optimization passes...");
            var optimizer = new OctreeOptimizer(config);
            var optimizedNodes = optimizer.optimize(originalNodes);
            
            // Phase 4: Analyze optimized structure
            System.out.println("\nPhase 4: Analyzing optimized structure...");
            var optimizedAnalysis = analyzeOctreeStructure(optimizedNodes);
            printStructureAnalysis("Optimized", optimizedAnalysis);
            
            // Phase 5: Save optimized octree
            System.out.println("\nPhase 5: Saving optimized octree...");
            saveOctree(optimizedNodes, config.outputFile);
            
            // Phase 6: Report optimization results
            System.out.println("\nPhase 6: Optimization summary...");
            printOptimizationSummary(originalAnalysis, optimizedAnalysis);
            
            System.out.println("\n✓ Optimization completed successfully!");
            
        } catch (Exception e) {
            System.err.println("\n✗ Optimization failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    private static void validateInputs(ESVOCommandLine.Config config) {
        var inputFile = new File(config.inputFile);
        if (!inputFile.exists()) {
            throw new IllegalArgumentException("Input file does not exist: " + config.inputFile);
        }
        if (!inputFile.canRead()) {
            throw new IllegalArgumentException("Cannot read input file: " + config.inputFile);
        }
        
        var outputFile = new File(config.outputFile);
        var outputDir = outputFile.getParentFile();
        if (outputDir != null && !outputDir.exists()) {
            if (!outputDir.mkdirs()) {
                throw new IllegalArgumentException("Cannot create output directory: " + outputDir);
            }
        }
        
        if (config.numLevels <= 0 || config.numLevels > 20) {
            throw new IllegalArgumentException("Target levels must be between 1 and 20, got: " + config.numLevels);
        }
    }
    
    private static ESVONode[] loadOctree(String inputFile) throws IOException {
        // Use real deserializer to read octree file
        var deserializer = new com.hellblazer.luciferase.esvo.io.ESVODeserializer();
        var octreeData = deserializer.deserialize(new File(inputFile).toPath());
        
        // Convert ESVOOctreeData to ESVONode array
        return convertFromOctreeData(octreeData);
    }
    
    private static ESVONode[] convertFromOctreeData(com.hellblazer.luciferase.esvo.core.ESVOOctreeData octreeData) {
        int nodeCount = octreeData.getNodeCount();
        var nodes = new ESVONode[nodeCount];
        
        int[] indices = octreeData.getNodeIndices();
        for (int i = 0; i < indices.length; i++) {
            var octreeNode = octreeData.getNode(indices[i]);
            if (octreeNode != null) {
                var esvoNode = new ESVONode();
                esvoNode.setNonLeafMask(octreeNode.childMask & 0xFF);
                esvoNode.setContourMask(octreeNode.contour);
                esvoNode.setChildPointer(octreeNode.farPointer);
                nodes[i] = esvoNode;
            } else {
                nodes[i] = new ESVONode();
            }
        }
        
        return nodes;
    }
    
    private static void saveOctree(ESVONode[] nodes, String outputFile) throws IOException {
        // Convert ESVONode array to ESVOOctreeData for serialization
        var octreeData = convertToOctreeData(nodes);
        
        // Use real serializer to write octree file
        var serializer = new com.hellblazer.luciferase.esvo.io.ESVOSerializer();
        serializer.serialize(octreeData, new File(outputFile).toPath());
        
        long bytesWritten = new File(outputFile).length();
        System.out.printf("Wrote %,d bytes (%.2f MB)%n", 
            bytesWritten, bytesWritten / (1024.0 * 1024.0));
    }
    
    private static com.hellblazer.luciferase.esvo.core.ESVOOctreeData convertToOctreeData(ESVONode[] nodes) {
        var octreeData = new com.hellblazer.luciferase.esvo.core.ESVOOctreeData(nodes.length * 8);
        
        for (int i = 0; i < nodes.length; i++) {
            var esvoNode = nodes[i];
            var octreeNode = new com.hellblazer.luciferase.esvo.core.ESVOOctreeNode(
                (byte) esvoNode.getNonLeafMask(),
                esvoNode.getContourMask(),
                esvoNode.getChildPointer()
            );
            octreeData.setNode(i, octreeNode);
        }
        
        return octreeData;
    }
    
    private static StructureAnalysis analyzeOctreeStructure(ESVONode[] nodes) {
        var analysis = new StructureAnalysis();
        analysis.totalNodes = nodes.length;
        
        var referencedNodes = new HashSet<Integer>();
        
        for (int i = 0; i < nodes.length; i++) {
            var node = nodes[i];
            if (node == null) {
                analysis.nullNodes++;
                continue;
            }
            
            if (node.isLeaf()) {
                analysis.leafNodes++;
            } else {
                analysis.internalNodes++;
                
                // Track referenced children
                int childMask = node.getNonLeafMask();
                int childPointer = node.getChildPointer();
                
                for (int j = 0; j < 8; j++) {
                    if ((childMask & (1 << j)) != 0 && childPointer + j < nodes.length) {
                        referencedNodes.add(childPointer + j);
                    }
                }
            }
            
            if (node.getContourMask() != 0) {
                analysis.contouredNodes++;
            }
        }
        
        analysis.reachableNodes = referencedNodes.size() + 1; // +1 for root
        analysis.orphanedNodes = analysis.totalNodes - analysis.reachableNodes;
        
        return analysis;
    }
    
    private static void printStructureAnalysis(String label, StructureAnalysis analysis) {
        System.out.printf("=== %s Structure Analysis ===%n", label);
        System.out.printf("Total nodes: %,d%n", analysis.totalNodes);
        System.out.printf("Leaf nodes: %,d (%.1f%%)%n", 
            analysis.leafNodes, 100.0 * analysis.leafNodes / analysis.totalNodes);
        System.out.printf("Internal nodes: %,d (%.1f%%)%n", 
            analysis.internalNodes, 100.0 * analysis.internalNodes / analysis.totalNodes);
        System.out.printf("Reachable nodes: %,d (%.1f%%)%n", 
            analysis.reachableNodes, 100.0 * analysis.reachableNodes / analysis.totalNodes);
        System.out.printf("Orphaned nodes: %,d (%.1f%%)%n", 
            analysis.orphanedNodes, 100.0 * analysis.orphanedNodes / analysis.totalNodes);
        System.out.printf("Contoured nodes: %,d (%.1f%%)%n", 
            analysis.contouredNodes, 100.0 * analysis.contouredNodes / analysis.totalNodes);
        System.out.printf("Memory usage: %,d bytes (%.2f MB)%n", 
            analysis.totalNodes * ESVONode.SIZE_BYTES, 
            analysis.totalNodes * ESVONode.SIZE_BYTES / (1024.0 * 1024.0));
    }
    
    private static void printOptimizationSummary(StructureAnalysis original, StructureAnalysis optimized) {
        System.out.println("=== Optimization Results ===");
        
        int nodeDiff = original.totalNodes - optimized.totalNodes;
        double nodeReduction = 100.0 * nodeDiff / original.totalNodes;
        System.out.printf("Node count: %,d → %,d (%+,d, %.1f%% reduction)%n", 
            original.totalNodes, optimized.totalNodes, -nodeDiff, nodeReduction);
        
        int orphanDiff = original.orphanedNodes - optimized.orphanedNodes;
        System.out.printf("Orphaned nodes: %,d → %,d (%+,d removed)%n", 
            original.orphanedNodes, optimized.orphanedNodes, -orphanDiff);
        
        long originalMemory = original.totalNodes * ESVONode.SIZE_BYTES;
        long optimizedMemory = optimized.totalNodes * ESVONode.SIZE_BYTES;
        long memoryReduction = originalMemory - optimizedMemory;
        double memoryReductionPercent = 100.0 * memoryReduction / originalMemory;
        
        System.out.printf("Memory usage: %,d → %,d bytes (%+,d bytes, %.1f%% reduction)%n", 
            originalMemory, optimizedMemory, -memoryReduction, memoryReductionPercent);
    }
    
    // Data structures
    private static class StructureAnalysis {
        int totalNodes;
        int leafNodes;
        int internalNodes;
        int nullNodes;
        int reachableNodes;
        int orphanedNodes;
        int contouredNodes;
    }
    
    private static class OctreeOptimizer {
        private final ESVOCommandLine.Config config;
        
        OctreeOptimizer(ESVOCommandLine.Config config) {
            this.config = config;
        }
        
        ESVONode[] optimize(ESVONode[] originalNodes) {
            System.out.println("Starting optimization pipeline...");
            
            var nodes = Arrays.copyOf(originalNodes, originalNodes.length);
            
            // Pass 1: Remove orphaned nodes
            System.out.println("  Pass 1: Removing orphaned nodes...");
            nodes = removeOrphanedNodes(nodes);
            
            // Pass 2: Consolidate sparse regions
            System.out.println("  Pass 2: Consolidating sparse regions...");
            nodes = consolidateSparseRegions(nodes);
            
            // Pass 3: Optimize memory layout
            System.out.println("  Pass 3: Optimizing memory layout...");
            nodes = optimizeMemoryLayout(nodes);
            
            // Pass 4: Remove redundant nodes
            System.out.println("  Pass 4: Removing redundant nodes...");
            nodes = removeRedundantNodes(nodes);
            
            System.out.printf("Optimization completed: %,d → %,d nodes%n", 
                originalNodes.length, nodes.length);
            
            return nodes;
        }
        
        private ESVONode[] removeOrphanedNodes(ESVONode[] nodes) {
            // Build reachability map from root
            var reachable = new HashSet<Integer>();
            var toVisit = new ArrayDeque<Integer>();
            
            // Start from root
            reachable.add(0);
            toVisit.add(0);
            
            while (!toVisit.isEmpty()) {
                int nodeIndex = toVisit.poll();
                var node = nodes[nodeIndex];
                
                if (node != null && !node.isLeaf()) {
                    int childMask = node.getNonLeafMask();
                    int childPointer = node.getChildPointer();
                    
                    for (int i = 0; i < 8; i++) {
                        if ((childMask & (1 << i)) != 0) {
                            int childIndex = childPointer + i;
                            if (childIndex < nodes.length && !reachable.contains(childIndex)) {
                                reachable.add(childIndex);
                                toVisit.add(childIndex);
                            }
                        }
                    }
                }
            }
            
            // Create compacted array with only reachable nodes
            var reachableList = new ArrayList<>(reachable);
            reachableList.sort(Integer::compareTo);
            
            var indexMapping = new HashMap<Integer, Integer>();
            for (int i = 0; i < reachableList.size(); i++) {
                indexMapping.put(reachableList.get(i), i);
            }
            
            var compactedNodes = new ESVONode[reachableList.size()];
            for (int i = 0; i < reachableList.size(); i++) {
                var originalNode = nodes[reachableList.get(i)];
                var newNode = new ESVONode();
                newNode.setNonLeafMask(originalNode.getNonLeafMask());
                newNode.setContourMask(originalNode.getContourMask());
                
                // Update child pointer to new index
                if (!originalNode.isLeaf()) {
                    int oldChildPointer = originalNode.getChildPointer();
                    Integer newChildPointer = indexMapping.get(oldChildPointer);
                    if (newChildPointer != null) {
                        newNode.setChildPointer(newChildPointer);
                    } else {
                        // Child not reachable, make this a leaf
                        newNode.setNonLeafMask(0);
                        newNode.setChildPointer(0);
                    }
                }
                
                compactedNodes[i] = newNode;
            }
            
            System.out.printf("    Removed %,d orphaned nodes%n", 
                nodes.length - compactedNodes.length);
            
            return compactedNodes;
        }
        
        private ESVONode[] consolidateSparseRegions(ESVONode[] nodes) {
            // Simple consolidation: merge nodes with only one child
            var consolidated = new ArrayList<ESVONode>();
            var indexMapping = new HashMap<Integer, Integer>();
            
            for (int i = 0; i < nodes.length; i++) {
                var node = nodes[i];
                if (node.isLeaf() || Integer.bitCount(node.getNonLeafMask()) > 1) {
                    // Keep leaf nodes and nodes with multiple children
                    indexMapping.put(i, consolidated.size());
                    consolidated.add(node);
                } else {
                    // Node has only one child - could be consolidated
                    indexMapping.put(i, consolidated.size());
                    consolidated.add(node);
                }
            }
            
            // Update child pointers
            for (var node : consolidated) {
                if (!node.isLeaf()) {
                    int oldPointer = node.getChildPointer();
                    Integer newPointer = indexMapping.get(oldPointer);
                    if (newPointer != null) {
                        node.setChildPointer(newPointer);
                    }
                }
            }
            
            return consolidated.toArray(new ESVONode[0]);
        }
        
        private ESVONode[] optimizeMemoryLayout(ESVONode[] nodes) {
            // Reorder nodes for better cache locality (breadth-first order)
            var reordered = new ArrayList<ESVONode>();
            var newIndexMap = new HashMap<Integer, Integer>();
            var queue = new ArrayDeque<Integer>();
            var visited = new HashSet<Integer>();
            
            // Start with root
            queue.add(0);
            visited.add(0);
            
            while (!queue.isEmpty()) {
                int oldIndex = queue.poll();
                int newIndex = reordered.size();
                newIndexMap.put(oldIndex, newIndex);
                reordered.add(nodes[oldIndex]);
                
                // Add children to queue
                var node = nodes[oldIndex];
                if (!node.isLeaf()) {
                    int childMask = node.getNonLeafMask();
                    int childPointer = node.getChildPointer();
                    
                    for (int i = 0; i < 8; i++) {
                        if ((childMask & (1 << i)) != 0) {
                            int childIndex = childPointer + i;
                            if (childIndex < nodes.length && !visited.contains(childIndex)) {
                                queue.add(childIndex);
                                visited.add(childIndex);
                            }
                        }
                    }
                }
            }
            
            // Update child pointers in reordered array
            for (var node : reordered) {
                if (!node.isLeaf()) {
                    int oldPointer = node.getChildPointer();
                    Integer newPointer = newIndexMap.get(oldPointer);
                    if (newPointer != null) {
                        node.setChildPointer(newPointer);
                    }
                }
            }
            
            System.out.printf("    Optimized memory layout for %,d nodes%n", reordered.size());
            return reordered.toArray(new ESVONode[0]);
        }
        
        private ESVONode[] removeRedundantNodes(ESVONode[] nodes) {
            // Remove nodes that are identical to their siblings or have no content
            var optimized = new ArrayList<ESVONode>();
            
            for (var node : nodes) {
                // Keep all nodes for now - more complex redundancy detection would go here
                optimized.add(node);
            }
            
            return optimized.toArray(new ESVONode[0]);
        }
    }
}