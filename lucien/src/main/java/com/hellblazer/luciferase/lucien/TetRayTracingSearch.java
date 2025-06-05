package com.hellblazer.luciferase.lucien;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import javax.vecmath.Vector3f;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Ray tracing intersection search implementation for Tetree
 * Finds tetrahedra that intersect with a 3D ray, ordered by distance from ray origin
 * Uses Möller-Trumbore algorithm for ray-triangle intersection on tetrahedral faces
 * 
 * @author hal.hildebrand
 */
public class TetRayTracingSearch extends TetrahedralSearchBase {

    /**
     * Ray-tetrahedron intersection result with distance information
     */
    public static class TetRayIntersection<Content> {
        public final long index;
        public final Content content;
        public final Point3f tetrahedronCenter;
        public final float distance;
        public final Point3f intersectionPoint;
        public final int intersectedFace; // Which face of tetrahedron was hit (0-3)

        public TetRayIntersection(long index, Content content, Point3f tetrahedronCenter, 
                                 float distance, Point3f intersectionPoint, int intersectedFace) {
            this.index = index;
            this.content = content;
            this.tetrahedronCenter = tetrahedronCenter;
            this.distance = distance;
            this.intersectionPoint = intersectionPoint;
            this.intersectedFace = intersectedFace;
        }
    }

