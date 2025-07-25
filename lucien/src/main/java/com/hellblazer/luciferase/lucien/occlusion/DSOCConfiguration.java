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
package com.hellblazer.luciferase.lucien.occlusion;

/**
 * Configuration for Dynamic Spatiotemporal Occlusion Culling (DSOC) system.
 * 
 * This configuration controls all aspects of the DSOC system including:
 * - TBV creation and management strategies
 * - Performance thresholds and limits
 * - Quality vs performance trade-offs
 * - Update and refresh policies
 * 
 * The configuration uses a fluent API pattern for easy customization.
 *
 * @author hal.hildebrand
 */
public class DSOCConfiguration {
    
    // TBV Strategy Configuration
    private TBVStrategy tbvStrategy = AdaptiveTBVStrategy.defaultStrategy();
    private boolean enableAdaptiveStrategySwitching = true;
    
    // Performance Thresholds
    private int maxTBVsPerEntity = 2;
    private int maxTotalTBVs = 100000;
    private float tbvRefreshThreshold = 0.3f; // Refresh when quality drops below 30%
    private int batchUpdateSize = 1000;
    
    // Quality Parameters
    private float minAcceptableQuality = 0.1f;
    private boolean enableQualityBasedCulling = true;
    private float qualityCullingThreshold = 0.2f;
    
    // Update Policies
    private int updateCheckInterval = 10; // Check for updates every N frames
    private boolean enableLazyUpdates = true;
    private boolean enablePredictiveUpdates = true;
    private int predictiveUpdateLookahead = 30; // Frames to look ahead
    
    // Memory Management
    private boolean enableTBVPooling = true;
    private int tbvPoolSize = 10000;
    private boolean enableAggressiveCleanup = false;
    private int cleanupInterval = 300; // Frames between cleanup passes
    
    // Spatial Optimization
    private boolean enableSpatialCoherence = true;
    private float spatialCoherenceRadius = 50.0f;
    private boolean enableHierarchicalTBVs = true;
    private int maxHierarchyDepth = 3;
    
    // Statistics and Monitoring
    private boolean enableStatistics = true;
    private boolean enableDetailedProfiling = false;
    private int statisticsUpdateInterval = 60; // Frames
    
    // Core enablement
    private boolean enabled = true;
    private float velocityThreshold = 0.1f; // Minimum velocity for TBV creation
    private boolean alwaysCreateTbv = false; // Force TBV creation regardless of velocity
    private boolean autoDynamicsEnabled = true; // Enable automatic dynamics tracking
    
    /**
     * Create a default configuration optimized for general use.
     */
    public static DSOCConfiguration defaultConfig() {
        return new DSOCConfiguration();
    }
    
    /**
     * Create a configuration optimized for high performance with reduced quality.
     */
    public static DSOCConfiguration highPerformance() {
        return new DSOCConfiguration()
            .withTBVStrategy(FixedDurationTBVStrategy.aggressiveStrategy(30))
            .withMaxTBVsPerEntity(1)
            .withQualityBasedCulling(false)
            .withLazyUpdates(true)
            .withAggressiveCleanup(true)
            .withDetailedProfiling(false);
    }
    
    /**
     * Create a configuration optimized for high quality with more frequent updates.
     */
    public static DSOCConfiguration highQuality() {
        return new DSOCConfiguration()
            .withTBVStrategy(AdaptiveTBVStrategy.defaultStrategy())
            .withMaxTBVsPerEntity(3)
            .withTBVRefreshThreshold(0.5f)
            .withUpdateCheckInterval(5)
            .withPredictiveUpdates(true)
            .withPredictiveUpdateLookahead(60);
    }
    
    /**
     * Create a configuration optimized for mostly static scenes.
     */
    public static DSOCConfiguration staticScene() {
        return new DSOCConfiguration()
            .withTBVStrategy(AdaptiveTBVStrategy.staticSceneStrategy())
            .withUpdateCheckInterval(30)
            .withLazyUpdates(true)
            .withTBVRefreshThreshold(0.1f)
            .withCleanupInterval(600);
    }
    
