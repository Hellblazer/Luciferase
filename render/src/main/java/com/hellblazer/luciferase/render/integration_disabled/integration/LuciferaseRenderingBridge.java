package com.hellblazer.luciferase.render.integration;

import com.hellblazer.luciferase.render.core.VoxelOctreeNode;
import com.hellblazer.luciferase.render.rendering.VoxelRenderingPipeline;
import com.hellblazer.luciferase.render.rendering.VoxelRayTraversal;
import com.hellblazer.luciferase.render.webgpu.WebGPUContext;
import com.hellblazer.luciferase.render.io.VoxelStreamingIO;
import com.hellblazer.luciferase.render.compression.SparseVoxelCompressor;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import javax.vecmath.Matrix4f;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Integration bridge between Luciferase spatial indexing and ESVO rendering system.
 * 
 * This bridge provides:
 * - Conversion between Luciferase spatial indices and voxel octrees
 * - Real-time synchronization of spatial data with rendering pipeline
 * - Performance-optimized data transfer between systems
 * - Unified API for rendering Luciferase spatial content
 * - Memory-efficient streaming of large spatial datasets
 */
public class LuciferaseRenderingBridge implements AutoCloseable {
    
    // Core rendering components
    private final VoxelRenderingPipeline renderingPipeline;
    private final VoxelStreamingIO streamingIO;
    private final SparseVoxelCompressor compressor;
    private final WebGPUContext webgpuContext;
    
    // Spatial data management
    private final ConcurrentHashMap<String, VoxelOctreeNode> spatialRegions;
    private final ReadWriteLock spatialLock;
    private volatile VoxelOctreeNode rootOctree;
    
    // Performance optimization
    private final DataSynchronizer dataSynchronizer;
    private final SpatialToVoxelConverter converter;
    private volatile boolean autoSyncEnabled;
    
    // Configuration
    private final BridgeConfiguration config;
    
    public static class BridgeConfiguration {
        public int voxelResolution = 1024; // Voxels per spatial unit
        public float spatialBounds = 1000.0f; // Maximum spatial extent
        public boolean enableAutoSync = true; // Automatic sync of spatial changes
        public int maxCachedRegions = 64; // Maximum cached spatial regions
        public boolean enableLODStreaming = true; // Level-of-detail streaming
        public float syncThresholdDistance = 10.0f; // Distance threshold for sync
        public int maxConcurrentConversions = 4; // Parallel conversion threads
        public boolean enableCompression = true; // Compress voxel data
        public int compressionLevel = 3; // 1-5 compression levels
        
        // Rendering integration settings
        public boolean enableRealTimeUpdate = true;
        public int updateFrequencyMs = 16; // ~60 FPS update rate
        public float cullingDistance = 500.0f; // Distance-based culling
        public boolean enableFrustumCulling = true;
    }
    
    public LuciferaseRenderingBridge(WebGPUContext webgpuContext,
                                   VoxelStreamingIO streamingIO,
                                   SparseVoxelCompressor compressor,
                                   BridgeConfiguration config) {
        this.webgpuContext = webgpuContext;
        this.streamingIO = streamingIO;
        this.compressor = compressor;
        this.config = config;
        
        // Initialize rendering pipeline
        var renderConfig = createRenderingConfiguration();
        this.renderingPipeline = new VoxelRenderingPipeline(
            webgpuContext, streamingIO, compressor, renderConfig);
        
        // Initialize spatial data structures
        this.spatialRegions = new ConcurrentHashMap<>();
        this.spatialLock = new ReentrantReadWriteLock();
        this.rootOctree = VoxelOctreeNode.createEmpty();
        
        // Initialize support systems
        this.converter = new SpatialToVoxelConverter(config);
        this.dataSynchronizer = new DataSynchronizer(this, config);
        this.autoSyncEnabled = config.enableAutoSync;
        
        // Start automatic synchronization if enabled
        if (config.enableRealTimeUpdate) {
            dataSynchronizer.startRealTimeSync();
        }
    }
    
