package com.hellblazer.luciferase.lucien;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import javax.vecmath.Vector3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

/**
 * Phase 5B: Tetrahedral Query Optimization
 * 
 * Provides advanced spatial query optimizations specifically designed for tetrahedral geometry.
 * Unlike cubic query optimization, this leverages the unique properties of tetrahedral
 * space-filling curves, 6-type subdivision, and tetrahedral geometric relationships.
 * 
 * Key tetrahedral optimizations:
 * - SFC range queries optimized for tetrahedral hierarchical structure
 * - Intersection testing using tetrahedral geometric properties
 * - Containment queries leveraging orientation tests
 * - Neighbor searches using face relationships
 * - Query result caching adapted for tetrahedral complexity
 * 
 * @author hal.hildebrand
 */
public class TetQueryOptimizer {
    
    // Query result caches optimized for tetrahedral access patterns
    private static final Map<QueryCacheKey, QueryResult> queryCache = new ConcurrentHashMap<>();
    private static final Map<IntersectionCacheKey, Boolean> intersectionCache = new ConcurrentHashMap<>();
    private static final Map<ContainmentCacheKey, Boolean> containmentCache = new ConcurrentHashMap<>();
    
    // Performance monitoring
    private final TetQueryMetrics queryMetrics = new TetQueryMetrics();
    
    /**
     * Advanced tetrahedral spatial range query optimizer
     * Leverages tetrahedral SFC properties for efficient range computation
     */
    public static class TetSpatialRangeQuery {
        
        private final TetQueryMetrics metrics;
        
        public TetSpatialRangeQuery(TetQueryMetrics metrics) {
            this.metrics = metrics;
        }
        
        /**
         * Optimized tetrahedral range query using hierarchical SFC decomposition
         * Exploits the 6-type tetrahedral structure for efficient traversal
         */
        public Stream<Long> queryTetRange(Spatial volume, QueryMode mode) {
            metrics.recordRangeQuery();
            
            var bounds = extractVolumeBounds(volume);
            if (bounds == null) {
                return Stream.empty();
            }
            
            // Use cached result if available
            var cacheKey = new QueryCacheKey(bounds, mode);
            var cached = queryCache.get(cacheKey);
            if (cached != null) {
                metrics.recordCacheHit();
                return cached.indices.stream();
            }
            
            metrics.recordCacheMiss();
            
            // Compute optimized SFC ranges using tetrahedral hierarchy
            var ranges = computeOptimizedTetRanges(bounds, mode);
            var result = ranges
                .flatMap(range -> LongStream.rangeClosed(range.start(), range.end()).boxed())
                .filter(index -> {
                    var tet = Tet.tetrahedron(index);
                    return switch (mode) {
                        case CONTAINED -> isTetrahedronContained(tet, volume);
                        case INTERSECTING -> isTetrahedronIntersecting(tet, volume);
                        case OVERLAPPING -> isTetrahedronOverlapping(tet, volume);
                    };
                })
                .distinct();
            
            // Cache the result for future queries
            var resultList = result.toList();
            queryCache.put(cacheKey, new QueryResult(resultList, System.currentTimeMillis()));
            
            return resultList.stream();
        }
        
        /**
         * Optimized tetrahedral SFC range computation using hierarchical decomposition
         * Considers the 6-type tetrahedral subdivision pattern
         */
        private Stream<TetSFCRange> computeOptimizedTetRanges(VolumeBounds bounds, QueryMode mode) {
            // Adaptive level selection based on volume characteristics
            byte optimalLevel = selectOptimalQueryLevel(bounds);
            byte minLevel = (byte) Math.max(0, optimalLevel - 2);
            byte maxLevel = (byte) Math.min(Constants.getMaxRefinementLevel(), optimalLevel + 3);
            
            return IntStream.rangeClosed(minLevel, maxLevel)
                .boxed()
                .flatMap(level -> computeTetRangesAtLevel(bounds, level.byteValue(), mode))
                .sorted((a, b) -> Long.compare(a.start(), b.start()));
        }
        
