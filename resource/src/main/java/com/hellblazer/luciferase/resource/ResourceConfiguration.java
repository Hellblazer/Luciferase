package com.hellblazer.luciferase.resource;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration for the unified resource management system.
 * Provides settings for memory pooling, eviction policies, and resource tracking.
 */
public class ResourceConfiguration {
    
    // Memory pool configuration
    private final long maxPoolSizeBytes;
    private final double highWaterMark;  // Percentage (0.0-1.0) at which to start eviction
    private final double lowWaterMark;   // Percentage (0.0-1.0) to evict down to
    
    // Eviction policy configuration
    private final EvictionPolicy evictionPolicy;
    private final Duration maxIdleTime;
    private final int maxResourceCount;
    
    // Resource tracking configuration
    private final boolean enableDetailedTracking;
    private final boolean enableLeakDetection;
    private final Duration leakDetectionInterval;
    
    // Performance configuration
    private final boolean enableAsyncCleanup;
    private final int cleanupThreadCount;
    private final Duration cleanupInterval;
    
    // API-specific limits
    private final long maxOpenGLBufferSize;
    private final long maxOpenCLBufferSize;
    private final int maxTextureSize;
    private final int maxShaderCount;
    
    public enum EvictionPolicy {
        LRU("Least Recently Used"),
        LFU("Least Frequently Used"),
        FIFO("First In First Out"),
        SIZE_BASED("Largest Resources First"),
        HYBRID("Combined LRU and Size");
        
        private final String description;
        
        EvictionPolicy(String description) {
            this.description = description;
        }
        
        public String getDescription() {
            return description;
        }
    }
    
    private ResourceConfiguration(Builder builder) {
        this.maxPoolSizeBytes = builder.maxPoolSizeBytes;
        this.highWaterMark = builder.highWaterMark;
        this.lowWaterMark = builder.lowWaterMark;
        this.evictionPolicy = builder.evictionPolicy;
        this.maxIdleTime = builder.maxIdleTime;
        this.maxResourceCount = builder.maxResourceCount;
        this.enableDetailedTracking = builder.enableDetailedTracking;
        this.enableLeakDetection = builder.enableLeakDetection;
        this.leakDetectionInterval = builder.leakDetectionInterval;
        this.enableAsyncCleanup = builder.enableAsyncCleanup;
        this.cleanupThreadCount = builder.cleanupThreadCount;
        this.cleanupInterval = builder.cleanupInterval;
        this.maxOpenGLBufferSize = builder.maxOpenGLBufferSize;
        this.maxOpenCLBufferSize = builder.maxOpenCLBufferSize;
        this.maxTextureSize = builder.maxTextureSize;
        this.maxShaderCount = builder.maxShaderCount;
        
        validate();
    }
    
    private void validate() {
        if (maxPoolSizeBytes <= 0) {
            throw new IllegalArgumentException("Max pool size must be positive");
        }
        if (highWaterMark <= 0 || highWaterMark > 1.0) {
            throw new IllegalArgumentException("High water mark must be between 0 and 1");
        }
        if (lowWaterMark <= 0 || lowWaterMark >= highWaterMark) {
            throw new IllegalArgumentException("Low water mark must be between 0 and high water mark");
        }
        if (maxResourceCount <= 0) {
            throw new IllegalArgumentException("Max resource count must be positive");
        }
        if (cleanupThreadCount < 0) {
            throw new IllegalArgumentException("Cleanup thread count cannot be negative");
        }
    }
    
    /**
     * Creates a default configuration suitable for most applications.
     */
    public static ResourceConfiguration defaultConfig() {
        return new Builder()
            .withMaxPoolSize(512L * 1024 * 1024)  // 512 MB
            .withHighWaterMark(0.9)
            .withLowWaterMark(0.7)
            .withEvictionPolicy(EvictionPolicy.LRU)
            .withMaxIdleTime(Duration.ofMinutes(5))
            .withMaxResourceCount(10000)
            .withDetailedTracking(true)
            .withLeakDetection(true)
            .withLeakDetectionInterval(Duration.ofSeconds(30))
            .withAsyncCleanup(true)
            .withCleanupThreadCount(2)
            .withCleanupInterval(Duration.ofSeconds(10))
            .withMaxOpenGLBufferSize(256L * 1024 * 1024)  // 256 MB
            .withMaxOpenCLBufferSize(256L * 1024 * 1024)  // 256 MB
            .withMaxTextureSize(4096)
            .withMaxShaderCount(100)
            .build();
    }
    
    /**
     * Creates a minimal configuration for testing or low-memory environments.
     */
    public static ResourceConfiguration minimalConfig() {
        return new Builder()
            .withMaxPoolSize(64L * 1024 * 1024)  // 64 MB
            .withHighWaterMark(0.95)
            .withLowWaterMark(0.8)
            .withEvictionPolicy(EvictionPolicy.LRU)
            .withMaxIdleTime(Duration.ofMinutes(1))
            .withMaxResourceCount(1000)
            .withDetailedTracking(false)
            .withLeakDetection(false)
            .withAsyncCleanup(false)
            .withCleanupThreadCount(0)
            .withCleanupInterval(Duration.ofSeconds(30))
            .withMaxOpenGLBufferSize(32L * 1024 * 1024)  // 32 MB
            .withMaxOpenCLBufferSize(32L * 1024 * 1024)  // 32 MB
            .withMaxTextureSize(2048)
            .withMaxShaderCount(20)
            .build();
    }
    
