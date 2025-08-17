package com.hellblazer.luciferase.render.gpu;

/**
 * GPU performance statistics for monitoring compute operations.
 * Provides frame timing, memory usage, and dispatch information.
 */
public class GPUStats {
    private final long frameTime;
    private final long gpuMemoryUsed;
    private final long gpuMemoryTotal;
    private final int dispatchCount;
    private final int bufferCount;
    private final int shaderCount;
    
    public GPUStats(long frameTime, long gpuMemoryUsed, long gpuMemoryTotal,
                   int dispatchCount, int bufferCount, int shaderCount) {
        this.frameTime = frameTime;
        this.gpuMemoryUsed = gpuMemoryUsed;
        this.gpuMemoryTotal = gpuMemoryTotal;
        this.dispatchCount = dispatchCount;
        this.bufferCount = bufferCount;
        this.shaderCount = shaderCount;
    }
    
    /**
     * Get the frame execution time in nanoseconds.
     * @return Frame time in nanoseconds
     */
    public long getFrameTime() {
        return frameTime;
    }
    
    /**
     * Get the frame execution time in milliseconds.
     * @return Frame time in milliseconds
     */
    public double getFrameTimeMs() {
        return frameTime / 1_000_000.0;
    }
    
    /**
     * Get the amount of GPU memory currently used in bytes.
     * @return GPU memory used
     */
    public long getGpuMemoryUsed() {
        return gpuMemoryUsed;
    }
    
    /**
     * Get the total available GPU memory in bytes.
     * @return Total GPU memory
     */
    public long getGpuMemoryTotal() {
        return gpuMemoryTotal;
    }
    
    /**
     * Get the percentage of GPU memory used.
     * @return Memory usage percentage (0.0 to 1.0)
     */
    public double getMemoryUsagePercent() {
        return gpuMemoryTotal > 0 ? (double) gpuMemoryUsed / gpuMemoryTotal : 0.0;
    }
    
    /**
     * Get the number of compute dispatches in the current frame.
     * @return Dispatch count
     */
    public int getDispatchCount() {
        return dispatchCount;
    }
    
    /**
     * Get the number of active GPU buffers.
     * @return Buffer count
     */
    public int getBufferCount() {
        return bufferCount;
    }
    
    /**
     * Get the number of compiled shaders.
     * @return Shader count
     */
    public int getShaderCount() {
        return shaderCount;
    }
    
    @Override
    public String toString() {
        return String.format("GPUStats{frameTime=%.2fms, memory=%d/%d (%.1f%%), dispatches=%d, buffers=%d, shaders=%d}",
                getFrameTimeMs(), gpuMemoryUsed, gpuMemoryTotal, getMemoryUsagePercent() * 100,
                dispatchCount, bufferCount, shaderCount);
    }
    
    /**
     * Create a builder for constructing GPUStats instances.
     * @return New GPUStats builder
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Builder pattern for constructing GPUStats instances.
     */
    public static class Builder {
        private long frameTime = 0;
        private long gpuMemoryUsed = 0;
        private long gpuMemoryTotal = 0;
        private int dispatchCount = 0;
        private int bufferCount = 0;
        private int shaderCount = 0;
        
        public Builder withFrameTime(long frameTime) {
            this.frameTime = frameTime;
            return this;
        }
        
        public Builder withFrameTimeMs(double frameTimeMs) {
            this.frameTime = (long) (frameTimeMs * 1_000_000);
            return this;
        }
        
        public Builder withGpuMemoryUsed(long gpuMemoryUsed) {
            this.gpuMemoryUsed = gpuMemoryUsed;
            return this;
        }
        
        public Builder withGpuMemoryTotal(long gpuMemoryTotal) {
            this.gpuMemoryTotal = gpuMemoryTotal;
            return this;
        }
        
        public Builder withDispatchCount(int dispatchCount) {
            this.dispatchCount = dispatchCount;
            return this;
        }
        
        public Builder withBufferCount(int bufferCount) {
            this.bufferCount = bufferCount;
            return this;
        }
        
        public Builder withShaderCount(int shaderCount) {
            this.shaderCount = shaderCount;
            return this;
        }
        
        public GPUStats build() {
            return new GPUStats(frameTime, gpuMemoryUsed, gpuMemoryTotal,
                              dispatchCount, bufferCount, shaderCount);
        }
    }
}