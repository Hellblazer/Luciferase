/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.render.profiler;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Logger;

import static org.lwjgl.opengl.GL45.*;

/**
 * Comprehensive profiler for rendering performance metrics.
 * Tracks GPU timings, CPU timings, memory usage, and draw call statistics.
 */
public class RenderProfiler {
    
    private static final Logger log = Logger.getLogger(RenderProfiler.class.getName());
    
    // Profiling categories
    public enum Category {
        FRAME_TOTAL("Frame Total"),
        FRUSTUM_CULLING("Frustum Culling"),
        LOD_SELECTION("LOD Selection"),
        GPU_UPLOAD("GPU Upload"),
        VOXEL_RENDER("Voxel Rendering"),
        POST_PROCESSING("Post Processing"),
        SHADOW_MAPPING("Shadow Mapping"),
        LIGHTING("Lighting"),
        TRANSPARENCY("Transparency"),
        UI_RENDER("UI Rendering");
        
        private final String displayName;
        
        Category(String displayName) {
            this.displayName = displayName;
        }
        
        public String getDisplayName() {
            return displayName;
        }
    }
    
    // Timer entry for hierarchical profiling
    private static class TimerEntry {
        final String name;
        final long startTime;
        final List<TimerEntry> children;
        long endTime;
        long gpuQueryId;
        
        TimerEntry(String name, long startTime) {
            this.name = name;
            this.startTime = startTime;
            this.children = new ArrayList<>();
            this.gpuQueryId = -1;
        }
        
        void end(long endTime) {
            this.endTime = endTime;
        }
        
        long getDuration() {
            return endTime - startTime;
        }
    }
    
    // Frame statistics
    private static class FrameStats {
        final long frameNumber;
        final Map<Category, Long> cpuTimings;
        final Map<Category, Long> gpuTimings;
        final int drawCalls;
        final int trianglesRendered;
        final int voxelsRendered;
        final long gpuMemoryUsed;
        final float frameTime;
        
        FrameStats(long frameNumber, Map<Category, Long> cpuTimings, Map<Category, Long> gpuTimings,
                  int drawCalls, int trianglesRendered, int voxelsRendered, 
                  long gpuMemoryUsed, float frameTime) {
            this.frameNumber = frameNumber;
            this.cpuTimings = new HashMap<>(cpuTimings);
            this.gpuTimings = new HashMap<>(gpuTimings);
            this.drawCalls = drawCalls;
            this.trianglesRendered = trianglesRendered;
            this.voxelsRendered = voxelsRendered;
            this.gpuMemoryUsed = gpuMemoryUsed;
            this.frameTime = frameTime;
        }
    }
    
    // Configuration
    private final boolean useGPUTimers;
    private final int historySize;
    private final int reportInterval;
    
    // Current frame data
    private final AtomicLong frameCounter = new AtomicLong(0);
    private final ThreadLocal<Stack<TimerEntry>> timerStack = ThreadLocal.withInitial(Stack::new);
    private final Map<Category, LongAdder> cpuTimings = new ConcurrentHashMap<>();
    private final Map<Category, LongAdder> gpuTimings = new ConcurrentHashMap<>();
    
    // Statistics
    private final LongAdder drawCallCounter = new LongAdder();
    private final LongAdder triangleCounter = new LongAdder();
    private final LongAdder voxelCounter = new LongAdder();
    private final AtomicLong gpuMemoryUsed = new AtomicLong(0);
    
    // Frame history
    private final Deque<FrameStats> frameHistory = new LinkedList<>();
    
    // GPU query objects
    private final Map<Category, Integer> gpuQueries = new HashMap<>();
    private final Map<Category, Boolean> gpuQueryActive = new HashMap<>();
    
    // Performance thresholds
    private float targetFrameTime = 16.67f; // 60 FPS
    private float warningThreshold = 20.0f; // 50 FPS
    private float criticalThreshold = 33.33f; // 30 FPS
    
    public RenderProfiler(boolean useGPUTimers, int historySize, int reportInterval) {
        this.useGPUTimers = useGPUTimers;
        this.historySize = historySize;
        this.reportInterval = reportInterval;
        
        // Initialize counters
        for (var category : Category.values()) {
            cpuTimings.put(category, new LongAdder());
            gpuTimings.put(category, new LongAdder());
            
            if (useGPUTimers) {
                gpuQueries.put(category, glGenQueries());
                gpuQueryActive.put(category, false);
            }
        }
    }
    
