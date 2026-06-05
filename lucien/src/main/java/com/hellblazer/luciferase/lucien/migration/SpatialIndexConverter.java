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

import com.hellblazer.luciferase.common.time.Clock;
import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.entity.EntityIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import java.util.ArrayList;
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
     * Pluggable clock for deterministic testing of conversion duration.
     * Defaults to the system clock; replace via {@link #setClock(Clock)} in tests.
     * Bead Luciferase-7wzml.128.
     */
    private static volatile Clock clock = Clock.system();

    /**
     * Inject a clock for deterministic conversion-duration reporting.
     *
     * @param c the clock to use; must not be null
     */
    public static void setClock(Clock c) {
        clock = c;
    }

    /**
     * Convert an Octree to a Tetree, preserving all entities and their content.
     *
     * @param source The source Octree
     * @param <ID>   The entity ID type
     * @param <Content> The content type
     * @return A new Tetree containing all entities from the source
     * @throws ConversionException if any per-entity migrations fail (Luciferase-7wzml.130)
     */
    public static <ID extends EntityID, Content> Tetree<ID, Content> octreeToTetree(
            Octree<ID, Content> source, EntityIDGenerator<ID> idGenerator) {
        log.info("Starting Octree to Tetree conversion");

        var startTime = clock.currentTimeMillis();
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
            var migrationErrors = migrateEntities(source, tetree, entitiesWithPositions, stats);

            // Finalize bulk loading
            tetree.finalizeBulkLoading();

            // Log statistics
            var duration = clock.currentTimeMillis() - startTime;
            logConversionStats("Octree to Tetree", stats, duration);

            // Bead Luciferase-7wzml.130: surface per-entity failures — callers must be able to
            // detect a lossy conversion; swallowing into a warn log is not sufficient.
            if (stats.failedEntities > 0) {
                throw new ConversionException(
                    String.format("Octree to Tetree conversion lost %d of %d entities. Errors: %s",
                                  stats.failedEntities, stats.totalEntities, migrationErrors));
            }

            return tetree;
        } catch (ConversionException ce) {
            throw ce;
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
     * @throws ConversionException if any per-entity migrations fail (Luciferase-7wzml.130)
     */
    public static <ID extends EntityID, Content> Octree<ID, Content> tetreeToOctree(
            Tetree<ID, Content> source, EntityIDGenerator<ID> idGenerator) {
        log.info("Starting Tetree to Octree conversion");

        var startTime = clock.currentTimeMillis();
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
            var migrationErrors = migrateEntities(source, octree, entitiesWithPositions, stats);

            // Finalize bulk loading
            octree.finalizeBulkLoading();

            // Log statistics
            var duration = clock.currentTimeMillis() - startTime;
            logConversionStats("Tetree to Octree", stats, duration);

            // Bead Luciferase-7wzml.130: surface per-entity failures.
            if (stats.failedEntities > 0) {
                throw new ConversionException(
                    String.format("Tetree to Octree conversion lost %d of %d entities. Errors: %s",
                                  stats.failedEntities, stats.totalEntities, migrationErrors));
            }

            return octree;
        } catch (ConversionException ce) {
            throw ce;
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
            throw new IllegalArgumentException("Unsupported conversion: "
                                               + source.getClass().getSimpleName() + " to " + targetType);
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

    /**
     * Migrate entities from source to target, preserving all spanning locations.
     *
     * <p>Bead Luciferase-7wzml.16: null-content entities are SKIPPED — they are not
     * inserted into the target and do not increment {@code successfulEntities}.  A separate
     * {@code skippedEntities} tally is recorded and logged so callers have an honest count.
     *
     * <p>Bead Luciferase-7wzml.17: spanning entities (present at multiple levels in the
     * source) are preserved by calling {@code getEntityLocations} on the source and inserting
     * the entity at EVERY location key, not just the deepest one.  The first insert creates
     * the entity in the target's entity manager; subsequent inserts for the same ID add the
     * additional location keys via {@code createOrUpdate + addEntityLocation} without
     * duplicating the content or changing the position.
     *
     * @return the list of per-entity error messages (empty if all succeeded)
     */
    private static <ID extends EntityID, Content, Target> List<String> migrateEntities(
            Object source,
            Target target,
            Map<ID, Point3f> entitiesWithPositions,
            ConversionStats stats) {

        var processedCount = new AtomicInteger(0);
        // Luciferase-2qpd2: atomic failure counter mirroring processedCount, so the tally is correct even if the
        // iteration is ever parallelized (Map.forEach is sequential today). errors is collected sequentially.
        var failedCount = new AtomicInteger(0);
        var skippedCount = new AtomicInteger(0);
        var errors = java.util.Collections.synchronizedList(new ArrayList<String>());

        entitiesWithPositions.forEach((entityId, position) -> {
            try {
                // Get entity content from source
                Content content = null;

                if (source instanceof Octree<?, ?>) {
                    var octree = (Octree<ID, Content>) source;
                    content = octree.getEntity(entityId);
                } else if (source instanceof Tetree<?, ?>) {
                    var tetree = (Tetree<ID, Content>) source;
                    content = tetree.getEntity(entityId);
                }

                // Luciferase-7wzml.16: skip null-content entities — do NOT count as success.
                if (content == null) {
                    skippedCount.incrementAndGet();
                    log.warn("Skipping entity {} at {} — content is null (not migrated)", entityId, position);
                    return;
                }

                // Luciferase-7wzml.17: preserve spanning — insert at EVERY location key, not just deepest.
                // getEntityLocations returns all keys (levels) at which the source stores this entity.
                // For a point entity this is a single key; for a spanning entity it is multiple.
                final Content finalContent = content;
                if (source instanceof Octree<?, ?>) {
                    var octree = (Octree<ID, Content>) source;
                    insertAtAllLocations(entityId, position, finalContent, octree.getEntityLocations(entityId),
                                        octree.getMaxDepth(), target);
                } else if (source instanceof Tetree<?, ?>) {
                    var tetree = (Tetree<ID, Content>) source;
                    insertAtAllLocations(entityId, position, finalContent, tetree.getEntityLocations(entityId),
                                        tetree.getMaxDepth(), target);
                }

                processedCount.incrementAndGet();
            } catch (Exception e) {
                failedCount.incrementAndGet();
                errors.add(String.format("Failed to migrate entity %s: %s",
                                         entityId, e.getMessage()));
            }
        });

        stats.successfulEntities = processedCount.get();
        stats.failedEntities = failedCount.get();
        stats.skippedEntities = skippedCount.get();

        if (skippedCount.get() > 0) {
            log.warn("Migration skipped {} entities with null content", skippedCount.get());
        }
        if (!errors.isEmpty()) {
            log.warn("Migration completed with {} errors", errors.size());
            errors.stream().limit(10).forEach(log::warn);
            if (errors.size() > 10) {
                log.warn("... and {} more errors", errors.size() - 10);
            }
        }
        return new ArrayList<>(errors);
    }

    /**
     * Insert {@code entityId} into {@code target} at every spatial key in {@code locations}.
     *
     * <p>If {@code locations} is empty (entity has no recorded location in source) we fall back
     * to a single insert at {@code min(3, maxDepth)} to preserve prior behaviour for that
     * degenerate case.
     *
     * <p>The first call to {@code insert(entityId, position, level, content)} creates the
     * entity record in the target's entity manager; subsequent calls for the same ID reach
     * {@code createOrUpdateEntity} (no-op for content/position) and then {@code addEntityLocation}
     * — accumulating the spanning key set without duplicating the entity.
     */
    @SuppressWarnings("unchecked")
    private static <ID extends EntityID, Content, Target> void insertAtAllLocations(
            ID entityId, Point3f position, Content content,
            java.util.Set<?> locations, byte maxDepth, Target target) {

        if (locations.isEmpty()) {
            // Fallback: no recorded locations — single insert at a sensible default level.
            byte defaultLevel = (byte) Math.min(3, maxDepth);
            insertIntoTarget(target, entityId, position, defaultLevel, content);
            return;
        }

        // Insert at every source location key to preserve the full spanning set.
        for (var rawKey : locations) {
            byte level = (byte) ((SpatialKey<?>) rawKey).getLevel();
            insertIntoTarget(target, entityId, position, level, content);
        }
    }

    /** Dispatch insert to the concrete target type. */
    @SuppressWarnings("unchecked")
    private static <ID extends EntityID, Content, Target> void insertIntoTarget(
            Target target, ID entityId, Point3f position, byte level, Content content) {
        if (target instanceof Octree<?, ?>) {
            ((Octree<ID, Content>) target).insert(entityId, position, level, content);
        } else if (target instanceof Tetree<?, ?>) {
            ((Tetree<ID, Content>) target).insert(entityId, position, level, content);
        }
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
        // Bead Luciferase-f6di1: align with .130 fail-loud contract — track per-entity failures.
        var failedCount = new AtomicInteger(0);
        var errors = java.util.Collections.synchronizedList(new ArrayList<String>());

        entities.forEach((entityId, position) -> {
            try {
                var content = source.getEntity(entityId);
                if (content == null) {
                    log.warn("Skipping entity {} in progress-conversion — content is null", entityId);
                    return;
                }
                var locations = source.getEntityLocations(entityId);
                if (locations.isEmpty()) {
                    tetree.insert(entityId, position, (byte) Math.min(3, source.getMaxDepth()), content);
                } else {
                    for (var rawKey : locations) {
                        byte level = (byte) ((SpatialKey<?>) rawKey).getLevel();
                        tetree.insert(entityId, position, level, content);
                    }
                }

                var count = processed.incrementAndGet();
                if (callback != null && count % 100 == 0) {
                    callback.onProgress(count, total);
                }
            } catch (Exception e) {
                failedCount.incrementAndGet();
                errors.add(String.format("Failed to migrate entity %s: %s", entityId, e.getMessage()));
                log.warn("Progress-conversion: failed to migrate entity {}: {}", entityId, e.getMessage(), e);
            }
        });

        tetree.finalizeBulkLoading();

        if (callback != null) {
            callback.onComplete(total);
        }

        // Bead Luciferase-f6di1: surface per-entity failures — consistent with .130 fail-loud contract.
        if (failedCount.get() > 0) {
            throw new ConversionException(
                String.format("Octree to Tetree (progress) conversion lost %d of %d entities. Errors: %s",
                              failedCount.get(), total, errors));
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
        // Bead Luciferase-f6di1: align with .130 fail-loud contract — track per-entity failures.
        var failedCount = new AtomicInteger(0);
        var errors = java.util.Collections.synchronizedList(new ArrayList<String>());

        entities.forEach((entityId, position) -> {
            try {
                var content = source.getEntity(entityId);
                if (content == null) {
                    log.warn("Skipping entity {} in progress-conversion — content is null", entityId);
                    return;
                }
                var locations = source.getEntityLocations(entityId);
                if (locations.isEmpty()) {
                    octree.insert(entityId, position, (byte) Math.min(3, source.getMaxDepth()), content);
                } else {
                    for (var rawKey : locations) {
                        byte level = (byte) ((SpatialKey<?>) rawKey).getLevel();
                        octree.insert(entityId, position, level, content);
                    }
                }

                var count = processed.incrementAndGet();
                if (callback != null && count % 100 == 0) {
                    callback.onProgress(count, total);
                }
            } catch (Exception e) {
                failedCount.incrementAndGet();
                errors.add(String.format("Failed to migrate entity %s: %s", entityId, e.getMessage()));
                log.warn("Progress-conversion: failed to migrate entity {}: {}", entityId, e.getMessage(), e);
            }
        });

        octree.finalizeBulkLoading();

        if (callback != null) {
            callback.onComplete(total);
        }

        // Bead Luciferase-f6di1: surface per-entity failures — consistent with .130 fail-loud contract.
        if (failedCount.get() > 0) {
            throw new ConversionException(
                String.format("Tetree to Octree (progress) conversion lost %d of %d entities. Errors: %s",
                              failedCount.get(), total, errors));
        }

        return octree;
    }

    private static void logConversionStats(String conversionType, ConversionStats stats, long duration) {
        log.info("{} conversion completed in {}ms", conversionType, duration);
        log.info("Total entities: {}, Successful: {}, Skipped (null content): {}, Failed: {}",
                 stats.totalEntities, stats.successfulEntities, stats.skippedEntities, stats.failedEntities);

        if (stats.skippedEntities > 0) {
            log.warn("Conversion skipped {} entities with null content", stats.skippedEntities);
        }
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
        /** Entities skipped because their content was null (Luciferase-7wzml.16). */
        int skippedEntities = 0;
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
