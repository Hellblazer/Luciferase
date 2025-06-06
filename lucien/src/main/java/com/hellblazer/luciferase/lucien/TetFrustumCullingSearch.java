package com.hellblazer.luciferase.lucien;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import javax.vecmath.Vector3f;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Frustum culling search implementation for Tetree
 * Finds tetrahedra that intersect with a 3D camera frustum, with results ordered by distance from camera
 * All operations are constrained to positive coordinates only
 * 
 * @author hal.hildebrand
 */
public class TetFrustumCullingSearch extends TetrahedralSearchBase {

    /**
     * Frustum intersection result with distance information
     */
    public static class TetFrustumIntersection<Content> {
        public final Tetree.Simplex<Content> simplex;
        public final float distanceToCamera;
        public final Point3f tetCenter;
        public final CullingResult cullingResult;

        public TetFrustumIntersection(Tetree.Simplex<Content> simplex, 
                                     float distanceToCamera, Point3f tetCenter, CullingResult cullingResult) {
            this.simplex = simplex;
            this.distanceToCamera = distanceToCamera;
            this.tetCenter = tetCenter;
            this.cullingResult = cullingResult;
        }
    }

    /**
     * Result of frustum culling test
     */
    public enum CullingResult {
        INSIDE,      // Completely inside frustum
        INTERSECTING, // Partially inside frustum
        OUTSIDE       // Completely outside frustum
    }

    /**
     * Find all tetrahedra that intersect with the frustum, ordered by distance from camera
     * 
     * @param frustum the camera frustum to test intersection with
     * @param tetree the tetree to search in
     * @param cameraPosition camera position for distance calculations (positive coordinates only)
     * @param strategy simplex aggregation strategy
     * @return list of intersections sorted by distance from camera (closest first)
     * @throws IllegalArgumentException if camera position has negative coordinates
     */
    public static <Content> List<TetFrustumIntersection<Content>> frustumCulledAll(
            Frustum3D frustum, Tetree<Content> tetree, Point3f cameraPosition,
            SimplexAggregationStrategy strategy) {
        
        validatePositiveCoordinates(cameraPosition, "cameraPosition");
        
        // Get frustum bounding box for spatial query
        Spatial.aabb frustumBounds = computeFrustumBounds(frustum);
        
        // Query tetree for simplicies that might intersect the frustum
        // Use TetreeHelper workaround due to known issues with Tetree.bounding()
        var simplicies = TetreeHelper.directScanBoundedBy(tetree, frustumBounds);
        var aggregated = aggregateSimplicies(simplicies, strategy);
        
        List<TetFrustumIntersection<Content>> intersections = new ArrayList<>();
        
        for (var simplex : aggregated) {
            var tetIndex = simplex.index();
            var tet = Tet.tetrahedron(tetIndex);
            
            CullingResult result = testFrustumCulling(frustum, tet);
            if (result != CullingResult.OUTSIDE) {
                Point3f tetCenter = getTetrahedronCenter(tet);
                float distance = calculateDistance(cameraPosition, tetCenter);
                
                TetFrustumIntersection<Content> intersection = new TetFrustumIntersection<>(
                    simplex, 
                    distance,
                    tetCenter,
                    result
                );
                intersections.add(intersection);
            }
        }

        // Sort by distance from camera
        intersections.sort(Comparator.comparing(fi -> fi.distanceToCamera));
        
        return intersections;
    }

    /**
     * Find the first (closest to camera) tetrahedron that intersects with the frustum
     * 
     * @param frustum the camera frustum to test intersection with
     * @param tetree the tetree to search in
     * @param cameraPosition camera position for distance calculations (positive coordinates only)
     * @param strategy simplex aggregation strategy
     * @return the closest intersection, or null if no intersection
     * @throws IllegalArgumentException if camera position has negative coordinates
     */
    public static <Content> TetFrustumIntersection<Content> frustumCulledFirst(
            Frustum3D frustum, Tetree<Content> tetree, Point3f cameraPosition,
            SimplexAggregationStrategy strategy) {
        
        List<TetFrustumIntersection<Content>> intersections = frustumCulledAll(frustum, tetree, cameraPosition, strategy);
        return intersections.isEmpty() ? null : intersections.get(0);
    }

