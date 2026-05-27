/*
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien.prism;

import com.hellblazer.luciferase.lucien.AbstractSpatialIndex;
import com.hellblazer.luciferase.lucien.Ray3D;
import com.hellblazer.luciferase.lucien.Frustum3D;
import com.hellblazer.luciferase.lucien.Plane3D;
import com.hellblazer.luciferase.lucien.Spatial;
import com.hellblazer.luciferase.lucien.SpatialIndex;
import com.hellblazer.luciferase.lucien.SubdivisionStrategy;
import com.hellblazer.luciferase.lucien.VolumeBounds;
import com.hellblazer.luciferase.lucien.entity.EntityManager;
import com.hellblazer.luciferase.lucien.balancing.TreeBalancer;
import com.hellblazer.luciferase.lucien.balancing.TreeBalancingStrategy;
import com.hellblazer.luciferase.lucien.geometry.AABBIntersector;
import com.hellblazer.luciferase.lucien.visitor.TreeVisitor;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.vecmath.Point3f;
import javax.vecmath.Tuple3i;
import javax.vecmath.Vector3f;

/**
 * Prism-based spatial index implementation combining 2D triangular subdivision
 * with 1D linear subdivision to create an anisotropic spatial data structure.
 * 
 * This implementation provides fine horizontal granularity and coarse vertical
 * granularity, making it ideal for applications where horizontal precision is
 * more important than vertical precision (e.g., terrain, urban planning).
 * 
 * @author hal.hildebrand
 * @param <ID> The type of entity identifiers
 * @param <Content> The type of content stored with entities
 */
public class Prism<ID extends com.hellblazer.luciferase.lucien.entity.EntityID, Content> extends AbstractSpatialIndex<PrismKey, ID, Content> {
    
    /** Maximum subdivision level */
    public static final int MAX_LEVEL = 21;
    
    private final float worldSize;
    private final int maxLevel;
    private final TreeBalancer<PrismKey, ID> balancer;
    private final PrismSubdivisionStrategy<ID, Content> subdivisionStrategy;
    
    /**
     * Create a new Prism spatial index with default parameters.
     * Uses balanced subdivision strategy.
     * 
     * @param idGenerator The ID generator for creating entity IDs
     */
    public Prism(com.hellblazer.luciferase.lucien.entity.EntityIDGenerator<ID> idGenerator) {
        this(idGenerator, 1.0f, MAX_LEVEL, PrismSubdivisionStrategy.balanced());
    }
    
    /**
     * Create a new Prism spatial index with specified parameters.
     * Uses balanced subdivision strategy.
     * 
     * @param idGenerator The ID generator for creating entity IDs
     * @param worldSize The size of the world cube (default 1.0)
     * @param maxLevel The maximum subdivision level (default 21)
     */
    public Prism(com.hellblazer.luciferase.lucien.entity.EntityIDGenerator<ID> idGenerator, float worldSize, int maxLevel) {
        this(idGenerator, worldSize, maxLevel, PrismSubdivisionStrategy.balanced());
    }
    
    /**
     * Create a new Prism spatial index with custom subdivision strategy.
     * 
     * @param idGenerator The ID generator for creating entity IDs
     * @param worldSize The size of the world cube
     * @param maxLevel The maximum subdivision level
     * @param strategy The subdivision strategy to use
     */
    public Prism(com.hellblazer.luciferase.lucien.entity.EntityIDGenerator<ID> idGenerator, 
                 float worldSize, int maxLevel, PrismSubdivisionStrategy<ID, Content> strategy) {
        super(idGenerator, 100, (byte)maxLevel, com.hellblazer.luciferase.lucien.entity.EntitySpanningPolicy.withSpanning());
        this.worldSize = worldSize;
        this.maxLevel = maxLevel;
        this.balancer = new NoOpTreeBalancer();
        this.subdivisionStrategy = strategy;
    }
    
