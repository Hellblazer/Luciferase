package com.hellblazer.luciferase.lucien;

import javax.vecmath.Point3f;
import java.lang.reflect.Field;
import java.util.*;
import java.util.stream.Collectors;

/**
 * k-Nearest Neighbor search implementation for Tetree
 * Finds the k closest tetrahedra to a query point using tetrahedral geometry and distance calculations
 * 
 * Adapts cube-based k-NN search to tetrahedral geometry, accounting for the fact that
 * tetrahedra have irregular shapes compared to uniform cubes. Uses precise geometric
 * distance calculations and leverages SFC optimization for performance.
 * 
 * @author hal.hildebrand
 */
public class TetKNearestNeighborSearch extends TetrahedralSearchBase {

    /**
     * Candidate entry for tetrahedral k-NN search with distance information
     */
    public static class TetKNNCandidate<Content> {
        public final long index;
        public final Content content;
        public final Tet tetrahedron;
        public final Point3f center;
        public final float distance;
        public final boolean isInside; // True if query point is inside this tetrahedron

        public TetKNNCandidate(long index, Content content, Tet tetrahedron, Point3f center, 
                              float distance, boolean isInside) {
            this.index = index;
            this.content = content;
            this.tetrahedron = tetrahedron;
            this.center = center;
            this.distance = distance;
            this.isInside = isInside;
        }
    }

    /**
     * Configuration for k-NN search optimization
     */
    public static class KNNConfig {
        public final boolean useSFCOptimization;
        public final boolean useDistanceThreshold;
        public final float maxSearchRadius;
        public final SimplexAggregationStrategy aggregationStrategy;
        public final boolean earlyTermination;
        
        public KNNConfig(boolean useSFCOptimization, boolean useDistanceThreshold, 
                        float maxSearchRadius, SimplexAggregationStrategy aggregationStrategy,
                        boolean earlyTermination) {
            this.useSFCOptimization = useSFCOptimization;
            this.useDistanceThreshold = useDistanceThreshold;
            this.maxSearchRadius = maxSearchRadius;
            this.aggregationStrategy = aggregationStrategy;
            this.earlyTermination = earlyTermination;
        }
        
        public static KNNConfig defaultConfig() {
            return new KNNConfig(true, false, Float.MAX_VALUE, 
                               SimplexAggregationStrategy.REPRESENTATIVE_ONLY, true);
        }
        
        public static KNNConfig fastConfig() {
            return new KNNConfig(true, true, 1000.0f, 
                               SimplexAggregationStrategy.REPRESENTATIVE_ONLY, true);
        }
        
        public static KNNConfig preciseConfig() {
            return new KNNConfig(false, false, Float.MAX_VALUE, 
                               SimplexAggregationStrategy.ALL_SIMPLICIES, false);
        }
    }

    /**
     * Find k nearest neighbors to the query point in the tetree
     * 
     * @param queryPoint the point to search around (must have positive coordinates)
     * @param k number of neighbors to find
     * @param tetree the tetree to search in
     * @return list of k nearest neighbors sorted by distance (closest first)
     */
    public static <Content> List<TetKNNCandidate<Content>> findKNearestNeighbors(
            Point3f queryPoint, int k, Tetree<Content> tetree) {
        return findKNearestNeighbors(queryPoint, k, tetree, KNNConfig.defaultConfig());
    }

    /**
     * Find k nearest neighbors to the query point with configuration options
     * 
     * @param queryPoint the point to search around (must have positive coordinates)
     * @param k number of neighbors to find
     * @param tetree the tetree to search in
     * @param config search configuration options
     * @return list of k nearest neighbors sorted by distance (closest first)
     */
    public static <Content> List<TetKNNCandidate<Content>> findKNearestNeighbors(
            Point3f queryPoint, int k, Tetree<Content> tetree, KNNConfig config) {
        
        if (k <= 0) {
            return Collections.emptyList();
        }
        
        validatePositiveCoordinates(queryPoint);
        
        // Access tetree contents using reflection
        NavigableMap<Long, Content> map = getTetreeContents(tetree);
        if (map.isEmpty()) {
            return Collections.emptyList();
        }

        List<TetKNNCandidate<Content>> candidates;
        
        if (config.useSFCOptimization && k < map.size() / 2) {
            candidates = findKNearestWithSFCOptimization(queryPoint, k, map, config);
        } else {
            candidates = findKNearestBruteForce(queryPoint, k, map, config);
        }
        
        // Apply aggregation strategy
        var aggregatedCandidates = aggregateKNNCandidates(candidates, config.aggregationStrategy);
        
        // Sort by distance and limit to k
        aggregatedCandidates.sort(Comparator.comparing(c -> c.distance));
        
        return aggregatedCandidates.stream()
            .limit(k)
            .collect(Collectors.toList());
    }