    /**
     * Find tetrahedra that are completely inside the frustum
     * 
     * @param frustum the camera frustum to test against
     * @param tetree the tetree to search in
     * @param cameraPosition camera position for distance calculations (positive coordinates only)
     * @param strategy simplex aggregation strategy
     * @return list of intersections completely inside frustum, sorted by distance from camera
     * @throws IllegalArgumentException if camera position has negative coordinates
     */
    public static <Content> List<TetFrustumIntersection<Content>> tetrahedraCompletelyInside(
            Frustum3D frustum, Tetree<Content> tetree, Point3f cameraPosition,
            SimplexAggregationStrategy strategy) {
        
        return frustumCulledAll(frustum, tetree, cameraPosition, strategy).stream()
            .filter(fi -> fi.cullingResult == CullingResult.INSIDE)
            .collect(Collectors.toList());
    }

    /**
     * Find tetrahedra that partially intersect the frustum (not completely inside or outside)
     * 
     * @param frustum the camera frustum to test against
     * @param tetree the tetree to search in
     * @param cameraPosition camera position for distance calculations (positive coordinates only)
     * @param strategy simplex aggregation strategy
     * @return list of intersections partially intersecting frustum, sorted by distance from camera
     * @throws IllegalArgumentException if camera position has negative coordinates
     */
    public static <Content> List<TetFrustumIntersection<Content>> tetrahedraPartiallyIntersecting(
            Frustum3D frustum, Tetree<Content> tetree, Point3f cameraPosition,
            SimplexAggregationStrategy strategy) {
        
        return frustumCulledAll(frustum, tetree, cameraPosition, strategy).stream()
            .filter(fi -> fi.cullingResult == CullingResult.INTERSECTING)
            .collect(Collectors.toList());
    }

    /**
     * Count the number of tetrahedra that intersect with the frustum
     * This is more efficient than getting all intersections when only count is needed
     * 
     * @param frustum the camera frustum to test intersection with
     * @param tetree the tetree to search in
     * @param strategy simplex aggregation strategy
     * @return number of tetrahedra intersecting the frustum
     */
    public static <Content> long countFrustumIntersections(Frustum3D frustum, Tetree<Content> tetree,
                                                          SimplexAggregationStrategy strategy) {
        // Get frustum bounding box for spatial query
        Spatial.aabb frustumBounds = computeFrustumBounds(frustum);
        
        // Query tetree for simplicies that might intersect the frustum
        // Use TetreeHelper workaround due to known issues with Tetree.bounding()
        var simplicies = TetreeHelper.directScanBoundedBy(tetree, frustumBounds);
        var aggregated = aggregateSimplicies(simplicies, strategy);
        
        return aggregated.stream()
            .filter(simplex -> {
                var tetIndex = simplex.index();
                var tet = Tet.tetrahedron(tetIndex);
                CullingResult result = testFrustumCulling(frustum, tet);
                return result != CullingResult.OUTSIDE;
            })
            .count();
    }

    /**
     * Test if any tetrahedron in the tetree intersects with the frustum
     * This is more efficient than getting all intersections when only existence check is needed
     * 
     * @param frustum the camera frustum to test intersection with
     * @param tetree the tetree to search in
     * @param strategy simplex aggregation strategy
     * @return true if any tetrahedron intersects the frustum
     */
    public static <Content> boolean hasAnyIntersection(Frustum3D frustum, Tetree<Content> tetree,
                                                      SimplexAggregationStrategy strategy) {
        // Get frustum bounding box for spatial query
        Spatial.aabb frustumBounds = computeFrustumBounds(frustum);
        
        // Query tetree for simplicies that might intersect the frustum
        // Use TetreeHelper workaround due to known issues with Tetree.bounding()
        var simplicies = TetreeHelper.directScanBoundedBy(tetree, frustumBounds);
        var aggregated = aggregateSimplicies(simplicies, strategy);
        
        return aggregated.stream()
            .anyMatch(simplex -> {
                var tetIndex = simplex.index();
                var tet = Tet.tetrahedron(tetIndex);
                CullingResult result = testFrustumCulling(frustum, tet);
                return result != CullingResult.OUTSIDE;
            });
    }

