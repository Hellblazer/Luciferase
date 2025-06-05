package com.hellblazer.luciferase.lucien;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Sphere intersection search implementation for Tetree
 * Finds tetrahedra that intersect with 3D spheres, with results ordered by distance from reference point
 * All operations are constrained to positive coordinates only, as required by tetrahedral SFC
 * 
 * @author hal.hildebrand
 */
public class TetSphereIntersectionSearch extends TetrahedralSearchBase {

    /**
     * Sphere intersection result with distance information for tetrahedral space
     */
    public static class SphereIntersection<Content> {
        public final long index;
        public final Content content;
        public final Tet tetrahedron;
        public final float distanceToReferencePoint;
        public final Point3f tetrahedronCenter;
        public final IntersectionType intersectionType;

        public SphereIntersection(long index, Content content, Tet tetrahedron, 
                                float distanceToReferencePoint, Point3f tetrahedronCenter, IntersectionType intersectionType) {
            this.index = index;
            this.content = content;
            this.tetrahedron = tetrahedron;
            this.distanceToReferencePoint = distanceToReferencePoint;
            this.tetrahedronCenter = tetrahedronCenter;
            this.intersectionType = intersectionType;
        }
    }

    /**
     * Type of intersection between sphere and tetrahedron
     */
    public enum IntersectionType {
        COMPLETELY_INSIDE,  // Tetrahedron is completely inside sphere
        INTERSECTING,       // Tetrahedron partially intersects sphere
        COMPLETELY_OUTSIDE  // Tetrahedron is completely outside sphere (not returned in intersection results)
    }

    /**
     * Find all tetrahedra that intersect with the sphere, ordered by distance from reference point
     * 
     * @param sphereCenter center of the sphere (positive coordinates only)
     * @param sphereRadius radius of the sphere (positive)
     * @param tetree the tetree to search in
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @return list of intersections sorted by distance from reference point (closest first)
     * @throws IllegalArgumentException if any coordinate is negative or radius is non-positive
     */
    public static <Content> List<SphereIntersection<Content>> sphereIntersectedAll(
            Point3f sphereCenter, float sphereRadius, Tetree<Content> tetree, Point3f referencePoint) {
        
        return sphereIntersectedAll(sphereCenter, sphereRadius, tetree, referencePoint, SimplexAggregationStrategy.ALL_SIMPLICIES);
    }

    /**
     * Find all tetrahedra that intersect with the sphere, with configurable simplex aggregation
     * 
     * @param sphereCenter center of the sphere (positive coordinates only)
     * @param sphereRadius radius of the sphere (positive)
     * @param tetree the tetree to search in
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @param strategy how to aggregate multiple simplicies per spatial region
     * @return list of intersections sorted by distance from reference point (closest first)
     * @throws IllegalArgumentException if any coordinate is negative or radius is non-positive
     */
    public static <Content> List<SphereIntersection<Content>> sphereIntersectedAll(
            Point3f sphereCenter, float sphereRadius, Tetree<Content> tetree, Point3f referencePoint,
            SimplexAggregationStrategy strategy) {
        
        validatePositiveCoordinates(sphereCenter);
        validatePositiveCoordinates(referencePoint);
        
        if (sphereRadius <= 0) {
            throw new IllegalArgumentException("Sphere radius must be positive, got: " + sphereRadius);
        }

        // Create spatial volume for sphere to constrain search
        Spatial.aabb sphereBounds = createSphereBounds(sphereCenter, sphereRadius);
        
        // Use tetree spatial query to get relevant simplicies
        Stream<Tetree.Simplex<Content>> candidateSimplicies = tetree.boundedBy(sphereBounds);
        
        // Apply AABB prefiltering and then detailed intersection test
        Stream<Tetree.Simplex<Content>> intersectingSimplicies = candidateSimplicies
            .filter(simplex -> prefilterAABB(simplex.index(), sphereCenter, sphereRadius))
            .filter(simplex -> tetrahedronIntersectsSphere(simplex.index(), sphereCenter, sphereRadius) != IntersectionType.COMPLETELY_OUTSIDE);

        // Apply simplex aggregation strategy
        List<Tetree.Simplex<Content>> aggregatedSimplicies = aggregateSimplicies(intersectingSimplicies, strategy);
        
        // Convert to intersection results with distance information
        List<SphereIntersection<Content>> intersections = aggregatedSimplicies.stream()
            .map(simplex -> {
                IntersectionType intersectionType = tetrahedronIntersectsSphere(simplex.index(), sphereCenter, sphereRadius);
                Point3f tetCenter = tetrahedronCenter(simplex.index());
                float distance = calculateDistance(referencePoint, tetCenter);
                
                return new SphereIntersection<>(
                    simplex.index(), 
                    simplex.cell(), 
                    Tet.tetrahedron(simplex.index()),
                    distance,
                    tetCenter,
                    intersectionType
                );
            })
            .collect(Collectors.toList());

        // Sort by distance from reference point
        intersections.sort(Comparator.comparing(si -> si.distanceToReferencePoint));
        
        return intersections;
    }