    /**
     * Find all tetrahedra that intersect with the ray, ordered by distance from ray origin
     * 
     * @param ray the ray to trace (must have positive origin coordinates)
     * @param tetree the tetree to search in
     * @return list of intersections sorted by distance (closest first)
     */
    public static <Content> List<TetRayIntersection<Content>> rayIntersectedAll(
            Ray3D ray, Tetree<Content> tetree) {
        return rayIntersectedAll(ray, tetree, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
    }

    /**
     * Find all tetrahedra that intersect with the ray, ordered by distance from ray origin
     * 
     * @param ray the ray to trace (must have positive origin coordinates)
     * @param tetree the tetree to search in
     * @param strategy how to handle multiple simplicies in the same spatial region
     * @return list of intersections sorted by distance (closest first)
     */
    public static <Content> List<TetRayIntersection<Content>> rayIntersectedAll(
            Ray3D ray, Tetree<Content> tetree, SimplexAggregationStrategy strategy) {
        
        validatePositiveCoordinates(ray.origin());
        
        // Create a spatial volume that encompasses the ray's potential intersection region
        var searchVolume = createRaySearchVolume(ray, tetree);
        
        // Get simplicies bounded by the search volume
        var spatialResults = tetree.boundedBy(searchVolume);
        
        // Apply simplex aggregation strategy
        var aggregatedResults = aggregateSimplicies(spatialResults, strategy);
        
        List<TetRayIntersection<Content>> intersections = new ArrayList<>();
        
        for (var simplex : aggregatedResults) {
            long tetIndex = simplex.index();
            Content content = simplex.cell();
            
            // Test if this tetrahedron intersects the ray
            RayTetrahedronIntersection intersection = rayTetrahedronIntersection(ray, tetIndex);
            if (intersection.intersects) {
                Point3f tetCenter = tetrahedronCenter(tetIndex);
                
                TetRayIntersection<Content> rayIntersection = new TetRayIntersection<>(
                    tetIndex, content, tetCenter, intersection.distance, 
                    intersection.intersectionPoint, intersection.intersectedFace);
                intersections.add(rayIntersection);
            }
        }

        // Sort by distance from ray origin
        intersections.sort(Comparator.comparing(ri -> ri.distance));
        
        return intersections;
    }

    /**
     * Find the first (closest) tetrahedron that intersects with the ray
     * 
     * @param ray the ray to trace
     * @param tetree the tetree to search in
     * @return the closest intersection, or null if no intersection
     */
    public static <Content> TetRayIntersection<Content> rayIntersectedFirst(
            Ray3D ray, Tetree<Content> tetree) {
        
        List<TetRayIntersection<Content>> intersections = rayIntersectedAll(ray, tetree);
        return intersections.isEmpty() ? null : intersections.get(0);
    }

    /**
     * Count the number of tetrahedra that intersect with the ray
     * This is more efficient than getting all intersections when only count is needed
     * 
     * @param ray the ray to trace
     * @param tetree the tetree to search in
     * @return number of tetrahedra intersecting the ray
     */
    public static <Content> long countRayIntersections(Ray3D ray, Tetree<Content> tetree) {
        validatePositiveCoordinates(ray.origin());
        
        var searchVolume = createRaySearchVolume(ray, tetree);
        var spatialResults = tetree.boundedBy(searchVolume);
        
        return spatialResults
            .filter(simplex -> rayTetrahedronIntersection(ray, simplex.index()).intersects)
            .count();
    }

    /**
     * Test if any tetrahedron in the tetree intersects with the ray
     * This is more efficient than getting all intersections when only existence check is needed
     * 
     * @param ray the ray to trace
     * @param tetree the tetree to search in
     * @return true if any tetrahedron intersects the ray
     */
    public static <Content> boolean hasAnyIntersection(Ray3D ray, Tetree<Content> tetree) {
        validatePositiveCoordinates(ray.origin());
        
        var searchVolume = createRaySearchVolume(ray, tetree);
        var spatialResults = tetree.boundedBy(searchVolume);
        
        return spatialResults
            .anyMatch(simplex -> rayTetrahedronIntersection(ray, simplex.index()).intersects);
    }

    /**
     * Ray-tetrahedron intersection result
     */
    private static class RayTetrahedronIntersection {
        public final boolean intersects;
        public final float distance;
        public final Point3f intersectionPoint;
        public final int intersectedFace; // Which face was hit (0-3)

        public RayTetrahedronIntersection(boolean intersects, float distance, 
                                        Point3f intersectionPoint, int intersectedFace) {
            this.intersects = intersects;
            this.distance = distance;
            this.intersectionPoint = intersectionPoint;
            this.intersectedFace = intersectedFace;
        }
    }

    /**
     * Test ray intersection with tetrahedron using Möller-Trumbore algorithm
     * Tests ray against all 4 triangular faces of the tetrahedron
     * 
     * @param ray the ray to test
     * @param tetIndex the tetrahedral SFC index
     * @return intersection result with closest intersection
     */
    private static RayTetrahedronIntersection rayTetrahedronIntersection(Ray3D ray, long tetIndex) {
        var tet = Tet.tetrahedron(tetIndex);
        var vertices = tet.coordinates();
        
        // Convert Point3i vertices to Point3f for calculations
        Point3f[] tetVertices = new Point3f[4];
        for (int i = 0; i < 4; i++) {
            tetVertices[i] = new Point3f(vertices[i].x, vertices[i].y, vertices[i].z);
        }
        
        float closestDistance = Float.MAX_VALUE;
        Point3f closestIntersection = null;
        int closestFace = -1;
        
        // Test ray against each of the 4 triangular faces
        for (int face = 0; face < 4; face++) {
            Point3f[] faceVertices = getFaceVertices(tetVertices, face);
            
            RayTriangleIntersection intersection = rayTriangleIntersection(
                ray, faceVertices[0], faceVertices[1], faceVertices[2]);
            
            if (intersection.intersects && intersection.distance >= 0 && 
                intersection.distance < closestDistance) {
                closestDistance = intersection.distance;
                closestIntersection = intersection.intersectionPoint;
                closestFace = face;
            }
        }
        
        if (closestFace >= 0) {
            return new RayTetrahedronIntersection(true, closestDistance, closestIntersection, closestFace);
        } else {
            return new RayTetrahedronIntersection(false, Float.MAX_VALUE, null, -1);
        }
    }

    /**
     * Ray-triangle intersection result using Möller-Trumbore algorithm
     */
    private static class RayTriangleIntersection {
        public final boolean intersects;
        public final float distance;
        public final Point3f intersectionPoint;
        public final float u, v; // Barycentric coordinates

        public RayTriangleIntersection(boolean intersects, float distance, 
                                     Point3f intersectionPoint, float u, float v) {
            this.intersects = intersects;
            this.distance = distance;
            this.intersectionPoint = intersectionPoint;
            this.u = u;
            this.v = v;
        }
    }

    /**
     * Ray-triangle intersection using Möller-Trumbore algorithm
     * Based on "Fast, Minimum Storage Ray-Triangle Intersection" by Möller & Trumbore
     * 
     * @param ray the ray to test
     * @param v0 first vertex of triangle
     * @param v1 second vertex of triangle  
     * @param v2 third vertex of triangle
     * @return intersection result
     */
    private static RayTriangleIntersection rayTriangleIntersection(Ray3D ray, Point3f v0, Point3f v1, Point3f v2) {
        Vector3f edge1 = new Vector3f();
        edge1.sub(v1, v0);
        
        Vector3f edge2 = new Vector3f();
        edge2.sub(v2, v0);
        
        Vector3f h = new Vector3f();
        h.cross(ray.direction(), edge2);
        
        float a = edge1.dot(h);
        
        // Ray is parallel to triangle plane
        if (Math.abs(a) < GEOMETRIC_TOLERANCE) {
            return new RayTriangleIntersection(false, Float.MAX_VALUE, null, 0, 0);
        }
        
        float f = 1.0f / a;
        
        Vector3f s = new Vector3f();
        s.sub(ray.origin(), v0);
        
        float u = f * s.dot(h);
        if (u < 0.0f || u > 1.0f) {
            return new RayTriangleIntersection(false, Float.MAX_VALUE, null, 0, 0);
        }
        
        Vector3f q = new Vector3f();
        q.cross(s, edge1);
        
        float v = f * ray.direction().dot(q);
        if (v < 0.0f || u + v > 1.0f) {
            return new RayTriangleIntersection(false, Float.MAX_VALUE, null, 0, 0);
        }
        
        // Compute t to find out where intersection point is on the line
        float t = f * edge2.dot(q);
        
        if (t > GEOMETRIC_TOLERANCE) { // Ray intersection
            Point3f intersectionPoint = ray.getPointAt(t);
            return new RayTriangleIntersection(true, t, intersectionPoint, u, v);
        } else {
            // Line intersection but not ray intersection
            return new RayTriangleIntersection(false, Float.MAX_VALUE, null, 0, 0);
        }
    }

    /**
     * Get the vertices of a specific face of the tetrahedron
     * 
     * @param vertices the 4 vertices of the tetrahedron
     * @param faceIndex which face (0-3)
     * @return array of 3 vertices forming the triangular face
     */
    private static Point3f[] getFaceVertices(Point3f[] vertices, int faceIndex) {
        return switch (faceIndex) {
            case 0 -> new Point3f[]{vertices[0], vertices[1], vertices[2]}; // Face opposite vertex 3
            case 1 -> new Point3f[]{vertices[0], vertices[1], vertices[3]}; // Face opposite vertex 2
            case 2 -> new Point3f[]{vertices[0], vertices[2], vertices[3]}; // Face opposite vertex 1
            case 3 -> new Point3f[]{vertices[1], vertices[2], vertices[3]}; // Face opposite vertex 0
            default -> throw new IllegalArgumentException("Face index must be 0-3");
        };
    }

    /**
     * Create a spatial volume that encompasses the ray's potential intersection region
     * 
     * @param ray the ray to create search volume for
     * @param tetree the tetree being searched
     * @return spatial volume for ray intersection search
     */
    private static <Content> Spatial.aabb createRaySearchVolume(Ray3D ray, Tetree<Content> tetree) {
        // For now, create a large bounding box that encompasses the ray's path
        // This is a simplified approach - could be optimized to create tighter bounds based on ray geometry
        
        float searchExtent = 1000000.0f; // Large extent to ensure we capture ray intersections
        
        Point3f origin = ray.origin();
        Vector3f direction = ray.direction();
        
        // Calculate the ray endpoint for a reasonable distance
        float rayLength = searchExtent;
        Point3f endpoint = new Point3f(
            origin.x + direction.x * rayLength,
            origin.y + direction.y * rayLength,
            origin.z + direction.z * rayLength
        );
        
        // Create AABB that encompasses both origin and endpoint with some padding
        float minX = Math.max(0, Math.min(origin.x, endpoint.x) - searchExtent * 0.1f);
        float minY = Math.max(0, Math.min(origin.y, endpoint.y) - searchExtent * 0.1f);
        float minZ = Math.max(0, Math.min(origin.z, endpoint.z) - searchExtent * 0.1f);
        float maxX = Math.max(origin.x, endpoint.x) + searchExtent * 0.1f;
        float maxY = Math.max(origin.y, endpoint.y) + searchExtent * 0.1f;
        float maxZ = Math.max(origin.z, endpoint.z) + searchExtent * 0.1f;
        
        return new Spatial.aabb(minX, minY, minZ, maxX, maxY, maxZ);
    }
}