    /**
     * Find the single nearest neighbor to the query point
     * 
     * @param queryPoint the point to search around (must have positive coordinates)
     * @param tetree the tetree to search in
     * @return nearest neighbor, or null if tetree is empty
     */
    public static <Content> TetKNNCandidate<Content> findNearestNeighbor(
            Point3f queryPoint, Tetree<Content> tetree) {
        
        var results = findKNearestNeighbors(queryPoint, 1, tetree);
        return results.isEmpty() ? null : results.get(0);
    }

    /**
     * Find neighbors within a specified radius
     * 
     * @param queryPoint the point to search around (must have positive coordinates)
     * @param radius maximum distance for neighbors
     * @param tetree the tetree to search in
     * @return list of neighbors within radius sorted by distance
     */
    public static <Content> List<TetKNNCandidate<Content>> findNeighborsWithinRadius(
            Point3f queryPoint, float radius, Tetree<Content> tetree) {
        
        validatePositiveCoordinates(queryPoint);
        if (radius <= 0) {
            return Collections.emptyList();
        }
        
        var config = new KNNConfig(true, true, radius, 
                                 SimplexAggregationStrategy.ALL_SIMPLICIES, false);
        
        // Use a large k to get all candidates, then filter by radius
        var allCandidates = findKNearestNeighbors(queryPoint, Integer.MAX_VALUE, tetree, config);
        
        return allCandidates.stream()
            .filter(candidate -> candidate.distance <= radius)
            .collect(Collectors.toList());
    }

    /**
     * Optimized k-NN search using SFC spatial optimization
     */
    private static <Content> List<TetKNNCandidate<Content>> findKNearestWithSFCOptimization(
            Point3f queryPoint, int k, NavigableMap<Long, Content> map, KNNConfig config) {
        
        List<TetKNNCandidate<Content>> candidates = new ArrayList<>();
        
        // Start with a reasonable search radius and expand if needed
        float searchRadius = estimateInitialSearchRadius(queryPoint, map, k);
        
        do {
            candidates.clear();
            
            // Create spatial bounds around query point
            var volumeBounds = new TetSpatialOptimizer.VolumeBounds(
                queryPoint.x - searchRadius, queryPoint.y - searchRadius, queryPoint.z - searchRadius,
                queryPoint.x + searchRadius, queryPoint.y + searchRadius, queryPoint.z + searchRadius
            );
            
            // Get SFC ranges that could contain relevant tetrahedra
            var ranges = TetSpatialOptimizer.computeOptimizedSFCRanges(volumeBounds, true);
            
            // Process each range
            for (var range : ranges) {
                var subMap = map.subMap(range.startIndex(), true, range.endIndex(), true);
                
                for (var entry : subMap.entrySet()) {
                    var candidate = createKNNCandidate(queryPoint, entry.getKey(), entry.getValue());
                    
                    if (candidate.distance <= searchRadius) {
                        candidates.add(candidate);
                    }
                }
            }
            
            // If we don't have enough candidates and haven't reached max radius, expand search
            if (candidates.size() < k && searchRadius < config.maxSearchRadius) {
                searchRadius *= 2.0f;
                if (searchRadius > config.maxSearchRadius) {
                    searchRadius = config.maxSearchRadius;
                }
            } else {
                break;
            }
            
        } while (candidates.size() < k && searchRadius < config.maxSearchRadius);
        
        return candidates;
    }

