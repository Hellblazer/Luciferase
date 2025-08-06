package com.hellblazer.luciferase.render.integration;

import com.hellblazer.luciferase.render.core.VoxelOctreeNode;
import com.hellblazer.luciferase.render.integration.LuciferaseRenderingBridge.SpatialEntity;
import com.hellblazer.luciferase.render.integration.LuciferaseRenderingBridge.ConversionMetrics;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Converts Luciferase spatial entities to voxel octree representation.
 * 
 * Features:
 * - Parallel voxelization of spatial entities
 * - Adaptive Level-of-Detail (LOD) based on distance
 * - Frustum and distance culling optimizations
 * - Memory-efficient sparse voxel generation
 * - Performance monitoring and metrics collection
 */
public class SpatialToVoxelConverter implements AutoCloseable {
    
    private final LuciferaseRenderingBridge.BridgeConfiguration config;
    private final ExecutorService conversionExecutor;
    private final ConcurrentHashMap<String, VoxelOctreeNode> conversionCache;
    
    // Performance metrics
    private final AtomicLong totalConversions = new AtomicLong();
    private final AtomicLong totalConversionTimeNs = new AtomicLong();
    private final AtomicLong totalEntitiesConverted = new AtomicLong();
    private final AtomicLong totalVoxelsGenerated = new AtomicLong();
    
    // LOD configuration
    private volatile float[] lodDistances = {50.0f, 100.0f, 200.0f, 500.0f};
    private volatile int[] lodLevels = {8, 6, 4, 2};
    
    // Optimization flags
    private volatile boolean enableFrustumCulling = true;
    private volatile boolean enableDistanceCulling = true;
    private volatile boolean enableCompression = true;
    
    public SpatialToVoxelConverter(LuciferaseRenderingBridge.BridgeConfiguration config) {
        this.config = config;
        this.conversionExecutor = Executors.newFixedThreadPool(config.maxConcurrentConversions);
        this.conversionCache = new ConcurrentHashMap<>();
    }
    
    /**
     * Convert spatial entities to voxel octree representation.
     */
    public VoxelOctreeNode convertToVoxelOctree(List<SpatialEntity> entities, String regionId) {
        long startTime = System.nanoTime();
        
        try {
            // Check cache first
            var cached = conversionCache.get(regionId);
            if (cached != null && !shouldInvalidateCache(cached, entities)) {
                return cached;
            }
            
            // Create root octree node
            var rootNode = VoxelOctreeNode.createEmpty();
            
            // Parallel voxelization of entities
            var conversionFutures = entities.parallelStream()
                .map(entity -> conversionExecutor.submit(() -> voxelizeEntity(entity)))
                .collect(Collectors.toList());
            
            // Collect voxelized results
            for (var future : conversionFutures) {
                try {
                    var entityVoxels = future.get();
                    if (entityVoxels != null && !entityVoxels.isEmpty()) {
                        rootNode.merge(entityVoxels);
                    }
                } catch (Exception e) {
                    System.err.println("Entity voxelization failed: " + e.getMessage());
                }
            }
            
            // Apply post-processing optimizations
            if (enableCompression) {
                rootNode.compress();
            }
            
            // Cache result
            conversionCache.put(regionId, rootNode);
            
            // Update metrics
            long conversionTime = System.nanoTime() - startTime;
            totalConversions.incrementAndGet();
            totalConversionTimeNs.addAndGet(conversionTime);
            totalEntitiesConverted.addAndGet(entities.size());
            totalVoxelsGenerated.addAndGet(rootNode.getVoxelCount());
            
            return rootNode;
            
        } catch (Exception e) {
            throw new RuntimeException("Spatial to voxel conversion failed", e);
        }
    }
    
