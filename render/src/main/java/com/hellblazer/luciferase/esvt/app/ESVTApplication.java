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
package com.hellblazer.luciferase.esvt.app;

import com.hellblazer.luciferase.esvt.core.ESVTData;
import com.hellblazer.luciferase.esvt.io.ESVTDeserializer;
import com.hellblazer.luciferase.esvt.traversal.ESVTRay;
import com.hellblazer.luciferase.esvt.traversal.ESVTResult;
import com.hellblazer.luciferase.esvt.traversal.ESVTTraversal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Main application runtime for ESVT operations.
 *
 * <p>Manages the lifecycle of ESVT data, traversal engine, and rendering components.
 * Provides a unified entry point for all ESVT operations with proper resource management.
 *
 * <p>Key responsibilities:
 * <ul>
 *   <li>ESVT data loading and management</li>
 *   <li>Traversal engine initialization</li>
 *   <li>Resource lifecycle management</li>
 *   <li>Background task execution</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class ESVTApplication {
    private static final Logger log = LoggerFactory.getLogger(ESVTApplication.class);

    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ESVTData esvtData;
    private ESVTTraversal traversal;
    private ExecutorService backgroundExecutor;
    private PerformanceMonitor performanceMonitor;

    // Configuration
    private final ESVTCommandLine.Config config;

    /**
     * Create application with configuration.
     */
    public ESVTApplication(ESVTCommandLine.Config config) {
        this.config = config;
        this.performanceMonitor = new PerformanceMonitor();
    }

    /**
     * Initialize the application.
     */
    public void initialize() throws IOException {
        if (initialized.getAndSet(true)) {
            log.warn("Application already initialized");
            return;
        }

        log.info("Initializing ESVT Application");

        // Create background executor
        backgroundExecutor = Executors.newFixedThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
                r -> {
                    var t = new Thread(r, "esvt-background");
                    t.setDaemon(true);
                    return t;
                }
        );

        // Load ESVT data if input file specified
        if (config.inputFile != null) {
            loadESVTData(Path.of(config.inputFile));
        }

        log.info("ESVT Application initialized");
    }

    /**
     * Load ESVT data from file.
     */
    public void loadESVTData(Path path) throws IOException {
        log.info("Loading ESVT data from: {}", path);

        var startTime = System.nanoTime();
        var deserializer = new ESVTDeserializer();
        esvtData = deserializer.deserialize(path);

        var loadTimeMs = (System.nanoTime() - startTime) / 1_000_000.0;

        log.info("Loaded ESVT data: {} nodes, {} leaves, depth={}, load time={} ms",
                esvtData.nodeCount(), esvtData.leafCount(), esvtData.maxDepth(),
                String.format("%.2f", loadTimeMs));

        // Initialize traversal engine (stateless - takes data in castRay)
        traversal = new ESVTTraversal();
    }

    /**
     * Get the loaded ESVT data.
     */
    public ESVTData getESVTData() {
        return esvtData;
    }

    /**
     * Set ESVT data directly (for build mode).
     */
    public void setESVTData(ESVTData data) {
        this.esvtData = data;
        if (this.traversal == null) {
            this.traversal = new ESVTTraversal();
        }
    }

    /**
     * Get the traversal engine.
     */
    public ESVTTraversal getTraversal() {
        return traversal;
    }

    /**
     * Cast a ray through the loaded ESVT data.
     *
     * @param ray The ray to cast
     * @return The traversal result
     */
    public ESVTResult castRay(ESVTRay ray) {
        if (esvtData == null || traversal == null) {
            return new ESVTResult();
        }
        return traversal.castRay(ray, esvtData.nodes(), esvtData.contours(),
                                 esvtData.farPointers(), 0);
    }

    /**
     * Get the performance monitor.
     */
    public PerformanceMonitor getPerformanceMonitor() {
        return performanceMonitor;
    }

    /**
     * Check if application is initialized.
     */
    public boolean isInitialized() {
        return initialized.get();
    }

    /**
     * Check if application is running.
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Start the application main loop.
     */
    public void start() {
        if (!initialized.get()) {
            throw new IllegalStateException("Application not initialized");
        }

        if (running.getAndSet(true)) {
            log.warn("Application already running");
            return;
        }

        log.info("Starting ESVT Application");
    }

    /**
     * Stop the application.
     */
    public void stop() {
        if (!running.getAndSet(false)) {
            return;
        }

        log.info("Stopping ESVT Application");
    }

    /**
     * Shutdown the application and release resources.
     */
    public void shutdown() {
        log.info("Shutting down ESVT Application");

        stop();

        // Shutdown background executor
        if (backgroundExecutor != null) {
            backgroundExecutor.shutdown();
            try {
                if (!backgroundExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    backgroundExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                backgroundExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            backgroundExecutor = null;
        }

        // Release ESVT resources
        esvtData = null;
        traversal = null;

        initialized.set(false);

        log.info("ESVT Application shutdown complete");
    }

    /**
     * Submit a background task.
     */
    public void submitBackgroundTask(Runnable task) {
        if (backgroundExecutor != null && !backgroundExecutor.isShutdown()) {
            backgroundExecutor.submit(task);
        }
    }

    /**
     * Get configuration.
     */
    public ESVTCommandLine.Config getConfig() {
        return config;
    }

    /**
     * Performance monitoring utility.
     */
    public static class PerformanceMonitor {
        private long frameCount = 0;
        private long totalFrameTimeNanos = 0;
        private long minFrameTimeNanos = Long.MAX_VALUE;
        private long maxFrameTimeNanos = 0;

        private long rayCount = 0;
        private long totalRayTimeNanos = 0;
        private long rayHits = 0;
        private long rayMisses = 0;

        private long lastResetTime = System.nanoTime();

        public void recordFrame(long frameTimeNanos) {
            frameCount++;
            totalFrameTimeNanos += frameTimeNanos;
            minFrameTimeNanos = Math.min(minFrameTimeNanos, frameTimeNanos);
            maxFrameTimeNanos = Math.max(maxFrameTimeNanos, frameTimeNanos);
        }

        public void recordRayTraversal(long timeNanos, boolean hit) {
            rayCount++;
            totalRayTimeNanos += timeNanos;
            if (hit) {
                rayHits++;
            } else {
                rayMisses++;
            }
        }

        public void recordRayBatch(int count, long totalTimeNanos, int hits) {
            rayCount += count;
            totalRayTimeNanos += totalTimeNanos;
            rayHits += hits;
            rayMisses += (count - hits);
        }

        public double getAverageFrameTimeMs() {
            return frameCount > 0 ? (totalFrameTimeNanos / (double) frameCount) / 1_000_000.0 : 0.0;
        }

        public double getAverageFPS() {
            var avgMs = getAverageFrameTimeMs();
            return avgMs > 0 ? 1000.0 / avgMs : 0.0;
        }

        public double getMinFrameTimeMs() {
            return minFrameTimeNanos != Long.MAX_VALUE ? minFrameTimeNanos / 1_000_000.0 : 0.0;
        }

        public double getMaxFrameTimeMs() {
            return maxFrameTimeNanos / 1_000_000.0;
        }

        public double getAverageRayTimeNanos() {
            return rayCount > 0 ? (double) totalRayTimeNanos / rayCount : 0.0;
        }

        public double getRaysPerSecond() {
            var elapsedSeconds = (System.nanoTime() - lastResetTime) / 1_000_000_000.0;
            return elapsedSeconds > 0 ? rayCount / elapsedSeconds : 0.0;
        }

        public double getHitRate() {
            var total = rayHits + rayMisses;
            return total > 0 ? (double) rayHits / total : 0.0;
        }

        public long getFrameCount() {
            return frameCount;
        }

        public long getRayCount() {
            return rayCount;
        }

        public long getRayHits() {
            return rayHits;
        }

        public long getRayMisses() {
            return rayMisses;
        }

        public void reset() {
            frameCount = 0;
            totalFrameTimeNanos = 0;
            minFrameTimeNanos = Long.MAX_VALUE;
            maxFrameTimeNanos = 0;
            rayCount = 0;
            totalRayTimeNanos = 0;
            rayHits = 0;
            rayMisses = 0;
            lastResetTime = System.nanoTime();
        }

        public String getSummary() {
            return String.format(
                    "Frames: %d (avg %.2f ms, %.1f FPS) | Rays: %d (%.1f M/s, %.1f%% hits)",
                    frameCount, getAverageFrameTimeMs(), getAverageFPS(),
                    rayCount, getRaysPerSecond() / 1_000_000.0, getHitRate() * 100
            );
        }
    }

    /**
     * Main entry point.
     */
    public static void main(String[] args) {
        ESVTCommandLine.main(args);
    }
}