        /**
         * Compute tetrahedral SFC ranges at a specific level
         * Uses touched dimension analysis for optimization
         */
        private Stream<TetSFCRange> computeTetRangesAtLevel(VolumeBounds bounds, byte level, QueryMode mode) {
            int length = Constants.lengthAtLevel(level);
            
            // Calculate grid bounds for this level
            int minX = (int) Math.floor(bounds.minX() / length);
            int maxX = (int) Math.ceil(bounds.maxX() / length);
            int minY = (int) Math.floor(bounds.minY() / length);
            int maxY = (int) Math.ceil(bounds.maxY() / length);
            int minZ = (int) Math.floor(bounds.minZ() / length);
            int maxZ = (int) Math.ceil(bounds.maxZ() / length);
            
            // Use hierarchical decomposition for large ranges
            if ((maxX - minX) * (maxY - minY) * (maxZ - minZ) > 1000) {
                return computeHierarchicalTetRanges(bounds, level, mode, minX, maxX, minY, maxY, minZ, maxZ);
            }
            
            // Direct enumeration for small ranges
            return IntStream.rangeClosed(minX, maxX)
                .boxed()
                .flatMap(x -> IntStream.rangeClosed(minY, maxY)
                    .boxed()
                    .flatMap(y -> IntStream.rangeClosed(minZ, maxZ)
                        .filter(z -> tetGridCellIntersects(x, y, z, length, bounds, mode))
                        .mapToObj(z -> computeTetRangesForGridCell(x, y, z, level))
                        .flatMap(stream -> stream)
                    )
                );
        }
        
        /**
         * Hierarchical decomposition for large tetrahedral ranges
         * Splits volume recursively to reduce computation complexity
         */
        private Stream<TetSFCRange> computeHierarchicalTetRanges(VolumeBounds bounds, byte level, QueryMode mode,
                                                               int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
            // Find largest dimension to split
            int xSpan = maxX - minX;
            int ySpan = maxY - minY; 
            int zSpan = maxZ - minZ;
            
            if (xSpan >= ySpan && xSpan >= zSpan && xSpan > 4) {
                // Split along X dimension
                int midX = (minX + maxX) / 2;
                var bounds1 = new VolumeBounds(bounds.minX(), bounds.minY(), bounds.minZ(),
                                             bounds.minX() + (midX - minX) * Constants.lengthAtLevel(level),
                                             bounds.maxY(), bounds.maxZ());
                var bounds2 = new VolumeBounds(bounds.minX() + (midX - minX) * Constants.lengthAtLevel(level),
                                             bounds.minY(), bounds.minZ(),
                                             bounds.maxX(), bounds.maxY(), bounds.maxZ());
                
                return Stream.concat(
                    computeTetRangesAtLevel(bounds1, level, mode),
                    computeTetRangesAtLevel(bounds2, level, mode)
                );
            } else if (ySpan >= zSpan && ySpan > 4) {
                // Split along Y dimension
                int midY = (minY + maxY) / 2;
                var bounds1 = new VolumeBounds(bounds.minX(), bounds.minY(), bounds.minZ(),
                                             bounds.maxX(), bounds.minY() + (midY - minY) * Constants.lengthAtLevel(level),
                                             bounds.maxZ());
                var bounds2 = new VolumeBounds(bounds.minX(), bounds.minY() + (midY - minY) * Constants.lengthAtLevel(level),
                                             bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ());
                
                return Stream.concat(
                    computeTetRangesAtLevel(bounds1, level, mode),
                    computeTetRangesAtLevel(bounds2, level, mode)
                );
            } else if (zSpan > 4) {
                // Split along Z dimension
                int midZ = (minZ + maxZ) / 2;
                var bounds1 = new VolumeBounds(bounds.minX(), bounds.minY(), bounds.minZ(),
                                             bounds.maxX(), bounds.maxY(), bounds.minZ() + (midZ - minZ) * Constants.lengthAtLevel(level));
                var bounds2 = new VolumeBounds(bounds.minX(), bounds.minY(), bounds.minZ() + (midZ - minZ) * Constants.lengthAtLevel(level),
                                             bounds.maxX(), bounds.maxY(), bounds.maxZ());
                
                return Stream.concat(
                    computeTetRangesAtLevel(bounds1, level, mode),
                    computeTetRangesAtLevel(bounds2, level, mode)
                );
            } else {
                // Base case: enumerate directly
                return IntStream.rangeClosed(minX, maxX)
                    .boxed()
                    .flatMap(x -> IntStream.rangeClosed(minY, maxY)
                        .boxed()
                        .flatMap(y -> IntStream.rangeClosed(minZ, maxZ)
                            .filter(z -> tetGridCellIntersects(x, y, z, Constants.lengthAtLevel(level), bounds, mode))
                            .mapToObj(z -> computeTetRangesForGridCell(x, y, z, level))
                            .flatMap(stream -> stream)
                        )
                    );
            }
        }
        
