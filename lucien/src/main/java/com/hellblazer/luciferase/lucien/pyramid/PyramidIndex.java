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
import javax.vecmath.Tuple3i;
import java.util.HashSet;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Pyramid-based spatial index (RDR-010 pi1.3, Phase A skeleton). Extends {@link AbstractSpatialIndex} with
 * {@link PyramidKey} as the SFC key type, mirroring the {@link com.hellblazer.luciferase.lucien.octree.Octree}
 * collaborator initialisation order.
 *
 * <p>Phase A contract: all 17 abstract geometry methods throw {@link UnsupportedOperationException}
 * with a phase-bead routing message. Geometry is implemented in subsequent phases (B–E).
 *
 * @param <ID>      entity-ID type
 * @param <Content> content type
 * @author hal.hildebrand
 */
public class PyramidIndex<ID extends EntityID, Content> extends AbstractSpatialIndex<PyramidKey, ID, Content> {

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
     * wired (mirroring {@code Octree}); see {@link PyramidNeighborDetector} — a Phase-A stub that fails
     * loud against {@code Luciferase-pi1.4}.
     */
    public PyramidIndex(EntityIDGenerator<ID> idGenerator, int maxEntitiesPerNode, byte maxDepth,
                        EntitySpanningPolicy spanningPolicy) {
        super(idGenerator, maxEntitiesPerNode, maxDepth, spanningPolicy);
        setNeighborDetector(new PyramidNeighborDetector(this));
    }

    // ===== TreeBalancer =====
    // createTreeBalancer() is inherited from AbstractSpatialIndex; default returns DefaultTreeBalancer.
    // No pyramid-specific balancer in Phase A.

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
     * True if the pyramid node's surrounding-cube AABB intersects the given {@code volume}.
     *
     * <p>For point-volumes (a {@link Spatial.Sphere} at a single point or a degenerate volume),
     * containment is tested via the AABB. For all other volumes, standard AABB-vs-AABB
     * intersection is used (mirroring Octree / Tetree behaviour).
     *
     * @param nodeIndex the PyramidKey identifying the node
     * @param volume    the query volume
     * @return true if the node's AABB intersects {@code volume}
     */
    @Override
    protected boolean doesNodeIntersectVolume(PyramidKey nodeIndex, Spatial volume) {
        var nodeBounds = getNodeBounds(nodeIndex);
        if (nodeBounds instanceof Spatial.Cube cube) {
            var vb = VolumeBounds.from(cube);
            return volume.intersects(vb.minX(), vb.minY(), vb.minZ(), vb.maxX(), vb.maxY(), vb.maxZ());
        }
        return false;
    }

    /**
     * True if the pyramid node's surrounding-cube AABB is fully contained within {@code volume}.
     *
     * <p>Uses the same conservative AABB model as Octree: the cube-AABB must fit inside the
     * volume. This may over-report "not contained" for volumes that contain the pyramid geometry
     * but not its surrounding cube.
     *
     * @param nodeIndex the PyramidKey identifying the node
     * @param volume    the query volume
     * @return true if the node's AABB is fully contained in {@code volume}
     */
    @Override
    protected boolean isNodeContainedInVolume(PyramidKey nodeIndex, Spatial volume) {
        var nodeBounds = getNodeBounds(nodeIndex);
        if (nodeBounds instanceof Spatial.Cube cube) {
            float minX = cube.originX();
            float minY = cube.originY();
            float minZ = cube.originZ();
            float maxX = minX + cube.extent();
            float maxY = minY + cube.extent();
            float maxZ = minZ + cube.extent();
            // All 8 corners of the cube must be inside the volume's AABB.
            var vb = getVolumeBounds(volume);
            if (vb == null) return false;
            return minX >= vb.minX() && minY >= vb.minY() && minZ >= vb.minZ()
                   && maxX <= vb.maxX() && maxY <= vb.maxY() && maxZ <= vb.maxZ();
        }
        return false;
    }

    // ===== Abstract geometry methods — Phase-D ray/plane traversal cluster =====

    @Override
    protected boolean doesRayIntersectNode(PyramidKey nodeIndex, Ray3D ray) {
        throw new UnsupportedOperationException(
        "RDR-010 pi1.3 Phase D: doesRayIntersectNode — bead Luciferase-jm6");
    }