    /**
     * Find the first (closest to reference point) tetrahedron that intersects with the sphere
     * 
     * @param sphereCenter center of the sphere (positive coordinates only)
     * @param sphereRadius radius of the sphere (positive)
     * @param tetree the tetree to search in
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @return the closest intersection, or null if no intersection
     * @throws IllegalArgumentException if any coordinate is negative or radius is non-positive
     */
    public static <Content> SphereIntersection<Content> sphereIntersectedFirst(
            Point3f sphereCenter, float sphereRadius, Tetree<Content> tetree, Point3f referencePoint) {
        
        List<SphereIntersection<Content>> intersections = sphereIntersectedAll(sphereCenter, sphereRadius, tetree, referencePoint);
        return intersections.isEmpty() ? null : intersections.get(0);
    }

    /**
     * Find tetrahedra that are completely inside the sphere
     * 
     * @param sphereCenter center of the sphere (positive coordinates only)
     * @param sphereRadius radius of the sphere (positive)
     * @param tetree the tetree to search in
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @return list of intersections completely inside sphere, sorted by distance from reference point
     * @throws IllegalArgumentException if any coordinate is negative or radius is non-positive
     */
    public static <Content> List<SphereIntersection<Content>> tetrahedraCompletelyInside(
            Point3f sphereCenter, float sphereRadius, Tetree<Content> tetree, Point3f referencePoint) {
        
        return sphereIntersectedAll(sphereCenter, sphereRadius, tetree, referencePoint).stream()
            .filter(si -> si.intersectionType == IntersectionType.COMPLETELY_INSIDE)
            .collect(Collectors.toList());
    }

    /**
     * Find tetrahedra that partially intersect the sphere (not completely inside or outside)
     * 
     * @param sphereCenter center of the sphere (positive coordinates only)
     * @param sphereRadius radius of the sphere (positive)
     * @param tetree the tetree to search in
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @return list of intersections partially intersecting sphere, sorted by distance from reference point
     * @throws IllegalArgumentException if any coordinate is negative or radius is non-positive
     */
    public static <Content> List<SphereIntersection<Content>> tetrahedraPartiallyIntersecting(
            Point3f sphereCenter, float sphereRadius, Tetree<Content> tetree, Point3f referencePoint) {
        
        return sphereIntersectedAll(sphereCenter, sphereRadius, tetree, referencePoint).stream()
            .filter(si -> si.intersectionType == IntersectionType.INTERSECTING)
            .collect(Collectors.toList());
    }

    /**
     * Count the number of tetrahedra that intersect with the sphere
     * This is more efficient than getting all intersections when only count is needed
     * 
     * @param sphereCenter center of the sphere (positive coordinates only)
     * @param sphereRadius radius of the sphere (positive)
     * @param tetree the tetree to search in
     * @return number of tetrahedra intersecting the sphere
     * @throws IllegalArgumentException if any coordinate is negative or radius is non-positive
     */
    public static <Content> long countSphereIntersections(Point3f sphereCenter, float sphereRadius, Tetree<Content> tetree) {
        validatePositiveCoordinates(sphereCenter);
        
        if (sphereRadius <= 0) {
            throw new IllegalArgumentException("Sphere radius must be positive, got: " + sphereRadius);
        }

        Spatial.aabb sphereBounds = createSphereBounds(sphereCenter, sphereRadius);
        
        return tetree.boundedBy(sphereBounds)
            .filter(simplex -> prefilterAABB(simplex.index(), sphereCenter, sphereRadius))
            .filter(simplex -> tetrahedronIntersectsSphere(simplex.index(), sphereCenter, sphereRadius) != IntersectionType.COMPLETELY_OUTSIDE)
            .count();
    }

