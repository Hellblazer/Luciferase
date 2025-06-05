package com.hellblazer.luciferase.lucien;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Plane intersection search implementation for Tetree
 * Finds tetrahedra that intersect with a 3D plane, with results ordered by distance from a reference point
 * All operations are constrained to positive coordinates only, as required by tetrahedral SFC
 * 
 * @author hal.hildebrand
 */
public class TetPlaneIntersectionSearch extends TetrahedralSearchBase {

    /**
     * Tetrahedron-plane intersection result with distance information
     */
    public static class TetPlaneIntersection<Content> {
        public final long index;
        public final Content content;
        public final Point3f tetrahedronCenter;
        public final float distanceToReferencePoint;
        public final float signedDistanceToPlane;

        public TetPlaneIntersection(long index, Content content, Point3f tetrahedronCenter, 
                                   float distanceToReferencePoint, float signedDistanceToPlane) {
            this.index = index;
            this.content = content;
            this.tetrahedronCenter = tetrahedronCenter;
            this.distanceToReferencePoint = distanceToReferencePoint;
            this.signedDistanceToPlane = signedDistanceToPlane;
        }
    }

    /**
     * Find all tetrahedra that intersect with the plane, ordered by distance from reference point
     * 
     * @param plane the plane to test intersection with
     * @param tetree the tetree to search in
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @return list of intersections sorted by distance from reference point (closest first)
     * @throws IllegalArgumentException if reference point has negative coordinates
     */
    public static <Content> List<TetPlaneIntersection<Content>> planeIntersectedAll(
            Plane3D plane, Tetree<Content> tetree, Point3f referencePoint) {
        return planeIntersectedAll(plane, tetree, referencePoint, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
    }

    /**
     * Find all tetrahedra that intersect with the plane, ordered by distance from reference point
     * 
     * @param plane the plane to test intersection with
     * @param tetree the tetree to search in
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @param strategy how to handle multiple simplicies in the same spatial region
     * @return list of intersections sorted by distance from reference point (closest first)
     * @throws IllegalArgumentException if reference point has negative coordinates
     */
    public static <Content> List<TetPlaneIntersection<Content>> planeIntersectedAll(
            Plane3D plane, Tetree<Content> tetree, Point3f referencePoint, SimplexAggregationStrategy strategy) {
        
        validatePositiveCoordinates(referencePoint);
        
        // Create a spatial volume that encompasses the plane intersection region
        // We need to find tetrahedra that could potentially intersect the plane
        var searchVolume = createPlaneSearchVolume(plane, tetree, referencePoint);
        
        // Get simplicies bounded by the search volume
        var spatialResults = tetree.boundedBy(searchVolume);
        
        // Apply simplex aggregation strategy
        var aggregatedResults = aggregateSimplicies(spatialResults, strategy);
        
        List<TetPlaneIntersection<Content>> intersections = new ArrayList<>();
        
        for (var simplex : aggregatedResults) {
            long tetIndex = simplex.index();
            Content content = simplex.cell();
            
            // Test if this tetrahedron intersects the plane
            if (tetrahedronIntersectsPlane(tetIndex, plane)) {
                Point3f tetCenter = tetrahedronCenter(tetIndex);
                float distance = calculateDistance(referencePoint, tetCenter);
                float planeDistance = plane.distanceToPoint(tetCenter);
                
                TetPlaneIntersection<Content> intersection = new TetPlaneIntersection<>(
                    tetIndex, content, tetCenter, distance, planeDistance);
                intersections.add(intersection);
            }
        }

        // Sort by distance from reference point
        intersections.sort(Comparator.comparing(pi -> pi.distanceToReferencePoint));
        
        return intersections;
    }

    /**
     * Find the first (closest to reference point) tetrahedron that intersects with the plane
     * 
     * @param plane the plane to test intersection with
     * @param tetree the tetree to search in
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @return the closest intersection, or null if no intersection
     * @throws IllegalArgumentException if reference point has negative coordinates
     */
    public static <Content> TetPlaneIntersection<Content> planeIntersectedFirst(
            Plane3D plane, Tetree<Content> tetree, Point3f referencePoint) {
        
        List<TetPlaneIntersection<Content>> intersections = planeIntersectedAll(plane, tetree, referencePoint);
        return intersections.isEmpty() ? null : intersections.get(0);
    }

    /**
     * Find all tetrahedra that intersect with the plane, ordered by distance along plane normal
     * This provides a different ordering than distance from reference point
     * 
     * @param plane the plane to test intersection with
     * @param tetree the tetree to search in
     * @return list of intersections sorted by signed distance from plane (closest to plane first)
     */
    public static <Content> List<TetPlaneIntersection<Content>> planeIntersectedAllByPlaneDistance(
            Plane3D plane, Tetree<Content> tetree) {
        
        // Create a spatial volume that encompasses the plane intersection region
        var searchVolume = createPlaneSearchVolume(plane, tetree, null);
        
        // Get simplicies bounded by the search volume
        var spatialResults = tetree.boundedBy(searchVolume);
        
        // Apply default aggregation strategy
        var aggregatedResults = aggregateSimplicies(spatialResults, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
        
        List<TetPlaneIntersection<Content>> intersections = new ArrayList<>();
        
        for (var simplex : aggregatedResults) {
            long tetIndex = simplex.index();
            Content content = simplex.cell();
            
            // Test if this tetrahedron intersects the plane
            if (tetrahedronIntersectsPlane(tetIndex, plane)) {
                Point3f tetCenter = tetrahedronCenter(tetIndex);
                float planeDistance = Math.abs(plane.distanceToPoint(tetCenter));
                
                TetPlaneIntersection<Content> intersection = new TetPlaneIntersection<>(
                    tetIndex, content, tetCenter, planeDistance, plane.distanceToPoint(tetCenter));
                intersections.add(intersection);
            }
        }

        // Sort by absolute distance from plane
        intersections.sort(Comparator.comparing(pi -> pi.distanceToReferencePoint));
        
        return intersections;
    }

    /**
     * Find tetrahedra that are on the positive side of the plane (in direction of normal)
     * 
     * @param plane the plane to test against
     * @param tetree the tetree to search in
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @return list of intersections on positive side, sorted by distance from reference point
     * @throws IllegalArgumentException if reference point has negative coordinates
     */
    public static <Content> List<TetPlaneIntersection<Content>> tetrahedraOnPositiveSide(
            Plane3D plane, Tetree<Content> tetree, Point3f referencePoint) {
        
        validatePositiveCoordinates(referencePoint);
        
        // Create a spatial volume that encompasses the positive half-space
        var searchVolume = createPlaneSearchVolume(plane, tetree, referencePoint);
        
        // Get simplicies bounded by the search volume
        var spatialResults = tetree.boundedBy(searchVolume);
        
        // Apply default aggregation strategy
        var aggregatedResults = aggregateSimplicies(spatialResults, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
        
        List<TetPlaneIntersection<Content>> results = new ArrayList<>();
        
        for (var simplex : aggregatedResults) {
            long tetIndex = simplex.index();
            Content content = simplex.cell();
            Point3f tetCenter = tetrahedronCenter(tetIndex);
            
            // Check if tetrahedron center is on positive side of plane
            if (plane.distanceToPoint(tetCenter) > 0) {
                float distance = calculateDistance(referencePoint, tetCenter);
                
                TetPlaneIntersection<Content> result = new TetPlaneIntersection<>(
                    tetIndex, content, tetCenter, distance, plane.distanceToPoint(tetCenter));
                results.add(result);
            }
        }

        // Sort by distance from reference point
        results.sort(Comparator.comparing(pi -> pi.distanceToReferencePoint));
        
        return results;
    }

    /**
     * Find tetrahedra that are on the negative side of the plane (opposite to normal direction)
     * 
     * @param plane the plane to test against
     * @param tetree the tetree to search in
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @return list of intersections on negative side, sorted by distance from reference point
     * @throws IllegalArgumentException if reference point has negative coordinates
     */
    public static <Content> List<TetPlaneIntersection<Content>> tetrahedraOnNegativeSide(
            Plane3D plane, Tetree<Content> tetree, Point3f referencePoint) {
        
        validatePositiveCoordinates(referencePoint);
        
        // Create a spatial volume that encompasses the negative half-space
        var searchVolume = createPlaneSearchVolume(plane, tetree, referencePoint);
        
        // Get simplicies bounded by the search volume
        var spatialResults = tetree.boundedBy(searchVolume);
        
        // Apply default aggregation strategy
        var aggregatedResults = aggregateSimplicies(spatialResults, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
        
        List<TetPlaneIntersection<Content>> results = new ArrayList<>();
        
        for (var simplex : aggregatedResults) {
            long tetIndex = simplex.index();
            Content content = simplex.cell();
            Point3f tetCenter = tetrahedronCenter(tetIndex);
            
            // Check if tetrahedron center is on negative side of plane
            if (plane.distanceToPoint(tetCenter) < 0) {
                float distance = calculateDistance(referencePoint, tetCenter);
                
                TetPlaneIntersection<Content> result = new TetPlaneIntersection<>(
                    tetIndex, content, tetCenter, distance, plane.distanceToPoint(tetCenter));
                results.add(result);
            }
        }

        // Sort by distance from reference point
        results.sort(Comparator.comparing(pi -> pi.distanceToReferencePoint));
        
        return results;
    }

    /**
     * Count the number of tetrahedra that intersect with the plane
     * This is more efficient than getting all intersections when only count is needed
     * 
     * @param plane the plane to test intersection with
     * @param tetree the tetree to search in
     * @return number of tetrahedra intersecting the plane
     */
    public static <Content> long countPlaneIntersections(Plane3D plane, Tetree<Content> tetree) {
        var searchVolume = createPlaneSearchVolume(plane, tetree, null);
        var spatialResults = tetree.boundedBy(searchVolume);
        
        return spatialResults
            .filter(simplex -> tetrahedronIntersectsPlane(simplex.index(), plane))
            .count();
    }

    /**
     * Test if any tetrahedron in the tetree intersects with the plane
     * This is more efficient than getting all intersections when only existence check is needed
     * 
     * @param plane the plane to test intersection with
     * @param tetree the tetree to search in
     * @return true if any tetrahedron intersects the plane
     */
    public static <Content> boolean hasAnyIntersection(Plane3D plane, Tetree<Content> tetree) {
        var searchVolume = createPlaneSearchVolume(plane, tetree, null);
        var spatialResults = tetree.boundedBy(searchVolume);
        
        return spatialResults
            .anyMatch(simplex -> tetrahedronIntersectsPlane(simplex.index(), plane));
    }

    /**
     * Test if a tetrahedron intersects with a plane
     * 
     * @param tetIndex the tetrahedral SFC index
     * @param plane the plane to test intersection with
     * @return true if tetrahedron intersects the plane
     */
    private static boolean tetrahedronIntersectsPlane(long tetIndex, Plane3D plane) {
        var tet = Tet.tetrahedron(tetIndex);
        var vertices = tet.coordinates();
        
        // Test each vertex of the tetrahedron against the plane
        float[] distances = new float[4];
        for (int i = 0; i < 4; i++) {
            Point3f vertex = new Point3f(vertices[i].x, vertices[i].y, vertices[i].z);
            distances[i] = plane.distanceToPoint(vertex);
        }
        
        // If all vertices are on the same side of the plane, no intersection
        boolean allPositive = true;
        boolean allNegative = true;
        
        for (float distance : distances) {
            if (distance <= 0) allPositive = false;
            if (distance >= 0) allNegative = false;
        }
        
        // Intersection occurs if vertices are on different sides of the plane
        return !(allPositive || allNegative);
    }

    /**
     * Create a spatial volume that encompasses the potential plane intersection region
     * 
     * @param plane the plane to create search volume for
     * @param tetree the tetree being searched
     * @param referencePoint optional reference point to center search around
     * @return spatial volume for plane intersection search
     */
    private static <Content> Spatial.aabb createPlaneSearchVolume(Plane3D plane, Tetree<Content> tetree, Point3f referencePoint) {
        // For now, create a large bounding box that covers the tetree's expected extent
        // This is a simplified approach - could be optimized to create tighter bounds based on plane geometry
        
        float searchExtent = 1000000.0f; // Large extent to ensure we capture plane intersections
        
        if (referencePoint != null) {
            // Center search around reference point
            float minX = Math.max(0, referencePoint.x - searchExtent);
            float minY = Math.max(0, referencePoint.y - searchExtent);
            float minZ = Math.max(0, referencePoint.z - searchExtent);
            float maxX = referencePoint.x + searchExtent;
            float maxY = referencePoint.y + searchExtent;
            float maxZ = referencePoint.z + searchExtent;
            
            return new Spatial.aabb(minX, minY, minZ, maxX, maxY, maxZ);
        } else {
            // Use a default large search volume
            return new Spatial.aabb(0, 0, 0, searchExtent, searchExtent, searchExtent);
        }
    }

    /**
     * Calculate Euclidean distance between two points
     */
    private static float calculateDistance(Point3f p1, Point3f p2) {
        float dx = p1.x - p2.x;
        float dy = p1.y - p2.y;
        float dz = p1.z - p2.z;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}