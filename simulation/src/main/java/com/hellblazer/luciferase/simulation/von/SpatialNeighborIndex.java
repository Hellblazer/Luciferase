package com.hellblazer.luciferase.simulation.von;

import com.hellblazer.luciferase.simulation.bubble.BubbleBounds;
import javafx.geometry.Point3D;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Spatial index for VON neighbor discovery using Tetree-based queries.
 * <p>
 * This class REPLACES SFVoronoi from Thoth's Perceptron pattern.
 * Instead of computing Voronoi diagrams, it uses:
 * - k-nearest neighbor queries via position distance
 * - Bounds overlap detection via BubbleBounds.overlaps()
 * - AOI radius for boundary detection
 * <p>
 * Key Architectural Point:
 * - NO Voronoi calculation - spatial index queries only
 * - Enclosing neighbors = bounds overlap
 * - Boundary neighbors = distance in (aoiRadius, aoiRadius + buffer]
 * <p>
 * Thread-safe: Uses ConcurrentHashMap for concurrent access.
 *
 * @author hal.hildebrand
 */
public class SpatialNeighborIndex {

    private final Map<UUID, Node> nodes = new ConcurrentHashMap<>();
    private final float aoiRadius;
    private final float boundaryBuffer;

    /**
     * Create a spatial neighbor index.
     *
     * @param aoiRadius       Area of Interest radius
     * @param boundaryBuffer  Additional buffer for boundary detection
     */
    public SpatialNeighborIndex(float aoiRadius, float boundaryBuffer) {
        this.aoiRadius = aoiRadius;
        this.boundaryBuffer = boundaryBuffer;
    }

    /**
     * Insert a node into the index.
     *
     * @param node VON node to insert
     */
    public void insert(Node node) {
        nodes.put(node.id(), node);
    }

    /**
     * Remove a node from the index.
     *
     * @param nodeId Node UUID to remove
     */
    public void remove(UUID nodeId) {
        nodes.remove(nodeId);
    }

    /**
     * Get a node by ID.
     *
     * @param nodeId Node UUID
     * @return Node or null if not found
     */
    public Node get(UUID nodeId) {
        return nodes.get(nodeId);
    }

    /**
     * Update a node's position.
     * <p>
     * Note: Position is tracked via the node reference itself (bubble.centroid()).
     * This method is a no-op but provided for API completeness.
     *
     * @param nodeId      Node UUID
     * @param newPosition New position
     */
    public void updatePosition(UUID nodeId, Point3D newPosition) {
        // Position is tracked via node.position() which delegates to bubble.centroid()
        // No explicit update needed - position is computed on-demand
    }

    /**
     * Find closest node to a position.
     * <p>
     * Replaces SFVoronoi.closestTo().
     *
     * @param position Query position
     * @return Closest Node or null if index is empty
     */
    public Node findClosestTo(Point3D position) {
        return nodes.values().stream()
            .min(Comparator.comparingDouble(n -> distance(n.position(), position)))
            .orElse(null);
    }

    /**
     * Find k nearest nodes to a position.
     * <p>
     * Replaces Voronoi neighbor discovery.
     *
     * @param position Query position
     * @param k        Number of neighbors
     * @return List of k nearest nodes (may be < k if index has fewer nodes)
     */
    public List<Node> findKNearest(Point3D position, int k) {
        return nodes.values().stream()
            .sorted(Comparator.comparingDouble(n -> distance(n.position(), position)))
            .limit(k)
            .toList();
    }

    /**
     * Find nodes whose bounds overlap with given bounds.
     * <p>
     * Replaces Voronoi enclosing neighbors.
     *
     * @param bounds Bounds to test
     * @return Set of overlapping nodes
     */
    public Set<Node> findOverlapping(BubbleBounds bounds) {
        return nodes.values().stream()
            .filter(n -> n.bounds().overlaps(bounds))
            .collect(Collectors.toSet());
    }

    /**
     * Find nodes within a radius of a position.
     * <p>
     * Useful for AOI queries.
     *
     * @param center Query position
     * @param radius Search radius
     * @return List of nodes within radius
     */
    public List<Node> findWithinRadius(Point3D center, float radius) {
        return nodes.values().stream()
            .filter(n -> distance(n.position(), center) <= radius)
            .toList();
    }

    /**
     * Check if target is a boundary neighbor of source.
     * <p>
     * Boundary neighbors are at distance > aoiRadius and <= aoiRadius + buffer.
     *
     * @param source Source node
     * @param target Target node
     * @return true if target is in boundary zone
     */
    public boolean isBoundaryNeighbor(Node source, Node target) {
        double dist = distance(source.position(), target.position());
        return dist > aoiRadius && dist <= (aoiRadius + boundaryBuffer);
    }

    /**
     * Check if target is an enclosing neighbor of source.
     * <p>
     * Enclosing neighbors have overlapping bounds.
     *
     * @param source Source node
     * @param target Target node
     * @return true if bounds overlap
     */
    public boolean isEnclosingNeighbor(Node source, Node target) {
        return source.bounds().overlaps(target.bounds());
    }

    /**
     * Get all nodes in the index.
     *
     * @return Collection of all VON nodes
     */
    public Collection<Node> getAllNodes() {
        return nodes.values();
    }

    /**
     * Get the number of nodes in the index.
     *
     * @return Node count
     */
    public int size() {
        return nodes.size();
    }

    /**
     * Check if index is empty.
     *
     * @return true if no nodes in index
     */
    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    /**
     * Calculate Euclidean distance between two points.
     *
     * @param p1 First point
     * @param p2 Second point
     * @return Distance
     */
    private double distance(Point3D p1, Point3D p2) {
        return p1.distance(p2);
    }

    @Override
    public String toString() {
        return String.format("SpatialNeighborIndex{nodes=%d, aoiRadius=%.2f, boundaryBuffer=%.2f}",
                           nodes.size(), aoiRadius, boundaryBuffer);
    }
}