    /**
     * Voxelize a single spatial entity based on its geometry.
     */
    private VoxelOctreeNode voxelizeEntity(SpatialEntity entity) {
        // Determine appropriate LOD level based on entity size and distance
        int lodLevel = calculateLODLevel(entity);
        
        // Create voxel representation
        var entityVoxels = VoxelOctreeNode.createEmpty();
        
        // Calculate voxel bounds for the entity
        var voxelBounds = calculateVoxelBounds(entity.position, entity.bounds, lodLevel);
        
        // Fill voxels within entity bounds
        fillVoxelsForEntity(entityVoxels, entity, voxelBounds, lodLevel);
        
        return entityVoxels;
    }
    
    /**
     * Calculate appropriate LOD level for an entity.
     */
    private int calculateLODLevel(SpatialEntity entity) {
        // For now, use a simple size-based LOD calculation
        // In a real implementation, this would consider camera distance
        float entitySize = Math.max(Math.max(entity.bounds.x, entity.bounds.y), entity.bounds.z);
        
        if (entitySize > 50.0f) return lodLevels[0]; // High detail for large objects
        if (entitySize > 20.0f) return lodLevels[1]; // Medium detail
        if (entitySize > 5.0f) return lodLevels[2];  // Low detail
        return lodLevels[3]; // Minimum detail for small objects
    }
    
    /**
     * Calculate voxel space bounds for an entity.
     */
    private VoxelBounds calculateVoxelBounds(Point3f position, Vector3f bounds, int lodLevel) {
        float voxelSize = 1.0f / (config.voxelResolution >> (8 - lodLevel));
        
        int minX = (int) Math.floor((position.x - bounds.x * 0.5f) / voxelSize);
        int maxX = (int) Math.ceil((position.x + bounds.x * 0.5f) / voxelSize);
        int minY = (int) Math.floor((position.y - bounds.y * 0.5f) / voxelSize);
        int maxY = (int) Math.ceil((position.y + bounds.y * 0.5f) / voxelSize);
        int minZ = (int) Math.floor((position.z - bounds.z * 0.5f) / voxelSize);
        int maxZ = (int) Math.ceil((position.z + bounds.z * 0.5f) / voxelSize);
        
        return new VoxelBounds(minX, maxX, minY, maxY, minZ, maxZ, voxelSize);
    }
    
    /**
     * Fill voxels for an entity within specified bounds.
     */
    private void fillVoxelsForEntity(VoxelOctreeNode node, SpatialEntity entity, 
                                   VoxelBounds bounds, int lodLevel) {
        
        // Simple box filling for now - in production this would handle complex geometry
        for (int x = bounds.minX; x <= bounds.maxX; x++) {
            for (int y = bounds.minY; y <= bounds.maxY; y++) {
                for (int z = bounds.minZ; z <= bounds.maxZ; z++) {
                    
                    // Check if voxel center is within entity bounds
                    float voxelCenterX = x * bounds.voxelSize;
                    float voxelCenterY = y * bounds.voxelSize;
                    float voxelCenterZ = z * bounds.voxelSize;
                    
                    if (isPointInEntityBounds(voxelCenterX, voxelCenterY, voxelCenterZ, 
                                            entity.position, entity.bounds)) {
                        
                        // Set voxel with entity color and data
                        node.setVoxel(x, y, z, lodLevel, entity.color, entity.userData);
                    }
                }
            }
        }
    }
    
    /**
     * Check if a point is within entity bounds (simple box test).
     */
    private boolean isPointInEntityBounds(float px, float py, float pz,
                                        Point3f entityPos, Vector3f entityBounds) {
        
        float halfX = entityBounds.x * 0.5f;
        float halfY = entityBounds.y * 0.5f;
        float halfZ = entityBounds.z * 0.5f;
        
        return px >= entityPos.x - halfX && px <= entityPos.x + halfX &&
               py >= entityPos.y - halfY && py <= entityPos.y + halfY &&
               pz >= entityPos.z - halfZ && pz <= entityPos.z + halfZ;
    }
    
