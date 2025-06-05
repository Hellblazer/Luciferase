package com.hellblazer.luciferase.lucien;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Containment search implementation for Tetree
 * Finds tetrahedra that are completely contained within specified spatial volumes
 * All operations are constrained to positive coordinates only, as required by tetrahedral SFC
 * 
 * @author hal.hildebrand
 */
public class TetContainmentSearch extends TetrahedralSearchBase {

    /**
     * Containment result with distance and volume information
     */
    public static class TetContainmentResult<Content> {
        public final long index;
        public final Content content;
        public final Tet tetrahedron;
        public final float distanceToReferencePoint;
        public final Point3f tetrahedronCenter;
        public final ContainmentType containmentType;
        public final float volumeRatio; // ratio of tetrahedron volume to container volume

        public TetContainmentResult(long index, Content content, Tet tetrahedron, 
                               float distanceToReferencePoint, Point3f tetrahedronCenter, 
                               ContainmentType containmentType, float volumeRatio) {
            this.index = index;
            this.content = content;
            this.tetrahedron = tetrahedron;
            this.distanceToReferencePoint = distanceToReferencePoint;
            this.tetrahedronCenter = tetrahedronCenter;
            this.containmentType = containmentType;
            this.volumeRatio = volumeRatio;
        }
    }

    /**
     * Type of containment relationship
     */
    public enum ContainmentType {
        COMPLETELY_CONTAINED,  // Tetrahedron is completely inside the container volume
        PARTIALLY_CONTAINED,   // Tetrahedron partially overlaps with container volume
        NOT_CONTAINED         // Tetrahedron is completely outside container volume
    }

    /**
     * Find all tetrahedra completely contained within a sphere
     * 
     * @param sphereCenter center of the containing sphere (positive coordinates only)
     * @param sphereRadius radius of the containing sphere (positive)
     * @param tetree the tetree to search in
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @return list of completely contained tetrahedra sorted by distance from reference point
     * @throws IllegalArgumentException if any coordinate is negative or radius is non-positive
     */
    public static <Content> List<TetContainmentResult<Content>> tetrahedraContainedInSphere(
            Point3f sphereCenter, float sphereRadius, Tetree<Content> tetree, Point3f referencePoint) {
        
        validatePositiveCoordinates(sphereCenter);
        validatePositiveCoordinates(referencePoint);
        
        if (sphereRadius <= 0) {
            throw new IllegalArgumentException("Sphere radius must be positive, got: " + sphereRadius);
        }
        
        List<TetContainmentResult<Content>> results = new ArrayList<>();
        float sphereVolume = (4.0f / 3.0f) * (float) Math.PI * sphereRadius * sphereRadius * sphereRadius;
        
        // Search within sphere bounds
        Spatial.aabb searchBounds = new Spatial.aabb(
            Math.max(0, sphereCenter.x - sphereRadius),
            Math.max(0, sphereCenter.y - sphereRadius),
            Math.max(0, sphereCenter.z - sphereRadius),
            sphereCenter.x + sphereRadius,
            sphereCenter.y + sphereRadius,
            sphereCenter.z + sphereRadius
        );
        
        // TODO: Using direct scan as workaround for Tetree.boundedBy() issue
        TetreeHelper.directScanBoundedBy(tetree, searchBounds)
            .forEach(simplex -> {
                Tet tet = Tet.tetrahedron(simplex.index());
                
                if (isTetrahedronCompletelyInSphere(tet, sphereCenter, sphereRadius)) {
                    Point3f tetCenter = tetrahedronCenter(simplex.index());
                    float distance = calculateDistance(referencePoint, tetCenter);
                    float tetVolume = calculateTetrahedronVolume(tet);
                    float volumeRatio = tetVolume / sphereVolume;
                    
                    TetContainmentResult<Content> result = new TetContainmentResult<>(
                        simplex.index(), 
                        simplex.cell(), 
                        tet, 
                        distance,
                        tetCenter,
                        ContainmentType.COMPLETELY_CONTAINED,
                        volumeRatio
                    );
                    results.add(result);
                }
            });

        // Sort by distance from reference point
        results.sort(Comparator.comparing(cr -> cr.distanceToReferencePoint));
        
        return results;
    }