    /**
     * Get statistics about frustum culling results
     * 
     * @param frustum the camera frustum to test intersection with
     * @param tetree the tetree to search in
     * @param strategy simplex aggregation strategy
     * @return statistics about culling results
     */
    public static <Content> CullingStatistics getFrustumCullingStatistics(Frustum3D frustum, Tetree<Content> tetree,
                                                                         SimplexAggregationStrategy strategy) {
        // Get frustum bounding box for spatial query
        Spatial.aabb frustumBounds = computeFrustumBounds(frustum);
        
        // Query tetree for simplicies that might intersect the frustum
        // Use TetreeHelper workaround due to known issues with Tetree.bounding()
        var simplicies = TetreeHelper.directScanBoundedBy(tetree, frustumBounds);
        var aggregated = aggregateSimplicies(simplicies, strategy);
        
        long totalTetrahedra = 0;
        long insideTetrahedra = 0;
        long intersectingTetrahedra = 0;
        long outsideTetrahedra = 0;

        for (var simplex : aggregated) {
            totalTetrahedra++;
            var tetIndex = simplex.index();
            var tet = Tet.tetrahedron(tetIndex);
            CullingResult result = testFrustumCulling(frustum, tet);
            
            switch (result) {
                case INSIDE -> insideTetrahedra++;
                case INTERSECTING -> intersectingTetrahedra++;
                case OUTSIDE -> outsideTetrahedra++;
            }
        }

        return new CullingStatistics(totalTetrahedra, insideTetrahedra, intersectingTetrahedra, outsideTetrahedra);
    }

    /**
     * Statistics about frustum culling results
     */
    public static class CullingStatistics {
        public final long totalTetrahedra;
        public final long insideTetrahedra;
        public final long intersectingTetrahedra;
        public final long outsideTetrahedra;
        
        public CullingStatistics(long totalTetrahedra, long insideTetrahedra, long intersectingTetrahedra, long outsideTetrahedra) {
            this.totalTetrahedra = totalTetrahedra;
            this.insideTetrahedra = insideTetrahedra;
            this.intersectingTetrahedra = intersectingTetrahedra;
            this.outsideTetrahedra = outsideTetrahedra;
        }
        
        public double getInsidePercentage() {
            return totalTetrahedra > 0 ? (double) insideTetrahedra / totalTetrahedra * 100.0 : 0.0;
        }
        
        public double getIntersectingPercentage() {
            return totalTetrahedra > 0 ? (double) intersectingTetrahedra / totalTetrahedra * 100.0 : 0.0;
        }
        
        public double getOutsidePercentage() {
            return totalTetrahedra > 0 ? (double) outsideTetrahedra / totalTetrahedra * 100.0 : 0.0;
        }
        
        public double getVisiblePercentage() {
            return totalTetrahedra > 0 ? (double) (insideTetrahedra + intersectingTetrahedra) / totalTetrahedra * 100.0 : 0.0;
        }
        
        @Override
        public String toString() {
            return String.format("CullingStats[total=%d, inside=%d(%.1f%%), intersecting=%d(%.1f%%), outside=%d(%.1f%%), visible=%.1f%%]",
                               totalTetrahedra, insideTetrahedra, getInsidePercentage(), 
                               intersectingTetrahedra, getIntersectingPercentage(),
                               outsideTetrahedra, getOutsidePercentage(), getVisiblePercentage());
        }
    }

    /**
     * Batch processing for multiple frustum culling queries
     * Useful for multiple camera views or time-based culling
     * 
     * @param frustums list of frustums to test
     * @param tetree the tetree to search in
     * @param cameraPosition camera position for distance calculations (positive coordinates only)
     * @param strategy simplex aggregation strategy
     * @return map of frustums to their intersection results
     * @throws IllegalArgumentException if camera position has negative coordinates
     */
    public static <Content> Map<Frustum3D, List<TetFrustumIntersection<Content>>> 
            batchFrustumCulling(List<Frustum3D> frustums, Tetree<Content> tetree, Point3f cameraPosition,
                               SimplexAggregationStrategy strategy) {
        
        validatePositiveCoordinates(cameraPosition, "cameraPosition");
        
        return frustums.stream()
            .collect(Collectors.toMap(
                frustum -> frustum,
                frustum -> frustumCulledAll(frustum, tetree, cameraPosition, strategy)
            ));
    }