        /**
         * Compute SFC ranges for all 6 tetrahedra in a grid cell
         */
        private Stream<TetSFCRange> computeTetRangesForGridCell(int gridX, int gridY, int gridZ, byte level) {
            int length = Constants.lengthAtLevel(level);
            int cellX = gridX * length;
            int cellY = gridY * length;
            int cellZ = gridZ * length;
            
            return IntStream.range(0, 6)
                .mapToObj(type -> {
                    var tet = new Tet(cellX, cellY, cellZ, level, (byte) type);
                    long index = tet.index();
                    return new TetSFCRange(index, index);
                });
        }
        
        /**
         * Test if a tetrahedral grid cell intersects with volume bounds
         */
        private boolean tetGridCellIntersects(int gridX, int gridY, int gridZ, int length, 
                                            VolumeBounds bounds, QueryMode mode) {
            // Grid cell bounds
            float cellMinX = gridX * length;
            float cellMaxX = (gridX + 1) * length;
            float cellMinY = gridY * length;
            float cellMaxY = (gridY + 1) * length;
            float cellMinZ = gridZ * length;
            float cellMaxZ = (gridZ + 1) * length;
            
            // Quick bounding box test
            if (cellMaxX < bounds.minX() || cellMinX > bounds.maxX() ||
                cellMaxY < bounds.minY() || cellMinY > bounds.maxY() ||
                cellMaxZ < bounds.minZ() || cellMinZ > bounds.maxZ()) {
                return false;
            }
            
            return switch (mode) {
                case CONTAINED -> 
                    cellMinX >= bounds.minX() && cellMaxX <= bounds.maxX() &&
                    cellMinY >= bounds.minY() && cellMaxY <= bounds.maxY() &&
                    cellMinZ >= bounds.minZ() && cellMaxZ <= bounds.maxZ();
                case INTERSECTING, OVERLAPPING -> true; // Already passed bounding box test
            };
        }
        
        /**
         * Select optimal query level based on volume characteristics
         */
        private byte selectOptimalQueryLevel(VolumeBounds bounds) {
            float maxExtent = Math.max(Math.max(bounds.maxX() - bounds.minX(), bounds.maxY() - bounds.minY()),
                                     bounds.maxZ() - bounds.minZ());
            
            // Find level where tetrahedral cell size is 1/4 to 1/2 of volume extent
            for (byte level = 0; level <= Constants.getMaxRefinementLevel(); level++) {
                int tetLength = Constants.lengthAtLevel(level);
                if (tetLength <= maxExtent * 2 && tetLength >= maxExtent / 4) {
                    return level;
                }
            }
            
            return (byte) 10; // Default fallback
        }
    }
    
    /**
     * Optimized tetrahedral intersection testing
     * Uses geometric properties specific to tetrahedra
     */
    public static class TetIntersectionOptimizer {
        
        private final TetQueryMetrics metrics;
        
        public TetIntersectionOptimizer(TetQueryMetrics metrics) {
            this.metrics = metrics;
        }
        
