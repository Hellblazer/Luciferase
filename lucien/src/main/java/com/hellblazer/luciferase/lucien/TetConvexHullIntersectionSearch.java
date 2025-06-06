package com.hellblazer.luciferase.lucien;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import javax.vecmath.Vector3f;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Convex hull intersection search implementation for Tetree
 * Finds tetrahedra that intersect with 3D convex hulls defined by planes or vertices
 * All operations are constrained to positive coordinates only
 * 
 * @author hal.hildebrand
 */
public class TetConvexHullIntersectionSearch extends TetrahedralSearchBase {

    /**
     * Convex hull intersection result with distance information for tetrahedra
     */
    public static class TetConvexHullIntersection<Content> {
        public final long index;
        public final Content content;
        public final Tet tetrahedron;
        public final float distanceToReferencePoint;
        public final Point3f tetrahedronCenter;
        public final IntersectionType intersectionType;
        public final float penetrationDepth; // how deep the tetrahedron penetrates into the hull

        public TetConvexHullIntersection(long index, Content content, Tet tetrahedron, 
                                       float distanceToReferencePoint, Point3f tetrahedronCenter, 
                                       IntersectionType intersectionType, float penetrationDepth) {
            this.index = index;
            this.content = content;
            this.tetrahedron = tetrahedron;
            this.distanceToReferencePoint = distanceToReferencePoint;
            this.tetrahedronCenter = tetrahedronCenter;
            this.intersectionType = intersectionType;
            this.penetrationDepth = penetrationDepth;
        }
    }

    /**
     * Type of intersection between convex hull and tetrahedron
     */
    public enum IntersectionType {
        COMPLETELY_INSIDE,  // Tetrahedron is completely inside convex hull
        INTERSECTING,       // Tetrahedron partially intersects convex hull
        COMPLETELY_OUTSIDE  // Tetrahedron is completely outside convex hull (not returned in intersection results)
    }

    /**
     * Represents a convex hull defined by a set of half-space planes
     * Each plane defines a half-space, and the convex hull is the intersection of all half-spaces
     * Adapted for tetrahedral space operations
     */
    public static class TetConvexHull {
        public final List<Plane3D> planes;
        public final Point3f centroid;
        public final float boundingRadius;
        
        public TetConvexHull(List<Plane3D> planes) {
            if (planes == null || planes.isEmpty()) {
                throw new IllegalArgumentException("Convex hull must have at least one plane");
            }
            this.planes = Collections.unmodifiableList(new ArrayList<>(planes));
            this.centroid = calculateCentroid(planes);
            this.boundingRadius = calculateBoundingRadius(planes, centroid);
        }
        
        /**
         * Create a convex hull from vertices using tetrahedral decomposition approach
         */
        public static TetConvexHull fromVertices(List<Point3f> vertices) {
            if (vertices == null || vertices.size() < 4) {
                throw new IllegalArgumentException("Convex hull requires at least 4 vertices");
            }
            
            for (Point3f vertex : vertices) {
                validatePositiveCoordinates(vertex);
            }
            
            // Create planes from vertices using tetrahedral convex hull construction
            List<Plane3D> planes = createPlanesFromVertices(vertices);
            return new TetConvexHull(planes);
        }
        
        /**
         * Create a tetrahedral convex hull approximation
         */
        public static TetConvexHull createTetrahedralHull(Point3f v0, Point3f v1, Point3f v2, Point3f v3) {
            validatePositiveCoordinates(v0);
            validatePositiveCoordinates(v1);
            validatePositiveCoordinates(v2);
            validatePositiveCoordinates(v3);
            
            List<Plane3D> planes = new ArrayList<>();
            
            try {
                // Create 4 triangular faces of the tetrahedron
                // Ensure normals point inward to the tetrahedron
                planes.add(createTetrahedralFacePlane(v0, v1, v2, v3)); // Face opposite to v3
                planes.add(createTetrahedralFacePlane(v0, v2, v3, v1)); // Face opposite to v1
                planes.add(createTetrahedralFacePlane(v0, v3, v1, v2)); // Face opposite to v2
                planes.add(createTetrahedralFacePlane(v1, v2, v3, v0)); // Face opposite to v0
            } catch (IllegalArgumentException e) {
                // If points are coplanar, fall back to bounding box
                List<Point3f> vertices = Arrays.asList(v0, v1, v2, v3);
                return new TetConvexHull(createBoundingBoxPlanes(vertices));
            }
            
            return new TetConvexHull(planes);
        }
        
