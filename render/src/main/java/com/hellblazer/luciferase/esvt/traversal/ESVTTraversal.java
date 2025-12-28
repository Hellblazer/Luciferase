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
package com.hellblazer.luciferase.esvt.traversal;

import com.hellblazer.luciferase.esvt.core.ESVTNodeUnified;
import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.tetree.TetreeConnectivity;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import javax.vecmath.Vector3f;

/**
 * Stack-based ray traversal for ESVT (Efficient Sparse Voxel Tetrahedra).
 *
 * <p>This implementation follows the ESVO traversal pattern adapted for
 * tetrahedral subdivision:
 * <ul>
 *   <li>Scale-indexed stack for efficient parent restoration</li>
 *   <li>Möller-Trumbore ray-tetrahedron intersection</li>
 *   <li>Entry-face-based child ordering for front-to-back traversal</li>
 *   <li>Support for 21 refinement levels</li>
 * </ul>
 *
 * <p><b>Coordinate Space:</b> [0,1] normalized, unit tetrahedra
 *
 * <p><b>Algorithm:</b>
 * <ol>
 *   <li>Test ray against root tetrahedron</li>
 *   <li>If hit, identify entry face</li>
 *   <li>Get children at entry face in front-to-back order</li>
 *   <li>For each child that exists and ray intersects:</li>
 *   <li>If leaf → return hit</li>
 *   <li>Else push current state, descend into child</li>
 *   <li>On miss, pop and continue with siblings</li>
 * </ol>
 *
 * @author hal.hildebrand
 */
public final class ESVTTraversal {

    /** Maximum traversal depth (21 levels + root) */
    public static final int MAX_DEPTH = 22;

    /** Maximum iterations to prevent infinite loops */
    private static final int MAX_ITERATIONS = 10000;

    // Reusable intersection tester (thread-local pattern)
    private final MollerTrumboreIntersection intersector;
    private final MollerTrumboreIntersection.TetrahedronResult tetResult;
    private final ESVTStack stack;

    // Scratch space for vertex calculations
    private final Point3f[] scratchVerts = new Point3f[4];

    // Current tetrahedron vertices (tracked during traversal)
    private final float[] currentVerts = new float[12]; // 4 vertices * 3 coords

    /**
     * Create a new traversal instance.
     * Each instance has its own scratch space for thread safety.
     */
    public ESVTTraversal() {
        this.intersector = MollerTrumboreIntersection.create();
        this.tetResult = new MollerTrumboreIntersection.TetrahedronResult();
        this.stack = new ESVTStack();
        for (int i = 0; i < 4; i++) {
            scratchVerts[i] = new Point3f();
        }
    }

