package com.hellblazer.luciferase.lucien;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import javax.vecmath.Vector3f;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Axis-Aligned Bounding Box (AABB) intersection search implementation for Tetree
 * Finds tetrahedra that intersect with 3D AABBs, with results ordered by distance from reference point
 * All operations are constrained to positive coordinates only, as required by tetrahedral SFC
 * 
 * @author hal.hildebrand
 */
public class TetAABBIntersectionSearch extends TetrahedralSearchBase {

    /**
     * AABB intersection result with distance information for tetrahedral space
     */
    public static class AABBIntersection<Content> {
        public final long index;
        public final Content content;
        public final Tet tetrahedron;
        public final float distanceToReferencePoint;
        public final Point3f tetrahedronCenter;
        public final IntersectionType intersectionType;

        public AABBIntersection(long index, Content content, Tet tetrahedron, 
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
     * Type of intersection between AABB and tetrahedron
     */
    public enum IntersectionType {
        COMPLETELY_INSIDE,  // Tetrahedron is completely inside AABB
        INTERSECTING,       // Tetrahedron partially intersects AABB
        CONTAINS_AABB,      // Tetrahedron completely contains AABB
        COMPLETELY_OUTSIDE  // Tetrahedron is completely outside AABB (not returned in intersection results)
    }

    /**
     * Represents an axis-aligned bounding box with positive coordinates
     */
    public static class AABB {
        public final float minX, minY, minZ;
        public final float maxX, maxY, maxZ;
        
        public AABB(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
            if (minX < 0 || minY < 0 || minZ < 0 || maxX < 0 || maxY < 0 || maxZ < 0) {
                throw new IllegalArgumentException("All AABB coordinates must be positive");
            }
            if (maxX <= minX || maxY <= minY || maxZ <= minZ) {
                throw new IllegalArgumentException("Max coordinates must be greater than min coordinates");
            }
            
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }
        
        /**
         * Create AABB from center point and half-extents
         */
        public static AABB fromCenterAndHalfExtents(Point3f center, float halfWidth, float halfHeight, float halfDepth) {
            validatePositiveCoordinates(center);
            if (halfWidth <= 0 || halfHeight <= 0 || halfDepth <= 0) {
                throw new IllegalArgumentException("Half-extents must be positive");
            }
            
            return new AABB(
                center.x - halfWidth, center.y - halfHeight, center.z - halfDepth,
                center.x + halfWidth, center.y + halfHeight, center.z + halfDepth
            );
        }
        
        /**
         * Create AABB from two corner points
         */
        public static AABB fromCorners(Point3f corner1, Point3f corner2) {
            validatePositiveCoordinates(corner1);
            validatePositiveCoordinates(corner2);
            
            float minX = Math.min(corner1.x, corner2.x);
            float minY = Math.min(corner1.y, corner2.y);
            float minZ = Math.min(corner1.z, corner2.z);
            float maxX = Math.max(corner1.x, corner2.x);
            float maxY = Math.max(corner1.y, corner2.y);
            float maxZ = Math.max(corner1.z, corner2.z);
            
            return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
        }
        
        public Point3f getCenter() {
            return new Point3f(
                (minX + maxX) / 2.0f,
                (minY + maxY) / 2.0f,
                (minZ + maxZ) / 2.0f
            );
        }
        
        public float getWidth() { return maxX - minX; }
        public float getHeight() { return maxY - minY; }
        public float getDepth() { return maxZ - minZ; }
        
        public float getVolume() {
            return getWidth() * getHeight() * getDepth();
        }
        
        /**
         * Convert to Spatial.aabb for tetree spatial queries
         */
        public Spatial.aabb toSpatialAABB() {
            return new Spatial.aabb(minX, minY, minZ, maxX, maxY, maxZ);
        }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof AABB)) return false;
            AABB other = (AABB) obj;
            return Float.compare(minX, other.minX) == 0 &&
                   Float.compare(minY, other.minY) == 0 &&
                   Float.compare(minZ, other.minZ) == 0 &&
                   Float.compare(maxX, other.maxX) == 0 &&
                   Float.compare(maxY, other.maxY) == 0 &&
                   Float.compare(maxZ, other.maxZ) == 0;
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(minX, minY, minZ, maxX, maxY, maxZ);
        }
        
