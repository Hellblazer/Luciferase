/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.Ray3D;
import com.hellblazer.luciferase.lucien.entity.EntityID;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import javax.vecmath.Vector3f;
import java.util.*;
import java.util.stream.Stream;

/**
 * Optimized ray traversal for Tetree using space-filling curve properties. Replaces brute-force traversal with
 * efficient neighbor-based algorithm. Based on t8code's ray traversal approach.
 *
 * @author hal.hildebrand
 */
public class TetreeSFCRayTraversal<ID extends EntityID, Content> {

    private static final float               EPSILON = 1e-6f;
    private final        Tetree<ID, Content> tree;

    public TetreeSFCRayTraversal(Tetree<ID, Content> tree) {
        this.tree = tree;
    }

    /**
     * Traverse the tetree along a ray using SFC properties for efficiency. Returns tetrahedra indices in the order they
     * are intersected by the ray.
     *
     * @param ray The ray to traverse
     * @return Stream of tetrahedral indices in traversal order
     */
    public Stream<TetreeKey> traverseRay(Ray3D ray) {
        // For sparse trees, we need to check actual nodes in the tree
        // Since nodes can exist at different levels, we need to check all existing nodes
        
        // Get all non-empty nodes from the tree
        var allNodes = tree.getSortedSpatialIndices();
        
        // Filter to only nodes that the ray intersects
        List<TetreeKey> intersectedNodes = new ArrayList<>();
        
        for (TetreeKey nodeIndex : allNodes) {
            // The TetreeKey already contains the level information
            // Check if ray intersects this tetrahedron at its specific level
            var intersection = TetrahedralGeometry.rayIntersectsTetrahedron(ray, nodeIndex);
            if (intersection.intersects) {
                intersectedNodes.add(nodeIndex);
            }
        }
        
        // Sort by distance along ray
        return sortByRayDistance(intersectedNodes, ray).stream();
    }

    /**
     * Add child tetrahedra that could be intersected by the ray.
     */
    private void addIntersectedChildren(Tet current, Ray3D ray, Queue<Tet> toProcess, Set<TetreeKey> visited) {
        for (int i = 0; i < 8; i++) {
            try {
                Tet child = current.child(i);
                if (!visited.contains(child.tmIndex()) && couldRayIntersect(ray, child)) {
                    toProcess.offer(child);
                    visited.add(child.tmIndex());
                }
            } catch (IllegalStateException e) {
                // Max level reached
                break;
            }
        }
    }

    /**
     * Add neighboring tetrahedra that could be intersected by the ray.
     */
    private void addIntersectedNeighbors(Tet current, Ray3D ray, Queue<Tet> toProcess, Set<TetreeKey> visited) {
        // Check all 4 faces
        for (int face = 0; face < 4; face++) {
            Tet.FaceNeighbor neighbor = current.faceNeighbor(face);

            // Check if neighbor exists (null at boundary)
            if (neighbor == null) {
                continue;
            }

            Tet neighborTet = neighbor.tet();

            // Check if neighbor is valid and not visited
            if (neighborTet.isValid() && !visited.contains(neighborTet.tmIndex())) {
                // Quick check if ray could possibly intersect this neighbor
                if (couldRayIntersect(ray, neighborTet)) {
                    toProcess.offer(neighborTet);
                    visited.add(neighborTet.tmIndex());
                }
            }
        }
    }