    /**
     * Cast a ray through the ESVT structure.
     *
     * @param ray The ray to cast (origin, direction)
     * @param nodes Array of ESVT nodes
     * @param rootIdx Index of root node (usually 0)
     * @return Traversal result with hit information
     */
    public ESVTResult castRay(ESVTRay ray, ESVTNodeUnified[] nodes, int rootIdx) {
        var result = new ESVTResult();
        ray.prepareForTraversal();
        stack.reset();

        if (nodes == null || nodes.length == 0 || rootIdx < 0 || rootIdx >= nodes.length) {
            return result;
        }

        var rayOrigin = ray.getOrigin();
        var rayDir = ray.getDirection();

        // Get root node and type
        var rootNode = nodes[rootIdx];
        if (!rootNode.isValid()) {
            return result;
        }
        byte rootType = rootNode.getTetType();

        // Get root tetrahedron vertices (unit [0,1] space)
        getRootVertices(rootType, scratchVerts);

        // Initialize current vertices from root
        currentVerts[0] = scratchVerts[0].x; currentVerts[1] = scratchVerts[0].y; currentVerts[2] = scratchVerts[0].z;
        currentVerts[3] = scratchVerts[1].x; currentVerts[4] = scratchVerts[1].y; currentVerts[5] = scratchVerts[1].z;
        currentVerts[6] = scratchVerts[2].x; currentVerts[7] = scratchVerts[2].y; currentVerts[8] = scratchVerts[2].z;
        currentVerts[9] = scratchVerts[3].x; currentVerts[10] = scratchVerts[3].y; currentVerts[11] = scratchVerts[3].z;

        // Test ray-root intersection
        if (!intersector.intersectTetrahedron(rayOrigin, rayDir,
                scratchVerts[0], scratchVerts[1], scratchVerts[2], scratchVerts[3],
                tetResult)) {
            return result;
        }

        // Ray hits root - start traversal
        int parentIdx = rootIdx;
        byte parentType = rootType;
        int entryFace = tetResult.entryFace;
        float tMin = tetResult.tEntry;
        float tMax = tetResult.tExit;
        int scale = MAX_DEPTH - 1;
        int iterations = 0;

        // Track position in the 8-child iteration
        int siblingPos = 0;  // Position in CHILD_ORDER (0-3)

        while (scale < MAX_DEPTH && iterations < MAX_ITERATIONS) {
            iterations++;

            var node = nodes[parentIdx];
            if (!node.isValid()) {
                // Invalid node - pop
                if (!popStack(scale, result)) {
                    break;
                }
                parentIdx = result.nodeIndex;
                parentType = result.tetType;
                entryFace = result.entryFace;
                tMax = result.t;
                scale++;
                siblingPos = stack.readSiblingPos(scale); // Resume from next untested sibling
                continue;
            }

            // Get children at entry face in front-to-back order
            byte[] childOrder = ESVTChildOrder.getChildOrder(parentType, entryFace);

            // Try each child starting from siblingPos
            boolean descended = false;
            for (int pos = siblingPos; pos < 4; pos++) {
                int childIdx = childOrder[pos];

                // Check if child exists
                if (!node.hasChild(childIdx)) {
                    continue;
                }

                // Get child vertices using actual parent position
                getChildVerticesFromParent(currentVerts, childIdx, scratchVerts);

                // Test ray-child intersection
                if (!intersector.intersectTetrahedron(rayOrigin, rayDir,
                        scratchVerts[0], scratchVerts[1], scratchVerts[2], scratchVerts[3],
                        tetResult)) {
                    continue;
                }

                // Child intersection found
                float childTEntry = tetResult.tEntry;
                int childEntryFace = tetResult.entryFace;

                // Check if this is a leaf
                if (node.isChildLeaf(childIdx)) {
                    // HIT - return result
                    byte childType = node.getChildType(childIdx);
                    result.setHit(childTEntry,
                        rayOrigin.x + childTEntry * rayDir.x,
                        rayOrigin.y + childTEntry * rayDir.y,
                        rayOrigin.z + childTEntry * rayDir.z,
                        parentIdx, childIdx, childType,
                        (byte) childEntryFace, scale);
                    result.exitFace = (byte) tetResult.exitFace;
                    result.iterations = iterations;
                    return result;
                }

                // Non-leaf - push current state including vertices and sibling position
                stack.write(scale, parentIdx, tMax, parentType, (byte) entryFace);
                stack.writeSiblingPos(scale, (byte) (pos + 1)); // Resume from next sibling after pop
                stack.writeVerts(scale,
                    currentVerts[0], currentVerts[1], currentVerts[2],
                    currentVerts[3], currentVerts[4], currentVerts[5],
                    currentVerts[6], currentVerts[7], currentVerts[8],
                    currentVerts[9], currentVerts[10], currentVerts[11]);

                // Update current vertices to child vertices
                currentVerts[0] = scratchVerts[0].x; currentVerts[1] = scratchVerts[0].y; currentVerts[2] = scratchVerts[0].z;
                currentVerts[3] = scratchVerts[1].x; currentVerts[4] = scratchVerts[1].y; currentVerts[5] = scratchVerts[1].z;
                currentVerts[6] = scratchVerts[2].x; currentVerts[7] = scratchVerts[2].y; currentVerts[8] = scratchVerts[2].z;
                currentVerts[9] = scratchVerts[3].x; currentVerts[10] = scratchVerts[3].y; currentVerts[11] = scratchVerts[3].z;

                // Move to child
                parentIdx = node.getChildIndex(childIdx);
                parentType = node.getChildType(childIdx);
                entryFace = childEntryFace >= 0 ? childEntryFace : 0;
                tMin = childTEntry;
                tMax = tetResult.tExit;
                scale--;
                siblingPos = 0;
                descended = true;
                break;
            }

            if (!descended) {
                // No valid child found - pop to parent
                if (scale >= MAX_DEPTH - 1) {
                    // At root level, traversal complete
                    break;
                }

                scale++;
                if (!stack.hasEntry(scale)) {
                    break;
                }

                parentIdx = stack.readNode(scale);
                parentType = stack.readType(scale);
                entryFace = stack.readEntryFace(scale);
                tMax = stack.readTmax(scale);

                // Restore parent vertices from stack
                float[] restoredVerts = stack.readVerts(scale);
                if (restoredVerts != null) {
                    System.arraycopy(restoredVerts, 0, currentVerts, 0, 12);
                }

                // Resume from next untested sibling
                siblingPos = stack.readSiblingPos(scale);
            }
        }

        result.iterations = iterations;
        return result;
    }

