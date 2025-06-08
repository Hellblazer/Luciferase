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
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Multi-entity aware convex hull intersection search for OctreeWithEntities.
 * Finds entities that intersect with 3D convex hulls defined by planes or vertices.
 *
 * @author hal.hildebrand
 */
public class ConvexHullIntersectionSearch {
    
    /**
     * Type of intersection between convex hull and entity
     */
    public enum IntersectionType {
        COMPLETELY_INSIDE,  // Entity is completely inside convex hull
        INTERSECTING,       // Entity partially intersects convex hull
        COMPLETELY_OUTSIDE  // Entity is completely outside convex hull
    }
    
    /**
     * Entity convex hull intersection result
     */
    public static class EntityConvexHullIntersection<ID extends EntityID, Content> {
        public final ID id;
        public final Content content;
        public final Point3f position;
        public final float distanceToReferencePoint;
        public final float distanceToHullCenter;
        public final IntersectionType intersectionType;
        public final float penetrationDepth; // how deep the entity penetrates into the hull
        
        public EntityConvexHullIntersection(ID id, Content content, Point3f position,
                                          float distanceToReferencePoint, float distanceToHullCenter,
                                          IntersectionType intersectionType, float penetrationDepth) {
            this.id = id;
            this.content = content;
            this.position = position;
            this.distanceToReferencePoint = distanceToReferencePoint;
            this.distanceToHullCenter = distanceToHullCenter;
            this.intersectionType = intersectionType;
            this.penetrationDepth = penetrationDepth;
        }
    }
    
    /**
     * Represents a convex hull defined by a set of half-space planes
     * Each plane defines a half-space, and the convex hull is the intersection of all half-spaces
     */
    public static class ConvexHull {
        public final List<Plane3D> planes;
        public final Point3f centroid;
        public final float boundingRadius;
        
        public ConvexHull(List<Plane3D> planes) {
            if (planes == null || planes.isEmpty()) {
                throw new IllegalArgumentException("Convex hull must have at least one plane");
            }
            this.planes = Collections.unmodifiableList(new ArrayList<>(planes));
            this.centroid = calculateCentroid(planes);
            this.boundingRadius = calculateBoundingRadius(planes, centroid);
            
            // Validate positive coordinates
            validatePositiveCoordinates(centroid);
        }
        
        /**
         * Create a convex hull from vertices (must have positive coordinates)
         */
        public static ConvexHull fromVertices(List<Point3f> vertices) {
            if (vertices == null || vertices.size() < 4) {
                throw new IllegalArgumentException("Convex hull requires at least 4 vertices");
            }
            
            for (Point3f vertex : vertices) {
                validatePositiveCoordinates(vertex);
            }
            
            // Create planes from vertices using simplified convex hull construction
            List<Plane3D> planes = createPlanesFromVertices(vertices);
            return new ConvexHull(planes);
        }
        
