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
import java.util.concurrent.RecursiveTask;
import java.util.stream.Collectors;

/**
 * Processes bulk operations for spatial indices, optimizing Morton code calculation,
 * entity sorting, and node grouping for improved cache locality and performance.
 *
 * @param <ID> The type of EntityID used for entity identification
 * @param <Content> The type of content stored with each entity
 * 
 * @author hal.hildebrand
 */
public class BulkOperationProcessor<ID extends EntityID, Content> {
    
    /**
     * Entity wrapper that includes pre-calculated Morton code
     */
    public static class MortonEntity<Content> {
        public final int originalIndex;
        public final long mortonCode;
        public final Point3f position;
        public final Content content;
        
        public MortonEntity(int originalIndex, long mortonCode, Point3f position, Content content) {
            this.originalIndex = originalIndex;
            this.mortonCode = mortonCode;
            this.position = position;
            this.content = content;
        }
    }
    
    /**
     * Result of grouping entities by their target spatial node
     */
    public static class GroupedEntities<Content> {
        private final Map<Long, List<MortonEntity<Content>>> groups;
        private final int totalEntities;
        private final int groupCount;
        
        public GroupedEntities(Map<Long, List<MortonEntity<Content>>> groups) {
            this.groups = groups;
            this.totalEntities = groups.values().stream().mapToInt(List::size).sum();
            this.groupCount = groups.size();
        }
        
        public Map<Long, List<MortonEntity<Content>>> getGroups() { return groups; }
        public int getTotalEntities() { return totalEntities; }
        public int getGroupCount() { return groupCount; }
        public double getAverageGroupSize() { return groupCount > 0 ? (double) totalEntities / groupCount : 0; }
    }
    
    private final AbstractSpatialIndex<ID, Content, ?> spatialIndex;
    private final ForkJoinPool forkJoinPool;
    
    public BulkOperationProcessor(AbstractSpatialIndex<ID, Content, ?> spatialIndex) {
        this.spatialIndex = spatialIndex;
        this.forkJoinPool = ForkJoinPool.commonPool();
    }
    
    /**
     * Pre-calculate Morton codes for all positions and optionally sort by spatial locality
     */
    public List<MortonEntity<Content>> preprocessBatch(List<Point3f> positions, 
                                                      List<Content> contents, 
                                                      byte level,
                                                      boolean sortByMorton) {
        if (positions.size() != contents.size()) {
            throw new IllegalArgumentException("Positions and contents must have the same size");
        }
        
        List<MortonEntity<Content>> mortonEntities = new ArrayList<>(positions.size());
        
        // Calculate Morton codes
        for (int i = 0; i < positions.size(); i++) {
            long mortonCode = spatialIndex.calculateSpatialIndex(positions.get(i), level);
            mortonEntities.add(new MortonEntity<>(i, mortonCode, positions.get(i), contents.get(i)));
        }
        
        // Sort by Morton code if requested
        if (sortByMorton) {
            mortonEntities.sort(Comparator.comparingLong(e -> e.mortonCode));
        }
        
        return mortonEntities;
    }
    
    /**
     * Parallel calculation of Morton codes for large batches
     */
    public List<MortonEntity<Content>> preprocessBatchParallel(List<Point3f> positions,
                                                              List<Content> contents,
                                                              byte level,
                                                              boolean sortByMorton,
                                                              int parallelThreshold) {
        if (positions.size() < parallelThreshold) {
            return preprocessBatch(positions, contents, level, sortByMorton);
        }
        
        // Use Fork/Join for parallel Morton code calculation
        MortonCalculationTask task = new MortonCalculationTask(positions, contents, level, 0, positions.size());
        List<MortonEntity<Content>> mortonEntities = forkJoinPool.invoke(task);
        
        // Sort by Morton code if requested
        if (sortByMorton) {
            mortonEntities.sort(Comparator.comparingLong(e -> e.mortonCode));
        }
        
        return mortonEntities;
    }
    
    /**
     * Group entities by their target spatial node for batch insertion
     */
    public GroupedEntities<Content> groupByNode(List<MortonEntity<Content>> sortedEntities, byte level) {
        Map<Long, List<MortonEntity<Content>>> groups = new LinkedHashMap<>();
        
        for (MortonEntity<Content> entity : sortedEntities) {
            // For octree, the node index at a given level is the morton code truncated to that level
            long nodeIndex = truncateToLevel(entity.mortonCode, level);
            groups.computeIfAbsent(nodeIndex, k -> new ArrayList<>()).add(entity);
        }
        
        return new GroupedEntities<>(groups);
    }
    
