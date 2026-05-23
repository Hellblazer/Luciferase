/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.simulation.von;

import com.hellblazer.luciferase.geometry.MortonCurve;
import com.hellblazer.luciferase.lucien.Spatial;
import com.hellblazer.luciferase.lucien.entity.UUIDEntityID;
import com.hellblazer.luciferase.lucien.entity.UUIDGenerator;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.simulation.bubble.BubbleBounds;
import com.hellblazer.luciferase.simulation.bubble.SpatialLevelHeuristic;
import javafx.geometry.Point3D;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Spatial index for VON neighbor discovery, backed by a {@link Tetree}.
 * <p>
 * RDR-003 Phase 0 Step 2 replaces the previous {@code ConcurrentHashMap} flat
 * implementation (linear scan over all entries for every AoI query) with a real
 * spatial index. {@link #findWithinRadius} now delegates to
 * {@link com.hellblazer.luciferase.lucien.AbstractSpatialIndex#findNeighborsIncludingGhosts};
 * {@link #findKNearest} delegates to
 * {@link com.hellblazer.luciferase.lucien.AbstractSpatialIndex#kNearestNeighbors}.
 * <p>
 * <b>Public API preserved.</b> Existing callers using the two-argument constructor
 * continue to work; the spatial level is computed from the {@code aoiRadius} via
 * {@link SpatialLevelHeuristic#computeDefault(float)}, targeting {@code r &approx;
 * 8&middot;cell-edge} (RDR-003 §Approach). A new three-argument constructor lets
 * deployments pin an explicit level when their AoI distribution diverges from the
 * default.
 * <p>
 * <b>Behavioral contract change.</b>
 * {@link #updatePosition(UUID, Point3D)} is no longer a no-op &mdash; it now
 * propagates the new centroid to the {@code Tetree} via
 * {@link Tetree#updateEntity}. {@code MoveProtocol} already calls this method on
 * every move, so existing callers get correct spatial bucketing automatically.
 * Callers that never invoked {@code updatePosition} previously (relying on the
 * "position is read fresh from {@code node.position()}" semantics of the linear-scan
 * implementation) will see stale spatial bucketing for any node whose centroid
 * drifts after insert. This is detected by the existing VoN test suite.
 * <p>
 * <b>Coordinate domain.</b> {@link Tetree} requires non-negative coordinates
 * (tetrahedral SFC root is the positive octant). Callers must supply positions in
 * {@code [0, +&infin;)}<sup>3</sup>. Negative components produce an
 * {@link IllegalArgumentException} from the underlying Tetree &mdash; this matches
 * the contract that the existing test suite already honors explicitly.
 * <p>
 * Thread-safe: {@link Tetree} provides internal read/write locking.
 *
 * @author hal.hildebrand
 */
public class SpatialNeighborIndex {

    private final Tetree<UUIDEntityID, Node> tetree;
    private final byte                       spatialLevel;
    private final float                      aoiRadius;
    private final float                      boundaryBuffer;

    /**
     * Create a spatial neighbor index with the default heuristic-derived spatial level.
     * <p>
     * The spatial level is computed via
     * {@link SpatialLevelHeuristic#computeDefault(float)} from {@code aoiRadius}. For
     * the canonical VoN configuration ({@code aoiRadius = 50}, world extent
     * {@code 200}) this resolves to level {@code 18} (cell-edge 8 units), placing
     * {@code r &approx; 6&middot;cell-edge} in the favorable end of the spatial-index
     * pruning curve.
     *
     * @param aoiRadius      Area of Interest radius
     * @param boundaryBuffer Additional buffer for boundary detection
     */
    public SpatialNeighborIndex(float aoiRadius, float boundaryBuffer) {
        this(aoiRadius, boundaryBuffer, SpatialLevelHeuristic.computeDefault(aoiRadius));
    }

    /**
     * Create a spatial neighbor index with an explicit Tetree refinement level.
     * <p>
     * Use this constructor when the deployment's AoI distribution diverges from the
     * heuristic-derived default (for example, multi-scale simulations where the
     * AoI radius doesn't fit the {@code r &approx; 8&middot;cell-edge} target).
     *
     * @param aoiRadius      Area of Interest radius
     * @param boundaryBuffer Additional buffer for boundary detection
     * @param spatialLevel   Tetree refinement level for indexed entities
     *                       (in {@code [0, MortonCurve.MAX_REFINEMENT_LEVEL]})
     */
    public SpatialNeighborIndex(float aoiRadius, float boundaryBuffer, byte spatialLevel) {
        this.aoiRadius      = aoiRadius;
        this.boundaryBuffer = boundaryBuffer;
        this.spatialLevel   = spatialLevel;
        this.tetree         = new Tetree<>(new UUIDGenerator());
    }

    /**
     * Insert a node into the index.
     *
     * @param node VON node to insert. Its centroid (returned by {@link Node#position()})
     *             must have non-negative coordinates &mdash; Tetree constraint.
     */
    public void insert(Node node) {
        tetree.insert(new UUIDEntityID(node.id()), toPoint3f(node.position()), spatialLevel, node);
    }

    /**
     * Remove a node from the index.
     *
     * @param nodeId Node UUID to remove
     */
    public void remove(UUID nodeId) {
        tetree.removeEntity(new UUIDEntityID(nodeId));
    }

    /**
     * Get a node by ID.
     *
     * @param nodeId Node UUID
     * @return Node or null if not found
     */
    public Node get(UUID nodeId) {
        return tetree.getEntity(new UUIDEntityID(nodeId));
    }

    /**
     * Update a node's spatial bucketing to reflect a new centroid.
     * <p>
     * Unlike the linear-scan implementation this replaces, this method is NOT a
     * no-op: the Tetree's spatial pruning depends on the entity being stored at
     * its current centroid. Callers that move a node's centroid (e.g.
     * {@code MoveProtocol.move}) MUST invoke this so subsequent queries see the
     * correct spatial neighborhood.
     *
     * @param nodeId      Node UUID
     * @param newPosition New centroid position (must have non-negative coordinates)
     */
    public void updatePosition(UUID nodeId, Point3D newPosition) {
        tetree.updateEntity(new UUIDEntityID(nodeId), toPoint3f(newPosition), spatialLevel);
    }

    /**
     * Find closest node to a position.
     *
     * @param position Query position
     * @return Closest Node or null if index is empty
     */
    public Node findClosestTo(Point3D position) {
        var ids = tetree.kNearestNeighbors(toPoint3f(position), 1, Float.POSITIVE_INFINITY);
        return ids.isEmpty() ? null : tetree.getEntity(ids.get(0));
    }

    /**
     * Find k nearest nodes to a position.
     *
     * @param position Query position
     * @param k        Number of neighbors
     * @return List of up to k nearest nodes, sorted by distance
     */
    public List<Node> findKNearest(Point3D position, int k) {
        return tetree.kNearestNeighbors(toPoint3f(position), k, Float.POSITIVE_INFINITY).stream()
            .map(tetree::getEntity)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }

    /**
     * Find nodes whose bounds overlap with the given bounds.
     * <p>
     * Iterates all indexed nodes and applies {@link BubbleBounds#overlaps}. This is
     * O(N) but is intentionally NOT spatially accelerated: the Tetree indexes by
     * centroid, and a node's bounds can extend past its centroid into the query
     * bounds even when the centroid itself lies outside the query's world-space
     * envelope. A centroid-based broad-phase therefore misses valid candidates
     * &mdash; observed concretely in
     * {@code IntegrationTest.testConcurrentJoinsHandled} where neighbor consistency
     * dropped from {@code &ge; 0.5} to {@code 0.43} with a spatial broad-phase.
     * <p>
     * RDR-003 Phase 0 scope is the AoI hot path ({@link #findWithinRadius},
     * {@link #findKNearest}). {@code findOverlapping} fires once per bubble JOIN,
     * not per tick. The optimisation would need bounds-aware indexing
     * (e.g. inserting each node with an {@code EntityBounds} so the Tetree's
     * spanning policy could be used) which is out of Step 2 scope.
     *
     * @param queryBounds Bounds to test against
     * @return Set of nodes whose bounds overlap {@code queryBounds}
     */
    public Set<Node> findOverlapping(BubbleBounds queryBounds) {
        return getAllNodes().stream()
            .filter(n -> n.bounds().overlaps(queryBounds))
            .collect(Collectors.toSet());
    }

    /**
     * Find nodes within a radius of a position.
     *
     * @param center Query position
     * @param radius Search radius
     * @return List of nodes within radius
     */
    public List<Node> findWithinRadius(Point3D center, float radius) {
        return tetree.findNeighborsIncludingGhosts(toPoint3f(center), radius).stream()
            .map(result -> tetree.getEntity(result.entityId()))
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }

    /**
     * Check if target is a boundary neighbor of source.
     * <p>
     * Boundary neighbors are at distance &gt; {@code aoiRadius} and
     * &le; {@code aoiRadius + boundaryBuffer}.
     */
    public boolean isBoundaryNeighbor(Node source, Node target) {
        double dist = source.position().distance(target.position());
        return dist > aoiRadius && dist <= (aoiRadius + boundaryBuffer);
    }

    /**
     * Check if target is an enclosing neighbor of source (bounds overlap).
     */
    public boolean isEnclosingNeighbor(Node source, Node target) {
        return source.bounds().overlaps(target.bounds());
    }

    /**
     * Get all nodes currently in the index.
     */
    public Collection<Node> getAllNodes() {
        return tetree.entitiesInRegion(FULL_DOMAIN).stream()
            .map(tetree::getEntity)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }

    /**
     * Get the number of nodes in the index.
     */
    public int size() {
        return tetree.entityCount();
    }

    /**
     * Check if the index is empty.
     */
    public boolean isEmpty() {
        return tetree.entityCount() == 0;
    }

    @Override
    public String toString() {
        return String.format("SpatialNeighborIndex{nodes=%d, level=%d, aoiRadius=%.2f, boundaryBuffer=%.2f}",
                             size(), spatialLevel, aoiRadius, boundaryBuffer);
    }

    // ==================== private helpers ====================

    /**
     * Cubic envelope covering the entire Tetree positive coordinate domain
     * {@code [0, 2^MAX_REFINEMENT_LEVEL)}. Used by {@link #getAllNodes()} as a
     * "give me everything" query.
     */
    private static final Spatial.Cube FULL_DOMAIN = new Spatial.Cube(0f, 0f, 0f,
                                                                    (float) (1 << MortonCurve.MAX_REFINEMENT_LEVEL));

    /**
     * Convert a JavaFX double-precision {@link Point3D} to a vecmath single-precision
     * {@link Point3f} for Tetree consumption. Float-mantissa precision (~7 decimal
     * digits) is sufficient for VoN's 200-unit world.
     */
    private static Point3f toPoint3f(Point3D p) {
        return new Point3f((float) p.getX(), (float) p.getY(), (float) p.getZ());
    }

}