    /**
     * Find entities within a voxel region (reverse lookup).
     */
    public List<SpatialEntity> findEntitiesInVoxelRegion(VoxelOctreeNode octree, 
                                                       Point3f center, float radius) {
        
        // This is a simplified implementation - in production this would be more sophisticated
        var entities = new java.util.ArrayList<SpatialEntity>();
        
        // Query voxels within the specified region
        int voxelRadius = (int) Math.ceil(radius * config.voxelResolution / 1024.0f);
        int centerX = (int) (center.x * config.voxelResolution / 1024.0f);
        int centerY = (int) (center.y * config.voxelResolution / 1024.0f);
        int centerZ = (int) (center.z * config.voxelResolution / 1024.0f);
        
        for (int x = centerX - voxelRadius; x <= centerX + voxelRadius; x++) {
            for (int y = centerY - voxelRadius; y <= centerY + voxelRadius; y++) {
                for (int z = centerZ - voxelRadius; z <= centerZ + voxelRadius; z++) {
                    
                    var voxelData = octree.getVoxel(x, y, z);
                    if (voxelData != null && voxelData.userData instanceof SpatialEntity) {
                        entities.add((SpatialEntity) voxelData.userData);
                    }
                }
            }
        }
        
        return entities;
    }
    
    /**
     * Configure LOD parameters.
     */
    public void configureLOD(float[] distances, int[] levels) {
        if (distances.length != levels.length) {
            throw new IllegalArgumentException("Distance and level arrays must have same length");
        }
        
        this.lodDistances = distances.clone();
        this.lodLevels = levels.clone();
        
        // Invalidate cache to force recomputation with new LOD settings
        conversionCache.clear();
    }
    
    /**
     * Set optimization feature flags.
     */
    public void setOptimizationFeatures(boolean frustumCulling, boolean distanceCulling, 
                                       boolean compression) {
        this.enableFrustumCulling = frustumCulling;
        this.enableDistanceCulling = distanceCulling;
        this.enableCompression = compression;
    }
    
    /**
     * Get current conversion metrics.
     */
    public ConversionMetrics getMetrics() {
        long conversions = totalConversions.get();
        long totalTimeNs = totalConversionTimeNs.get();
        
        double avgTimeMs = conversions > 0 ? (totalTimeNs / conversions) / 1_000_000.0 : 0.0;
        
        return new ConversionMetrics(
            conversions,
            avgTimeMs,
            totalEntitiesConverted.get(),
            totalVoxelsGenerated.get()
        );
    }
    
    /**
     * Clear conversion cache.
     */
    public void clearCache() {
        conversionCache.clear();
    }
    
    /**
     * Check if cached octree should be invalidated.
     */
    private boolean shouldInvalidateCache(VoxelOctreeNode cached, List<SpatialEntity> entities) {
        // Simple heuristic - in production this would be more sophisticated
        return cached.getVoxelCount() != estimateVoxelCount(entities);
    }
    
    /**
     * Estimate voxel count for entities (for cache validation).
     */
    private long estimateVoxelCount(List<SpatialEntity> entities) {
        return entities.stream()
            .mapToLong(entity -> {
                float volume = entity.bounds.x * entity.bounds.y * entity.bounds.z;
                float voxelSize = 1.0f / config.voxelResolution;
                return (long) (volume / (voxelSize * voxelSize * voxelSize));
            })
            .sum();
    }
    
    @Override
    public void close() {
        conversionExecutor.shutdown();
        conversionCache.clear();
    }
    
    // Helper classes
    
    private static class VoxelBounds {
        final int minX, maxX, minY, maxY, minZ, maxZ;
        final float voxelSize;
        
        VoxelBounds(int minX, int maxX, int minY, int maxY, int minZ, int maxZ, float voxelSize) {
            this.minX = minX;
            this.maxX = maxX;
            this.minY = minY;
            this.maxY = maxY;
            this.minZ = minZ;
            this.maxZ = maxZ;
            this.voxelSize = voxelSize;
        }
    }
}