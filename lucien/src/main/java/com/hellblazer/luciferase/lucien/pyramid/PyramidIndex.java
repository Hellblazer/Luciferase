/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.pyramid;

import com.hellblazer.luciferase.lucien.*;
import com.hellblazer.luciferase.lucien.entity.*;
import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.TetreeConnectivity;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import javax.vecmath.Tuple3i;
import javax.vecmath.Vector3f;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Pyramid-based spatial index (RDR-010, pi1.3 through pi1.5). Extends {@link AbstractSpatialIndex} with
 * {@link PyramidKey} as the SFC key type, mirroring the {@link com.hellblazer.luciferase.lucien.octree.Octree}
 * collaborator initialisation order.
 *
 * <p>All 17 abstract geometry hooks are implemented (phases B–E); none throw. Two query paths are
 * intentionally conservative pending exact tet-geometry tests (Phase E): {@link #findNodesIntersectingBounds}
 * is an O(n) scan, and ray/plane intersection on deep tet keys uses the enclosing pyramid's bounds as a
 * never-false-negative over-approximation. Queries therefore return correct (possibly over-inclusive)
 * results today; exactness and pruning are the open tail.
 *
 * @param <ID>      entity-ID type
 * @param <Content> content type
 * @author hal.hildebrand
 */
public class PyramidIndex<ID extends EntityID, Content> extends AbstractSpatialIndex<PyramidKey, ID, Content> {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PyramidIndex.class);

    /** Default maximum entities per node, mirroring Octree. */
    private static final int DEFAULT_MAX_ENTITIES_PER_NODE = 10;

    // ===== Constructors (mirroring Octree's three-constructor chain) =====

    /**
     * Create a PyramidIndex with default configuration.
     */
    public PyramidIndex(EntityIDGenerator<ID> idGenerator) {
        this(idGenerator, DEFAULT_MAX_ENTITIES_PER_NODE, PyramidKey.MAX_PYRAMID_LEVEL);
    }

    /**
     * Create a PyramidIndex with custom capacity and depth.
     */
    public PyramidIndex(EntityIDGenerator<ID> idGenerator, int maxEntitiesPerNode, byte maxDepth) {
        this(idGenerator, maxEntitiesPerNode, maxDepth, new EntitySpanningPolicy());
    }

    /**
     * Create a PyramidIndex with full configuration.
     *
     * <p>Collaborator initialisation order mirrors Octree (and therefore AbstractSpatialIndex):
     * EntityManager nucleus → SpatialIndexCore → KnnSearcher → Culler → CollisionEngine →
     * EntityLifecycleManager → GhostCoordinator. After the super-constructor the neighbor detector is
     * wired (mirroring {@code Octree}); see {@link PyramidNeighborDetector} — same-shape topology
     * (RDR-010 pi1.4 Phase B, Luciferase-mu9). Cross-shape (pyramid&harr;tet&harr;hex) ghost wiring
     * is deferred to pi1.5.
     */
    public PyramidIndex(EntityIDGenerator<ID> idGenerator, int maxEntitiesPerNode, byte maxDepth,
                        EntitySpanningPolicy spanningPolicy) {
        super(idGenerator, maxEntitiesPerNode, maxDepth, spanningPolicy);
        setNeighborDetector(new PyramidNeighborDetector(this));
    }

    // ===== TreeBalancer =====
    // createTreeBalancer() is inherited from AbstractSpatialIndex; default returns DefaultTreeBalancer.
    // No pyramid-specific balancer in Phase A.

    /**
     * Per-shape partition weight {@code N_pyramid(level) = 2·8^level − 6^level} (RDR-010 pi1.6,
     * Knapp 2026 Eq 5.1). A pyramid root refines into 6 pyramids + 4 tets; the {@code −6^level} term
     * corrects the non-uniform pyramid/tet mixing across levels. Overrides the default {@code 8^level}
     * so a hybrid-forest weighted partition ({@link com.hellblazer.luciferase.lucien.balancing.ShapeWeightPartitioner})
     * counts pyramid trees by their true element load rather than as 1:8 trees.
     *
     * <p>{@code N_pyramid(0)=1}, {@code N_pyramid(1)=10} (= {@link TetreeConnectivity#CHILDREN_PER_PYRAMID}).
     */
    @Override
    public long elementCount(int level) {
        return 2L * com.hellblazer.luciferase.lucien.balancing.ShapeWeightProvider.eightToThe(level)
               - com.hellblazer.luciferase.lucien.balancing.ShapeWeightProvider.sixToThe(level);
    }

    // ===== SpatialIndex interface: enclosing methods (Phase C) =====

    @Override
    public SpatialIndex.SpatialNode<PyramidKey, ID> enclosing(Tuple3i point, byte level) {
        var position = new Point3f(point.x, point.y, point.z);
        var key = calculateSpatialIndex(position, level);
        var node = spatialIndex.get(key);
        if (node != null && !node.isEmpty()) {
            return new SpatialIndex.SpatialNode<>(key, new java.util.HashSet<>(node.getEntityIds()));
        }
        // Return empty node for the enclosing pyramid even if no entities are present
        return new SpatialIndex.SpatialNode<>(key, new java.util.HashSet<>());
    }

    @Override
    public SpatialIndex.SpatialNode<PyramidKey, ID> enclosing(Spatial volume) {
        var bounds = getVolumeBounds(volume);
        if (bounds == null) {
            return null;
        }
        var level = findMinimumContainingLevel(bounds);
        var centerPoint = new Point3f((bounds.minX() + bounds.maxX()) / 2f,
                                      (bounds.minY() + bounds.maxY()) / 2f,
                                      (bounds.minZ() + bounds.maxZ()) / 2f);
        var key = calculateSpatialIndex(centerPoint, level);
        var node = spatialIndex.get(key);
        if (node != null && !node.isEmpty()) {
            return new SpatialIndex.SpatialNode<>(key, new java.util.HashSet<>(node.getEntityIds()));
        }
        return null;
    }

    // ===== Abstract geometry methods — Phase-C spatial-index + node-bounds cluster =====

    /**
     * Outcome of one step in the pyramid-tree descent. Distinguishes three cases:
     * <ul>
     *   <li>{@link Kind#PYRAMID} — containing child is a pyramid; {@link #pyramid()} is non-null.</li>
     *   <li>{@link Kind#TET} — containing child is a tet (leaf of the pyramid SFC);
     *       {@link #pyramid()} is null; coordBits/typeBits at the step were already written.</li>
     *   <li>{@link Kind#NOT_FOUND} — no child claimed the point (boundary degenerate case);
     *       coordBits/typeBits at the step are NOT valid.</li>
     * </ul>
     */
    private record ChildResult(Kind kind, Pyramid pyramid) {
        enum Kind { PYRAMID, TET, NOT_FOUND }

        static ChildResult pyramid(Pyramid p) { return new ChildResult(Kind.PYRAMID, p); }
        static ChildResult tet()              { return new ChildResult(Kind.TET, null); }
        static ChildResult notFound()         { return new ChildResult(Kind.NOT_FOUND, null); }
    }

    /**
     * Compute the PyramidKey of the pyramid (or tet child of pyramid) that contains {@code position}
     * at the given {@code level}. Navigates from the root cover (two type-6/7 pyramids) down to
     * {@code level} by selecting at each step the child that contains the point.
     *
     * <p>Containment uses {@link PyramidContainment#contains} for pyramid children and
     * {@link Tet#contains12DOP} for tetrahedral children (invariant §3b: contains12DOP is NEVER
     * called on type-6/7 elements).
     *
     * <p><b>Level contract caveat (Phase D/E note):</b> when {@code position} falls in a tet region
     * before {@code level} is reached, the returned key is at the tet's (shallower) level, not
     * {@code level}. Callers that require the returned key to be at exactly {@code level} must
     * account for this.
     *
     * @param position the point to locate (non-negative coordinates)
     * @param level    target refinement level, 0..{@link PyramidKey#MAX_PYRAMID_LEVEL}
     * @return the PyramidKey of the containing element at or above the given level
     */
    @Override
    protected PyramidKey calculateSpatialIndex(Point3f position, byte level) {
        if (level == 0) {
            return PyramidKey.getRoot();
        }

        // The pyramid SFC tree:
        //   Virtual root (PyramidKey level=0) → two level-0 pyramids (type 6 and 7).
        //   Each level-0 pyramid has 10 children (level-1 pyramids/tets).
        //   A PyramidKey at level L encodes the path through L refinement steps:
        //     step 1 → level-1 element (child of a level-0 root pyramid)
        //     step 2 → level-2 element (child of the step-1 element)
        //     ...
        //   coordBits[l] = cubeId of the chosen child at step l
        //   typeBits[l]  = type of the chosen child at step l
        //
        // Step 1: find the level-1 element containing the point.
        //   Iterate children of BOTH level-0 root pyramids (type-6 and type-7).
        int[] coordBits = new int[level + 1];
        int[] typeBits  = new int[level + 1];

        var type6Root = new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_6);
        var type7Root = new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_7);

        ChildResult step1 = findContainingChild(
                new Pyramid[] { type6Root, type7Root }, position, coordBits, typeBits, 1);

        if (step1.kind() == ChildResult.Kind.TET) {
            // Tet child found at step 1 — bits[1] written; return level-1 tet key.
            return PyramidKey.fromLevels((byte) 1, coordBits, typeBits);
        }
        if (step1.kind() == ChildResult.Kind.NOT_FOUND) {
            // Boundary degenerate: fall back to type-7 root child 0.
            var child0 = type7Root.child(0);
            coordBits[1] = TetreeConnectivity.PYRAMID_PARENT_TO_CHILD_CID[type7Root.type() - Pyramid.TYPE_6][0];
            typeBits[1] = TetreeConnectivity.PYRAMID_PARENT_TO_CHILD_TYPE[type7Root.type() - Pyramid.TYPE_6][0];
            if (child0 instanceof Pyramid pc) {
                step1 = ChildResult.pyramid(pc);
            } else {
                return PyramidKey.fromLevels((byte) Math.min(1, level), coordBits, typeBits);
            }
        }

        if (level == 1) {
            return PyramidKey.fromLevels(level, coordBits, typeBits);
        }

        Pyramid currentPyramid = step1.pyramid();

        // Steps 2..level: descend through the pyramid tree.
        for (int l = 2; l <= level; l++) {
            ChildResult result = findContainingChild(
                    new Pyramid[] { currentPyramid }, position, coordBits, typeBits, l);

            switch (result.kind()) {
                case PYRAMID -> currentPyramid = result.pyramid();
                case TET -> {
                    // Tet child found at step l — bits[l] were written; return level-l key.
                    return PyramidKey.fromLevels((byte) l, coordBits, typeBits);
                }
                case NOT_FOUND -> {
                    // Boundary fall-through: return the deepest pyramid we reached so far.
                    return PyramidKey.fromLevels((byte) (l - 1), coordBits, typeBits);
                }
            }
        }
        return PyramidKey.fromLevels(level, coordBits, typeBits);
    }

    /**
     * Search the children of each {@code parents} pyramid at step {@code l} to find the child
     * (pyramid or tet) that contains {@code position}. Writes {@code coordBits[l]} and
     * {@code typeBits[l]} on PYRAMID or TET matches.
     *
     * <p>Returns:
     * <ul>
     *   <li>{@link ChildResult#pyramid} — containing child is a pyramid (bits[l] written).</li>
     *   <li>{@link ChildResult#tet} — containing child is a tet (bits[l] written; leaf of
     *       the pyramid SFC; cannot descend further).</li>
     *   <li>{@link ChildResult#notFound} — no child claimed the point (bits[l] NOT written;
     *       boundary degenerate).</li>
     * </ul>
     *
     * <p>For pyramid children: uses {@link PyramidContainment#contains} (§3b invariant).
     * For tet children: uses {@link Tet#contains12DOP} — safe because Pyramid.child() only
     * produces tets with type ∈ {0..5}.
     */
    private ChildResult findContainingChild(Pyramid[] parents, Point3f position,
                                            int[] coordBits, int[] typeBits, int l) {
        for (var parent : parents) {
            int row = parent.type() - Pyramid.TYPE_6;
            for (int i = 0; i < TetreeConnectivity.CHILDREN_PER_PYRAMID; i++) {
                var child = parent.child(i);
                boolean contains;
                if (child instanceof Pyramid pc) {
                    contains = PyramidContainment.contains(pc, position);
                } else if (child instanceof Tet t) {
                    // §3b invariant: type is 0..5 (by Pyramid.child() construction).
                    contains = t.contains12DOP(position.x, position.y, position.z);
                } else {
                    continue;
                }

                if (contains) {
                    coordBits[l] = TetreeConnectivity.PYRAMID_PARENT_TO_CHILD_CID[row][i];
                    typeBits[l] = TetreeConnectivity.PYRAMID_PARENT_TO_CHILD_TYPE[row][i];
                    if (child instanceof Pyramid pc) {
                        return ChildResult.pyramid(pc);
                    }
                    // Tet child found — bits[l] written; leaf of the pyramid SFC path.
                    return ChildResult.tet();
                }
            }
        }
        // Boundary fall-through: pick first pyramid child whose AABB contains the point.
        for (var parent : parents) {
            int row = parent.type() - Pyramid.TYPE_6;
            for (int i = 0; i < TetreeConnectivity.CHILDREN_PER_PYRAMID; i++) {
                var child = parent.child(i);
                if (child instanceof Pyramid pc) {
                    float h = pc.length();
                    if (position.x >= pc.x() && position.x <= pc.x() + h
                        && position.y >= pc.y() && position.y <= pc.y() + h
                        && position.z >= pc.z() && position.z <= pc.z() + h) {
                        coordBits[l] = TetreeConnectivity.PYRAMID_PARENT_TO_CHILD_CID[row][i];
                        typeBits[l] = pc.type();
                        return ChildResult.pyramid(pc);
                    }
                }
            }
        }
        return ChildResult.notFound();
    }

    /**
     * Return the broad {@link Spatial.Cube} AABB envelope that spans the 5 vertices of the pyramid
     * addressed by {@code index}. Mirroring Tetree and Octree: the envelope is the pyramid's
     * surrounding cube (anchor + cell-size extent in all three dimensions).
     *
     * <p>This method does NOT return a {@link Spatial.aabt} implementor (invariant #7 preserved).
     *
     * @param index the PyramidKey to decode
     * @return a {@link Spatial.Cube} covering the pyramid's surrounding cube
     */
    @Override
    protected Spatial getNodeBounds(PyramidKey index) {
        // Decode anchor by accumulating the cubeId offset at each step.
        // At step l, the child anchor = parent anchor + offset(cubeId, childSize).
        float px = 0, py = 0, pz = 0;
        byte level = index.getLevel();
        for (int l = 1; l <= level; l++) {
            float childSize = Constants.lengthAtLevel((byte) l);
            int cubeId = index.getCoordBitsAtLevel(l);
            if ((cubeId & 1) != 0) px += childSize;
            if ((cubeId & 2) != 0) py += childSize;
            if ((cubeId & 4) != 0) pz += childSize;
        }
        // The surrounding cube has edge length = cell size at the key's level.
        float cellSize = Constants.lengthAtLevel(level);
        return new Spatial.Cube(px, py, pz, cellSize);
    }

    /**
     * The edge length of the surrounding cube at the given refinement level.
     *
     * @param level refinement level, 0..{@link PyramidKey#MAX_PYRAMID_LEVEL}
     * @return {@code Constants.lengthAtLevel(level)}
     */
    @Override
    protected float getCellSizeAtLevel(byte level) {
        return Constants.lengthAtLevel(level);
    }

    /** Defensive cap on the spanning cube-grid enumeration to avoid pathological memory blow-up. */
    private static final int MAX_SPANNING_CELLS = 1_000_000;

    /**
     * Distribute a bounded entity across the pyramid-SFC elements its bounds overlap (RDR-010, beads
     * Luciferase-7eb / Luciferase-401t), so {@code getEntitySpanCount > 1} for space-spanning bounds.
     *
     * <p><b>Element-level coverage (Luciferase-401t).</b> {@link #collectSpanningLeaves} descends the pyramid
     * SFC from both root pyramids, pruning any subtree whose surrounding cube is disjoint from {@code bounds},
     * and registers the entity in EVERY leaf (tet child, or pyramid at {@code level}) whose cube intersects —
     * not just the one element containing each cube cell's centre. This fixes the prior cube-granular
     * under-coverage (a cube can hold several pyramid/tet elements; the old centre-only registration missed
     * all but one). Cube tiling makes the prune sound: a disjoint child cube contains no intersecting leaf.
     *
     * <p><b>Conservative per element.</b> The per-leaf test is cube-vs-bounds (exact pyramid containment is
     * intrinsically cube-conservative — only 2/3 of a cube is pyramid-covered; see PyramidDomainCoverageTest).
     * Spanning may therefore <em>over</em>-cover (register a leaf whose exact shape the bounds graze), never
     * <em>under</em>-cover; the over-coverage is filtered by the exact post-check in range/kNN. Insert and
     * query are consistent (both cube-conservative for pyramid leaves) — no false negatives.
     *
     * <p><b>Fallbacks (never leaves the entity in zero nodes).</b> {@code null} bounds, {@code level == 0},
     * inverted/empty clamped ranges (bounds outside the domain), an up-front cube-grid count exceeding
     * {@link #MAX_SPANNING_CELLS}, a post-descent element count exceeding the same cap, and an empty leaf set
     * (bounds entirely in the uncovered third — no root pyramid owns them) all fall back to single-node
     * insertion at the entity position. Spanning does not trigger subdivision.
     */
    @Override
    protected void insertWithSpanning(ID entityId, EntityBounds bounds, byte level) {
        if (bounds == null || level == 0) {
            // No bounds, or level 0 (the whole domain is one cell — nothing to span, and the descent's
            // coordBits[1] would be out of range for a level-0 key). Register at the position.
            super.insertWithSpanning(entityId, bounds, level);
            return;
        }
        int cellSize = Constants.lengthAtLevel(level);
        int max = Constants.MAX_COORD;
        // Clamp the bounds to the domain and snap to the cube grid at this level.
        int minX = Math.max(0, (int) Math.floor(bounds.getMinX() / cellSize) * cellSize);
        int minY = Math.max(0, (int) Math.floor(bounds.getMinY() / cellSize) * cellSize);
        int minZ = Math.max(0, (int) Math.floor(bounds.getMinZ() / cellSize) * cellSize);
        int maxX = Math.min(max, (int) Math.floor(bounds.getMaxX() / cellSize) * cellSize);
        int maxY = Math.min(max, (int) Math.floor(bounds.getMaxY() / cellSize) * cellSize);
        int maxZ = Math.min(max, (int) Math.floor(bounds.getMaxZ() / cellSize) * cellSize);

        // Inverted/empty range after clamping ⇒ the bounds lie entirely outside the domain in some axis.
        // Fall back to single-node insertion so the entity is still registered (a negative cell count
        // would otherwise slip past the cap guard and leave the entity in zero spatial nodes — invisible).
        if (maxX < minX || maxY < minY || maxZ < minZ) {
            super.insertWithSpanning(entityId, bounds, level);
            return;
        }

        long cellsX = (long) (maxX - minX) / cellSize + 1;
        long cellsY = (long) (maxY - minY) / cellSize + 1;
        long cellsZ = (long) (maxZ - minZ) / cellSize + 1;
        if (cellsX * cellsY * cellsZ > MAX_SPANNING_CELLS) {
            log.warn("Spanning grid {}x{}x{} exceeds cap {} at level {} — falling back to single-node insert",
                     cellsX, cellsY, cellsZ, MAX_SPANNING_CELLS, level);
            super.insertWithSpanning(entityId, bounds, level);
            return;
        }

        // Element-level coverage (Luciferase-401t): descend the pyramid SFC from both roots, pruning any
        // subtree whose surrounding cube does not intersect the bounds, and collect EVERY leaf (tet child, or
        // pyramid at `level`) whose cube intersects — not just the one element containing each cube's centre.
        // The per-element test is cube-conservative (sound for spanning: it may over-cover, never under-cover;
        // over-coverage is filtered by the exact post-check in range/kNN). Entities in the uncovered third of
        // the cube (no root pyramid owns them — see PyramidDomainCoverageTest) collect no leaf and fall back to
        // single-node insertion below, preserving the established conservative contract.
        var keys = new HashSet<PyramidKey>();
        int[] coordBits = new int[level + 1];
        int[] typeBits = new int[level + 1];
        for (var root : new Pyramid[] { new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_6),
                                        new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_7) }) {
            collectSpanningLeaves(root, coordBits, typeBits, 0, level, bounds, keys);
        }

        if (keys.size() > MAX_SPANNING_CELLS) {
            // The descent can yield more leaves than cube cells (multiple elements per cube), so re-check the
            // actual element count against the cap (the up-front cube-grid estimate is only a lower bound).
            log.warn("Spanning produced {} elements exceeding cap {} at level {} — falling back to single-node "
                     + "insert", keys.size(), MAX_SPANNING_CELLS, level);
            super.insertWithSpanning(entityId, bounds, level);
            return;
        }

        if (keys.isEmpty()) {
            // Bounds lie in the uncovered third (no pyramid owner) — register conservatively at the position.
            super.insertWithSpanning(entityId, bounds, level);
            return;
        }

        for (var key : keys) {
            var node = spatialIndex.computeIfAbsent(key, k -> createNode());
            node.addEntity(entityId);
            entityManager.addEntityLocation(entityId, key);
        }
    }

    /**
     * Recursively collect the pyramid-SFC leaf keys (tet children, and pyramids at {@code targetLevel}) whose
     * surrounding cube intersects {@code bounds}, descending from {@code parent} (at {@code parentLevel}) and
     * pruning subtrees whose cube is disjoint from the bounds (Luciferase-401t). The child cube tiling means a
     * disjoint child cube contains no intersecting leaf, so pruning never drops a true overlap.
     */
    private void collectSpanningLeaves(Pyramid parent, int[] coordBits, int[] typeBits, int parentLevel,
                                       byte targetLevel, EntityBounds bounds, Set<PyramidKey> out) {
        if (out.size() > MAX_SPANNING_CELLS) {
            return; // cap exceeded — stop descending; the caller detects the overflow and falls back
        }
        int row = parent.type() - Pyramid.TYPE_6;
        int childLevel = parentLevel + 1;
        for (int i = 0; i < TetreeConnectivity.CHILDREN_PER_PYRAMID; i++) {
            var child = parent.child(i);
            if (!cubeIntersectsBounds(child, bounds)) {
                continue; // pruned: this child's cube cannot overlap the bounds
            }
            coordBits[childLevel] = TetreeConnectivity.PYRAMID_PARENT_TO_CHILD_CID[row][i];
            typeBits[childLevel] = TetreeConnectivity.PYRAMID_PARENT_TO_CHILD_TYPE[row][i];
            if (child instanceof Tet || childLevel == targetLevel) {
                // Tet children are leaves of the pyramid SFC; pyramids terminate at targetLevel.
                out.add(PyramidKey.fromLevels((byte) childLevel, coordBits, typeBits));
            } else {
                collectSpanningLeaves((Pyramid) child, coordBits, typeBits, childLevel, targetLevel, bounds, out);
            }
        }
    }

    /** True if the element's surrounding cube [origin, origin+length] intersects the bounds AABB. */
    private static boolean cubeIntersectsBounds(HybridElement e, EntityBounds b) {
        float ex = e.x(), ey = e.y(), ez = e.z();
        float h = e.length();
        return b.getMaxX() >= ex && b.getMinX() <= ex + h
               && b.getMaxY() >= ey && b.getMinY() <= ey + h
               && b.getMaxZ() >= ez && b.getMinZ() <= ez + h;
    }

    /**
     * Find all nodes in the spatial index whose bounding envelope intersects {@code bounds}.
     *
     * <p>Iterates all keys in the {@link #spatialIndex} and tests each via
     * {@link #doesNodeIntersectVolume}. This is an O(n) scan, correct for Phase C; the SFC
     * optimisation (LITMAX/BIGMIN range query) is deferred to a later phase.
     *
     * @param bounds the AABB query region
     * @return set of intersecting PyramidKeys (may be empty; never null)
     */
    @Override
    protected Set<PyramidKey> findNodesIntersectingBounds(VolumeBounds bounds) {
        var intersecting = new HashSet<PyramidKey>();
        var queryVolume = createSpatialFromBounds(bounds);
        for (var key : spatialIndex.keySet()) {
            if (doesNodeIntersectVolume(key, queryVolume)) {
                intersecting.add(key);
            }
        }
        return intersecting;
    }

    /**
     * True if the pyramid node intersects the given {@code volume}.
     *
     * <p>Uses the surrounding-cube AABB as an outer broad-phase gate (never-false-negative).
     * For tet-typed leaf nodes (type 0–5 by construction — types 6/7 are Pyramids), the broad
     * test is tightened via {@link Tet#intersects12DOP} against the volume's AABB, eliminating
     * false positives that pass the cube gate but miss the tet. For Pyramid leaf nodes (type 6/7),
     * the cube result is returned unchanged (14-DOP exact pyramid tests are pending separate work).
     *
     * <p>Invariant: never-false-negative — a node that truly intersects the volume always returns
     * {@code true} (tet ⊆ cube, so tet∩volume≠∅ ⟹ tet∩volumeAABB≠∅ AND cube broad test passes).
     *
     * @param nodeIndex the PyramidKey identifying the node
     * @param volume    the query volume
     * @return true if the node intersects {@code volume}
     */
    @Override
    protected boolean doesNodeIntersectVolume(PyramidKey nodeIndex, Spatial volume) {
        var nodeBounds = getNodeBounds(nodeIndex);
        if (!(nodeBounds instanceof Spatial.Cube cube)) {
            return false;
        }
        // Broad cube gate — never-false-negative outer guard.
        var vb = VolumeBounds.from(cube);
        boolean cubeIntersects = volume.intersects(vb.minX(), vb.minY(), vb.minZ(), vb.maxX(), vb.maxY(), vb.maxZ());
        if (!cubeIntersects) {
            return false;
        }
        // Tighten for tet-typed leaves (type 0–5). Types 6/7 are Pyramids — keep cube result.
        // INVARIANT: must never call contains12DOP/intersects12DOP on a Tet of type 6 or 7.
        var el = elementFromKey(nodeIndex);
        if (el == null) {
            // Key did not round-trip through elementFromKey (non-SFC key or decode failure).
            // Fall through to cube result (conservative — never a false negative).
            log.debug("doesNodeIntersectVolume: elementFromKey returned null for key {}; using cube-AABB fallback",
                      nodeIndex);
            return true;
        }
        if (el instanceof Tet t) {
            // Tet leaf: use exact 12-DOP AABB-vs-tet test against the volume's AABB.
            var volumeBounds = getVolumeBounds(volume);
            if (volumeBounds == null) {
                return true; // fall back to cube result (already true)
            }
            return t.intersects12DOP(volumeBounds.minX(), volumeBounds.minY(), volumeBounds.minZ(),
                                     volumeBounds.maxX(), volumeBounds.maxY(), volumeBounds.maxZ());
        }
        // Pyramid leaf (type 6/7): keep the conservative surrounding-cube result. This is REQUIRED, not a
        // pending optimization (Luciferase-2lo3 / yye5): the two root pyramids (types 6/7 — the only dpyramid
        // types) tile only 2/3 of the cube (a cube needs three Yangma pyramids), so points in the uncovered
        // third are filed into a pyramid leaf by cube-AABB, not by exact shape. An exact pyramid test would
        // then return a false negative for them. The conservative cube result never misses. See
        // PyramidDomainCoverageTest, which pins the 2/3 coverage.
        return true;
    }

    /**
     * True if the pyramid node is fully contained within {@code volume}.
     *
     * <p>For tet-typed leaf nodes (type 0–5), containment is tested by checking all 4 tet
     * vertices against the volume's AABB (the tet is convex, so this is necessary and sufficient
     * for AABB query volumes). This is strictly tighter than testing all 8 cube corners for
     * non-AABB volumes (Sphere etc.) whose AABB proxy is smaller than the surrounding cube.
     * For pure AABB volumes the two tests are equivalent because the 4 tet vertices span the full
     * cube AABB (v0 = anchor, v3 = opposite cube corner). For Pyramid leaf nodes (type 6/7), the
     * conservative 8-cube-corner test is retained (exact 14-DOP pyramid work is pending).
     *
     * <p><b>Proxy note</b>: for non-AABB volumes (e.g. {@link Spatial.Sphere}), containment is
     * tested against the volume's AABB via {@link #getVolumeBounds(Spatial)}, consistent with
     * the pre-existing conservative approximation for all shape types.
     *
     * @param nodeIndex the PyramidKey identifying the node
     * @param volume    the query volume
     * @return true if the node is fully contained in {@code volume}
     */
    @Override
    protected boolean isNodeContainedInVolume(PyramidKey nodeIndex, Spatial volume) {
        var nodeBounds = getNodeBounds(nodeIndex);
        if (!(nodeBounds instanceof Spatial.Cube cube)) {
            return false;
        }
        var vb = getVolumeBounds(volume);
        if (vb == null) {
            return false;
        }
        // Tighten for tet-typed leaves (type 0–5). Types 6/7 are Pyramids — use cube corners.
        // INVARIANT: must never call contains12DOP/intersects12DOP on a Tet of type 6 or 7.
        var el = elementFromKey(nodeIndex);
        if (el == null) {
            // Key did not round-trip through elementFromKey (non-SFC key or decode failure).
            // Fall through to cube-corner containment (conservative — may return false negative for a
            // valid tet whose key cannot be decoded, but avoids incorrect true for invalid keys).
            log.debug("isNodeContainedInVolume: elementFromKey returned null for key {}; using cube-corner fallback",
                      nodeIndex);
        }
        if (el instanceof Tet t) {
            // Tet leaf: all 4 tet vertices must be inside the volume's AABB (tet is convex).
            for (var vertex : t.coordinates()) {
                if (vertex.x < vb.minX() || vertex.x > vb.maxX()
                    || vertex.y < vb.minY() || vertex.y > vb.maxY()
                    || vertex.z < vb.minZ() || vertex.z > vb.maxZ()) {
                    return false;
                }
            }
            return true;
        }
        // Pyramid leaf (type 6/7): all 8 cube corners must be inside the volume's AABB. Conservative by
        // necessity, not pending work (Luciferase-2lo3 / yye5): the type-6/7 root pyramids tile only 2/3 of
        // the cube, so points in the uncovered third are filed into a pyramid leaf by cube, not exact shape;
        // an exact containment test would drop them. See PyramidDomainCoverageTest.
        float minX = cube.originX();
        float minY = cube.originY();
        float minZ = cube.originZ();
        float maxX = minX + cube.extent();
        float maxY = minY + cube.extent();
        float maxZ = minZ + cube.extent();
        return minX >= vb.minX() && minY >= vb.minY() && minZ >= vb.minZ()
               && maxX <= vb.maxX() && maxY <= vb.maxY() && maxZ <= vb.maxZ();
    }

    // ===== Abstract geometry methods — Phase-D ray/plane traversal cluster =====

    // Möller-Trumbore epsilon (matches TetrahedralGeometry.EPSILON)
    private static final float MT_EPSILON = 1e-6f;

    /**
     * Test whether {@code ray} intersects the pyramid node identified by {@code nodeIndex}.
     *
     * <p>The pyramid has 5 faces: f0–f3 are triangular, f4 is the quadrilateral base.
     * <ul>
     *   <li>f0–f3: tested individually via Möller-Trumbore ray-triangle intersection.</li>
     *   <li>f4 (quad base): split along the <em>fixed diagonal c[0]→c[3]</em> into two triangles
     *       (c[0],c[1],c[3]) and (c[0],c[2],c[3]).  This split is consistent with
     *       {@link Pyramid#coordinates()} corner ordering for both TYPE-6 and TYPE-7:
     *       c[0] is the (low-x,low-y) base corner and c[3] is the (high-x,high-y) base corner
     *       in both types, so the diagonal is geometrically well-defined and cannot leave a gap
     *       or produce an overlap between the two triangles.</li>
     * </ul>
     *
     * <p>A naïve single-triangle test for f4 would miss a ray entering through the half of the
     * quad not covered by that triangle (the known-hard f4 case from the bead spec).
     *
     * @param nodeIndex the PyramidKey of the node to test
     * @param ray       the query ray
     * @return {@code true} if the ray intersects any face of the pyramid
     */
    @Override
    protected boolean doesRayIntersectNode(PyramidKey nodeIndex, Ray3D ray) {
        var pyramid = pyramidFromKey(nodeIndex);
        if (pyramid == null) {
            return false;
        }
        Point3i[] c = pyramid.coordinates();
        // Convert to float for MT arithmetic
        var v0 = new Point3f(c[0].x, c[0].y, c[0].z);
        var v1 = new Point3f(c[1].x, c[1].y, c[1].z);
        var v2 = new Point3f(c[2].x, c[2].y, c[2].z);
        var v3 = new Point3f(c[3].x, c[3].y, c[3].z);
        var v4 = new Point3f(c[4].x, c[4].y, c[4].z); // apex

        // 4 triangular faces (f0–f3). For a TYPE-6 pyramid with base c[0..3] and apex c[4]:
        //   f0: c[0], c[1], c[4]
        //   f1: c[1], c[3], c[4]
        //   f2: c[3], c[2], c[4]
        //   f3: c[2], c[0], c[4]
        if (rayTriangleIntersects(ray, v0, v1, v4)) return true;
        if (rayTriangleIntersects(ray, v1, v3, v4)) return true;
        if (rayTriangleIntersects(ray, v3, v2, v4)) return true;
        if (rayTriangleIntersects(ray, v2, v0, v4)) return true;

        // f4: quad base split along fixed diagonal c[0]→c[3].
        // Triangle 1: (c[0], c[1], c[3])
        // Triangle 2: (c[0], c[2], c[3])
        // Diagonal c[0]→c[3] is the same geometric seam for both TYPE-6 and TYPE-7.
        if (rayTriangleIntersects(ray, v0, v1, v3)) return true;
        if (rayTriangleIntersects(ray, v0, v2, v3)) return true;

        return false;
    }

    /**
     * Return the entry parameter {@code t} along {@code ray} at which the ray first enters
     * the pyramid node, or {@code Float.MAX_VALUE} if the ray does not intersect.
     *
     * <p>Scans all 6 triangle faces (including both f4 triangles from the fixed-diagonal split),
     * and returns the minimum positive {@code t} among face hits.
     *
     * @param nodeIndex the PyramidKey of the node
     * @param ray       the query ray
     * @return minimum entry {@code t}, or {@link Float#MAX_VALUE} if no intersection
     */
    @Override
    protected float getRayNodeIntersectionDistance(PyramidKey nodeIndex, Ray3D ray) {
        var pyramid = pyramidFromKey(nodeIndex);
        if (pyramid == null) {
            return Float.MAX_VALUE;
        }
        Point3i[] c = pyramid.coordinates();
        var v0 = new Point3f(c[0].x, c[0].y, c[0].z);
        var v1 = new Point3f(c[1].x, c[1].y, c[1].z);
        var v2 = new Point3f(c[2].x, c[2].y, c[2].z);
        var v3 = new Point3f(c[3].x, c[3].y, c[3].z);
        var v4 = new Point3f(c[4].x, c[4].y, c[4].z);

        float minT = Float.MAX_VALUE;
        float t;
        t = rayTriangleT(ray, v0, v1, v4); if (t >= 0 && t < minT) minT = t;
        t = rayTriangleT(ray, v1, v3, v4); if (t >= 0 && t < minT) minT = t;
        t = rayTriangleT(ray, v3, v2, v4); if (t >= 0 && t < minT) minT = t;
        t = rayTriangleT(ray, v2, v0, v4); if (t >= 0 && t < minT) minT = t;
        // f4 quad base — fixed diagonal c[0]→c[3]
        t = rayTriangleT(ray, v0, v1, v3); if (t >= 0 && t < minT) minT = t;
        t = rayTriangleT(ray, v0, v2, v3); if (t >= 0 && t < minT) minT = t;

        return minT;
    }

    /**
     * Stream all non-empty pyramid nodes in the spatial index ordered by ascending ray-entry
     * parameter (front-to-back). Only nodes that the ray actually intersects are included.
     *
     * <p>Implementation mirrors {@code Octree.getRayTraversalOrder}: iterate all populated nodes,
     * test ray intersection, record entry distance, sort ascending. SFC ordering (PyramidKey natural
     * order) is used as a stable tiebreak.
     *
     * @param ray the query ray
     * @return stream of {@link PyramidKey} values ordered by entry distance
     */
    @Override
    protected Stream<PyramidKey> getRayTraversalOrder(Ray3D ray) {
        record NodeDist(PyramidKey key, float dist) implements Comparable<NodeDist> {
            @Override
            public int compareTo(NodeDist o) {
                int c = Float.compare(dist, o.dist);
                return c != 0 ? c : key.compareTo(o.key);
            }
        }
        var entries = new ArrayList<NodeDist>();
        for (var entry : spatialIndex.entrySet()) {
            var node = entry.getValue();
            if (node == null || node.isEmpty()) continue;
            var key = entry.getKey();
            if (doesRayIntersectNode(key, ray)) {
                float dist = getRayNodeIntersectionDistance(key, ray);
                if (dist <= ray.maxDistance()) {
                    entries.add(new NodeDist(key, dist));
                }
            }
        }
        Collections.sort(entries);
        return entries.stream().map(NodeDist::key);
    }

    /**
     * Test whether the given {@code plane} intersects the pyramid node identified by {@code nodeIndex}.
     *
     * <p>Classifies all 5 vertices of the pyramid against the plane using the signed-distance formula
     * {@code a*x + b*y + c*z + d}. If there exist at least one vertex with strictly positive distance
     * and at least one with strictly negative distance, the plane intersects the pyramid interior.
     * Vertices exactly on the plane (signed distance within {@link #MT_EPSILON}) are treated as
     * coplanar and do not by themselves constitute a mixed-sign pair; they do not prevent detection
     * when the remaining vertices are split.
     *
     * <p>This is the 5-vertex extension of the standard 4-vertex tet approach used by
     * {@code TetrahedralGeometry.planeIntersectsTetrahedron}.
     *
     * @param nodeIndex the PyramidKey of the node to test
     * @param plane     the query plane
     * @return {@code true} if the plane cuts through the pyramid interior
     */
    @Override
    protected boolean doesPlaneIntersectNode(PyramidKey nodeIndex, Plane3D plane) {
        var pyramid = pyramidFromKey(nodeIndex);
        if (pyramid == null) {
            return false;
        }
        Point3i[] c = pyramid.coordinates();
        boolean hasPositive = false;
        boolean hasNegative = false;
        for (var vi : c) {
            float dist = plane.distanceToPoint(new Point3f(vi.x, vi.y, vi.z));
            if (dist > MT_EPSILON) {
                hasPositive = true;
            } else if (dist < -MT_EPSILON) {
                hasNegative = true;
            }
            if (hasPositive && hasNegative) return true; // early exit
        }
        return false;
    }

    /**
     * Stream all non-empty pyramid nodes in the spatial index in front-to-back order relative to
     * {@code plane}, ordered by ascending absolute signed-distance from node centroid to the plane.
     *
     * <p>Mirrors {@code Octree.getPlaneTraversalOrder} and {@code Tetree.getPlaneTraversalOrder}:
     * all populated nodes are streamed, sorted by {@code |plane.distanceToPoint(centroid)|}.
     * Nodes straddling the plane (smallest absolute distance) come first.
     *
     * @param plane the query plane
     * @return stream of {@link PyramidKey} values ordered by ascending |plane-signed-distance|
     */
    @Override
    protected Stream<PyramidKey> getPlaneTraversalOrder(Plane3D plane) {
        return spatialIndex.entrySet().stream().filter(e -> {
            var node = e.getValue();
            return node != null && !node.isEmpty();
        }).sorted((e1, e2) -> {
            float d1 = Math.abs(planeNodeDistance(e1.getKey(), plane));
            float d2 = Math.abs(planeNodeDistance(e2.getKey(), plane));
            return Float.compare(d1, d2);
        }).map(java.util.Map.Entry::getKey);
    }

    // ===== Phase-D private helpers =====

    /**
     * Reconstruct the {@link Pyramid} element for a given {@code key} by descending the
     * pyramid tree from the root following the coordinate/type bits encoded in the key.
     *
     * <p><b>Tet-child keys:</b> for a <em>level-1</em> tet-child key this returns {@code null}
     * (no pyramid at that key). For a <em>deeper</em> (level &gt; 1) tet-child key it returns the
     * nearest enclosing parent pyramid (a strictly larger bounding volume) rather than null — a
     * conservative over-approximation. Ray/plane callers therefore may report a false-positive
     * intersection for deep tet keys, never a false negative. This is safe for Phase D (the
     * pyramid bound encloses the tet); exact tet-geometry ray/plane tests are deferred to Phase E.
     *
     * <p><b>Tet-leaf callers:</b> when the <em>actual</em> leaf element is needed (e.g. to call
     * cross-shape navigation on a tet leaf), use {@link #elementFromKey(PyramidKey)} instead — it
     * returns the tet itself rather than this over-approximating enclosing pyramid.
     *
     * @see #elementFromKey(PyramidKey)
     */
    static Pyramid pyramidFromKey(PyramidKey key) {
        // Shared descent (RDR-010 Luciferase-3y1): delegate to PyramidKeyDecoder so this and
        // PyramidSubdivisionStrategy.pyramidFromKey cannot silently diverge.
        return PyramidKeyDecoder.pyramidFromKey(key);
    }

    /**
     * Decode {@code key} to its actual leaf {@link HybridElement} — a {@link Pyramid} for a pyramid
     * key, a {@link Tet} for a <em>shallowest</em> tet-leaf key (RDR-010 pi1.5 Phase A, bead
     * Luciferase-uqik). Unlike {@link #pyramidFromKey(PyramidKey)} (which over-approximates a tet-leaf
     * key to its enclosing parent pyramid), this returns the tet itself, so it is the validating
     * inverse of {@link PyramidKeyCodec#encode(Tet)}.
     *
     * <p><b>Full depth.</b> The descent follows {@link Pyramid#child(int)} edges while the path is
     * pyramidal and {@link Tet#child(int)} edges once it enters the tetrahedral branch, so it
     * reconstructs both a shallowest tet leaf ({@code minTetLevel == level}) and a <em>deep</em>
     * pyramid-rooted tet ({@code l > minTetLevel}) — the tet-of-tet refinement below the boundary
     * (RDR-010 Luciferase-cjwr Phase B). A key with no matching child at some level returns {@code null}.
     *
     * @param key the SFC key
     * @return the leaf element (Pyramid or Tet), or {@code null} for a non-reconstructible key
     */
    static HybridElement elementFromKey(PyramidKey key) {
        byte level = key.getLevel();
        if (level == 0) {
            return new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_6); // virtual root cover
        }
        // Level-1 child of one of the two roots.
        int coordBits1 = key.getCoordBitsAtLevel(1);
        int typeBits1 = key.getTypeAtLevel(1);
        HybridElement currentEl = null;
        outer:
        for (var root : new Pyramid[] { new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_6),
                                        new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_7) }) {
            int row = root.type() - Pyramid.TYPE_6;
            for (int i = 0; i < TetreeConnectivity.CHILDREN_PER_PYRAMID; i++) {
                if (TetreeConnectivity.PYRAMID_PARENT_TO_CHILD_CID[row][i] == coordBits1
                    && TetreeConnectivity.PYRAMID_PARENT_TO_CHILD_TYPE[row][i] == typeBits1) {
                    currentEl = root.child(i);
                    break outer;
                }
            }
        }
        if (currentEl == null || level == 1) {
            return currentEl; // level-1 leaf (Tet or Pyramid), or no match
        }
        // Descend levels 2..level. While the path is pyramidal, follow Pyramid.child edges (matching the
        // child's coord+type bits). Once it enters the tetrahedral branch, follow Tet.child edges (the
        // deep tet-of-tet refinement, RDR-010 Luciferase-cjwr Phase B) — Tet.child inherits minTetLevel,
        // so the reconstructed deep tet carries the boundary level set at the shallowest tet.
        for (int l = 2; l <= level; l++) {
            int cb = key.getCoordBitsAtLevel(l);
            int tb = key.getTypeAtLevel(l);
            HybridElement next = null;
            if (currentEl instanceof Pyramid cp) {
                int row = cp.type() - Pyramid.TYPE_6;
                for (int i = 0; i < TetreeConnectivity.CHILDREN_PER_PYRAMID; i++) {
                    if (TetreeConnectivity.PYRAMID_PARENT_TO_CHILD_CID[row][i] == cb
                        && TetreeConnectivity.PYRAMID_PARENT_TO_CHILD_TYPE[row][i] == tb) {
                        next = cp.child(i);
                        break;
                    }
                }
            } else {
                // Deep tet-of-tet: (parent type, child cube-id) uniquely identifies the Bey child
                // (TYPE_CID_TO_BEYID rows are permutations), so match on cube-id; type bits cross-check.
                var ct = (Tet) currentEl;
                int h = Constants.lengthAtLevel((byte) l);
                for (int i = 0; i < TetreeConnectivity.CHILDREN_PER_TET; i++) {
                    var c = ct.child(i);
                    int childCb = ((c.x() & h) != 0 ? 1 : 0) | ((c.y() & h) != 0 ? 2 : 0)
                                  | ((c.z() & h) != 0 ? 4 : 0);
                    if (childCb == cb && c.type() == tb) {
                        next = c;
                        break;
                    }
                }
            }
            if (next == null) {
                return null; // no matching child: not an SFC element
            }
            currentEl = next;
        }
        return currentEl;
    }

    /**
     * Möller-Trumbore ray-triangle intersection: returns {@code true} if the ray hits the
     * triangle (v0, v1, v2) within (MT_EPSILON, ray.maxDistance()].
     */
    private static boolean rayTriangleIntersects(Ray3D ray, Point3f v0, Point3f v1, Point3f v2) {
        return rayTriangleT(ray, v0, v1, v2) >= 0f;
    }

    /**
     * Möller-Trumbore ray-triangle intersection: returns the parameter {@code t} at which the ray
     * hits the triangle (v0, v1, v2), or {@code -1} if there is no intersection within
     * (MT_EPSILON, ray.maxDistance()].
     */
    private static float rayTriangleT(Ray3D ray, Point3f v0, Point3f v1, Point3f v2) {
        float e1x = v1.x - v0.x, e1y = v1.y - v0.y, e1z = v1.z - v0.z;
        float e2x = v2.x - v0.x, e2y = v2.y - v0.y, e2z = v2.z - v0.z;

        // h = direction × edge2
        float hx = ray.direction().y * e2z - ray.direction().z * e2y;
        float hy = ray.direction().z * e2x - ray.direction().x * e2z;
        float hz = ray.direction().x * e2y - ray.direction().y * e2x;

        float a = e1x * hx + e1y * hy + e1z * hz;
        if (a > -MT_EPSILON && a < MT_EPSILON) return -1f; // parallel

        float f = 1.0f / a;
        float sx = ray.origin().x - v0.x, sy = ray.origin().y - v0.y, sz = ray.origin().z - v0.z;
        float u = f * (sx * hx + sy * hy + sz * hz);
        if (u < 0f || u > 1f) return -1f;

        // q = s × edge1
        float qx = sy * e1z - sz * e1y;
        float qy = sz * e1x - sx * e1z;
        float qz = sx * e1y - sy * e1x;
        float v = f * (ray.direction().x * qx + ray.direction().y * qy + ray.direction().z * qz);
        if (v < 0f || u + v > 1f) return -1f;

        float t = f * (e2x * qx + e2y * qy + e2z * qz);
        if (t > MT_EPSILON && t <= ray.maxDistance()) return t;
        return -1f;
    }

    /**
     * Signed distance from the centroid of the surrounding cube of {@code key} to {@code plane}.
     * Used by {@link #getPlaneTraversalOrder}.
     */
    private float planeNodeDistance(PyramidKey key, Plane3D plane) {
        float px = 0, py = 0, pz = 0;
        byte level = key.getLevel();
        for (int l = 1; l <= level; l++) {
            float childSize = Constants.lengthAtLevel((byte) l);
            int cubeId = key.getCoordBitsAtLevel(l);
            if ((cubeId & 1) != 0) px += childSize;
            if ((cubeId & 2) != 0) py += childSize;
            if ((cubeId & 4) != 0) pz += childSize;
        }
        float half = Constants.lengthAtLevel(level) / 2f;
        return plane.distanceToPoint(new Point3f(px + half, py + half, pz + half));
    }

    // ===== Abstract geometry methods — Phase-E frustum/knn/collision/neighbor cluster =====

    /**
     * Test whether a frustum intersects the pyramid at {@code nodeIndex}.
     *
     * <p>Uses the standard convex-hull separating-plane test: if all five pyramid vertices
     * lie on the outside (negative-distance) half of any single frustum plane, the pyramid
     * and frustum are separated on that axis, so there is no intersection. Because the
     * pyramid is convex, this sufficient (no false negatives).
     */
    @Override
    protected boolean doesFrustumIntersectNode(PyramidKey nodeIndex, Frustum3D frustum) {
        var pyramid = pyramidFromKey(nodeIndex);
        if (pyramid == null) {
            return false;
        }
        var vertices = pyramid.coordinates();
        for (var plane : frustum.getPlanes()) {
            // If ALL vertices are on the outside (negative distance) of this plane, no intersection.
            boolean allOutside = true;
            for (var v : vertices) {
                if (plane.distanceToPoint(new Point3f(v.x, v.y, v.z)) >= 0) {
                    allOutside = false;
                    break;
                }
            }
            if (allOutside) {
                return false;
            }
        }
        return true;
    }

    /**
     * Return a stream of populated PyramidKeys ordered by ascending centroid-to-camera distance.
     * Mirrors Octree's getFrustumTraversalOrder (distance-sorted stream of non-empty nodes).
     */
    @Override
    protected Stream<PyramidKey> getFrustumTraversalOrder(Frustum3D frustum, Point3f cameraPosition) {
        return spatialIndex.keySet().stream()
                           .filter(k -> {
                               var node = spatialIndex.get(k);
                               return node != null && !node.isEmpty();
                           })
                           .sorted((k1, k2) -> {
                               float d1 = estimateNodeDistance(k1, cameraPosition);
                               float d2 = estimateNodeDistance(k2, cameraPosition);
                               return Float.compare(d1, d2);
                           });
    }

    /**
     * Estimate the distance from a query point to the pyramid node using the pyramid's vertex centroid.
     * This is a coarse distance measure used for traversal ordering; see {@link #shouldContinueKNNSearch}
     * for the provably-correct lower bound used in kNN pruning.
     */
    @Override
    protected float estimateNodeDistance(PyramidKey nodeIndex, Point3f queryPoint) {
        var pyramid = pyramidFromKey(nodeIndex);
        if (pyramid == null) {
            return Float.MAX_VALUE;
        }
        return pyramid.centroid().distance(queryPoint);
    }

    /**
     * Determine whether the kNN search should continue into the node at {@code nodeIndex}.
     *
     * <p><b>Correctness-critical lower bound</b>: we use the bounding-sphere lower bound
     * <pre>
     *   lowerBound = max(0, centroidDistance − maxVertexRadius)
     * </pre>
     * where {@code centroidDistance} = distance from {@code queryPoint} to the pyramid's
     * vertex centroid, and {@code maxVertexRadius} = maximum distance from the centroid to
     * any of the 5 pyramid vertices. The bounding sphere centred at the centroid with radius
     * {@code maxVertexRadius} encloses the entire pyramid; therefore any point inside the
     * pyramid is within {@code maxVertexRadius} of the centroid. Consequently
     * {@code lowerBound ≤ trueClosestPointDistance}, so kNN never prunes a real neighbor.
     *
     * <p>A naive centroid distance alone is NOT a valid lower bound: when the query point
     * lies near a vertex that is far from the centroid, the centroid distance exceeds the
     * true closest-point distance (= 0 at a vertex).
     */
    @Override
    protected boolean shouldContinueKNNSearch(PyramidKey nodeIndex, Point3f queryPoint,
                                              PriorityQueue<EntityDistance<ID>> candidates) {
        if (candidates.isEmpty()) {
            return true;
        }
        var furthest = candidates.peek();
        if (furthest == null) {
            return true;
        }

        var pyramid = pyramidFromKey(nodeIndex);
        if (pyramid == null) {
            return true; // Cannot prune — be conservative
        }

        var centroid = pyramid.centroid();
        float centroidDistance = centroid.distance(queryPoint);

        // Compute maxVertexRadius: max distance from centroid to any vertex
        float maxVertexRadius = 0f;
        for (var v : pyramid.coordinates()) {
            float r = centroid.distance(new Point3f(v.x, v.y, v.z));
            if (r > maxVertexRadius) {
                maxVertexRadius = r;
            }
        }

        // Bounding-sphere lower bound on true closest-point distance
        float lowerBound = Math.max(0f, centroidDistance - maxVertexRadius);

        // Continue searching if the lower bound does not exceed the furthest candidate's distance
        return lowerBound <= furthest.distance();
    }

    /**
     * Return the default subdivision strategy: entity-count threshold + {@code Pyramid.child(0..9)}
     * descent (mirroring Octree's {@code OctreeSubdivisionStrategy.balanced()}).
     *
     * <p>This method is called from the {@code AbstractSpatialIndex} super-constructor; it must
     * not throw and must return a non-null value at construction time (before any entity insertion).
     */
    @Override
    protected SubdivisionStrategy<PyramidKey, ID, Content> createDefaultSubdivisionStrategy() {
        return PyramidSubdivisionStrategy.balanced();
    }

    /**
     * Emit at least the SFC-adjacent same-level PyramidKeys into {@code toVisit}.
     *
     * <p><b>Pi1.5 Phase C (Luciferase-azwr) — cross-shape graduation.</b> Emits the sibling pyramid
     * children of the parent (the SFC-adjacent same-level nodes) <em>unioned</em> with the full
     * cross-shape face-neighbour set from the wired {@link PyramidNeighborDetector}. Because the detector
     * now resolves faces by element navigation, this union includes the four triangular tet faces
     * (pyramid→tet) as well as the quad base (f4, pyramid↔pyramid) — cross-parent neighbours the sibling
     * walk alone misses. A <em>tet-leaf</em> {@code nodeIndex} (first-class since pi1.5) likewise emits
     * its cross-shape face neighbours here.
     *
     * <p><b>Tet-sibling bound (documented, not silent).</b> The sibling walk below enqueues only the
     * <em>Pyramid</em> children of the enclosing parent; non-face-adjacent tet siblings are intentionally
     * not enqueued. BFS connectivity (kNN / range / collision) traverses face-adjacent cells, and every
     * face-adjacent neighbour — including cross-shape tets — is emitted via {@code findFaceNeighbors}, so
     * the omission cannot disconnect the BFS. Exhaustive cross-shape edge/vertex adjacency is a registered
     * deferral (bead Luciferase-0utt).
     *
     * <p>Occupancy-blind: the detector emits geometric neighbours regardless of index occupancy; BFS
     * callers null-check the node map and skip absent keys. Do NOT add occupancy filtering here.
     */
    @Override
    protected void addNeighboringNodes(PyramidKey nodeIndex, Queue<PyramidKey> toVisit,
                                       Set<PyramidKey> visitedNodes) {
        byte level = nodeIndex.getLevel();

        // Cross-shape face neighbours from the wired detector: the f4 quad base (pyramid↔pyramid) plus
        // the four triangular tet faces (pyramid→tet), all cross-parent neighbours the sibling walk
        // below cannot reach. Empty for the root; for a tet-leaf node this is its cross-shape face set.
        // The detector emits geometric neighbours regardless of index occupancy; the BFS callers
        // null-check the node map and skip absent keys (see KnnSearcher / CollisionEngine). Do NOT
        // add occupancy filtering here — it would break BFS connectivity through empty cells.
        var detector = getNeighborDetector();
        if (detector != null) {
            for (var faceKey : detector.findFaceNeighbors(nodeIndex)) {
                if (!visitedNodes.contains(faceKey) && !faceKey.equals(nodeIndex)) {
                    toVisit.add(faceKey);
                }
            }
        }

        if (level == 0) {
            // Root: emit the two level-1 pyramid roots (type-6 and type-7 children of each root)
            var roots = new Pyramid[]{ new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_6),
                                       new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_7) };
            for (var root : roots) {
                int row = root.type() - Pyramid.TYPE_6;
                for (int i = 0; i < TetreeConnectivity.CHILDREN_PER_PYRAMID; i++) {
                    var child = root.child(i);
                    if (!(child instanceof Pyramid)) continue;
                    int cb = TetreeConnectivity.PYRAMID_PARENT_TO_CHILD_CID[row][i];
                    int tb = TetreeConnectivity.PYRAMID_PARENT_TO_CHILD_TYPE[row][i];
                    var childKey = PyramidKey.fromLevels((byte) 1, new int[]{ 0, cb }, new int[]{ 0, tb });
                    if (!visitedNodes.contains(childKey)) {
                        toVisit.add(childKey);
                    }
                }
            }
            return;
        }

        // For level ≥ 1: emit the siblings (other pyramid children of the same parent).
        // A sibling shares the same parent key (== nodeIndex with the last level stripped).
        var parentKey = nodeIndex.parent();
        if (parentKey == null) {
            return;
        }
        var parentPyramid = pyramidFromKey(parentKey);
        if (parentPyramid == null) {
            return;
        }

        int row = parentPyramid.type() - Pyramid.TYPE_6;
        for (int i = 0; i < TetreeConnectivity.CHILDREN_PER_PYRAMID; i++) {
            var child = parentPyramid.child(i);
            if (!(child instanceof Pyramid)) continue;
            int cb = TetreeConnectivity.PYRAMID_PARENT_TO_CHILD_CID[row][i];
            int tb = TetreeConnectivity.PYRAMID_PARENT_TO_CHILD_TYPE[row][i];
            var siblingKey = appendBitsToKey(parentKey, (byte) (level - 1), cb, tb);
            if (!visitedNodes.contains(siblingKey) && !siblingKey.equals(nodeIndex)) {
                toVisit.add(siblingKey);
            }
        }
    }

    // ===== Phase-E private helpers =====

    /**
     * Append one child level's coord/type bits to a parent key, producing a child key at
     * {@code parentLevel + 1}.
     */
    private static PyramidKey appendBitsToKey(PyramidKey parent, byte parentLevel, int coordBits, int typeBits) {
        byte childLevel = (byte) (parentLevel + 1);
        int[] cbArr = new int[childLevel + 1];
        int[] tbArr = new int[childLevel + 1];
        for (int l = 1; l <= parentLevel; l++) {
            cbArr[l] = parent.getCoordBitsAtLevel(l);
            tbArr[l] = parent.getTypeAtLevel(l);
        }
        cbArr[childLevel] = coordBits;
        tbArr[childLevel] = typeBits;
        return PyramidKey.fromLevels(childLevel, cbArr, tbArr);
    }
}