    /**
     * Find all tetrahedra completely contained within an AABB
     * 
     * @param aabb the containing axis-aligned bounding box
     * @param tetree the tetree to search in
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @return list of completely contained tetrahedra sorted by distance from reference point
     * @throws IllegalArgumentException if reference point has negative coordinates
     */
    public static <Content> List<TetContainmentResult<Content>> tetrahedraContainedInAABB(
            Spatial.aabb aabb, Tetree<Content> tetree, Point3f referencePoint) {
        
        validatePositiveCoordinates(referencePoint);
        
        List<TetContainmentResult<Content>> results = new ArrayList<>();
        float aabbVolume = (aabb.extentX() - aabb.originX()) * 
                          (aabb.extentY() - aabb.originY()) * 
                          (aabb.extentZ() - aabb.originZ());
        
        // TODO: Using direct scan as workaround for Tetree.boundedBy() issue
        TetreeHelper.directScanBoundedBy(tetree, aabb)
            .forEach(simplex -> {
                Tet tet = Tet.tetrahedron(simplex.index());
                
                if (isTetrahedronCompletelyInAABB(tet, aabb)) {
                    Point3f tetCenter = tetrahedronCenter(simplex.index());
                    float distance = calculateDistance(referencePoint, tetCenter);
                    float tetVolume = calculateTetrahedronVolume(tet);
                    float volumeRatio = tetVolume / aabbVolume;
                    
                    TetContainmentResult<Content> result = new TetContainmentResult<>(
                        simplex.index(), 
                        simplex.cell(), 
                        tet, 
                        distance,
                        tetCenter,
                        ContainmentType.COMPLETELY_CONTAINED,
                        volumeRatio
                    );
                    results.add(result);
                }
            });

        // Sort by distance from reference point
        results.sort(Comparator.comparing(cr -> cr.distanceToReferencePoint));
        
        return results;
    }

    /**
     * Find all tetrahedra contained within a cylindrical volume
     * 
     * @param cylinderBase base center of the cylinder (positive coordinates only)
     * @param cylinderTop top center of the cylinder (positive coordinates only)
     * @param cylinderRadius radius of the cylinder (positive)
     * @param tetree the tetree to search in
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @return list of completely contained tetrahedra sorted by distance from reference point
     * @throws IllegalArgumentException if any coordinate is negative or radius is non-positive
     */
    public static <Content> List<TetContainmentResult<Content>> tetrahedraContainedInCylinder(
            Point3f cylinderBase, Point3f cylinderTop, float cylinderRadius, 
            Tetree<Content> tetree, Point3f referencePoint) {
        
        validatePositiveCoordinates(cylinderBase);
        validatePositiveCoordinates(cylinderTop);
        validatePositiveCoordinates(referencePoint);
        
        if (cylinderRadius <= 0) {
            throw new IllegalArgumentException("Cylinder radius must be positive, got: " + cylinderRadius);
        }
        
        List<TetContainmentResult<Content>> results = new ArrayList<>();
        float cylinderHeight = calculateDistance(cylinderBase, cylinderTop);
        float cylinderVolume = (float) Math.PI * cylinderRadius * cylinderRadius * cylinderHeight;
        
        // Calculate bounding box for cylinder
        float minX = Math.max(0, Math.min(cylinderBase.x, cylinderTop.x) - cylinderRadius);
        float minY = Math.max(0, Math.min(cylinderBase.y, cylinderTop.y) - cylinderRadius);
        float minZ = Math.max(0, Math.min(cylinderBase.z, cylinderTop.z) - cylinderRadius);
        float maxX = Math.max(cylinderBase.x, cylinderTop.x) + cylinderRadius;
        float maxY = Math.max(cylinderBase.y, cylinderTop.y) + cylinderRadius;
        float maxZ = Math.max(cylinderBase.z, cylinderTop.z) + cylinderRadius;
        
        Spatial.aabb searchBounds = new Spatial.aabb(minX, minY, minZ, maxX, maxY, maxZ);
        
        // TODO: Using direct scan as workaround for Tetree.boundedBy() issue
        TetreeHelper.directScanBoundedBy(tetree, searchBounds)
            .forEach(simplex -> {
                Tet tet = Tet.tetrahedron(simplex.index());
                
                if (isTetrahedronCompletelyInCylinder(tet, cylinderBase, cylinderTop, cylinderRadius)) {
                    Point3f tetCenter = tetrahedronCenter(simplex.index());
                    float distance = calculateDistance(referencePoint, tetCenter);
                    float tetVolume = calculateTetrahedronVolume(tet);
                    float volumeRatio = tetVolume / cylinderVolume;
                    
                    TetContainmentResult<Content> result = new TetContainmentResult<>(
                        simplex.index(), 
                        simplex.cell(), 
                        tet, 
                        distance,
                        tetCenter,
                        ContainmentType.COMPLETELY_CONTAINED,
                        volumeRatio
                    );
                    results.add(result);
                }
            });

        // Sort by distance from reference point
        results.sort(Comparator.comparing(cr -> cr.distanceToReferencePoint));
        
        return results;
    }