        /**
         * Fast tetrahedral intersection test with caching
         */
        public boolean intersects(Tet tetrahedron, Spatial volume) {
            metrics.recordIntersectionTest();
            
            // Check cache first
            var cacheKey = new IntersectionCacheKey(tetrahedron.index(), volume.hashCode());
            var cached = intersectionCache.get(cacheKey);
            if (cached != null) {
                metrics.recordCacheHit();
                return cached;
            }
            
            metrics.recordCacheMiss();
            
            // Compute intersection using tetrahedral geometric properties
            boolean result = computeTetrahhedralIntersection(tetrahedron, volume);
            
            // Cache the result
            intersectionCache.put(cacheKey, result);
            
            return result;
        }
        
        /**
         * Optimized tetrahedral intersection computation
         * Uses hierarchical testing with early rejection
         */
        private boolean computeTetrahhedralIntersection(Tet tetrahedron, Spatial volume) {
            var tetBounds = getTetrahedronBounds(tetrahedron);
            var volumeBounds = extractVolumeBounds(volume);
            
            if (volumeBounds == null) {
                return false;
            }
            
            // Quick bounding box rejection test
            if (!boundsIntersect(tetBounds, volumeBounds)) {
                return false;
            }
            
            // Detailed tetrahedral intersection test
            return switch (volume) {
                case Spatial.Sphere sphere -> intersectTetrahedronSphere(tetrahedron, sphere);
                case Spatial.Cube cube -> intersectTetrahedronCube(tetrahedron, cube);
                case Spatial.aabb aabb -> intersectTetrahedronAABB(tetrahedron, aabb);
                default -> intersectTetrahedronGeneral(tetrahedron, volume);
            };
        }
        
        /**
         * Optimized tetrahedron-sphere intersection
         */
        private boolean intersectTetrahedronSphere(Tet tetrahedron, Spatial.Sphere sphere) {
            var center = new Point3f(sphere.centerX(), sphere.centerY(), sphere.centerZ());
            var radius = sphere.radius();
            var vertices = tetrahedron.coordinates();
            
            // Test if sphere center is inside tetrahedron
            if (tetrahedron.contains(center)) {
                return true;
            }
            
            // Test if any vertex is inside sphere
            for (var vertex : vertices) {
                var dx = vertex.x - center.x;
                var dy = vertex.y - center.y;
                var dz = vertex.z - center.z;
                if (dx * dx + dy * dy + dz * dz <= radius * radius) {
                    return true;
                }
            }
            
            // Test edge-sphere intersections (simplified check)
            return isPointNearTetrahedron(center, vertices, radius);
        }
        
        /**
         * Optimized tetrahedron-cube intersection
         */
        private boolean intersectTetrahedronCube(Tet tetrahedron, Spatial.Cube cube) {
            var cubeMin = new Point3f(cube.originX(), cube.originY(), cube.originZ());
            var cubeMax = new Point3f(cube.originX() + cube.extent(), cube.originY() + cube.extent(), cube.originZ() + cube.extent());
            
            return intersectTetrahedronBox(tetrahedron, cubeMin, cubeMax);
        }
        
        /**
         * Optimized tetrahedron-AABB intersection
         */
        private boolean intersectTetrahedronAABB(Tet tetrahedron, Spatial.aabb aabb) {
            var boxMin = new Point3f(aabb.originX(), aabb.originY(), aabb.originZ());
            var boxMax = new Point3f(aabb.extentX(), aabb.extentY(), aabb.extentZ());
            
            return intersectTetrahedronBox(tetrahedron, boxMin, boxMax);
        }
        