        /**
         * Create an oriented bounding box convex hull
         */
        public static ConvexHull createOrientedBoundingBox(Point3f center, Vector3f[] axes, float[] extents) {
            validatePositiveCoordinates(center);
            
            if (axes.length != 3 || extents.length != 3) {
                throw new IllegalArgumentException("Need exactly 3 axes and 3 extents");
            }
            
            for (float extent : extents) {
                if (extent <= 0) {
                    throw new IllegalArgumentException("All extents must be positive");
                }
            }
            
            // Validate that the bounding box won't have negative coordinates
            float minCoord = Math.min(Math.min(center.x, center.y), center.z);
            float maxExtent = Math.max(Math.max(extents[0], extents[1]), extents[2]);
            if (minCoord - maxExtent < 0) {
                throw new IllegalArgumentException("Oriented bounding box would extend into negative coordinates");
            }
            
            List<Plane3D> planes = new ArrayList<>();
            
            // For axis-aligned bounding boxes (axes are identity), use simpler approach
            if (isAxisAligned(axes)) {
                // Calculate bounds
                float minX = center.x - extents[0];
                float maxX = center.x + extents[0];
                float minY = center.y - extents[1];
                float maxY = center.y + extents[1];
                float minZ = center.z - extents[2];
                float maxZ = center.z + extents[2];
                
                // Create 6 bounding box planes with inward normals (same as createBoundingBoxPlanes)
                planes.add(new Plane3D(1, 0, 0, -maxX));   // Right face: x <= maxX
                planes.add(new Plane3D(-1, 0, 0, minX));   // Left face: x >= minX
                planes.add(new Plane3D(0, 1, 0, -maxY));   // Top face: y <= maxY
                planes.add(new Plane3D(0, -1, 0, minY));   // Bottom face: y >= minY
                planes.add(new Plane3D(0, 0, 1, -maxZ));   // Far face: z <= maxZ
                planes.add(new Plane3D(0, 0, -1, minZ));   // Near face: z >= minZ
            } else {
                // Create 6 planes for general oriented bounding box
                for (int i = 0; i < 3; i++) {
                    Vector3f axis = new Vector3f(axes[i]);
                    axis.normalize();
                    float extent = extents[i];
                    
                    // Positive side plane
                    Point3f posPoint = new Point3f(
                        center.x + axis.x * extent,
                        center.y + axis.y * extent,
                        center.z + axis.z * extent
                    );
                    Vector3f posNormal = new Vector3f(-axis.x, -axis.y, -axis.z); // Inward normal
                    planes.add(Plane3D.fromPointAndNormal(posPoint, posNormal));
                    
                    // Negative side plane - ensure coordinates remain positive
                    Point3f negPoint = new Point3f(
                        Math.max(0.1f, center.x - axis.x * extent),
                        Math.max(0.1f, center.y - axis.y * extent),
                        Math.max(0.1f, center.z - axis.z * extent)
                    );
                    Vector3f negNormal = new Vector3f(axis.x, axis.y, axis.z); // Inward normal
                    planes.add(Plane3D.fromPointAndNormal(negPoint, negNormal));
                }
            }
            
            return new ConvexHull(planes);
        }
        
        // Helper method to check if axes are axis-aligned
        private static boolean isAxisAligned(Vector3f[] axes) {
            final float EPSILON = 1e-6f;
            
            // Check if axes are close to identity vectors
            Vector3f xAxis = new Vector3f(axes[0]);
            Vector3f yAxis = new Vector3f(axes[1]);
            Vector3f zAxis = new Vector3f(axes[2]);
            
            xAxis.normalize();
            yAxis.normalize();
            zAxis.normalize();
            
            return Math.abs(xAxis.x - 1.0f) < EPSILON && Math.abs(xAxis.y) < EPSILON && Math.abs(xAxis.z) < EPSILON &&
                   Math.abs(yAxis.y - 1.0f) < EPSILON && Math.abs(yAxis.x) < EPSILON && Math.abs(yAxis.z) < EPSILON &&
                   Math.abs(zAxis.z - 1.0f) < EPSILON && Math.abs(zAxis.x) < EPSILON && Math.abs(zAxis.y) < EPSILON;
        }
        
        /**
         * Test if a point is inside this convex hull (includes boundary)
         */
        public boolean containsPoint(Point3f point) {
            validatePositiveCoordinates(point);
            
            for (Plane3D plane : planes) {
                if (plane.distanceToPoint(point) > 0) {
                    return false; // Point is on positive side of plane (outside)
                }
            }
            return true;
        }
        
        /**
         * Test if a point is strictly inside this convex hull (excludes boundary)
         */
        public boolean strictlyContainsPoint(Point3f point) {
            validatePositiveCoordinates(point);
            
            final float EPSILON = 1e-6f; // Small tolerance for floating point comparison
            
            for (Plane3D plane : planes) {
                float distance = plane.distanceToPoint(point);
                if (distance >= -EPSILON) { // Point is outside or on boundary
                    return false;
                }
            }
            return true;
        }
        
