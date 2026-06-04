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
import javax.vecmath.Point3d;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Spatial index for VON neighbor discovery. All read paths execute as linear
 * scans of an internal {@link ConcurrentHashMap}. A {@link Tetree} mirror is
 * retained as a write-only architectural option (kept in sync by insert /
 * remove / updatePosition) but no read path currently consumes it.
 * <p>
 * <b>Why ALL reads via the flat map (RDR-003 Phase 0 evolution + cold-cache
 * benchmark).</b> Phase 0 Step 3 validation (sc4) measured the original
 * "all queries on Tetree" design and found per-candidate {@code getEntity}
 * cost dominated {@link #findWithinRadius} for VoN's typical radii — the
 * dual-store dispatcher was introduced, routing range queries via flat-map
 * linear scan but keeping {@link #findKNearest} on the Tetree for its k-NN
 * cache benefit (0.5 μs lookup latency for cycled-query workloads).
 * <p>
 * The cold-cache benchmark
 * ({@code simulation/src/test/java/.../TetreeKNearestColdCacheBenchmark.java})
 * then measured the cost when cache misses dominate: 326 μs at N=1K, 11 ms
 * at N=10K, 688 ms at N=100K — catastrophic against the 2 ms operational /
 * 5 ms stress thresholds. Production cache-miss rate is unmeasured but
 * non-trivial: each Tetree write (via {@code updateEntity}) can bump
 * {@code spatialVersion} and invalidate cache entries; high-update-rate
 * workloads (every bubble moves every tick) could see most queries cold.
 * <p>
 * Per-cell summary:
 *
 * <table>
 * <tr><th>N</th><th>Linear scan</th><th>Tetree cache-hit</th><th>Tetree cold-cache</th></tr>
 * <tr><td>1K</td>   <td>0.10 ms</td>  <td>0.5 μs</td>  <td>0.33 ms</td></tr>
 * <tr><td>10K</td>  <td>1.6 ms</td>   <td>0.5 μs</td>  <td>11 ms</td></tr>
 * <tr><td>100K</td> <td>22 ms</td>    <td>0.5 μs</td>  <td>688 ms</td></tr>
 * </table>
 *
 * Linear scan is the safer floor: 3-32× faster than cold Tetree at every
 * measured N, with predictable latency that does not collapse under cache
 * pressure. It does sacrifice the cache-hit fast path (which would have given
 * sub-μs latency for steady-state cycled queries) — and the N=100K linear-scan
 * cost (22 ms) does exceed the 5 ms stress threshold from Phase 0 Step 4. The
 * deliberate choice (Phase 0 Step 5, post-cold-cache-measurement) is to accept
 * the stress-threshold regression in exchange for predictability across all
 * cache states.
 * <p>
 * Final dispatch policy: ALL read paths use the flat map. The Tetree mirror
 * is retained for the architectural option but does no reads. A follow-up
 * decision to remove it entirely is out of scope for this iteration.
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
 * {@link #updatePosition(UUID, Point3d)} is no longer a no-op — it propagates
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
    public void updatePosition(UUID nodeId, Point3d newPosition) {
        tetree.updateEntity(new UUIDEntityID(nodeId), toPoint3f(newPosition), spatialLevel);
    }

    /**
     * Find closest node to a position. Linear scan of the flat map.
     * <p>
     * Previously routed through the Tetree's k-NN cache for cycled-query
     * workloads. The cold-cache benchmark
     * ({@code TetreeKNearestColdCacheBenchmark}) showed Tetree cold-cache cost
     * is 11 ms at N=10K and 688 ms at N=100K — catastrophic for any tick
     * budget when cache misses dominate. Linear scan is consistent across all
     * N (sub-millisecond at N=10K, ~22 ms at N=100K) and avoids the
     * cliff. Single-pass min algorithm; O(N) with no allocations.
     *
     * @param position Query position
     * @return Closest Node or null if index is empty
     */
    public Node findClosestTo(Point3d position) {
        if (nodes.isEmpty()) {
            return null;
        }
        var cx = position.getX();
        var cy = position.getY();
        var cz = position.getZ();
        Node   best   = null;
        double bestD2 = Double.POSITIVE_INFINITY;
        for (var n : nodes.values()) {
            var pos = n.position();
            var dx = pos.getX() - cx;
            var dy = pos.getY() - cy;
            var dz = pos.getZ() - cz;
            var d2 = dx * dx + dy * dy + dz * dz;
            if (d2 < bestD2) {
                bestD2 = d2;
                best   = n;
            }
        }
        return best;
    }

    /**
     * Find k nearest nodes to a position. Linear scan of the flat map with a
     * bounded max-heap of size k.
     * <p>
     * See {@link #findClosestTo} for the cold-cache rationale. Algorithm is
     * O(N log k) — efficient for the small k typical in VoN
     * ({@code k = 10}). Returns at most k nodes sorted closest-first.
     *
     * @param position Query position
     * @param k        Number of neighbors (must be {@code > 0})
     * @return List of up to k nearest nodes, sorted by ascending distance
     */
    public List<Node> findKNearest(Point3d position, int k) {
        if (k <= 0 || nodes.isEmpty()) {
            return List.of();
        }
        var cx = position.getX();
        var cy = position.getY();
        var cz = position.getZ();
        // Max-heap on distance² so the top is the farthest current member of
        // the top-k. A candidate enters only if it beats the top.
        var heap = new java.util.PriorityQueue<Scored>(k, (a, b) -> Double.compare(b.d2, a.d2));
        for (var n : nodes.values()) {
            var pos = n.position();
            var dx = pos.getX() - cx;
            var dy = pos.getY() - cy;
            var dz = pos.getZ() - cz;
            var d2 = dx * dx + dy * dy + dz * dz;
            if (heap.size() < k) {
                heap.add(new Scored(d2, n));
            } else if (d2 < heap.peek().d2) {
                heap.poll();
                heap.add(new Scored(d2, n));
            }
        }
        // Heap polls farthest first; reverse to closest-first.
        var result = new ArrayList<Node>(heap.size());
        while (!heap.isEmpty()) {
            result.add(heap.poll().node);
        }
        java.util.Collections.reverse(result);
        return result;
    }

    private record Scored(double d2, Node node) {
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
        if (queryBounds == null) {
            return Set.of();
        }
        return nodes.values().stream()
            .filter(n -> n.bounds() != null && n.bounds().overlaps(queryBounds))
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
    public List<Node> findWithinRadius(Point3d center, float radius) {
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
        return source.bounds() != null && target.bounds() != null
            && source.bounds().overlaps(target.bounds());
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
     * Convert a JavaFX double-precision {@link Point3d} to a vecmath single-precision
     * {@link Point3f} for Tetree consumption. Float-mantissa precision (~7 decimal
     * digits) is sufficient for VoN's 200-unit world.
     */
    private static Point3f toPoint3f(Point3d p) {
        return new Point3f((float) p.getX(), (float) p.getY(), (float) p.getZ());
    }

}
