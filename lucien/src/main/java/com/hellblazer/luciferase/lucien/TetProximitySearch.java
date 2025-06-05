package com.hellblazer.luciferase.lucien;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Proximity search implementation for Tetree
 * Finds tetrahedra based on distance relationships and proximity criteria in tetrahedral space
 * All operations are constrained to positive coordinates only, as required by tetrahedral SFC
 * 
 * @author hal.hildebrand
 */
public class TetProximitySearch extends TetrahedralSearchBase {

    /**
     * Proximity result with detailed distance information for tetrahedral space
     */
    public static class ProximityResult<Content> {
        public final long index;
        public final Content content;
        public final Tet tetrahedron;
        public final Point3f tetrahedronCenter;
        public final float distanceToQuery;
        public final float minDistanceToQuery; // minimum distance from query point to tetrahedron surface
        public final float maxDistanceToQuery; // maximum distance from query point to tetrahedron vertex
        public final ProximityType proximityType;

        public ProximityResult(long index, Content content, Tet tetrahedron, Point3f tetrahedronCenter,
                             float distanceToQuery, float minDistanceToQuery, float maxDistanceToQuery,
                             ProximityType proximityType) {
            this.index = index;
            this.content = content;
            this.tetrahedron = tetrahedron;
            this.tetrahedronCenter = tetrahedronCenter;
            this.distanceToQuery = distanceToQuery;
            this.minDistanceToQuery = minDistanceToQuery;
            this.maxDistanceToQuery = maxDistanceToQuery;
            this.proximityType = proximityType;
        }
    }

    /**
     * Type of proximity relationship in tetrahedral space
     */
    public enum ProximityType {
        VERY_CLOSE,    // Within very close range
        CLOSE,         // Within close range
        MODERATE,      // Within moderate range
        FAR,           // Within far range
        VERY_FAR       // Beyond far range (typically not returned in proximity results)
    }

    /**
     * Distance range specification for proximity queries in tetrahedral space
     */
    public static class DistanceRange {
        public final float minDistance;
        public final float maxDistance;
        public final ProximityType proximityType;
        
        public DistanceRange(float minDistance, float maxDistance, ProximityType proximityType) {
            if (minDistance < 0 || maxDistance < 0) {
                throw new IllegalArgumentException("Distances must be non-negative");
            }
            if (maxDistance < minDistance) {
                throw new IllegalArgumentException("Max distance must be >= min distance");
            }
            this.minDistance = minDistance;
            this.maxDistance = maxDistance;
            this.proximityType = proximityType;
        }
        