    /**
     * Add spatial entities to the rendering system.
     * Converts Luciferase spatial data to voxel representation.
     */
    public CompletableFuture<Void> addSpatialEntities(String regionId, 
                                                     List<SpatialEntity> entities) {
        return CompletableFuture.runAsync(() -> {
            try {
                // Convert spatial entities to voxel octree
                var voxelRegion = converter.convertToVoxelOctree(entities, regionId);
                
                spatialLock.writeLock().lock();
                try {
                    // Store region
                    spatialRegions.put(regionId, voxelRegion);
                    
                    // Merge into root octree
                    rootOctree = mergeOctreeRegions(rootOctree, voxelRegion);
                    
                } finally {
                    spatialLock.writeLock().unlock();
                }
                
                // Update rendering pipeline if auto-sync enabled
                if (autoSyncEnabled) {
                    renderingPipeline.updateOctreeData(rootOctree);
                }
                
            } catch (Exception e) {
                throw new RuntimeException("Failed to add spatial entities", e);
            }
        });
    }
    
    /**
     * Update spatial entities in real-time.
     */
    public CompletableFuture<Void> updateSpatialEntities(String regionId,
                                                        List<SpatialEntity> updatedEntities) {
        return CompletableFuture.runAsync(() -> {
            try {
                // Convert updated entities
                var updatedRegion = converter.convertToVoxelOctree(updatedEntities, regionId);
                
                spatialLock.writeLock().lock();
                try {
                    // Replace existing region
                    var oldRegion = spatialRegions.put(regionId, updatedRegion);
                    
                    // Rebuild root octree if necessary
                    if (oldRegion != null) {
                        rootOctree = rebuildRootOctree();
                    }
                    
                } finally {
                    spatialLock.writeLock().unlock();
                }
                
                // Sync with rendering pipeline
                if (autoSyncEnabled) {
                    renderingPipeline.updateOctreeData(rootOctree);
                }
                
            } catch (Exception e) {
                throw new RuntimeException("Failed to update spatial entities", e);
            }
        });
    }
    
    /**
     * Remove spatial entities from rendering.
     */
    public CompletableFuture<Void> removeSpatialEntities(String regionId) {
        return CompletableFuture.runAsync(() -> {
            spatialLock.writeLock().lock();
            try {
                var removedRegion = spatialRegions.remove(regionId);
                if (removedRegion != null) {
                    rootOctree = rebuildRootOctree();
                    
                    if (autoSyncEnabled) {
                        renderingPipeline.updateOctreeData(rootOctree);
                    }
                }
            } finally {
                spatialLock.writeLock().unlock();
            }
        });
    }
    
    /**
     * Render frame with Luciferase camera parameters.
     */
    public CompletableFuture<VoxelRenderingPipeline.RenderedFrame> renderFrame(
            Point3f cameraPosition,
            Vector3f cameraDirection,
            Vector3f upVector,
            Matrix4f projectionMatrix,
            long frameNumber) {
        
        // Convert Luciferase vectors to rendering state
        var renderingState = createRenderingState(
            cameraPosition, cameraDirection, upVector, 
            projectionMatrix, frameNumber);
        
        return renderingPipeline.renderFrame(renderingState);
    }
    
    /**
     * Perform spatial queries using the voxel representation.
     */
    public List<SpatialEntity> findEntitiesInRegion(Point3f center, float radius) {
        spatialLock.readLock().lock();
        try {
            // Use voxel octree for efficient spatial querying
            return converter.findEntitiesInVoxelRegion(rootOctree, center, radius);
            
        } finally {
            spatialLock.readLock().unlock();
        }
    }
    
    /**
     * Ray casting using the integrated voxel system.
     */
    public RayHit castRay(Point3f origin, Vector3f direction) {
        try {
            var rayTraversal = new VoxelRayTraversal(webgpuContext);
            var ray = new VoxelRayTraversal.Ray(
                new float[]{origin.x, origin.y, origin.z},
                new float[]{direction.x, direction.y, direction.z}
            );
            
            return rayTraversal.castRay(ray);
            
        } catch (Exception e) {
            throw new RuntimeException("Ray casting failed", e);
        }
    }
    