    /**
     * Find tetrahedra with a specific volume ratio within a container
     * 
     * @param containerVolume the volume of the container
     * @param minVolumeRatio minimum volume ratio (tet_volume / container_volume)
     * @param maxVolumeRatio maximum volume ratio (tet_volume / container_volume)
     * @param containedTetrahedra list of contained tetrahedra to filter
     * @return list of tetrahedra within the specified volume ratio range
     */
    public static <Content> List<TetContainmentResult<Content>> tetrahedraWithVolumeRatio(
            float containerVolume, float minVolumeRatio, float maxVolumeRatio,
            List<TetContainmentResult<Content>> containedTetrahedra) {
        
        if (minVolumeRatio < 0 || maxVolumeRatio < 0 || minVolumeRatio > maxVolumeRatio) {
            throw new IllegalArgumentException("Invalid volume ratio range: [" + minVolumeRatio + ", " + maxVolumeRatio + "]");
        }
        
        return containedTetrahedra.stream()
            .filter(cr -> cr.volumeRatio >= minVolumeRatio && cr.volumeRatio <= maxVolumeRatio)
            .collect(Collectors.toList());
    }

    /**
     * Count tetrahedra completely contained within a sphere
     * More efficient than getting all results when only count is needed
     * 
     * @param sphereCenter center of the containing sphere (positive coordinates only)
     * @param sphereRadius radius of the containing sphere (positive)
     * @param tetree the tetree to search in
     * @return number of tetrahedra completely contained in the sphere
     * @throws IllegalArgumentException if any coordinate is negative or radius is non-positive
     */
    public static <Content> long countTetrahedraContainedInSphere(
            Point3f sphereCenter, float sphereRadius, Tetree<Content> tetree) {
        
        validatePositiveCoordinates(sphereCenter);
        
        if (sphereRadius <= 0) {
            throw new IllegalArgumentException("Sphere radius must be positive, got: " + sphereRadius);
        }
        
        Spatial.aabb searchBounds = new Spatial.aabb(
            Math.max(0, sphereCenter.x - sphereRadius),
            Math.max(0, sphereCenter.y - sphereRadius),
            Math.max(0, sphereCenter.z - sphereRadius),
            sphereCenter.x + sphereRadius,
            sphereCenter.y + sphereRadius,
            sphereCenter.z + sphereRadius
        );
        
        // TODO: Using direct scan as workaround for Tetree.boundedBy() issue
        return TetreeHelper.directScanBoundedBy(tetree, searchBounds)
            .filter(simplex -> {
                Tet tet = Tet.tetrahedron(simplex.index());
                return isTetrahedronCompletelyInSphere(tet, sphereCenter, sphereRadius);
            })
            .count();
    }

    /**
     * Count tetrahedra completely contained within an AABB
     * More efficient than getting all results when only count is needed
     * 
     * @param aabb the containing axis-aligned bounding box
     * @param tetree the tetree to search in
     * @return number of tetrahedra completely contained in the AABB
     */
    public static <Content> long countTetrahedraContainedInAABB(
            Spatial.aabb aabb, Tetree<Content> tetree) {
        
        // TODO: Using direct scan as workaround for Tetree.boundedBy() issue
        return TetreeHelper.directScanBoundedBy(tetree, aabb)
            .filter(simplex -> {
                Tet tet = Tet.tetrahedron(simplex.index());
                return isTetrahedronCompletelyInAABB(tet, aabb);
            })
            .count();
    }