    /**
     * Creates a high-performance configuration for production environments.
     */
    public static ResourceConfiguration productionConfig() {
        return new Builder()
            .withMaxPoolSize(2L * 1024 * 1024 * 1024)  // 2 GB
            .withHighWaterMark(0.85)
            .withLowWaterMark(0.6)
            .withEvictionPolicy(EvictionPolicy.HYBRID)
            .withMaxIdleTime(Duration.ofMinutes(15))
            .withMaxResourceCount(50000)
            .withDetailedTracking(true)
            .withLeakDetection(true)
            .withLeakDetectionInterval(Duration.ofMinutes(1))
            .withAsyncCleanup(true)
            .withCleanupThreadCount(4)
            .withCleanupInterval(Duration.ofSeconds(5))
            .withMaxOpenGLBufferSize(512L * 1024 * 1024)  // 512 MB
            .withMaxOpenCLBufferSize(1024L * 1024 * 1024)  // 1 GB
            .withMaxTextureSize(8192)
            .withMaxShaderCount(500)
            .build();
    }
    
    // Getters
    public long getMaxPoolSizeBytes() { return maxPoolSizeBytes; }
    public double getHighWaterMark() { return highWaterMark; }
    public double getLowWaterMark() { return lowWaterMark; }
    public EvictionPolicy getEvictionPolicy() { return evictionPolicy; }
    public Duration getMaxIdleTime() { return maxIdleTime; }
    public int getMaxResourceCount() { return maxResourceCount; }
    public boolean isDetailedTrackingEnabled() { return enableDetailedTracking; }
    public boolean isLeakDetectionEnabled() { return enableLeakDetection; }
    public Duration getLeakDetectionInterval() { return leakDetectionInterval; }
    public boolean isAsyncCleanupEnabled() { return enableAsyncCleanup; }
    public int getCleanupThreadCount() { return cleanupThreadCount; }
    public Duration getCleanupInterval() { return cleanupInterval; }
    public long getMaxOpenGLBufferSize() { return maxOpenGLBufferSize; }
    public long getMaxOpenCLBufferSize() { return maxOpenCLBufferSize; }
    public int getMaxTextureSize() { return maxTextureSize; }
    public int getMaxShaderCount() { return maxShaderCount; }
    
    public static class Builder {
        private long maxPoolSizeBytes = 512L * 1024 * 1024;
        private double highWaterMark = 0.9;
        private double lowWaterMark = 0.7;
        private EvictionPolicy evictionPolicy = EvictionPolicy.LRU;
        private Duration maxIdleTime = Duration.ofMinutes(5);
        private int maxResourceCount = 10000;
        private boolean enableDetailedTracking = true;
        private boolean enableLeakDetection = true;
        private Duration leakDetectionInterval = Duration.ofSeconds(30);
        private boolean enableAsyncCleanup = true;
        private int cleanupThreadCount = 2;
        private Duration cleanupInterval = Duration.ofSeconds(10);
        private long maxOpenGLBufferSize = 256L * 1024 * 1024;
        private long maxOpenCLBufferSize = 256L * 1024 * 1024;
        private int maxTextureSize = 4096;
        private int maxShaderCount = 100;
        
        public Builder withMaxPoolSize(long bytes) {
            this.maxPoolSizeBytes = bytes;
            return this;
        }
        
        public Builder withHighWaterMark(double percentage) {
            this.highWaterMark = percentage;
            return this;
        }
        
        public Builder withLowWaterMark(double percentage) {
            this.lowWaterMark = percentage;
            return this;
        }
        
        public Builder withEvictionPolicy(EvictionPolicy policy) {
            this.evictionPolicy = Objects.requireNonNull(policy);
            return this;
        }
        
        public Builder withMaxIdleTime(Duration duration) {
            this.maxIdleTime = Objects.requireNonNull(duration);
            return this;
        }
        
        public Builder withMaxResourceCount(int count) {
            this.maxResourceCount = count;
            return this;
        }
        
        public Builder withDetailedTracking(boolean enable) {
            this.enableDetailedTracking = enable;
            return this;
        }
        
        public Builder withLeakDetection(boolean enable) {
            this.enableLeakDetection = enable;
            return this;
        }
        
        public Builder withLeakDetectionInterval(Duration interval) {
            this.leakDetectionInterval = Objects.requireNonNull(interval);
            return this;
        }
        
        public Builder withAsyncCleanup(boolean enable) {
            this.enableAsyncCleanup = enable;
            return this;
        }
        
        public Builder withCleanupThreadCount(int count) {
            this.cleanupThreadCount = count;
            return this;
        }
        
        public Builder withCleanupInterval(Duration interval) {
            this.cleanupInterval = Objects.requireNonNull(interval);
            return this;
        }
        
        public Builder withMaxOpenGLBufferSize(long bytes) {
            this.maxOpenGLBufferSize = bytes;
            return this;
        }
        
        public Builder withMaxOpenCLBufferSize(long bytes) {
            this.maxOpenCLBufferSize = bytes;
            return this;
        }
        
        public Builder withMaxTextureSize(int size) {
            this.maxTextureSize = size;
            return this;
        }
        
        public Builder withMaxShaderCount(int count) {
            this.maxShaderCount = count;
            return this;
        }
        
        public ResourceConfiguration build() {
            return new ResourceConfiguration(this);
        }
    }
    
    @Override
    public String toString() {
        return String.format(
            "ResourceConfiguration[pool=%d MB, watermarks=(%.1f%%, %.1f%%), policy=%s, tracking=%s, leak detection=%s]",
            maxPoolSizeBytes / (1024 * 1024),
            highWaterMark * 100,
            lowWaterMark * 100,
            evictionPolicy,
            enableDetailedTracking ? "enabled" : "disabled",
            enableLeakDetection ? "enabled" : "disabled"
        );
    }
}