    /**
     * Get current rendering performance metrics.
     */
    public PerformanceReport getPerformanceReport() {
        var pipelineMetrics = renderingPipeline.getPerformanceMetrics();
        var conversionMetrics = converter.getMetrics();
        var syncMetrics = dataSynchronizer.getMetrics();
        
        return new PerformanceReport(
            pipelineMetrics,
            conversionMetrics,
            syncMetrics,
            spatialRegions.size(),
            calculateTotalVoxels()
        );
    }
    
    /**
     * Enable or disable automatic synchronization.
     */
    public void setAutoSyncEnabled(boolean enabled) {
        this.autoSyncEnabled = enabled;
        
        if (enabled && config.enableRealTimeUpdate) {
            dataSynchronizer.startRealTimeSync();
        } else {
            dataSynchronizer.stopRealTimeSync();
        }
    }
    
    /**
     * Manually trigger synchronization of all spatial data.
     */
    public CompletableFuture<Void> forceSynchronization() {
        return CompletableFuture.runAsync(() -> {
            spatialLock.readLock().lock();
            try {
                renderingPipeline.updateOctreeData(rootOctree);
            } finally {
                spatialLock.readLock().unlock();
            }
        });
    }
    
    /**
     * Configure Level-of-Detail (LOD) parameters.
     */
    public void configureLOD(float[] lodDistances, int[] lodLevels) {
        converter.configureLOD(lodDistances, lodLevels);
        dataSynchronizer.updateLODConfiguration(lodDistances, lodLevels);
    }
    
    /**
     * Enable or disable specific optimization features.
     */
    public void setOptimizationFeatures(boolean enableFrustumCulling,
                                       boolean enableDistanceCulling,
                                       boolean enableCompression) {
        converter.setOptimizationFeatures(enableFrustumCulling, 
                                        enableDistanceCulling, 
                                        enableCompression);
    }
    
    @Override
    public void close() {
        dataSynchronizer.shutdown();
        renderingPipeline.close();
        converter.close();
        
        spatialRegions.clear();
        rootOctree = null;
    }
    
    // Helper methods
    
    private VoxelRenderingPipeline.RenderingConfiguration createRenderingConfiguration() {
        var renderConfig = new VoxelRenderingPipeline.RenderingConfiguration();
        renderConfig.enableLOD = config.enableLODStreaming;
        renderConfig.enableAsyncStreaming = config.enableLODStreaming;
        renderConfig.enableAdaptiveQuality = true;
        renderConfig.rayStepSize = 1.0f / config.voxelResolution;
        return renderConfig;
    }
    
    private VoxelRenderingPipeline.RenderingState createRenderingState(
            Point3f cameraPosition, Vector3f cameraDirection,
            Vector3f upVector, Matrix4f projectionMatrix, long frameNumber) {
        
        // Create view matrix from camera parameters
        var viewMatrix = createViewMatrix(cameraPosition, cameraDirection, upVector);
        var projMatrix = matrixToFloatArray(projectionMatrix);
        var camPos = new float[]{cameraPosition.x, cameraPosition.y, cameraPosition.z};
        var lightDir = new float[]{0.0f, -1.0f, -1.0f}; // Default downward light
        
        return new VoxelRenderingPipeline.RenderingState(
            viewMatrix, projMatrix, camPos, lightDir, 0.2f, 2, frameNumber);
    }
    
    private float[] createViewMatrix(Point3f position, Vector3f direction, Vector3f up) {
        // Calculate view matrix from camera parameters
        Vector3f forward = new Vector3f(direction);
        forward.normalize();
        
        Vector3f right = new Vector3f();
        right.cross(forward, up);
        right.normalize();
        
        Vector3f actualUp = new Vector3f();
        actualUp.cross(right, forward);
        
        return new float[] {
            right.x, actualUp.x, -forward.x, 0,
            right.y, actualUp.y, -forward.y, 0,
            right.z, actualUp.z, -forward.z, 0,
            -right.dot(new Vector3f(position)), -actualUp.dot(new Vector3f(position)), 
            forward.dot(new Vector3f(position)), 1
        };
    }
    