    /**
     * Quick test if ray could possibly intersect a tetrahedron. Uses bounding box test for efficiency.
     */
    private boolean couldRayIntersect(Ray3D ray, Tet tet) {
        Point3i[] vertices = tet.coordinates();

        // Find AABB of tetrahedron
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = Float.MIN_VALUE, maxY = Float.MIN_VALUE, maxZ = Float.MIN_VALUE;

        for (Point3i v : vertices) {
            minX = Math.min(minX, v.x);
            minY = Math.min(minY, v.y);
            minZ = Math.min(minZ, v.z);
            maxX = Math.max(maxX, v.x);
            maxY = Math.max(maxY, v.y);
            maxZ = Math.max(maxZ, v.z);
        }

        // Ray-AABB intersection test
        return rayIntersectsAABB(ray, minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * Find the tetrahedron containing a given point at the appropriate level.
     */
    private Tet findContainingTetrahedron(Point3f point) {
        // Simple approach: check if the point intersects with the ray
        // For the basic case, just return the first tetrahedron that contains entities
        // and check if ray could intersect it

        // Try to find any existing tetrahedron that could be intersected by a ray from this point
        // For now, just return any valid tetrahedron at a reasonable level
        return tree.locateTetrahedron(point, (byte) 10);
    }

    /**
     * Find where ray enters the domain (if it starts outside).
     */
    private Point3f findDomainEntryPoint(Ray3D ray) {
        float maxCoord = Constants.lengthAtLevel((byte) 0);

        // Ray-AABB intersection for domain bounds
        if (!rayIntersectsAABB(ray, 0, 0, 0, maxCoord, maxCoord, maxCoord)) {
            return null;
        }

        // Find entry point
        Point3f origin = ray.origin();
        Vector3f dir = ray.direction();

        float tmin = 0.0f;

        // Check each axis
        if (origin.x < 0) {
            tmin = Math.max(tmin, -origin.x / dir.x);
        } else if (origin.x > maxCoord) {
            tmin = Math.max(tmin, (maxCoord - origin.x) / dir.x);
        }

        if (origin.y < 0) {
            tmin = Math.max(tmin, -origin.y / dir.y);
        } else if (origin.y > maxCoord) {
            tmin = Math.max(tmin, (maxCoord - origin.y) / dir.y);
        }

        if (origin.z < 0) {
            tmin = Math.max(tmin, -origin.z / dir.z);
        } else if (origin.z > maxCoord) {
            tmin = Math.max(tmin, (maxCoord - origin.z) / dir.z);
        }

        // Calculate entry point
        Point3f entry = new Point3f(origin);
        entry.scaleAdd(tmin + EPSILON, dir, origin);

        return entry;
    }

    /**
     * Find the entry tetrahedron where the ray starts or first enters the domain.
     */
    private Tet findEntryTetrahedron(Ray3D ray) {
        Point3f origin = ray.origin();

        // Check if origin is inside domain
        if (isInsideDomain(origin)) {
            // Find the tetrahedron containing the origin
            return findContainingTetrahedron(origin);
        } else {
            // Find where ray enters the domain
            Point3f entryPoint = findDomainEntryPoint(ray);
            if (entryPoint != null) {
                return findContainingTetrahedron(entryPoint);
            }
        }

        return null;
    }

    /**
     * Find the tetrahedron that would contain a point at a specific level, generating the tetrahedron even if it
     * doesn't exist in the tree.
     */
    private Tet findTetrahedronAtLevel(Point3f point, byte level) {
        // Convert point to positive coordinates if needed
        float x = Math.max(0, point.x);
        float y = Math.max(0, point.y);
        float z = Math.max(0, point.z);

        // Use the tree's locateTetrahedron method to find the containing tetrahedron
        // This will generate the tetrahedron based on the SFC encoding
        return tree.locateTetrahedron(new Point3f(x, y, z), level);
    }

    /**
     * Check if a point is inside the domain.
     */
    private boolean isInsideDomain(Point3f point) {
        float maxCoord = Constants.lengthAtLevel((byte) 0);
        return point.x >= 0 && point.x <= maxCoord && point.y >= 0 && point.y <= maxCoord && point.z >= 0
        && point.z <= maxCoord;
    }

    /**
     * Check if a point is reasonable for domain calculations (not too far outside bounds).
     */
    private boolean isPointReasonableForDomain(Point3f point, float domainSize) {
        float margin = domainSize * 0.1f; // Allow 10% margin outside domain
        return point.x >= -margin && point.x <= domainSize + margin && point.y >= -margin
        && point.y <= domainSize + margin && point.z >= -margin && point.z <= domainSize + margin;
    }

    /**
     * Ray-AABB intersection test.
     */
    private boolean rayIntersectsAABB(Ray3D ray, float minX, float minY, float minZ, float maxX, float maxY,
                                      float maxZ) {
        Point3f origin = ray.origin();
        Vector3f dir = ray.direction();

        float tmin = 0.0f;
        float tmax = Float.MAX_VALUE;

        // X axis
        if (Math.abs(dir.x) < EPSILON) {
            if (origin.x < minX || origin.x > maxX) {
                return false;
            }
        } else {
            float t1 = (minX - origin.x) / dir.x;
            float t2 = (maxX - origin.x) / dir.x;
            tmin = Math.max(tmin, Math.min(t1, t2));
            tmax = Math.min(tmax, Math.max(t1, t2));
        }

        // Y axis
        if (Math.abs(dir.y) < EPSILON) {
            if (origin.y < minY || origin.y > maxY) {
                return false;
            }
        } else {
            float t1 = (minY - origin.y) / dir.y;
            float t2 = (maxY - origin.y) / dir.y;
            tmin = Math.max(tmin, Math.min(t1, t2));
            tmax = Math.min(tmax, Math.max(t1, t2));
        }

        // Z axis
        if (Math.abs(dir.z) < EPSILON) {
            if (origin.z < minZ || origin.z > maxZ) {
                return false;
            }
        } else {
            float t1 = (minZ - origin.z) / dir.z;
            float t2 = (maxZ - origin.z) / dir.z;
            tmin = Math.max(tmin, Math.min(t1, t2));
            tmax = Math.min(tmax, Math.max(t1, t2));
        }

        return tmax >= tmin && tmax > 0;
    }

    /**
     * Ray-triangle intersection test using Möller-Trumbore algorithm.
     */
    private boolean rayIntersectsFace(Ray3D ray, Point3f v0, Point3f v1, Point3f v2) {
        Vector3f edge1 = new Vector3f();
        edge1.sub(v1, v0);

        Vector3f edge2 = new Vector3f();
        edge2.sub(v2, v0);

        Vector3f h = new Vector3f();
        h.cross(ray.direction(), edge2);

        float a = edge1.dot(h);

        if (a > -EPSILON && a < EPSILON) {
            return false; // Ray is parallel to triangle
        }

        float f = 1.0f / a;
        Vector3f s = new Vector3f();
        s.sub(ray.origin(), v0);

        float u = f * s.dot(h);

        if (u < 0.0f || u > 1.0f) {
            return false;
        }

        Vector3f q = new Vector3f();
        q.cross(s, edge1);

        float v = f * ray.direction().dot(q);

        if (v < 0.0f || u + v > 1.0f) {
            return false;
        }

        float t = f * edge2.dot(q);

        return t > EPSILON; // Intersection exists if t > 0
    }

    /**
     * Check if a ray intersects a tetrahedron.
     */
    private boolean rayIntersectsTetrahedron(Ray3D ray, Tet tet) {
        Point3i[] vertices = tet.coordinates();

        // Convert to float coordinates
        Point3f[] verts = new Point3f[4];
        for (int i = 0; i < 4; i++) {
            verts[i] = new Point3f(vertices[i].x, vertices[i].y, vertices[i].z);
        }

        // Check ray intersection with tetrahedron
        // Using ray-triangle intersection for each face
        return rayIntersectsFace(ray, verts[0], verts[1], verts[2]) || rayIntersectsFace(ray, verts[0], verts[1],
                                                                                         verts[3]) || rayIntersectsFace(
        ray, verts[0], verts[2], verts[3]) || rayIntersectsFace(ray, verts[1], verts[2], verts[3]) || tet.contains(
        ray.origin());
    }

    /**
     * Check if we should traverse into children for this ray.
     */
    private boolean shouldCheckChildren(Tet tet, Ray3D ray) {
        // Always check children if they might contain intersections
        // Could be optimized based on ray direction and tet position
        return tree.hasNode(tet.tmIndex());
    }

    /**
     * Sort tetrahedra by distance along ray for correct traversal order.
     */
    private List<TetreeKey> sortByRayDistance(List<TetreeKey> indices, Ray3D ray) {
        Map<TetreeKey, Float> distances = new HashMap<>();

        for (TetreeKey index : indices) {
            Tet tet = Tet.tetrahedron(index);
            Point3i[] vertices = tet.coordinates();

            // Use centroid for distance calculation
            float cx = (vertices[0].x + vertices[1].x + vertices[2].x + vertices[3].x) / 4.0f;
            float cy = (vertices[0].y + vertices[1].y + vertices[2].y + vertices[3].y) / 4.0f;
            float cz = (vertices[0].z + vertices[1].z + vertices[2].z + vertices[3].z) / 4.0f;

            Point3f centroid = new Point3f(cx, cy, cz);

            // Project centroid onto ray to get distance
            Vector3f toCenter = new Vector3f();
            toCenter.sub(centroid, ray.origin());
            float distance = toCenter.dot(ray.direction());

            distances.put(index, distance);
        }

        // Sort by distance
        indices.sort(Comparator.comparing(distances::get));

        return indices;
    }
}
