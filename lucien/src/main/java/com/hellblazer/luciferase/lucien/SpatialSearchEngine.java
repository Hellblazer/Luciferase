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

/**
 * Unified interface for spatial search operations that abstracts the differences
 * between Octree and Tetree implementations. Provides a consistent API for all
 * spatial indexing operations regardless of the underlying spatial decomposition.
 * 
 * This interface enables seamless switching between cubic (Octree) and tetrahedral 
 * (Tetree) spatial indexing based on performance characteristics and geometric
 * requirements.
 * 
 * @param <Content> the type of content stored in the spatial index
 * 
 * @author hal.hildebrand
 */
public interface SpatialSearchEngine<Content> {
    
    /**
     * Core spatial queries - basic operations available in both engines
     */
    
    /**
     * Find all entries bounded by the given spatial volume
     * @param volume the bounding volume to search within
     * @return list of spatial results within the volume
     */
    List<SpatialResult<Content>> boundedBy(Spatial volume);
    
    /**
     * Find the k nearest neighbors to a given point
     * @param point the query point
     * @param k the number of neighbors to find
     * @return list of k nearest spatial results
     */
    List<SpatialResult<Content>> kNearestNeighbors(Point3f point, int k);
    
    /**
     * Find all entries within a given distance from a point
     * @param point the center point
     * @param distance the maximum distance
     * @return list of spatial results within distance
     */
    List<SpatialResult<Content>> withinDistance(Point3f point, float distance);
    
    /**
     * Advanced geometric queries - complex spatial operations
     */
    
    /**
     * Find all entries intersected by a ray
     * @param ray the ray to test intersection with
     * @return list of spatial results intersected by the ray
     */
    List<SpatialResult<Content>> rayIntersection(Ray3D ray);
    
    /**
     * Find all entries intersected by a sphere
     * @param center the sphere center
     * @param radius the sphere radius
     * @return list of spatial results intersected by the sphere
     */
    List<SpatialResult<Content>> sphereIntersection(Point3f center, float radius);
    
    /**
     * Find all entries intersected by a plane
     * @param plane the plane to test intersection with
     * @return list of spatial results intersected by the plane
     */
    List<SpatialResult<Content>> planeIntersection(Plane3D plane);
    
    /**
     * Find all entries visible within a viewing frustum
     * @param frustum the viewing frustum
     * @return list of spatial results visible within the frustum
     */
    List<SpatialResult<Content>> frustumCulling(Frustum3D frustum);
    
    /**
     * Find all entries intersected by a convex hull
     * @param hull the convex hull to test intersection with
     * @param referencePoint reference point for hull orientation
     * @return list of spatial results intersected by the convex hull
     */
    List<SpatialResult<Content>> convexHullIntersection(ConvexHull hull, Point3f referencePoint);
    
    /**
     * Find all entries completely contained within a sphere
     * @param center the sphere center
     * @param radius the sphere radius
     * @return list of spatial results contained within the sphere
     */
    List<SpatialResult<Content>> containedInSphere(Point3f center, float radius);
    
    /**
     * Find all entries within a distance range from a point
     * @param point the center point
     * @param minDistance the minimum distance
     * @param maxDistance the maximum distance
     * @return list of spatial results within the distance range
     */
    List<SpatialResult<Content>> withinDistanceRange(Point3f point, float minDistance, float maxDistance);
    
    /**
     * Visibility and line-of-sight operations
     */
    
    /**
     * Test line-of-sight between two points
     * @param observer the observer point
     * @param target the target point
     * @param tolerance the occlusion tolerance
     * @return line-of-sight result with occlusion information
     */
    LineOfSightResult<Content> testLineOfSight(Point3f observer, Point3f target, double tolerance);
    
    /**
     * Find all entries visible from an observer point
     * @param observer the observer point
     * @param maxDistance the maximum visibility distance
     * @return list of visible spatial results
     */
    List<SpatialResult<Content>> visibleFrom(Point3f observer, float maxDistance);
    
    /**
     * Parallel processing operations
     */
    
    /**
     * Parallel version of boundedBy operation
     * @param volume the bounding volume to search within
     * @return list of spatial results within the volume
     */
    List<SpatialResult<Content>> parallelBoundedBy(Spatial volume);
    
    /**
     * Engine metadata and performance monitoring
     */
    
    /**
     * Get the type of spatial engine (Octree or Tetree)
     * @return the engine type
     */
    SpatialEngineType getEngineType();
    
    /**
     * Get performance metrics for this engine instance
     * @return performance metrics data
     */
    PerformanceMetrics getPerformanceMetrics();
    
    /**
     * Get memory usage statistics for this engine
     * @return memory usage information
     */
    MemoryUsage getMemoryUsage();
    
    /**
     * Result wrapper for spatial search operations
     */
    interface SpatialResult<Content> {
        /**
         * Get the spatial key/index for this result
         */
        long getSpatialKey();
        
        /**
         * Get the content associated with this spatial location
         */
        Content getContent();
        
        /**
         * Get the distance from the query point (if applicable)
         */
        double getDistance();
        
        /**
         * Get the centroid point of the spatial region
         */
        Point3f getCentroid();
    }
    
    /**
     * Line-of-sight test result
     */
    interface LineOfSightResult<Content> {
        /**
         * Whether line-of-sight is clear (not occluded)
         */
        boolean isClear();
        
        /**
         * Total occlusion ratio (0.0 = clear, 1.0 = fully occluded)
         */
        double getTotalOcclusionRatio();
        
        /**
         * List of occluding spatial results
         */
        List<SpatialResult<Content>> getOccluders();
        
        /**
         * Distance of line-of-sight test
         */
        double getDistance();
    }
    
    /**
     * Convex hull interface for hull intersection operations
     */
    interface ConvexHull {
        /**
         * Test if a point is inside this convex hull
         */
        boolean contains(Point3f point);
        
        /**
         * Test if this hull intersects with a tetrahedron
         */
        boolean intersectsTetrahedron(Point3f v0, Point3f v1, Point3f v2, Point3f v3);
        
        /**
         * Test if this hull intersects with a cube
         */
        boolean intersectsCube(Point3f minCorner, float sideLength);
    }
    
    /**
     * Performance metrics for monitoring engine performance
     */
    interface PerformanceMetrics {
        /**
         * Average query execution time in microseconds
         */
        double getAverageQueryTime();
        
        /**
         * Total number of queries executed
         */
        long getTotalQueries();
        
        /**
         * Cache hit ratio (0.0 to 1.0)
         */
        double getCacheHitRatio();
        
        /**
         * Number of spatial cells traversed per query
         */
        double getAverageCellsTraversed();
    }
    
    /**
     * Memory usage information
     */
    interface MemoryUsage {
        /**
         * Memory per entry in KB
         */
        double getMemoryPerEntry();
        
        /**
         * Total memory usage in KB
         */
        double getTotalMemoryUsage();
        
        /**
         * Number of entries stored
         */
        long getEntryCount();
    }
}

/**
 * Enumeration of spatial engine types
 */
enum SpatialEngineType {
    /**
     * Morton curve-based cubic decomposition (Octree)
     */
    OCTREE,
    
    /**
     * Tetrahedral space-filling curve decomposition (Tetree)
     */
    TETREE
}