    /**
     * Create a configuration optimized for highly dynamic scenes.
     */
    public static DSOCConfiguration dynamicScene() {
        return new DSOCConfiguration()
            .withTBVStrategy(AdaptiveTBVStrategy.dynamicSceneStrategy())
            .withUpdateCheckInterval(3)
            .withTBVRefreshThreshold(0.4f)
            .withPredictiveUpdates(true)
            .withBatchUpdateSize(2000);
    }
    
    // Fluent API methods
    
    public DSOCConfiguration withTBVStrategy(TBVStrategy strategy) {
        if (strategy == null) {
            throw new IllegalArgumentException("TBV strategy cannot be null");
        }
        this.tbvStrategy = strategy;
        return this;
    }
    
    public DSOCConfiguration withAdaptiveStrategySwitching(boolean enable) {
        this.enableAdaptiveStrategySwitching = enable;
        return this;
    }
    
    public DSOCConfiguration withMaxTBVsPerEntity(int max) {
        if (max <= 0) {
            throw new IllegalArgumentException("Max TBVs per entity must be positive");
        }
        this.maxTBVsPerEntity = max;
        return this;
    }
    
    public DSOCConfiguration withMaxTotalTBVs(int max) {
        if (max <= 0) {
            throw new IllegalArgumentException("Max total TBVs must be positive");
        }
        this.maxTotalTBVs = max;
        return this;
    }
    
    public DSOCConfiguration withTBVRefreshThreshold(float threshold) {
        if (threshold < 0 || threshold > 1) {
            throw new IllegalArgumentException("TBV refresh threshold must be between 0 and 1");
        }
        this.tbvRefreshThreshold = threshold;
        return this;
    }
    
