package com.hellblazer.luciferase.lucien.forest;

/**
 * Configuration class for Forest spatial index structures.
 * Provides options for tree overlapping, ghost zones, and partition strategies.
 * 
 * @author hal.hildebrand
 */
public class ForestConfig {
    
    /**
     * Enumeration of available partition strategies for the forest
     */
    public enum PartitionStrategy {
        /**
         * Simple grid-based partitioning
         */
        GRID,
        
        /**
         * K-d tree based partitioning
         */
        KD_TREE,
        
        /**
         * BSP tree based partitioning
         */
        BSP_TREE,
        
        /**
         * Hilbert curve based partitioning
         */
        HILBERT_CURVE,
        
        /**
         * Custom user-defined partitioning
         */
        CUSTOM
    }
    
    private final boolean overlappingTrees;
    private final PartitionStrategy partitionStrategy;

    private ForestConfig(Builder builder) {
        this.overlappingTrees = builder.overlappingTrees;
        this.partitionStrategy = builder.partitionStrategy;
    }
    
    /**
     * Returns whether trees are allowed to overlap in this forest configuration
     * 
     * @return true if trees can overlap, false otherwise
     */
    public boolean isOverlappingTrees() {
        return overlappingTrees;
    }
    
    /**
     * Returns the partition strategy for this forest configuration
     * 
     * @return the partition strategy
     */
    public PartitionStrategy getPartitionStrategy() {
        return partitionStrategy;
    }
    
    /**
     * Creates a new builder for ForestConfig
     * 
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Builder class for ForestConfig
     */
    public static class Builder {
        private boolean overlappingTrees = false;
        private PartitionStrategy partitionStrategy = PartitionStrategy.GRID;
        
        private Builder() {
            // Private constructor to enforce builder pattern
        }
        
        /**
         * Sets whether trees are allowed to overlap
         * 
         * @param overlapping true to allow overlapping trees
         * @return this builder instance
         */
        public Builder withOverlappingTrees(boolean overlapping) {
            this.overlappingTrees = overlapping;
            return this;
        }
        
        /**
         * Sets the partition strategy
         * 
         * @param strategy the partition strategy to use
         * @return this builder instance
         * @throws IllegalArgumentException if strategy is null
         */
        public Builder withPartitionStrategy(PartitionStrategy strategy) {
            if (strategy == null) {
                throw new IllegalArgumentException("Partition strategy cannot be null");
            }
            this.partitionStrategy = strategy;
            return this;
        }
        
        /**
         * Builds and returns a new ForestConfig instance
         * 
         * @return the configured ForestConfig
         */
        public ForestConfig build() {
            return new ForestConfig(this);
        }
    }
    
    /**
     * Creates a default ForestConfig with no overlapping trees and grid-based partitioning
     *
     * @return a default ForestConfig instance
     */
    public static ForestConfig defaultConfig() {
        return builder().build();
    }

    @Override
    public String toString() {
        return String.format("ForestConfig[overlapping=%s, strategy=%s]", overlappingTrees, partitionStrategy);
    }
}