        public boolean contains(float distance) {
            return distance >= minDistance && distance <= maxDistance;
        }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof DistanceRange)) return false;
            DistanceRange other = (DistanceRange) obj;
            return Float.compare(minDistance, other.minDistance) == 0 &&
                   Float.compare(maxDistance, other.maxDistance) == 0 &&
                   proximityType == other.proximityType;
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(minDistance, maxDistance, proximityType);
        }
        
        @Override
        public String toString() {
            return String.format("DistanceRange[%.2f-%.2f, %s]", minDistance, maxDistance, proximityType);
        }
    }

    /**
     * Find tetrahedra within a specific distance range from a query point
     * 
     * @param queryPoint the reference point for distance calculations (positive coordinates only)
     * @param distanceRange the distance range to search within
     * @param tetree the tetree to search in
     * @return list of tetrahedra within the distance range, sorted by distance
     * @throws IllegalArgumentException if query point has negative coordinates
     */
    public static <Content> List<ProximityResult<Content>> tetrahedraWithinDistanceRange(
            Point3f queryPoint, DistanceRange distanceRange, Tetree<Content> tetree) {
        
        return tetrahedraWithinDistanceRange(queryPoint, distanceRange, tetree, SimplexAggregationStrategy.ALL_SIMPLICIES);
    }

    /**
     * Find tetrahedra within a specific distance range from a query point, with configurable simplex aggregation
     * 
     * @param queryPoint the reference point for distance calculations (positive coordinates only)
     * @param distanceRange the distance range to search within
     * @param tetree the tetree to search in
     * @param strategy how to aggregate multiple simplicies per spatial region
     * @return list of tetrahedra within the distance range, sorted by distance
     * @throws IllegalArgumentException if query point has negative coordinates
     */
    public static <Content> List<ProximityResult<Content>> tetrahedraWithinDistanceRange(
            Point3f queryPoint, DistanceRange distanceRange, Tetree<Content> tetree,
            SimplexAggregationStrategy strategy) {
        
        validatePositiveCoordinates(queryPoint);
        
        // Create spatial AABB for spatial query optimization
        float searchRadius = distanceRange.maxDistance * 1.1f; // Add buffer for safety
        Spatial.aabb searchBounds = createSearchBounds(queryPoint, searchRadius);
        
        // Use tetree spatial query to get relevant simplicies
        Stream<Tetree.Simplex<Content>> candidateSimplicies = tetree.boundedBy(searchBounds);
        
        // Apply distance filtering
        Stream<Tetree.Simplex<Content>> filteredSimplicies = candidateSimplicies
            .filter(simplex -> {
                float minDistance = calculateMinDistanceToTetrahedron(queryPoint, simplex.index());
                float maxDistance = calculateMaxDistanceToTetrahedron(queryPoint, simplex.index());
                // Check if the tetrahedron overlaps with the distance range
                return minDistance <= distanceRange.maxDistance && maxDistance >= distanceRange.minDistance;
            });

        // Apply simplex aggregation strategy
        List<Tetree.Simplex<Content>> aggregatedSimplicies = aggregateSimplicies(filteredSimplicies, strategy);
        
        // Convert to proximity results with distance information
        List<ProximityResult<Content>> results = aggregatedSimplicies.stream()
            .map(simplex -> {
                Point3f tetCenter = tetrahedronCenter(simplex.index());
                float centerDistance = calculateDistance(queryPoint, tetCenter);
                float minDistance = calculateMinDistanceToTetrahedron(queryPoint, simplex.index());
                float maxDistance = calculateMaxDistanceToTetrahedron(queryPoint, simplex.index());
                
                return new ProximityResult<>(
                    simplex.index(), 
                    simplex.cell(), 
                    Tet.tetrahedron(simplex.index()),
                    tetCenter,
                    centerDistance,
                    minDistance,
                    maxDistance,
                    distanceRange.proximityType
                );
            })
            .collect(Collectors.toList());

        // Sort by center distance
        results.sort(Comparator.comparing(pr -> pr.distanceToQuery));
        
        return results;
    }

    /**
     * Find tetrahedra within multiple distance ranges (proximity bands)
     * 
     * @param queryPoint the reference point for distance calculations (positive coordinates only)
     * @param distanceRanges list of distance ranges to search within
     * @param tetree the tetree to search in
     * @return map of distance ranges to their proximity results
     * @throws IllegalArgumentException if query point has negative coordinates
     */
    public static <Content> Map<DistanceRange, List<ProximityResult<Content>>> tetrahedraInProximityBands(
            Point3f queryPoint, List<DistanceRange> distanceRanges, Tetree<Content> tetree) {
        
        validatePositiveCoordinates(queryPoint);
        
        Map<DistanceRange, List<ProximityResult<Content>>> results = new LinkedHashMap<>();
        
        for (DistanceRange range : distanceRanges) {
            List<ProximityResult<Content>> rangeResults = tetrahedraWithinDistanceRange(queryPoint, range, tetree);
            results.put(range, rangeResults);
        }
        
        return results;
    }

    /**
     * Find the N closest tetrahedra to a query point
     * 
     * @param queryPoint the reference point for distance calculations (positive coordinates only)
     * @param n number of closest tetrahedra to find
     * @param tetree the tetree to search in
     * @return list of the N closest tetrahedra, sorted by distance
     * @throws IllegalArgumentException if query point has negative coordinates or n is non-positive
     */
    public static <Content> List<ProximityResult<Content>> findNClosestTetrahedra(
            Point3f queryPoint, int n, Tetree<Content> tetree) {
        
        return findNClosestTetrahedra(queryPoint, n, tetree, SimplexAggregationStrategy.ALL_SIMPLICIES);
    }

    /**
     * Find the N closest tetrahedra to a query point, with configurable simplex aggregation
     * 
     * @param queryPoint the reference point for distance calculations (positive coordinates only)
     * @param n number of closest tetrahedra to find
     * @param tetree the tetree to search in
     * @param strategy how to aggregate multiple simplicies per spatial region
     * @return list of the N closest tetrahedra, sorted by distance
     * @throws IllegalArgumentException if query point has negative coordinates or n is non-positive
     */
    public static <Content> List<ProximityResult<Content>> findNClosestTetrahedra(
            Point3f queryPoint, int n, Tetree<Content> tetree, SimplexAggregationStrategy strategy) {
        
        validatePositiveCoordinates(queryPoint);
        
        if (n <= 0) {
            throw new IllegalArgumentException("N must be positive, got: " + n);
        }
        
        // Use a large AABB to capture all potentially close tetrahedra
        // Use adaptive search radius
        float searchRadius = estimateSearchRadius(tetree, n);
        Spatial.aabb searchBounds = createSearchBounds(queryPoint, searchRadius);
        
        // Get all simplicies within search radius
        Stream<Tetree.Simplex<Content>> candidateSimplicies = tetree.boundedBy(searchBounds);
        
        // Apply simplex aggregation strategy
        List<Tetree.Simplex<Content>> aggregatedSimplicies = aggregateSimplicies(candidateSimplicies, strategy);
        
        // Convert to proximity results and calculate distances
        List<ProximityResult<Content>> allResults = aggregatedSimplicies.stream()
            .map(simplex -> {
                Point3f tetCenter = tetrahedronCenter(simplex.index());
                float centerDistance = calculateDistance(queryPoint, tetCenter);
                float minDistance = calculateMinDistanceToTetrahedron(queryPoint, simplex.index());
                float maxDistance = calculateMaxDistanceToTetrahedron(queryPoint, simplex.index());
                ProximityType proximityType = classifyDistance(centerDistance);
                
                return new ProximityResult<>(
                    simplex.index(), 
                    simplex.cell(), 
                    Tet.tetrahedron(simplex.index()),
                    tetCenter,
                    centerDistance,
                    minDistance,
                    maxDistance,
                    proximityType
                );
            })
            .collect(Collectors.toList());

        // Sort by center distance and take top N
        return allResults.stream()
            .sorted(Comparator.comparing(pr -> pr.distanceToQuery))
            .limit(n)
            .collect(Collectors.toList());
    }

    /**
     * Find tetrahedra within a specific distance from multiple query points
     * 
     * @param queryPoints list of reference points (positive coordinates only)
     * @param maxDistance maximum distance from any query point
     * @param tetree the tetree to search in
     * @return list of tetrahedra within distance of any query point, with distances to closest query point
     * @throws IllegalArgumentException if any query point has negative coordinates
     */
    public static <Content> List<ProximityResult<Content>> tetrahedraNearAnyPoint(
            List<Point3f> queryPoints, float maxDistance, Tetree<Content> tetree) {
        
        for (Point3f point : queryPoints) {
            validatePositiveCoordinates(point);
        }
        
        if (maxDistance < 0) {
            throw new IllegalArgumentException("Max distance must be non-negative, got: " + maxDistance);
        }
        
        if (queryPoints.isEmpty()) {
            return Collections.emptyList();
        }
        
        // Create union of spatial AABB for all query points
        Set<Tetree.Simplex<Content>> allCandidates = new HashSet<>();
        for (Point3f queryPoint : queryPoints) {
            Spatial.aabb searchBounds = createSearchBounds(queryPoint, maxDistance);
            tetree.boundedBy(searchBounds).forEach(allCandidates::add);
        }
        
        List<ProximityResult<Content>> results = new ArrayList<>();
        
        for (Tetree.Simplex<Content> simplex : allCandidates) {
            Point3f tetCenter = tetrahedronCenter(simplex.index());
            
            float closestDistance = Float.MAX_VALUE;
            Point3f closestQueryPoint = null;
            
            // Find closest query point to this tetrahedron
            for (Point3f queryPoint : queryPoints) {
                float distance = calculateMinDistanceToTetrahedron(queryPoint, simplex.index());
                if (distance < closestDistance) {
                    closestDistance = distance;
                    closestQueryPoint = queryPoint;
                }
            }
            
            if (closestDistance <= maxDistance && closestQueryPoint != null) {
                float centerDistance = calculateDistance(closestQueryPoint, tetCenter);
                float maxDistanceToQuery = calculateMaxDistanceToTetrahedron(closestQueryPoint, simplex.index());
                ProximityType proximityType = classifyDistance(centerDistance);
                
                ProximityResult<Content> result = new ProximityResult<>(
                    simplex.index(), 
                    simplex.cell(), 
                    Tet.tetrahedron(simplex.index()),
                    tetCenter,
                    centerDistance,
                    closestDistance,
                    maxDistanceToQuery,
                    proximityType
                );
                results.add(result);
            }
        }

        // Sort by closest distance
        results.sort(Comparator.comparing(pr -> pr.minDistanceToQuery));
        
        return results;
    }

    /**
     * Find tetrahedra that are within distance of all specified query points
     * 
     * @param queryPoints list of reference points (positive coordinates only)
     * @param maxDistance maximum distance from each query point
     * @param tetree the tetree to search in
     * @return list of tetrahedra within distance of all query points
     * @throws IllegalArgumentException if any query point has negative coordinates
     */
    public static <Content> List<ProximityResult<Content>> tetrahedraNearAllPoints(
            List<Point3f> queryPoints, float maxDistance, Tetree<Content> tetree) {
        
        for (Point3f point : queryPoints) {
            validatePositiveCoordinates(point);
        }
        
        if (maxDistance < 0) {
            throw new IllegalArgumentException("Max distance must be non-negative, got: " + maxDistance);
        }
        
        if (queryPoints.isEmpty()) {
            return Collections.emptyList();
        }
        
        // Start with candidates from first query point
        Point3f firstQuery = queryPoints.get(0);
        Spatial.aabb searchBounds = createSearchBounds(firstQuery, maxDistance);
        Set<Tetree.Simplex<Content>> candidates = tetree.boundedBy(searchBounds).collect(Collectors.toSet());
        
        // Filter candidates that are within distance of all other query points
        for (int i = 1; i < queryPoints.size(); i++) {
            Point3f queryPoint = queryPoints.get(i);
            candidates.removeIf(simplex -> 
                calculateMinDistanceToTetrahedron(queryPoint, simplex.index()) > maxDistance);
        }
        
        List<ProximityResult<Content>> results = new ArrayList<>();
        
        for (Tetree.Simplex<Content> simplex : candidates) {
            Point3f tetCenter = tetrahedronCenter(simplex.index());
            
            // Use first query point for primary distance calculation
            Point3f primaryQuery = queryPoints.get(0);
            float centerDistance = calculateDistance(primaryQuery, tetCenter);
            float minDistanceToQuery = calculateMinDistanceToTetrahedron(primaryQuery, simplex.index());
            float maxDistanceToQuery = calculateMaxDistanceToTetrahedron(primaryQuery, simplex.index());
            ProximityType proximityType = classifyDistance(centerDistance);
            
            ProximityResult<Content> result = new ProximityResult<>(
                simplex.index(), 
                simplex.cell(), 
                Tet.tetrahedron(simplex.index()),
                tetCenter,
                centerDistance,
                minDistanceToQuery,
                maxDistanceToQuery,
                proximityType
            );
            results.add(result);
        }

        // Sort by distance to primary query point
        results.sort(Comparator.comparing(pr -> pr.distanceToQuery));
        
        return results;
    }

    /**
     * Count the number of tetrahedra within a specific distance range
     * This is more efficient than getting all results when only count is needed
     * 
     * @param queryPoint the reference point for distance calculations (positive coordinates only)
     * @param distanceRange the distance range to search within
     * @param tetree the tetree to search in
     * @return number of tetrahedra within the distance range
     * @throws IllegalArgumentException if query point has negative coordinates
     */
    public static <Content> long countTetrahedraInRange(
            Point3f queryPoint, DistanceRange distanceRange, Tetree<Content> tetree) {
        
        validatePositiveCoordinates(queryPoint);
        
        float searchRadius = distanceRange.maxDistance * 1.1f; // Add buffer for safety
        Spatial.aabb searchBounds = createSearchBounds(queryPoint, searchRadius);
        
        return tetree.boundedBy(searchBounds)
            .filter(simplex -> {
                float minDistance = calculateMinDistanceToTetrahedron(queryPoint, simplex.index());
                float maxDistance = calculateMaxDistanceToTetrahedron(queryPoint, simplex.index());
                return minDistance <= distanceRange.maxDistance && maxDistance >= distanceRange.minDistance;
            })
            .count();
    }

    /**
     * Test if any tetrahedron is within a specific distance range
     * This is more efficient than getting all results when only existence check is needed
     * 
     * @param queryPoint the reference point for distance calculations (positive coordinates only)
     * @param distanceRange the distance range to search within
     * @param tetree the tetree to search in
     * @return true if any tetrahedron is within the distance range
     * @throws IllegalArgumentException if query point has negative coordinates
     */
    public static <Content> boolean hasAnyTetrahedronInRange(
            Point3f queryPoint, DistanceRange distanceRange, Tetree<Content> tetree) {
        
        validatePositiveCoordinates(queryPoint);
        
        float searchRadius = distanceRange.maxDistance * 1.1f; // Add buffer for safety
        Spatial.aabb searchBounds = createSearchBounds(queryPoint, searchRadius);
        
        return tetree.boundedBy(searchBounds)
            .anyMatch(simplex -> {
                float minDistance = calculateMinDistanceToTetrahedron(queryPoint, simplex.index());
                float maxDistance = calculateMaxDistanceToTetrahedron(queryPoint, simplex.index());
                return minDistance <= distanceRange.maxDistance && maxDistance >= distanceRange.minDistance;
            });
    }

    /**
     * Get proximity statistics for tetrahedra relative to a query point
     * 
     * @param queryPoint the reference point (positive coordinates only)
     * @param tetree the tetree to search in
     * @return statistics about tetrahedron proximity distribution
     * @throws IllegalArgumentException if query point has negative coordinates
     */
    public static <Content> ProximityStatistics getProximityStatistics(
            Point3f queryPoint, Tetree<Content> tetree) {
        
        validatePositiveCoordinates(queryPoint);
        
        // Use a reasonable search radius to capture nearby tetrahedra for statistics
        float searchRadius = Constants.MAX_EXTENT * 0.1f; // Use 10% of max extent
        Spatial.aabb searchBounds = createSearchBounds(queryPoint, searchRadius);
        
        List<Tetree.Simplex<Content>> allSimplicies = tetree.boundedBy(searchBounds).collect(Collectors.toList());
        
        if (allSimplicies.isEmpty()) {
            return new ProximityStatistics(0, 0, 0, 0, 0, 0.0f, 0.0f, 0.0f);
        }

        long totalTetrahedra = 0;
        long veryCloseTetrahedra = 0;
        long closeTetrahedra = 0;
        long moderateTetrahedra = 0;
        long farTetrahedra = 0;
        float totalDistance = 0.0f;
        float minDistance = Float.MAX_VALUE;
        float maxDistance = Float.MIN_VALUE;

        for (Tetree.Simplex<Content> simplex : allSimplicies) {
            totalTetrahedra++;
            Point3f tetCenter = tetrahedronCenter(simplex.index());
            float distance = calculateDistance(queryPoint, tetCenter);
            
            totalDistance += distance;
            minDistance = Math.min(minDistance, distance);
            maxDistance = Math.max(maxDistance, distance);
            
            ProximityType proximityType = classifyDistance(distance);
            switch (proximityType) {
                case VERY_CLOSE -> veryCloseTetrahedra++;
                case CLOSE -> closeTetrahedra++;
                case MODERATE -> moderateTetrahedra++;
                case FAR, VERY_FAR -> farTetrahedra++;
            }
        }

        float averageDistance = totalTetrahedra > 0 ? totalDistance / totalTetrahedra : 0.0f;

        return new ProximityStatistics(totalTetrahedra, veryCloseTetrahedra, closeTetrahedra, moderateTetrahedra, farTetrahedra,
                                     averageDistance, minDistance, maxDistance);
    }

    /**
     * Statistics about proximity distribution in tetrahedral space
     */
    public static class ProximityStatistics {
        public final long totalTetrahedra;
        public final long veryCloseTetrahedra;
        public final long closeTetrahedra;
        public final long moderateTetrahedra;
        public final long farTetrahedra;
        public final float averageDistance;
        public final float minDistance;
        public final float maxDistance;
        
        public ProximityStatistics(long totalTetrahedra, long veryCloseTetrahedra, long closeTetrahedra, 
                                 long moderateTetrahedra, long farTetrahedra, float averageDistance,
                                 float minDistance, float maxDistance) {
            this.totalTetrahedra = totalTetrahedra;
            this.veryCloseTetrahedra = veryCloseTetrahedra;
            this.closeTetrahedra = closeTetrahedra;
            this.moderateTetrahedra = moderateTetrahedra;
            this.farTetrahedra = farTetrahedra;
            this.averageDistance = averageDistance;
            this.minDistance = minDistance;
            this.maxDistance = maxDistance;
        }
        
        public double getVeryClosePercentage() {
            return totalTetrahedra > 0 ? (double) veryCloseTetrahedra / totalTetrahedra * 100.0 : 0.0;
        }
        
        public double getClosePercentage() {
            return totalTetrahedra > 0 ? (double) closeTetrahedra / totalTetrahedra * 100.0 : 0.0;
        }
        
        public double getModeratePercentage() {
            return totalTetrahedra > 0 ? (double) moderateTetrahedra / totalTetrahedra * 100.0 : 0.0;
        }
        
        public double getFarPercentage() {
            return totalTetrahedra > 0 ? (double) farTetrahedra / totalTetrahedra * 100.0 : 0.0;
        }
        
        @Override
        public String toString() {
            return String.format("TetProximityStats[total=%d, very_close=%d(%.1f%%), close=%d(%.1f%%), moderate=%d(%.1f%%), far=%d(%.1f%%), avg_dist=%.2f, range=[%.2f-%.2f]]",
                               totalTetrahedra, veryCloseTetrahedra, getVeryClosePercentage(), 
                               closeTetrahedra, getClosePercentage(), moderateTetrahedra, getModeratePercentage(),
                               farTetrahedra, getFarPercentage(), averageDistance, minDistance, maxDistance);
        }
    }

    /**
     * Batch processing for multiple proximity queries
     * Useful for multiple simultaneous proximity analyses
     * 
     * @param queries list of proximity queries to process
     * @param tetree the tetree to search in
     * @return map of queries to their proximity results
     */
    public static <Content> Map<ProximityQuery, List<ProximityResult<Content>>> 
            batchProximityQueries(List<ProximityQuery> queries, Tetree<Content> tetree) {
        
        for (ProximityQuery query : queries) {
            validatePositiveCoordinates(query.queryPoint);
        }
        
        return queries.stream()
            .collect(Collectors.toMap(
                query -> query,
                query -> tetrahedraWithinDistanceRange(query.queryPoint, query.distanceRange, tetree)
            ));
    }

    /**
     * Proximity query specification
     */
    public static class ProximityQuery {
        public final Point3f queryPoint;
        public final DistanceRange distanceRange;
        
        public ProximityQuery(Point3f queryPoint, DistanceRange distanceRange) {
            validatePositiveCoordinates(queryPoint);
            this.queryPoint = new Point3f(queryPoint);
            this.distanceRange = distanceRange;
        }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof ProximityQuery)) return false;
            ProximityQuery other = (ProximityQuery) obj;
            return queryPoint.equals(other.queryPoint) && distanceRange.equals(other.distanceRange);
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(queryPoint, distanceRange);
        }
        
        @Override
        public String toString() {
            return String.format("ProximityQuery[point=%s, range=%s]", queryPoint, distanceRange);
        }
    }

    /**
     * Calculate minimum distance from a point to a tetrahedron surface
     * Uses complex tetrahedral distance calculations
     */
    private static float calculateMinDistanceToTetrahedron(Point3f point, long tetIndex) {
        var tet = Tet.tetrahedron(tetIndex);
        var vertices = tet.coordinates();
        
        // Check if point is inside tetrahedron
        if (isPointInTetrahedron(point, vertices)) {
            return 0.0f; // Point is inside
        }
        
        // Calculate distance to closest point on tetrahedron surface
        float minDistance = Float.MAX_VALUE;
        
        // Distance to vertices
        for (var vertex : vertices) {
            float vertexDistance = calculateDistance(point, new Point3f(vertex.x, vertex.y, vertex.z));
            minDistance = Math.min(minDistance, vertexDistance);
        }
        
        // Distance to edges
        for (int i = 0; i < 4; i++) {
            for (int j = i + 1; j < 4; j++) {
                Point3f v1 = new Point3f(vertices[i].x, vertices[i].y, vertices[i].z);
                Point3f v2 = new Point3f(vertices[j].x, vertices[j].y, vertices[j].z);
                float edgeDistance = calculateDistanceToLineSegment(point, v1, v2);
                minDistance = Math.min(minDistance, edgeDistance);
            }
        }
        
        // Distance to faces
        int[][] faces = {{0, 1, 2}, {0, 1, 3}, {0, 2, 3}, {1, 2, 3}};
        for (int[] face : faces) {
            Point3f v1 = new Point3f(vertices[face[0]].x, vertices[face[0]].y, vertices[face[0]].z);
            Point3f v2 = new Point3f(vertices[face[1]].x, vertices[face[1]].y, vertices[face[1]].z);
            Point3f v3 = new Point3f(vertices[face[2]].x, vertices[face[2]].y, vertices[face[2]].z);
            float faceDistance = calculateDistanceToTriangle(point, v1, v2, v3);
            minDistance = Math.min(minDistance, faceDistance);
        }
        
        return minDistance;
    }

    /**
     * Calculate maximum distance from a point to a tetrahedron vertex
     */
    private static float calculateMaxDistanceToTetrahedron(Point3f point, long tetIndex) {
        var tet = Tet.tetrahedron(tetIndex);
        var vertices = tet.coordinates();
        
        float maxDistance = Float.MIN_VALUE;
        
        for (var vertex : vertices) {
            float distance = calculateDistance(point, new Point3f(vertex.x, vertex.y, vertex.z));
            maxDistance = Math.max(maxDistance, distance);
        }
        
        return maxDistance;
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
     * Calculate distance from point to line segment
     */
    private static float calculateDistanceToLineSegment(Point3f point, Point3f lineStart, Point3f lineEnd) {
        float dx = lineEnd.x - lineStart.x;
        float dy = lineEnd.y - lineStart.y;
        float dz = lineEnd.z - lineStart.z;
        
        if (dx == 0 && dy == 0 && dz == 0) {
            // Line segment is a point
            return calculateDistance(point, lineStart);
        }
        
        float t = ((point.x - lineStart.x) * dx + (point.y - lineStart.y) * dy + (point.z - lineStart.z) * dz) / (dx * dx + dy * dy + dz * dz);
        t = Math.max(0, Math.min(1, t)); // Clamp to [0, 1]
        
        Point3f closestPoint = new Point3f(
            lineStart.x + t * dx,
            lineStart.y + t * dy,
            lineStart.z + t * dz
        );
        
        return calculateDistance(point, closestPoint);
    }

    /**
     * Calculate distance from point to triangle
     */
    private static float calculateDistanceToTriangle(Point3f point, Point3f v1, Point3f v2, Point3f v3) {
        // For simplicity, calculate distance to triangle vertices and return minimum
        // This is an approximation - full point-to-triangle distance is more complex
        float dist1 = calculateDistance(point, v1);
        float dist2 = calculateDistance(point, v2);
        float dist3 = calculateDistance(point, v3);
        
        float edgeDist1 = calculateDistanceToLineSegment(point, v1, v2);
        float edgeDist2 = calculateDistanceToLineSegment(point, v2, v3);
        float edgeDist3 = calculateDistanceToLineSegment(point, v3, v1);
        
        return Math.min(Math.min(dist1, Math.min(dist2, dist3)), 
                       Math.min(edgeDist1, Math.min(edgeDist2, edgeDist3)));
    }

    /**
     * Classify distance into proximity types for tetrahedral space
     */
    private static ProximityType classifyDistance(float distance) {
        // Adjust thresholds for tetrahedral coordinate system which uses larger scale
        float scale = Constants.MAX_EXTENT / 10000.0f; // Scale down from MAX_EXTENT
        
        if (distance < 1000.0f * scale) {
            return ProximityType.VERY_CLOSE;
        } else if (distance < 5000.0f * scale) {
            return ProximityType.CLOSE;
        } else if (distance < 10000.0f * scale) {
            return ProximityType.MODERATE;
        } else if (distance < 50000.0f * scale) {
            return ProximityType.FAR;
        } else {
            return ProximityType.VERY_FAR;
        }
    }

    /**
     * Estimate appropriate search radius for N closest search
     */
    private static <Content> float estimateSearchRadius(Tetree<Content> tetree, int n) {
        // Start with a conservative estimate based on tetree structure
        // This is a heuristic - could be improved with tetree statistics
        return Constants.MAX_EXTENT * 0.1f * (float) Math.sqrt(n);
    }

    /**
     * Create search bounds as AABB around a point with given radius
     */
    private static Spatial.aabb createSearchBounds(Point3f center, float radius) {
        return new Spatial.aabb(
            Math.max(0, center.x - radius), // Ensure positive coordinates
            Math.max(0, center.y - radius),
            Math.max(0, center.z - radius),
            center.x + radius,
            center.y + radius,
            center.z + radius
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