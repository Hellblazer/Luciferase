/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.esvo.gpu;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Central manager for GPU memory allocation and streaming.
 *
 * <p>Manages GPU VRAM allocation, tracks memory pressure, and coordinates
 * buffer pooling and streaming for large scene support.
 *
 * <p>Key features:
 * <ul>
 *   <li>VRAM capacity detection from GPUCapabilities</li>
 *   <li>Memory pressure monitoring with configurable thresholds</li>
 *   <li>Buffer pooling via GPUBufferPool</li>
 *   <li>Pressure callbacks for eviction and streaming triggers</li>
 *   <li>Large scene streaming support (exceeding GPU VRAM)</li>
 * </ul>
 *
 * @see GPUBufferPool
 * @see GPUMemoryPressure
 * @see GPUMemoryStats
 */
public class GPUMemoryManager {

    /**
     * Configuration for memory manager thresholds.
     */
    public record MemoryConfig(
        double moderateThreshold,   // Default 0.75
        double highThreshold,       // Default 0.85
        double criticalThreshold,   // Default 0.95
        double poolSizeRatio        // Fraction of VRAM for pool (default 0.8)
    ) {
        public static MemoryConfig defaults() {
            return new MemoryConfig(0.75, 0.85, 0.95, 0.8);
        }

        public MemoryConfig {
            if (moderateThreshold <= 0 || moderateThreshold > 1) {
                throw new IllegalArgumentException("Moderate threshold must be in (0, 1]");
            }
            if (highThreshold <= moderateThreshold || highThreshold > 1) {
                throw new IllegalArgumentException("High threshold must be in (moderate, 1]");
            }
            if (criticalThreshold <= highThreshold || criticalThreshold > 1) {
                throw new IllegalArgumentException("Critical threshold must be in (high, 1]");
            }
            if (poolSizeRatio <= 0 || poolSizeRatio > 1) {
                throw new IllegalArgumentException("Pool size ratio must be in (0, 1]");
            }
        }
    }

    /**
     * Callback for memory pressure events.
     */
    @FunctionalInterface
    public interface PressureListener {
        void onPressureChange(GPUMemoryPressure oldPressure, GPUMemoryPressure newPressure);
    }

    private final GPUCapabilities capabilities;
    private final MemoryConfig config;
    private final GPUBufferPool bufferPool;
    private final List<PressureListener> pressureListeners;

    // Statistics tracking
    private final AtomicLong streamInBytes;
    private final AtomicLong streamOutBytes;
    private final AtomicLong evictionCount;
    private final AtomicLong peakAllocatedBytes;

    // Current state
    private volatile GPUMemoryPressure currentPressure;

    /**
     * Creates a memory manager with default configuration.
     *
     * @param capabilities GPU capabilities for VRAM detection
     */
    public GPUMemoryManager(GPUCapabilities capabilities) {
        this(capabilities, MemoryConfig.defaults());
    }

    /**
     * Creates a memory manager with custom configuration.
     *
     * @param capabilities GPU capabilities for VRAM detection
     * @param config       memory configuration
     */
    public GPUMemoryManager(GPUCapabilities capabilities, MemoryConfig config) {
        if (capabilities == null) {
            throw new IllegalArgumentException("Capabilities cannot be null");
        }
        if (config == null) {
            throw new IllegalArgumentException("Config cannot be null");
        }

        this.capabilities = capabilities;
        this.config = config;

        long poolSize = (long) (capabilities.globalMemorySize() * config.poolSizeRatio());
        this.bufferPool = new GPUBufferPool(poolSize);
        this.pressureListeners = new CopyOnWriteArrayList<>();

        this.streamInBytes = new AtomicLong(0);
        this.streamOutBytes = new AtomicLong(0);
        this.evictionCount = new AtomicLong(0);
        this.peakAllocatedBytes = new AtomicLong(0);

        this.currentPressure = GPUMemoryPressure.NONE;
    }

    /**
     * Creates a memory manager for testing with mock VRAM size.
     *
     * @param vramBytes mock VRAM size in bytes
     * @return memory manager with mock capabilities
     */
    public static GPUMemoryManager forTesting(long vramBytes) {
        var mockCapabilities = new GPUCapabilities(
            GPUVendor.UNKNOWN,
            "Mock GPU",
            "Mock",
            4,
            256,
            vramBytes,
            16384,
            1000,
            "OpenCL 2.0"
        );
        return new GPUMemoryManager(mockCapabilities);
    }

    /**
     * Allocates a buffer from the pool.
     *
     * @param requestedBytes requested buffer size
     * @return pooled buffer, or null if allocation failed
     */
    public GPUBufferPool.PooledBuffer allocate(long requestedBytes) {
        var buffer = bufferPool.allocate(requestedBytes);

        if (buffer != null) {
            streamInBytes.addAndGet(requestedBytes);
            updatePeakAllocation();
            updatePressure();
        }

        return buffer;
    }

