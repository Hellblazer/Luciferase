package com.hellblazer.luciferase.render.integration;

import com.hellblazer.luciferase.render.integration.LuciferaseRenderingBridge.SyncMetrics;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages real-time synchronization between spatial data and rendering pipeline.
 * 
 * Features:
 * - Periodic synchronization of spatial changes with rendering system
 * - Adaptive sync frequency based on change rate and performance
 * - LOD-aware synchronization to prioritize visible content
 * - Performance monitoring and adaptive throttling
 * - Graceful degradation under high load
 */
public class DataSynchronizer {
    
    private final LuciferaseRenderingBridge bridge;
    private final LuciferaseRenderingBridge.BridgeConfiguration config;
    private final ScheduledExecutorService syncExecutor;
    
    // Sync state management
    private final AtomicBoolean isRealTimeSyncActive = new AtomicBoolean(false);
    private final AtomicLong totalSyncs = new AtomicLong();
    private final AtomicLong totalSyncTimeNs = new AtomicLong();
    private volatile long lastSyncTimestamp = 0;
    
    // Adaptive sync parameters
    private volatile int currentSyncFrequencyMs;
    private volatile float[] lodDistances;
    private volatile int[] lodLevels;
    
    // Performance throttling
    private final AtomicLong changesSinceLastSync = new AtomicLong();
    private volatile boolean throttlingEnabled = false;
    private static final long THROTTLE_THRESHOLD_CHANGES = 1000;
    private static final double MAX_SYNC_TIME_MS = 50.0; // Don't sync if it takes too long
    