    /**
     * Get statistics about containment within a sphere
     * 
     * @param sphereCenter center of the containing sphere (positive coordinates only)
     * @param sphereRadius radius of the containing sphere (positive)
     * @param tetree the tetree to search in
     * @return statistics about containment results
     * @throws IllegalArgumentException if any coordinate is negative or radius is non-positive
     */
    public static <Content> TetContainmentStatistics getContainmentStatisticsForSphere(
            Point3f sphereCenter, float sphereRadius, Tetree<Content> tetree) {
        
        validatePositiveCoordinates(sphereCenter);
        
        if (sphereRadius <= 0) {
            throw new IllegalArgumentException("Sphere radius must be positive, got: " + sphereRadius);
        }
        
        // Use a mutable container class to track statistics
        class StatsAccumulator {
            long totalTetrahedra = 0;
            long containedTetrahedra = 0;
            long partiallyContainedTetrahedra = 0;
            long notContainedTetrahedra = 0;
            float totalVolumeRatio = 0.0f;
        }
        
        final StatsAccumulator stats = new StatsAccumulator();
        final float sphereVolume = (4.0f / 3.0f) * (float) Math.PI * sphereRadius * sphereRadius * sphereRadius;

        // Search a larger area to find all potentially affected tetrahedra
        float searchRadius = sphereRadius * 1.5f; // Expand search to catch all potentially intersecting tetrahedra
        Spatial.aabb searchBounds = new Spatial.aabb(
            Math.max(0, sphereCenter.x - searchRadius),
            Math.max(0, sphereCenter.y - searchRadius),
            Math.max(0, sphereCenter.z - searchRadius),
            sphereCenter.x + searchRadius,
            sphereCenter.y + searchRadius,
            sphereCenter.z + searchRadius
        );
        
        // TODO: Using direct scan as workaround for Tetree.boundedBy() issue
        TetreeHelper.directScanBoundedBy(tetree, searchBounds)
            .forEach(simplex -> {
                stats.totalTetrahedra++;
                Tet tet = Tet.tetrahedron(simplex.index());
                
                if (isTetrahedronCompletelyInSphere(tet, sphereCenter, sphereRadius)) {
                    stats.containedTetrahedra++;
                    float tetVolume = calculateTetrahedronVolume(tet);
                    stats.totalVolumeRatio += tetVolume / sphereVolume;
                } else if (tetrahedronPartiallyIntersectsSphere(tet, sphereCenter, sphereRadius)) {
                    stats.partiallyContainedTetrahedra++;
                } else {
                    stats.notContainedTetrahedra++;
                }
            });

        float averageVolumeRatio = stats.containedTetrahedra > 0 ? stats.totalVolumeRatio / stats.containedTetrahedra : 0.0f;

        return new TetContainmentStatistics(stats.totalTetrahedra, stats.containedTetrahedra, stats.partiallyContainedTetrahedra, 
                                          stats.notContainedTetrahedra, stats.totalVolumeRatio, averageVolumeRatio);
    }

    /**
     * Statistics about containment results
     */
    public static class TetContainmentStatistics {
        public final long totalTetrahedra;
        public final long containedTetrahedra;
        public final long partiallyContainedTetrahedra;
        public final long notContainedTetrahedra;
        public final float totalVolumeRatio;
        public final float averageVolumeRatio;
        
        public TetContainmentStatistics(long totalTetrahedra, long containedTetrahedra, long partiallyContainedTetrahedra, 
                                       long notContainedTetrahedra, float totalVolumeRatio, float averageVolumeRatio) {
            this.totalTetrahedra = totalTetrahedra;
            this.containedTetrahedra = containedTetrahedra;
            this.partiallyContainedTetrahedra = partiallyContainedTetrahedra;
            this.notContainedTetrahedra = notContainedTetrahedra;
            this.totalVolumeRatio = totalVolumeRatio;
            this.averageVolumeRatio = averageVolumeRatio;
        }
        
        public double getContainedPercentage() {
            return totalTetrahedra > 0 ? (double) containedTetrahedra / totalTetrahedra * 100.0 : 0.0;
        }
        
