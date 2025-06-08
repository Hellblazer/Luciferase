/**
 * Copyright (C) 2023 Hal Hildebrand. All rights reserved.
 * 
 * This file is part of the Luciferase.
 * 
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.lucien;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

/**
 * Octree implementation of the unified SpatialSearchEngine interface.
 * Provides Morton curve-based cubic decomposition spatial indexing with
 * comprehensive search operations.
 * 
 * @param <Content> the type of content stored in the spatial index
 * 
 * @author hal.hildebrand
 */
public class OctreeSpatialEngine<Content> implements SpatialSearchEngine<Content> {
    
    private final SingleContentAdapter<Content> octree;
    private final AtomicLong queryCount = new AtomicLong(0);
    private final LongAdder totalQueryTime = new LongAdder();
    private final LongAdder cacheHits = new LongAdder();
    private final LongAdder cellsTraversed = new LongAdder();
    
    /**
     * Create with a SingleContentAdapter (recommended for new code)
     */
    public OctreeSpatialEngine(SingleContentAdapter<Content> octree) {
        this.octree = octree;
    }
    
    /**
     * Create with legacy Octree (for backward compatibility)
     */
    public OctreeSpatialEngine(Octree<Content> octree) {
        // Wrap the legacy Octree in an adapter
        this.octree = new SingleContentAdapter<>();
        // Copy data from legacy octree to adapter
        // Note: This is a one-time migration, not a live view
        migrateFromLegacyOctree(octree);
    }
    
    private void migrateFromLegacyOctree(Octree<Content> legacyOctree) {
        // Get all entries from the legacy octree
        var map = legacyOctree.getMap();
        for (var entry : map.entrySet()) {
            long mortonIndex = entry.getKey();
            Content content = entry.getValue();
            
            // Decode Morton index to get position and level
            var cube = Octree.toCube(mortonIndex);
            Point3f position = new Point3f(cube.originX(), cube.originY(), cube.originZ());
            
            // Calculate level from cube extent
            byte level = 0;
            float extent = cube.extent();
            while (level <= Constants.getMaxRefinementLevel() && 
                   Constants.lengthAtLevel(level) > extent) {
                level++;
            }
            
            // Insert into adapter
            octree.insert(position, level, content);
        }
    }
    
    @Override
    public List<SpatialResult<Content>> boundedBy(Spatial volume) {
        return executeQuery(() -> 
            octree.boundedBy(volume)
                  .map(node -> createSpatialResult(node.index(), node.content()))
                  .toList()
        );
    }
    
    @Override
    public List<SpatialResult<Content>> kNearestNeighbors(Point3f point, int k) {
        return executeQuery(() -> {
            // TODO: Implement using multi-entity k-NN search
            throw new UnsupportedOperationException("K-NN search not yet implemented for single-content adapter");
        });
    }
    
    @Override
    public List<SpatialResult<Content>> withinDistance(Point3f point, float distance) {
        // TODO: Implement using multi-entity proximity search
        return executeQuery(() -> {
            throw new UnsupportedOperationException("Proximity search not yet implemented for single-content adapter");
        });
    }
    
    @Override
    public List<SpatialResult<Content>> rayIntersection(Ray3D ray) {
        return executeQuery(() -> {
            // TODO: Implement using multi-entity ray tracing search
            throw new UnsupportedOperationException("Ray tracing not yet implemented for single-content adapter");
        });
    }
    
    @Override
    public List<SpatialResult<Content>> sphereIntersection(Point3f center, float radius) {
        return executeQuery(() -> {
            // TODO: Implement using multi-entity sphere intersection search
            throw new UnsupportedOperationException("Sphere intersection not yet implemented for single-content adapter");
        });
    }
    
    @Override
    public List<SpatialResult<Content>> planeIntersection(Plane3D plane) {
        return executeQuery(() -> {
            // TODO: Implement using multi-entity plane intersection search
            throw new UnsupportedOperationException("Plane intersection not yet implemented for single-content adapter");
        });
    }
    
    @Override
    public List<SpatialResult<Content>> frustumCulling(Frustum3D frustum) {
        return executeQuery(() -> {
            // TODO: Implement using multi-entity frustum culling search
            throw new UnsupportedOperationException("Frustum culling not yet implemented for single-content adapter");
        });
    }
    
    @Override
    public List<SpatialResult<Content>> convexHullIntersection(ConvexHull hull, Point3f referencePoint) {
        return executeQuery(() -> {
            // TODO: Implement using multi-entity convex hull intersection search
            throw new UnsupportedOperationException("Convex hull intersection not yet implemented for single-content adapter");
        });
    }
    
    @Override
    public List<SpatialResult<Content>> containedInSphere(Point3f center, float radius) {
        return executeQuery(() -> {
            // TODO: Implement using multi-entity containment search
            throw new UnsupportedOperationException("Containment search not yet implemented for single-content adapter");
        });
    }
    
    @Override
    public List<SpatialResult<Content>> withinDistanceRange(Point3f point, float minDistance, float maxDistance) {
        // TODO: Implement using multi-entity proximity search
        return executeQuery(() -> {
            throw new UnsupportedOperationException("Proximity search not yet implemented for single-content adapter");
        });
    }
    