        /**
         * Test if a point is inside this convex hull
         */
        public boolean containsPoint(Point3f point) {
            validatePositiveCoordinates(point);
            
            for (Plane3D plane : planes) {
                if (plane.distanceToPoint(point) > GEOMETRIC_TOLERANCE) {
                    return false; // Point is on positive side of plane (outside)
                }
            }
            return true;
        }
        
        /**
         * Test if a tetrahedron intersects with this convex hull
         */
        public IntersectionType intersectsTetrahedron(Tet tetrahedron) {
            return testConvexHullTetrahedronIntersection(this, tetrahedron);
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
        
        private static Plane3D createTetrahedralFacePlane(Point3f v0, Point3f v1, Point3f v2, Point3f oppositeVertex) {
            // Create plane from three vertices with normal pointing toward the opposite vertex (inward)
            Plane3D basePlane = Plane3D.fromThreePoints(v0, v1, v2);
            
            // Check if normal points toward or away from the opposite vertex
            float distanceToOpposite = basePlane.distanceToPoint(oppositeVertex);
            
            if (distanceToOpposite > 0) {
                // Normal points away from opposite vertex - flip it to point inward
                return new Plane3D(-basePlane.a(), -basePlane.b(), -basePlane.c(), -basePlane.d());
            } else {
                // Normal already points toward opposite vertex (inward)
                return basePlane;
            }
        }
        
        private static Point3f calculateCentroid(List<Plane3D> planes) {
            // Simplified centroid calculation for tetrahedral space
            float sumX = 0, sumY = 0, sumZ = 0;
            for (Plane3D plane : planes) {
                // Get a point on the plane
                float distance = -plane.d();
                Point3f planePoint = new Point3f(
                    plane.a() * distance,
                    plane.b() * distance,
                    plane.c() * distance
                );
                sumX += planePoint.x;
                sumY += planePoint.y;
                sumZ += planePoint.z;
            }
            return new Point3f(sumX / planes.size(), sumY / planes.size(), sumZ / planes.size());
        }
        
        private static float calculateBoundingRadius(List<Plane3D> planes, Point3f centroid) {
            // Calculate bounding radius for tetrahedral operations
            float maxDistance = 0;
            for (Plane3D plane : planes) {
                float distance = Math.abs(plane.distanceToPoint(centroid));
                maxDistance = Math.max(maxDistance, distance);
            }
            return maxDistance;
        }
        
        private static List<Plane3D> createPlanesFromVertices(List<Point3f> vertices) {
            // Create tetrahedral convex hull planes
            List<Plane3D> planes = new ArrayList<>();
            
            if (vertices.size() >= 4) {
                // Create a tetrahedron from first 4 vertices
                Point3f v0 = vertices.get(0);
                Point3f v1 = vertices.get(1);
                Point3f v2 = vertices.get(2);
                Point3f v3 = vertices.get(3);
                
                // Create 4 triangular faces with inward normals
                try {
                    planes.add(createTetrahedralFacePlane(v0, v1, v2, v3));
                    planes.add(createTetrahedralFacePlane(v0, v2, v3, v1));
                    planes.add(createTetrahedralFacePlane(v0, v3, v1, v2));
                    planes.add(createTetrahedralFacePlane(v1, v2, v3, v0));
                } catch (IllegalArgumentException e) {
                    // If points are coplanar, fall back to bounding box
                    return createBoundingBoxPlanes(vertices);
                }
            } else {
                // Fall back to axis-aligned bounding box
                return createBoundingBoxPlanes(vertices);
            }
            
            return planes;
        }
        
        private static List<Plane3D> createBoundingBoxPlanes(List<Point3f> vertices) {
            // Create axis-aligned bounding box planes for tetrahedral space
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
            planes.add(new Plane3D(-1, 0, 0, maxX));  // Right face (normal points left)
            planes.add(new Plane3D(1, 0, 0, -minX));   // Left face (normal points right)
            planes.add(new Plane3D(0, -1, 0, maxY));  // Top face (normal points down)
            planes.add(new Plane3D(0, 1, 0, -minY));   // Bottom face (normal points up)
            planes.add(new Plane3D(0, 0, -1, maxZ));  // Far face (normal points back)
            planes.add(new Plane3D(0, 0, 1, -minZ));   // Near face (normal points forward)
            
            return planes;
        }
        
        @Override
        public String toString() {
            return String.format("TetConvexHull[planes=%d, centroid=%s, radius=%.2f]", 
                               planes.size(), centroid, boundingRadius);
        }
    }