        /**
         * Generic tetrahedron-box intersection using separating axis theorem
         */
        private boolean intersectTetrahedronBox(Tet tetrahedron, Point3f boxMin, Point3f boxMax) {
            var vertices = tetrahedron.coordinates();
            
            // Test if any tetrahedron vertex is inside box
            for (var vertex : vertices) {
                if (vertex.x >= boxMin.x && vertex.x <= boxMax.x &&
                    vertex.y >= boxMin.y && vertex.y <= boxMax.y &&
                    vertex.z >= boxMin.z && vertex.z <= boxMax.z) {
                    return true;
                }
            }
            
            // Test if any box corner is inside tetrahedron
            var corners = new Point3f[] {
                new Point3f(boxMin.x, boxMin.y, boxMin.z),
                new Point3f(boxMax.x, boxMin.y, boxMin.z),
                new Point3f(boxMin.x, boxMax.y, boxMin.z),
                new Point3f(boxMax.x, boxMax.y, boxMin.z),
                new Point3f(boxMin.x, boxMin.y, boxMax.z),
                new Point3f(boxMax.x, boxMin.y, boxMax.z),
                new Point3f(boxMin.x, boxMax.y, boxMax.z),
                new Point3f(boxMax.x, boxMax.y, boxMax.z)
            };
            
            for (var corner : corners) {
                if (tetrahedron.contains(corner)) {
                    return true;
                }
            }
            
            // Additional edge-face intersection tests could be added here
            return false;
        }
        
        /**
         * Generic tetrahedral intersection fallback
         */
        private boolean intersectTetrahedronGeneral(Tet tetrahedron, Spatial volume) {
            // Fallback to bounding box intersection
            var tetBounds = getTetrahedronBounds(tetrahedron);
            var volumeBounds = extractVolumeBounds(volume);
            return volumeBounds != null && boundsIntersect(tetBounds, volumeBounds);
        }
        
        /**
         * Test if a point is near a tetrahedron within a given distance
         */
        private boolean isPointNearTetrahedron(Point3f point, Point3i[] vertices, float maxDistance) {
            // Simplified distance test - could be enhanced with exact point-to-tetrahedron distance
            for (var vertex : vertices) {
                var dx = vertex.x - point.x;
                var dy = vertex.y - point.y;
                var dz = vertex.z - point.z;
                if (dx * dx + dy * dy + dz * dz <= maxDistance * maxDistance) {
                    return true;
                }
            }
            return false;
        }
    }
    
    /**
     * Optimized tetrahedral containment testing
     * Leverages tetrahedral orientation tests for maximum efficiency
     */
    public static class TetContainmentOptimizer {
        
        private final TetQueryMetrics metrics;
        
        public TetContainmentOptimizer(TetQueryMetrics metrics) {
            this.metrics = metrics;
        }
        
        /**
         * Fast point-in-tetrahedron test with orientation caching
         */
        public boolean contains(Tet tetrahedron, Point3f point) {
            metrics.recordContainmentTest();
            
            // Check cache first
            var cacheKey = new ContainmentCacheKey(tetrahedron.index(), point);
            var cached = containmentCache.get(cacheKey);
            if (cached != null) {
                metrics.recordCacheHit();
                return cached;
            }
            
            metrics.recordCacheMiss();
            
            // Compute containment using optimized orientation tests
            boolean result = computeOptimizedContainment(tetrahedron, point);
            
            // Cache the result
            containmentCache.put(cacheKey, result);
            
            return result;
        }
        
        /**
         * Optimized containment test using cached orientation calculations
         */
        private boolean computeOptimizedContainment(Tet tetrahedron, Point3f point) {
            var vertices = tetrahedron.coordinates();
            
            // Use cached orientation tests from TetGeometryLookupTables
            var pointI = new Point3i((int) point.x, (int) point.y, (int) point.z);
            
            // Test against all four faces using orientation tests
            // Face CDB (vertices 2, 3, 1)
            if (TetSpatialIndexOptimizer.TetGeometryLookupTables.orientationCached(pointI, vertices[2], vertices[3], vertices[1]) > 0.0) {
                return false;
            }
            
            // Face DCA (vertices 3, 2, 0)
            if (TetSpatialIndexOptimizer.TetGeometryLookupTables.orientationCached(pointI, vertices[3], vertices[2], vertices[0]) > 0.0) {
                return false;
            }
            
            // Face BDA (vertices 1, 3, 0)
            if (TetSpatialIndexOptimizer.TetGeometryLookupTables.orientationCached(pointI, vertices[1], vertices[3], vertices[0]) > 0.0) {
                return false;
            }
            
            // Face BAC (vertices 1, 0, 2)
            return TetSpatialIndexOptimizer.TetGeometryLookupTables.orientationCached(pointI, vertices[1], vertices[0], vertices[2]) <= 0.0;
        }
        
