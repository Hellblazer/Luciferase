package com.hellblazer.luciferase.esvo.core;

/**
 * Stack structure for ESVO ray traversal operations.
 * Manages the traversal state during octree navigation.
 */
public class ESVOStack {
    
    // Stack storage - matches CUDA reference implementation
    private static final int STACK_SIZE = 64;  // Sufficient for depth 23
    private int[] nodeStack;   // Node indices
    private float[] tMaxStack; // tMax values
    
    /**
     * Create new traversal stack
     */
    public ESVOStack() {
        this.nodeStack = new int[STACK_SIZE];
        this.tMaxStack = new float[STACK_SIZE];
        reset();
    }
    
    /**
     * Reset stack to empty state
     */
    public void reset() {
        for (int i = 0; i < STACK_SIZE; i++) {
            nodeStack[i] = -1;
            tMaxStack[i] = 0.0f;
        }
    }
    
    /**
     * Write entry to stack at given scale level.
     * This matches the CUDA reference stack operations.
     * 
     * @param scale Scale level (0-22)
     * @param nodeIndex Node index to store
     * @param tMax tMax value to store
     */
    public void write(int scale, int nodeIndex, float tMax) {
        if (scale >= 0 && scale < STACK_SIZE) {
            nodeStack[scale] = nodeIndex;
            tMaxStack[scale] = tMax;
        }
    }
    
    /**
     * Read node index from stack at given scale level.
     * 
     * @param scale Scale level (0-22)
     * @return Node index, or -1 if invalid
     */
    public int readNode(int scale) {
        if (scale >= 0 && scale < STACK_SIZE) {
            return nodeStack[scale];
        }
        return -1;
    }
    
    /**
     * Read tMax value from stack at given scale level.
     * 
     * @param scale Scale level (0-22)
     * @return tMax value, or 0.0 if invalid
     */
    public float readTmax(int scale) {
        if (scale >= 0 && scale < STACK_SIZE) {
            return tMaxStack[scale];
        }
        return 0.0f;
    }
    
    /**
     * Check if stack has entry at given scale
     */
    public boolean hasEntry(int scale) {
        return scale >= 0 && scale < STACK_SIZE && nodeStack[scale] != -1;
    }
    
    /**
     * Get current stack depth (highest valid entry)
     */
    public int getDepth() {
        for (int i = STACK_SIZE - 1; i >= 0; i--) {
            if (nodeStack[i] != -1) {
                return i + 1;
            }
        }
        return 0;
    }
    
    /**
     * Clear entry at specific scale
     */
    public void clear(int scale) {
        if (scale >= 0 && scale < STACK_SIZE) {
            nodeStack[scale] = -1;
            tMaxStack[scale] = 0.0f;
        }
    }
    
    /**
     * Copy stack state from another stack
     */
    public void copyFrom(ESVOStack other) {
        System.arraycopy(other.nodeStack, 0, this.nodeStack, 0, STACK_SIZE);
        System.arraycopy(other.tMaxStack, 0, this.tMaxStack, 0, STACK_SIZE);
    }
    
    @Override
    public String toString() {
        var sb = new StringBuilder();
        sb.append("ESVOStack[depth=").append(getDepth()).append(", entries={");
        boolean first = true;
        for (int i = 0; i < STACK_SIZE; i++) {
            if (nodeStack[i] != -1) {
                if (!first) sb.append(", ");
                sb.append(i).append(":").append(nodeStack[i]).append("(").append(String.format("%.3f", tMaxStack[i])).append(")");
                first = false;
            }
        }
        sb.append("}]");
        return sb.toString();
    }
}