    /**
     * Find all tetrahedra that intersect with the convex hull, ordered by distance from reference point
     * 
     * @param convexHull the convex hull to test intersection with
     * @param tetree the tetree to search in
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @param strategy aggregation strategy for multiple simplicies
     * @return list of intersections sorted by distance from reference point (closest first)
     * @throws IllegalArgumentException if reference point has negative coordinates
     */
    public static <Content> List<TetConvexHullIntersection<Content>> convexHullIntersectedAll(
            TetConvexHull convexHull, Tetree<Content> tetree, Point3f referencePoint, SimplexAggregationStrategy strategy) {
        
        validatePositiveCoordinates(referencePoint);
        
        // Create a spatial volume around the convex hull for efficient querying
        var hullBounds = createConvexHullBounds(convexHull);
        var candidateTetrahedra = tetree.boundedBy(hullBounds);
        
        List<TetConvexHullIntersection<Content>> intersections = new ArrayList<>();
        
        for (var simplex : aggregateSimplicies(candidateTetrahedra, strategy)) {
            var tet = Tet.tetrahedron(simplex.index());
            
            IntersectionType intersectionType = testConvexHullTetrahedronIntersection(convexHull, tet);
            if (intersectionType != IntersectionType.COMPLETELY_OUTSIDE) {
                Point3f tetCenter = tetrahedronCenter(simplex.index());
                float distance = calculateDistance(referencePoint, tetCenter);
                float penetrationDepth = calculatePenetrationDepth(convexHull, tet);
                
                TetConvexHullIntersection<Content> intersection = new TetConvexHullIntersection<>(
                    simplex.index(), 
                    simplex.cell(), 
                    tet, 
                    distance,
                    tetCenter,
                    intersectionType,
                    penetrationDepth
                );
                intersections.add(intersection);
            }
        }

        // Sort by distance from reference point
        intersections.sort(Comparator.comparing(tchi -> tchi.distanceToReferencePoint));
        
        return intersections;
    }

    /**
     * Find the first (closest to reference point) tetrahedron that intersects with the convex hull
     * 
     * @param convexHull the convex hull to test intersection with
     * @param tetree the tetree to search in
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @param strategy aggregation strategy for multiple simplicies
     * @return the closest intersection, or null if no intersection
     * @throws IllegalArgumentException if reference point has negative coordinates
     */
    public static <Content> TetConvexHullIntersection<Content> convexHullIntersectedFirst(
            TetConvexHull convexHull, Tetree<Content> tetree, Point3f referencePoint, SimplexAggregationStrategy strategy) {
        
        List<TetConvexHullIntersection<Content>> intersections = convexHullIntersectedAll(convexHull, tetree, referencePoint, strategy);
        return intersections.isEmpty() ? null : intersections.get(0);
    }