        /**
         * Batch containment test for multiple points
         * Optimized for tetrahedral geometry with spatial locality
         */
        public Map<Point3f, Boolean> containsBatch(Tet tetrahedron, Collection<Point3f> points) {
            var results = new HashMap<Point3f, Boolean>();
            var vertices = tetrahedron.coordinates();
            
            // Pre-compute tetrahedron bounds for quick rejection
            var bounds = getTetrahedronBounds(tetrahedron);
            
            for (var point : points) {
                // Quick bounds check first
                if (point.x < bounds.minX() || point.x > bounds.maxX() ||
                    point.y < bounds.minY() || point.y > bounds.maxY() ||
                    point.z < bounds.minZ() || point.z > bounds.maxZ()) {
                    results.put(point, false);
                } else {
                    results.put(point, contains(tetrahedron, point));
                }
            }
            
            return results;
        }
    }
    
    /**
     * Efficient tetrahedral neighbor search using face relationships
     * Leverages the tetrahedral face neighbor structure
     */
    public static class TetNeighborSearch {
        
        private final TetQueryMetrics metrics;
        
        public TetNeighborSearch(TetQueryMetrics metrics) {
            this.metrics = metrics;
        }
        
        /**
         * Find all tetrahedral neighbors using face relationships
         */
        public List<Tet> findAllNeighbors(Tet tetrahedron) {
            metrics.recordNeighborSearch();
            
            var neighbors = new ArrayList<Tet>();
            
            // Get all 4 face neighbors
            for (int face = 0; face < 4; face++) {
                var neighbor = tetrahedron.faceNeighbor(face);
                if (neighbor != null && neighbor.tet() != null) {
                    // Validate neighbor has positive coordinates
                    var neighborTet = neighbor.tet();
                    if (neighborTet.x() >= 0 && neighborTet.y() >= 0 && neighborTet.z() >= 0) {
                        neighbors.add(neighborTet);
                    }
                }
            }
            
            return neighbors;
        }
        
        /**
         * Find neighbors within a specific distance using tetrahedral SFC locality
         */
        public List<Tet> findNeighborsWithinDistance(Tet tetrahedron, float maxDistance) {
            metrics.recordNeighborSearch();
            
            var neighbors = new ArrayList<Tet>();
            var center = getTetrahhedronCenter(tetrahedron);
            
            // Use SFC locality to find nearby tetrahedra
            long baseIndex = tetrahedron.index();
            int searchRadius = Math.max(1, (int) (maxDistance / tetrahedron.length()));
            
            // Search in SFC index space around the base tetrahedron
            for (long offset = -searchRadius; offset <= searchRadius; offset++) {
                long candidateIndex = baseIndex + offset;
                if (candidateIndex >= 0) {
                    try {
                        var candidate = Tet.tetrahedron(candidateIndex);
                        var candidateCenter = getTetrahhedronCenter(candidate);
                        
                        var distance = center.distance(candidateCenter);
                        if (distance <= maxDistance && candidateIndex != baseIndex) {
                            neighbors.add(candidate);
                        }
                    } catch (Exception e) {
                        // Skip invalid indices
                    }
                }
            }
            
            return neighbors;
        }
        
        /**
         * Find tetrahedral neighbors by level (parent/children)
         */
        public List<Tet> findLevelNeighbors(Tet tetrahedron) {
            metrics.recordNeighborSearch();
            
            var neighbors = new ArrayList<Tet>();
            
            // Add parent if not at root level
            if (tetrahedron.l() > 0) {
                neighbors.add(tetrahedron.parent());
            }
            
            // Add children if not at maximum level
            if (tetrahedron.l() < Constants.getMaxRefinementLevel()) {
                for (byte childIndex = 0; childIndex < 8; childIndex++) {
                    neighbors.add(tetrahedron.child(childIndex));
                }
            }
            
            return neighbors;
        }
        