        public double getPartiallyContainedPercentage() {
            return totalTetrahedra > 0 ? (double) partiallyContainedTetrahedra / totalTetrahedra * 100.0 : 0.0;
        }
        
        public double getNotContainedPercentage() {
            return totalTetrahedra > 0 ? (double) notContainedTetrahedra / totalTetrahedra * 100.0 : 0.0;
        }
        
        public double getOverallContainmentPercentage() {
            return totalTetrahedra > 0 ? (double) (containedTetrahedra + partiallyContainedTetrahedra) / totalTetrahedra * 100.0 : 0.0;
        }
        
        @Override
        public String toString() {
            return String.format("TetContainmentStats[total=%d, contained=%d(%.1f%%), partial=%d(%.1f%%), not_contained=%d(%.1f%%), avg_volume_ratio=%.3f]",
                               totalTetrahedra, containedTetrahedra, getContainedPercentage(), 
                               partiallyContainedTetrahedra, getPartiallyContainedPercentage(),
                               notContainedTetrahedra, getNotContainedPercentage(), averageVolumeRatio);
        }
    }

    /**
     * Test if a tetrahedron is completely contained within a sphere
     */
    private static boolean isTetrahedronCompletelyInSphere(Tet tet, Point3f sphereCenter, float sphereRadius) {
        // Check if all 4 vertices are within the sphere
        Point3i[] vertices = tet.coordinates();
        
        for (Point3i vertex : vertices) {
            float dx = vertex.x - sphereCenter.x;
            float dy = vertex.y - sphereCenter.y;
            float dz = vertex.z - sphereCenter.z;
            float distanceSquared = dx * dx + dy * dy + dz * dz;
            
            if (distanceSquared > sphereRadius * sphereRadius) {
                return false;
            }
        }
        
        return true;
    }

    /**
     * Test if a tetrahedron is completely contained within an AABB
     */
    private static boolean isTetrahedronCompletelyInAABB(Tet tet, Spatial.aabb aabb) {
        // Check if all 4 vertices are within the AABB
        Point3i[] vertices = tet.coordinates();
        
        for (Point3i vertex : vertices) {
            if (vertex.x < aabb.originX() || vertex.x > aabb.extentX() ||
                vertex.y < aabb.originY() || vertex.y > aabb.extentY() ||
                vertex.z < aabb.originZ() || vertex.z > aabb.extentZ()) {
                return false;
            }
        }
        
        return true;
    }

    /**
     * Test if a tetrahedron is completely contained within a cylinder
     */
    private static boolean isTetrahedronCompletelyInCylinder(Tet tet, Point3f cylinderBase, 
                                                            Point3f cylinderTop, float cylinderRadius) {
        // Check if all 4 vertices are within the cylinder
        Point3i[] vertices = tet.coordinates();
        
        for (Point3i vertex : vertices) {
            Point3f vertexPoint = new Point3f(vertex.x, vertex.y, vertex.z);
            if (!isPointInCylinder(vertexPoint, cylinderBase, cylinderTop, cylinderRadius)) {
                return false;
            }
        }
        
        return true;
    }

    /**
     * Test if a point is within a cylinder
     */
    private static boolean isPointInCylinder(Point3f point, Point3f cylinderBase, Point3f cylinderTop, float cylinderRadius) {
        // Vector from base to top
        float cylDx = cylinderTop.x - cylinderBase.x;
        float cylDy = cylinderTop.y - cylinderBase.y;
        float cylDz = cylinderTop.z - cylinderBase.z;
        float cylLength = (float) Math.sqrt(cylDx * cylDx + cylDy * cylDy + cylDz * cylDz);
        
        if (cylLength < 1e-6f) {
            return false; // Degenerate cylinder
        }
        
        // Normalize cylinder direction
        cylDx /= cylLength;
        cylDy /= cylLength;
        cylDz /= cylLength;
        
        // Vector from base to point
        float pointDx = point.x - cylinderBase.x;
        float pointDy = point.y - cylinderBase.y;
        float pointDz = point.z - cylinderBase.z;
        
        // Project point onto cylinder axis
        float axisProjection = pointDx * cylDx + pointDy * cylDy + pointDz * cylDz;
        
        // Check if point is between base and top
        if (axisProjection < 0 || axisProjection > cylLength) {
            return false;
        }
        
        // Calculate radial distance from cylinder axis
        float projX = cylinderBase.x + axisProjection * cylDx;
        float projY = cylinderBase.y + axisProjection * cylDy;
        float projZ = cylinderBase.z + axisProjection * cylDz;
        
        float radialDx = point.x - projX;
        float radialDy = point.y - projY;
        float radialDz = point.z - projZ;
        float radialDistance = (float) Math.sqrt(radialDx * radialDx + radialDy * radialDy + radialDz * radialDz);
        
        return radialDistance <= cylinderRadius;
    }