    /**
     * Test if any tetrahedron in the tetree intersects with the sphere
     * This is more efficient than getting all intersections when only existence check is needed
     * 
     * @param sphereCenter center of the sphere (positive coordinates only)
     * @param sphereRadius radius of the sphere (positive)
     * @param tetree the tetree to search in
     * @return true if any tetrahedron intersects the sphere
     * @throws IllegalArgumentException if any coordinate is negative or radius is non-positive
     */
    public static <Content> boolean hasAnyIntersection(Point3f sphereCenter, float sphereRadius, Tetree<Content> tetree) {
        validatePositiveCoordinates(sphereCenter);
        
        if (sphereRadius <= 0) {
            throw new IllegalArgumentException("Sphere radius must be positive, got: " + sphereRadius);
        }

        Spatial.aabb sphereBounds = createSphereBounds(sphereCenter, sphereRadius);
        
        return tetree.boundedBy(sphereBounds)
            .filter(simplex -> prefilterAABB(simplex.index(), sphereCenter, sphereRadius))
            .anyMatch(simplex -> tetrahedronIntersectsSphere(simplex.index(), sphereCenter, sphereRadius) != IntersectionType.COMPLETELY_OUTSIDE);
    }

    /**
     * Get statistics about sphere intersection results
     * 
     * @param sphereCenter center of the sphere (positive coordinates only)
     * @param sphereRadius radius of the sphere (positive)
     * @param tetree the tetree to search in
     * @return statistics about intersection results
     * @throws IllegalArgumentException if any coordinate is negative or radius is non-positive
     */
    public static <Content> IntersectionStatistics getSphereIntersectionStatistics(
            Point3f sphereCenter, float sphereRadius, Tetree<Content> tetree) {
        validatePositiveCoordinates(sphereCenter);
        
        if (sphereRadius <= 0) {
            throw new IllegalArgumentException("Sphere radius must be positive, got: " + sphereRadius);
        }

        Spatial.aabb sphereBounds = createSphereBounds(sphereCenter, sphereRadius);
        
        long totalTetrahedra = 0;
        long insideTetrahedra = 0;
        long intersectingTetrahedra = 0;
        long outsideTetrahedra = 0;

        List<Tetree.Simplex<Content>> candidateSimplicies = tetree.boundedBy(sphereBounds)
            .filter(simplex -> prefilterAABB(simplex.index(), sphereCenter, sphereRadius))
            .collect(Collectors.toList());

        for (var simplex : candidateSimplicies) {
            totalTetrahedra++;
            IntersectionType intersectionType = tetrahedronIntersectsSphere(simplex.index(), sphereCenter, sphereRadius);
            
            switch (intersectionType) {
                case COMPLETELY_INSIDE -> insideTetrahedra++;
                case INTERSECTING -> intersectingTetrahedra++;
                case COMPLETELY_OUTSIDE -> outsideTetrahedra++;
            }
        }

        return new IntersectionStatistics(totalTetrahedra, insideTetrahedra, intersectingTetrahedra, outsideTetrahedra);
    }

    /**
     * Statistics about sphere intersection results
     */
    public static class IntersectionStatistics {
        public final long totalTetrahedra;
        public final long insideTetrahedra;
        public final long intersectingTetrahedra;
        public final long outsideTetrahedra;
        
        public IntersectionStatistics(long totalTetrahedra, long insideTetrahedra, long intersectingTetrahedra, long outsideTetrahedra) {
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
        
        public double getIntersectedPercentage() {
            return totalTetrahedra > 0 ? (double) (insideTetrahedra + intersectingTetrahedra) / totalTetrahedra * 100.0 : 0.0;
        }
        
        @Override
        public String toString() {
            return String.format("TetSphereIntersectionStats[total=%d, inside=%d(%.1f%%), intersecting=%d(%.1f%%), outside=%d(%.1f%%), intersected=%.1f%%]",
                               totalTetrahedra, insideTetrahedra, getInsidePercentage(), 
                               intersectingTetrahedra, getIntersectingPercentage(),
                               outsideTetrahedra, getOutsidePercentage(), getIntersectedPercentage());
        }
    }

    /**
     * Batch processing for multiple sphere intersection queries
     * Useful for multiple simultaneous sphere queries
     * 
     * @param sphereQueries list of sphere queries (center, radius pairs)
     * @param tetree the tetree to search in
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @return map of sphere queries to their intersection results
     * @throws IllegalArgumentException if reference point has negative coordinates
     */
    public static <Content> Map<SphereQuery, List<SphereIntersection<Content>>> 
            batchSphereIntersections(List<SphereQuery> sphereQueries, Tetree<Content> tetree, Point3f referencePoint) {
        
        validatePositiveCoordinates(referencePoint);
        
        return sphereQueries.stream()
            .collect(Collectors.toMap(
                query -> query,
                query -> sphereIntersectedAll(query.center, query.radius, tetree, referencePoint)
            ));
    }

    /**
     * Represents a sphere query with center and radius
     */
    public static class SphereQuery {
        public final Point3f center;
        public final float radius;
        
