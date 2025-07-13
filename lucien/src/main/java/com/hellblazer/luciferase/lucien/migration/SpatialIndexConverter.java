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
package com.hellblazer.luciferase.lucien.migration;

import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.entity.EntityIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Utility class for converting between different spatial index implementations.
 * Supports migration of data between Octree and Tetree while preserving entity IDs and content.
 */
public class SpatialIndexConverter {

    private static final Logger log = LoggerFactory.getLogger(SpatialIndexConverter.class);

    /**
     * Convert an Octree to a Tetree, preserving all entities and their content.
     *
     * @param source The source Octree
     * @param <ID>   The entity ID type
     * @param <Content> The content type
     * @return A new Tetree containing all entities from the source
     */
    public static <ID extends EntityID, Content> Tetree<ID, Content> octreeToTetree(
            Octree<ID, Content> source, EntityIDGenerator<ID> idGenerator) {
        log.info("Starting Octree to Tetree conversion");
        
        var startTime = System.currentTimeMillis();
        var stats = new ConversionStats();
        
        // Create target Tetree with similar configuration
        var targetMaxDepth = source.getMaxDepth();
        var targetMaxEntities = source.getMaxEntitiesPerNode();
        var tetree = new Tetree<ID, Content>(idGenerator, targetMaxEntities, targetMaxDepth);
        
        // Enable bulk loading for better performance
        tetree.enableBulkLoading();
        
        try {
            // Get all entities with their positions
            var entitiesWithPositions = source.getEntitiesWithPositions();
            stats.totalEntities = entitiesWithPositions.size();
            
            // Migrate entities
            migrateEntities(source, tetree, entitiesWithPositions, stats);
            
            // Finalize bulk loading
            tetree.finalizeBulkLoading();
            
            // Log statistics
            var duration = System.currentTimeMillis() - startTime;
            logConversionStats("Octree to Tetree", stats, duration);
            
            return tetree;
        } catch (Exception e) {
            log.error("Error during Octree to Tetree conversion", e);
            throw new ConversionException("Failed to convert Octree to Tetree", e);
        }
    }

    /**
     * Convert a Tetree to an Octree, preserving all entities and their content.
     *
     * @param source The source Tetree
     * @param <ID>   The entity ID type
     * @param <Content> The content type
     * @return A new Octree containing all entities from the source
     */
    public static <ID extends EntityID, Content> Octree<ID, Content> tetreeToOctree(
            Tetree<ID, Content> source, EntityIDGenerator<ID> idGenerator) {
        log.info("Starting Tetree to Octree conversion");
        
        var startTime = System.currentTimeMillis();
        var stats = new ConversionStats();
        
        // Create target Octree with similar configuration
        var targetMaxDepth = source.getMaxDepth();
        var targetMaxEntities = source.getMaxEntitiesPerNode();
        var octree = new Octree<ID, Content>(idGenerator, targetMaxEntities, targetMaxDepth);
        
        // Enable bulk loading for better performance
        octree.enableBulkLoading();
        
        try {
            // Get all entities with their positions
            var entitiesWithPositions = source.getEntitiesWithPositions();
            stats.totalEntities = entitiesWithPositions.size();
            
            // Migrate entities
            migrateEntities(source, octree, entitiesWithPositions, stats);
            
            // Finalize bulk loading
            octree.finalizeBulkLoading();
            
            // Log statistics
            var duration = System.currentTimeMillis() - startTime;
            logConversionStats("Tetree to Octree", stats, duration);
            
            return octree;
        } catch (Exception e) {
            log.error("Error during Tetree to Octree conversion", e);
            throw new ConversionException("Failed to convert Tetree to Octree", e);
        }
    }

    /**
     * Convert between spatial indices with progress callback.
     *
     * @param source The source spatial index
     * @param targetType The target index type
     * @param idGenerator ID generator for the target
     * @param progressCallback Optional callback for progress updates
     * @param <ID> The entity ID type
     * @param <Content> The content type
     * @return The converted spatial index
     */
    public static <ID extends EntityID, Content> Object convertWithProgress(
            Object source, 
            SpatialIndexType targetType,
            EntityIDGenerator<ID> idGenerator,
            ProgressCallback progressCallback) {
        
        if (source instanceof Octree<?, ?> && targetType == SpatialIndexType.TETREE) {
            return octreeToTetreeWithProgress((Octree<ID, Content>) source, idGenerator, progressCallback);
        } else if (source instanceof Tetree<?, ?> && targetType == SpatialIndexType.OCTREE) {
            return tetreeToOctreeWithProgress((Tetree<ID, Content>) source, idGenerator, progressCallback);
        } else {
            throw new IllegalArgumentException("Unsupported conversion: " + 
                                             source.getClass().getSimpleName() + " to " + targetType);
        }
    }