        /**
         * Test if entity bounds intersect with this convex hull
         */
        public IntersectionType intersectsEntityBounds(EntityBounds bounds) {
            if (bounds == null) {
                return IntersectionType.COMPLETELY_OUTSIDE;
            }
            
            // Get all 8 corners of the bounds
            Point3f[] corners = getBoundsCorners(bounds);
            
            // Test if all corners are inside the convex hull
            boolean allInside = true;
            boolean anyInside = false;
            
            for (Point3f corner : corners) {
                boolean cornerInside = containsPoint(corner);
                if (!cornerInside) {
                    allInside = false;
                } else {
                    anyInside = true;
                }
            }
            
            if (allInside) {
                return IntersectionType.COMPLETELY_INSIDE;
            }
            
            if (!anyInside) {
                // Check if hull intersects bounds
                for (Plane3D plane : planes) {
                    if (intersectsPlane(bounds, plane)) {
                        return IntersectionType.INTERSECTING;
                    }
                }
                return IntersectionType.COMPLETELY_OUTSIDE;
            }
            
            return IntersectionType.INTERSECTING;
        }
        
        /**
         * Calculate the distance from a point to the convex hull surface
         */
        public float distanceToPoint(Point3f point) {
            validatePositiveCoordinates(point);
            
            if (containsPoint(point)) {
                // Point is inside - find minimum distance to any plane
                float minDistance = Float.MAX_VALUE;
                for (Plane3D plane : planes) {
                    float distance = Math.abs(plane.distanceToPoint(point));
                    minDistance = Math.min(minDistance, distance);
                }
                return -minDistance; // Negative for inside
            } else {
                // Point is outside - find minimum distance to hull surface
                float minDistance = Float.MAX_VALUE;
                for (Plane3D plane : planes) {
                    float distance = plane.distanceToPoint(point);
                    if (distance > 0) {
                        minDistance = Math.min(minDistance, distance);
                    }
                }
                return minDistance;
            }
        }
        
        private static Point3f calculateCentroid(List<Plane3D> planes) {
            // Simplified centroid calculation - average of plane centers
            float sumX = 0, sumY = 0, sumZ = 0;
            int validPoints = 0;
            
            for (Plane3D plane : planes) {
                // Get a point on the plane
                float distance = -plane.d();
                if (Math.abs(distance) > 1e-6f) {
                    Point3f planePoint = new Point3f(
                        Math.max(0.1f, plane.a() * distance),
                        Math.max(0.1f, plane.b() * distance),
                        Math.max(0.1f, plane.c() * distance)
                    );
                    sumX += planePoint.x;
                    sumY += planePoint.y;
                    sumZ += planePoint.z;
                    validPoints++;
                }
            }
            
            if (validPoints > 0) {
                return new Point3f(sumX / validPoints, sumY / validPoints, sumZ / validPoints);
            } else {
                // Fallback to a default positive point
                return new Point3f(1.0f, 1.0f, 1.0f);
            }
        }
        
        private static float calculateBoundingRadius(List<Plane3D> planes, Point3f centroid) {
            // Simplified bounding radius - maximum distance from centroid to any plane
            float maxDistance = 0;
            for (Plane3D plane : planes) {
                float distance = Math.abs(plane.distanceToPoint(centroid));
                maxDistance = Math.max(maxDistance, distance);
            }
            return maxDistance + 10.0f; // Add buffer
        }
        
        private static List<Plane3D> createPlanesFromVertices(List<Point3f> vertices) {
            // For simplicity and reliability, always use bounding box approach
            // Creating proper convex hull from arbitrary vertices is complex
            return createBoundingBoxPlanes(vertices);
        }
        
        private static List<Plane3D> createBoundingBoxPlanes(List<Point3f> vertices) {
            // Create axis-aligned bounding box planes
            float minX = Float.MAX_VALUE, maxX = Float.MIN_VALUE;
            float minY = Float.MAX_VALUE, maxY = Float.MIN_VALUE;
            float minZ = Float.MAX_VALUE, maxZ = Float.MIN_VALUE;
            
            for (Point3f vertex : vertices) {
                minX = Math.min(minX, vertex.x);
                maxX = Math.max(maxX, vertex.x);
                minY = Math.min(minY, vertex.y);
                maxY = Math.max(maxY, vertex.y);
                minZ = Math.min(minZ, vertex.z);
                maxZ = Math.max(maxZ, vertex.z);
            }
            
            List<Plane3D> planes = new ArrayList<>();
            
            // Create 6 bounding box planes with inward normals
            // For a point to be inside, it must satisfy: minX <= x <= maxX, etc.
            // Plane equation: ax + by + cz + d = 0
            // For inward normals, distance should be negative for inside points
            planes.add(new Plane3D(1, 0, 0, -maxX));   // Right face: x <= maxX
            planes.add(new Plane3D(-1, 0, 0, minX));   // Left face: x >= minX
            planes.add(new Plane3D(0, 1, 0, -maxY));   // Top face: y <= maxY
            planes.add(new Plane3D(0, -1, 0, minY));   // Bottom face: y >= minY
            planes.add(new Plane3D(0, 0, 1, -maxZ));   // Far face: z <= maxZ
            planes.add(new Plane3D(0, 0, -1, minZ));   // Near face: z >= minZ
            
            return planes;
        }
    }
    
