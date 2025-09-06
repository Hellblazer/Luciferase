package com.hellblazer.luciferase.esvo.data;

/**
 * Stub class for ESVO octree data structure
 * Created to enable compilation during EnhancedRay migration
 */
public class ESVOOctreeData {
    
    private int nodeCount;
    private byte[] treeData;
    
    public ESVOOctreeData() {
        this.nodeCount = 0;
        this.treeData = new byte[0];
    }

    public ESVOOctreeData(int nodeCount) {
        this(nodeCount, null);
    }
    
    public ESVOOctreeData(int nodeCount, byte[] treeData) {
        this.nodeCount = nodeCount;
        this.treeData = treeData != null ? treeData : new byte[0];
    }
    
    public int getNodeCount() {
        return nodeCount;
    }
    
    public byte[] getTreeData() {
        return treeData;
    }
    
    public void setNodeCount(int nodeCount) {
        this.nodeCount = nodeCount;
    }
    
    public void setTreeData(byte[] treeData) {
        this.treeData = treeData != null ? treeData : new byte[0];
    }
}
