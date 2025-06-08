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
import java.util.Map;
import java.util.TreeMap;

import static com.hellblazer.luciferase.lucien.TetrahedralSearchBase.SimplexAggregationStrategy;
import com.hellblazer.luciferase.lucien.SpatialSearchEngine.*;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;

/**
 * Factory for creating unified spatial search engines. Provides intelligent
 * engine selection based on workload characteristics and performance requirements.
 * 
 * @author hal.hildebrand
 */
public class SpatialEngineFactory {
    
    /**
     * Create a spatial search engine with explicit type preference
     * 
     * @param preferredType the preferred engine type (OCTREE or TETREE)
     * @param initialData initial data to populate the spatial index
     * @return configured spatial search engine
     */
    public static <Content> SpatialSearchEngine<Content> createOptimal(
            SpatialEngineType preferredType, 
            Map<Long, Content> initialData) {
        
        var data = initialData != null ? new TreeMap<>(initialData) : new TreeMap<Long, Content>();
        return switch (preferredType) {
            case OCTREE -> {
                var adapter = new OctreeWithEntitiesSpatialIndexAdapter<LongEntityID, Content>(
                    new SequentialLongIDGenerator()
                );
                // Pre-populate if initial data provided
                if (initialData != null) {
                    // Instead of directly using the Morton codes, we need to use proper spatial insertion
                    // Convert Morton codes back to coordinates and insert properly
                    for (Map.Entry<Long, Content> entry : initialData.entrySet()) {
                        // Decode the Morton code to get coordinates
                        var coords = com.hellblazer.luciferase.geometry.MortonCurve.decode(entry.getKey());
                        var level = Constants.toLevel(entry.getKey());
                        
                        // Insert using proper spatial coordinates
                        var point = new Point3f(coords[0], coords[1], coords[2]);
                        adapter.insert(point, level, entry.getValue());
                    }
                }
                yield new OctreeSpatialEngine<>(adapter);
            }
            case TETREE -> new TetreeSpatialEngine<>(new Tetree<>(data));
        };
    }
    
    /**
     * Create a spatial search engine optimized for specific performance characteristics
     * 
     * @param initialData initial data to populate the spatial index  
     * @param queryProfile profile describing expected query patterns
     * @return optimally configured spatial search engine
     */
    public static <Content> SpatialSearchEngine<Content> createForPerformance(
            Map<Long, Content> initialData, 
            SpatialQueryProfile queryProfile) {
        
        SpatialEngineType optimalType = queryProfile.favorsTetrahedral() ? 
            SpatialEngineType.TETREE : SpatialEngineType.OCTREE;
            
        return createOptimal(optimalType, initialData);
    }
    
    /**
     * Create a Tetree engine with specific aggregation strategy
     * 
     * @param initialData initial data to populate the spatial index
     * @param aggregationStrategy strategy for handling multiple tetrahedra per spatial region
     * @return configured Tetree spatial search engine
     */
    public static <Content> TetreeSpatialEngine<Content> createTetreeWithStrategy(
            Map<Long, Content> initialData,
            SimplexAggregationStrategy aggregationStrategy) {
        
        var data = initialData != null ? new TreeMap<>(initialData) : new TreeMap<Long, Content>();
        var tetree = new Tetree<Content>(data);
        return new TetreeSpatialEngine<>(tetree, aggregationStrategy);
    }
    
    /**
     * Create an empty spatial search engine for gradual population
     * 
     * @param preferredType the preferred engine type
     * @return empty spatial search engine ready for population
     */
    public static <Content> SpatialSearchEngine<Content> createEmpty(SpatialEngineType preferredType) {
        return createOptimal(preferredType, new TreeMap<>());
    }
    
    /**
     * Create the best engine for a specific coordinate system
     * 
     * @param coordinateSystem description of the coordinate system constraints
     * @return optimally configured spatial search engine
     */
    public static <Content> SpatialSearchEngine<Content> createForCoordinateSystem(
            CoordinateSystemProfile coordinateSystem) {
        
        // Tetree requires positive coordinates, Octree handles any coordinates
        SpatialEngineType optimalType = coordinateSystem.hasNegativeCoordinates() ? 
            SpatialEngineType.OCTREE : SpatialEngineType.TETREE;
            
        return createEmpty(optimalType);
    }
    