    /**
     * Find tetrahedra that are completely inside the convex hull
     * 
     * @param convexHull the convex hull to test against
     * @param tetree the tetree to search in
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @param strategy aggregation strategy for multiple simplicies
     * @return list of intersections completely inside convex hull, sorted by distance from reference point
     * @throws IllegalArgumentException if reference point has negative coordinates
     */
    public static <Content> List<TetConvexHullIntersection<Content>> tetrahedraCompletelyInside(
            TetConvexHull convexHull, Tetree<Content> tetree, Point3f referencePoint, SimplexAggregationStrategy strategy) {
        
        return convexHullIntersectedAll(convexHull, tetree, referencePoint, strategy).stream()
            .filter(tchi -> tchi.intersectionType == IntersectionType.COMPLETELY_INSIDE)
            .collect(Collectors.toList());
    }

    /**
     * Find tetrahedra that partially intersect the convex hull (not completely inside or outside)
     * 
     * @param convexHull the convex hull to test against
     * @param tetree the tetree to search in
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @param strategy aggregation strategy for multiple simplicies
     * @return list of intersections partially intersecting convex hull, sorted by distance from reference point
     * @throws IllegalArgumentException if reference point has negative coordinates
     */
    public static <Content> List<TetConvexHullIntersection<Content>> tetrahedraPartiallyIntersecting(
            TetConvexHull convexHull, Tetree<Content> tetree, Point3f referencePoint, SimplexAggregationStrategy strategy) {
        
        return convexHullIntersectedAll(convexHull, tetree, referencePoint, strategy).stream()
            .filter(tchi -> tchi.intersectionType == IntersectionType.INTERSECTING)
            .collect(Collectors.toList());
    }

    /**
     * Count the number of tetrahedra that intersect with the convex hull
     * This is more efficient than getting all intersections when only count is needed
     * 
     * @param convexHull the convex hull to test intersection with
     * @param tetree the tetree to search in
     * @param strategy aggregation strategy for multiple simplicies
     * @return number of tetrahedra intersecting the convex hull
     */
    public static <Content> long countConvexHullIntersections(TetConvexHull convexHull, Tetree<Content> tetree, SimplexAggregationStrategy strategy) {
        var hullBounds = createConvexHullBounds(convexHull);
        var candidateTetrahedra = tetree.boundedBy(hullBounds);

        return aggregateSimplicies(candidateTetrahedra, strategy).stream()
            .mapToLong(simplex -> {
                var tet = Tet.tetrahedron(simplex.index());
                IntersectionType intersectionType = testConvexHullTetrahedronIntersection(convexHull, tet);
                return intersectionType != IntersectionType.COMPLETELY_OUTSIDE ? 1 : 0;
            })
            .sum();
    }

    /**
     * Test if any tetrahedron in the tetree intersects with the convex hull
     * This is more efficient than getting all intersections when only existence check is needed
     * 
     * @param convexHull the convex hull to test intersection with
     * @param tetree the tetree to search in
     * @param strategy aggregation strategy for multiple simplicies
     * @return true if any tetrahedron intersects the convex hull
     */
    public static <Content> boolean hasAnyIntersection(TetConvexHull convexHull, Tetree<Content> tetree, SimplexAggregationStrategy strategy) {
        var hullBounds = createConvexHullBounds(convexHull);
        var candidateTetrahedra = tetree.boundedBy(hullBounds);

        return aggregateSimplicies(candidateTetrahedra, strategy).stream()
            .anyMatch(simplex -> {
                var tet = Tet.tetrahedron(simplex.index());
                IntersectionType intersectionType = testConvexHullTetrahedronIntersection(convexHull, tet);
                return intersectionType != IntersectionType.COMPLETELY_OUTSIDE;
            });
    }