    public DSOCConfiguration withBatchUpdateSize(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("Batch update size must be positive");
        }
        this.batchUpdateSize = size;
        return this;
    }
    
    public DSOCConfiguration withMinAcceptableQuality(float quality) {
        if (quality < 0 || quality > 1) {
            throw new IllegalArgumentException("Min acceptable quality must be between 0 and 1");
        }
        this.minAcceptableQuality = quality;
        return this;
    }
    
    public DSOCConfiguration withQualityBasedCulling(boolean enable) {
        this.enableQualityBasedCulling = enable;
        return this;
    }
    
    public DSOCConfiguration withQualityCullingThreshold(float threshold) {
        if (threshold < 0 || threshold > 1) {
            throw new IllegalArgumentException("Quality culling threshold must be between 0 and 1");
        }
        this.qualityCullingThreshold = threshold;
        return this;
    }
    
    public DSOCConfiguration withUpdateCheckInterval(int interval) {
        if (interval <= 0) {
            throw new IllegalArgumentException("Update check interval must be positive");
        }
        this.updateCheckInterval = interval;
        return this;
    }
    
    public DSOCConfiguration withLazyUpdates(boolean enable) {
        this.enableLazyUpdates = enable;
        return this;
    }
    
    public DSOCConfiguration withPredictiveUpdates(boolean enable) {
        this.enablePredictiveUpdates = enable;
        return this;
    }
    
    public DSOCConfiguration withPredictiveUpdateLookahead(int frames) {
        if (frames <= 0) {
            throw new IllegalArgumentException("Predictive update lookahead must be positive");
        }
        this.predictiveUpdateLookahead = frames;
        return this;
    }
    
    public DSOCConfiguration withTBVPooling(boolean enable) {
        this.enableTBVPooling = enable;
        return this;
    }
    
    public DSOCConfiguration withTBVPoolSize(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("TBV pool size must be positive");
        }
        this.tbvPoolSize = size;
        return this;
    }
    
    public DSOCConfiguration withAggressiveCleanup(boolean enable) {
        this.enableAggressiveCleanup = enable;
        return this;
    }
    
    public DSOCConfiguration withCleanupInterval(int interval) {
        if (interval <= 0) {
            throw new IllegalArgumentException("Cleanup interval must be positive");
        }
        this.cleanupInterval = interval;
        return this;
    }
    
    public DSOCConfiguration withSpatialCoherence(boolean enable) {
        this.enableSpatialCoherence = enable;
        return this;
    }
    
    public DSOCConfiguration withSpatialCoherenceRadius(float radius) {
        if (radius <= 0) {
            throw new IllegalArgumentException("Spatial coherence radius must be positive");
        }
        this.spatialCoherenceRadius = radius;
        return this;
    }
    
    public DSOCConfiguration withHierarchicalTBVs(boolean enable) {
        this.enableHierarchicalTBVs = enable;
        return this;
    }
    
    public DSOCConfiguration withMaxHierarchyDepth(int depth) {
        if (depth <= 0) {
            throw new IllegalArgumentException("Max hierarchy depth must be positive");
        }
        this.maxHierarchyDepth = depth;
        return this;
    }
    
    public DSOCConfiguration withStatistics(boolean enable) {
        this.enableStatistics = enable;
        return this;
    }
    
    public DSOCConfiguration withDetailedProfiling(boolean enable) {
        this.enableDetailedProfiling = enable;
        return this;
    }
    
    public DSOCConfiguration withStatisticsUpdateInterval(int interval) {
        if (interval <= 0) {
            throw new IllegalArgumentException("Statistics update interval must be positive");
        }
        this.statisticsUpdateInterval = interval;
        return this;
    }
    
    // Getters
    
    public TBVStrategy getTbvStrategy() {
        return tbvStrategy;
    }
    
    public boolean isEnableAdaptiveStrategySwitching() {
        return enableAdaptiveStrategySwitching;
    }
    
    public int getMaxTBVsPerEntity() {
        return maxTBVsPerEntity;
    }
    
    public int getMaxTotalTBVs() {
        return maxTotalTBVs;
    }
    
    public float getTbvRefreshThreshold() {
        return tbvRefreshThreshold;
    }
    
    public int getBatchUpdateSize() {
        return batchUpdateSize;
    }
    
    public float getMinAcceptableQuality() {
        return minAcceptableQuality;
    }
    
    public boolean isEnableQualityBasedCulling() {
        return enableQualityBasedCulling;
    }
    
    public float getQualityCullingThreshold() {
        return qualityCullingThreshold;
    }
    
    public int getUpdateCheckInterval() {
        return updateCheckInterval;
    }
    
    public boolean isEnableLazyUpdates() {
        return enableLazyUpdates;
    }
    
    public boolean isEnablePredictiveUpdates() {
        return enablePredictiveUpdates;
    }
    
    public int getPredictiveUpdateLookahead() {
        return predictiveUpdateLookahead;
    }
    
    public boolean isEnableTBVPooling() {
        return enableTBVPooling;
    }
    
    public int getTbvPoolSize() {
        return tbvPoolSize;
    }
    
    public boolean isEnableAggressiveCleanup() {
        return enableAggressiveCleanup;
    }
    
    public int getCleanupInterval() {
        return cleanupInterval;
    }
    
    public boolean isEnableSpatialCoherence() {
        return enableSpatialCoherence;
    }
    
    public float getSpatialCoherenceRadius() {
        return spatialCoherenceRadius;
    }
    
    public boolean isEnableHierarchicalTBVs() {
        return enableHierarchicalTBVs;
    }
    
    public int getMaxHierarchyDepth() {
        return maxHierarchyDepth;
    }
    
    public boolean isEnableStatistics() {
        return enableStatistics;
    }
    
    public boolean isEnableDetailedProfiling() {
        return enableDetailedProfiling;
    }
    
    public int getStatisticsUpdateInterval() {
        return statisticsUpdateInterval;
    }
    
    public boolean isEnabled() {
        return enabled;
    }
    
    public DSOCConfiguration withEnabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }
    
    public float getVelocityThreshold() {
        return velocityThreshold;
    }
    
    public DSOCConfiguration withVelocityThreshold(float threshold) {
        if (threshold < 0) {
            throw new IllegalArgumentException("Velocity threshold must be non-negative");
        }
        this.velocityThreshold = threshold;
        return this;
    }
    
    public boolean isAlwaysCreateTbv() {
        return alwaysCreateTbv;
    }
    
    public DSOCConfiguration withAlwaysCreateTbv(boolean always) {
        this.alwaysCreateTbv = always;
        return this;
    }
    
    public boolean isAutoDynamicsEnabled() {
        return autoDynamicsEnabled;
    }
    
    public DSOCConfiguration withAutoDynamicsEnabled(boolean enabled) {
        this.autoDynamicsEnabled = enabled;
        return this;
    }
    
    public boolean isPredictiveUpdates() {
        return enablePredictiveUpdates;
    }
}