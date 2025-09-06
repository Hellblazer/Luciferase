package com.dyada.core.descriptors;

import com.dyada.core.bitarray.BitArray;

import java.util.*;

/**
 * Represents a path through the refinement tree for navigation and traversal.
 * 
 * A Branch maintains a stack of LevelCounter objects representing the current
 * position in the tree and provides methods for tree traversal operations.
 * This is essential for converting between tree indices and spatial coordinates.
 */
public final class Branch {
    
    private final Deque<LevelCounter> stack;
    private final int numDimensions;
    
    /**
     * Creates a new branch starting at the root.
     */
    public Branch(int numDimensions) {
        if (numDimensions <= 0) {
            throw new IllegalArgumentException("Dimensions must be positive: " + numDimensions);
        }
        
        this.numDimensions = numDimensions;
        this.stack = new ArrayDeque<>();
        // Start with empty stack - root has no level counter
    }
    
    /**
     * Private constructor for creating branches with existing stack.
     */
    private Branch(int numDimensions, Deque<LevelCounter> stack) {
        this.numDimensions = numDimensions;
        this.stack = new ArrayDeque<>(stack);
    }
    
    /**
     * Returns a new branch that has grown by applying the given refinement.
     * 
     * @param refinement BitArray indicating which dimensions are refined
     * @return new Branch representing the path after refinement
     */
    public Branch growBranch(BitArray refinement) {
        if (refinement.size() != numDimensions) {
            throw new IllegalArgumentException(
                String.format("Refinement size %d != dimensions %d", 
                    refinement.size(), numDimensions));
        }
        
        var newStack = new ArrayDeque<>(stack);
        
        // Calculate count to go up based on refinement pattern
        int refinedDimensions = refinement.count();
        int countToGoUp = (1 << refinedDimensions) - 1; // 2^n - 1
        
        // Create level counter for this refinement step
        var levelCounter = new LevelCounter(refinement, countToGoUp);
        newStack.push(levelCounter);
        
        return new Branch(numDimensions, newStack);
    }
    
    /**
     * Returns a new branch that has advanced to the next sibling.
     * 
     * @return new Branch representing the next sibling, or null if no more siblings
     */
    public Branch advanceBranch() {
        if (stack.isEmpty()) {
            return null; // Root has no siblings
        }
        
        var newStack = new ArrayDeque<>(stack);
        
        // Process the stack to advance to next sibling
        while (!newStack.isEmpty()) {
            var top = newStack.pop();
            
            if (top.countToGoUp() > 0) {
                // Decrement counter and push back
                var decremented = new LevelCounter(top.levelIncrement(), top.countToGoUp() - 1);
                newStack.push(decremented);
                return new Branch(numDimensions, newStack);
            }
            // If countToGoUp is 0, continue popping (go up a level)
        }
        
        return null; // No more siblings at any level
    }
    
    /**
     * Converts this branch to a history of indices and level increments.
     * 
     * @return Pair of (indices list, level increments list)
     */
    public BranchHistory toHistory() {
        var indices = new ArrayList<Integer>();
        var levelIncrements = new ArrayList<BitArray>();
        
        // Build history from stack (bottom to top)
        var stackArray = stack.toArray(new LevelCounter[0]);
        
        for (int i = stackArray.length - 1; i >= 0; i--) {
            var counter = stackArray[i];
            levelIncrements.add(counter.levelIncrement());
            
            // Calculate index based on remaining count
            int totalChildren = 1 << counter.levelIncrement().count();
            int currentIndex = totalChildren - 1 - counter.countToGoUp();
            indices.add(currentIndex);
        }
        
        return new BranchHistory(indices, levelIncrements);
    }
    
    /**
     * Returns the depth of this branch (number of refinement levels).
     */
    public int depth() {
        return stack.size();
    }
    
    /**
     * Returns true if this branch is at the root.
     */
    public boolean isAtRoot() {
        return stack.isEmpty();
    }
    
    /**
     * Returns the current level increment (refinement pattern) at the top of the stack.
     * Returns null if at root.
     */
    public BitArray getCurrentLevelIncrement() {
        return stack.isEmpty() ? null : stack.peek().levelIncrement();
    }
    
    /**
     * Returns the number of remaining siblings at the current level.
     */
    public int getRemainingCount() {
        return stack.isEmpty() ? 0 : stack.peek().countToGoUp();
    }
    
    /**
     * Returns true if this branch can advance to a sibling.
     */
    public boolean hasNextSibling() {
        return !stack.isEmpty() && stack.peek().countToGoUp() > 0;
    }
    
    /**
     * Creates a copy of this branch.
     */
    public Branch copy() {
        return new Branch(numDimensions, stack);
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        
        var other = (Branch) obj;
        return numDimensions == other.numDimensions && 
               List.copyOf(stack).equals(List.copyOf(other.stack));
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(List.copyOf(stack), numDimensions);
    }
    
    @Override
    public String toString() {
        return String.format("Branch{depth=%d, dimensions=%d, stack=%s}", 
            depth(), numDimensions, stack);
    }
}

/**
 * Record representing a level counter in the branch stack.
 * 
 * @param levelIncrement BitArray indicating which dimensions were refined
 * @param countToGoUp Number of remaining siblings at this level
 */
record LevelCounter(
    BitArray levelIncrement,
    int countToGoUp
) {
    public LevelCounter {
        Objects.requireNonNull(levelIncrement, "Level increment cannot be null");
        if (countToGoUp < 0) {
            throw new IllegalArgumentException("Count to go up cannot be negative: " + countToGoUp);
        }
    }
}

/**
 * Record representing the history of a branch traversal.
 * 
 * @param indices List of child indices at each level
 * @param levelIncrements List of refinement patterns at each level
 */
record BranchHistory(
    List<Integer> indices,
    List<BitArray> levelIncrements
) {
    public BranchHistory {
        Objects.requireNonNull(indices, "Indices cannot be null");
        Objects.requireNonNull(levelIncrements, "Level increments cannot be null");
        
        if (indices.size() != levelIncrements.size()) {
            throw new IllegalArgumentException(
                String.format("Indices and level increments must have same size: %d vs %d", 
                    indices.size(), levelIncrements.size()));
        }
        
        // Make defensive copies
        indices = List.copyOf(indices);
        levelIncrements = List.copyOf(levelIncrements);
    }
}