    private float[] matrixToFloatArray(Matrix4f matrix) {
        float[] result = new float[16];
        result[0] = matrix.m00; result[1] = matrix.m01; result[2] = matrix.m02; result[3] = matrix.m03;
        result[4] = matrix.m10; result[5] = matrix.m11; result[6] = matrix.m12; result[7] = matrix.m13;
        result[8] = matrix.m20; result[9] = matrix.m21; result[10] = matrix.m22; result[11] = matrix.m23;
        result[12] = matrix.m30; result[13] = matrix.m31; result[14] = matrix.m32; result[15] = matrix.m33;
        return result;
    }
    
    private VoxelOctreeNode mergeOctreeRegions(VoxelOctreeNode root, VoxelOctreeNode region) {
        // Simplified merge - in production this would be more sophisticated
        if (root.isEmpty()) {
            return region;
        }
        
        var merged = VoxelOctreeNode.createEmpty();
        merged.merge(root);
        merged.merge(region);
        return merged;
    }
    
    private VoxelOctreeNode rebuildRootOctree() {
        var rebuiltRoot = VoxelOctreeNode.createEmpty();
        
        for (var region : spatialRegions.values()) {
            rebuiltRoot = mergeOctreeRegions(rebuiltRoot, region);
        }
        
        return rebuiltRoot;
    }
    
    private long calculateTotalVoxels() {
        return spatialRegions.values().stream()
            .mapToLong(VoxelOctreeNode::getVoxelCount)
            .sum();
    }
    
    // Data classes for integration
    
    public static class SpatialEntity {
        public final String id;
        public final Point3f position;
        public final Vector3f bounds;
        public final int color; // RGBA packed
        public final Object userData;
        
        public SpatialEntity(String id, Point3f position, Vector3f bounds, 
                           int color, Object userData) {
            this.id = id;
            this.position = new Point3f(position);
            this.bounds = new Vector3f(bounds);
            this.color = color;
            this.userData = userData;
        }
    }
    
    public static class RayHit {
        public final Point3f position;
        public final Vector3f normal;
        public final float distance;
        public final SpatialEntity entity;
        
        public RayHit(Point3f position, Vector3f normal, float distance, SpatialEntity entity) {
            this.position = new Point3f(position);
            this.normal = new Vector3f(normal);
            this.distance = distance;
            this.entity = entity;
        }
    }
    
    public static class PerformanceReport {
        public final VoxelRenderingPipeline.PerformanceMetrics renderingMetrics;
        public final ConversionMetrics conversionMetrics;
        public final SyncMetrics syncMetrics;
        public final int totalRegions;
        public final long totalVoxels;
        
        public PerformanceReport(VoxelRenderingPipeline.PerformanceMetrics renderingMetrics,
                               ConversionMetrics conversionMetrics,
                               SyncMetrics syncMetrics,
                               int totalRegions,
                               long totalVoxels) {
            this.renderingMetrics = renderingMetrics;
            this.conversionMetrics = conversionMetrics;
            this.syncMetrics = syncMetrics;
            this.totalRegions = totalRegions;
            this.totalVoxels = totalVoxels;
        }
    }
    
    public static class ConversionMetrics {
        public final long totalConversions;
        public final double averageConversionTimeMs;
        public final long totalEntitiesConverted;
        public final long totalVoxelsGenerated;
        
        public ConversionMetrics(long totalConversions, double averageConversionTimeMs,
                               long totalEntitiesConverted, long totalVoxelsGenerated) {
            this.totalConversions = totalConversions;
            this.averageConversionTimeMs = averageConversionTimeMs;
            this.totalEntitiesConverted = totalEntitiesConverted;
            this.totalVoxelsGenerated = totalVoxelsGenerated;
        }
    }
    
    public static class SyncMetrics {
        public final long totalSyncs;
        public final double averageSyncTimeMs;
        public final long lastSyncTimestamp;
        public final boolean isRealTimeSyncActive;
        
        public SyncMetrics(long totalSyncs, double averageSyncTimeMs,
                         long lastSyncTimestamp, boolean isRealTimeSyncActive) {
            this.totalSyncs = totalSyncs;
            this.averageSyncTimeMs = averageSyncTimeMs;
            this.lastSyncTimestamp = lastSyncTimestamp;
            this.isRealTimeSyncActive = isRealTimeSyncActive;
        }
    }
}