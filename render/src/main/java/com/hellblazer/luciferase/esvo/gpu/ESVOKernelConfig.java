package com.hellblazer.luciferase.esvo.gpu;

/**
 * Configuration class for ESVO GPU kernel parameters.
 * Controls workgroup sizes, local memory allocation, and other kernel execution parameters.
 */
public class ESVOKernelConfig {
    
    private int workgroupSize;
    private int localMemorySize;
    private int maxWorkgroupsPerComputeUnit;
    private int preferredVectorWidth;
    private boolean useLocalMemoryOptimization;
    private boolean enableCoalescing;
    
    /**
     * Default constructor with reasonable defaults
     */
    public ESVOKernelConfig() {
        this.workgroupSize = 64;                    // Common default for most GPUs
        this.localMemorySize = 16384;              // 16KB local memory
        this.maxWorkgroupsPerComputeUnit = 4;      // Conservative default
        this.preferredVectorWidth = 4;             // float4 vectors
        this.useLocalMemoryOptimization = true;
        this.enableCoalescing = true;
    }
    
    /**
     * Constructor with explicit parameters
     */
    public ESVOKernelConfig(int workgroupSize, int localMemorySize) {
        this();
        this.workgroupSize = workgroupSize;
        this.localMemorySize = localMemorySize;
    }
    
    /**
     * Copy constructor
     */
    public ESVOKernelConfig(ESVOKernelConfig other) {
        this.workgroupSize = other.workgroupSize;
        this.localMemorySize = other.localMemorySize;
        this.maxWorkgroupsPerComputeUnit = other.maxWorkgroupsPerComputeUnit;
        this.preferredVectorWidth = other.preferredVectorWidth;
        this.useLocalMemoryOptimization = other.useLocalMemoryOptimization;
        this.enableCoalescing = other.enableCoalescing;
    }
    
    // Getters
    public int getWorkgroupSize() {
        return workgroupSize;
    }
    
    public int getLocalMemorySize() {
        return localMemorySize;
    }
    
    public int getMaxWorkgroupsPerComputeUnit() {
        return maxWorkgroupsPerComputeUnit;
    }
    
    public int getPreferredVectorWidth() {
        return preferredVectorWidth;
    }
    
    public boolean isUseLocalMemoryOptimization() {
        return useLocalMemoryOptimization;
    }
    
    public boolean isEnableCoalescing() {
        return enableCoalescing;
    }
    
    // Setters
    public ESVOKernelConfig setWorkgroupSize(int workgroupSize) {
        if (workgroupSize <= 0 || workgroupSize > 1024) {
            throw new IllegalArgumentException("Workgroup size must be between 1 and 1024");
        }
        this.workgroupSize = workgroupSize;
        return this;
    }
    
    public ESVOKernelConfig setLocalMemorySize(int localMemorySize) {
        if (localMemorySize < 0) {
            throw new IllegalArgumentException("Local memory size must be non-negative");
        }
        this.localMemorySize = localMemorySize;
        return this;
    }
    
    public ESVOKernelConfig setMaxWorkgroupsPerComputeUnit(int maxWorkgroupsPerComputeUnit) {
        if (maxWorkgroupsPerComputeUnit <= 0) {
            throw new IllegalArgumentException("Max workgroups per compute unit must be positive");
        }
        this.maxWorkgroupsPerComputeUnit = maxWorkgroupsPerComputeUnit;
        return this;
    }
    
    public ESVOKernelConfig setPreferredVectorWidth(int preferredVectorWidth) {
        if (preferredVectorWidth <= 0 || (preferredVectorWidth & (preferredVectorWidth - 1)) != 0) {
            throw new IllegalArgumentException("Preferred vector width must be a positive power of 2");
        }
        this.preferredVectorWidth = preferredVectorWidth;
        return this;
    }
    
    public ESVOKernelConfig setUseLocalMemoryOptimization(boolean useLocalMemoryOptimization) {
        this.useLocalMemoryOptimization = useLocalMemoryOptimization;
        return this;
    }
    
    public ESVOKernelConfig setEnableCoalescing(boolean enableCoalescing) {
        this.enableCoalescing = enableCoalescing;
        return this;
    }
    
    /**
     * Validate the configuration parameters
     */
    public boolean isValid() {
        return workgroupSize > 0 && workgroupSize <= 1024 &&
               localMemorySize >= 0 &&
               maxWorkgroupsPerComputeUnit > 0 &&
               preferredVectorWidth > 0 && (preferredVectorWidth & (preferredVectorWidth - 1)) == 0;
    }
    
    /**
     * Calculate the total number of threads for given global work size
     */
    public int calculateTotalThreads(int globalWorkSize) {
        return (globalWorkSize + workgroupSize - 1) / workgroupSize * workgroupSize;
    }
    
    /**
     * Calculate number of workgroups for given global work size
     */
    public int calculateNumWorkgroups(int globalWorkSize) {
        return (globalWorkSize + workgroupSize - 1) / workgroupSize;
    }
    
    /**
     * Estimate occupancy based on local memory usage
     */
    public float estimateOccupancy(int maxLocalMemoryPerComputeUnit) {
        if (maxLocalMemoryPerComputeUnit <= 0 || localMemorySize <= 0) {
            return 1.0f;
        }
        
        int workgroupsLimitedByMemory = maxLocalMemoryPerComputeUnit / localMemorySize;
        int actualWorkgroups = Math.min(maxWorkgroupsPerComputeUnit, workgroupsLimitedByMemory);
        
        return (float) actualWorkgroups / maxWorkgroupsPerComputeUnit;
    }
    
    @Override
    public String toString() {
        return String.format("ESVOKernelConfig{workgroupSize=%d, localMemorySize=%d, " +
                           "maxWorkgroupsPerComputeUnit=%d, preferredVectorWidth=%d, " +
                           "useLocalMemoryOptimization=%b, enableCoalescing=%b}",
                           workgroupSize, localMemorySize, maxWorkgroupsPerComputeUnit,
                           preferredVectorWidth, useLocalMemoryOptimization, enableCoalescing);
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        
        ESVOKernelConfig that = (ESVOKernelConfig) obj;
        return workgroupSize == that.workgroupSize &&
               localMemorySize == that.localMemorySize &&
               maxWorkgroupsPerComputeUnit == that.maxWorkgroupsPerComputeUnit &&
               preferredVectorWidth == that.preferredVectorWidth &&
               useLocalMemoryOptimization == that.useLocalMemoryOptimization &&
               enableCoalescing == that.enableCoalescing;
    }
    
    @Override
    public int hashCode() {
        int result = workgroupSize;
        result = 31 * result + localMemorySize;
        result = 31 * result + maxWorkgroupsPerComputeUnit;
        result = 31 * result + preferredVectorWidth;
        result = 31 * result + (useLocalMemoryOptimization ? 1 : 0);
        result = 31 * result + (enableCoalescing ? 1 : 0);
        return result;
    }
}