    /**
     * Create dual engines for comparative performance testing
     * 
     * @param initialData initial data to populate both engines
     * @return array containing [OctreeSpatialEngine, TetreeSpatialEngine]
     */
    @SuppressWarnings("unchecked")
    public static <Content> SpatialSearchEngine<Content>[] createDualEngines(Map<Long, Content> initialData) {
        var octreeEngine = (SpatialSearchEngine<Content>) createOptimal(SpatialEngineType.OCTREE, initialData);
        var tetreeEngine = (SpatialSearchEngine<Content>) createOptimal(SpatialEngineType.TETREE, initialData);
        
        return new SpatialSearchEngine[] { octreeEngine, tetreeEngine };
    }
    
    /**
     * Profile describing expected spatial query patterns
     */
    public static class SpatialQueryProfile {
        private final boolean geometricQueriesPreferred;
        private final boolean complexIntersectionQueries;
        private final boolean highPrecisionRequired;
        private final int expectedDatasetSize;
        
        public SpatialQueryProfile(boolean geometricQueriesPreferred, 
                                 boolean complexIntersectionQueries,
                                 boolean highPrecisionRequired,
                                 int expectedDatasetSize) {
            this.geometricQueriesPreferred = geometricQueriesPreferred;
            this.complexIntersectionQueries = complexIntersectionQueries;
            this.highPrecisionRequired = highPrecisionRequired;
            this.expectedDatasetSize = expectedDatasetSize;
        }
        
        /**
         * Determine if tetrahedral decomposition is favored for this workload
         */
        public boolean favorsTetrahedral() {
            // Tetree excels at complex geometric queries and high-precision operations
            return geometricQueriesPreferred || complexIntersectionQueries || highPrecisionRequired;
        }
        
        /**
         * Create profile for AABB-heavy workloads (favors Octree)
         */
        public static SpatialQueryProfile createAABBHeavy(int datasetSize) {
            return new SpatialQueryProfile(false, false, false, datasetSize);
        }
        
        /**
         * Create profile for geometric-heavy workloads (favors Tetree)
         */
        public static SpatialQueryProfile createGeometricHeavy(int datasetSize) {
            return new SpatialQueryProfile(true, true, true, datasetSize);
        }
        
        /**
         * Create balanced profile for mixed workloads
         */
        public static SpatialQueryProfile createBalanced(int datasetSize) {
            return new SpatialQueryProfile(true, false, false, datasetSize);
        }
    }
    
    /**
     * Profile describing coordinate system characteristics
     */
    public static class CoordinateSystemProfile {
        private final Point3f minBounds;
        private final Point3f maxBounds;
        private final boolean allowsNegativeCoordinates;
        
        public CoordinateSystemProfile(Point3f minBounds, Point3f maxBounds) {
            this.minBounds = minBounds;
            this.maxBounds = maxBounds;
            this.allowsNegativeCoordinates = minBounds.x < 0 || minBounds.y < 0 || minBounds.z < 0;
        }
        
        public boolean hasNegativeCoordinates() {
            return allowsNegativeCoordinates;
        }
        
        public Point3f getMinBounds() {
            return minBounds;
        }
        
        public Point3f getMaxBounds() {
            return maxBounds;
        }
        
        /**
         * Create profile for positive-only coordinates (optimal for Tetree)
         */
        public static CoordinateSystemProfile createPositiveOnly(float maxRange) {
            return new CoordinateSystemProfile(
                new Point3f(0.0f, 0.0f, 0.0f), 
                new Point3f(maxRange, maxRange, maxRange)
            );
        }
        
        /**
         * Create profile for full range coordinates (requires Octree)
         */
        public static CoordinateSystemProfile createFullRange(float range) {
            return new CoordinateSystemProfile(
                new Point3f(-range, -range, -range), 
                new Point3f(range, range, range)
            );
        }
    }
    
    /**
     * Adaptive engine wrapper that can switch between implementations
     */
    public static class AdaptiveSpatialEngine<Content> implements SpatialSearchEngine<Content> {
        private SpatialSearchEngine<Content> primaryEngine;
        private SpatialSearchEngine<Content> fallbackEngine;
        private final PerformanceMonitor monitor;
        
        public AdaptiveSpatialEngine(SpatialSearchEngine<Content> primary, SpatialSearchEngine<Content> fallback) {
            this.primaryEngine = primary;
            this.fallbackEngine = fallback;
            this.monitor = new PerformanceMonitor();
        }
        