    /**
     * Calculate the axis-aligned bounding box of a tetrahedron
     */
    private static Spatial.aabb getTetrahedronBounds(Tet tet) {
        Point3i[] vertices = tet.coordinates();
        
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = Float.MIN_VALUE, maxY = Float.MIN_VALUE, maxZ = Float.MIN_VALUE;
        
        for (Point3i vertex : vertices) {
            minX = Math.min(minX, vertex.x);
            minY = Math.min(minY, vertex.y);
            minZ = Math.min(minZ, vertex.z);
            maxX = Math.max(maxX, vertex.x);
            maxY = Math.max(maxY, vertex.y);
            maxZ = Math.max(maxZ, vertex.z);
        }
        
        return new Spatial.aabb(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * Test if a tetrahedron partially intersects with a sphere (for statistics)
     */
    private static boolean tetrahedronPartiallyIntersectsSphere(Tet tet, Point3f sphereCenter, float sphereRadius) {
        // First check if tetrahedron AABB intersects sphere
        Spatial.aabb tetBounds = getTetrahedronBounds(tet);
        
        // Calculate squared distance from sphere center to closest point on AABB
        float dx = Math.max(0, Math.max(tetBounds.originX() - sphereCenter.x, sphereCenter.x - tetBounds.extentX()));
        float dy = Math.max(0, Math.max(tetBounds.originY() - sphereCenter.y, sphereCenter.y - tetBounds.extentY()));
        float dz = Math.max(0, Math.max(tetBounds.originZ() - sphereCenter.z, sphereCenter.z - tetBounds.extentZ()));
        
        float distanceSquared = dx * dx + dy * dy + dz * dz;
        
        // If AABB doesn't intersect sphere, tetrahedron doesn't either
        if (distanceSquared > sphereRadius * sphereRadius) {
            return false;
        }
        
        // Check if sphere center is inside tetrahedron
        if (tet.contains(sphereCenter)) {
            return true;
        }
        
        // Check if any vertex is inside the sphere
        Point3i[] vertices = tet.coordinates();
        for (Point3i vertex : vertices) {
            float vdx = vertex.x - sphereCenter.x;
            float vdy = vertex.y - sphereCenter.y;
            float vdz = vertex.z - sphereCenter.z;
            if (vdx * vdx + vdy * vdy + vdz * vdz <= sphereRadius * sphereRadius) {
                return true;
            }
        }
        
        // More complex intersection tests would go here (edge-sphere, face-sphere)
        // For now, use AABB intersection as approximation
        return true;
    }

    /**
     * Calculate the volume of a tetrahedron using the scalar triple product
     */
    private static float calculateTetrahedronVolume(Tet tet) {
        Point3i[] vertices = tet.coordinates();
        
        // Use vertex 0 as reference point
        float ax = vertices[1].x - vertices[0].x;
        float ay = vertices[1].y - vertices[0].y;
        float az = vertices[1].z - vertices[0].z;
        
        float bx = vertices[2].x - vertices[0].x;
        float by = vertices[2].y - vertices[0].y;
        float bz = vertices[2].z - vertices[0].z;
        
        float cx = vertices[3].x - vertices[0].x;
        float cy = vertices[3].y - vertices[0].y;
        float cz = vertices[3].z - vertices[0].z;
        
        // Scalar triple product: a · (b × c)
        float crossX = by * cz - bz * cy;
        float crossY = bz * cx - bx * cz;
        float crossZ = bx * cy - by * cx;
        
        float scalarTriple = ax * crossX + ay * crossY + az * crossZ;
        
        // Volume = |scalar triple product| / 6
        return Math.abs(scalarTriple) / 6.0f;
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