    /**
     * Brute force k-NN search testing all tetrahedra
     */
    private static <Content> List<TetKNNCandidate<Content>> findKNearestBruteForce(
            Point3f queryPoint, int k, NavigableMap<Long, Content> map, KNNConfig config) {
        
        List<TetKNNCandidate<Content>> candidates = new ArrayList<>();
        
        for (Map.Entry<Long, Content> entry : map.entrySet()) {
            var candidate = createKNNCandidate(queryPoint, entry.getKey(), entry.getValue());
            
            // Apply distance threshold if configured
            if (config.useDistanceThreshold && candidate.distance > config.maxSearchRadius) {
                continue;
            }
            
            candidates.add(candidate);
            
            // Early termination optimization: if we have enough candidates and
            // current candidate is much farther than our k-th closest, we can stop
            // in some cases (only works if data is spatially sorted)
            if (config.earlyTermination && candidates.size() > k * 2) {
                candidates.sort(Comparator.comparing(c -> c.distance));
                float kthDistance = candidates.get(k - 1).distance;
                if (candidate.distance > kthDistance * 2.0f) {
                    break;
                }
            }
        }
        
        return candidates;
    }

    /**
     * Create a k-NN candidate from a tetree entry
     */
    private static <Content> TetKNNCandidate<Content> createKNNCandidate(
            Point3f queryPoint, long tetIndex, Content content) {
        
        var tet = Tet.tetrahedron(tetIndex);
        boolean isInside = pointInTetrahedron(queryPoint, tetIndex);
        float distance = isInside ? 0.0f : distanceToTetrahedron(queryPoint, tetIndex);
        Point3f center = tetrahedronCenter(tet);
        
        return new TetKNNCandidate<>(tetIndex, content, tet, center, distance, isInside);
    }

    /**
     * Estimate initial search radius based on data distribution
     */
    private static <Content> float estimateInitialSearchRadius(
            Point3f queryPoint, NavigableMap<Long, Content> map, int k) {
        
        if (map.isEmpty()) {
            return 100.0f; // Default radius
        }
        
        // Sample a few tetrahedra to estimate typical distances
        int sampleSize = Math.min(10, map.size());
        List<Float> sampleDistances = new ArrayList<>();
        
        int count = 0;
        for (var entry : map.entrySet()) {
            if (count >= sampleSize) break;
            
            var tet = Tet.tetrahedron(entry.getKey());
            Point3f center = tetrahedronCenter(tet);
            float distance = distance(queryPoint, center);
            sampleDistances.add(distance);
            count++;
        }
        
        if (sampleDistances.isEmpty()) {
            return 100.0f;
        }
        
        // Use median distance as estimate, scaled by k
        sampleDistances.sort(Float::compareTo);
        float medianDistance = sampleDistances.get(sampleDistances.size() / 2);
        
        // Scale based on k and add some buffer
        return medianDistance * (float) Math.sqrt(k) * 1.5f;
    }

