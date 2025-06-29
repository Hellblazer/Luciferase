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
package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.entity.EntityID;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.RecursiveTask;

/**
 * Processes bulk operations for spatial indices, optimizing Morton code calculation, entity sorting, and node grouping
 * for improved cache locality and performance.
 *
 * @param <ID>      The type of EntityID used for entity identification
 * @param <Content> The type of content stored with each entity
 * @author hal.hildebrand
 */
public class BulkOperationProcessor<Key extends SpatialKey<Key>, ID extends EntityID, Content> {

    private final AbstractSpatialIndex<Key, ID, Content, ?> spatialIndex;
    private final ForkJoinPool                              forkJoinPool;

    public BulkOperationProcessor(AbstractSpatialIndex<Key, ID, Content, ?> spatialIndex) {
        this.spatialIndex = spatialIndex;
        this.forkJoinPool = ForkJoinPool.commonPool();
    }

    /**
     * Analyze batch characteristics for optimization decisions
     */
    public BatchAnalysis analyzeBatch(List<SfcEntity<Key, Content>> entities) {
        if (entities.isEmpty()) {
            return new BatchAnalysis();
        }

        // Calculate spatial bounds
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = Float.MIN_VALUE, maxY = Float.MIN_VALUE, maxZ = Float.MIN_VALUE;

        for (var entity : entities) {
            Point3f pos = entity.position;
            minX = Math.min(minX, pos.x);
            minY = Math.min(minY, pos.y);
            minZ = Math.min(minZ, pos.z);
            maxX = Math.max(maxX, pos.x);
            maxY = Math.max(maxY, pos.y);
            maxZ = Math.max(maxZ, pos.z);
        }

        // Calculate density and distribution metrics
        float volume = (maxX - minX) * (maxY - minY) * (maxZ - minZ);
        float density = volume > 0 ? entities.size() / volume : 0;

        // Analyze spatial key distribution for clustering
        Set<Byte> uniqueLevels = new HashSet<>();
        for (var entity : entities) {
            // Check levels for clustering analysis
            uniqueLevels.add(entity.sfcIndex.getLevel());
        }

        float clusteringFactor = 1.0f - (float) uniqueLevels.size() / entities.size();

        return new BatchAnalysis(entities.size(), density, clusteringFactor, minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * Group entities by their target spatial node for batch insertion
     */
    public GroupedEntities<Key, Content> groupByNode(List<SfcEntity<Key, Content>> sortedEntities, byte level) {
        Map<Key, List<SfcEntity<Key, Content>>> groups = new LinkedHashMap<>();

        for (var entity : sortedEntities) {
            // For octree, the node index at a given level is the morton code truncated to that level
            var nodeIndex = truncateToLevel(entity.sfcIndex, level);
            groups.computeIfAbsent(nodeIndex, k -> new ArrayList<>()).add(entity);
        }

        return new GroupedEntities<>(groups);
    }

    /**
     * Group entities with adaptive level selection based on entity density
     */
    public GroupedEntities<Key, Content> groupByNodeAdaptive(List<SfcEntity<Key, Content>> sortedEntities,
                                                             byte minLevel, byte maxLevel, int targetGroupSize) {
        Map<Key, List<SfcEntity<Key, Content>>> groups = new LinkedHashMap<>();

        // Start with minimum level and refine as needed
        byte currentLevel = minLevel;

        for (var entity : sortedEntities) {
            var nodeIndex = truncateToLevel(entity.sfcIndex, currentLevel);
            var group = groups.computeIfAbsent(nodeIndex, k -> new ArrayList<>());
            group.add(entity);

            // If group is getting too large and we haven't reached max level, split it
            if (group.size() > targetGroupSize && currentLevel < maxLevel) {
                var toSplit = new ArrayList<>(group);
                groups.remove(nodeIndex);

                // Re-distribute at higher level
                currentLevel++;
                for (var e : toSplit) {
                    var newNodeIndex = truncateToLevel(e.sfcIndex, currentLevel);
                    groups.computeIfAbsent(newNodeIndex, k -> new ArrayList<>()).add(e);
                }
            }
        }

        return new GroupedEntities<>(groups);
    }

    /**
     * Pre-calculate Morton codes for all positions and optionally sort by spatial locality
     */
    public List<SfcEntity<Key, Content>> preprocessBatch(List<Point3f> positions, List<Content> contents, byte level,
                                                         boolean sortByMorton) {
        if (positions.size() != contents.size()) {
            throw new IllegalArgumentException("Positions and contents must have the same size");
        }

        var sfcEntities = new ArrayList<SfcEntity<Key, Content>>(positions.size());

        // Calculate Morton codes
        for (int i = 0; i < positions.size(); i++) {
            var sfcIndex = spatialIndex.calculateSpatialIndex(positions.get(i), level);
            sfcEntities.add(new SfcEntity<>(i, sfcIndex, positions.get(i), contents.get(i)));
        }

        // Sort by Morton code if requested
        if (sortByMorton) {
            sfcEntities.sort(Comparator.comparing(e -> e.sfcIndex));
        }

        return sfcEntities;
    }

    /**
     * Parallel calculation of Morton codes for large batches
     */
    public List<SfcEntity<Key, Content>> preprocessBatchParallel(List<Point3f> positions, List<Content> contents,
                                                                 byte level, boolean sortByMorton,
                                                                 int parallelThreshold) {
        if (positions.size() < parallelThreshold) {
            return preprocessBatch(positions, contents, level, sortByMorton);
        }

        // Use Fork/Join for parallel spatial key calculation
        ForkJoinTask<List<SfcEntity<Key, Content>>> task = new SpatialKeyCalculationTask(positions, contents, level, 0,
                                                                                         positions.size());
        List<SfcEntity<Key, Content>> mortonEntities = forkJoinPool.invoke(task);

        // Sort by spatial key if requested
        if (sortByMorton) {
            mortonEntities.sort(Comparator.comparing(e -> e.sfcIndex));
        }

        return mortonEntities;
    }

    // Helper method to truncate spatial key to specific level
    private Key truncateToLevel(Key spatialKey, byte level) {
        // Delegate to the spatial index implementation for proper truncation
        // This allows each spatial structure to handle its own key truncation logic
        return spatialIndex.calculateSpatialIndex(new Point3f(0, 0, 0), level);
        // Note: This is a placeholder - the actual implementation should be provided
        // by the spatial index based on the key type
    }

    /**
     * Entity wrapper that includes pre-calculated Morton code
     */
    public static class SfcEntity<Key extends SpatialKey<Key>, Content> {
        public final int     originalIndex;
        public final Key     sfcIndex;
        public final Point3f position;
        public final Content content;

        public SfcEntity(int originalIndex, Key sfcIndex, Point3f position, Content content) {
            this.originalIndex = originalIndex;
            this.sfcIndex = sfcIndex;
            this.position = position;
            this.content = content;
        }
    }

    /**
     * Result of grouping entities by their target spatial node
     */
    public static class GroupedEntities<Key extends SpatialKey<Key>, Content> {
        private final Map<Key, List<SfcEntity<Key, Content>>> groups;
        private final int                                     totalEntities;
        private final int                                     groupCount;

        public GroupedEntities(Map<Key, List<SfcEntity<Key, Content>>> groups) {
            this.groups = groups;
            this.totalEntities = groups.values().stream().mapToInt(List::size).sum();
            this.groupCount = groups.size();
        }

        public double getAverageGroupSize() {
            return groupCount > 0 ? (double) totalEntities / groupCount : 0;
        }

        public int getGroupCount() {
            return groupCount;
        }

        public Map<Key, List<SfcEntity<Key, Content>>> getGroups() {
            return groups;
        }

        public int getTotalEntities() {
            return totalEntities;
        }
    }

    /**
     * Batch analysis results
     */
    public static class BatchAnalysis {
        public final int   entityCount;
        public final float density;
        public final float clusteringFactor;
        public final float minX, minY, minZ;
        public final float maxX, maxY, maxZ;

        public BatchAnalysis() {
            this(0, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        public BatchAnalysis(int entityCount, float density, float clusteringFactor, float minX, float minY, float minZ,
                             float maxX, float maxY, float maxZ) {
            this.entityCount = entityCount;
            this.density = density;
            this.clusteringFactor = clusteringFactor;
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }

        public float getVolume() {
            return (maxX - minX) * (maxY - minY) * (maxZ - minZ);
        }

        public boolean isHighlyClusteredst() {
            return clusteringFactor > 0.7f;
        }

        public boolean isSparse() {
            return density < 0.1f;
        }
    }

    /**
     * Fork/Join task for parallel spatial key calculation
     */
    private class SpatialKeyCalculationTask extends RecursiveTask<List<SfcEntity<Key, Content>>> {
        private static final int           THRESHOLD = 1000;
        private final        List<Point3f> positions;
        private final        List<Content> contents;
        private final        byte          level;
        private final        int           start;
        private final        int           end;

        SpatialKeyCalculationTask(List<Point3f> positions, List<Content> contents, byte level, int start, int end) {
            this.positions = positions;
            this.contents = contents;
            this.level = level;
            this.start = start;
            this.end = end;
        }

        @Override
        protected List<SfcEntity<Key, Content>> compute() {
            if (end - start <= THRESHOLD) {
                // Sequential computation for small chunks
                var result = new ArrayList<SfcEntity<Key, Content>>(end - start);
                for (int i = start; i < end; i++) {
                    Key spatialKey = spatialIndex.calculateSpatialIndex(positions.get(i), level);
                    result.add(new SfcEntity<Key, Content>(i, spatialKey, positions.get(i), contents.get(i)));
                }
                return result;
            } else {
                // Split task
                int mid = start + (end - start) / 2;
                SpatialKeyCalculationTask leftTask = new SpatialKeyCalculationTask(positions, contents, level, start,
                                                                                   mid);
                SpatialKeyCalculationTask rightTask = new SpatialKeyCalculationTask(positions, contents, level, mid,
                                                                                    end);

                leftTask.fork();
                List<SfcEntity<Key, Content>> rightResult = rightTask.compute();
                List<SfcEntity<Key, Content>> leftResult = leftTask.join();

                // Combine results
                List<SfcEntity<Key, Content>> combined = new ArrayList<>(leftResult.size() + rightResult.size());
                combined.addAll(leftResult);
                combined.addAll(rightResult);
                return combined;
            }
        }
    }
}
