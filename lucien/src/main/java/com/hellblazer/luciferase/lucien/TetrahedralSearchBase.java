package com.hellblazer.luciferase.lucien;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import javax.vecmath.Tuple3f;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Base class for tetrahedral search operations providing common geometric operations and utilities for handling
 * multiple simplicies per spatial region.
 *
 * All operations are constrained to positive coordinates only, as required by the tetrahedral space-filling curve
 * implementation.
 *
 * @author hal.hildebrand
 */
public abstract class TetrahedralSearchBase {

    /**
     * Tolerance for floating-point geometric comparisons
     */
    protected static final float GEOMETRIC_TOLERANCE = 1e-6f;

    /**
     * Aggregate simplicies based on the specified strategy
     *
     * @param simplicies stream of simplicies to aggregate
     * @param strategy   aggregation strategy to use
     * @return aggregated result according to strategy
     */
    protected static <Content> List<Simplex<Content>> aggregateSimplicies(Stream<Simplex<Content>> simplicies,
                                                                          SimplexAggregationStrategy strategy) {

        var simpliciesList = simplicies.collect(Collectors.toList());
        if (simpliciesList.isEmpty()) {
            return Collections.emptyList();
        }

        return switch (strategy) {
            case REPRESENTATIVE_ONLY -> {
                var representative = selectRepresentativeSimplex(simpliciesList);
                yield representative != null ? List.of(representative) : Collections.emptyList();
            }
            case ALL_SIMPLICIES -> simpliciesList;
            case WEIGHTED_AVERAGE -> {
                // For volume-weighted averaging, we still return individual simplicies
                // but could be extended to create synthetic averaged simplicies
                yield simpliciesList;
            }
            case BEST_FIT -> {
                // Default to representative for now, can be specialized per search type
                var representative = selectRepresentativeSimplex(simpliciesList);
                yield representative != null ? List.of(representative) : Collections.emptyList();
            }
        };
    }