    /**
     * Batch convert multiple entities between index types.
     *
     * @param entities List of entities with their data
     * @param sourceType Source index type
     * @param targetType Target index type
     * @param idGenerator ID generator
     * @param <ID> Entity ID type
     * @param <Content> Content type
     * @return The target spatial index
     */
    public static <ID extends EntityID, Content> Object batchConvert(
            List<EntityData<ID, Content>> entities,
            SpatialIndexType sourceType,
            SpatialIndexType targetType,
            EntityIDGenerator<ID> idGenerator,
            byte maxDepth,
            int maxEntitiesPerNode) {
        
        log.info("Batch converting {} entities from {} to {}", 
                entities.size(), sourceType, targetType);
        
        if (targetType == SpatialIndexType.OCTREE) {
            var octree = new Octree<ID, Content>(idGenerator, maxEntitiesPerNode, maxDepth);
            octree.enableBulkLoading();
            
            for (var entity : entities) {
                octree.insert(entity.id, entity.position, entity.level, entity.content);
            }
            
            octree.finalizeBulkLoading();
            return octree;
        } else if (targetType == SpatialIndexType.TETREE) {
            var tetree = new Tetree<ID, Content>(idGenerator, maxEntitiesPerNode, maxDepth);
            tetree.enableBulkLoading();
            
            for (var entity : entities) {
                tetree.insert(entity.id, entity.position, entity.level, entity.content);
            }
            
            tetree.finalizeBulkLoading();
            return tetree;
        } else {
            throw new IllegalArgumentException("Unsupported target type: " + targetType);
        }
    }

    // Private helper methods

    private static <ID extends EntityID, Content, Target> void migrateEntities(
            Object source,
            Target target,
            Map<ID, Point3f> entitiesWithPositions,
            ConversionStats stats) {
        
        var processedCount = new AtomicInteger(0);
        var errors = new ArrayList<String>();
        
        entitiesWithPositions.forEach((entityId, position) -> {
            try {
                // Get entity content from source
                Content content = null;
                byte level = 0;
                
                if (source instanceof Octree<?, ?>) {
                    var octree = (Octree<ID, Content>) source;
                    content = octree.getEntity(entityId);
                    level = findEntityLevel(octree, entityId, position);
                } else if (source instanceof Tetree<?, ?>) {
                    var tetree = (Tetree<ID, Content>) source;
                    content = tetree.getEntity(entityId);
                    level = findEntityLevel(tetree, entityId, position);
                }
                
                // Insert into target
                if (target instanceof Octree<?, ?>) {
                    var octree = (Octree<ID, Content>) target;
                    octree.insert(entityId, position, level, content);
                } else if (target instanceof Tetree<?, ?>) {
                    var tetree = (Tetree<ID, Content>) target;
                    tetree.insert(entityId, position, level, content);
                }
                
                processedCount.incrementAndGet();
            } catch (Exception e) {
                stats.failedEntities++;
                errors.add(String.format("Failed to migrate entity %s: %s", 
                                       entityId, e.getMessage()));
            }
        });
        
        stats.successfulEntities = processedCount.get();
        
        if (!errors.isEmpty()) {
            log.warn("Migration completed with {} errors", errors.size());
            errors.stream().limit(10).forEach(log::warn);
            if (errors.size() > 10) {
                log.warn("... and {} more errors", errors.size() - 10);
            }
        }
    }

    private static <ID extends EntityID, Content> byte findEntityLevel(
            Octree<ID, Content> octree, ID entityId, Point3f position) {
        // Find the level by checking nodes containing the entity
        var nodes = octree.nodes()
                         .filter(node -> node.entityIds().contains(entityId))
                         .toList();
        
        if (!nodes.isEmpty()) {
            // Return the deepest level where entity exists
            return (byte) nodes.stream()
                       .mapToInt(node -> node.sfcIndex().getLevel())
                       .max()
                       .orElse(0);
        }
        
        // Default to a reasonable level based on tree depth
        return (byte) Math.min(3, octree.getMaxDepth());
    }