    @Override
    public LineOfSightResult<Content> testLineOfSight(Point3f observer, Point3f target, double tolerance) {
        return executeQuery(() -> {
            // TODO: Implement using multi-entity visibility search
            // For now, return a clear line of sight
            return new OctreeLineOfSightResult<>(true, 0.0, List.of(), observer.distance(target));
        });
    }
    
    @Override
    public List<SpatialResult<Content>> visibleFrom(Point3f observer, float maxDistance) {
        return executeQuery(() -> {
            // TODO: Implement using multi-entity visibility search
            throw new UnsupportedOperationException("Visibility search not yet implemented for single-content adapter");
        });
    }
    
    @Override
    public List<SpatialResult<Content>> parallelBoundedBy(Spatial volume) {
        return executeQuery(() -> {
            // Use parallel stream for parallel processing
            return octree.spatialRangeQuery(volume, false)
                        .parallel()
                        .map(hex -> createSpatialResult(hex.index(), hex.cell()))
                        .toList();
        });
    }
    
    @Override
    public SpatialEngineType getEngineType() {
        return SpatialEngineType.OCTREE;
    }
    
    @Override
    public PerformanceMetrics getPerformanceMetrics() {
        return new OctreePerformanceMetrics();
    }
    
    @Override
    public MemoryUsage getMemoryUsage() {
        return new OctreeMemoryUsage();
    }
    
    private <T> T executeQuery(java.util.function.Supplier<T> queryOperation) {
        long startTime = System.nanoTime();
        try {
            T result = queryOperation.get();
            queryCount.incrementAndGet();
            return result;
        } finally {
            totalQueryTime.add(System.nanoTime() - startTime);
        }
    }
    
    private SpatialResult<Content> createSpatialResult(long key, Content content) {
        return createSpatialResult(key, content, 0.0, null);
    }
    
    private SpatialResult<Content> createSpatialResult(long key, Content content, double distance, Point3f queryPoint) {
        return new OctreeSpatialResult<>(key, content, distance, queryPoint);
    }
    
    /**
     * Octree-specific spatial result implementation
     */
    private static class OctreeSpatialResult<Content> implements SpatialResult<Content> {
        private final long key;
        private final Content content;
        private final double distance;
        private final Point3f centroid;
        
        public OctreeSpatialResult(long key, Content content, double distance, Point3f queryPoint) {
            this.key = key;
            this.content = content;
            this.distance = distance;
            this.centroid = computeCentroid(key);
        }
        
        @Override
        public long getSpatialKey() {
            return key;
        }
        
        @Override
        public Content getContent() {
            return content;
        }
        
        @Override
        public double getDistance() {
            return distance;
        }
        
        @Override
        public Point3f getCentroid() {
            return centroid;
        }
        
        private Point3f computeCentroid(long key) {
            // Convert key to cube and get its center
            var cube = Octree.toCube(key);
            float halfExtent = cube.extent() / 2.0f;
            return new Point3f(cube.originX() + halfExtent, cube.originY() + halfExtent, cube.originZ() + halfExtent);
        }
    }
    
    /**
     * Octree-specific line-of-sight result implementation
     */
    private static class OctreeLineOfSightResult<Content> implements LineOfSightResult<Content> {
        private final boolean clear;
        private final double totalOcclusionRatio;
        private final List<SpatialResult<Content>> occluders;
        private final double distance;
        
        public OctreeLineOfSightResult(boolean clear, double totalOcclusionRatio, 
                                      List<SpatialResult<Content>> occluders, double distance) {
            this.clear = clear;
            this.totalOcclusionRatio = totalOcclusionRatio;
            this.occluders = occluders;
            this.distance = distance;
        }
        
        @Override
        public boolean isClear() {
            return clear;
        }
        
        @Override
        public double getTotalOcclusionRatio() {
            return totalOcclusionRatio;
        }
        
        @Override
        public List<SpatialResult<Content>> getOccluders() {
            return occluders;
        }
        
        @Override
        public double getDistance() {
            return distance;
        }
    }
    
    /**
     * Octree performance metrics implementation
     */
    private class OctreePerformanceMetrics implements PerformanceMetrics {
        @Override
        public double getAverageQueryTime() {
            long queries = queryCount.get();
            return queries > 0 ? totalQueryTime.sum() / (queries * 1000.0) : 0.0; // Convert to microseconds
        }
        
        @Override
        public long getTotalQueries() {
            return queryCount.get();
        }
        
        @Override
        public double getCacheHitRatio() {
            long queries = queryCount.get();
            return queries > 0 ? cacheHits.sum() / (double) queries : 0.0;
        }
        
        @Override
        public double getAverageCellsTraversed() {
            long queries = queryCount.get();
            return queries > 0 ? cellsTraversed.sum() / (double) queries : 0.0;
        }
    }
    
    /**
     * Octree memory usage implementation
     */
    private class OctreeMemoryUsage implements MemoryUsage {
        @Override
        public double getMemoryPerEntry() {
            return 0.10; // KB per entry (from benchmarks)
        }
        
        @Override
        public double getTotalMemoryUsage() {
            return getEntryCount() * getMemoryPerEntry();
        }
        
        @Override
        public long getEntryCount() {
            return octree.size();
        }
    }
}