        @Override
        public String toString() {
            return String.format("AABB[min=(%.2f,%.2f,%.2f), max=(%.2f,%.2f,%.2f)]", 
                               minX, minY, minZ, maxX, maxY, maxZ);
        }
    }

    /**
     * Find all tetrahedra that intersect with the AABB, ordered by distance from reference point
     * 
     * @param aabb the axis-aligned bounding box to test intersection with
     * @param tetree the tetree to search in
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @return list of intersections sorted by distance from reference point (closest first)
     * @throws IllegalArgumentException if reference point has negative coordinates
     */
    public static <Content> List<AABBIntersection<Content>> aabbIntersectedAll(
            AABB aabb, Tetree<Content> tetree, Point3f referencePoint) {
        
        return aabbIntersectedAll(aabb, tetree, referencePoint, SimplexAggregationStrategy.ALL_SIMPLICIES);
    }

    /**
     * Find all tetrahedra that intersect with the AABB, with configurable simplex aggregation
     * 
     * @param aabb the axis-aligned bounding box to test intersection with
     * @param tetree the tetree to search in
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @param strategy how to aggregate multiple simplicies per spatial region
     * @return list of intersections sorted by distance from reference point (closest first)
     * @throws IllegalArgumentException if reference point has negative coordinates
     */
    public static <Content> List<AABBIntersection<Content>> aabbIntersectedAll(
            AABB aabb, Tetree<Content> tetree, Point3f referencePoint,
            SimplexAggregationStrategy strategy) {
        
        validatePositiveCoordinates(referencePoint);
        
        // Use tetree spatial query to get relevant simplicies
        Stream<Tetree.Simplex<Content>> candidateSimplicies = tetree.boundedBy(aabb.toSpatialAABB());
        
        // Apply AABB prefiltering and then detailed intersection test
        Stream<Tetree.Simplex<Content>> intersectingSimplicies = candidateSimplicies
            .filter(simplex -> prefilterAABB(simplex.index(), aabb))
            .filter(simplex -> tetrahedronIntersectsAABB(simplex.index(), aabb) != IntersectionType.COMPLETELY_OUTSIDE);

        // Apply simplex aggregation strategy
        List<Tetree.Simplex<Content>> aggregatedSimplicies = aggregateSimplicies(intersectingSimplicies, strategy);
        
        // Convert to intersection results with distance information
        List<AABBIntersection<Content>> intersections = aggregatedSimplicies.stream()
            .map(simplex -> {
                IntersectionType intersectionType = tetrahedronIntersectsAABB(simplex.index(), aabb);
                Point3f tetCenter = tetrahedronCenter(simplex.index());
                float distance = calculateDistance(referencePoint, tetCenter);
                
                return new AABBIntersection<>(
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
        intersections.sort(Comparator.comparing(ai -> ai.distanceToReferencePoint));
        
        return intersections;
    }

    /**
     * Find the first (closest to reference point) tetrahedron that intersects with the AABB
     * 
     * @param aabb the axis-aligned bounding box to test intersection with
     * @param tetree the tetree to search in
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @return the closest intersection, or null if no intersection
     * @throws IllegalArgumentException if reference point has negative coordinates
     */
    public static <Content> AABBIntersection<Content> aabbIntersectedFirst(
            AABB aabb, Tetree<Content> tetree, Point3f referencePoint) {
        
        List<AABBIntersection<Content>> intersections = aabbIntersectedAll(aabb, tetree, referencePoint);
        return intersections.isEmpty() ? null : intersections.get(0);
    }

    /**
     * Find tetrahedra that are completely inside the AABB
     * 
     * @param aabb the axis-aligned bounding box to test intersection with
     * @param tetree the tetree to search in
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @return list of intersections completely inside AABB, sorted by distance from reference point
     * @throws IllegalArgumentException if reference point has negative coordinates
     */
    public static <Content> List<AABBIntersection<Content>> tetrahedraCompletelyInside(
            AABB aabb, Tetree<Content> tetree, Point3f referencePoint) {
        
        return aabbIntersectedAll(aabb, tetree, referencePoint).stream()
            .filter(ai -> ai.intersectionType == IntersectionType.COMPLETELY_INSIDE)
            .collect(Collectors.toList());
    }

    /**
     * Find tetrahedra that partially intersect the AABB (not completely inside or outside)
     * 
     * @param aabb the axis-aligned bounding box to test intersection with
     * @param tetree the tetree to search in
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @return list of intersections partially intersecting AABB, sorted by distance from reference point
     * @throws IllegalArgumentException if reference point has negative coordinates
     */
    public static <Content> List<AABBIntersection<Content>> tetrahedraPartiallyIntersecting(
            AABB aabb, Tetree<Content> tetree, Point3f referencePoint) {
        
        return aabbIntersectedAll(aabb, tetree, referencePoint).stream()
            .filter(ai -> ai.intersectionType == IntersectionType.INTERSECTING)
            .collect(Collectors.toList());
    }

    /**
     * Find tetrahedra that completely contain the AABB
     * 
     * @param aabb the axis-aligned bounding box to test intersection with
     * @param tetree the tetree to search in
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @return list of intersections containing AABB, sorted by distance from reference point
     * @throws IllegalArgumentException if reference point has negative coordinates
     */
    public static <Content> List<AABBIntersection<Content>> tetrahedraContainingAABB(
            AABB aabb, Tetree<Content> tetree, Point3f referencePoint) {
        
        return aabbIntersectedAll(aabb, tetree, referencePoint).stream()
            .filter(ai -> ai.intersectionType == IntersectionType.CONTAINS_AABB)
            .collect(Collectors.toList());
    }

    /**
     * Count the number of tetrahedra that intersect with the AABB
     * This is more efficient than getting all intersections when only count is needed
     * 
     * @param aabb the axis-aligned bounding box to test intersection with
     * @param tetree the tetree to search in
     * @return number of tetrahedra intersecting the AABB
     */
    public static <Content> long countAABBIntersections(AABB aabb, Tetree<Content> tetree) {
        return tetree.boundedBy(aabb.toSpatialAABB())
            .filter(simplex -> prefilterAABB(simplex.index(), aabb))
            .filter(simplex -> tetrahedronIntersectsAABB(simplex.index(), aabb) != IntersectionType.COMPLETELY_OUTSIDE)
            .count();
    }

    /**
     * Test if any tetrahedron in the tetree intersects with the AABB
     * This is more efficient than getting all intersections when only existence check is needed
     * 
     * @param aabb the axis-aligned bounding box to test intersection with
     * @param tetree the tetree to search in
     * @return true if any tetrahedron intersects the AABB
     */
    public static <Content> boolean hasAnyIntersection(AABB aabb, Tetree<Content> tetree) {
        return tetree.boundedBy(aabb.toSpatialAABB())
            .filter(simplex -> prefilterAABB(simplex.index(), aabb))
            .anyMatch(simplex -> tetrahedronIntersectsAABB(simplex.index(), aabb) != IntersectionType.COMPLETELY_OUTSIDE);
    }

    /**
     * Get statistics about AABB intersection results
     * 
     * @param aabb the axis-aligned bounding box to test intersection with
     * @param tetree the tetree to search in
     * @return statistics about intersection results
     */
    public static <Content> IntersectionStatistics getAABBIntersectionStatistics(
            AABB aabb, Tetree<Content> tetree) {
        
        long totalTetrahedra = 0;
        long insideTetrahedra = 0;
        long intersectingTetrahedra = 0;
        long containingTetrahedra = 0;
        long outsideTetrahedra = 0;

        List<Tetree.Simplex<Content>> candidateSimplicies = tetree.boundedBy(aabb.toSpatialAABB())
            .filter(simplex -> prefilterAABB(simplex.index(), aabb))
            .collect(Collectors.toList());

        for (var simplex : candidateSimplicies) {
            totalTetrahedra++;
            IntersectionType intersectionType = tetrahedronIntersectsAABB(simplex.index(), aabb);
            
            switch (intersectionType) {
                case COMPLETELY_INSIDE -> insideTetrahedra++;
                case INTERSECTING -> intersectingTetrahedra++;
                case CONTAINS_AABB -> containingTetrahedra++;
                case COMPLETELY_OUTSIDE -> outsideTetrahedra++;
            }
        }

        return new IntersectionStatistics(totalTetrahedra, insideTetrahedra, intersectingTetrahedra, containingTetrahedra, outsideTetrahedra);
    }

    /**
     * Statistics about AABB intersection results
     */
    public static class IntersectionStatistics {
        public final long totalTetrahedra;
        public final long insideTetrahedra;
        public final long intersectingTetrahedra;
        public final long containingTetrahedra;
        public final long outsideTetrahedra;
        
        public IntersectionStatistics(long totalTetrahedra, long insideTetrahedra, long intersectingTetrahedra, 
                                    long containingTetrahedra, long outsideTetrahedra) {
            this.totalTetrahedra = totalTetrahedra;
            this.insideTetrahedra = insideTetrahedra;
            this.intersectingTetrahedra = intersectingTetrahedra;
            this.containingTetrahedra = containingTetrahedra;
            this.outsideTetrahedra = outsideTetrahedra;
        }
        
        public double getInsidePercentage() {
            return totalTetrahedra > 0 ? (double) insideTetrahedra / totalTetrahedra * 100.0 : 0.0;
        }
        
        public double getIntersectingPercentage() {
            return totalTetrahedra > 0 ? (double) intersectingTetrahedra / totalTetrahedra * 100.0 : 0.0;
        }
        
        public double getContainingPercentage() {
            return totalTetrahedra > 0 ? (double) containingTetrahedra / totalTetrahedra * 100.0 : 0.0;
        }
        
        public double getOutsidePercentage() {
            return totalTetrahedra > 0 ? (double) outsideTetrahedra / totalTetrahedra * 100.0 : 0.0;
        }
        
        public double getIntersectedPercentage() {
            return totalTetrahedra > 0 ? (double) (insideTetrahedra + intersectingTetrahedra + containingTetrahedra) / totalTetrahedra * 100.0 : 0.0;
        }
        
        @Override
        public String toString() {
            return String.format("TetAABBIntersectionStats[total=%d, inside=%d(%.1f%%), intersecting=%d(%.1f%%), containing=%d(%.1f%%), outside=%d(%.1f%%), intersected=%.1f%%]",
                               totalTetrahedra, insideTetrahedra, getInsidePercentage(), 
                               intersectingTetrahedra, getIntersectingPercentage(),
                               containingTetrahedra, getContainingPercentage(),
                               outsideTetrahedra, getOutsidePercentage(), getIntersectedPercentage());
        }
    }

    /**
     * Batch processing for multiple AABB intersection queries
     * Useful for multiple simultaneous AABB queries
     * 
     * @param aabbs list of AABBs to test intersection with
     * @param tetree the tetree to search in
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @return map of AABBs to their intersection results
     * @throws IllegalArgumentException if reference point has negative coordinates
     */
    public static <Content> Map<AABB, List<AABBIntersection<Content>>> 
            batchAABBIntersections(List<AABB> aabbs, Tetree<Content> tetree, Point3f referencePoint) {
        
        validatePositiveCoordinates(referencePoint);
        
        return aabbs.stream()
            .collect(Collectors.toMap(
                aabb -> aabb,
                aabb -> aabbIntersectedAll(aabb, tetree, referencePoint)
            ));
    }

    /**
     * Test AABB-tetrahedron intersection using separating axis theorem (SAT)
     * 
     * @param tetIndex the tetrahedral SFC index
     * @param aabb the axis-aligned bounding box
     * @return intersection type
     */
    private static IntersectionType tetrahedronIntersectsAABB(long tetIndex, AABB aabb) {
        var tet = Tet.tetrahedron(tetIndex);
        var vertices = tet.coordinates();
        
        // First check if all tetrahedron vertices are inside AABB
        boolean allVerticesInside = true;
        boolean anyVertexInside = false;
        
        for (var vertex : vertices) {
            boolean vertexInside = (vertex.x >= aabb.minX && vertex.x <= aabb.maxX &&
                                  vertex.y >= aabb.minY && vertex.y <= aabb.maxY &&
                                  vertex.z >= aabb.minZ && vertex.z <= aabb.maxZ);
            
            if (vertexInside) {
                anyVertexInside = true;
            } else {
                allVerticesInside = false;
            }
        }
        
        if (allVerticesInside) {
            return IntersectionType.COMPLETELY_INSIDE;
        }
        
        // Check if AABB is completely inside tetrahedron
        if (isAABBCompletelyInTetrahedron(aabb, vertices)) {
            return IntersectionType.CONTAINS_AABB;
        }
        
        // Check for intersection using separating axis theorem
        if (anyVertexInside || tetrahedronAABBIntersect(vertices, aabb)) {
            return IntersectionType.INTERSECTING;
        }
        
        return IntersectionType.COMPLETELY_OUTSIDE;
    }

    /**
     * Check if AABB is completely inside tetrahedron
     */
    private static boolean isAABBCompletelyInTetrahedron(AABB aabb, Point3i[] vertices) {
        // Test all 8 corners of AABB against tetrahedron
        Point3f[] aabbCorners = {
            new Point3f(aabb.minX, aabb.minY, aabb.minZ),
            new Point3f(aabb.maxX, aabb.minY, aabb.minZ),
            new Point3f(aabb.minX, aabb.maxY, aabb.minZ),
            new Point3f(aabb.maxX, aabb.maxY, aabb.minZ),
            new Point3f(aabb.minX, aabb.minY, aabb.maxZ),
            new Point3f(aabb.maxX, aabb.minY, aabb.maxZ),
            new Point3f(aabb.minX, aabb.maxY, aabb.maxZ),
            new Point3f(aabb.maxX, aabb.maxY, aabb.maxZ)
        };
        
        for (Point3f corner : aabbCorners) {
            if (!isPointInTetrahedron(corner, vertices)) {
                return false;
            }
        }
        
        return true;
    }

    /**
     * Check if point is inside tetrahedron using barycentric coordinates
     */
    private static boolean isPointInTetrahedron(Point3f point, Point3i[] vertices) {
        // Convert to float for calculations
        Point3f v0 = new Point3f(vertices[0].x, vertices[0].y, vertices[0].z);
        Point3f v1 = new Point3f(vertices[1].x, vertices[1].y, vertices[1].z);
        Point3f v2 = new Point3f(vertices[2].x, vertices[2].y, vertices[2].z);
        Point3f v3 = new Point3f(vertices[3].x, vertices[3].y, vertices[3].z);
        
        // Use determinant method for point-in-tetrahedron test
        float d0 = signedVolumeOfTetrahedron(point, v1, v2, v3);
        float d1 = signedVolumeOfTetrahedron(v0, point, v2, v3);
        float d2 = signedVolumeOfTetrahedron(v0, v1, point, v3);
        float d3 = signedVolumeOfTetrahedron(v0, v1, v2, point);
        
        // Point is inside if all have the same sign
        boolean hasNeg = (d0 < 0) || (d1 < 0) || (d2 < 0) || (d3 < 0);
        boolean hasPos = (d0 > 0) || (d1 > 0) || (d2 > 0) || (d3 > 0);
        
        return !(hasNeg && hasPos);
    }

    /**
     * Calculate signed volume of tetrahedron
     */
    private static float signedVolumeOfTetrahedron(Point3f a, Point3f b, Point3f c, Point3f d) {
        return (1.0f / 6.0f) * ((b.x - a.x) * ((c.y - a.y) * (d.z - a.z) - (c.z - a.z) * (d.y - a.y)) -
                                (b.y - a.y) * ((c.x - a.x) * (d.z - a.z) - (c.z - a.z) * (d.x - a.x)) +
                                (b.z - a.z) * ((c.x - a.x) * (d.y - a.y) - (c.y - a.y) * (d.x - a.x)));
    }

    /**
     * Test tetrahedron-AABB intersection using separating axis theorem
     */
    private static boolean tetrahedronAABBIntersect(Point3i[] tetVertices, AABB aabb) {
        // Convert vertices to float
        Point3f[] vertices = new Point3f[4];
        for (int i = 0; i < 4; i++) {
            vertices[i] = new Point3f(tetVertices[i].x, tetVertices[i].y, tetVertices[i].z);
        }
        
        // Test separation on coordinate axes
        if (!testAxisSeparation(vertices, aabb, 1, 0, 0)) return false; // X-axis
        if (!testAxisSeparation(vertices, aabb, 0, 1, 0)) return false; // Y-axis
        if (!testAxisSeparation(vertices, aabb, 0, 0, 1)) return false; // Z-axis
        
        // Test separation on tetrahedron face normals
        for (int i = 0; i < 4; i++) {
            Point3f[] face = getTetrahedronFace(vertices, i);
            Point3f normal = calculateFaceNormal(face[0], face[1], face[2]);
            if (!testAxisSeparation(vertices, aabb, normal.x, normal.y, normal.z)) return false;
        }
        
        // Test separation on cross products of edges
        Point3f[] aabbVertices = getAABBVertices(aabb);
        for (int i = 0; i < 4; i++) {
            for (int j = i + 1; j < 4; j++) {
                Point3f tetEdge = new Point3f();
                tetEdge.sub(vertices[j], vertices[i]);
                
                for (int k = 0; k < 3; k++) {
                    Vector3f aabbEdge = getAABBEdge(k);
                    Vector3f tetEdgeVec = new Vector3f(tetEdge.x, tetEdge.y, tetEdge.z);
                    Vector3f cross = new Vector3f();
                    cross.cross(tetEdgeVec, aabbEdge);
                    
                    if (cross.lengthSquared() > GEOMETRIC_TOLERANCE) {
                        cross.normalize();
                        if (!testAxisSeparation(vertices, aabb, cross.x, cross.y, cross.z)) return false;
                    }
                }
            }
        }
        
        return true; // No separating axis found, shapes intersect
    }

    /**
     * Test separation along a given axis
     */
    private static boolean testAxisSeparation(Point3f[] tetVertices, AABB aabb, float axisX, float axisY, float axisZ) {
        // Project tetrahedron onto axis
        float tetMin = Float.MAX_VALUE;
        float tetMax = Float.MIN_VALUE;
        
        for (Point3f vertex : tetVertices) {
            float projection = vertex.x * axisX + vertex.y * axisY + vertex.z * axisZ;
            tetMin = Math.min(tetMin, projection);
            tetMax = Math.max(tetMax, projection);
        }
        
        // Project AABB onto axis
        Point3f[] aabbVertices = getAABBVertices(aabb);
        float aabbMin = Float.MAX_VALUE;
        float aabbMax = Float.MIN_VALUE;
        
        for (Point3f vertex : aabbVertices) {
            float projection = vertex.x * axisX + vertex.y * axisY + vertex.z * axisZ;
            aabbMin = Math.min(aabbMin, projection);
            aabbMax = Math.max(aabbMax, projection);
        }
        
        // Check for separation
        return !(tetMax < aabbMin || aabbMax < tetMin);
    }

    /**
     * Get vertices of AABB
     */
    private static Point3f[] getAABBVertices(AABB aabb) {
        return new Point3f[] {
            new Point3f(aabb.minX, aabb.minY, aabb.minZ),
            new Point3f(aabb.maxX, aabb.minY, aabb.minZ),
            new Point3f(aabb.minX, aabb.maxY, aabb.minZ),
            new Point3f(aabb.maxX, aabb.maxY, aabb.minZ),
            new Point3f(aabb.minX, aabb.minY, aabb.maxZ),
            new Point3f(aabb.maxX, aabb.minY, aabb.maxZ),
            new Point3f(aabb.minX, aabb.maxY, aabb.maxZ),
            new Point3f(aabb.maxX, aabb.maxY, aabb.maxZ)
        };
    }

    /**
     * Get edge direction for AABB
     */
    private static Vector3f getAABBEdge(int index) {
        return switch (index) {
            case 0 -> new Vector3f(1, 0, 0); // X-axis
            case 1 -> new Vector3f(0, 1, 0); // Y-axis
            case 2 -> new Vector3f(0, 0, 1); // Z-axis
            default -> throw new IllegalArgumentException("Invalid edge index");
        };
    }

    /**
     * Get face vertices of tetrahedron
     */
    private static Point3f[] getTetrahedronFace(Point3f[] vertices, int faceIndex) {
        return switch (faceIndex) {
            case 0 -> new Point3f[]{vertices[0], vertices[1], vertices[2]};
            case 1 -> new Point3f[]{vertices[0], vertices[1], vertices[3]};
            case 2 -> new Point3f[]{vertices[0], vertices[2], vertices[3]};
            case 3 -> new Point3f[]{vertices[1], vertices[2], vertices[3]};
            default -> throw new IllegalArgumentException("Invalid face index");
        };
    }

    /**
     * Calculate face normal
     */
    private static Point3f calculateFaceNormal(Point3f v0, Point3f v1, Point3f v2) {
        Vector3f edge1 = new Vector3f();
        edge1.sub(new Vector3f(v1.x, v1.y, v1.z), new Vector3f(v0.x, v0.y, v0.z));
        Vector3f edge2 = new Vector3f();
        edge2.sub(new Vector3f(v2.x, v2.y, v2.z), new Vector3f(v0.x, v0.y, v0.z));
        
        Vector3f normal = new Vector3f();
        normal.cross(edge1, edge2);
        
        if (normal.lengthSquared() > GEOMETRIC_TOLERANCE) {
            normal.normalize();
        }
        
        return new Point3f(normal.x, normal.y, normal.z);
    }

    /**
     * AABB prefiltering for performance optimization
     * Fast rejection test before expensive geometric intersection
     * 
     * @param tetIndex the tetrahedral SFC index
     * @param aabb the axis-aligned bounding box
     * @return true if tetrahedron's AABB intersects query AABB
     */
    private static boolean prefilterAABB(long tetIndex, AABB aabb) {
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
        
        // AABB intersection test
        return !(maxX < aabb.minX || minX > aabb.maxX ||
                 maxY < aabb.minY || minY > aabb.maxY ||
                 maxZ < aabb.minZ || minZ > aabb.maxZ);
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