    /**
     * Pop traversal state from stack.
     */
    private boolean popStack(int currentScale, ESVTResult tempResult) {
        int parentScale = currentScale + 1;
        if (!stack.hasEntry(parentScale)) {
            return false;
        }
        tempResult.nodeIndex = stack.readNode(parentScale);
        tempResult.tetType = stack.readType(parentScale);
        tempResult.entryFace = stack.readEntryFace(parentScale);
        tempResult.t = stack.readTmax(parentScale);
        return true;
    }

    /**
     * Get root tetrahedron vertices in [0,1] space.
     */
    private void getRootVertices(int tetType, Point3f[] verts) {
        Point3i[] standard = Constants.SIMPLEX_STANDARD[tetType];
        for (int i = 0; i < 4; i++) {
            verts[i].set(standard[i].x, standard[i].y, standard[i].z);
        }
    }

    /**
     * Get child tetrahedron vertices using actual parent vertex positions.
     *
     * <p>Uses Bey subdivision to compute child vertices from parent's actual vertices.
     * The 8 children are formed from:
     * - 4 corner children (at each parent vertex)
     * - 4 octahedral children (in the center region)
     *
     * @param parentVerts Parent vertices as float[12] (v0.xyz, v1.xyz, v2.xyz, v3.xyz)
     * @param childIdx Child index (0-7)
     * @param childVerts Output array for child vertex positions
     */
    private void getChildVerticesFromParent(float[] parentVerts, int childIdx, Point3f[] childVerts) {
        // Extract parent vertices
        float p0x = parentVerts[0], p0y = parentVerts[1], p0z = parentVerts[2];
        float p1x = parentVerts[3], p1y = parentVerts[4], p1z = parentVerts[5];
        float p2x = parentVerts[6], p2y = parentVerts[7], p2z = parentVerts[8];
        float p3x = parentVerts[9], p3y = parentVerts[10], p3z = parentVerts[11];

        // Compute edge midpoints
        float m01x = (p0x + p1x) * 0.5f, m01y = (p0y + p1y) * 0.5f, m01z = (p0z + p1z) * 0.5f;
        float m02x = (p0x + p2x) * 0.5f, m02y = (p0y + p2y) * 0.5f, m02z = (p0z + p2z) * 0.5f;
        float m03x = (p0x + p3x) * 0.5f, m03y = (p0y + p3y) * 0.5f, m03z = (p0z + p3z) * 0.5f;
        float m12x = (p1x + p2x) * 0.5f, m12y = (p1y + p2y) * 0.5f, m12z = (p1z + p2z) * 0.5f;
        float m13x = (p1x + p3x) * 0.5f, m13y = (p1y + p3y) * 0.5f, m13z = (p1z + p3z) * 0.5f;
        float m23x = (p2x + p3x) * 0.5f, m23y = (p2y + p3y) * 0.5f, m23z = (p2z + p3z) * 0.5f;

        // Bey subdivision children
        switch (childIdx) {
            case 0 -> { // Corner child at v0
                childVerts[0].set(p0x, p0y, p0z);
                childVerts[1].set(m01x, m01y, m01z);
                childVerts[2].set(m02x, m02y, m02z);
                childVerts[3].set(m03x, m03y, m03z);
            }
            case 1 -> { // Corner child at v1
                childVerts[0].set(p1x, p1y, p1z);
                childVerts[1].set(m01x, m01y, m01z);
                childVerts[2].set(m12x, m12y, m12z);
                childVerts[3].set(m13x, m13y, m13z);
            }
            case 2 -> { // Corner child at v2
                childVerts[0].set(p2x, p2y, p2z);
                childVerts[1].set(m02x, m02y, m02z);
                childVerts[2].set(m12x, m12y, m12z);
                childVerts[3].set(m23x, m23y, m23z);
            }
            case 3 -> { // Corner child at v3
                childVerts[0].set(p3x, p3y, p3z);
                childVerts[1].set(m03x, m03y, m03z);
                childVerts[2].set(m13x, m13y, m13z);
                childVerts[3].set(m23x, m23y, m23z);
            }
            case 4 -> { // Octahedral child 0
                childVerts[0].set(m01x, m01y, m01z);
                childVerts[1].set(m02x, m02y, m02z);
                childVerts[2].set(m03x, m03y, m03z);
                childVerts[3].set(m12x, m12y, m12z);
            }
            case 5 -> { // Octahedral child 1
                childVerts[0].set(m01x, m01y, m01z);
                childVerts[1].set(m02x, m02y, m02z);
                childVerts[2].set(m12x, m12y, m12z);
                childVerts[3].set(m13x, m13y, m13z);
            }
            case 6 -> { // Octahedral child 2
                childVerts[0].set(m02x, m02y, m02z);
                childVerts[1].set(m03x, m03y, m03z);
                childVerts[2].set(m12x, m12y, m12z);
                childVerts[3].set(m23x, m23y, m23z);
            }
            case 7 -> { // Octahedral child 3
                childVerts[0].set(m03x, m03y, m03z);
                childVerts[1].set(m12x, m12y, m12z);
                childVerts[2].set(m13x, m13y, m13z);
                childVerts[3].set(m23x, m23y, m23z);
            }
        }
    }

