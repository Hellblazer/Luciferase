package com.hellblazer.luciferase.lucien;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.stream.Collectors;

/**
 * k-Nearest Neighbor search implementation for Tetree
 * Finds the k closest entries to a query point using tetrahedral distance calculations
 * 
 * @author hal.hildebrand
 */
public class TetKNearestNeighborSearch extends TetrahedralSearchBase {

    /**
     * Candidate entry for k-NN search with distance information
     */
    public static class TetKNNCandidate<Content> {
        public final long index;
        public final Content content;
        public final Point3f position;
        public final float distance;

        public TetKNNCandidate(long index, Content content, Point3f position, float distance) {
            this.index = index;
            this.content = content;
            this.position = position;
            this.distance = distance;
        }
    }

    /**
     * Find k nearest neighbors to the query point in tetrahedral space
     * 
     * @param queryPoint the point to search around (must have positive coordinates)
     * @param k number of neighbors to find
     * @param tetree the tetree to search in
     * @return list of k nearest neighbors sorted by distance (closest first)
     */
    public static <Content> List<TetKNNCandidate<Content>> findKNearestNeighbors(
            Point3f queryPoint, int k, Tetree<Content> tetree) {
        return findKNearestNeighbors(queryPoint, k, tetree, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
    }
    
    /**
     * Find k nearest neighbors to the query point in tetrahedral space with simplex aggregation
     * 
     * @param queryPoint the point to search around (must have positive coordinates)
     * @param k number of neighbors to find
     * @param tetree the tetree to search in
     * @param strategy how to handle multiple simplicies in the same spatial region
     * @return list of k nearest neighbors sorted by distance (closest first)
     */
    public static <Content> List<TetKNNCandidate<Content>> findKNearestNeighbors(
            Point3f queryPoint, int k, Tetree<Content> tetree, SimplexAggregationStrategy strategy) {
        
        // Early validation
        if (k <= 0) {
            return Collections.emptyList();
        }
        
        // Validate positive coordinates (required for tetrahedral operations)
        validatePositiveCoordinates(queryPoint);

        // Step 1: Use the tetree's locate method to find the enclosing tetrahedron for the query point
        // This gives us a starting point and level to work with
        var queryTet = tetree.locate(queryPoint, (byte) 15); // Use high resolution for initial locate
        
        // Step 2: Create a spatial volume that encompasses the expected search area
        // For now, we'll create a spatial tetrahedron around the query point 
        // but we need to be more sophisticated about this
        
        // Get the bounding tetrahedron vertices to create a proper spatial volume
        var vertices = queryTet.coordinates();
        
        // Find the bounds of this tetrahedron
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
        
        // Expand the bounds to include a reasonable search area
        float expansion = Math.max(Math.max(maxX - minX, maxY - minY), maxZ - minZ) * 5; // 5x the tetrahedron size
        minX = Math.max(0, minX - expansion); // Ensure positive coordinates
        minY = Math.max(0, minY - expansion);
        minZ = Math.max(0, minZ - expansion);
        maxX += expansion;
        maxY += expansion;
        maxZ += expansion;
        
        // Create a spatial volume that respects tetrahedral constraints
        Spatial.aabb searchVolume = new Spatial.aabb(minX, minY, minZ, maxX, maxY, maxZ);
        
        // Step 3: Get simplicies bounded by this search volume
        var spatialResults = tetree.boundedBy(searchVolume);
        
        // Step 4: Apply simplex aggregation strategy
        var aggregatedResults = aggregateSimplicies(spatialResults, strategy);
        
        // Step 5: Create candidates by calculating distance from query point to each simplex
        List<TetKNNCandidate<Content>> candidates = new ArrayList<>();
        
        for (var simplex : aggregatedResults) {
            long tetIndex = simplex.index();
            Content content = simplex.cell();
            
            // Calculate distance from query point to this tetrahedron
            float distance = distanceToTetrahedron(queryPoint, tetIndex);
            
            // Get tetrahedron center as position
            Point3f position = tetrahedronCenter(tetIndex);
            
            // Create candidate
            TetKNNCandidate<Content> candidate = new TetKNNCandidate<>(
                tetIndex, content, position, distance);
            candidates.add(candidate);
        }
        
        // Step 6: Sort candidates by distance (closest first)
        candidates.sort(Comparator.comparing(c -> c.distance));
        
        // Step 7: Return the first k candidates
        return candidates.stream()
            .limit(k)
            .collect(Collectors.toList());
    }

    /**
     * Test helper method to calculate distance from a query point to a specific tetrahedron
     * 
     * @param queryPoint the point to measure distance from (must have positive coordinates) 
     * @param tetIndex the tetrahedral SFC index
     * @return distance from point to tetrahedron (0 if point is inside)
     */
    public static float calculateDistanceToTet(Point3f queryPoint, long tetIndex) {
        validatePositiveCoordinates(queryPoint);
        return distanceToTetrahedron(queryPoint, tetIndex);
    }

    /**
     * Test helper method to get the center of a tetrahedron
     * 
     * @param tetIndex the tetrahedral SFC index
     * @return center point of the tetrahedron
     */
    public static Point3f getTetCenter(long tetIndex) {
        return tetrahedronCenter(tetIndex);
    }
}