    /**
     * Calculate Euclidean distance between two points
     */
    private static float distance(Point3f p1, Point3f p2) {
        float dx = p1.x - p2.x;
        float dy = p1.y - p2.y;
        float dz = p1.z - p2.z;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Aggregate k-NN candidates based on strategy
     */
    private static <Content> List<TetKNNCandidate<Content>> aggregateKNNCandidates(
            List<TetKNNCandidate<Content>> candidates, SimplexAggregationStrategy strategy) {
        
        return switch (strategy) {
            case REPRESENTATIVE_ONLY -> {
                // Group by spatial proximity and select representatives
                var grouped = groupCandidatesBySpatialProximity(candidates);
                yield grouped.stream()
                    .map(TetKNearestNeighborSearch::selectRepresentativeCandidate)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            }
            case ALL_SIMPLICIES -> candidates;
            case WEIGHTED_AVERAGE -> {
                // For now, return all candidates - could implement averaging logic
                yield candidates;
            }
            case BEST_FIT -> {
                // Prefer candidates where query point is inside tetrahedron
                var insideCandidates = candidates.stream()
                    .filter(c -> c.isInside)
                    .collect(Collectors.toList());
                yield insideCandidates.isEmpty() ? candidates : insideCandidates;
            }
        };
    }

    /**
     * Group candidates by spatial proximity
     */
    private static <Content> List<List<TetKNNCandidate<Content>>> groupCandidatesBySpatialProximity(
            List<TetKNNCandidate<Content>> candidates) {
        
        // Group by coarse spatial coordinates
        Map<String, List<TetKNNCandidate<Content>>> spatialGroups = candidates.stream()
            .collect(Collectors.groupingBy(candidate -> {
                var tet = candidate.tetrahedron;
                int levelSize = Constants.lengthAtLevel(tet.l());
                int groupX = tet.x() / levelSize;
                int groupY = tet.y() / levelSize;
                int groupZ = tet.z() / levelSize;
                return groupX + "," + groupY + "," + groupZ;
            }));
        
        return new ArrayList<>(spatialGroups.values());
    }

    /**
     * Select representative candidate from a group
     */
    private static <Content> TetKNNCandidate<Content> selectRepresentativeCandidate(
            List<TetKNNCandidate<Content>> group) {
        
        if (group.isEmpty()) {
            return null;
        }
        
        if (group.size() == 1) {
            return group.get(0);
        }
        
        // Prefer candidates where query point is inside, then by closest distance
        var insideCandidates = group.stream()
            .filter(c -> c.isInside)
            .collect(Collectors.toList());
        
        if (!insideCandidates.isEmpty()) {
            return insideCandidates.stream()
                .min(Comparator.comparing(c -> c.distance))
                .orElse(group.get(0));
        }
        
        // Otherwise, select closest by distance
        return group.stream()
            .min(Comparator.comparing(c -> c.distance))
            .orElse(group.get(0));
    }

    /**
     * Access Tetree contents using reflection (helper method)
     */
    private static <Content> NavigableMap<Long, Content> getTetreeContents(Tetree<Content> tetree) {
        try {
            Field field = tetree.getClass().getDeclaredField("contents");
            field.setAccessible(true);
            return (NavigableMap<Long, Content>) field.get(tetree);
        } catch (Exception e) {
            throw new RuntimeException("Unable to access tetree contents", e);
        }
    }

    /**
     * k-NN search performance statistics
     */
    public static class KNNSearchStats {
        public final int totalCandidatesEvaluated;
        public final int sfcRangesProcessed;
        public final int finalResultCount;
        public final long executionTimeNanos;
        public final boolean usedSFCOptimization;
        public final float searchRadius;
        
        public KNNSearchStats(int totalCandidatesEvaluated, int sfcRangesProcessed,
                            int finalResultCount, long executionTimeNanos,
                            boolean usedSFCOptimization, float searchRadius) {
            this.totalCandidatesEvaluated = totalCandidatesEvaluated;
            this.sfcRangesProcessed = sfcRangesProcessed;
            this.finalResultCount = finalResultCount;
            this.executionTimeNanos = executionTimeNanos;
            this.usedSFCOptimization = usedSFCOptimization;
            this.searchRadius = searchRadius;
        }
        
        public double getCandidateEfficiency() {
            return finalResultCount > 0 ? 
                (double) finalResultCount / totalCandidatesEvaluated : 0.0;
        }
        
        @Override
        public String toString() {
            return String.format("KNNSearchStats[evaluated=%d, ranges=%d, results=%d, " +
                "time=%.2fms, SFC=%s, radius=%.1f, efficiency=%.1f%%]",
                totalCandidatesEvaluated, sfcRangesProcessed, finalResultCount,
                executionTimeNanos / 1_000_000.0, usedSFCOptimization, searchRadius,
                getCandidateEfficiency() * 100);
        }
    }

    /**
     * Instrumented k-NN search for performance analysis
     */
    public static <Content> List<TetKNNCandidate<Content>> findKNearestNeighborsWithStats(
            Point3f queryPoint, int k, Tetree<Content> tetree, KNNConfig config) {
        
        long startTime = System.nanoTime();
        
        // TODO: Add instrumentation counters throughout the algorithm
        // For now, delegate to standard implementation
        var results = findKNearestNeighbors(queryPoint, k, tetree, config);
        
        long endTime = System.nanoTime();
        
        // Create basic stats (would be enhanced with actual counters)
        var stats = new KNNSearchStats(
            getTetreeContents(tetree).size(),  // totalCandidatesEvaluated (approximation)
            1,                                 // sfcRangesProcessed (approximation)
            results.size(),                    // finalResultCount
            endTime - startTime,               // executionTimeNanos
            config.useSFCOptimization,         // usedSFCOptimization
            config.maxSearchRadius            // searchRadius
        );
        
        // For debugging/profiling - could be made configurable
        if (System.getProperty("tetree.knn.debug") != null) {
            System.out.println("TetKNN: " + stats);
        }
        
        return results;
    }
}