    /**
     * Start a new frame.
     */
    public void beginFrame() {
        var frame = frameCounter.incrementAndGet();
        
        // Reset per-frame counters
        drawCallCounter.reset();
        triangleCounter.reset();
        voxelCounter.reset();
        
        // Clear timings
        cpuTimings.values().forEach(LongAdder::reset);
        gpuTimings.values().forEach(LongAdder::reset);
        
        // Start frame timer
        beginTimer(Category.FRAME_TOTAL);
    }
    
    /**
     * End the current frame.
     */
    public void endFrame() {
        endTimer(Category.FRAME_TOTAL);
        
        // Collect GPU timings if available
        if (useGPUTimers) {
            collectGPUTimings();
        }
        
        // Calculate frame time
        float frameTime = cpuTimings.get(Category.FRAME_TOTAL).sum() / 1_000_000.0f;
        
        // Store frame statistics
        var stats = new FrameStats(
            frameCounter.get(),
            collectTimings(cpuTimings),
            collectTimings(gpuTimings),
            drawCallCounter.intValue(),
            triangleCounter.intValue(),
            voxelCounter.intValue(),
            gpuMemoryUsed.get(),
            frameTime
        );
        
        frameHistory.addLast(stats);
        if (frameHistory.size() > historySize) {
            frameHistory.removeFirst();
        }
        
        // Report if needed
        if (frameCounter.get() % reportInterval == 0) {
            reportStatistics();
        }
        
        // Check performance
        checkPerformance(frameTime);
    }
    
    /**
     * Begin timing a category.
     */
    public void beginTimer(Category category) {
        var entry = new TimerEntry(category.getDisplayName(), System.nanoTime());
        
        // CPU timing
        timerStack.get().push(entry);
        
        // GPU timing
        if (useGPUTimers && !gpuQueryActive.get(category)) {
            glBeginQuery(GL_TIME_ELAPSED, gpuQueries.get(category));
            gpuQueryActive.put(category, true);
            entry.gpuQueryId = gpuQueries.get(category);
        }
    }
    
    /**
     * End timing a category.
     */
    public void endTimer(Category category) {
        var stack = timerStack.get();
        if (stack.isEmpty()) {
            log.warning("Timer stack empty when ending " + category);
            return;
        }
        
        var entry = stack.pop();
        entry.end(System.nanoTime());
        
        // Record CPU timing
        cpuTimings.get(category).add(entry.getDuration());
        
        // End GPU query
        if (useGPUTimers && gpuQueryActive.get(category)) {
            glEndQuery(GL_TIME_ELAPSED);
            gpuQueryActive.put(category, false);
        }
    }
    
    /**
     * Record a draw call.
     */
    public void recordDrawCall(int triangles) {
        drawCallCounter.increment();
        triangleCounter.add(triangles);
    }
    
    /**
     * Record voxel rendering.
     */
    public void recordVoxels(int voxelCount) {
        voxelCounter.add(voxelCount);
    }
    
    /**
     * Update GPU memory usage.
     */
    public void updateGPUMemory(long bytesUsed) {
        gpuMemoryUsed.set(bytesUsed);
    }
    
    /**
     * Get average frame time over history.
     */
    public float getAverageFrameTime() {
        if (frameHistory.isEmpty()) {
            return 0;
        }
        
        float sum = 0;
        for (var stats : frameHistory) {
            sum += stats.frameTime;
        }
        return sum / frameHistory.size();
    }
    
    /**
     * Get current FPS.
     */
    public float getCurrentFPS() {
        float avgFrameTime = getAverageFrameTime();
        return avgFrameTime > 0 ? 1000.0f / avgFrameTime : 0;
    }
    
