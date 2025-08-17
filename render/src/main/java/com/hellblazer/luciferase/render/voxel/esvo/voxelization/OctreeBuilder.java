package com.hellblazer.luciferase.render.voxel.esvo.voxelization;

import com.hellblazer.luciferase.render.voxel.esvo.ESVONode;
import com.hellblazer.luciferase.render.voxel.esvo.ESVOPage;

import java.util.*;

/**
 * Builds ESVO octree structures from voxelized data.
 * Handles node creation, page alignment, and memory optimization.
 */
public class OctreeBuilder {
    
    private static class OctreeNode {
        int x, y, z;
        int level;
        int size;
        boolean isLeaf;
        List<Voxel> voxels;
        OctreeNode[] children;
        VoxelAttribute homogeneousAttribute;
        ContourData contourData;
        
        OctreeNode(int x, int y, int z, int level, int size) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.level = level;
            this.size = size;
            this.voxels = new ArrayList<>();
            this.children = new OctreeNode[8];
        }
    }
    
    /**
     * Build octree from voxel list
     */
    public Octree buildOctree(List<Voxel> voxels, OctreeConfig config) {
        if (voxels.isEmpty()) {
            return new Octree();
        }
        
        // Determine bounds
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        
        for (Voxel v : voxels) {
            minX = Math.min(minX, v.x);
            minY = Math.min(minY, v.y);
            minZ = Math.min(minZ, v.z);
            maxX = Math.max(maxX, v.x);
            maxY = Math.max(maxY, v.y);
            maxZ = Math.max(maxZ, v.z);
        }
        
        // Calculate octree size (power of 2)
        int rangeX = maxX - minX + 1;
        int rangeY = maxY - minY + 1;
        int rangeZ = maxZ - minZ + 1;
        int maxRange = Math.max(Math.max(rangeX, rangeY), rangeZ);
        int size = 1;
        while (size < maxRange) {
            size <<= 1;
        }
        
        // Build based on strategy
        OctreeNode root;
        if (config.getBuildStrategy() == BuildStrategy.BOTTOM_UP) {
            root = buildBottomUp(voxels, minX, minY, minZ, size, config);
        } else {
            root = buildTopDown(voxels, minX, minY, minZ, size, 0, config);
        }
        
        // Convert to ESVO format
        return convertToESVO(root, config);
    }
    
    /**
     * Top-down octree construction
     */
    private OctreeNode buildTopDown(List<Voxel> voxels, int x, int y, int z, 
                                    int size, int level, OctreeConfig config) {
        OctreeNode node = new OctreeNode(x, y, z, level, size);
        
        // Check if we should stop subdividing
        if (level >= config.getMaxDepth() || size == 1 || 
            voxels.size() <= config.getMinVoxelsPerNode()) {
            node.isLeaf = true;
            node.voxels = voxels;
            
            // Check for homogeneous region
            if (config.isCompressHomogeneous() && areVoxelsHomogeneous(voxels)) {
                node.homogeneousAttribute = voxels.get(0).getAttribute();
            }
            
            return node;
        }
        
        // Subdivide voxels into octants
        int halfSize = size / 2;
        List<List<Voxel>> octantVoxels = new ArrayList<>(8);
        for (int i = 0; i < 8; i++) {
            octantVoxels.add(new ArrayList<>());
        }
        
        for (Voxel v : voxels) {
            int octant = getOctant(v.x - x, v.y - y, v.z - z, halfSize);
            octantVoxels.get(octant).add(v);
        }
        
        // Create child nodes
        for (int i = 0; i < 8; i++) {
            if (!octantVoxels.get(i).isEmpty()) {
                int childX = x + ((i & 1) != 0 ? halfSize : 0);
                int childY = y + ((i & 2) != 0 ? halfSize : 0);
                int childZ = z + ((i & 4) != 0 ? halfSize : 0);
                
                node.children[i] = buildTopDown(octantVoxels.get(i), 
                    childX, childY, childZ, halfSize, level + 1, config);
            }
        }
        
        return node;
    }
    
    /**
     * Bottom-up octree construction
     */
    private OctreeNode buildBottomUp(List<Voxel> voxels, int minX, int minY, int minZ,
                                     int size, OctreeConfig config) {
        // Create leaf nodes for all voxels
        Map<Long, OctreeNode> leafNodes = new HashMap<>();
        
        for (Voxel v : voxels) {
            long key = encodePosition(v.x - minX, v.y - minY, v.z - minZ);
            OctreeNode leaf = new OctreeNode(v.x, v.y, v.z, config.getMaxDepth(), 1);
            leaf.isLeaf = true;
            leaf.voxels.add(v);
            leafNodes.put(key, leaf);
        }
        
        // Build tree bottom-up
        return mergeBottomUp(leafNodes, minX, minY, minZ, size, config.getMaxDepth(), config);
    }
    
    private OctreeNode mergeBottomUp(Map<Long, OctreeNode> nodes, int x, int y, int z,
                                     int size, int level, OctreeConfig config) {
        if (level == 0 || nodes.size() == 1) {
            if (nodes.size() == 1) {
                return nodes.values().iterator().next();
            }
        }
        
        OctreeNode parent = new OctreeNode(x, y, z, level - 1, size);
        int halfSize = size / 2;
        
        // Group nodes by octant
        Map<Integer, List<OctreeNode>> octantNodes = new HashMap<>();
        for (OctreeNode node : nodes.values()) {
            int octant = getOctant(node.x - x, node.y - y, node.z - z, halfSize);
            octantNodes.computeIfAbsent(octant, k -> new ArrayList<>()).add(node);
        }
        
        // Create or merge children
        for (Map.Entry<Integer, List<OctreeNode>> entry : octantNodes.entrySet()) {
            int octant = entry.getKey();
            List<OctreeNode> childNodes = entry.getValue();
            
            if (childNodes.size() == 1) {
                parent.children[octant] = childNodes.get(0);
            } else {
                // Merge multiple nodes
                int childX = x + ((octant & 1) != 0 ? halfSize : 0);
                int childY = y + ((octant & 2) != 0 ? halfSize : 0);
                int childZ = z + ((octant & 4) != 0 ? halfSize : 0);
                
                Map<Long, OctreeNode> childMap = new HashMap<>();
                for (OctreeNode node : childNodes) {
                    long key = encodePosition(node.x - childX, node.y - childY, node.z - childZ);
                    childMap.put(key, node);
                }
                
                parent.children[octant] = mergeBottomUp(childMap, childX, childY, childZ,
                    halfSize, level - 1, config);
            }
        }
        
        return parent;
    }
    
    /**
     * Convert internal octree to ESVO format
     */
    private Octree convertToESVO(OctreeNode root, OctreeConfig config) {
        Octree octree = new Octree();
        List<ESVONode> nodes = new ArrayList<>();
        Queue<OctreeNode> queue = new LinkedList<>();
        queue.offer(root);
        
        int nodeCount = 0;
        int leafCount = 0;
        int maxDepth = 0;
        int treeDepth = calculateTreeDepth(root);  // Calculate actual tree depth
        
        while (!queue.isEmpty()) {
            OctreeNode node = queue.poll();
            nodeCount++;
            maxDepth = Math.max(maxDepth, node.level);
            
            // Create ESVO node
            byte validMask = 0;
            byte nonLeafMask = 0;
            int childPointer = 0; // Will be set if node has children
            
            // Process children
            int validChildren = 0;
            int nonLeafChildren = 0;
            for (int i = 0; i < 8; i++) {
                if (node.children[i] != null) {
                    validMask |= (1 << i);
                    validChildren++;
                    
                    if (!node.children[i].isLeaf) {
                        nonLeafMask |= (1 << i);
                        nonLeafChildren++;
                        queue.offer(node.children[i]);
                    } else {
                        leafCount++;
                    }
                }
            }
            
            // Set child pointer if there are any valid children
            // In ESVO, child pointer points to the first child regardless of leaf status
            if (validChildren > 0 && !node.isLeaf) {
                childPointer = nodes.size() + 1; // Points to where children will be in the array
            }
            
            // Create ESVO node
            ESVONode esvoNode = new ESVONode();
            
            // Handle leaf node
            if (node.isLeaf) {
                leafCount++;
                // For leaf nodes: compute validMask based on which octants contain voxels
                if (!node.voxels.isEmpty()) {
                    // Mark octants that contain voxels
                    validMask = 0;
                    int halfSize = node.size / 2;
                    if (halfSize > 0) {
                        for (Voxel v : node.voxels) {
                            int octant = getOctant(v.x - node.x, v.y - node.y, v.z - node.z, halfSize);
                            validMask |= (1 << octant);
                        }
                    } else {
                        // Node size is 1, can't subdivide further
                        // Mark the single octant as occupied
                        validMask = (byte) 0x01;  // Just octant 0
                    }
                }
                nonLeafMask = 0;  // Leaf nodes have no internal children
                childPointer = 0; // Leaf nodes have no child pointer
                
                // Add to leaf list
                octree.addLeafNode(esvoNode);
            }
            
            esvoNode.setValidMask(validMask);
            esvoNode.setNonLeafMask(nonLeafMask);
            esvoNode.setChildPointer(childPointer, false);
            
            // Handle contours if enabled
            if (config.isStoreContours() && node.contourData != null) {
                esvoNode.setContourMask((byte) 0xFF);
                esvoNode.setContourPointer(nodes.size()); // Simplified
            }
            
            nodes.add(esvoNode);
            octree.addESVONode(esvoNode);
        }
        
        octree.setNodeCount(nodeCount);
        octree.setLeafCount(leafCount);
        
        // Use the actual tree depth calculated from structure
        octree.setMaxDepth(treeDepth);
        
        // Set LOD count if configured
        if (config.getLODLevels() > 0) {
            octree.setLODCount(config.getLODLevels());
        }
        
        // Generate pages if needed
        if (config.getPageSize() > 0) {
            generatePages(octree, nodes, config);
        }
        
        // Optimize layout if requested
        if (config.isOptimizeLayout()) {
            optimizeMemoryLayout(octree, nodes);
        }
        
        // Set complete flag for bottom-up builds
        if (config.getBuildStrategy() == BuildStrategy.BOTTOM_UP) {
            octree.setComplete(true);
        }
        
        // Handle contours
        if (config.isStoreContours()) {
            octree.setHasContours(true);
        }
        
        return octree;
    }
    
    /**
     * Generate page-aligned memory layout
     */
    private void generatePages(Octree octree, List<ESVONode> nodes, OctreeConfig config) {
        // Skip page generation for now - requires Arena management
        // This is a simplified implementation
    }
    
    /**
     * Optimize memory layout for cache efficiency
     */
    private void optimizeMemoryLayout(Octree octree, List<ESVONode> nodes) {
        // Calculate average child distance (simplified)
        float totalDistance = 0;
        int count = 0;
        
        for (int i = 0; i < nodes.size(); i++) {
            ESVONode node = nodes.get(i);
            if (node.getChildPointer() > 0 && node.getChildPointer() < nodes.size()) {
                totalDistance += Math.abs(node.getChildPointer() - i);
                count++;
            }
        }
        
        if (count > 0) {
            octree.getLayoutStatistics().setAverageChildDistance(totalDistance / count);
        }
    }
    
    // Helper methods
    
    private int getOctant(int x, int y, int z, int halfSize) {
        int octant = 0;
        if (x >= halfSize) octant |= 1;
        if (y >= halfSize) octant |= 2;
        if (z >= halfSize) octant |= 4;
        return octant;
    }
    
    private long encodePosition(int x, int y, int z) {
        return ((long) x << 40) | ((long) y << 20) | z;
    }
    
    private boolean areVoxelsHomogeneous(List<Voxel> voxels) {
        if (voxels.isEmpty()) return false;
        
        VoxelAttribute first = voxels.get(0).getAttribute();
        if (first == null) return false;
        
        for (int i = 1; i < voxels.size(); i++) {
            VoxelAttribute attr = voxels.get(i).getAttribute();
            if (attr == null || !attributesEqual(first, attr)) {
                return false;
            }
        }
        
        return true;
    }
    
    private boolean attributesEqual(VoxelAttribute a, VoxelAttribute b) {
        // Simplified comparison
        if (a.getColor() != null && b.getColor() != null) {
            float[] ca = a.getColor();
            float[] cb = b.getColor();
            for (int i = 0; i < 4; i++) {
                if (Math.abs(ca[i] - cb[i]) > 0.01f) {
                    return false;
                }
            }
        }
        
        return Math.abs(a.getRoughness() - b.getRoughness()) < 0.01f &&
               Math.abs(a.getMetallic() - b.getMetallic()) < 0.01f;
    }
    
    private int calculateTreeDepth(OctreeNode node) {
        return calculateTreeDepthRecursive(node, 0);
    }
    
    private int calculateTreeDepthRecursive(OctreeNode node, int currentDepth) {
        if (node == null) {
            return currentDepth - 1;
        }
        
        if (node.isLeaf) {
            return currentDepth;
        }
        
        int maxDepth = currentDepth;
        for (int i = 0; i < 8; i++) {
            if (node.children[i] != null) {
                int childDepth = calculateTreeDepthRecursive(node.children[i], currentDepth + 1);
                maxDepth = Math.max(maxDepth, childDepth);
            }
        }
        
        return maxDepth;
    }
}