    @Override
    protected float getRayNodeIntersectionDistance(PyramidKey nodeIndex, Ray3D ray) {
        throw new UnsupportedOperationException(
        "RDR-010 pi1.3 Phase D: getRayNodeIntersectionDistance — bead Luciferase-jm6");
    }

    @Override
    protected Stream<PyramidKey> getRayTraversalOrder(Ray3D ray) {
        throw new UnsupportedOperationException(
        "RDR-010 pi1.3 Phase D: getRayTraversalOrder — bead Luciferase-jm6");
    }

    @Override
    protected boolean doesPlaneIntersectNode(PyramidKey nodeIndex, Plane3D plane) {
        throw new UnsupportedOperationException(
        "RDR-010 pi1.3 Phase D: doesPlaneIntersectNode — bead Luciferase-jm6");
    }

    @Override
    protected Stream<PyramidKey> getPlaneTraversalOrder(Plane3D plane) {
        throw new UnsupportedOperationException(
        "RDR-010 pi1.3 Phase D: getPlaneTraversalOrder — bead Luciferase-jm6");
    }

    // ===== Abstract geometry methods — Phase-E frustum/knn/collision/neighbor cluster =====

    @Override
    protected boolean doesFrustumIntersectNode(PyramidKey nodeIndex, Frustum3D frustum) {
        throw new UnsupportedOperationException(
        "RDR-010 pi1.3 Phase E: doesFrustumIntersectNode — bead Luciferase-ioz");
    }

    @Override
    protected Stream<PyramidKey> getFrustumTraversalOrder(Frustum3D frustum, Point3f cameraPosition) {
        throw new UnsupportedOperationException(
        "RDR-010 pi1.3 Phase E: getFrustumTraversalOrder — bead Luciferase-ioz");
    }

    @Override
    protected float estimateNodeDistance(PyramidKey nodeIndex, Point3f queryPoint) {
        throw new UnsupportedOperationException(
        "RDR-010 pi1.3 Phase E: estimateNodeDistance — bead Luciferase-ioz");
    }

    @Override
    protected boolean shouldContinueKNNSearch(PyramidKey nodeIndex, Point3f queryPoint,
                                              PriorityQueue<EntityDistance<ID>> candidates) {
        throw new UnsupportedOperationException(
        "RDR-010 pi1.3 Phase E: shouldContinueKNNSearch — bead Luciferase-ioz");
    }

    /**
     * Phase A placeholder strategy: defers all subdivision decisions. No geometry is available until
     * Phase E (bead Luciferase-ioz) provides the real implementation.
     *
     * <p>This method must return a non-null value because {@code AbstractSpatialIndex} stores the
     * result during construction (before any entity insertion occurs). The placeholder never
     * actually subdivides, so construction and insert-free usage is safe. Any code path that
     * triggers subdivision will get a DEFER_SUBDIVISION decision (insert goes into the parent node).
     */
    @Override
    protected SubdivisionStrategy<PyramidKey, ID, Content> createDefaultSubdivisionStrategy() {
        return new SubdivisionStrategy<>() {
            @Override
            public Set<PyramidKey> calculateTargetNodes(PyramidKey parentIndex, byte parentLevel,
                                                        EntityBounds entityBounds,
                                                        AbstractSpatialIndex<PyramidKey, ID, Content> index) {
                return Set.of();
            }

            @Override
            public SubdivisionResult determineStrategy(SubdivisionContext<PyramidKey, ID> context) {
                return SubdivisionResult.deferSubdivision(
                "RDR-010 pi1.3 Phase E: createDefaultSubdivisionStrategy — bead Luciferase-ioz");
            }

            @Override
            protected double estimateEntitySizeFactor(SubdivisionContext<PyramidKey, ID> context) {
                return 1.0;
            }
        };
    }

    @Override
    protected void addNeighboringNodes(PyramidKey nodeIndex, Queue<PyramidKey> toVisit,
                                       Set<PyramidKey> visitedNodes) {
        throw new UnsupportedOperationException(
        "RDR-010 pi1.3 Phase E: addNeighboringNodes — bead Luciferase-ioz");
    }
}
