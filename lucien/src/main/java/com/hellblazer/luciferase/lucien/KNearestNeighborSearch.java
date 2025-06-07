package com.hellblazer.luciferase.lucien;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.stream.Collectors;

/**
 * k-Nearest Neighbor search implementation for Octree
 * Finds the k closest entries to a query point using distance-based priority queues
 * 
 * @author hal.hildebrand
 */
public class KNearestNeighborSearch {

    /**
     * Candidate entry for k-NN search with distance information
     */
    public static class KNNCandidate<Content> {
        public final long index;
        public final Content content;
        public final Point3f position;
        public final float distance;

        public KNNCandidate(long index, Content content, Point3f position, float distance) {
            this.index = index;
            this.content = content;
            this.position = position;
            this.distance = distance;
        }
    }

    /**
     * Node candidate for expansion during search
     */
    private static class NodeCandidate {
        public final long index;
        public final Spatial.Cube cube;
        public final float wallDistance;

        public NodeCandidate(long index, Spatial.Cube cube, float wallDistance) {
            this.index = index;
            this.cube = cube;
            this.wallDistance = wallDistance;
        }
    }

    /**
     * Find k nearest neighbors to the query point
     * 
     * @param queryPoint the point to search around (must have positive coordinates)
     * @param k number of neighbors to find
     * @param octree the octree to search in
     * @return list of k nearest neighbors sorted by distance (closest first)
     */
    public static <Content> List<KNNCandidate<Content>> findKNearestNeighbors(
            Point3f queryPoint, int k, Octree<Content> octree) {
        return findKNearestNeighborsImpl(queryPoint, k, octree.getMap(), 
            index -> Octree.toCube(index));
    }
    
    /**
     * Find k nearest neighbors to the query point using SingleContentAdapter
     * 
     * @param queryPoint the point to search around (must have positive coordinates)
     * @param k number of neighbors to find
     * @param adapter the adapter to search in
     * @return list of k nearest neighbors sorted by distance (closest first)
     */
    public static <Content> List<KNNCandidate<Content>> findKNearestNeighbors(
            Point3f queryPoint, int k, SingleContentAdapter<Content> adapter) {
        return findKNearestNeighborsImpl(queryPoint, k, adapter.getMap(), 
            index -> SingleContentAdapter.toCube(index));
    }
    
    /**
     * Common implementation for k-NN search
     */
    private static <Content> List<KNNCandidate<Content>> findKNearestNeighborsImpl(
            Point3f queryPoint, int k, NavigableMap<Long, Content> map, 
            java.util.function.Function<Long, Spatial.Cube> toCube) {
        
        if (k <= 0) {
            return Collections.emptyList();
        }
        
        // Validate positive coordinates
        if (queryPoint.x < 0 || queryPoint.y < 0 || queryPoint.z < 0) {
            throw new IllegalArgumentException("Query point must have positive coordinates");
        }

        if (map.isEmpty()) {
            return Collections.emptyList();
        }

        // Create candidates for all entries by calculating minimum distance from query point to cube
        List<KNNCandidate<Content>> allCandidates = new ArrayList<>();
        
        for (Map.Entry<Long, Content> entry : map.entrySet()) {
            Spatial.Cube cube = toCube.apply(entry.getKey());
            
            // Calculate minimum distance from query point to cube (0 if inside cube)
            float distance = calculateMinDistanceToBox(queryPoint, cube);
            Point3f cubeCenter = getCubeCenter(cube);
            
            KNNCandidate<Content> candidate = new KNNCandidate<>(
                entry.getKey(), entry.getValue(), cubeCenter, distance);
            allCandidates.add(candidate);
        }

        // Sort all candidates by distance and take the first k
        allCandidates.sort(Comparator.comparing(c -> c.distance));
        
        return allCandidates.stream()
            .limit(k)
            .collect(Collectors.toList());
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

    /**
     * Calculate the center point of a cube
     */
    private static Point3f getCubeCenter(Spatial.Cube cube) {
        float halfExtent = cube.extent() / 2.0f;
        return new Point3f(
            cube.originX() + halfExtent,
            cube.originY() + halfExtent,
            cube.originZ() + halfExtent
        );
    }

    /**
     * Calculate minimum distance from a point to a cube (0 if point is inside cube)
     */
    private static float calculateMinDistanceToBox(Point3f point, Spatial.Cube cube) {
        float dx = Math.max(0, Math.max(cube.originX() - point.x, 
                                       point.x - (cube.originX() + cube.extent())));
        float dy = Math.max(0, Math.max(cube.originY() - point.y, 
                                       point.y - (cube.originY() + cube.extent())));
        float dz = Math.max(0, Math.max(cube.originZ() - point.z, 
                                       point.z - (cube.originZ() + cube.extent())));
        
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Calculate minimum distance from a point to a cube's surface
     * Returns 0 if point is inside the cube
     */
    private static float calculateWallDistance(Point3f point, Spatial.Cube cube) {
        return calculateMinDistanceToBox(point, cube);
    }
}