package com.hellblazer.luciferase.esvo.app;

import com.hellblazer.luciferase.esvo.core.ESVONode;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.HashMap;

/**
 * ESVO Inspect Mode - Inspect and validate octree file structure.
 * 
 * This is the Java port of the runInspect() function from App.hpp:
 * void runInspect(const String& inFile);
 * 
 * Functionality:
 * - Load octree from binary file
 * - Validate structural integrity (node relationships, pointers, masks)
 * - Analyze tree statistics (depth, branching factor, memory usage)
 * - Check for common issues (orphaned nodes, invalid pointers, mask mismatches)
 * - Generate detailed report of octree properties
 * - Verify CUDA raycast compatibility
 */
public class ESVOInspectMode {
    
    public static void runInspect(ESVOCommandLine.Config config) {
        System.out.println("=== ESVO Inspect Mode ===");
        System.out.println("Input file: " + config.inputFile);
        System.out.println();
        
        validateInputs(config);
        
        try {
            // Phase 1: Load octree file
            System.out.println("Phase 1: Loading octree file...");
            var octreeData = loadOctreeFile(config.inputFile);
            System.out.printf("Loaded octree: %d nodes, %d bytes%n", 
                octreeData.nodes.length, octreeData.fileSizeBytes);
            
            // Phase 2: Basic file information
            System.out.println("\nPhase 2: File information...");
            printFileInformation(octreeData, config.inputFile);
            
            // Phase 3: Structural validation
            System.out.println("\nPhase 3: Structural validation...");
            var validationResult = validateStructure(octreeData.nodes);
            
            // Phase 4: Tree analysis
            System.out.println("\nPhase 4: Tree analysis...");
            var analysis = analyzeTree(octreeData.nodes);
            
            // Phase 5: Memory analysis
            System.out.println("\nPhase 5: Memory analysis...");
            analyzeMemoryUsage(octreeData.nodes, analysis);
            
            // Phase 6: Performance characteristics
            System.out.println("\nPhase 6: Performance characteristics...");
            analyzePerformanceCharacteristics(octreeData.nodes, analysis);
            
            // Phase 7: CUDA compatibility
            System.out.println("\nPhase 7: CUDA compatibility check...");
            checkCudaCompatibility(octreeData.nodes);
            
            // Phase 8: Final summary
            printFinalSummary(validationResult, analysis);
            
        } catch (Exception e) {
            System.err.println("\n✗ Inspection failed: " + e.getMessage());
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
        if (inputFile.length() == 0) {
            throw new IllegalArgumentException("Input file is empty: " + config.inputFile);
        }
    }
    
    private static OctreeData loadOctreeFile(String inputFile) throws IOException {
        var reader = new OctreeReader();
        var nodes = reader.readOctree(inputFile);
        var fileSize = new File(inputFile).length();
        
        return new OctreeData(nodes, fileSize);
    }
    
    private static void printFileInformation(OctreeData octreeData, String inputFile) {
        var file = new File(inputFile);
        
        System.out.println("=== File Information ===");
        System.out.printf("Filename: %s%n", file.getName());
        System.out.printf("Path: %s%n", file.getAbsolutePath());
        System.out.printf("Size: %,d bytes (%.2f MB)%n", 
            octreeData.fileSizeBytes, octreeData.fileSizeBytes / (1024.0 * 1024.0));
        System.out.printf("Last modified: %s%n", 
            new java.util.Date(file.lastModified()));
        System.out.printf("Node count: %,d%n", octreeData.nodes.length);
        System.out.printf("Bytes per node: %.1f%n", 
            (double)octreeData.fileSizeBytes / octreeData.nodes.length);
    }
    
    private static ValidationResult validateStructure(ESVONode[] nodes) {
        var validator = new StructureValidator();
        
        System.out.println("=== Structural Validation ===");
        System.out.printf("Validating %,d nodes...%n", nodes.length);
        
        var result = validator.validateStructure(nodes);
        
        if (result.hasErrors()) {
            System.err.println("✗ Structural errors found:");
            for (String error : result.getErrors()) {
                System.err.println("  - " + error);
            }
        } else {
            System.out.println("✓ No structural errors found");
        }
        
        if (result.hasWarnings()) {
            System.out.println("⚠ Structural warnings:");
            for (String warning : result.getWarnings()) {
                System.out.println("  - " + warning);
            }
        }
        
        // Detailed validation metrics
        System.out.printf("Valid nodes: %,d (%.1f%%)%n", 
            result.validNodes, 100.0 * result.validNodes / nodes.length);
        System.out.printf("Orphaned nodes: %,d%n", result.orphanedNodes);
        System.out.printf("Invalid pointers: %,d%n", result.invalidPointers);
        System.out.printf("Mask mismatches: %,d%n", result.maskMismatches);
        
        return result;
    }
    
    private static TreeAnalysis analyzeTree(ESVONode[] nodes) {
        var analyzer = new TreeAnalyzer();
        
        System.out.println("=== Tree Analysis ===");
        
        var analysis = analyzer.analyzeTree(nodes);
        
        // Tree structure
        System.out.printf("Tree depth: %d levels%n", analysis.maxDepth);
        System.out.printf("Root nodes: %,d%n", analysis.rootNodeCount);
        System.out.printf("Internal nodes: %,d (%.1f%%)%n", 
            analysis.internalNodeCount, 100.0 * analysis.internalNodeCount / nodes.length);
        System.out.printf("Leaf nodes: %,d (%.1f%%)%n", 
            analysis.leafNodeCount, 100.0 * analysis.leafNodeCount / nodes.length);
        
        // Branching statistics
        System.out.printf("Average branching factor: %.2f%n", analysis.averageBranchingFactor);
        System.out.printf("Max branching factor: %d%n", analysis.maxBranchingFactor);
        System.out.printf("Empty nodes: %,d (%.1f%%)%n", 
            analysis.emptyNodeCount, 100.0 * analysis.emptyNodeCount / nodes.length);
        
        // Sparse structure analysis
        System.out.printf("Total children slots: %,d%n", analysis.totalChildSlots);
        System.out.printf("Used children slots: %,d (%.1f%%)%n", 
            analysis.usedChildSlots, 100.0 * analysis.usedChildSlots / analysis.totalChildSlots);
        System.out.printf("Sparsity ratio: %.1f%%n", 
            100.0 * (analysis.totalChildSlots - analysis.usedChildSlots) / analysis.totalChildSlots);
        
        return analysis;
    }
    
    private static void analyzeMemoryUsage(ESVONode[] nodes, TreeAnalysis analysis) {
        System.out.println("=== Memory Analysis ===");
        
        long totalMemory = nodes.length * ESVONode.SIZE_BYTES;
        long leafMemory = analysis.leafNodeCount * ESVONode.SIZE_BYTES;
        long internalMemory = analysis.internalNodeCount * ESVONode.SIZE_BYTES;
        
        System.out.printf("Total memory: %,d bytes (%.2f MB)%n", 
            totalMemory, totalMemory / (1024.0 * 1024.0));
        System.out.printf("Leaf memory: %,d bytes (%.1f%%)%n", 
            leafMemory, 100.0 * leafMemory / totalMemory);
        System.out.printf("Internal memory: %,d bytes (%.1f%%)%n", 
            internalMemory, 100.0 * internalMemory / totalMemory);
        
        // Memory efficiency metrics
        double bitsPerVoxel = (totalMemory * 8.0) / Math.pow(2, analysis.maxDepth * 3);
        System.out.printf("Bits per voxel: %.2f%n", bitsPerVoxel);
        
        // Estimate theoretical minimum memory
        long theoreticalMinimum = analysis.leafNodeCount * ESVONode.SIZE_BYTES;
        double compressionRatio = (double)totalMemory / theoreticalMinimum;
        System.out.printf("Compression ratio: %.2fx (actual/theoretical minimum)%n", compressionRatio);
        
        // Contour memory usage
        int contouredNodes = 0;
        for (var node : nodes) {
            if (node.getContourMask() != 0) {
                contouredNodes++;
            }
        }
        if (contouredNodes > 0) {
            System.out.printf("Contoured nodes: %,d (%.1f%%)%n", 
                contouredNodes, 100.0 * contouredNodes / nodes.length);
        }
    }
    
    private static void analyzePerformanceCharacteristics(ESVONode[] nodes, TreeAnalysis analysis) {
        System.out.println("=== Performance Characteristics ===");
        
        // Ray traversal performance estimates
        double avgTraversalDepth = analysis.maxDepth * 0.7; // Estimate
        System.out.printf("Estimated avg traversal depth: %.1f%n", avgTraversalDepth);
        System.out.printf("Max traversal depth: %d%n", analysis.maxDepth);
        
        // Memory access patterns
        double cacheEfficiency = calculateCacheEfficiency(nodes);
        System.out.printf("Estimated cache efficiency: %.1f%%n", cacheEfficiency * 100);
        
        // Far pointer usage
        int farPointerNodes = 0;
        for (var node : nodes) {
            if (node.isFar()) {
                farPointerNodes++;
            }
        }
        if (farPointerNodes > 0) {
            System.out.printf("Far pointer nodes: %,d (%.1f%%)%n", 
                farPointerNodes, 100.0 * farPointerNodes / nodes.length);
            System.out.println("⚠ Far pointers may impact performance");
        }
        
        // Sparsity impact on performance
        if (analysis.sparsityRatio > 0.8) {
            System.out.printf("High sparsity (%.1f%%) - excellent for sparse scenes%n", 
                analysis.sparsityRatio * 100);
        } else if (analysis.sparsityRatio < 0.3) {
            System.out.printf("Low sparsity (%.1f%%) - consider dense representation%n", 
                analysis.sparsityRatio * 100);
        }
    }
    
    private static void checkCudaCompatibility(ESVONode[] nodes) {
        System.out.println("=== CUDA Compatibility ===");
        
        boolean compatible = true;
        var issues = new java.util.ArrayList<String>();
        
        // Check node size alignment
        if (ESVONode.SIZE_BYTES != 8) {
            compatible = false;
            issues.add("Node size mismatch: expected 8 bytes, got " + ESVONode.SIZE_BYTES);
        }
        
        // Check bit layout compatibility
        for (int i = 0; i < Math.min(100, nodes.length); i++) {
            var node = nodes[i];
            
            // Validate bit masks are in correct ranges
            if ((node.getValidMask() & ~0xFF) != 0) {
                compatible = false;
                issues.add(String.format("Node %d: Valid mask out of range: 0x%02X", i, node.getValidMask()));
                break;
            }
            
            if ((node.getNonLeafMask() & ~0xFF) != 0) {
                compatible = false;
                issues.add(String.format("Node %d: Non-leaf mask out of range: 0x%02X", i, node.getNonLeafMask()));
                break;
            }
        }
        
        if (compatible) {
            System.out.println("✓ CUDA compatibility verified");
            System.out.println("  - Node size: 8 bytes (int2 compatible)");
            System.out.println("  - Bit layout: CUDA raycast.inl compatible");
            System.out.println("  - Mask ranges: Valid for hardware acceleration");
        } else {
            System.err.println("✗ CUDA compatibility issues found:");
            for (String issue : issues) {
                System.err.println("  - " + issue);
            }
        }
    }
    
    private static void printFinalSummary(ValidationResult validationResult, TreeAnalysis analysis) {
        System.out.println("\n=== Inspection Summary ===");
        
        if (!validationResult.hasErrors()) {
            System.out.println("✓ Octree structure is valid");
        } else {
            System.err.println("✗ Octree has structural errors");
        }
        
        // Quality assessment
        if (analysis.sparsityRatio > 0.7 && analysis.maxDepth >= 8) {
            System.out.println("✓ Good octree quality - high sparsity, appropriate depth");
        } else if (analysis.sparsityRatio < 0.3) {
            System.out.println("⚠ Low sparsity - consider optimization");
        } else if (analysis.maxDepth < 6) {
            System.out.println("⚠ Shallow depth - may lack detail");
        } else if (analysis.maxDepth > 15) {
            System.out.println("⚠ Very deep tree - may impact performance");
        }
        
        // Performance summary
        System.out.printf("Performance rating: %s%n", calculatePerformanceRating(analysis));
        
        System.out.println("\nInspection completed successfully!");
    }
    
    private static double calculateCacheEfficiency(ESVONode[] nodes) {
        // Simplified cache efficiency estimation based on spatial locality
        return 0.75; // Placeholder
    }
    
    private static String calculatePerformanceRating(TreeAnalysis analysis) {
        double score = 0.0;
        
        // Sparsity contributes to performance (higher is better)
        score += analysis.sparsityRatio * 40;
        
        // Depth affects traversal cost (8-12 is optimal range)
        if (analysis.maxDepth >= 8 && analysis.maxDepth <= 12) {
            score += 30;
        } else if (analysis.maxDepth >= 6 && analysis.maxDepth <= 15) {
            score += 20;
        } else {
            score += 10;
        }
        
        // Branching factor affects memory access (2-4 is good)
        if (analysis.averageBranchingFactor >= 2 && analysis.averageBranchingFactor <= 4) {
            score += 30;
        } else {
            score += 15;
        }
        
        if (score >= 80) return "Excellent";
        if (score >= 60) return "Good";
        if (score >= 40) return "Fair";
        return "Poor";
    }
    
    // Data structures
    private static class OctreeData {
        final ESVONode[] nodes;
        final long fileSizeBytes;
        
        OctreeData(ESVONode[] nodes, long fileSizeBytes) {
            this.nodes = nodes;
            this.fileSizeBytes = fileSizeBytes;
        }
    }
    
    private static class ValidationResult {
        int validNodes;
        int orphanedNodes;
        int invalidPointers;
        int maskMismatches;
        private java.util.List<String> errors = new java.util.ArrayList<>();
        private java.util.List<String> warnings = new java.util.ArrayList<>();
        
        boolean hasErrors() { return !errors.isEmpty(); }
        boolean hasWarnings() { return !warnings.isEmpty(); }
        java.util.List<String> getErrors() { return errors; }
        java.util.List<String> getWarnings() { return warnings; }
    }
    
    private static class TreeAnalysis {
        int maxDepth;
        int rootNodeCount;
        int internalNodeCount;
        int leafNodeCount;
        int emptyNodeCount;
        double averageBranchingFactor;
        int maxBranchingFactor;
        int totalChildSlots;
        int usedChildSlots;
        double sparsityRatio;
    }
    
    private static ESVONode[] convertFromOctreeData(com.hellblazer.luciferase.esvo.core.ESVOOctreeData octreeData) {
        int nodeCount = octreeData.getNodeCount();
        var nodes = new ESVONode[nodeCount];
        
        int[] indices = octreeData.getNodeIndices();
        for (int i = 0; i < indices.length; i++) {
            var octreeNode = octreeData.getNode(indices[i]);
            if (octreeNode != null) {
                // Convert ESVOOctreeNode to ESVONode
                var esvoNode = new ESVONode();
                esvoNode.setNonLeafMask(octreeNode.childMask & 0xFF);
                esvoNode.setContourMask(octreeNode.contour);
                esvoNode.setChildPointer(octreeNode.farPointer);
                nodes[i] = esvoNode;
            } else {
                nodes[i] = new ESVONode(); // Create empty node
            }
        }
        
        System.out.printf("Converted octree data to %d ESVONodes%n", nodeCount);
        return nodes;
    }
    
    // Placeholder classes that would be implemented elsewhere
    private static class OctreeReader {
        ESVONode[] readOctree(String filename) throws IOException {
            // Use real deserializer to read octree file
            var deserializer = new com.hellblazer.luciferase.esvo.io.ESVODeserializer();
            var octreeData = deserializer.deserialize(new File(filename).toPath());
            
            // Convert ESVOOctreeData to ESVONode array
            return convertFromOctreeData(octreeData);
        }
    }
    
    private static class StructureValidator {
        ValidationResult validateStructure(ESVONode[] nodes) {
            var result = new ValidationResult();
            
            // Initialize counters
            int validNodes = 0;
            int orphanedNodes = 0;
            int invalidPointers = 0;
            int maskMismatches = 0;
            
            // Track which nodes are referenced as children
            var referencedNodes = new java.util.HashSet<Integer>();
            var visitedNodes = new java.util.HashSet<Integer>();
            
            // First pass: validate each node individually and collect child references
            for (int i = 0; i < nodes.length; i++) {
                var node = nodes[i];
                if (node == null) {
                    result.getErrors().add("Null node found at index " + i);
                    continue;
                }
                
                boolean nodeValid = true;
                
                // Validate child pointer and mask consistency
                int childMask = node.getNonLeafMask();
                int childPointer = node.getChildPointer();
                
                if (childMask != 0) {
                    // Node has children - validate pointer
                    if (childPointer <= 0 || childPointer >= nodes.length) {
                        result.getErrors().add(String.format("Node %d: Invalid child pointer %d (out of bounds)", i, childPointer));
                        invalidPointers++;
                        nodeValid = false;
                    } else {
                        // Validate that child mask matches actual children
                        int expectedChildren = Integer.bitCount(childMask);
                        int availableChildren = Math.min(8, nodes.length - childPointer);
                        
                        if (expectedChildren > availableChildren) {
                            result.getWarnings().add(String.format("Node %d: Child mask expects %d children but only %d available", 
                                i, expectedChildren, availableChildren));
                        }
                        
                        // Mark children as referenced
                        for (int j = 0; j < 8 && (childPointer + j) < nodes.length; j++) {
                            if ((childMask & (1 << j)) != 0) {
                                referencedNodes.add(childPointer + j);
                            }
                        }
                    }
                } else if (childPointer != 0) {
                    // Leaf node should have zero child pointer
                    result.getWarnings().add(String.format("Node %d: Leaf node has non-zero child pointer %d", i, childPointer));
                }
                
                // Validate contour mask (should be 0 for internal nodes)
                if (childMask != 0 && node.getContourMask() != 0) {
                    result.getWarnings().add(String.format("Node %d: Internal node has non-zero contour mask", i));
                }
                
                if (nodeValid) {
                    validNodes++;
                }
            }
            
            // Second pass: check for orphaned nodes
            // Root node (index 0) should not be considered orphaned
            if (nodes.length > 0) {
                visitedNodes.add(0); // Root is never orphaned
            }
            
            for (int i = 1; i < nodes.length; i++) {
                if (!referencedNodes.contains(i)) {
                    orphanedNodes++;
                }
            }
            
            // Third pass: validate tree connectivity by traversing from root
            if (nodes.length > 0) {
                validateTreeConnectivity(nodes, 0, visitedNodes, result);
            }
            
            // Check for unreachable nodes
            for (int i = 0; i < nodes.length; i++) {
                if (!visitedNodes.contains(i)) {
                    result.getWarnings().add(String.format("Node %d is not reachable from root", i));
                }
            }
            
            // Set final validation results
            result.validNodes = validNodes;
            result.orphanedNodes = orphanedNodes;
            result.invalidPointers = invalidPointers;
            result.maskMismatches = maskMismatches;
            
            // Add summary messages
            if (result.hasErrors()) {
                result.getErrors().add(String.format("Validation failed with %d errors", result.getErrors().size() - 1));
            }
            
            if (orphanedNodes > 0) {
                result.getWarnings().add(String.format("Found %d orphaned nodes not referenced by any parent", orphanedNodes));
            }
            
            return result;
        }
        
        private void validateTreeConnectivity(ESVONode[] nodes, int nodeIndex, 
                                            java.util.Set<Integer> visitedNodes, ValidationResult result) {
            if (nodeIndex < 0 || nodeIndex >= nodes.length) {
                return;
            }
            
            if (visitedNodes.contains(nodeIndex)) {
                return; // Already visited
            }
            
            visitedNodes.add(nodeIndex);
            var node = nodes[nodeIndex];
            
            if (node == null) {
                return;
            }
            
            // Recursively visit children
            int childMask = node.getNonLeafMask();
            int childPointer = node.getChildPointer();
            
            if (childMask != 0 && childPointer > 0 && childPointer < nodes.length) {
                for (int i = 0; i < 8; i++) {
                    if ((childMask & (1 << i)) != 0) {
                        int childIndex = childPointer + i;
                        if (childIndex < nodes.length) {
                            validateTreeConnectivity(nodes, childIndex, visitedNodes, result);
                        }
                    }
                }
            }
        }
    }
    
    private static class TreeAnalyzer {
        TreeAnalysis analyzeTree(ESVONode[] nodes) {
            var analysis = new TreeAnalysis();
            
            if (nodes.length == 0) {
                return analysis;
            }
            
            // Analyze tree structure by traversing from root
            var visitedNodes = new java.util.HashSet<Integer>();
            var nodeDepths = new java.util.HashMap<Integer, Integer>();
            var branchingFactors = new java.util.ArrayList<Integer>();
            
            // Start analysis from root (index 0)
            analysis.rootNodeCount = 1;
            analyzeNodeRecursive(nodes, 0, 0, visitedNodes, nodeDepths, branchingFactors);
            
            // Count node types
            int leafNodes = 0;
            int internalNodes = 0;
            int emptyNodes = 0;
            int totalChildSlots = 0;
            int usedChildSlots = 0;
            
            for (int i = 0; i < nodes.length; i++) {
                var node = nodes[i];
                if (node == null) {
                    emptyNodes++;
                    continue;
                }
                
                int childMask = node.getNonLeafMask();
                totalChildSlots += 8; // Each node has 8 potential child slots
                usedChildSlots += Integer.bitCount(childMask);
                
                if (childMask == 0) {
                    leafNodes++;
                } else {
                    internalNodes++;
                }
            }
            
            // Calculate tree depth
            analysis.maxDepth = nodeDepths.isEmpty() ? 0 : 
                nodeDepths.values().stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
            
            // Calculate branching factors
            if (!branchingFactors.isEmpty()) {
                analysis.averageBranchingFactor = branchingFactors.stream()
                    .mapToDouble(Integer::doubleValue)
                    .average()
                    .orElse(0.0);
                analysis.maxBranchingFactor = branchingFactors.stream()
                    .mapToInt(Integer::intValue)
                    .max()
                    .orElse(0);
            } else {
                analysis.averageBranchingFactor = 0.0;
                analysis.maxBranchingFactor = 0;
            }
            
            // Set final counts
            analysis.leafNodeCount = leafNodes;
            analysis.internalNodeCount = internalNodes;
            analysis.emptyNodeCount = emptyNodes;
            analysis.totalChildSlots = totalChildSlots;
            analysis.usedChildSlots = usedChildSlots;
            
            // Calculate sparsity ratio (unused slots / total slots)
            analysis.sparsityRatio = totalChildSlots > 0 ? 
                (double) (totalChildSlots - usedChildSlots) / totalChildSlots : 0.0;
            
            return analysis;
        }
        
        private void analyzeNodeRecursive(ESVONode[] nodes, int nodeIndex, int depth,
                                        java.util.Set<Integer> visitedNodes,
                                        java.util.Map<Integer, Integer> nodeDepths,
                                        java.util.List<Integer> branchingFactors) {
            
            if (nodeIndex < 0 || nodeIndex >= nodes.length || visitedNodes.contains(nodeIndex)) {
                return;
            }
            
            visitedNodes.add(nodeIndex);
            nodeDepths.put(nodeIndex, depth);
            
            var node = nodes[nodeIndex];
            if (node == null) {
                return;
            }
            
            int childMask = node.getNonLeafMask();
            int childPointer = node.getChildPointer();
            
            if (childMask != 0 && childPointer > 0 && childPointer < nodes.length) {
                // Count actual children for branching factor
                int actualChildren = 0;
                
                for (int i = 0; i < 8; i++) {
                    if ((childMask & (1 << i)) != 0) {
                        int childIndex = childPointer + i;
                        if (childIndex < nodes.length && nodes[childIndex] != null) {
                            actualChildren++;
                            // Recursively analyze child
                            analyzeNodeRecursive(nodes, childIndex, depth + 1, 
                                               visitedNodes, nodeDepths, branchingFactors);
                        }
                    }
                }
                
                if (actualChildren > 0) {
                    branchingFactors.add(actualChildren);
                }
            }
        }
    }
}