    /**
     * Get child tetrahedron vertices at given scale.
     *
     * <p>Uses Bey subdivision to compute child vertices from parent.
     * At each level, the tetrahedra are scaled by 0.5.
     */
    private void getChildVertices(int parentType, int childIdx, int scale, Point3f[] verts) {
        // Get parent vertices
        Point3i[] parentStandard = Constants.SIMPLEX_STANDARD[parentType];
        float[] pv0 = {parentStandard[0].x, parentStandard[0].y, parentStandard[0].z};
        float[] pv1 = {parentStandard[1].x, parentStandard[1].y, parentStandard[1].z};
        float[] pv2 = {parentStandard[2].x, parentStandard[2].y, parentStandard[2].z};
        float[] pv3 = {parentStandard[3].x, parentStandard[3].y, parentStandard[3].z};

        // Scale factor for this level (each level halves the size)
        float levelScale = (float) Math.pow(0.5, MAX_DEPTH - 1 - scale);

        // Edge midpoints
        float[] m01 = midpoint(pv0, pv1);
        float[] m02 = midpoint(pv0, pv2);
        float[] m03 = midpoint(pv0, pv3);
        float[] m12 = midpoint(pv1, pv2);
        float[] m13 = midpoint(pv1, pv3);
        float[] m23 = midpoint(pv2, pv3);

        // Bey children vertices (from BeySubdivision)
        switch (childIdx) {
            case 0 -> { // Corner at v0
                setVertex(verts[0], pv0, levelScale);
                setVertex(verts[1], m01, levelScale);
                setVertex(verts[2], m02, levelScale);
                setVertex(verts[3], m03, levelScale);
            }
            case 1 -> { // Corner at v1
                setVertex(verts[0], pv1, levelScale);
                setVertex(verts[1], m01, levelScale);
                setVertex(verts[2], m12, levelScale);
                setVertex(verts[3], m13, levelScale);
            }
            case 2 -> { // Corner at v2
                setVertex(verts[0], pv2, levelScale);
                setVertex(verts[1], m02, levelScale);
                setVertex(verts[2], m12, levelScale);
                setVertex(verts[3], m23, levelScale);
            }
            case 3 -> { // Corner at v3
                setVertex(verts[0], pv3, levelScale);
                setVertex(verts[1], m03, levelScale);
                setVertex(verts[2], m13, levelScale);
                setVertex(verts[3], m23, levelScale);
            }
            case 4 -> { // Octahedral region
                setVertex(verts[0], m01, levelScale);
                setVertex(verts[1], m02, levelScale);
                setVertex(verts[2], m03, levelScale);
                setVertex(verts[3], m12, levelScale);
            }
            case 5 -> {
                setVertex(verts[0], m01, levelScale);
                setVertex(verts[1], m02, levelScale);
                setVertex(verts[2], m12, levelScale);
                setVertex(verts[3], m13, levelScale);
            }
            case 6 -> {
                setVertex(verts[0], m02, levelScale);
                setVertex(verts[1], m03, levelScale);
                setVertex(verts[2], m12, levelScale);
                setVertex(verts[3], m23, levelScale);
            }
            case 7 -> {
                setVertex(verts[0], m03, levelScale);
                setVertex(verts[1], m12, levelScale);
                setVertex(verts[2], m13, levelScale);
                setVertex(verts[3], m23, levelScale);
            }
        }
    }

    private static float[] midpoint(float[] a, float[] b) {
        return new float[]{
            (a[0] + b[0]) / 2,
            (a[1] + b[1]) / 2,
            (a[2] + b[2]) / 2
        };
    }

    private static void setVertex(Point3f vert, float[] coords, float scale) {
        vert.set(coords[0] * scale, coords[1] * scale, coords[2] * scale);
    }

    /**
     * Cast multiple rays (batch processing).
     */
    public ESVTResult[] castRays(ESVTRay[] rays, ESVTNodeUnified[] nodes, int rootIdx) {
        var results = new ESVTResult[rays.length];
        for (int i = 0; i < rays.length; i++) {
            results[i] = castRay(rays[i], nodes, rootIdx);
        }
        return results;
    }

    /**
     * Create a traversal instance for the current thread.
     */
    public static ESVTTraversal create() {
        return new ESVTTraversal();
    }
}
