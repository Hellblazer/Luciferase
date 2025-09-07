package com.hellblazer.luciferase.gpu.esvo.correct;

import java.util.*;

/**
 * Generator for valid ESVO octree test data.
 * Creates properly structured octrees with correct sparse indexing.
 */
public class ESVOTestDataGenerator {
    
    private final Random random;
    private final List<ESVONode> nodes;
    private int nextNodeIndex;
    
    public ESVOTestDataGenerator(long seed) {
        this.random = new Random(seed);
        this.nodes = new ArrayList<>();
        this.nextNodeIndex = 0;
    }
    
    /**
     * Generate a complete octree with specified depth.
     * 
     * @param maxDepth Maximum depth of the octree
     * @param fillProbability Probability of a voxel being filled (0.0 to 1.0)
     * @param subdivisionProbability Probability of subdividing a node (0.0 to 1.0)
     * @return Array of octree nodes
     */
    public ESVONode[] generateOctree(int maxDepth, float fillProbability, float subdivisionProbability) {
        nodes.clear();
        nextNodeIndex = 0;
        
        // Create root node
        ESVONode root = new ESVONode();
        root.setValid(true);
        nodes.add(root);
        nextNodeIndex++;
        
        // Recursively build the octree
        buildNode(0, 0, maxDepth, fillProbability, subdivisionProbability);
        
        return nodes.toArray(new ESVONode[0]);
    }
    
    /**
     * Recursively build octree nodes
     */
    private void buildNode(int nodeIdx, int currentDepth, int maxDepth, 
                          float fillProbability, float subdivisionProbability) {
        if (nodeIdx >= nodes.size()) {
            return;
        }
        
        ESVONode node = nodes.get(nodeIdx);
        
        // Decide which children exist
        int childMask = 0;
        int leafMask = 0;
        List<Integer> childIndices = new ArrayList<>();
        
        for (int i = 0; i < 8; i++) {
            if (random.nextFloat() < fillProbability) {
                childMask |= (1 << i);
                
                // Decide if this child is a leaf or should be subdivided
                boolean isLeaf = (currentDepth >= maxDepth - 1) || 
                                 (random.nextFloat() > subdivisionProbability);
                if (isLeaf) {
                    leafMask |= (1 << i);
                }
            }
        }
        
        node.setChildMask(childMask);
        node.setLeafMask(leafMask);
        
        // If node has children, allocate them
        int childCount = Integer.bitCount(childMask);
        if (childCount > 0) {
            // Set child pointer to next available index
            node.setChildPointer(nextNodeIndex);
            
            // Create child nodes
            for (int i = 0; i < 8; i++) {
                if ((childMask & (1 << i)) != 0) {
                    ESVONode child = new ESVONode();
                    child.setValid(true);
                    nodes.add(child);
                    childIndices.add(nextNodeIndex);
                    nextNodeIndex++;
                }
            }
            
            // Recursively build non-leaf children
            int childIdx = 0;
            for (int i = 0; i < 8; i++) {
                if ((childMask & (1 << i)) != 0) {
                    if ((leafMask & (1 << i)) == 0) {
                        // This child should be subdivided
                        buildNode(childIndices.get(childIdx), currentDepth + 1, 
                                 maxDepth, fillProbability, subdivisionProbability);
                    }
                    childIdx++;
                }
            }
        }
    }
    
    /**
     * Generate a simple test octree with known structure for validation
     */
    public static ESVONode[] generateSimpleTestOctree() {
        ESVONode[] nodes = new ESVONode[10];
        
        // Root node - has 4 children (0,1,2,3)
        nodes[0] = new ESVONode();
        nodes[0].setValid(true);
        nodes[0].setChildMask(0x0F);  // Children 0,1,2,3 exist
        nodes[0].setLeafMask(0x0C);   // Children 2,3 are leaves
        nodes[0].setChildPointer(1);   // First child at index 1
        
        // Child 0 (index 1) - has 2 children (0,1)
        nodes[1] = new ESVONode();
        nodes[1].setValid(true);
        nodes[1].setChildMask(0x03);  // Children 0,1 exist
        nodes[1].setLeafMask(0x03);   // Both are leaves
        nodes[1].setChildPointer(5);   // First child at index 5
        
        // Child 1 (index 2) - has 4 children (4,5,6,7)
        nodes[2] = new ESVONode();
        nodes[2].setValid(true);
        nodes[2].setChildMask(0xF0);  // Children 4,5,6,7 exist
        nodes[2].setLeafMask(0xF0);   // All are leaves
        nodes[2].setChildPointer(7);   // First child at index 7
        
        // Child 2 (index 3) - leaf
        nodes[3] = new ESVONode();
        nodes[3].setValid(true);
        nodes[3].setChildMask(0x00);  // No children
        
        // Child 3 (index 4) - leaf
        nodes[4] = new ESVONode();
        nodes[4].setValid(true);
        nodes[4].setChildMask(0x00);  // No children
        
        // Grandchildren of child 0
        nodes[5] = new ESVONode();  // Leaf
        nodes[5].setValid(true);
        nodes[5].setChildMask(0x00);
        
        nodes[6] = new ESVONode();  // Leaf
        nodes[6].setValid(true);
        nodes[6].setChildMask(0x00);
        
        // Grandchildren of child 1
        nodes[7] = new ESVONode();  // Leaf
        nodes[7].setValid(true);
        nodes[7].setChildMask(0x00);
        
        nodes[8] = new ESVONode();  // Leaf
        nodes[8].setValid(true);
        nodes[8].setChildMask(0x00);
        
        nodes[9] = new ESVONode();  // Leaf
        nodes[9].setValid(true);
        nodes[9].setChildMask(0x00);
        
        return nodes;
    }
    
    /**
     * Validate that an octree structure is correct
     */
    public static boolean validateOctreeStructure(ESVONode[] nodes) {
        if (nodes == null || nodes.length == 0) {
            return false;
        }
        
        // Check each node
        for (int i = 0; i < nodes.length; i++) {
            ESVONode node = nodes[i];
            if (node == null) {
                System.err.println("Node " + i + " is null");
                return false;
            }
            
            // Check that child pointers are valid
            int childCount = node.getChildCount();
            if (childCount > 0) {
                int basePtr = node.getChildPointer();
                
                // All children should be within array bounds
                if (basePtr < 0 || basePtr + childCount > nodes.length) {
                    System.err.println("Node " + i + " has invalid child pointer: " + 
                                      basePtr + " with " + childCount + " children");
                    return false;
                }
                
                // Verify sparse indexing
                for (int childIdx = 0; childIdx < 8; childIdx++) {
                    if (node.hasChild(childIdx)) {
                        int actualIdx = node.getChildNodeIndex(childIdx);
                        if (actualIdx < 0 || actualIdx >= nodes.length) {
                            System.err.println("Node " + i + " child " + childIdx + 
                                             " has invalid index: " + actualIdx);
                            return false;
                        }
                    }
                }
            }
        }
        
        return true;
    }
    
    /**
     * Print octree structure for debugging
     */
    public static void printOctreeStructure(ESVONode[] nodes) {
        System.out.println("Octree Structure (" + nodes.length + " nodes):");
        for (int i = 0; i < nodes.length; i++) {
            ESVONode node = nodes[i];
            System.out.printf("[%3d] %s", i, node.toString());
            
            // Show child indices
            if (node.getChildCount() > 0) {
                System.out.print(" -> children: ");
                for (int j = 0; j < 8; j++) {
                    if (node.hasChild(j)) {
                        System.out.print(node.getChildNodeIndex(j) + " ");
                    }
                }
            }
            System.out.println();
        }
    }
}