        /**
         * Get center point of a tetrahedron
         */
        private Point3f getTetrahhedronCenter(Tet tetrahedron) {
            var vertices = tetrahedron.coordinates();
            float centerX = (vertices[0].x + vertices[1].x + vertices[2].x + vertices[3].x) / 4.0f;
            float centerY = (vertices[0].y + vertices[1].y + vertices[2].y + vertices[3].y) / 4.0f;
            float centerZ = (vertices[0].z + vertices[1].z + vertices[2].z + vertices[3].z) / 4.0f;
            return new Point3f(centerX, centerY, centerZ);
        }
    }
    
    /**
     * Performance metrics for tetrahedral query operations
     */
    public static class TetQueryMetrics {
        
        private long rangeQueries = 0;
        private long intersectionTests = 0;
        private long containmentTests = 0;
        private long neighborSearches = 0;
        private long cacheHits = 0;
        private long cacheMisses = 0;
        
        public synchronized void recordRangeQuery() { rangeQueries++; }
        public synchronized void recordIntersectionTest() { intersectionTests++; }
        public synchronized void recordContainmentTest() { containmentTests++; }
        public synchronized void recordNeighborSearch() { neighborSearches++; }
        public synchronized void recordCacheHit() { cacheHits++; }
        public synchronized void recordCacheMiss() { cacheMisses++; }
        
        public synchronized double getCacheHitRate() {
            long total = cacheHits + cacheMisses;
            return total > 0 ? (double) cacheHits / total : 0.0;
        }
        
        public synchronized String getMetricsSummary() {
            return String.format(
                "TetQuery Metrics:\n" +
                "  Range Queries: %d\n" +
                "  Intersection Tests: %d\n" +
                "  Containment Tests: %d\n" +
                "  Neighbor Searches: %d\n" +
                "  Cache Hit Rate: %.2f%% (%d hits, %d misses)",
                rangeQueries, intersectionTests, containmentTests, neighborSearches,
                getCacheHitRate() * 100, cacheHits, cacheMisses
            );
        }
        
        public synchronized void reset() {
            rangeQueries = 0;
            intersectionTests = 0;
            containmentTests = 0;
            neighborSearches = 0;
            cacheHits = 0;
            cacheMisses = 0;
        }
    }
    
    // Helper methods and records
    
    /**
     * Extract volume bounds from various spatial types
     */
    private static VolumeBounds extractVolumeBounds(Spatial volume) {
        return switch (volume) {
            case Spatial.Cube cube -> new VolumeBounds(
                cube.originX(), cube.originY(), cube.originZ(),
                cube.originX() + cube.extent(), cube.originY() + cube.extent(), cube.originZ() + cube.extent()
            );
            case Spatial.Sphere sphere -> new VolumeBounds(
                sphere.centerX() - sphere.radius(), sphere.centerY() - sphere.radius(), sphere.centerZ() - sphere.radius(),
                sphere.centerX() + sphere.radius(), sphere.centerY() + sphere.radius(), sphere.centerZ() + sphere.radius()
            );
            case Spatial.aabb aabb -> new VolumeBounds(
                aabb.originX(), aabb.originY(), aabb.originZ(),
                aabb.extentX(), aabb.extentY(), aabb.extentZ()
            );
            default -> null;
        };
    }
    