    /**
     * Get statistics about convex hull intersection results
     * 
     * @param convexHull the convex hull to test intersection with
     * @param tetree the tetree to search in
     * @param strategy aggregation strategy for multiple simplicies
     * @return statistics about intersection results
     */
    public static <Content> TetIntersectionStatistics getConvexHullIntersectionStatistics(
            TetConvexHull convexHull, Tetree<Content> tetree, SimplexAggregationStrategy strategy) {
        
        var hullBounds = createConvexHullBounds(convexHull);
        var candidateTetrahedra = tetree.boundedBy(hullBounds);
        
        long totalTetrahedra = 0;
        long insideTetrahedra = 0;
        long intersectingTetrahedra = 0;
        long outsideTetrahedra = 0;
        float totalPenetrationDepth = 0.0f;

        for (var simplex : aggregateSimplicies(candidateTetrahedra, strategy)) {
            totalTetrahedra++;
            var tet = Tet.tetrahedron(simplex.index());
            IntersectionType intersectionType = testConvexHullTetrahedronIntersection(convexHull, tet);
            
            switch (intersectionType) {
                case COMPLETELY_INSIDE -> {
                    insideTetrahedra++;
                    totalPenetrationDepth += calculatePenetrationDepth(convexHull, tet);
                }
                case INTERSECTING -> {
                    intersectingTetrahedra++;
                    totalPenetrationDepth += calculatePenetrationDepth(convexHull, tet);
                }
                case COMPLETELY_OUTSIDE -> outsideTetrahedra++;
            }
        }

        float averagePenetrationDepth = (insideTetrahedra + intersectingTetrahedra) > 0 ? 
            totalPenetrationDepth / (insideTetrahedra + intersectingTetrahedra) : 0.0f;

        return new TetIntersectionStatistics(totalTetrahedra, insideTetrahedra, intersectingTetrahedra, 
                                           outsideTetrahedra, totalPenetrationDepth, averagePenetrationDepth);
    }

    /**
     * Statistics about convex hull intersection results in tetrahedral space
     */
    public static class TetIntersectionStatistics {
        public final long totalTetrahedra;
        public final long insideTetrahedra;
        public final long intersectingTetrahedra;
        public final long outsideTetrahedra;
        public final float totalPenetrationDepth;
        public final float averagePenetrationDepth;
        
        public TetIntersectionStatistics(long totalTetrahedra, long insideTetrahedra, long intersectingTetrahedra, 
                                       long outsideTetrahedra, float totalPenetrationDepth, float averagePenetrationDepth) {
            this.totalTetrahedra = totalTetrahedra;
            this.insideTetrahedra = insideTetrahedra;
            this.intersectingTetrahedra = intersectingTetrahedra;
            this.outsideTetrahedra = outsideTetrahedra;
            this.totalPenetrationDepth = totalPenetrationDepth;
            this.averagePenetrationDepth = averagePenetrationDepth;
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
        
        public double getIntersectedPercentage() {
            return totalTetrahedra > 0 ? (double) (insideTetrahedra + intersectingTetrahedra) / totalTetrahedra * 100.0 : 0.0;
        }
        
        @Override
        public String toString() {
            return String.format("TetConvexHullIntersectionStats[total=%d, inside=%d(%.1f%%), intersecting=%d(%.1f%%), outside=%d(%.1f%%), intersected=%.1f%%, avg_penetration=%.3f]",
                               totalTetrahedra, insideTetrahedra, getInsidePercentage(), 
                               intersectingTetrahedra, getIntersectingPercentage(),
                               outsideTetrahedra, getOutsidePercentage(), getIntersectedPercentage(), averagePenetrationDepth);
        }
    }

    /**
     * Batch processing for multiple convex hull intersection queries
     * Useful for multiple simultaneous convex hull queries in tetrahedral space
     * 
     * @param convexHulls list of convex hulls to test
     * @param tetree the tetree to search in
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @param strategy aggregation strategy for multiple simplicies
     * @return map of convex hulls to their intersection results
     * @throws IllegalArgumentException if reference point has negative coordinates
     */
    public static <Content> Map<TetConvexHull, List<TetConvexHullIntersection<Content>>> 
            batchConvexHullIntersections(List<TetConvexHull> convexHulls, Tetree<Content> tetree, 
                                       Point3f referencePoint, SimplexAggregationStrategy strategy) {
        
        validatePositiveCoordinates(referencePoint);
        
        return convexHulls.stream()
            .collect(Collectors.toMap(
                hull -> hull,
                hull -> convexHullIntersectedAll(hull, tetree, referencePoint, strategy)
            ));
    }

    // Helper methods

