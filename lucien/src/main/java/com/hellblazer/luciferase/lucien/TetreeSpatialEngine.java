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

import static com.hellblazer.luciferase.lucien.TetrahedralSearchBase.SimplexAggregationStrategy;

/**
 * Tetree implementation of the unified SpatialSearchEngine interface.
 * Provides tetrahedral space-filling curve decomposition spatial indexing with
 * comprehensive search operations.
 * 
 * @param <Content> the type of content stored in the spatial index
 * 
 * @author hal.hildebrand
 */
public class TetreeSpatialEngine<Content> implements SpatialSearchEngine<Content> {
    
    private final Tetree<Content> tetree;
    private final SimplexAggregationStrategy defaultStrategy;
    private final AtomicLong queryCount = new AtomicLong(0);
    private final LongAdder totalQueryTime = new LongAdder();
    private final LongAdder cacheHits = new LongAdder();
    private final LongAdder cellsTraversed = new LongAdder();
    
    public TetreeSpatialEngine(Tetree<Content> tetree) {
        this(tetree, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
    }
    
    public TetreeSpatialEngine(Tetree<Content> tetree, SimplexAggregationStrategy defaultStrategy) {
        this.tetree = tetree;
        this.defaultStrategy = defaultStrategy;
    }
    
    @Override
    public List<SpatialResult<Content>> boundedBy(Spatial volume) {
        return executeQuery(() -> 
            tetree.boundedBy(volume)
                  .map(tet -> createSpatialResult(tet.index(), tet.cell()))
                  .toList()
        );
    }
    
    @Override
    public List<SpatialResult<Content>> kNearestNeighbors(Point3f point, int k) {
        return executeQuery(() -> 
            TetKNearestNeighborSearch.findKNearestNeighbors(point, k, tetree, defaultStrategy)
                                    .stream()
                                    .map(result -> createSpatialResult(result.index, result.content, result.distance, point))
                                    .toList()
        );
    }
    
    @Override
    public List<SpatialResult<Content>> withinDistance(Point3f point, float distance) {
        return executeQuery(() -> 
            TetProximitySearch.tetrahedraWithinDistanceRange(point, new TetProximitySearch.DistanceRange(0, distance, TetProximitySearch.ProximityType.CLOSE), tetree, defaultStrategy)
                             .stream()
                             .map(result -> createSpatialResult(result.index, result.content, result.distanceToQuery, point))
                             .toList()
        );
    }
    
    @Override
    public List<SpatialResult<Content>> rayIntersection(Ray3D ray) {
        return executeQuery(() -> 
            TetRayTracingSearch.rayIntersectedAll(ray, tetree, defaultStrategy)
                              .stream()
                              .map(result -> createSpatialResult(result.index, result.content))
                              .toList()
        );
    }
    
    @Override
    public List<SpatialResult<Content>> sphereIntersection(Point3f center, float radius) {
        return executeQuery(() -> 
            TetSphereIntersectionSearch.sphereIntersectedAll(center, radius, tetree, center, defaultStrategy)
                                      .stream()
                                      .map(result -> createSpatialResult(result.index, result.content))
                                      .toList()
        );
    }
    
    @Override
    public List<SpatialResult<Content>> planeIntersection(Plane3D plane) {
        return executeQuery(() -> 
            TetPlaneIntersectionSearch.planeIntersectedAll(plane, tetree, new Point3f(0, 0, 0), defaultStrategy)
                                     .stream()
                                     .map(result -> createSpatialResult(result.index, result.content))
                                     .toList()
        );
    }
    
    @Override
    public List<SpatialResult<Content>> frustumCulling(Frustum3D frustum) {
        return executeQuery(() -> 
            TetFrustumCullingSearch.frustumCulledAll(frustum, tetree, new Point3f(1000.0f, 1000.0f, 1000.0f), defaultStrategy)
                                  .stream()
                                  .map(result -> createSpatialResult(result.simplex.index(), result.simplex.cell()))
                                  .toList()
        );
    }
    
    @Override
    public List<SpatialResult<Content>> convexHullIntersection(ConvexHull hull, Point3f referencePoint) {
        // Create a simple tetrahedral convex hull
        var tetreeHull = TetConvexHullIntersectionSearch.TetConvexHull.createTetrahedralHull(
            referencePoint,
            new Point3f(referencePoint.x + 100, referencePoint.y, referencePoint.z),
            new Point3f(referencePoint.x, referencePoint.y + 100, referencePoint.z),
            new Point3f(referencePoint.x, referencePoint.y, referencePoint.z + 100)
        );
        
        return executeQuery(() -> 
            TetConvexHullIntersectionSearch.convexHullIntersectedAll(tetreeHull, tetree, referencePoint, defaultStrategy)
                                          .stream()
                                          .map(result -> createSpatialResult(result.index, result.content))
                                          .toList()
        );
    }
    
    @Override
    public List<SpatialResult<Content>> containedInSphere(Point3f center, float radius) {
        return executeQuery(() -> 
            TetContainmentSearch.tetrahedraContainedInSphere(center, radius, tetree, center)
                               .stream()
                               .map(result -> createSpatialResult(result.index, result.content))
                               .toList()
        );
    }
    
    @Override
    public List<SpatialResult<Content>> withinDistanceRange(Point3f point, float minDistance, float maxDistance) {
        return executeQuery(() -> 
            TetProximitySearch.tetrahedraWithinDistanceRange(point, new TetProximitySearch.DistanceRange(minDistance, maxDistance, TetProximitySearch.ProximityType.MODERATE), tetree, defaultStrategy)
                             .stream()
                             .map(result -> createSpatialResult(result.index, result.content, result.distanceToQuery, point))
                             .toList()
        );
    }
    
    @Override
    public LineOfSightResult<Content> testLineOfSight(Point3f observer, Point3f target, double tolerance) {
        return executeQuery(() -> {
            var result = TetVisibilitySearch.testLineOfSight(observer, target, tetree, tolerance, defaultStrategy);
            return new TetreeLineOfSightResult<>(result);
        });
    }
    
    @Override
    public List<SpatialResult<Content>> visibleFrom(Point3f observer, float maxDistance) {
        return executeQuery(() -> 
            TetVisibilitySearch.findVisibleTetrahedra(observer, new javax.vecmath.Vector3f(0, 0, 1), (float)(Math.PI/2), maxDistance, tetree, defaultStrategy)
                              .stream()
                              .map(result -> createSpatialResult(result.index, result.content))
                              .toList()
        );
    }
    
    @Override
    public List<SpatialResult<Content>> parallelBoundedBy(Spatial volume) {
        return executeQuery(() -> 
            // Use standard tetree operations with parallel stream
            tetree.boundedBy(volume)
                  .parallel()
                  .map(tet -> createSpatialResult(tet.index(), tet.cell()))
                  .toList()
        );
    }
    
    @Override
    public SpatialEngineType getEngineType() {
        return SpatialEngineType.TETREE;
    }
    
    @Override
    public PerformanceMetrics getPerformanceMetrics() {
        return new TetreePerformanceMetrics();
    }
    
    @Override
    public MemoryUsage getMemoryUsage() {
        return new TetreeMemoryUsage();
    }
    
    /**
     * Get the configured aggregation strategy for this engine
     */
    public SimplexAggregationStrategy getAggregationStrategy() {
        return defaultStrategy;
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
        return new TetreeSpatialResult<>(key, content, distance, queryPoint);
    }
    
    /**
     * Tetree-specific spatial result implementation
     */
    private static class TetreeSpatialResult<Content> implements SpatialResult<Content> {
        private final long key;
        private final Content content;
        private final double distance;
        private final Point3f centroid;
        
        public TetreeSpatialResult(long key, Content content, double distance, Point3f queryPoint) {
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
            // Convert key to tetrahedron and get its center
            // For now, use a simple approximation
            return new Point3f(0, 0, 0); // TODO: implement proper centroid calculation
        }
    }
    
    /**
     * Tetree-specific line-of-sight result implementation
     */
    private static class TetreeLineOfSightResult<Content> implements LineOfSightResult<Content> {
        private final TetVisibilitySearch.TetLineOfSightResult<Content> originalResult;
        
        public TetreeLineOfSightResult(TetVisibilitySearch.TetLineOfSightResult<Content> originalResult) {
            this.originalResult = originalResult;
        }
        
        @Override
        public boolean isClear() {
            return originalResult.hasLineOfSight;
        }
        
        @Override
        public double getTotalOcclusionRatio() {
            return originalResult.totalOcclusionRatio;
        }
        
        @Override
        public List<SpatialResult<Content>> getOccluders() {
            // Convert occluders to unified format
            return originalResult.occludingTetrahedra.stream()
                .map(occluder -> (SpatialResult<Content>) new TetreeSpatialResult<>(
                    occluder.index, 
                    occluder.content, 
                    0.0, 
                    null))
                .toList();
        }
        
        @Override
        public double getDistance() {
            return originalResult.distanceThroughOccluders;
        }
    }
    
    /**
     * Tetree performance metrics implementation
     */
    private class TetreePerformanceMetrics implements PerformanceMetrics {
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
     * Tetree memory usage implementation
     */
    private class TetreeMemoryUsage implements MemoryUsage {
        @Override
        public double getMemoryPerEntry() {
            return 0.10; // KB per entry (similar to Octree efficiency)
        }
        
        @Override
        public double getTotalMemoryUsage() {
            return getEntryCount() * getMemoryPerEntry();
        }
        
        @Override
        public long getEntryCount() {
            return tetree.size();
        }
    }
}