    private static float distance(Point3f point, Point3i vertex) {
        float dx = point.x - vertex.x;
        float dy = point.y - vertex.y;
        float dz = point.z - vertex.z;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static float distanceToLineSegment(Point3f point, Point3i start, Point3i end) {
        float dx = end.x - start.x;
        float dy = end.y - start.y;
        float dz = end.z - start.z;

        float length2 = dx * dx + dy * dy + dz * dz;
        if (length2 < GEOMETRIC_TOLERANCE) {
            return distance(point, start); // Degenerate segment, treat as point
        }

        // Project point onto line segment
        float t = ((point.x - start.x) * dx + (point.y - start.y) * dy + (point.z - start.z) * dz) / length2;
        t = Math.max(0, Math.min(1, t)); // Clamp to segment

        float projX = start.x + t * dx;
        float projY = start.y + t * dy;
        float projZ = start.z + t * dz;

        float distX = point.x - projX;
        float distY = point.y - projY;
        float distZ = point.z - projZ;

        return (float) Math.sqrt(distX * distX + distY * distY + distZ * distZ);
    }

    /**
     * Compute the minimum distance from a point to a tetrahedron
     *
     * @param point    the query point (must have positive coordinates)
     * @param tetIndex the tetrahedral SFC index
     * @return minimum distance to tetrahedron (0 if point is inside)
     */
    protected static float distanceToTetrahedron(Point3f point, long tetIndex) {
        if (point.x < 0 || point.y < 0 || point.z < 0) {
            throw new IllegalArgumentException("Point coordinates must be positive");
        }

        // If point is inside tetrahedron, distance is 0
        if (pointInTetrahedron(point, tetIndex)) {
            return 0.0f;
        }

        var tet = Tet.tetrahedron(tetIndex);
        var vertices = tet.coordinates();

        float minDistance = Float.MAX_VALUE;

        // Check distance to each tetrahedral face
        for (int face = 0; face < 4; face++) {
            float dist = distanceToTriangularFace(point, vertices, face);
            minDistance = Math.min(minDistance, dist);
        }

        // Check distance to each edge
        for (int i = 0; i < 4; i++) {
            for (int j = i + 1; j < 4; j++) {
                float dist = distanceToLineSegment(point, vertices[i], vertices[j]);
                minDistance = Math.min(minDistance, dist);
            }
        }

        // Check distance to each vertex
        for (Point3i vertex : vertices) {
            float dist = distance(point, vertex);
            minDistance = Math.min(minDistance, dist);
        }

        return minDistance;
    }

    private static float distanceToTriangularFace(Point3f point, Point3i[] vertices, int faceIndex) {
        // Get vertices of the specified face
        Point3i[] faceVertices = getFaceVertices(vertices, faceIndex);

        // Convert to Point3f for calculations
        Point3f v0 = new Point3f(faceVertices[0].x, faceVertices[0].y, faceVertices[0].z);
        Point3f v1 = new Point3f(faceVertices[1].x, faceVertices[1].y, faceVertices[1].z);
        Point3f v2 = new Point3f(faceVertices[2].x, faceVertices[2].y, faceVertices[2].z);

        // Compute distance to triangle using proper point-to-triangle algorithm
        // This projects the point onto the triangle plane and checks if it's inside the triangle

        // Compute plane normal
        float e1x = v1.x - v0.x, e1y = v1.y - v0.y, e1z = v1.z - v0.z;
        float e2x = v2.x - v0.x, e2y = v2.y - v0.y, e2z = v2.z - v0.z;

        float nx = e1y * e2z - e1z * e2y;
        float ny = e1z * e2x - e1x * e2z;
        float nz = e1x * e2y - e1y * e2x;

        float normalLength = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (normalLength < GEOMETRIC_TOLERANCE) {
            // Degenerate triangle - return distance to closest vertex
            return Math.min(Math.min(distance(point, faceVertices[0]), distance(point, faceVertices[1])),
                            distance(point, faceVertices[2]));
        }

        nx /= normalLength;
        ny /= normalLength;
        nz /= normalLength;

        // Project point onto triangle plane
        float d = nx * (point.x - v0.x) + ny * (point.y - v0.y) + nz * (point.z - v0.z);
        float projX = point.x - d * nx;
        float projY = point.y - d * ny;
        float projZ = point.z - d * nz;

        // Check if projection is inside triangle using barycentric coordinates
        float v0x = v0.x, v0y = v0.y, v0z = v0.z;
        float v1x = v1.x, v1y = v1.y, v1z = v1.z;
        float v2x = v2.x, v2y = v2.y, v2z = v2.z;

        // Compute barycentric coordinates
        float dot00 = (v2x - v0x) * (v2x - v0x) + (v2y - v0y) * (v2y - v0y) + (v2z - v0z) * (v2z - v0z);
        float dot01 = (v2x - v0x) * (v1x - v0x) + (v2y - v0y) * (v1y - v0y) + (v2z - v0z) * (v1z - v0z);
        float dot02 = (v2x - v0x) * (projX - v0x) + (v2y - v0y) * (projY - v0y) + (v2z - v0z) * (projZ - v0z);
        float dot11 = (v1x - v0x) * (v1x - v0x) + (v1y - v0y) * (v1y - v0y) + (v1z - v0z) * (v1z - v0z);
        float dot12 = (v1x - v0x) * (projX - v0x) + (v1y - v0y) * (projY - v0y) + (v1z - v0z) * (projZ - v0z);

        float invDenom = 1.0f / (dot00 * dot11 - dot01 * dot01);
        float u = (dot11 * dot02 - dot01 * dot12) * invDenom;
        float v = (dot00 * dot12 - dot01 * dot02) * invDenom;

        // Check if point is inside triangle
        if (u >= 0 && v >= 0 && u + v <= 1) {
            // Point projects inside triangle - return perpendicular distance to plane
            return Math.abs(d);
        } else {
            // Point projects outside triangle - return distance to closest edge or vertex
            float dist1 = distanceToLineSegment(point, faceVertices[0], faceVertices[1]);
            float dist2 = distanceToLineSegment(point, faceVertices[1], faceVertices[2]);
            float dist3 = distanceToLineSegment(point, faceVertices[2], faceVertices[0]);
            return Math.min(Math.min(dist1, dist2), dist3);
        }
    }

    private static Point3i[] getFaceVertices(Point3i[] tetrahedronVertices, int faceIndex) {
        // Return the 3 vertices that form the specified face of the tetrahedron
        return switch (faceIndex) {
            case 0 -> new Point3i[] { tetrahedronVertices[0], tetrahedronVertices[1], tetrahedronVertices[2] };
            case 1 -> new Point3i[] { tetrahedronVertices[0], tetrahedronVertices[1], tetrahedronVertices[3] };
            case 2 -> new Point3i[] { tetrahedronVertices[0], tetrahedronVertices[2], tetrahedronVertices[3] };
            case 3 -> new Point3i[] { tetrahedronVertices[1], tetrahedronVertices[2], tetrahedronVertices[3] };
            default -> throw new IllegalArgumentException("Face index must be 0-3");
        };
    }

    /**
     * Group simplicies by spatial proximity (same cubic region)
     *
     * @param simplicies stream of simplicies to group
     * @return list of simplex groups
     */
    protected static <Content> List<SimplexGroup<Content>> groupSimpliciesBySpatialProximity(
    Stream<Simplex<Content>> simplicies) {

        // Group by spatial proximity using tetrahedral coordinates at a coarser level
        Map<String, List<Simplex<Content>>> spatialGroups = simplicies.collect(Collectors.groupingBy(simplex -> {
            var tet = Tet.tetrahedron(simplex.index());
            // Group by coarser spatial coordinates (divide by tetrahedron level size)
            int levelSize = Constants.lengthAtLevel(tet.l());
            int groupX = tet.x() / levelSize;
            int groupY = tet.y() / levelSize;
            int groupZ = tet.z() / levelSize;
            return groupX + "," + groupY + "," + groupZ;
        }));

        return spatialGroups.values().stream().filter(group -> !group.isEmpty()).map(SimplexGroup::new).collect(
        Collectors.toList());
    }

    /**
     * Test if a point is inside a tetrahedron using the existing Tet.contains method
     *
     * @param point    the point to test (must have positive coordinates)
     * @param tetIndex the tetrahedral SFC index
     * @return true if point is inside the tetrahedron
     */
    protected static boolean pointInTetrahedron(Point3f point, long tetIndex) {
        if (point.x < 0 || point.y < 0 || point.z < 0) {
            throw new IllegalArgumentException("Point coordinates must be positive");
        }

        var tet = Tet.tetrahedron(tetIndex);

        // Use the existing Tet.contains method which uses proper orientation tests
        return tet.contains(point);
    }

    /**
     * Select the representative simplex from a group based on geometric criteria
     *
     * @param simplicies list of simplicies to choose from
     * @return representative simplex, or null if list is empty
     */
    protected static <Content> Simplex<Content> selectRepresentativeSimplex(List<Simplex<Content>> simplicies) {
        if (simplicies.isEmpty()) {
            return null;
        }

        if (simplicies.size() == 1) {
            return simplicies.get(0);
        }

        // Select simplex with largest volume as representative
        return simplicies.stream().max((s1, s2) -> {
            double vol1 = tetrahedronVolume(s1.index());
            double vol2 = tetrahedronVolume(s2.index());
            return Double.compare(vol1, vol2);
        }).orElse(simplicies.get(0));
    }

    /**
     * Compute the center point of a tetrahedron
     *
     * @param tetIndex the tetrahedral SFC index
     * @return center point of the tetrahedron
     */
    protected static Point3f tetrahedronCenter(long tetIndex) {
        var tet = Tet.tetrahedron(tetIndex);
        return tetrahedronCenter(tet);
    }

    // Helper methods for geometric computations

    /**
     * Compute the center point of a tetrahedron
     *
     * @param tet the tetrahedron
     * @return center point of the tetrahedron
     */
    protected static Point3f tetrahedronCenter(Tet tet) {
        var vertices = tet.coordinates();

        float centerX = (vertices[0].x + vertices[1].x + vertices[2].x + vertices[3].x) / 4.0f;
        float centerY = (vertices[0].y + vertices[1].y + vertices[2].y + vertices[3].y) / 4.0f;
        float centerZ = (vertices[0].z + vertices[1].z + vertices[2].z + vertices[3].z) / 4.0f;

        return new Point3f(centerX, centerY, centerZ);
    }

    /**
     * Compute the volume of a tetrahedron
     *
     * @param tetIndex the tetrahedral SFC index
     * @return volume of the tetrahedron
     */
    protected static double tetrahedronVolume(long tetIndex) {
        var tet = Tet.tetrahedron(tetIndex);
        var vertices = tet.coordinates();

        Point3i v0 = vertices[0], v1 = vertices[1], v2 = vertices[2], v3 = vertices[3];

        // Compute volume using determinant formula
        int a1 = v1.x - v0.x, a2 = v1.y - v0.y, a3 = v1.z - v0.z;
        int b1 = v2.x - v0.x, b2 = v2.y - v0.y, b3 = v2.z - v0.z;
        int c1 = v3.x - v0.x, c2 = v3.y - v0.y, c3 = v3.z - v0.z;

        double det = a1 * (b2 * c3 - b3 * c2) - a2 * (b1 * c3 - b3 * c1) + a3 * (b1 * c2 - b2 * c1);
        return Math.abs(det) / 6.0;
    }

    /**
     * Validate that coordinates are positive (required for tetrahedral SFC)
     */
    protected static void validatePositiveCoordinates(Tuple3f point) {
        if (point.x < 0 || point.y < 0 || point.z < 0) {
            throw new IllegalArgumentException("Coordinates must be positive for tetrahedral operations: " + point);
        }
    }

    /**
     * Validate that coordinates are positive (required for tetrahedral SFC)
     */
    protected static void validatePositiveCoordinates(float x, float y, float z) {
        if (x < 0 || y < 0 || z < 0) {
            throw new IllegalArgumentException(
            "Coordinates must be positive for tetrahedral operations: (" + x + ", " + y + ", " + z + ")");
        }
    }

    /**
     * Strategy for aggregating multiple simplicies in the same spatial region
     */
    public enum SimplexAggregationStrategy {
        REPRESENTATIVE_ONLY,    // Return only the representative simplex
        WEIGHTED_AVERAGE,       // Combine using volume-weighted averaging
        ALL_SIMPLICIES,         // Return all simplicies individually
        BEST_FIT               // Select simplex that best fits the query criteria
    }

    /**
     * Group of simplicies representing the same spatial region (up to 6 tetrahedra per cubic region in tetrahedral
     * decomposition)
     */
    public static class SimplexGroup<Content> {
        public final List<Simplex<Content>> simplicies;
        public final Point3f                groupCenter;
        public final float                  groupVolume;
        public final long                   representativeIndex;

        public SimplexGroup(List<Simplex<Content>> simplicies) {
            if (simplicies.isEmpty()) {
                throw new IllegalArgumentException("SimplexGroup cannot be empty");
            }

            this.simplicies = new ArrayList<>(simplicies);
            this.groupCenter = computeGroupCenter(simplicies);
            this.groupVolume = computeGroupVolume(simplicies);
            this.representativeIndex = selectRepresentativeIndex(simplicies);
        }

        private static <Content> Point3f computeGroupCenter(List<Simplex<Content>> simplicies) {
            float sumX = 0, sumY = 0, sumZ = 0;
            for (var simplex : simplicies) {
                Point3f center = tetrahedronCenter(simplex.index());
                sumX += center.x;
                sumY += center.y;
                sumZ += center.z;
            }
            int count = simplicies.size();
            return new Point3f(sumX / count, sumY / count, sumZ / count);
        }

        private static <Content> float computeGroupVolume(List<Simplex<Content>> simplicies) {
            return (float) simplicies.stream().mapToDouble(simplex -> tetrahedronVolume(simplex.index())).sum();
        }

        private static <Content> long selectRepresentativeIndex(List<Simplex<Content>> simplicies) {
            // Select the simplex closest to the group center
            Point3f groupCenter = computeGroupCenter(simplicies);
            return simplicies.stream().min((s1, s2) -> {
                float dist1 = distanceToTetrahedron(groupCenter, s1.index());
                float dist2 = distanceToTetrahedron(groupCenter, s2.index());
                return Float.compare(dist1, dist2);
            }).map(simplex -> simplex.index()).orElse(simplicies.get(0).index());
        }
    }
}