    /**
     * Test frustum culling for a single tetrahedron
     * 
     * @param frustum the camera frustum
     * @param tet the tetrahedron to test
     * @return culling result (INSIDE, INTERSECTING, or OUTSIDE)
     */
    private static CullingResult testFrustumCulling(Frustum3D frustum, Tet tet) {
        // Get tetrahedron vertices
        Point3i[] vertices = tet.coordinates();
        Point3f[] verticesFloat = new Point3f[4];
        for (int i = 0; i < 4; i++) {
            verticesFloat[i] = new Point3f(vertices[i].x, vertices[i].y, vertices[i].z);
        }
        
        // Test if all vertices are inside frustum
        boolean allInside = true;
        boolean anyInside = false;
        
        for (Point3f vertex : verticesFloat) {
            if (frustum.containsPoint(vertex)) {
                anyInside = true;
            } else {
                allInside = false;
            }
        }
        
        if (allInside) {
            return CullingResult.INSIDE;
        }
        
        if (anyInside) {
            return CullingResult.INTERSECTING;
        }
        
        // Test if tetrahedron edges intersect frustum planes
        // If no vertices are inside but edges cross frustum planes, it's intersecting
        if (testTetrahedronFrustumIntersection(frustum, verticesFloat)) {
            return CullingResult.INTERSECTING;
        }
        
        // Tetrahedron is completely outside
        return CullingResult.OUTSIDE;
    }
    