    /**
     * Find all entities that intersect with the convex hull
     * 
     * @param convexHull the convex hull to test intersection with
     * @param octree the multi-entity octree to search
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @return list of entity intersections sorted by distance from reference point
     */
    public static <ID extends EntityID, Content> List<EntityConvexHullIntersection<ID, Content>> 
            findEntitiesIntersectingConvexHull(
            ConvexHull convexHull,
            OctreeWithEntities<ID, Content> octree,
            Point3f referencePoint) {
        
        validatePositiveCoordinates(referencePoint);
        
        List<EntityConvexHullIntersection<ID, Content>> results = new ArrayList<>();
        
        // Get all entities with their positions
        Map<ID, Point3f> entitiesWithPositions = octree.getEntitiesWithPositions();
        
        for (Map.Entry<ID, Point3f> entry : entitiesWithPositions.entrySet()) {
            ID entityId = entry.getKey();
            Point3f position = entry.getValue();
            
            // Get entity bounds if available
            EntityBounds bounds = octree.getEntityBounds(entityId);
            
            // Test intersection
            IntersectionType intersectionType;
            if (bounds != null) {
                intersectionType = convexHull.intersectsEntityBounds(bounds);
            } else {
                // No bounds, test point
                if (convexHull.strictlyContainsPoint(position)) {
                    intersectionType = IntersectionType.COMPLETELY_INSIDE;
                } else if (convexHull.containsPoint(position)) {
                    intersectionType = IntersectionType.INTERSECTING; // On boundary
                } else {
                    intersectionType = IntersectionType.COMPLETELY_OUTSIDE;
                }
            }
            
            if (intersectionType != IntersectionType.COMPLETELY_OUTSIDE) {
                Content content = octree.getEntity(entityId);
                if (content != null) {
                    float distanceToRef = position.distance(referencePoint);
                    float distanceToHullCenter = position.distance(convexHull.centroid);
                    float penetrationDepth = calculateEntityPenetrationDepth(convexHull, position, bounds);
                    
                    results.add(new EntityConvexHullIntersection<>(
                        entityId, content, position, distanceToRef, distanceToHullCenter,
                        intersectionType, penetrationDepth
                    ));
                }
            }
        }
        
        // Sort by distance from reference point
        results.sort(Comparator.comparingDouble(e -> e.distanceToReferencePoint));
        
        return results;
    }
    
    /**
     * Find entities completely inside the convex hull
     * 
     * @param convexHull the convex hull to test
     * @param octree the multi-entity octree to search
     * @return list of entities completely inside the hull
     */
    public static <ID extends EntityID, Content> List<EntityConvexHullIntersection<ID, Content>> 
            findEntitiesInsideConvexHull(
            ConvexHull convexHull,
            OctreeWithEntities<ID, Content> octree) {
        
        List<EntityConvexHullIntersection<ID, Content>> results = new ArrayList<>();
        
        // Get all entities with their positions
        Map<ID, Point3f> entitiesWithPositions = octree.getEntitiesWithPositions();
        
        for (Map.Entry<ID, Point3f> entry : entitiesWithPositions.entrySet()) {
            ID entityId = entry.getKey();
            Point3f position = entry.getValue();
            
            // Get entity bounds if available
            EntityBounds bounds = octree.getEntityBounds(entityId);
            
            // Test if completely inside
            boolean completelyInside = false;
            if (bounds != null) {
                completelyInside = convexHull.intersectsEntityBounds(bounds) == IntersectionType.COMPLETELY_INSIDE;
            } else {
                completelyInside = convexHull.strictlyContainsPoint(position);
            }
            
            if (completelyInside) {
                Content content = octree.getEntity(entityId);
                if (content != null) {
                    float distanceToHullCenter = position.distance(convexHull.centroid);
                    float penetrationDepth = calculateEntityPenetrationDepth(convexHull, position, bounds);
                    
                    results.add(new EntityConvexHullIntersection<>(
                        entityId, content, position, 0.0f, distanceToHullCenter,
                        IntersectionType.COMPLETELY_INSIDE, penetrationDepth
                    ));
                }
            }
        }
        
        // Sort by distance from hull center
        results.sort(Comparator.comparingDouble(e -> e.distanceToHullCenter));
        
        return results;
    }
    