        @Override
        public List<SpatialResult<Content>> boundedBy(Spatial volume) {
            return executeWithFallback(() -> primaryEngine.boundedBy(volume));
        }
        
        @Override
        public List<SpatialResult<Content>> kNearestNeighbors(Point3f point, int k) {
            return executeWithFallback(() -> primaryEngine.kNearestNeighbors(point, k));
        }
        
        @Override
        public List<SpatialResult<Content>> withinDistance(Point3f point, float distance) {
            return executeWithFallback(() -> primaryEngine.withinDistance(point, distance));
        }
        
        @Override
        public List<SpatialResult<Content>> rayIntersection(Ray3D ray) {
            return executeWithFallback(() -> primaryEngine.rayIntersection(ray));
        }
        
        @Override
        public List<SpatialResult<Content>> sphereIntersection(Point3f center, float radius) {
            return executeWithFallback(() -> primaryEngine.sphereIntersection(center, radius));
        }
        
        @Override
        public List<SpatialResult<Content>> planeIntersection(Plane3D plane) {
            return executeWithFallback(() -> primaryEngine.planeIntersection(plane));
        }
        
        @Override
        public List<SpatialResult<Content>> frustumCulling(Frustum3D frustum) {
            return executeWithFallback(() -> primaryEngine.frustumCulling(frustum));
        }
        
        @Override
        public List<SpatialResult<Content>> convexHullIntersection(ConvexHull hull, Point3f referencePoint) {
            return executeWithFallback(() -> primaryEngine.convexHullIntersection(hull, referencePoint));
        }
        
        @Override
        public List<SpatialResult<Content>> containedInSphere(Point3f center, float radius) {
            return executeWithFallback(() -> primaryEngine.containedInSphere(center, radius));
        }
        
        @Override
        public List<SpatialResult<Content>> withinDistanceRange(Point3f point, float minDistance, float maxDistance) {
            return executeWithFallback(() -> primaryEngine.withinDistanceRange(point, minDistance, maxDistance));
        }
        
        @Override
        public LineOfSightResult<Content> testLineOfSight(Point3f observer, Point3f target, double tolerance) {
            return executeWithFallback(() -> primaryEngine.testLineOfSight(observer, target, tolerance));
        }
        
        @Override
        public List<SpatialResult<Content>> visibleFrom(Point3f observer, float maxDistance) {
            return executeWithFallback(() -> primaryEngine.visibleFrom(observer, maxDistance));
        }
        
        @Override
        public List<SpatialResult<Content>> parallelBoundedBy(Spatial volume) {
            return executeWithFallback(() -> primaryEngine.parallelBoundedBy(volume));
        }
        
        @Override
        public SpatialEngineType getEngineType() {
            return primaryEngine.getEngineType();
        }
        
        @Override
        public PerformanceMetrics getPerformanceMetrics() {
            return primaryEngine.getPerformanceMetrics();
        }
        
        @Override
        public MemoryUsage getMemoryUsage() {
            return primaryEngine.getMemoryUsage();
        }
        
        private <T> T executeWithFallback(java.util.function.Supplier<T> operation) {
            try {
                long startTime = System.nanoTime();
                T result = operation.get();
                monitor.recordSuccess(System.nanoTime() - startTime);
                
                // Switch to fallback if primary consistently underperforms
                if (monitor.shouldSwitchEngine()) {
                    var temp = primaryEngine;
                    primaryEngine = fallbackEngine;
                    fallbackEngine = temp;
                    monitor.reset();
                }
                
                return result;
            } catch (Exception e) {
                monitor.recordFailure();
                // Throw the exception - let the caller handle fallback if needed
                throw new RuntimeException("Spatial query failed in adaptive engine", e);
            }
        }
        
        private static class PerformanceMonitor {
            private long totalOperations = 0;
            private long totalTime = 0;
            private long failures = 0;
            
            public void recordSuccess(long timeNanos) {
                totalOperations++;
                totalTime += timeNanos;
            }
            
            public void recordFailure() {
                failures++;
            }
            
            public boolean shouldSwitchEngine() {
                return failures > 10 || (totalOperations > 100 && getAverageTime() > 10_000_000); // 10ms threshold
            }
            
            public double getAverageTime() {
                return totalOperations > 0 ? totalTime / (double) totalOperations : 0.0;
            }
            
            public void reset() {
                totalOperations = 0;
                totalTime = 0;
                failures = 0;
            }
        }
    }
}