    /**
     * Get performance report.
     */
    public String getPerformanceReport() {
        var sb = new StringBuilder();
        sb.append("=== Render Performance Report ===\n");
        sb.append(String.format("Frame: %d | FPS: %.1f | Avg Frame: %.2fms\n",
                frameCounter.get(), getCurrentFPS(), getAverageFrameTime()));
        
        // Category breakdown
        sb.append("\nTiming Breakdown:\n");
        for (var category : Category.values()) {
            long cpuTime = getAverageCPUTime(category);
            long gpuTime = getAverageGPUTime(category);
            if (cpuTime > 0 || gpuTime > 0) {
                sb.append(String.format("  %-20s CPU: %6.2fms GPU: %6.2fms\n",
                        category.getDisplayName(),
                        cpuTime / 1_000_000.0f,
                        gpuTime / 1_000_000.0f));
            }
        }
        
        // Statistics
        sb.append("\nStatistics:\n");
        sb.append(String.format("  Draw Calls: %d\n", getAverageDrawCalls()));
        sb.append(String.format("  Triangles: %,d\n", getAverageTriangles()));
        sb.append(String.format("  Voxels: %,d\n", getAverageVoxels()));
        sb.append(String.format("  GPU Memory: %.2f MB\n", gpuMemoryUsed.get() / (1024.0 * 1024.0)));
        
        return sb.toString();
    }
    
    /**
     * Reset all statistics.
     */
    public void reset() {
        frameCounter.set(0);
        frameHistory.clear();
        cpuTimings.values().forEach(LongAdder::reset);
        gpuTimings.values().forEach(LongAdder::reset);
        drawCallCounter.reset();
        triangleCounter.reset();
        voxelCounter.reset();
    }
    
    /**
     * Cleanup resources.
     */
    public void dispose() {
        if (useGPUTimers) {
            for (int query : gpuQueries.values()) {
                glDeleteQueries(query);
            }
            gpuQueries.clear();
        }
    }
    
    // Private helper methods
    
    private void collectGPUTimings() {
        for (var entry : gpuQueries.entrySet()) {
            var category = entry.getKey();
            var query = entry.getValue();
            
            if (glGetQueryObjecti(query, GL_QUERY_RESULT_AVAILABLE) == GL_TRUE) {
                long elapsed = glGetQueryObjectui64(query, GL_QUERY_RESULT);
                gpuTimings.get(category).add(elapsed);
            }
        }
    }
    
    private Map<Category, Long> collectTimings(Map<Category, LongAdder> timings) {
        var result = new HashMap<Category, Long>();
        for (var entry : timings.entrySet()) {
            result.put(entry.getKey(), entry.getValue().sum());
        }
        return result;
    }
    
    private void checkPerformance(float frameTime) {
        if (frameTime > criticalThreshold) {
            log.severe(String.format("Critical performance: %.2fms frame time (%.1f FPS)",
                    frameTime, 1000.0f / frameTime));
        } else if (frameTime > warningThreshold) {
            log.warning(String.format("Poor performance: %.2fms frame time (%.1f FPS)",
                    frameTime, 1000.0f / frameTime));
        }
    }
    
    private void reportStatistics() {
        log.info("\n" + getPerformanceReport());
    }
    
    private long getAverageCPUTime(Category category) {
        if (frameHistory.isEmpty()) return 0;
        
        long sum = 0;
        for (var stats : frameHistory) {
            sum += stats.cpuTimings.getOrDefault(category, 0L);
        }
        return sum / frameHistory.size();
    }
    
    private long getAverageGPUTime(Category category) {
        if (frameHistory.isEmpty()) return 0;
        
        long sum = 0;
        for (var stats : frameHistory) {
            sum += stats.gpuTimings.getOrDefault(category, 0L);
        }
        return sum / frameHistory.size();
    }
    
    private int getAverageDrawCalls() {
        if (frameHistory.isEmpty()) return 0;
        
        int sum = 0;
        for (var stats : frameHistory) {
            sum += stats.drawCalls;
        }
        return sum / frameHistory.size();
    }
    
    private int getAverageTriangles() {
        if (frameHistory.isEmpty()) return 0;
        
        int sum = 0;
        for (var stats : frameHistory) {
            sum += stats.trianglesRendered;
        }
        return sum / frameHistory.size();
    }
    
    private int getAverageVoxels() {
        if (frameHistory.isEmpty()) return 0;
        
        int sum = 0;
        for (var stats : frameHistory) {
            sum += stats.voxelsRendered;
        }
        return sum / frameHistory.size();
    }
    
    // Setters for thresholds
    public void setTargetFrameTime(float ms) { this.targetFrameTime = ms; }
    public void setWarningThreshold(float ms) { this.warningThreshold = ms; }
    public void setCriticalThreshold(float ms) { this.criticalThreshold = ms; }
}