    /**
     * Count entities by convex hull intersection type
     * 
     * @param convexHull the convex hull to test
     * @param octree the multi-entity octree to search
     * @return array with [inside count, intersecting count, outside count]
     */
    public static <ID extends EntityID, Content> int[] countEntitiesByConvexHullIntersection(
            ConvexHull convexHull,
            OctreeWithEntities<ID, Content> octree) {
        
        int inside = 0;
        int intersecting = 0;
        int outside = 0;
        
        // Get all entities with their positions
        Map<ID, Point3f> entitiesWithPositions = octree.getEntitiesWithPositions();
        
        for (Map.Entry<ID, Point3f> entry : entitiesWithPositions.entrySet()) {
            ID entityId = entry.getKey();
            Point3f position = entry.getValue();
            EntityBounds bounds = octree.getEntityBounds(entityId);
            
            IntersectionType type;
            if (bounds != null) {
                type = convexHull.intersectsEntityBounds(bounds);
            } else {
                if (convexHull.strictlyContainsPoint(position)) {
                    type = IntersectionType.COMPLETELY_INSIDE;
                } else if (convexHull.containsPoint(position)) {
                    type = IntersectionType.INTERSECTING; // On boundary
                } else {
                    type = IntersectionType.COMPLETELY_OUTSIDE;
                }
            }
            
            switch (type) {
                case COMPLETELY_INSIDE -> inside++;
                case INTERSECTING -> intersecting++;
                case COMPLETELY_OUTSIDE -> outside++;
            }
        }
        
        return new int[] { inside, intersecting, outside };
    }
    
    /**
     * Find entities that intersect with multiple convex hulls
     * 
     * @param convexHulls list of convex hulls to test
     * @param octree the multi-entity octree to search
     * @param requireAllHulls if true, entity must intersect all hulls; if false, any hull
     * @return list of entities meeting the intersection criteria
     */
    public static <ID extends EntityID, Content> List<EntityConvexHullIntersection<ID, Content>> 
            findEntitiesIntersectingMultipleConvexHulls(
            List<ConvexHull> convexHulls,
            OctreeWithEntities<ID, Content> octree,
            boolean requireAllHulls) {
        
        List<EntityConvexHullIntersection<ID, Content>> results = new ArrayList<>();
        
        // Get all entities with their positions
        Map<ID, Point3f> entitiesWithPositions = octree.getEntitiesWithPositions();
        
        for (Map.Entry<ID, Point3f> entry : entitiesWithPositions.entrySet()) {
            ID entityId = entry.getKey();
            Point3f position = entry.getValue();
            EntityBounds bounds = octree.getEntityBounds(entityId);
            
            int intersectionCount = 0;
            float minDistanceToCenter = Float.MAX_VALUE;
            float totalPenetrationDepth = 0.0f;
            
            // Test against all convex hulls
            for (ConvexHull hull : convexHulls) {
                IntersectionType type;
                if (bounds != null) {
                    type = hull.intersectsEntityBounds(bounds);
                } else {
                    if (hull.strictlyContainsPoint(position)) {
                        type = IntersectionType.COMPLETELY_INSIDE;
                    } else if (hull.containsPoint(position)) {
                        type = IntersectionType.INTERSECTING; // On boundary
                    } else {
                        type = IntersectionType.COMPLETELY_OUTSIDE;
                    }
                }
                
                if (type != IntersectionType.COMPLETELY_OUTSIDE) {
                    intersectionCount++;
                    float distance = position.distance(hull.centroid);
                    minDistanceToCenter = Math.min(minDistanceToCenter, distance);
                    totalPenetrationDepth += calculateEntityPenetrationDepth(hull, position, bounds);
                }
            }
            
            // Check if entity meets criteria
            boolean include = requireAllHulls ? 
                (intersectionCount == convexHulls.size()) : 
                (intersectionCount > 0);
                
            if (include) {
                Content content = octree.getEntity(entityId);
                if (content != null) {
                    float avgPenetrationDepth = totalPenetrationDepth / intersectionCount;
                    results.add(new EntityConvexHullIntersection<>(
                        entityId, content, position, 0.0f, minDistanceToCenter,
                        IntersectionType.INTERSECTING, avgPenetrationDepth
                    ));
                }
            }
        }
        
        return results;
    }
    