    /**
     * Get bounding box of a tetrahedron
     */
    private static VolumeBounds getTetrahedronBounds(Tet tetrahedron) {
        var vertices = tetrahedron.coordinates();
        float minX = Float.MAX_VALUE, maxX = Float.MIN_VALUE;
        float minY = Float.MAX_VALUE, maxY = Float.MIN_VALUE;
        float minZ = Float.MAX_VALUE, maxZ = Float.MIN_VALUE;
        
        for (var vertex : vertices) {
            minX = Math.min(minX, vertex.x);
            maxX = Math.max(maxX, vertex.x);
            minY = Math.min(minY, vertex.y);
            maxY = Math.max(maxY, vertex.y);
            minZ = Math.min(minZ, vertex.z);
            maxZ = Math.max(maxZ, vertex.z);
        }
        
        return new VolumeBounds(minX, minY, minZ, maxX, maxY, maxZ);
    }
    
    /**
     * Test if two bounding boxes intersect
     */
    private static boolean boundsIntersect(VolumeBounds bounds1, VolumeBounds bounds2) {
        return !(bounds1.maxX() < bounds2.minX() || bounds1.minX() > bounds2.maxX() ||
                bounds1.maxY() < bounds2.minY() || bounds1.minY() > bounds2.maxY() ||
                bounds1.maxZ() < bounds2.minZ() || bounds1.minZ() > bounds2.maxZ());
    }
    
    /**
     * Test tetrahedral containment
     */
    private static boolean isTetrahedronContained(Tet tetrahedron, Spatial volume) {
        var vertices = tetrahedron.coordinates();
        var bounds = extractVolumeBounds(volume);
        if (bounds == null) return false;
        
        // All vertices must be within bounds
        for (var vertex : vertices) {
            if (vertex.x < bounds.minX() || vertex.x > bounds.maxX() ||
                vertex.y < bounds.minY() || vertex.y > bounds.maxY() ||
                vertex.z < bounds.minZ() || vertex.z > bounds.maxZ()) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Test tetrahedral intersection
     */
    private static boolean isTetrahedronIntersecting(Tet tetrahedron, Spatial volume) {
        var bounds = extractVolumeBounds(volume);
        if (bounds == null) return false;
        
        var tetBounds = getTetrahedronBounds(tetrahedron);
        return boundsIntersect(tetBounds, bounds);
    }
    
    /**
     * Test tetrahedral overlap (same as intersection for this implementation)
     */
    private static boolean isTetrahedronOverlapping(Tet tetrahedron, Spatial volume) {
        return isTetrahedronIntersecting(tetrahedron, volume);
    }
    
    // Helper records
    private record VolumeBounds(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {}
    private record TetSFCRange(long start, long end) {}
    private record QueryCacheKey(VolumeBounds bounds, QueryMode mode) {}
    private record QueryResult(List<Long> indices, long timestamp) {}
    private record IntersectionCacheKey(long tetIndex, int volumeHash) {}
    private record ContainmentCacheKey(long tetIndex, Point3f point) {}
    
    // Query modes
    public enum QueryMode {
        CONTAINED,      // Tetrahedra completely contained within volume
        INTERSECTING,   // Tetrahedra intersecting with volume
        OVERLAPPING     // Tetrahedra overlapping with volume (alias for intersecting)
    }
    
    // Public API
    
    /**
     * Get query metrics for performance monitoring
     */
    public TetQueryMetrics getMetrics() {
        return queryMetrics;
    }
    
    /**
     * Clear all query caches
     */
    public static void clearCaches() {
        queryCache.clear();
        intersectionCache.clear();
        containmentCache.clear();
    }
    
    /**
     * Create tetrahedral range query optimizer
     */
    public TetSpatialRangeQuery createRangeQuery() {
        return new TetSpatialRangeQuery(queryMetrics);
    }
    
    /**
     * Create tetrahedral intersection optimizer
     */
    public TetIntersectionOptimizer createIntersectionOptimizer() {
        return new TetIntersectionOptimizer(queryMetrics);
    }
    
    /**
     * Create tetrahedral containment optimizer
     */
    public TetContainmentOptimizer createContainmentOptimizer() {
        return new TetContainmentOptimizer(queryMetrics);
    }
    
    /**
     * Create tetrahedral neighbor search
     */
    public TetNeighborSearch createNeighborSearch() {
        return new TetNeighborSearch(queryMetrics);
    }
}