    /**
     * Test if tetrahedron edges intersect with frustum planes
     * This handles the case where no vertices are inside the frustum but the tetrahedron still intersects
     */
    private static boolean testTetrahedronFrustumIntersection(Frustum3D frustum, Point3f[] vertices) {
        // Get tetrahedron AABB for quick rejection
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = Float.MIN_VALUE, maxY = Float.MIN_VALUE, maxZ = Float.MIN_VALUE;
        
        for (Point3f v : vertices) {
            minX = Math.min(minX, v.x);
            minY = Math.min(minY, v.y);
            minZ = Math.min(minZ, v.z);
            maxX = Math.max(maxX, v.x);
            maxY = Math.max(maxY, v.y);
            maxZ = Math.max(maxZ, v.z);
        }
        
        // Quick AABB test
        if (!frustum.intersectsAABB(minX, minY, minZ, maxX, maxY, maxZ)) {
            return false;
        }
        
        // If AABB intersects but no vertices are inside, check if frustum is contained within tetrahedron
        // This is a complex test - for now, we'll use the AABB intersection as sufficient
        return true;
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
    
    /**
     * Calculate the center point of a tetrahedron
     */
    private static Point3f getTetrahedronCenter(Tet tet) {
        Point3i[] vertices = tet.coordinates();
        float centerX = (vertices[0].x + vertices[1].x + vertices[2].x + vertices[3].x) / 4.0f;
        float centerY = (vertices[0].y + vertices[1].y + vertices[2].y + vertices[3].y) / 4.0f;
        float centerZ = (vertices[0].z + vertices[1].z + vertices[2].z + vertices[3].z) / 4.0f;
        return new Point3f(centerX, centerY, centerZ);
    }
    
    /**
     * Compute bounding box for a frustum
     * This is used to query the tetree spatially before doing detailed frustum tests
     */
    static Spatial.aabb computeFrustumBounds(Frustum3D frustum) {
        // Get the 8 frustum corners by finding intersections of the frustum planes
        Point3f[] corners = computeFrustumCorners(frustum);
        
        // Find the bounding box of all corners
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = Float.MIN_VALUE, maxY = Float.MIN_VALUE, maxZ = Float.MIN_VALUE;
        
        for (Point3f corner : corners) {
            // Ensure positive coordinates
            if (corner.x < 0 || corner.y < 0 || corner.z < 0) {
                // Skip negative coordinates - clamp to 0
                corner.x = Math.max(0f, corner.x);
                corner.y = Math.max(0f, corner.y);
                corner.z = Math.max(0f, corner.z);
            }
            
            minX = Math.min(minX, corner.x);
            minY = Math.min(minY, corner.y);
            minZ = Math.min(minZ, corner.z);
            maxX = Math.max(maxX, corner.x);
            maxY = Math.max(maxY, corner.y);
            maxZ = Math.max(maxZ, corner.z);
        }
        
        // Ensure bounds are within valid range
        minX = Math.max(0f, minX);
        minY = Math.max(0f, minY);
        minZ = Math.max(0f, minZ);
        maxX = Math.min(Constants.MAX_EXTENT, maxX);
        maxY = Math.min(Constants.MAX_EXTENT, maxY);
        maxZ = Math.min(Constants.MAX_EXTENT, maxZ);
        
        // Check if we computed valid bounds
        if (minX == Float.MAX_VALUE || maxX == Float.MIN_VALUE) {
            // If corners computation failed, use a conservative estimate based on camera position
            // This is temporary until we have a better frustum bounds implementation
            return new Spatial.aabb(0f, 0f, 0f, 3000f, 3000f, 3000f);
        }
        
        return new Spatial.aabb(minX, minY, minZ, maxX, maxY, maxZ);
    }
    
    /**
     * Compute the 8 corners of a frustum by finding intersections of planes
     */
    private static Point3f[] computeFrustumCorners(Frustum3D frustum) {
        // A frustum has 8 corners formed by the intersection of 3 planes each
        // Near plane corners: intersections of near plane with top/bottom and left/right
        // Far plane corners: intersections of far plane with top/bottom and left/right
        
        Point3f[] corners = new Point3f[8];
        
        // Near plane corners
        corners[0] = intersectThreePlanes(frustum.nearPlane, frustum.leftPlane, frustum.bottomPlane);  // Near-Left-Bottom
        corners[1] = intersectThreePlanes(frustum.nearPlane, frustum.rightPlane, frustum.bottomPlane); // Near-Right-Bottom
        corners[2] = intersectThreePlanes(frustum.nearPlane, frustum.leftPlane, frustum.topPlane);     // Near-Left-Top
        corners[3] = intersectThreePlanes(frustum.nearPlane, frustum.rightPlane, frustum.topPlane);    // Near-Right-Top
        
        // Far plane corners
        corners[4] = intersectThreePlanes(frustum.farPlane, frustum.leftPlane, frustum.bottomPlane);   // Far-Left-Bottom
        corners[5] = intersectThreePlanes(frustum.farPlane, frustum.rightPlane, frustum.bottomPlane);  // Far-Right-Bottom
        corners[6] = intersectThreePlanes(frustum.farPlane, frustum.leftPlane, frustum.topPlane);      // Far-Left-Top
        corners[7] = intersectThreePlanes(frustum.farPlane, frustum.rightPlane, frustum.topPlane);     // Far-Right-Top
        
        return corners;
    }
    
    /**
     * Find the intersection point of three planes
     * Returns null if planes don't have a unique intersection point
     */
    private static Point3f intersectThreePlanes(Plane3D p1, Plane3D p2, Plane3D p3) {
        // Three planes intersect at a point if their normal vectors are linearly independent
        // We solve the system: n1·x = d1, n2·x = d2, n3·x = d3
        
        Vector3f n1 = p1.getNormal();
        Vector3f n2 = p2.getNormal();
        Vector3f n3 = p3.getNormal();
        
        float d1 = -p1.d();
        float d2 = -p2.d();
        float d3 = -p3.d();
        
        // Compute the determinant of the normal matrix
        float det = n1.x * (n2.y * n3.z - n2.z * n3.y) -
                   n1.y * (n2.x * n3.z - n2.z * n3.x) +
                   n1.z * (n2.x * n3.y - n2.y * n3.x);
        
        if (Math.abs(det) < GEOMETRIC_TOLERANCE) {
            // Planes are parallel or don't have unique intersection
            // Return a large default point
            return new Point3f(Constants.MAX_EXTENT / 2.0f, Constants.MAX_EXTENT / 2.0f, Constants.MAX_EXTENT / 2.0f);
        }
        
        // Use Cramer's rule to solve for the intersection point
        float x = (d1 * (n2.y * n3.z - n2.z * n3.y) -
                  n1.y * (d2 * n3.z - n2.z * d3) +
                  n1.z * (d2 * n3.y - n2.y * d3)) / det;
        
        float y = (n1.x * (d2 * n3.z - n2.z * d3) -
                  d1 * (n2.x * n3.z - n2.z * n3.x) +
                  n1.z * (n2.x * d3 - d2 * n3.x)) / det;
        
        float z = (n1.x * (n2.y * d3 - d2 * n3.y) -
                  n1.y * (n2.x * d3 - d2 * n3.x) +
                  d1 * (n2.x * n3.y - n2.y * n3.x)) / det;
        
        return new Point3f(x, y, z);
    }
    
    /**
     * Validate that all coordinates in a point are positive
     */
    private static void validatePositiveCoordinates(Point3f point, String paramName) {
        if (point.x < 0 || point.y < 0 || point.z < 0) {
            throw new IllegalArgumentException(paramName + " coordinates must be positive, got: " + point);
        }
    }
}