    public DataSynchronizer(LuciferaseRenderingBridge bridge, 
                           LuciferaseRenderingBridge.BridgeConfiguration config) {
        this.bridge = bridge;
        this.config = config;
        this.syncExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            var thread = new Thread(r, "DataSynchronizer");
            thread.setDaemon(true);
            return thread;
        });
        
        this.currentSyncFrequencyMs = config.updateFrequencyMs;
        this.lodDistances = new float[]{50.0f, 100.0f, 200.0f, 500.0f};
        this.lodLevels = new int[]{8, 6, 4, 2};
    }
    
    /**
     * Start real-time synchronization with rendering pipeline.
     */
    public void startRealTimeSync() {
        if (isRealTimeSyncActive.compareAndSet(false, true)) {
            syncExecutor.scheduleAtFixedRate(
                this::performSynchronization,
                0,
                currentSyncFrequencyMs,
                TimeUnit.MILLISECONDS
            );
        }
    }
    
    /**
     * Stop real-time synchronization.
     */
    public void stopRealTimeSync() {
        isRealTimeSyncActive.set(false);
    }
    
    /**
     * Perform a single synchronization cycle.
     */
    private void performSynchronization() {
        if (!isRealTimeSyncActive.get()) {
            return;
        }
        
        long startTime = System.nanoTime();
        
        try {
            // Check if synchronization is needed
            if (!shouldPerformSync()) {
                return;
            }
            
            // Check throttling conditions
            if (throttlingEnabled && shouldThrottle()) {
                return;
            }
            
            // Perform the actual synchronization
            var syncFuture = bridge.forceSynchronization();
            
            // Wait for completion with timeout
            syncFuture.get(100, TimeUnit.MILLISECONDS);
            
            // Update metrics
            long syncTime = System.nanoTime() - startTime;
            totalSyncs.incrementAndGet();
            totalSyncTimeNs.addAndGet(syncTime);
            lastSyncTimestamp = System.currentTimeMillis();
            changesSinceLastSync.set(0);
            
            // Adapt sync frequency based on performance
            adaptSyncFrequency(syncTime);
            
        } catch (Exception e) {
            // Log error but don't stop sync process
            System.err.println("Synchronization failed: " + e.getMessage());
            
            // Enable throttling if sync is consistently failing
            throttlingEnabled = true;
        }
    }
    
    /**
     * Determine if synchronization should be performed this cycle.
     */
    private boolean shouldPerformSync() {
        // Don't sync if no changes occurred
        if (changesSinceLastSync.get() == 0) {
            return false;
        }
        
        // Don't sync too frequently
        long timeSinceLastSync = System.currentTimeMillis() - lastSyncTimestamp;
        if (timeSinceLastSync < currentSyncFrequencyMs * 0.5) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Check if synchronization should be throttled due to performance.
     */
    private boolean shouldThrottle() {
        // Get current rendering performance
        var perfReport = bridge.getPerformanceReport();
        var renderingMetrics = perfReport.renderingMetrics;
        
        // Throttle if rendering performance is poor
        if (renderingMetrics.currentFPS < 30.0) {
            return true;
        }
        
        // Throttle if too many changes are pending
        if (changesSinceLastSync.get() > THROTTLE_THRESHOLD_CHANGES) {
            return true;
        }
        
        // Throttle if last sync took too long
        long avgSyncTimeNs = totalSyncs.get() > 0 ? 
            totalSyncTimeNs.get() / totalSyncs.get() : 0;
        double avgSyncTimeMs = avgSyncTimeNs / 1_000_000.0;
        
        return avgSyncTimeMs > MAX_SYNC_TIME_MS;
    }
    
    /**
     * Adapt synchronization frequency based on performance.
     */
    private void adaptSyncFrequency(long lastSyncTimeNs) {
        double lastSyncTimeMs = lastSyncTimeNs / 1_000_000.0;
        
        // Increase frequency if sync is fast and rendering is good
        var perfReport = bridge.getPerformanceReport();
        var renderingMetrics = perfReport.renderingMetrics;
        
        if (lastSyncTimeMs < 5.0 && renderingMetrics.currentFPS > 55.0) {
            // Speed up sync frequency (but don't go below 8ms ~120 FPS)
            currentSyncFrequencyMs = Math.max(8, currentSyncFrequencyMs - 2);
            throttlingEnabled = false;
        } else if (lastSyncTimeMs > 20.0 || renderingMetrics.currentFPS < 45.0) {
            // Slow down sync frequency
            currentSyncFrequencyMs = Math.min(100, currentSyncFrequencyMs + 5);
            throttlingEnabled = true;
        }
    }
    
    /**
     * Notify synchronizer that spatial data has changed.
     */
    public void notifyDataChanged() {
        changesSinceLastSync.incrementAndGet();
    }
    
    /**
     * Update LOD configuration for distance-based synchronization.
     */
    public void updateLODConfiguration(float[] distances, int[] levels) {
        this.lodDistances = distances.clone();
        this.lodLevels = levels.clone();
    }
    
    /**
     * Get current synchronization metrics.
     */
    public SyncMetrics getMetrics() {
        long syncs = totalSyncs.get();
        long totalTimeNs = totalSyncTimeNs.get();
        
        double avgTimeMs = syncs > 0 ? (totalTimeNs / syncs) / 1_000_000.0 : 0.0;
        
        return new SyncMetrics(
            syncs,
            avgTimeMs,
            lastSyncTimestamp,
            isRealTimeSyncActive.get()
        );
    }
    
    /**
     * Force immediate synchronization (bypasses throttling).
     */
    public void forceImmediateSync() {
        if (isRealTimeSyncActive.get()) {
            syncExecutor.execute(this::performSynchronization);
        }
    }
    
    /**
     * Enable or disable performance-based throttling.
     */
    public void setThrottlingEnabled(boolean enabled) {
        this.throttlingEnabled = enabled;
    }
    
    /**
     * Set manual sync frequency (overrides adaptive frequency).
     */
    public void setSyncFrequency(int frequencyMs) {
        this.currentSyncFrequencyMs = Math.max(8, Math.min(1000, frequencyMs));
        
        // Restart sync with new frequency if active
        if (isRealTimeSyncActive.get()) {
            stopRealTimeSync();
            startRealTimeSync();
        }
    }
    
    /**
     * Get current sync frequency.
     */
    public int getCurrentSyncFrequency() {
        return currentSyncFrequencyMs;
    }
    
    /**
     * Check if throttling is currently active.
     */
    public boolean isThrottlingActive() {
        return throttlingEnabled;
    }
    
    /**
     * Get number of pending changes since last sync.
     */
    public long getPendingChanges() {
        return changesSinceLastSync.get();
    }
    
    /**
     * Reset synchronization statistics.
     */
    public void resetStatistics() {
        totalSyncs.set(0);
        totalSyncTimeNs.set(0);
        changesSinceLastSync.set(0);
        lastSyncTimestamp = 0;
        throttlingEnabled = false;
    }
    
    /**
     * Shutdown the synchronizer and cleanup resources.
     */
    public void shutdown() {
        stopRealTimeSync();
        
        syncExecutor.shutdown();
        try {
            if (!syncExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                syncExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            syncExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}