    @Override
    protected PrismKey calculateSpatialIndex(Point3f position, byte level) {
        float x = position.x;
        float y = position.y;
        float z = position.z;
        // Validate coordinates
        if (x < 0 || x >= worldSize || y < 0 || y >= worldSize || z < 0 || z >= worldSize) {
            throw new IllegalArgumentException(
                String.format("Coordinates (%.3f, %.3f, %.3f) outside world bounds [0, %.3f)", 
                            x, y, z, worldSize));
        }
        
        // Normalize coordinates to [0,1)
        float normX = x / worldSize;
        float normY = y / worldSize;
        float normZ = z / worldSize;
        
        // Full-cube two-prism cover (RDR-009 P3): y <= x routes to the S0 root, y > x to the S1
        // root. Triangle.fromWorldCoordinates handles the routing (reflecting S1 into the S0
        // frame), so every point in [0,worldSize)^3 maps to exactly one prism — no gaps, no
        // double-counting along the diagonal, and no silent relocation of the old per-cell model.
        var triangle = Triangle.fromWorldCoordinate(normX, normY, level);
        
        // Create Line from Z coordinate
        var line = Line.fromWorldCoordinate(normZ, level);
        
        // Return composite PrismKey
        return new PrismKey(triangle, line);
    }
    
    @Override
    protected float estimateNodeDistance(PrismKey prismKey, Point3f queryPoint) {
        float x = queryPoint.x;
        float y = queryPoint.y;
        float z = queryPoint.z;
        // Get prism centroid
        var centroid = PrismGeometry.computeCentroid(prismKey);
        
        // Denormalize centroid to world coordinates
        float centroidX = centroid[0] * worldSize;
        float centroidY = centroid[1] * worldSize;
        float centroidZ = centroid[2] * worldSize;
        
        // Compute Euclidean distance
        float dx = x - centroidX;
        float dy = y - centroidY;
        float dz = z - centroidZ;
        
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
    
    protected float[] getBounds(PrismKey prismKey) {
        // Get normalized bounds from geometry
        var normalizedBounds = PrismGeometry.computeBoundingBox(prismKey);
        
        // Denormalize to world coordinates
        return new float[] {
            normalizedBounds[0] * worldSize,  // minX
            normalizedBounds[1] * worldSize,  // minY
            normalizedBounds[2] * worldSize,  // minZ
            normalizedBounds[3] * worldSize,  // maxX
            normalizedBounds[4] * worldSize,  // maxY
            normalizedBounds[5] * worldSize   // maxZ
        };
    }
    
    @Override
    protected boolean doesRayIntersectNode(PrismKey prismKey, Ray3D ray) {
        // Get prism bounds for initial AABB test
        var bounds = getBounds(prismKey);
        
        // Quick AABB rejection test
        if (!AABBIntersector.intersectsRay(bounds, ray)) {
            return false;
        }
        
        // For now, use AABB intersection as final result
        // TODO: Implement exact prism-ray intersection
        return true;
    }
    
    @Override
    protected boolean doesFrustumIntersectNode(PrismKey prismKey, Frustum3D frustum) {
        // Get prism bounds
        var bounds = getBounds(prismKey);
        
        // Test AABB against frustum
        return frustum.intersectsAABB(
            bounds[0], bounds[1], bounds[2],
            bounds[3], bounds[4], bounds[5]
        );
    }
    
    @Override
    protected boolean doesPlaneIntersectNode(PrismKey prismKey, Plane3D plane) {
        // Get prism vertices
        var vertices = PrismGeometry.getVertices(prismKey);
        
        // Scale vertices to world coordinates
        for (var vertex : vertices) {
            vertex.scale(worldSize);
        }
        
        // Test if any vertex is on different side of plane
        boolean hasPositive = false;
        boolean hasNegative = false;
        
        for (var vertex : vertices) {
            float distance = plane.distanceToPoint(vertex);
            if (distance > 0) hasPositive = true;
            if (distance < 0) hasNegative = true;
            
            // Prism straddles plane
            if (hasPositive && hasNegative) {
                return true;
            }
        }
        
        // All vertices on same side
        return false;
    }
    
    @Override
    protected boolean doesNodeIntersectVolume(PrismKey prismKey, Spatial spatial) {
        // Get prism bounds
        var bounds = getBounds(prismKey);
        
        // Test AABB intersection using Spatial interface
        return spatial.intersects(
            bounds[0], bounds[1], bounds[2],
            bounds[3], bounds[4], bounds[5]
        );
    }
    
    @Override
    protected void addNeighboringNodes(PrismKey nodeIndex, Queue<PrismKey> toVisit, Set<PrismKey> visitedNodes) {
        // The 3x3 neighbor shell: every combination of {self, the 3 lateral neighbors} x
        // {self, below, above}, minus self. This matches the Octree (26-neighbor) and Tetree
        // (face+edge+grid-shell) collision/kNN neighbor contract; both single-hop collision passes
        // (checkNeighborNodesForCollisions, findAdjacentNodeCollisions) and the kNN BFS rely on
        // edge/vertex-adjacent (diagonal) cells being reachable directly, so a face-only set would
        // miss collisions between entities in edge/vertex-adjacent prisms.
        //
        // RDR-009 P6: the lateral neighbors come from Triangle.faceNeighbor (the t8 cross-diagonal
        // face-neighbor from P4), so a cell adjacent to the shared S0/S1 diagonal yields its
        // neighbor in the OTHER prism family — letting these queries traverse both halves of the
        // full cube. The legacy same-half triangle.neighbors() suppressed that crossing.
        var triangle = nodeIndex.getTriangle();
        var line = nodeIndex.getLine();

        var lateral = new ArrayList<Triangle>(4);
        lateral.add(triangle);
        for (var face = 0; face < 3; face++) {
            var t = triangle.faceNeighbor(face);
            if (t != null) {
                lateral.add(t);
            }
        }
        var levels = new ArrayList<Line>(3);
        levels.add(line);
        for (var l : line.neighbors()) {
            if (l != null) {
                levels.add(l);
            }
        }

        for (var t : lateral) {
            for (var l : levels) {
                if (t == triangle && l == line) {
                    continue; // skip self
                }
                var neighbor = new PrismKey(t, l);
                if (!visitedNodes.contains(neighbor) && spatialIndex.containsKey(neighbor)) {
                    toVisit.add(neighbor);
                }
            }
        }
    }
    
    
    @Override
    protected float getRayNodeIntersectionDistance(PrismKey nodeIndex, Ray3D ray) {
        // Get prism bounds
        var bounds = getBounds(nodeIndex);
        
        // Compute AABB intersection
        float[] tValues = AABBIntersector.computeRayAABBIntersection(bounds, ray);
        
        if (tValues == null) {
            return Float.POSITIVE_INFINITY;
        }
        
        // Use nearest intersection point
        float t = tValues[0];
        if (t < 0 && tValues[1] >= 0) {
            t = tValues[1]; // Ray starts inside box
        }
        
        if (t < 0) {
            return Float.POSITIVE_INFINITY; // No forward intersection
        }
        
        return t;
    }
    
    protected PrismKey getParentIndex(PrismKey childKey) {
        if (childKey.getLevel() == 0) {
            return null; // Root has no parent
        }
        
        // Get parent triangle and line
        var parentTriangle = childKey.getTriangle().parent();
        var parentLine = childKey.getLine().parent();
        
        return new PrismKey(parentTriangle, parentLine);
    }
    
    protected PrismKey getChildIndex(PrismKey parentKey, int childIdx) {
        if (childIdx < 0 || childIdx >= 8) {
            throw new IllegalArgumentException("Child index must be 0-7, got: " + childIdx);
        }
        
        // Prisms have 8 children: 4 triangle children × 2 line children
        // Decode Morton order: childIdx = triangleIdx * 2 + lineIdx
        int triangleIdx = childIdx / 2;
        int lineIdx = childIdx % 2;
        
        var childTriangle = parentKey.getTriangle().child(triangleIdx);
        var childLine = parentKey.getLine().child(lineIdx);
        
        return new PrismKey(childTriangle, childLine);
    }
    
    protected List<PrismKey> getAllChildren(PrismKey parentKey) {
        var children = new ArrayList<PrismKey>(8);
        
        // Generate all 8 children in Morton order
        for (int i = 0; i < 8; i++) {
            children.add(getChildIndex(parentKey, i));
        }
        
        return children;
    }
    
    protected TreeBalancer<PrismKey, ID> getTreeBalancer() {
        return balancer;
    }
    
    @Override
    public SubdivisionStrategy<PrismKey, ID, Content> getSubdivisionStrategy() {
        return subdivisionStrategy;
    }
    
    /**
     * Helper method to estimate normal vector at intersection point on AABB.
     */
    private Vector3f estimateNormalAtPoint(float[] bounds, Point3f point) {
        float epsilon = 1e-5f;
        Vector3f normal = new Vector3f();
        
        // Check which face the point is on
        if (Math.abs(point.x - bounds[0]) < epsilon) {
            normal.set(-1, 0, 0); // Left face
        } else if (Math.abs(point.x - bounds[3]) < epsilon) {
            normal.set(1, 0, 0); // Right face
        } else if (Math.abs(point.y - bounds[1]) < epsilon) {
            normal.set(0, -1, 0); // Bottom face
        } else if (Math.abs(point.y - bounds[4]) < epsilon) {
            normal.set(0, 1, 0); // Top face
        } else if (Math.abs(point.z - bounds[2]) < epsilon) {
            normal.set(0, 0, -1); // Near face
        } else {
            normal.set(0, 0, 1); // Far face
        }
        
        return normal;
    }
    
    @Override
    protected boolean shouldContinueKNNSearch(PrismKey nodeIndex, Point3f queryPoint,
                                            java.util.PriorityQueue<com.hellblazer.luciferase.lucien.entity.EntityDistance<ID>> candidates) {
        // Standard kNN pruning: continue iff the closest possible point in this
        // node's bounds is no farther than the worst (head of max-heap) candidate
        // we already have. If the AABB's closest point is strictly farther, no
        // entity inside this node can improve the candidate set.
        if (candidates.isEmpty()) {
            return true;
        }
        var furthest = candidates.peek();
        if (furthest == null) {
            return true;
        }
        var bounds = getBounds(nodeIndex);
        var closestX = Math.max(bounds[0], Math.min(queryPoint.x, bounds[3]));
        var closestY = Math.max(bounds[1], Math.min(queryPoint.y, bounds[4]));
        var closestZ = Math.max(bounds[2], Math.min(queryPoint.z, bounds[5]));
        var dx = closestX - queryPoint.x;
        var dy = closestY - queryPoint.y;
        var dz = closestZ - queryPoint.z;
        var nodeDistance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        return nodeDistance <= furthest.distance();
    }
    
    @Override
    protected SubdivisionStrategy<PrismKey, ID, Content> createDefaultSubdivisionStrategy() {
        return PrismSubdivisionStrategy.balanced();
    }
    
    @Override
    protected boolean isNodeContainedInVolume(PrismKey nodeIndex, Spatial volume) {
        // True containment: every point of the node must be inside the volume.
        // We use the node's AABB as a conservative superset of the triangular
        // prism it actually represents; if the AABB is contained, the prism
        // (a subset of the AABB) is necessarily contained too. The prior code
        // returned intersection instead of containment, which produced false
        // positives that caused range queries to over-include nodes whose
        // entities sat outside the requested volume.
        var bounds = getBounds(nodeIndex);
        var minX = bounds[0];
        var minY = bounds[1];
        var minZ = bounds[2];
        var maxX = bounds[3];
        var maxY = bounds[4];
        var maxZ = bounds[5];
        return switch (volume) {
            case Spatial.Cube cube ->
                minX >= cube.originX() && minY >= cube.originY() && minZ >= cube.originZ()
                && maxX <= cube.originX() + cube.extent()
                && maxY <= cube.originY() + cube.extent()
                && maxZ <= cube.originZ() + cube.extent();
            case Spatial.aabb box ->
                minX >= box.originX() && minY >= box.originY() && minZ >= box.originZ()
                && maxX <= box.extentX() && maxY <= box.extentY() && maxZ <= box.extentZ();
            case Spatial.Sphere sphere -> {
                var r2 = sphere.radius() * sphere.radius();
                for (int i = 0; i < 8; i++) {
                    var cx = ((i & 1) != 0 ? maxX : minX) - sphere.centerX();
                    var cy = ((i & 2) != 0 ? maxY : minY) - sphere.centerY();
                    var cz = ((i & 4) != 0 ? maxZ : minZ) - sphere.centerZ();
                    if (cx * cx + cy * cy + cz * cz > r2) {
                        yield false;
                    }
                }
                yield true;
            }
            default -> false; // Conservative: exclude for volume types we don't recognise.
        };
    }
    
    @Override
    protected Set<PrismKey> findNodesIntersectingBounds(com.hellblazer.luciferase.lucien.VolumeBounds bounds) {
        // Map-scan over both prism families (the index holds S0 and S1 keys), collecting every node
        // whose world AABB intersects the query bounds. Mirrors the entitiesInRegion / rayIntersectAll
        // scan philosophy and is consistent with Prism's O(n) k-NN BFS.
        //
        // Luciferase-h65: previously a stub returning empty, which silently no-op'd its callers for
        // Prism — most importantly the k-NN expanding-radius fallback (searchKNNInRadius), as well as
        // bounded/region search (findCollisionsInRegion, bounded-spanning insert). Scanning both
        // halves makes those find entities in either family.
        var result = new HashSet<PrismKey>();
        for (var key : spatialIndex.keySet()) {
            var b = getBounds(key); // [minX, minY, minZ, maxX, maxY, maxZ] world AABB
            var disjoint = b[3] < bounds.minX() || b[0] > bounds.maxX()
                        || b[4] < bounds.minY() || b[1] > bounds.maxY()
                        || b[5] < bounds.minZ() || b[2] > bounds.maxZ();
            if (!disjoint) {
                result.add(key);
            }
        }
        return result;
    }
    
    @Override
    protected float getCellSizeAtLevel(byte level) {
        // Normalized [0,1) world: the cell size is fractional (worldSize / 2^level). Returning the
        // true value (not an int truncation to 0) is required for the ASI k-NN search radius,
        // level selection, and spanning comparisons to work in Prism's coordinate space (Luciferase-h65).
        return (float) (worldSize / Math.pow(2, level));
    }

    @Override
    protected boolean knnRequiresFullDomainSweep() {
        // Prism's normalized [0,1) fractional coordinates defeat the ASI expanding-radius heuristic
        // (sub-unit initial radius cannot span the world within the expansion cap), so a sparse k-NN
        // needs the final full-domain sweep. Affordable here: findNodesIntersectingBounds is an O(n)
        // map-scan, not an SFC interval walk. (Luciferase-h65)
        return true;
    }

    /**
     * Get the cell size at a specific level as a float.
     *
     * @param level the level (0-21)
     * @return the cell size as a float
     * @deprecated {@link #getCellSizeAtLevel(byte)} now returns {@code float}; this delegates to it
     *             and is retained for source compatibility.
     */
    @Deprecated
    public float getCellSizeAtLevelFloat(byte level) {
        return getCellSizeAtLevel(level);
    }
    
    @Override
    protected Stream<PrismKey> getFrustumTraversalOrder(Frustum3D frustum, Point3f cameraPosition) {
        // Simple implementation - return all nodes that might intersect frustum
        // If no nodes yet, return empty stream
        if (spatialIndex.isEmpty()) {
            return Stream.empty();
        }
        
        // Return all non-empty nodes that intersect the frustum
        return spatialIndex.keySet().stream()
            .filter(key -> {
                var node = spatialIndex.get(key);
                return node != null && !node.getEntityIds().isEmpty() && doesFrustumIntersectNode(key, frustum);
            });
    }
    
    @Override
    protected Spatial getNodeBounds(PrismKey index) {
        var bounds = getBounds(index);
        return new Spatial.aabb(
            bounds[0], bounds[1], bounds[2],
            bounds[3], bounds[4], bounds[5]
        );
    }
    
    @Override
    protected Stream<PrismKey> getPlaneTraversalOrder(Plane3D plane) {
        // Return empty stream for now - would need plane intersection implementation
        return Stream.empty();
    }
    
    @Override
    protected Stream<PrismKey> getRayTraversalOrder(Ray3D ray) {
        // Find all prisms that the ray intersects, ordered by distance
        return spatialIndex.keySet().stream()
            .filter(key -> PrismRayIntersector.intersectRayAABB(ray, key))
            .map(key -> {
                var result = PrismRayIntersector.intersectRayPrism(ray, key);
                return new RayIntersection(key, result.tNear);
            })
            .filter(intersection -> intersection.t >= 0)
            .sorted((a, b) -> Float.compare(a.t, b.t))
            .map(intersection -> intersection.key);
    }
    
    /**
     * Helper class for ray intersection sorting.
     */
    private static class RayIntersection {
        final PrismKey key;
        final float t;
        
        RayIntersection(PrismKey key, float t) {
            this.key = key;
            this.t = t;
        }
    }
    
    @Override
    public SpatialIndex.SpatialNode<PrismKey, ID> enclosing(Spatial volume) {
        // Find the minimum enclosing prism for a volume
        // For now, return null - would need volume enclosure calculation
        return null;
    }
    
    @Override
    public SpatialIndex.SpatialNode<PrismKey, ID> enclosing(Tuple3i point, byte level) {
        // Find the prism containing this point at the specified level
        var prismKey = calculateSpatialIndex(new Point3f(point.x, point.y, point.z), level);
        var node = spatialIndex.get(prismKey);
        if (node != null) {
            return new SpatialIndex.SpatialNode<>(prismKey, new HashSet<>(node.getEntityIds()));
        }
        return null;
    }
    
    /**
     * Find all entities within a triangular region in the XY plane.
     * This query is optimized for the prism's triangular subdivision.
     * 
     * @param triangle The triangle defining the search region in XY plane
     * @param minZ Minimum Z coordinate
     * @param maxZ Maximum Z coordinate
     * @return Set of entity IDs within the triangular region
     */
    public Set<ID> findInTriangularRegion(Triangle searchTriangle, float minZ, float maxZ) {
        Set<ID> results = new HashSet<>();
        
        // Normalize coordinates
        float normMinZ = minZ / worldSize;
        float normMaxZ = maxZ / worldSize;
        
        // Find all prism keys that might intersect this triangular region
        spatialIndex.forEach((key, node) -> {
            if (node != null && !node.getEntityIds().isEmpty()) {
                // Check if prism's triangle intersects search triangle
                var prismTriangle = key.getTriangle();
                if (trianglesIntersect(prismTriangle, searchTriangle)) {
                    // Check Z overlap
                    var prismLine = key.getLine();
                    float[] lineBounds = prismLine.getWorldBounds();
                    float prismMinZ = lineBounds[0];
                    float prismMaxZ = lineBounds[1];
                    
                    if (prismMinZ <= normMaxZ && prismMaxZ >= normMinZ) {
                        // This prism intersects the search region
                        results.addAll(node.getEntityIds());
                    }
                }
            }
        });
        
        return results;
    }
    
    /**
     * Find all entities within a specific vertical layer.
     * This is highly optimized for the prism structure which has coarse vertical granularity.
     * 
     * @param minZ Minimum Z coordinate of the layer
     * @param maxZ Maximum Z coordinate of the layer
     * @return Set of entity IDs within the vertical layer
     */
    public Set<ID> findInVerticalLayer(float minZ, float maxZ) {
        Set<ID> results = new HashSet<>();
        
        // Normalize Z coordinates
        float normMinZ = minZ / worldSize;
        float normMaxZ = maxZ / worldSize;
        
        // Efficiently find all prisms in this Z range
        spatialIndex.forEach((key, node) -> {
            if (node != null && !node.getEntityIds().isEmpty()) {
                var line = key.getLine();
                float[] lineBounds = line.getWorldBounds();
                float prismMinZ = lineBounds[0];
                float prismMaxZ = lineBounds[1];
                
                // Check if prism overlaps the layer
                if (prismMinZ <= normMaxZ && prismMaxZ >= normMinZ) {
                    results.addAll(node.getEntityIds());
                }
            }
        });
        
        return results;
    }
    
    /**
     * Find all entities within a specific vertical layer that also fall within
     * a triangular region in the XY plane. Combines the benefits of both query types.
     * 
     * @param triangle The triangle defining the search region in XY plane
     * @param minZ Minimum Z coordinate
     * @param maxZ Maximum Z coordinate
     * @return Set of entity IDs within the triangular prism volume
     */
    public Set<ID> findInTriangularPrism(Triangle searchTriangle, float minZ, float maxZ) {
        // This is equivalent to findInTriangularRegion but named more clearly
        return findInTriangularRegion(searchTriangle, minZ, maxZ);
    }
    
    /**
     * Check if two triangles intersect in 2D (XY plane).
     * Uses separating axis theorem for triangle-triangle intersection.
     */
    private boolean trianglesIntersect(Triangle t1, Triangle t2) {
        // Get vertices of both triangles
        float[][] v1 = t1.getVertices();
        float[][] v2 = t2.getVertices();
        
        // First check if any vertex of t1 is inside t2
        for (float[] vertex : v1) {
            if (t2.contains(vertex[0], vertex[1])) {
                return true;
            }
        }
        
        // Check if any vertex of t2 is inside t1
        for (float[] vertex : v2) {
            if (t1.contains(vertex[0], vertex[1])) {
                return true;
            }
        }
        
        // Check edge-edge intersections
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                if (edgesIntersect(
                    v1[i][0], v1[i][1], v1[(i + 1) % 3][0], v1[(i + 1) % 3][1],
                    v2[j][0], v2[j][1], v2[(j + 1) % 3][0], v2[(j + 1) % 3][1])) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    /**
     * Check if two line segments intersect.
     */
    private boolean edgesIntersect(float x1, float y1, float x2, float y2,
                                  float x3, float y3, float x4, float y4) {
        float denom = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4);
        if (Math.abs(denom) < 1e-10) {
            return false; // Lines are parallel
        }
        
        float t = ((x1 - x3) * (y3 - y4) - (y1 - y3) * (x3 - x4)) / denom;
        float u = -((x1 - x2) * (y1 - y3) - (y1 - y2) * (x1 - x3)) / denom;
        
        return t >= 0 && t <= 1 && u >= 0 && u <= 1;
    }
    
    
    
    /**
     * No-op tree balancer that disables automatic balancing.
     */
    private class NoOpTreeBalancer implements TreeBalancer<PrismKey, ID> {
        
        @Override
        public BalancingAction checkNodeBalance(PrismKey nodeIndex) {
            return BalancingAction.NONE;
        }
        
        @Override
        public TreeBalancingStrategy.TreeBalancingStats getBalancingStats() {
            return new TreeBalancingStrategy.TreeBalancingStats(0, 0, 0, 0, 0, 0.0, 0.0);
        }
        
        @Override
        public boolean isAutoBalancingEnabled() {
            return false;
        }
        
        @Override
        public boolean mergeNodes(Set<PrismKey> nodeIndices, PrismKey parentIndex) {
            return false; // No-op
        }
        
        @Override
        public int rebalanceSubtree(PrismKey rootNodeIndex) {
            return 0; // No-op
        }
        
        @Override
        public RebalancingResult rebalanceTree() {
            return new RebalancingResult(0, 0, 0, 0, 0, 0L, true);
        }
        
        @Override
        public void setAutoBalancingEnabled(boolean enabled) {
            // No-op
        }
        
        @Override
        public void setBalancingStrategy(TreeBalancingStrategy<ID> strategy) {
            // No-op
        }
        
        @Override
        public List<PrismKey> splitNode(PrismKey nodeIndex, byte nodeLevel) {
            return List.of(); // No-op
        }
    }
}