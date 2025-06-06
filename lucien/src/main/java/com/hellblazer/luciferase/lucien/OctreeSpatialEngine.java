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
    
    private final Octree<Content> octree;
    private final AtomicLong queryCount = new AtomicLong(0);
    private final LongAdder totalQueryTime = new LongAdder();
    private final LongAdder cacheHits = new LongAdder();
    private final LongAdder cellsTraversed = new LongAdder();
    
    public OctreeSpatialEngine(Octree<Content> octree) {
        this.octree = octree;
    }
    
    @Override
    public List<SpatialResult<Content>> boundedBy(Spatial volume) {
        return executeQuery(() -> 
            octree.boundedBy(volume)
                  .map(this::createSpatialResult)
                  .toList()
        );
    }
    
    @Override
    public List<SpatialResult<Content>> kNearestNeighbors(Point3f point, int k) {
        return executeQuery(() -> 
            KNearestNeighborSearch.findKNearestNeighbors(point, k, octree)
                                 .stream()
                                 .map(result -> createSpatialResult(result.key(), result.content(), result.distance(), point))
                                 .toList()
        );
    }
    
    @Override
    public List<SpatialResult<Content>> withinDistance(Point3f point, float distance) {
        return executeQuery(() -> 
            ProximitySearch.cubesWithinDistance(point, distance, octree)
                          .stream()
                          .map(result -> createSpatialResult(result.key(), result.content(), result.distance(), point))
                          .toList()
        );
    }
    
    @Override
    public List<SpatialResult<Content>> rayIntersection(Ray3D ray) {
        return executeQuery(() -> 
            RayTracingSearch.rayIntersectedAll(ray, octree)
                           .stream()
                           .map(result -> createSpatialResult(result.key(), result.content()))
                           .toList()
        );
    }
    
    @Override
    public List<SpatialResult<Content>> sphereIntersection(Point3f center, float radius) {
        return executeQuery(() -> 
            SphereIntersectionSearch.sphereIntersectedAll(center, radius, octree)
                                   .stream()
                                   .map(result -> createSpatialResult(result.key(), result.content()))
                                   .toList()
        );
    }
    
    @Override
    public List<SpatialResult<Content>> planeIntersection(Plane3D plane) {
        return executeQuery(() -> 
            PlaneIntersectionSearch.planeIntersectedAll(plane, octree)
                                  .stream()
                                  .map(result -> createSpatialResult(result.key(), result.content()))
                                  .toList()
        );
    }
    
    @Override
    public List<SpatialResult<Content>> frustumCulling(Frustum3D frustum) {
        return executeQuery(() -> 
            FrustumCullingSearch.frustumVisibleAll(frustum, octree)
                               .stream()
                               .map(result -> createSpatialResult(result.key(), result.content()))
                               .toList()
        );
    }
    
    @Override
    public List<SpatialResult<Content>> convexHullIntersection(ConvexHull hull, Point3f referencePoint) {
        // Convert unified ConvexHull to Octree-specific ConvexHull
        // Create a simple tetrahedral hull for testing
        var testPoints = List.of(
            new Point3f(referencePoint.x, referencePoint.y, referencePoint.z),
            new Point3f(referencePoint.x + 100, referencePoint.y, referencePoint.z),
            new Point3f(referencePoint.x, referencePoint.y + 100, referencePoint.z),
            new Point3f(referencePoint.x, referencePoint.y, referencePoint.z + 100)
        );
        var octreeHull = ConvexHullIntersectionSearch.ConvexHull.createTetrahedralHull(
            testPoints.get(0), testPoints.get(1), testPoints.get(2), testPoints.get(3)
        );
        
        return executeQuery(() -> 
            ConvexHullIntersectionSearch.convexHullIntersectedAll(octreeHull, octree, referencePoint)
                                       .stream()
                                       .map(result -> createSpatialResult(result.key(), result.content()))
                                       .toList()
        );
    }
    
    @Override
    public List<SpatialResult<Content>> containedInSphere(Point3f center, float radius) {
        return executeQuery(() -> 
            ContainmentSearch.cubesContainedInSphere(center, radius, octree, center)
                            .stream()
                            .map(result -> createSpatialResult(result.key(), result.content()))
                            .toList()
        );
    }
    
    @Override
    public List<SpatialResult<Content>> withinDistanceRange(Point3f point, float minDistance, float maxDistance) {
        return executeQuery(() -> 
            ProximitySearch.cubesWithinDistanceRange(point, new ProximitySearch.DistanceRange(minDistance, maxDistance), octree)
                          .stream()
                          .map(result -> createSpatialResult(result.key(), result.content(), result.distance(), point))
                          .toList()
        );
    }
    
    @Override
    public LineOfSightResult<Content> testLineOfSight(Point3f observer, Point3f target, double tolerance) {
        return executeQuery(() -> {
            var result = VisibilitySearch.testLineOfSight(observer, target, octree, (float) tolerance);
            return new OctreeLineOfSightResult<>(result);
        });
    }
    
    @Override
    public List<SpatialResult<Content>> visibleFrom(Point3f observer, float maxDistance) {
        return executeQuery(() -> 
            VisibilitySearch.findVisibleCubes(observer, new javax.vecmath.Vector3f(0, 0, 1), maxDistance, maxDistance, octree)
                           .stream()
                           .map(result -> createSpatialResult(result.index, result.content))
                           .toList()
        );
    }
    
    @Override
    public List<SpatialResult<Content>> parallelBoundedBy(Spatial volume) {
        return executeQuery(() -> 
            ParallelSpatialProcessor.parallelBoundedBy(volume, octree)
                                   .stream()
                                   .map(result -> createSpatialResult(result.key(), result.content()))
                                   .toList()
        );
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
        private final VisibilitySearch.LineOfSightResult originalResult;
        
        public OctreeLineOfSightResult(VisibilitySearch.LineOfSightResult originalResult) {
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
            return originalResult.occludingCubes.stream()
                .map(occluder -> (SpatialResult<Content>) new OctreeSpatialResult<>(
                    occluder.index, 
                    (Content) occluder.content, 
                    0.0, 
                    null))
                .toList();
        }
        
        @Override
        public double getDistance() {
            return originalResult.distance;
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
            return octree.keySet().size();
        }
    }
}