    private static <ID extends EntityID, Content> byte findEntityLevel(
            Tetree<ID, Content> tetree, ID entityId, Point3f position) {
        // Find the level by checking nodes containing the entity
        var nodes = tetree.nodes()
                         .filter(node -> node.entityIds().contains(entityId))
                         .toList();
        
        if (!nodes.isEmpty()) {
            // Return the deepest level where entity exists
            return (byte) nodes.stream()
                       .mapToInt(node -> {
                           // Extract level from TetreeKey
                           try {
                               var method = node.sfcIndex().getClass().getMethod("getLevel");
                               return (byte) method.invoke(node.sfcIndex());
                           } catch (Exception e) {
                               return 0;
                           }
                       })
                       .max()
                       .orElse(0);
        }
        
        // Default to a reasonable level based on tree depth
        return (byte) Math.min(3, tetree.getMaxDepth());
    }

    private static <ID extends EntityID, Content> Tetree<ID, Content> octreeToTetreeWithProgress(
            Octree<ID, Content> source,
            EntityIDGenerator<ID> idGenerator,
            ProgressCallback callback) {
        
        var tetree = new Tetree<ID, Content>(idGenerator, source.getMaxEntitiesPerNode(), source.getMaxDepth());
        tetree.enableBulkLoading();
        
        var entities = source.getEntitiesWithPositions();
        var total = entities.size();
        var processed = new AtomicInteger(0);
        
        entities.forEach((entityId, position) -> {
            var content = source.getEntity(entityId);
            var level = findEntityLevel(source, entityId, position);
            tetree.insert(entityId, position, level, content);
            
            var count = processed.incrementAndGet();
            if (callback != null && count % 100 == 0) {
                callback.onProgress(count, total);
            }
        });
        
        tetree.finalizeBulkLoading();
        
        if (callback != null) {
            callback.onComplete(total);
        }
        
        return tetree;
    }

    private static <ID extends EntityID, Content> Octree<ID, Content> tetreeToOctreeWithProgress(
            Tetree<ID, Content> source,
            EntityIDGenerator<ID> idGenerator,
            ProgressCallback callback) {
        
        var octree = new Octree<ID, Content>(idGenerator, source.getMaxEntitiesPerNode(), source.getMaxDepth());
        octree.enableBulkLoading();
        
        var entities = source.getEntitiesWithPositions();
        var total = entities.size();
        var processed = new AtomicInteger(0);
        
        entities.forEach((entityId, position) -> {
            var content = source.getEntity(entityId);
            var level = findEntityLevel(source, entityId, position);
            octree.insert(entityId, position, level, content);
            
            var count = processed.incrementAndGet();
            if (callback != null && count % 100 == 0) {
                callback.onProgress(count, total);
            }
        });
        
        octree.finalizeBulkLoading();
        
        if (callback != null) {
            callback.onComplete(total);
        }
        
        return octree;
    }

    private static void logConversionStats(String conversionType, ConversionStats stats, long duration) {
        log.info("{} conversion completed in {}ms", conversionType, duration);
        log.info("Total entities: {}, Successful: {}, Failed: {}", 
                stats.totalEntities, stats.successfulEntities, stats.failedEntities);
        
        if (stats.failedEntities > 0) {
            log.warn("Conversion completed with {} failures", stats.failedEntities);
        }
    }

    // Inner classes

    /**
     * Spatial index types supported for conversion
     */
    public enum SpatialIndexType {
        OCTREE,
        TETREE
    }

    /**
     * Progress callback for long-running conversions
     */
    public interface ProgressCallback {
        void onProgress(int processed, int total);
        void onComplete(int total);
    }

    /**
     * Entity data for batch conversion
     */
    public static class EntityData<ID extends EntityID, Content> {
        public final ID id;
        public final Point3f position;
        public final byte level;
        public final Content content;

        public EntityData(ID id, Point3f position, byte level, Content content) {
            this.id = id;
            this.position = position;
            this.level = level;
            this.content = content;
        }
    }

    /**
     * Conversion statistics
     */
    private static class ConversionStats {
        int totalEntities = 0;
        int successfulEntities = 0;
        int failedEntities = 0;
    }

    /**
     * Exception thrown during conversion failures
     */
    public static class ConversionException extends RuntimeException {
        public ConversionException(String message) {
            super(message);
        }

        public ConversionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}