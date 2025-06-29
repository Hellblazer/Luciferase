package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.Simplex;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
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

    // ===== Tetrahedral Distance Metrics =====

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

    /**
     * Helper method to expand search radius adaptively based on results found. Concrete implementations can use this
     * for adaptive search strategies.
     *
     * @param currentRadius current search radius
     * @param resultsFound  number of results found so far
     * @param targetResults target number of results desired
     * @param maxRadius     maximum allowed radius
     * @return suggested new radius
     */
    protected static float calculateAdaptiveRadius(float currentRadius, int resultsFound, int targetResults,
                                                   float maxRadius) {
        if (resultsFound >= targetResults) {
            return currentRadius; // Found enough results
        }

        // Expand radius based on density estimation
        float expansionFactor = resultsFound == 0 ? 2.0f : (float) Math.sqrt((double) targetResults / resultsFound);
        expansionFactor = Math.min(expansionFactor, 3.0f); // Limit expansion to 3x

        float newRadius = currentRadius * expansionFactor;
        return Math.min(newRadius, maxRadius);
    }

    /**
     * Calculate distance between two points using the specified metric
     *
     * @param p1     first point (must have positive coordinates)
     * @param p2     second point (must have positive coordinates)
     * @param metric distance metric to use
     * @return distance according to the specified metric
     */
    protected static float calculateDistance(Point3f p1, Point3f p2, TetrahedralDistanceMetric metric) {
        if (p1.x < 0 || p1.y < 0 || p1.z < 0 || p2.x < 0 || p2.y < 0 || p2.z < 0) {
            throw new IllegalArgumentException("Point coordinates must be positive");
        }

        float dx = p2.x - p1.x;
        float dy = p2.y - p1.y;
        float dz = p2.z - p1.z;

        return switch (metric) {
            case EUCLIDEAN -> (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            case MANHATTAN -> Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
            case CHEBYSHEV -> Math.max(Math.max(Math.abs(dx), Math.abs(dy)), Math.abs(dz));
            default -> throw new IllegalArgumentException("Use calculateTetrahedralDistance for tetrahedral metrics");
        };
    }

    /**
     * Calculate distance from a point to a tetrahedral region using the specified metric
     *
     * @param point    query point (must have positive coordinates)
     * @param tetIndex tetrahedral SFC index
     * @param metric   distance metric to use
     * @return distance according to the specified metric
     */
    protected static float calculateTetrahedralDistance(Point3f point, TetreeKey tetIndex,
                                                        TetrahedralDistanceMetric metric) {
        if (point.x < 0 || point.y < 0 || point.z < 0) {
            throw new IllegalArgumentException("Point coordinates must be positive");
        }

        return switch (metric) {
            case EUCLIDEAN, MANHATTAN, CHEBYSHEV -> {
                Point3f center = tetrahedronCenter(tetIndex);
                yield calculateDistance(point, center, metric);
            }
            case TETRAHEDRAL_CENTROID -> {
                Point3f center = tetrahedronCenter(tetIndex);
                yield calculateDistance(point, center, TetrahedralDistanceMetric.EUCLIDEAN);
            }
            case TETRAHEDRAL_SURFACE -> distanceToTetrahedron(point, tetIndex);
        };
    }

    /**
     * Calculate distance between two tetrahedral regions using the specified metric
     *
     * @param tetIndex1 first tetrahedral SFC index
     * @param tetIndex2 second tetrahedral SFC index
     * @param metric    distance metric to use
     * @return distance between tetrahedra according to the specified metric
     */
    protected static float calculateTetrahedralDistance(TetreeKey tetIndex1, TetreeKey tetIndex2,
                                                        TetrahedralDistanceMetric metric) {
        if (tetIndex1 == tetIndex2) {
            return 0.0f;
        }

        return switch (metric) {
            case EUCLIDEAN, MANHATTAN, CHEBYSHEV, TETRAHEDRAL_CENTROID -> {
                Point3f center1 = tetrahedronCenter(tetIndex1);
                Point3f center2 = tetrahedronCenter(tetIndex2);
                yield calculateDistance(center1, center2, metric == TetrahedralDistanceMetric.TETRAHEDRAL_CENTROID
                                                          ? TetrahedralDistanceMetric.EUCLIDEAN : metric);
            }
            case TETRAHEDRAL_SURFACE -> {
                // For tetrahedral surface distance, we need to compute closest approach between tetrahedra
                // This is complex, so we'll approximate using closest vertices for now
                var tet1 = Tet.tetrahedron(tetIndex1);
                var tet2 = Tet.tetrahedron(tetIndex2);
                var vertices1 = tet1.coordinates();
                var vertices2 = tet2.coordinates();

                float minDistance = Float.MAX_VALUE;
                for (Point3i v1 : vertices1) {
                    for (Point3i v2 : vertices2) {
                        float dist = calculateDistance(new Point3f(v1.x, v1.y, v1.z), new Point3f(v2.x, v2.y, v2.z),
                                                       TetrahedralDistanceMetric.EUCLIDEAN);
                        minDistance = Math.min(minDistance, dist);
                    }
                }
                yield minDistance;
            }
        };
    }

    /**
     * Helper method to determine search bounds around a query point for tetrahedral operations. This uses
     * tetrahedral-specific geometry for optimal bound calculation.
     *
     * @param queryPoint center point for search bounds (must have positive coordinates)
     * @param radius     search radius
     * @return volume bounds for the search region
     */
    protected static com.hellblazer.luciferase.lucien.VolumeBounds calculateTetrahedralSearchBounds(Point3f queryPoint,
                                                                                                    float radius) {
        if (queryPoint.x < 0 || queryPoint.y < 0 || queryPoint.z < 0) {
            throw new IllegalArgumentException("Query point coordinates must be positive");
        }

        // Ensure bounds don't extend into negative coordinates (tetrahedral constraint)
        float minX = Math.max(0, queryPoint.x - radius);
        float minY = Math.max(0, queryPoint.y - radius);
        float minZ = Math.max(0, queryPoint.z - radius);

        float maxX = queryPoint.x + radius;
        float maxY = queryPoint.y + radius;
        float maxZ = queryPoint.z + radius;

        return new com.hellblazer.luciferase.lucien.VolumeBounds(minX, minY, minZ, maxX, maxY, maxZ);
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
    protected static float distanceToTetrahedron(Point3f point, BaseTetreeKey<? extends BaseTetreeKey> tetIndex) {
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

    /**
     * Helper method to estimate the expected number of tetrahedra within a search radius. This helps with performance
     * tuning and adaptive strategies.
     *
     * @param radius search radius
     * @param level  tetrahedral refinement level
     * @return estimated number of tetrahedra in the search region
     */
    protected static int estimateTetrahedraInRadius(float radius, byte level) {
        // Approximate volume of sphere: (4/3) * π * r³
        double sphereVolume = (4.0 / 3.0) * Math.PI * Math.pow(radius, 3);

        // Estimate tetrahedron size at given level
        // Each tetrahedron at level L has edge length approximately 2^(maxLevel - L)
        int maxLevel = Constants.getMaxRefinementLevel();
        double tetSize = Math.pow(2, maxLevel - level);
        double tetVolume = Math.pow(tetSize, 3) / 6.0; // Approximate tetrahedron volume

        // Estimate number of tetrahedra (with some padding for irregular shapes)
        return (int) Math.ceil(sphereVolume / tetVolume * 1.5);
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

    // Helper methods for geometric computations

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
    protected static boolean pointInTetrahedron(Point3f point, BaseTetreeKey<? extends BaseTetreeKey> tetIndex) {
        if (point.x < 0 || point.y < 0 || point.z < 0) {
            throw new IllegalArgumentException("Point coordinates must be positive");
        }

        var tet = Tet.tetrahedron(tetIndex);
        return pointInTetrahedron(point, tet);
    }

    /**
     * Test if a point is inside a tetrahedron using the existing Tet.contains method
     *
     * @param point the point to test (must have positive coordinates)
     * @param tet   the tetrahedron
     * @return true if point is inside the tetrahedron
     */
    protected static boolean pointInTetrahedron(Point3f point, Tet tet) {
        if (point.x < 0 || point.y < 0 || point.z < 0) {
            throw new IllegalArgumentException("Point coordinates must be positive");
        }

        // Use the existing Tet.contains method which uses proper orientation tests
        return tet.contains(point);
    }

    // ===== Distance Calculation Methods =====

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
     * @return center point of the tetrahedron  instead.
     */
    protected static Point3f tetrahedronCenter(BaseTetreeKey<? extends BaseTetreeKey> tetIndex) {
        var tet = Tet.tetrahedron(tetIndex);
        return tetrahedronCenter(tet);
    }

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

    // ===== Tetrahedral Priority Queue Classes =====

    /**
     * Compute the volume of a tetrahedron
     *
     * @param tetIndex the tetrahedral SFC index
     * @return volume of the tetrahedron
     */
    protected static double tetrahedronVolume(BaseTetreeKey<? extends BaseTetreeKey> tetIndex) {
        var tet = Tet.tetrahedron(tetIndex);
        return tetrahedronVolume(tet);
    }

    /**
     * Compute the volume of a tetrahedron
     *
     * @param tet the tetrahedron
     * @return volume of the tetrahedron
     */
    protected static double tetrahedronVolume(Tet tet) {
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
     * Get all simplicies within range using tetrahedral-specific optimization. This method provides the underlying
     * simplex-level access for advanced use cases.
     *
     * @param queryPoint query point (must have positive coordinates)
     * @param radius     search radius
     * @param config     search configuration with distance metric and other parameters
     * @return stream of simplicies within range
     */
    public abstract <Content> Stream<Simplex<Content>> getSimpliciesInRange(Point3f queryPoint, float radius,
                                                                            TetrahedralSearchConfig config);

    /**
     * Perform tetrahedral-specific k-nearest neighbor search using tetrahedral space-filling curve properties. This
     * method leverages the unique properties of tetrahedral decomposition for enhanced performance compared to generic
     * spatial index k-NN search.
     *
     * @param queryPoint query point (must have positive coordinates)
     * @param k          number of nearest neighbors to find
     * @param config     search configuration with distance metric and other parameters
     * @return list of k nearest neighbor IDs sorted by distance (closest first)
     */
    public abstract <ID> List<ID> kNearestNeighborsTetrahedral(Point3f queryPoint, int k,
                                                               TetrahedralSearchConfig config);

    /**
     * Perform tetrahedral-specific range query leveraging tetrahedral geometry for optimal performance. This method
     * uses tetrahedral space-filling curve properties to efficiently find all entities within the specified range.
     *
     * @param queryPoint query point (must have positive coordinates)
     * @param radius     search radius
     * @param config     search configuration with distance metric and other parameters
     * @return list of entity IDs within the specified range
     */
    public abstract <ID> List<ID> rangeQueryTetrahedral(Point3f queryPoint, float radius,
                                                        TetrahedralSearchConfig config);

    // ===== Abstract Search Methods =====

    /**
     * Distance metric for tetrahedral search operations
     */
    public enum TetrahedralDistanceMetric {
        /** 3D Euclidean distance */
        EUCLIDEAN,
        /** Manhattan (L1) distance */
        MANHATTAN,
        /** Chebyshev (L∞) distance */
        CHEBYSHEV,
        /** Tetrahedral centroid distance (distance between tetrahedron centers) */
        TETRAHEDRAL_CENTROID,
        /** Minimum distance to tetrahedron surface */
        TETRAHEDRAL_SURFACE
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
     * Priority queue entry for tetrahedral search operations combining entity ID with distance
     */
    public static class TetrahedralPriorityEntry<ID> {
        public final ID                        entityId;
        public final float                     distance;
        public final long                      tetIndex;
        public final TetrahedralDistanceMetric metric;

        public TetrahedralPriorityEntry(ID entityId, float distance, long tetIndex, TetrahedralDistanceMetric metric) {
            this.entityId = entityId;
            this.distance = distance;
            this.tetIndex = tetIndex;
            this.metric = metric;
        }

        @Override
        public String toString() {
            return String.format("Entry{id=%s, dist=%.3f, tet=%d, metric=%s}", entityId, distance, tetIndex, metric);
        }
    }

    // ===== Helper Methods for Concrete Implementations =====

    /**
     * Specialized priority queue for tetrahedral k-NN search with configurable distance metrics
     */
    public static class TetrahedralPriorityQueue<ID> {
        private final java.util.PriorityQueue<TetrahedralPriorityEntry<ID>> queue;
        private final int                                                   maxSize;
        private final TetrahedralDistanceMetric                             metric;

        /**
         * Create a max-heap priority queue for k-NN search
         *
         * @param k      maximum number of entries to keep
         * @param metric distance metric to use for comparisons
         */
        public TetrahedralPriorityQueue(int k, TetrahedralDistanceMetric metric) {
            this.maxSize = k;
            this.metric = metric;
            // Max heap: largest distance first (for k-NN search we want to keep k smallest)
            this.queue = new java.util.PriorityQueue<>(k, (a, b) -> Float.compare(b.distance, a.distance));
        }

        /**
         * Clear all entries from the queue
         */
        public void clear() {
            queue.clear();
        }

        /**
         * Get only the entity IDs sorted by distance (closest first)
         *
         * @return list of entity IDs ordered by increasing distance
         */
        public List<ID> getEntityIds() {
            return getResults().stream().map(entry -> entry.entityId).collect(Collectors.toList());
        }

        /**
         * Get the current maximum distance in the queue (useful for pruning)
         *
         * @return maximum distance, or Float.MAX_VALUE if queue is not full
         */
        public float getMaxDistance() {
            return queue.isEmpty() ? Float.MAX_VALUE : queue.peek().distance;
        }

        /**
         * Get all entries sorted by distance (closest first)
         *
         * @return list of entries ordered by increasing distance
         */
        public List<TetrahedralPriorityEntry<ID>> getResults() {
            List<TetrahedralPriorityEntry<ID>> results = new ArrayList<>(queue);
            // Sort by distance (ascending) since we use max-heap internally
            results.sort((a, b) -> Float.compare(a.distance, b.distance));
            return results;
        }

        /**
         * Get summary statistics for the current queue contents
         *
         * @return summary string with queue state
         */
        public String getSummary() {
            if (isEmpty()) {
                return "Empty queue";
            }

            var results = getResults();
            float minDist = results.get(0).distance;
            float maxDist = results.get(results.size() - 1).distance;

            return String.format("Queue: %d/%d entries, distance range [%.3f, %.3f], metric: %s", size(), maxSize,
                                 minDist, maxDist, metric);
        }

        /**
         * Check if queue is empty
         *
         * @return true if queue has no entries
         */
        public boolean isEmpty() {
            return queue.isEmpty();
        }

        /**
         * Check if queue is full
         *
         * @return true if queue has reached maximum capacity
         */
        public boolean isFull() {
            return queue.size() >= maxSize;
        }

        /**
         * Add an entry to the priority queue, maintaining max size
         *
         * @param entityId entity identifier
         * @param distance distance from query point
         * @param tetIndex tetrahedral SFC index where entity is located
         */
        public void offer(ID entityId, float distance, long tetIndex) {
            TetrahedralPriorityEntry<ID> entry = new TetrahedralPriorityEntry<>(entityId, distance, tetIndex, metric);

            if (queue.size() < maxSize) {
                queue.offer(entry);
            } else if (distance < queue.peek().distance) {
                // Replace the farthest entry with this closer one
                queue.poll();
                queue.offer(entry);
            }
        }

        /**
         * Get current size of the queue
         *
         * @return number of entries in queue
         */
        public int size() {
            return queue.size();
        }
    }

    /**
     * Search configuration for tetrahedral queries
     */
    public static class TetrahedralSearchConfig {
        public final TetrahedralDistanceMetric  distanceMetric;
        public final SimplexAggregationStrategy aggregationStrategy;
        public final float                      searchRadius;
        public final boolean                    useAdaptiveRadius;
        public final int                        maxExpansions;

        public TetrahedralSearchConfig(TetrahedralDistanceMetric distanceMetric,
                                       SimplexAggregationStrategy aggregationStrategy, float searchRadius,
                                       boolean useAdaptiveRadius, int maxExpansions) {
            this.distanceMetric = distanceMetric;
            this.aggregationStrategy = aggregationStrategy;
            this.searchRadius = searchRadius;
            this.useAdaptiveRadius = useAdaptiveRadius;
            this.maxExpansions = maxExpansions;
        }

        /**
         * Create default configuration for tetrahedral search
         */
        public static TetrahedralSearchConfig defaultConfig() {
            return new TetrahedralSearchConfig(TetrahedralDistanceMetric.EUCLIDEAN,
                                               SimplexAggregationStrategy.REPRESENTATIVE_ONLY, 1000.0f,
                                               // Default search radius
                                               true,       // Use adaptive radius expansion
                                               5           // Maximum search expansions
            );
        }

        /**
         * Create configuration for fast approximate search
         */
        public static TetrahedralSearchConfig fastApproximate() {
            return new TetrahedralSearchConfig(TetrahedralDistanceMetric.TETRAHEDRAL_CENTROID,
                                               SimplexAggregationStrategy.REPRESENTATIVE_ONLY, 2000.0f,
                                               // Larger radius for fast search
                                               false,      // No adaptive expansion
                                               1           // Single expansion only
            );
        }

        /**
         * Create configuration optimized for surface-based distance
         */
        public static TetrahedralSearchConfig surfaceOptimized() {
            return new TetrahedralSearchConfig(TetrahedralDistanceMetric.TETRAHEDRAL_SURFACE,
                                               SimplexAggregationStrategy.BEST_FIT, 500.0f,
                                               // Smaller initial radius for surface queries
                                               true, 3           // Fewer expansions for surface queries
            );
        }
    }

    /**
     * Group of simplicies representing the same spatial region (up to 6 tetrahedra per cubic region in tetrahedral
     * decomposition)
     */
    public static class SimplexGroup<Content> {
        public final List<Simplex<Content>>                 simplicies;
        public final Point3f                                groupCenter;
        public final float                                  groupVolume;
        public final BaseTetreeKey<? extends BaseTetreeKey> representativeIndex;

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

        private static <Content> BaseTetreeKey<? extends BaseTetreeKey> selectRepresentativeIndex(
        List<Simplex<Content>> simplicies) {
            // Select the simplex closest to the group center
            Point3f groupCenter = computeGroupCenter(simplicies);
            return simplicies.stream().min((s1, s2) -> {
                float dist1 = distanceToTetrahedron(groupCenter, s1.index());
                float dist2 = distanceToTetrahedron(groupCenter, s2.index());
                return Float.compare(dist1, dist2);
            }).map(Simplex::index).orElse(simplicies.get(0).index());
        }
    }
}