    /**
     * Test convex hull-tetrahedron intersection using separating axis theorem adapted for tetrahedra
     * 
     * @param convexHull the convex hull
     * @param tetrahedron the tetrahedron to test
     * @return intersection type
     */
    private static IntersectionType testConvexHullTetrahedronIntersection(TetConvexHull convexHull, Tet tetrahedron) {
        // Get all 4 vertices of the tetrahedron
        Point3i[] tetVertices = tetrahedron.coordinates();
        Point3f[] tetVerticesF = new Point3f[4];
        for (int i = 0; i < 4; i++) {
            tetVerticesF[i] = new Point3f(tetVertices[i].x, tetVertices[i].y, tetVertices[i].z);
        }
        
        // Test if all vertices are inside the convex hull
        boolean allInside = true;
        boolean anyInside = false;
        
        for (Point3f vertex : tetVerticesF) {
            boolean vertexInside = convexHull.containsPoint(vertex);
            if (!vertexInside) {
                allInside = false;
            } else {
                anyInside = true;
            }
        }
        
        if (allInside) {
            return IntersectionType.COMPLETELY_INSIDE;
        }
        
        // If no vertices are inside, check if hull intersects tetrahedron faces
        if (!anyInside) {
            // Test if any plane of the convex hull intersects the tetrahedron
            for (Plane3D plane : convexHull.planes) {
                if (planeIntersectsTetrahedron(plane, tetrahedron)) {
                    return IntersectionType.INTERSECTING;
                }
            }
            return IntersectionType.COMPLETELY_OUTSIDE;
        }
        
        // Some vertices inside, some outside = intersecting
        return IntersectionType.INTERSECTING;
    }

    /**
     * Calculate penetration depth of tetrahedron into convex hull
     */
    private static float calculatePenetrationDepth(TetConvexHull convexHull, Tet tetrahedron) {
        Point3f tetCenter = tetrahedronCenter(tetrahedron);
        float distanceToHull = convexHull.distanceToPoint(tetCenter);
        
        if (distanceToHull < 0) {
            // Tetrahedron center is inside hull
            return Math.abs(distanceToHull);
        } else {
            // Tetrahedron center is outside hull - check if any part of tetrahedron is inside
            Point3i[] vertices = tetrahedron.coordinates();
            float maxPenetration = 0.0f;
            
            for (Point3i vertex : vertices) {
                Point3f vertexF = new Point3f(vertex.x, vertex.y, vertex.z);
                float vertexDistance = convexHull.distanceToPoint(vertexF);
                if (vertexDistance < 0) {
                    maxPenetration = Math.max(maxPenetration, Math.abs(vertexDistance));
                }
            }
            
            return maxPenetration;
        }
    }

    /**
     * Create spatial bounds around the convex hull for efficient querying
     */
    private static Spatial createConvexHullBounds(TetConvexHull convexHull) {
        // Create a sphere around the convex hull centroid for initial filtering
        float searchRadius = convexHull.boundingRadius * 1.1f; // Add 10% margin
        return new Spatial.Sphere(convexHull.centroid.x, convexHull.centroid.y, convexHull.centroid.z, searchRadius);
    }

    /**
     * Test if a plane intersects with a tetrahedron
     */
    private static boolean planeIntersectsTetrahedron(Plane3D plane, Tet tetrahedron) {
        Point3i[] vertices = tetrahedron.coordinates();
        
        // Check if vertices are on different sides of the plane
        boolean hasPositive = false;
        boolean hasNegative = false;
        
        for (Point3i vertex : vertices) {
            Point3f vertexF = new Point3f(vertex.x, vertex.y, vertex.z);
            float distance = plane.distanceToPoint(vertexF);
            
            if (distance > GEOMETRIC_TOLERANCE) {
                hasPositive = true;
            } else if (distance < -GEOMETRIC_TOLERANCE) {
                hasNegative = true;
            }
            
            // If we have vertices on both sides, the plane intersects the tetrahedron
            if (hasPositive && hasNegative) {
                return true;
            }
        }
        
        return false; // All vertices are on the same side of the plane
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