        public SphereQuery(Point3f center, float radius) {
            validatePositiveCoordinates(center);
            if (radius <= 0) {
                throw new IllegalArgumentException("Sphere radius must be positive, got: " + radius);
            }
            this.center = new Point3f(center); // Defensive copy
            this.radius = radius;
        }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof SphereQuery)) return false;
            SphereQuery other = (SphereQuery) obj;
            return center.equals(other.center) && Float.compare(radius, other.radius) == 0;
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(center, radius);
        }
        
        @Override
        public String toString() {
            return String.format("SphereQuery[center=%s, radius=%.2f]", center, radius);
        }
    }

    /**
     * Test sphere-tetrahedron intersection using distance-based algorithm
     * 
     * @param tetIndex the tetrahedral SFC index
     * @param sphereCenter center of the sphere
     * @param sphereRadius radius of the sphere
     * @return intersection type
     */
    private static IntersectionType tetrahedronIntersectsSphere(long tetIndex, Point3f sphereCenter, float sphereRadius) {
        // Get minimum distance from sphere center to tetrahedron
        float distanceToTetrahedron = distanceToTetrahedron(sphereCenter, tetIndex);
        
        // No intersection if distance > radius
        if (distanceToTetrahedron > sphereRadius) {
            return IntersectionType.COMPLETELY_OUTSIDE;
        }
        
        // Check if tetrahedron is completely inside sphere
        // Calculate maximum distance from sphere center to any vertex of tetrahedron
        var tet = Tet.tetrahedron(tetIndex);
        var vertices = tet.coordinates();
        
        float maxDistanceSquared = 0;
        float radiusSquared = sphereRadius * sphereRadius;
        
        for (var vertex : vertices) {
            float dx = sphereCenter.x - vertex.x;
            float dy = sphereCenter.y - vertex.y;
            float dz = sphereCenter.z - vertex.z;
            float distanceSquared = dx * dx + dy * dy + dz * dz;
            maxDistanceSquared = Math.max(maxDistanceSquared, distanceSquared);
        }
        
        if (maxDistanceSquared <= radiusSquared) {
            return IntersectionType.COMPLETELY_INSIDE;
        }
        
        // Tetrahedron partially intersects sphere
        return IntersectionType.INTERSECTING;
    }

    /**
     * AABB prefiltering for performance optimization
     * Fast rejection test before expensive geometric intersection
     * 
     * @param tetIndex the tetrahedral SFC index
     * @param sphereCenter center of the sphere
     * @param sphereRadius radius of the sphere
     * @return true if tetrahedron's AABB intersects sphere's AABB
     */
    private static boolean prefilterAABB(long tetIndex, Point3f sphereCenter, float sphereRadius) {
        var tet = Tet.tetrahedron(tetIndex);
        var vertices = tet.coordinates();
        
        // Compute tetrahedron AABB
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = Float.MIN_VALUE, maxY = Float.MIN_VALUE, maxZ = Float.MIN_VALUE;
        
        for (var vertex : vertices) {
            minX = Math.min(minX, vertex.x);
            minY = Math.min(minY, vertex.y);
            minZ = Math.min(minZ, vertex.z);
            maxX = Math.max(maxX, vertex.x);
            maxY = Math.max(maxY, vertex.y);
            maxZ = Math.max(maxZ, vertex.z);
        }
        
        // Sphere AABB
        float sphereMinX = sphereCenter.x - sphereRadius;
        float sphereMinY = sphereCenter.y - sphereRadius;
        float sphereMinZ = sphereCenter.z - sphereRadius;
        float sphereMaxX = sphereCenter.x + sphereRadius;
        float sphereMaxY = sphereCenter.y + sphereRadius;
        float sphereMaxZ = sphereCenter.z + sphereRadius;
        
        // AABB intersection test
        return !(maxX < sphereMinX || minX > sphereMaxX ||
                 maxY < sphereMinY || minY > sphereMaxY ||
                 maxZ < sphereMinZ || minZ > sphereMaxZ);
    }

    /**
     * Create AABB bounds for a sphere to constrain spatial queries
     * 
     * @param sphereCenter center of the sphere
     * @param sphereRadius radius of the sphere
     * @return AABB bounds that contain the sphere
     */
    private static Spatial.aabb createSphereBounds(Point3f sphereCenter, float sphereRadius) {
        return new Spatial.aabb(
            sphereCenter.x - sphereRadius,
            sphereCenter.y - sphereRadius,
            sphereCenter.z - sphereRadius,
            sphereCenter.x + sphereRadius,
            sphereCenter.y + sphereRadius,
            sphereCenter.z + sphereRadius
        );
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