    /**
     * Group entities with adaptive level selection based on entity density
     */
    public GroupedEntities<Content> groupByNodeAdaptive(List<MortonEntity<Content>> sortedEntities,
                                                       byte minLevel,
                                                       byte maxLevel,
                                                       int targetGroupSize) {
        Map<Long, List<MortonEntity<Content>>> groups = new LinkedHashMap<>();
        
        // Start with minimum level and refine as needed
        byte currentLevel = minLevel;
        
        for (MortonEntity<Content> entity : sortedEntities) {
            long nodeIndex = truncateToLevel(entity.mortonCode, currentLevel);
            List<MortonEntity<Content>> group = groups.computeIfAbsent(nodeIndex, k -> new ArrayList<>());
            group.add(entity);
            
            // If group is getting too large and we haven't reached max level, split it
            if (group.size() > targetGroupSize && currentLevel < maxLevel) {
                List<MortonEntity<Content>> toSplit = new ArrayList<>(group);
                groups.remove(nodeIndex);
                
                // Re-distribute at higher level
                currentLevel++;
                for (MortonEntity<Content> e : toSplit) {
                    long newNodeIndex = truncateToLevel(e.mortonCode, currentLevel);
                    groups.computeIfAbsent(newNodeIndex, k -> new ArrayList<>()).add(e);
                }
            }
        }
        
        return new GroupedEntities<>(groups);
    }
    
    /**
     * Analyze batch characteristics for optimization decisions
     */
    public BatchAnalysis analyzeBatch(List<MortonEntity<Content>> entities) {
        if (entities.isEmpty()) {
            return new BatchAnalysis();
        }
        
        // Calculate spatial bounds
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = Float.MIN_VALUE, maxY = Float.MIN_VALUE, maxZ = Float.MIN_VALUE;
        
        for (MortonEntity<Content> entity : entities) {
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
        
        // Analyze Morton code distribution for clustering
        Set<Long> uniquePrefixes = new HashSet<>();
        for (MortonEntity<Content> entity : entities) {
            // Check common prefixes at different levels
            uniquePrefixes.add(entity.mortonCode >> 12); // Level ~7
        }
        
        float clusteringFactor = 1.0f - (float) uniquePrefixes.size() / entities.size();
        
        return new BatchAnalysis(entities.size(), density, clusteringFactor,
                                minX, minY, minZ, maxX, maxY, maxZ);
    }
    
    /**
     * Batch analysis results
     */
    public static class BatchAnalysis {
        public final int entityCount;
        public final float density;
        public final float clusteringFactor;
        public final float minX, minY, minZ;
        public final float maxX, maxY, maxZ;
        
        public BatchAnalysis() {
            this(0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
        
        public BatchAnalysis(int entityCount, float density, float clusteringFactor,
                           float minX, float minY, float minZ,
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
    
    // Helper method to truncate Morton code to specific level
    private long truncateToLevel(long mortonCode, byte level) {
        // For octree, each level uses 3 bits
        int bitsToKeep = level * 3;
        if (bitsToKeep >= 64) {
            return mortonCode;
        }
        long mask = (1L << bitsToKeep) - 1;
        return mortonCode & mask;
    }
    
    /**
     * Fork/Join task for parallel Morton code calculation
     */
    private class MortonCalculationTask extends RecursiveTask<List<MortonEntity<Content>>> {
        private static final int THRESHOLD = 1000;
        private final List<Point3f> positions;
        private final List<Content> contents;
        private final byte level;
        private final int start;
        private final int end;
        
        MortonCalculationTask(List<Point3f> positions, List<Content> contents, 
                             byte level, int start, int end) {
            this.positions = positions;
            this.contents = contents;
            this.level = level;
            this.start = start;
            this.end = end;
        }
        
        @Override
        protected List<MortonEntity<Content>> compute() {
            if (end - start <= THRESHOLD) {
                // Sequential computation for small chunks
                List<MortonEntity<Content>> result = new ArrayList<>(end - start);
                for (int i = start; i < end; i++) {
                    long mortonCode = spatialIndex.calculateSpatialIndex(positions.get(i), level);
                    result.add(new MortonEntity<>(i, mortonCode, positions.get(i), contents.get(i)));
                }
                return result;
            } else {
                // Split task
                int mid = start + (end - start) / 2;
                MortonCalculationTask leftTask = new MortonCalculationTask(positions, contents, level, start, mid);
                MortonCalculationTask rightTask = new MortonCalculationTask(positions, contents, level, mid, end);
                
                leftTask.fork();
                List<MortonEntity<Content>> rightResult = rightTask.compute();
                List<MortonEntity<Content>> leftResult = leftTask.join();
                
                // Combine results
                List<MortonEntity<Content>> combined = new ArrayList<>(leftResult.size() + rightResult.size());
                combined.addAll(leftResult);
                combined.addAll(rightResult);
                return combined;
            }
        }
    }
}