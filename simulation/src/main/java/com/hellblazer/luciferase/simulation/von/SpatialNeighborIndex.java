/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.simulation.von;

import com.hellblazer.luciferase.lucien.entity.UUIDEntityID;
import com.hellblazer.luciferase.lucien.entity.UUIDGenerator;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.simulation.bubble.BubbleBounds;
import com.hellblazer.luciferase.simulation.bubble.SpatialLevelHeuristic;
import javafx.geometry.Point3D;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Spatial index for VON neighbor discovery, dispatching per operation between a
 * {@link Tetree}-backed path and a flat {@link ConcurrentHashMap} path.
 * <p>
 * <b>Dual-store rationale (RDR-003 Phase 0 Step 2 evolution).</b> Step 3
 * validation ({@code Luciferase-sc4}) measured the original "all queries on
 * Tetree" design from Step 2 ({@code Luciferase-mj7}) and the Step 2.1 fix
 * ({@code Luciferase-2mn}), then a level-sweep and a stream-vs-imperative spike.
 * Findings:
 * <ul>
 *   <li>{@link #findWithinRadius}: at the VoN operational radius (~50 units in a
 *       200<sup>3</sup> world, ~6% of world volume per query), Tetree-backed
 *       range queries spend most of their cost on per-candidate
 *       {@code getEntity} concurrent-map lookups (~500 ns each) over the ~12,500
 *       sphere-AABB candidates at N=100K. Total ~6&ndash;8 ms. The flat
 *       {@code ConcurrentHashMap} linear scan over N=100K is ~1.6 ms — 4&ndash;5×
 *       faster because its per-entity cost is just a {@link Point3D#distance}
 *       (~15 ns), no map lookup. The Tetree's spatial pruning (~8× candidate
 *       reduction) does not overcome its ~30× per-entity overhead penalty at
 *       this workload. Spatial-level tuning does not change this (level-sweep
 *       at levels 14&ndash;18 produced identical results within noise).</li>
 *   <li>{@link #findKNearest}: the Tetree's k-NN result cache
 *       ({@code AbstractSpatialIndex.java:1429-1438}) keys at level 15
 *       (cell-edge 64 units in a 200<sup>3</sup> world → ~64 cache buckets).
 *       At the VoN tick rate (60 Hz, bubbles moving ~5 units/tick), consecutive
 *       queries from the same bubble stay in the same cache cell for ~13 ticks
 *       → cache hits dominate, giving 0.5 μs lookup latency vs the linear-scan
 *       {@code O(N log N)} sort (21 ms at N=100K). This is a real production
 *       benefit even though cold-cache k-NN cost is higher.</li>
 * </ul>
 * <p>
 * Therefore: {@link #findKNearest} and {@link #findClosestTo} route through the
 * Tetree (for the k-NN cache benefit). {@link #findWithinRadius},
 * {@link #findOverlapping}, {@link #getAllNodes}, {@link #get}, {@link #size},
 * and {@link #isEmpty} route through the flat map. Insert / remove /
 * updatePosition operations keep both stores synchronised.
 * <p>
 * <b>What about closing {@code Luciferase-gig} and {@code Luciferase-ay7}?</b>
 * The architectural integration with Tetree exists; future workloads that
 * actually benefit from spatial pruning (small radius, very large N, or queries
 * that don't iterate every candidate) can route through the Tetree path
 * directly. The dispatch policy is conservative for VoN's measured workload,
 * not a rejection of the spatial-index option.
 * <p>
 * <b>Public API preserved.</b> Existing callers using the two-argument
 * constructor continue to work; the spatial level is computed from the
 * {@code aoiRadius} via {@link SpatialLevelHeuristic#computeDefault(float)}.
 * A three-argument constructor lets deployments pin an explicit level.
 * <p>
 * <b>Behavioral contract change vs the pre-mj7 implementation.</b>
 * {@link #updatePosition(UUID, Point3D)} is no longer a no-op — it propagates
 * the new centroid to the {@code Tetree} so the {@link #findKNearest} /
 * {@link #findClosestTo} path sees correct spatial bucketing. The flat map's
 * stored {@link Node} reference is unchanged since the {@link Node} reads
 * its position dynamically.
 * <p>
 * <b>Coordinate domain.</b> {@link Tetree} requires non-negative coordinates
 * (tetrahedral SFC root is the positive octant). Callers must supply positions
 * in {@code [0, +&infin;)}<sup>3</sup>. Negative components produce an
 * {@link IllegalArgumentException} from the Tetree path.
 * <p>
 * Thread-safe: {@link ConcurrentHashMap} for the flat store, {@link Tetree}'s
 * internal read/write locking for the spatial path.
 *
 * @author hal.hildebrand
 */
public class SpatialNeighborIndex {

    private final Tetree<UUIDEntityID, Node> tetree;
    private final ConcurrentHashMap<UUID, Node> nodes;
    private final byte                          spatialLevel;
    private final float                         aoiRadius;
    private final float                         boundaryBuffer;

    /**
     * Create a spatial neighbor index with the default heuristic-derived spatial level.
     * <p>
     * The spatial level is computed via
     * {@link SpatialLevelHeuristic#computeDefault(float)} from {@code aoiRadius}. For
     * the canonical VoN configuration ({@code aoiRadius = 50}, world extent
     * {@code 200}) this resolves to level {@code 18} (cell-edge 8 units). The level
     * is consumed by the Tetree path ({@link #findKNearest}); the flat-map path
     * ({@link #findWithinRadius}) is level-agnostic.
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
     * heuristic-derived default. The level only affects the Tetree path
     * ({@link #findKNearest} / {@link #findClosestTo}); the flat-map path is
     * unaffected.
     *
     * @param aoiRadius      Area of Interest radius
     * @param boundaryBuffer Additional buffer for boundary detection
     * @param spatialLevel   Tetree refinement level for indexed entities
     */
    public SpatialNeighborIndex(float aoiRadius, float boundaryBuffer, byte spatialLevel) {
        this.aoiRadius      = aoiRadius;
        this.boundaryBuffer = boundaryBuffer;
        this.spatialLevel   = spatialLevel;
        this.tetree         = new Tetree<>(new UUIDGenerator());
        this.nodes          = new ConcurrentHashMap<>();
    }

    /**
     * Insert a node into both stores.
     *
     * @param node VON node to insert. Its centroid (returned by {@link Node#position()})
     *             must have non-negative coordinates &mdash; Tetree constraint.
     */
    public void insert(Node node) {
        tetree.insert(new UUIDEntityID(node.id()), toPoint3f(node.position()), spatialLevel, node);
        nodes.put(node.id(), node);
    }

    /**
     * Remove a node from both stores.
     *
     * @param nodeId Node UUID to remove
     */
    public void remove(UUID nodeId) {
        tetree.removeEntity(new UUIDEntityID(nodeId));
        nodes.remove(nodeId);
    }

    /**
     * Get a node by ID. Reads from the flat map for direct {@code O(1)} lookup
     * without the Tetree's per-cell traversal overhead.
     *
     * @param nodeId Node UUID
     * @return Node or null if not found
     */
    public Node get(UUID nodeId) {
        return nodes.get(nodeId);
    }

    /**
     * Update a node's spatial bucketing in the Tetree to reflect a new centroid.
     * <p>
     * The flat map's stored {@link Node} reference is unchanged (the {@link Node}
     * reads its position dynamically). The Tetree's spatial pruning depends on
     * the entity being stored at its current centroid, so callers that move a
     * node's centroid MUST invoke this so subsequent {@link #findKNearest} /
     * {@link #findClosestTo} queries see the correct spatial neighborhood.
     *
     * @param nodeId      Node UUID
     * @param newPosition New centroid position (must have non-negative coordinates)
     */
    public void updatePosition(UUID nodeId, Point3D newPosition) {
        tetree.updateEntity(new UUIDEntityID(nodeId), toPoint3f(newPosition), spatialLevel);
    }

    /**
     * Find closest node to a position. Routes through the Tetree's
     * {@code kNearestNeighbors} for the k-NN cache benefit
     * (see class JavaDoc for the rationale).
     *
     * @param position Query position
     * @return Closest Node or null if index is empty
     */
    public Node findClosestTo(Point3D position) {
        var ids = tetree.kNearestNeighbors(toPoint3f(position), 1, Float.POSITIVE_INFINITY);
        return ids.isEmpty() ? null : nodes.get(ids.get(0).getValue());
    }

    /**
     * Find k nearest nodes to a position. Routes through the Tetree for the
     * k-NN cache benefit (see class JavaDoc).
     *
     * @param position Query position
     * @param k        Number of neighbors
     * @return List of up to k nearest nodes, sorted by distance
     */
    public List<Node> findKNearest(Point3D position, int k) {
        return tetree.kNearestNeighbors(toPoint3f(position), k, Float.POSITIVE_INFINITY).stream()
            .map(id -> nodes.get(id.getValue()))
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }

    /**
     * Find nodes whose bounds overlap with the given bounds. Linear scan of the
     * flat map — Tetree centroid indexing cannot accelerate bounds-based queries
     * because a node's bounds can extend past its centroid into the query
     * region. Observed regression-class in
     * {@code IntegrationTest.testConcurrentJoinsHandled} when a centroid-based
     * broad-phase was attempted (neighbor consistency dropped from
     * {@code &ge; 0.5} to {@code 0.43}).
     *
     * @param queryBounds Bounds to test against
     * @return Set of nodes whose bounds overlap {@code queryBounds}
     */
    public Set<Node> findOverlapping(BubbleBounds queryBounds) {
        return nodes.values().stream()
            .filter(n -> n.bounds().overlaps(queryBounds))
            .collect(Collectors.toSet());
    }

    /**
     * Find nodes within a radius of a position. Linear scan of the flat map.
     * <p>
     * See class JavaDoc for the measurement-driven rationale: the Tetree's
     * spatial pruning at VoN's typical {@code r} (covering ~6% of world volume)
     * is not competitive with linear scan because the per-candidate Tetree
     * {@code getEntity} cost dominates. Measured Tetree-backed path at N=100K
     * r=50 was 7.66 ms; flat-map linear scan was 1.57 ms.
     *
     * @param center Query position
     * @param radius Search radius (non-negative)
     * @return List of nodes whose centroid lies within {@code radius} of {@code center}
     */
    public List<Node> findWithinRadius(Point3D center, float radius) {
        var radiusSq = (double) radius * radius;
        var cx = center.getX();
        var cy = center.getY();
        var cz = center.getZ();
        var result = new ArrayList<Node>();
        for (var n : nodes.values()) {
            var pos = n.position();
            var dx = pos.getX() - cx;
            var dy = pos.getY() - cy;
            var dz = pos.getZ() - cz;
            if (dx * dx + dy * dy + dz * dz <= radiusSq) {
                result.add(n);
            }
        }
        return result;
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
     * Get all nodes currently in the index. Returns a snapshot collection
     * (subsequent inserts/removes do not affect the returned view).
     */
    public Collection<Node> getAllNodes() {
        return new ArrayList<>(nodes.values());
    }

    /**
     * Get the number of nodes in the index.
     */
    public int size() {
        return nodes.size();
    }

    /**
     * Check if the index is empty.
     */
    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    @Override
    public String toString() {
        return String.format("SpatialNeighborIndex{nodes=%d, level=%d, aoiRadius=%.2f, boundaryBuffer=%.2f}",
                             size(), spatialLevel, aoiRadius, boundaryBuffer);
    }

    // ==================== private helpers ====================

    /**
     * Convert a JavaFX double-precision {@link Point3D} to a vecmath single-precision
     * {@link Point3f} for Tetree consumption. Float-mantissa precision (~7 decimal
     * digits) is sufficient for VoN's 200-unit world.
     */
    private static Point3f toPoint3f(Point3D p) {
        return new Point3f((float) p.getX(), (float) p.getY(), (float) p.getZ());
    }

}