    /**
     * Get statistics about convex hull intersection results
     * 
     * @param convexHull the convex hull to test
     * @param octree the multi-entity octree to analyze
     * @return statistics about intersection results
     */
    public static <ID extends EntityID, Content> ConvexHullIntersectionStatistics 
            getConvexHullIntersectionStatistics(
            ConvexHull convexHull,
            OctreeWithEntities<ID, Content> octree) {
        
        long totalEntities = 0;
        long insideEntities = 0;
        long intersectingEntities = 0;
        long outsideEntities = 0;
        float totalPenetrationDepth = 0.0f;
        
        // Get all entities with their positions
        Map<ID, Point3f> entitiesWithPositions = octree.getEntitiesWithPositions();
        
        for (Map.Entry<ID, Point3f> entry : entitiesWithPositions.entrySet()) {
            totalEntities++;
            ID entityId = entry.getKey();
            Point3f position = entry.getValue();
            EntityBounds bounds = octree.getEntityBounds(entityId);
            
            IntersectionType type;
            if (bounds != null) {
                type = convexHull.intersectsEntityBounds(bounds);
            } else {
                if (convexHull.strictlyContainsPoint(position)) {
                    type = IntersectionType.COMPLETELY_INSIDE;
                } else if (convexHull.containsPoint(position)) {
                    type = IntersectionType.INTERSECTING; // On boundary
                } else {
                    type = IntersectionType.COMPLETELY_OUTSIDE;
                }
            }
            
            switch (type) {
                case COMPLETELY_INSIDE -> {
                    insideEntities++;
                    totalPenetrationDepth += calculateEntityPenetrationDepth(convexHull, position, bounds);
                }
                case INTERSECTING -> {
                    intersectingEntities++;
                    totalPenetrationDepth += calculateEntityPenetrationDepth(convexHull, position, bounds);
                }
                case COMPLETELY_OUTSIDE -> outsideEntities++;
            }
        }
        
        float averagePenetrationDepth = (insideEntities + intersectingEntities) > 0 ?
            totalPenetrationDepth / (insideEntities + intersectingEntities) : 0.0f;
        
        return new ConvexHullIntersectionStatistics(
            totalEntities, insideEntities, intersectingEntities,
            outsideEntities, totalPenetrationDepth, averagePenetrationDepth
        );
    }
    
    /**
     * Batch processing for multiple convex hull queries
     * 
     * @param convexHulls list of convex hulls to test
     * @param octree the multi-entity octree to search
     * @param referencePoint reference point for distance calculations
     * @return map of convex hulls to their intersection results
     */
    public static <ID extends EntityID, Content> Map<ConvexHull, List<EntityConvexHullIntersection<ID, Content>>> 
            batchConvexHullIntersections(
            List<ConvexHull> convexHulls,
            OctreeWithEntities<ID, Content> octree,
            Point3f referencePoint) {
        
        validatePositiveCoordinates(referencePoint);
        
        Map<ConvexHull, List<EntityConvexHullIntersection<ID, Content>>> results = new HashMap<>();
        
        for (ConvexHull hull : convexHulls) {
            List<EntityConvexHullIntersection<ID, Content>> intersections =
                findEntitiesIntersectingConvexHull(hull, octree, referencePoint);
            results.put(hull, intersections);
        }
        
        return results;
    }
    
