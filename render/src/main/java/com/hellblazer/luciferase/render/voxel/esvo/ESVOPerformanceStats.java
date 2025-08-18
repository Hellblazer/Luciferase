/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * Licensed under the AGPL License, Version 3.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hellblazer.luciferase.render.voxel.esvo;

/**
 * Performance statistics for ESVO operations.
 */
public class ESVOPerformanceStats {
    
    private final long totalNodesUploaded;
    private final long totalPagesUploaded;
    private final long uploadTimeMs;
    private final long memoryUsage;
    private final String compilationStats;
    
    public ESVOPerformanceStats(long totalNodesUploaded, long totalPagesUploaded, 
                               long uploadTimeMs, long memoryUsage, String compilationStats) {
        this.totalNodesUploaded = totalNodesUploaded;
        this.totalPagesUploaded = totalPagesUploaded;
        this.uploadTimeMs = uploadTimeMs;
        this.memoryUsage = memoryUsage;
        this.compilationStats = compilationStats;
    }
    
    public long getTotalNodesUploaded() {
        return totalNodesUploaded;
    }
    
    public long getTotalPagesUploaded() {
        return totalPagesUploaded;
    }
    
    public long getUploadTimeMs() {
        return uploadTimeMs;
    }
    
    public long getMemoryUsage() {
        return memoryUsage;
    }
    
    public String getCompilationStats() {
        return compilationStats;
    }
    
    @Override
    public String toString() {
        return String.format("ESVOPerformanceStats{nodes=%d, pages=%d, uploadTime=%dms, memory=%d, compilation=%s}",
                           totalNodesUploaded, totalPagesUploaded, uploadTimeMs, memoryUsage, compilationStats);
    }
}