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

/**
 * Snapshot of GPU memory statistics.
 *
 * <p>Tracks current memory allocation, pressure level, and usage patterns
 * for GPU memory management decisions.
 *
 * @param totalBytes         total GPU VRAM capacity in bytes
 * @param allocatedBytes     currently allocated bytes
 * @param availableBytes     available bytes for allocation
 * @param pressure           current memory pressure level
 * @param allocationCount    number of active allocations
 * @param evictionCount      total evictions since creation
 * @param streamInBytes      bytes streamed to GPU since creation
 * @param streamOutBytes     bytes evicted from GPU since creation
 * @param peakAllocatedBytes highest allocation watermark
 */
public record GPUMemoryStats(
    long totalBytes,
    long allocatedBytes,
    long availableBytes,
    GPUMemoryPressure pressure,
    int allocationCount,
    long evictionCount,
    long streamInBytes,
    long streamOutBytes,
    long peakAllocatedBytes
) {
    /**
     * Creates stats with calculated available bytes.
     */
    public static GPUMemoryStats create(
        long totalBytes,
        long allocatedBytes,
        GPUMemoryPressure pressure,
        int allocationCount,
        long evictionCount,
        long streamInBytes,
        long streamOutBytes,
        long peakAllocatedBytes
    ) {
        long available = Math.max(0, totalBytes - allocatedBytes);
        return new GPUMemoryStats(
            totalBytes, allocatedBytes, available, pressure,
            allocationCount, evictionCount, streamInBytes, streamOutBytes,
            peakAllocatedBytes
        );
    }

    /**
     * Creates an empty stats object with given total capacity.
     */
    public static GPUMemoryStats empty(long totalBytes) {
        return new GPUMemoryStats(
            totalBytes, 0, totalBytes, GPUMemoryPressure.NONE,
            0, 0, 0, 0, 0
        );
    }

    /**
     * Returns utilization as a percentage (0.0 to 1.0).
     */
    public double utilization() {
        return totalBytes > 0 ? (double) allocatedBytes / totalBytes : 0.0;
    }

    /**
     * Returns true if pressure is at or above MODERATE.
     */
    public boolean isUnderPressure() {
        return pressure.ordinal() >= GPUMemoryPressure.MODERATE.ordinal();
    }

    /**
     * Returns true if pressure is CRITICAL.
     */
    public boolean isCritical() {
        return pressure == GPUMemoryPressure.CRITICAL;
    }

    /**
     * Returns a human-readable summary.
     */
    public String summary() {
        return String.format(
            "GPU Memory: %.1f%% used (%.2f MB / %.2f MB), pressure=%s, allocations=%d, evictions=%d",
            utilization() * 100,
            allocatedBytes / (1024.0 * 1024),
            totalBytes / (1024.0 * 1024),
            pressure,
            allocationCount,
            evictionCount
        );
    }

    /**
     * Validates the stats invariants.
     */
    public GPUMemoryStats {
        if (totalBytes < 0) {
            throw new IllegalArgumentException("Total bytes cannot be negative");
        }
        if (allocatedBytes < 0) {
            throw new IllegalArgumentException("Allocated bytes cannot be negative");
        }
        if (allocatedBytes > totalBytes) {
            throw new IllegalArgumentException("Allocated bytes cannot exceed total bytes");
        }
        if (pressure == null) {
            throw new IllegalArgumentException("Pressure cannot be null");
        }
        if (allocationCount < 0) {
            throw new IllegalArgumentException("Allocation count cannot be negative");
        }
    }
}