    /**
     * Releases a buffer back to the pool.
     *
     * @param bufferId ID of the buffer to release
     * @return true if buffer was found and released
     */
    public boolean release(String bufferId) {
        boolean released = bufferPool.release(bufferId);
        if (released) {
            updatePressure();
        }
        return released;
    }

    /**
     * Checks if an allocation of the given size can be made.
     *
     * @param requestedBytes requested buffer size
     * @return true if allocation is likely to succeed
     */
    public boolean canAllocate(long requestedBytes) {
        var stats = getStats();
        return stats.availableBytes() >= requestedBytes ||
            currentPressure != GPUMemoryPressure.CRITICAL;
    }

    /**
     * Attempts to free memory to reach target utilization.
     *
     * @param targetUtilization target utilization (0.0 to 1.0)
     * @return bytes freed
     */
    public long freeMemory(double targetUtilization) {
        if (targetUtilization < 0 || targetUtilization > 1) {
            throw new IllegalArgumentException("Target utilization must be in [0, 1]");
        }

        var stats = getStats();
        double currentUtilization = stats.utilization();

        if (currentUtilization <= targetUtilization) {
            return 0;  // Already below target
        }

        long bytesToFree = (long) ((currentUtilization - targetUtilization) * stats.totalBytes());
        long freed = bufferPool.evict(bytesToFree);

        if (freed > 0) {
            evictionCount.incrementAndGet();
            streamOutBytes.addAndGet(freed);
            updatePressure();
        }

        return freed;
    }

    /**
     * Forces eviction of unused buffers to reduce memory pressure.
     *
     * @return bytes freed
     */
    public long forceEviction() {
        return freeMemory(config.moderateThreshold() * 0.9);  // Target 90% of moderate threshold
    }

    /**
     * Adds a listener for memory pressure changes.
     *
     * @param listener listener to add
     */
    public void addPressureListener(PressureListener listener) {
        pressureListeners.add(listener);
    }

    /**
     * Removes a pressure listener.
     *
     * @param listener listener to remove
     */
    public void removePressureListener(PressureListener listener) {
        pressureListeners.remove(listener);
    }

    /**
     * Returns current memory pressure level.
     */
    public GPUMemoryPressure getPressure() {
        return currentPressure;
    }

    /**
     * Returns current memory statistics.
     */
    public GPUMemoryStats getStats() {
        var poolStats = bufferPool.getStats();
        long totalBytes = capabilities.globalMemorySize();
        long allocatedBytes = poolStats.activeBytes() + poolStats.freeBytes();

        return GPUMemoryStats.create(
            totalBytes,
            allocatedBytes,
            currentPressure,
            poolStats.activeBuffers(),
            evictionCount.get(),
            streamInBytes.get(),
            streamOutBytes.get(),
            peakAllocatedBytes.get()
        );
    }

    /**
     * Returns the underlying buffer pool.
     */
    public GPUBufferPool getBufferPool() {
        return bufferPool;
    }

    /**
     * Returns the GPU capabilities.
     */
    public GPUCapabilities getCapabilities() {
        return capabilities;
    }

    /**
     * Returns the memory configuration.
     */
    public MemoryConfig getConfig() {
        return config;
    }

    /**
     * Returns total VRAM capacity in bytes.
     */
    public long getTotalVRAM() {
        return capabilities.globalMemorySize();
    }

    /**
     * Clears all buffers and resets statistics.
     */
    public void reset() {
        bufferPool.clear();
        streamInBytes.set(0);
        streamOutBytes.set(0);
        evictionCount.set(0);
        peakAllocatedBytes.set(0);
        currentPressure = GPUMemoryPressure.NONE;
    }

    // Private helpers

    private void updatePressure() {
        var stats = getStats();
        double utilization = stats.utilization();

        GPUMemoryPressure newPressure = GPUMemoryPressure.fromUtilization(
            utilization,
            config.moderateThreshold(),
            config.highThreshold(),
            config.criticalThreshold()
        );

        if (newPressure != currentPressure) {
            GPUMemoryPressure oldPressure = currentPressure;
            currentPressure = newPressure;
            notifyPressureChange(oldPressure, newPressure);
        }
    }

    private void updatePeakAllocation() {
        var poolStats = bufferPool.getStats();
        long current = poolStats.activeBytes();
        peakAllocatedBytes.updateAndGet(peak -> Math.max(peak, current));
    }

    private void notifyPressureChange(GPUMemoryPressure oldPressure, GPUMemoryPressure newPressure) {
        for (var listener : pressureListeners) {
            try {
                listener.onPressureChange(oldPressure, newPressure);
            } catch (Exception e) {
                // Log but don't propagate
            }
        }
    }
}