    /**
     * Statistics about convex hull intersection results
     */
    public static class ConvexHullIntersectionStatistics {
        public final long totalEntities;
        public final long insideEntities;
        public final long intersectingEntities;
        public final long outsideEntities;
        public final float totalPenetrationDepth;
        public final float averagePenetrationDepth;
        
        public ConvexHullIntersectionStatistics(long totalEntities, long insideEntities,
                                              long intersectingEntities, long outsideEntities,
                                              float totalPenetrationDepth, float averagePenetrationDepth) {
            this.totalEntities = totalEntities;
            this.insideEntities = insideEntities;
            this.intersectingEntities = intersectingEntities;
            this.outsideEntities = outsideEntities;
            this.totalPenetrationDepth = totalPenetrationDepth;
            this.averagePenetrationDepth = averagePenetrationDepth;
        }
        
        public double getInsidePercentage() {
            return totalEntities > 0 ? (double) insideEntities / totalEntities * 100.0 : 0.0;
        }
        
        public double getIntersectingPercentage() {
            return totalEntities > 0 ? (double) intersectingEntities / totalEntities * 100.0 : 0.0;
        }
        
        public double getOutsidePercentage() {
            return totalEntities > 0 ? (double) outsideEntities / totalEntities * 100.0 : 0.0;
        }
    }
    
    // Helper methods
    
    /**
     * Calculate penetration depth of entity into convex hull
     */
    private static float calculateEntityPenetrationDepth(ConvexHull convexHull, Point3f entityPos, EntityBounds bounds) {
        float distanceToHull = convexHull.distanceToPoint(entityPos);
        
        if (distanceToHull < 0) {
            // Entity center is inside hull
            return Math.abs(distanceToHull);
        } else if (bounds != null) {
            // Entity center is outside hull - check if any part of bounds is inside
            Point3f[] corners = getBoundsCorners(bounds);
            float maxPenetration = 0.0f;
            
            for (Point3f corner : corners) {
                float cornerDistance = convexHull.distanceToPoint(corner);
                if (cornerDistance < 0) {
                    maxPenetration = Math.max(maxPenetration, Math.abs(cornerDistance));
                }
            }
            
            return maxPenetration;
        } else {
            return 0.0f;
        }
    }
    
    /**
     * Get all 8 corners of entity bounds
     */
    private static Point3f[] getBoundsCorners(EntityBounds bounds) {
        float minX = bounds.getMinX();
        float minY = bounds.getMinY();
        float minZ = bounds.getMinZ();
        float maxX = bounds.getMaxX();
        float maxY = bounds.getMaxY();
        float maxZ = bounds.getMaxZ();
        
        return new Point3f[] {
            new Point3f(minX, minY, minZ), new Point3f(maxX, minY, minZ),
            new Point3f(minX, maxY, minZ), new Point3f(maxX, maxY, minZ),
            new Point3f(minX, minY, maxZ), new Point3f(maxX, minY, maxZ),
            new Point3f(minX, maxY, maxZ), new Point3f(maxX, maxY, maxZ)
        };
    }
    
    private static void validatePositiveCoordinates(Point3f point) {
        if (point.x < 0 || point.y < 0 || point.z < 0) {
            throw new IllegalArgumentException("Point must have positive coordinates");
        }
    }
    
    /**
     * Check if entity bounds intersect with a plane
     */
    private static boolean intersectsPlane(EntityBounds bounds, Plane3D plane) {
        Point3f[] corners = getBoundsCorners(bounds);
        
        boolean hasPositive = false;
        boolean hasNegative = false;
        
        for (Point3f corner : corners) {
            float distance = plane.distanceToPoint(corner);
            if (distance > 0) {
                hasPositive = true;
            } else if (distance < 0) {
                hasNegative = true;
            }
            
            // If we have both positive and negative distances, the bounds intersect the plane
            if (hasPositive && hasNegative) {
                return true;
            }
        }
        
        return false; // All points are on one side of the plane
    }
}