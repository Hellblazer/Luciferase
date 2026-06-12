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

import com.hellblazer.luciferase.esvt.core.ESVTContour;
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
    private final MollerTrumboreIntersection.AABBResult aabbResult;
    private final ESVTStack stack;

    // Scratch space for vertex calculations
    private final Point3f[] scratchVerts = new Point3f[4];

    // Current tetrahedron vertices (tracked during traversal)
    private final float[] currentVerts = new float[12]; // 4 vertices * 3 coords

    // Scratch space for contour refinement
    private final Vector3f contourRayOrigin = new Vector3f();
    private final Vector3f contourRayDir = new Vector3f();
    private final Vector3f contourNormal = new Vector3f();

    /**
     * Create a new traversal instance.
     * Each instance has its own scratch space for thread safety.
     */
    public ESVTTraversal() {
        this.intersector = MollerTrumboreIntersection.create();
        this.tetResult = new MollerTrumboreIntersection.TetrahedronResult();
        this.aabbResult = new MollerTrumboreIntersection.AABBResult();
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
        return castRay(ray, nodes, null, null, rootIdx);
    }

    /**
     * Cast a ray through the ESVT structure with contour refinement.
     *
     * @param ray The ray to cast (origin, direction)
     * @param nodes Array of ESVT nodes
     * @param contours Array of contour data (may be null for no refinement)
     * @param rootIdx Index of root node (usually 0)
     * @return Traversal result with hit information
     */
    public ESVTResult castRay(ESVTRay ray, ESVTNodeUnified[] nodes, int[] contours, int rootIdx) {
        return castRay(ray, nodes, contours, null, rootIdx);
    }

    /**
     * Cast a ray through the ESVT structure with contour refinement and far pointer support.
     *
     * @param ray The ray to cast (origin, direction)
     * @param nodes Array of ESVT nodes
     * @param contours Array of contour data (may be null for no refinement)
     * @param farPointers Array of far pointers for large trees (may be null)
     * @param rootIdx Index of root node (usually 0)
     * @return Traversal result with hit information
     */
    public ESVTResult castRay(ESVTRay ray, ESVTNodeUnified[] nodes, int[] contours, int[] farPointers, int rootIdx) {
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
        // NOTE: These are used for CHILD vertex computation (Bey subdivision),
        // NOT for root bounds testing. The root covers the full [0,1]³ cube.
        getRootVertices(rootType, scratchVerts);

        // Initialize current vertices from root
        currentVerts[0] = scratchVerts[0].x; currentVerts[1] = scratchVerts[0].y; currentVerts[2] = scratchVerts[0].z;
        currentVerts[3] = scratchVerts[1].x; currentVerts[4] = scratchVerts[1].y; currentVerts[5] = scratchVerts[1].z;
        currentVerts[6] = scratchVerts[2].x; currentVerts[7] = scratchVerts[2].y; currentVerts[8] = scratchVerts[2].z;
        currentVerts[9] = scratchVerts[3].x; currentVerts[10] = scratchVerts[3].y; currentVerts[11] = scratchVerts[3].z;

        // =========================================================================
        // ROOT INTERSECTION: Use UNIT CUBE [0,1]³, not tetrahedron!
        // =========================================================================
        // The Tetree uses CUBIC octant subdivision internally (not Bey tetrahedron
        // subdivision at root). The root "type" describes orientation for child
        // subdivision, but spatial coverage is the FULL cube, not just 1/6 of it.
        if (!intersector.intersectUnitCube(rayOrigin, rayDir, aabbResult)) {
            return result;
        }

        // Ray hits cube - start traversal
        int parentIdx = rootIdx;
        byte parentType = rootType;
        // entryFace is kept for child-descent context but no longer drives child selection:
        // the all-8 scan below iterates all Morton children regardless of entry face.
        int entryFace = 0;
        float tMax = aabbResult.tExit;
        int scale = MAX_DEPTH - 1;
        int iterations = 0;

        // Global best hit — preserved across the entire DFS including pop/resume.
        // Only updated when a leaf's refined t is strictly less than bestT.
        // Returned after stack exhaustion (mirrors .cl bestT semantics).
        var bestResult = new ESVTResult();
        float bestT = Float.MAX_VALUE;

        // front-to-back: sorted position (index into sorted order per level)
        int sortedPos = 0;

        // Scratch arrays for front-to-back child collection and insertion sort (max 8 children).
        // Sorted in tandem: after the sort, slot i holds the i-th nearest child's (tEntry, childIdx).
        final float[] candidateTEntry = new float[8];
        final int[] candidateIdx = new int[8];

        while (scale < MAX_DEPTH && iterations < MAX_ITERATIONS) {
            iterations++;

            // -----------------------------------------------------------------------
            // Front-to-back: collect intersecting children, insertion-sort by tEntry,
            // visit in sorted order, prune when next tEntry >= bestT.
            //
            // On first entry to this node: sortedPos == 0, collect all candidates.
            // On resume (after pop): sortedPos > 0, re-derive the next child in order.
            // -----------------------------------------------------------------------
            var currentNode = nodes[parentIdx];
            if (!currentNode.isValid()) {
                // Invalid node — pop
                if (scale >= MAX_DEPTH - 1) break;
                scale++;
                if (!stack.hasEntry(scale)) break;
                parentIdx = stack.readNode(scale);
                parentType = stack.readType(scale);
                entryFace = stack.readEntryFace(scale);
                tMax = stack.readTmax(scale);
                sortedPos = stack.readSiblingPos(scale);
                float[] restoredVerts = stack.readVerts(scale);
                if (restoredVerts != null) {
                    System.arraycopy(restoredVerts, 0, currentVerts, 0, 12);
                }
                continue;
            }

            boolean descended = false;

            // Determine the packed sorted order for this level.
            // If sortedPos == 0 we are entering this node fresh: collect + sort.
            // If sortedPos > 0 we are resuming: use the stored packed order.
            int packedOrder;
            int childCount;

            if (sortedPos == 0) {
                // ----- COLLECT: scan all 8 Morton children, compute intersections -----
                int nCandidates = 0;
                for (int childIdx = 0; childIdx < 8; childIdx++) {
                    if (!currentNode.hasChild(childIdx)) {
                        continue;
                    }
                    // getChildVerticesFromParent: Morton→Bey internally (INDEX_TO_BEY_NUMBER)
                    getChildVerticesFromParent(currentVerts, childIdx, parentType, scratchVerts);
                    if (!intersector.intersectTetrahedron(rayOrigin, rayDir,
                            scratchVerts[0], scratchVerts[1], scratchVerts[2], scratchVerts[3],
                            tetResult)) {
                        continue;
                    }
                    candidateTEntry[nCandidates] = tetResult.tEntry;
                    candidateIdx[nCandidates] = childIdx;
                    nCandidates++;
                }

                // ----- INSERTION SORT ascending by tEntry, tiebreak childIdx ascending -----
                // Direct tandem sort of the two parallel arrays; max 8 elements.
                // P3 mirrors this exact shape in .cl/.comp: same loop, same comparator.
                for (int i = 1; i < nCandidates; i++) {
                    float ti = candidateTEntry[i];
                    int ci = candidateIdx[i];
                    int j = i - 1;
                    while (j >= 0 && (candidateTEntry[j] > ti
                                      || (candidateTEntry[j] == ti && candidateIdx[j] > ci))) {
                        candidateTEntry[j + 1] = candidateTEntry[j];
                        candidateIdx[j + 1] = candidateIdx[j];
                        j--;
                    }
                    candidateTEntry[j + 1] = ti;
                    candidateIdx[j + 1] = ci;
                }

                childCount = nCandidates;
                // Pack the sorted childIdx order into the sorted-order word
                packedOrder = ESVTStack.packSortedOrder(childCount, candidateIdx);

                // ----- VISIT in sorted order, PRUNE when tEntry >= bestT -----
                boolean descendedInner = false;
                for (int si = 0; si < childCount; si++) {
                    int childIdx = candidateIdx[si];
                    float childTEntry = candidateTEntry[si];

                    // front-to-back: prune — next ordered child's tEntry >= bestT → BREAK
                    // (sorted ⇒ all remaining also >= bestT, cannot improve result)
                    if (childTEntry >= bestT) {
                        break; // prune: next tEntry >= bestT, break
                    }

                    // Re-derive vertices and full intersection result for this child
                    getChildVerticesFromParent(currentVerts, childIdx, parentType, scratchVerts);
                    if (!intersector.intersectTetrahedron(rayOrigin, rayDir,
                            scratchVerts[0], scratchVerts[1], scratchVerts[2], scratchVerts[3],
                            tetResult)) {
                        // No longer intersects (shouldn't happen since we collected above, but be safe)
                        continue;
                    }
                    int childEntryFace = tetResult.entryFace;

                    if (currentNode.isChildLeaf(childIdx)) {
                        int childNodeIdx = currentNode.getChildIndex(childIdx, parentIdx, farPointers);
                        var childNode = (childNodeIdx >= 0 && childNodeIdx < nodes.length)
                            ? nodes[childNodeIdx] : null;

                        float refinedT = childTEntry;
                        Vector3f refinedNormal = null;

                        if (contours != null && childNode != null && childNode.hasContour()) {
                            int contourMask = childNode.getContourMask();
                            if ((contourMask & (1 << childEntryFace)) != 0) {
                                int contourPtr = childNode.getContourPtr();
                                int contourOffset = Integer.bitCount(contourMask & ((1 << childEntryFace) - 1));
                                int contourIdx = contourPtr + contourOffset;

                                if (contourIdx >= 0 && contourIdx < contours.length) {
                                    int contour = contours[contourIdx];
                                    float tetScale = (float) Math.pow(0.5, MAX_DEPTH - 1 - scale);
                                    contourRayOrigin.set(rayOrigin.x, rayOrigin.y, rayOrigin.z);
                                    contourRayDir.set(rayDir.x, rayDir.y, rayDir.z);
                                    float[] contourHit = ESVTContour.intersectRay(
                                        contour, contourRayOrigin, contourRayDir, tetScale);

                                    if (contourHit == null) {
                                        // Contour-invalid: continue to next child (do NOT abort subtree)
                                        continue;
                                    }

                                    var decoded = ESVTContour.decodeNormal(contour);
                                    float[] posThick = ESVTContour.decodePosThick(contour);
                                    float contourPos = posThick[0] * tetScale;
                                    float denom = decoded.x * rayDir.x + decoded.y * rayDir.y + decoded.z * rayDir.z;
                                    if (Math.abs(denom) > 1e-10f) {
                                        float originDot = decoded.x * rayOrigin.x + decoded.y * rayOrigin.y + decoded.z * rayOrigin.z;
                                        float newT = (contourPos - originDot) / denom;
                                        if (newT >= childTEntry && newT <= tetResult.tExit) {
                                            refinedT = newT;
                                            refinedNormal = decoded;
                                            refinedNormal.normalize();
                                            if (refinedNormal.x * rayDir.x + refinedNormal.y * rayDir.y + refinedNormal.z * rayDir.z > 0) {
                                                refinedNormal.negate();
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Global min-t: only update bestResult if this hit is closer.
                        if (refinedT < bestT) {
                            bestT = refinedT;
                            byte childType = childNode != null ? childNode.getTetType() : 0;
                            bestResult.setHit(refinedT,
                                rayOrigin.x + refinedT * rayDir.x,
                                rayOrigin.y + refinedT * rayDir.y,
                                rayOrigin.z + refinedT * rayDir.z,
                                parentIdx, childIdx, childType,
                                (byte) childEntryFace, scale);
                            bestResult.exitFace = (byte) tetResult.exitFace;
                            if (refinedNormal != null) {
                                bestResult.normal = refinedNormal;
                            }
                        }
                        // Continue to check other children at same level (may find closer hit)
                        continue;
                    }

                    // Non-leaf: fail loud on OOB before any state mutation
                    int childNodeIdx = currentNode.getChildIndex(childIdx, parentIdx, farPointers);
                    if (childNodeIdx < 0 || childNodeIdx >= nodes.length) {
                        throw new IllegalStateException(
                            "child pointer out of bounds: " + childNodeIdx
                            + " (node " + parentIdx + ", child " + childIdx
                            + ", nodes.length " + nodes.length + ")");
                    }

                    // Push current state; store packed sorted order + next resume position
                    stack.write(scale, parentIdx, tMax, parentType, (byte) entryFace);
                    // Resume position = si+1 (next slot in sorted order after this non-leaf descent)
                    stack.writeSiblingPos(scale, (byte) (si + 1));
                    stack.writeSortedOrder(scale, packedOrder);
                    stack.writeVerts(scale,
                        currentVerts[0], currentVerts[1], currentVerts[2],
                        currentVerts[3], currentVerts[4], currentVerts[5],
                        currentVerts[6], currentVerts[7], currentVerts[8],
                        currentVerts[9], currentVerts[10], currentVerts[11]);

                    // Descend
                    currentVerts[0] = scratchVerts[0].x; currentVerts[1] = scratchVerts[0].y; currentVerts[2] = scratchVerts[0].z;
                    currentVerts[3] = scratchVerts[1].x; currentVerts[4] = scratchVerts[1].y; currentVerts[5] = scratchVerts[1].z;
                    currentVerts[6] = scratchVerts[2].x; currentVerts[7] = scratchVerts[2].y; currentVerts[8] = scratchVerts[2].z;
                    currentVerts[9] = scratchVerts[3].x; currentVerts[10] = scratchVerts[3].y; currentVerts[11] = scratchVerts[3].z;

                    parentIdx = childNodeIdx;
                    parentType = nodes[childNodeIdx].getTetType();
                    entryFace = childEntryFace >= 0 ? childEntryFace : 0;
                    tMax = tetResult.tExit;
                    scale--;
                    sortedPos = 0;
                    descendedInner = true;
                    descended = true;
                    break;
                }
                descended = descendedInner;

            } else {
                // ----- RESUME: we popped back to this node; continue sorted-order visit -----
                // Restore the packed sorted order (already read above during pop restore or passed through)
                packedOrder = stack.readSortedOrder(scale);
                childCount = ESVTStack.sortedCount(packedOrder);

                boolean descendedResume = false;
                for (int si = sortedPos; si < childCount; si++) {
                    int childIdx = ESVTStack.sortedChildAt(packedOrder, si);

                    // Re-derive vertices + re-intersect (per design: resume re-intersects, no stored tEntry)
                    getChildVerticesFromParent(currentVerts, childIdx, parentType, scratchVerts);
                    if (!intersector.intersectTetrahedron(rayOrigin, rayDir,
                            scratchVerts[0], scratchVerts[1], scratchVerts[2], scratchVerts[3],
                            tetResult)) {
                        continue;
                    }
                    float childTEntry = tetResult.tEntry;
                    int childEntryFace = tetResult.entryFace;

                    // front-to-back: prune — next ordered child's tEntry >= bestT → BREAK
                    if (childTEntry >= bestT) {
                        break; // prune: next tEntry >= bestT, break
                    }

                    if (currentNode.isChildLeaf(childIdx)) {
                        int childNodeIdx = currentNode.getChildIndex(childIdx, parentIdx, farPointers);
                        var childNode = (childNodeIdx >= 0 && childNodeIdx < nodes.length)
                            ? nodes[childNodeIdx] : null;

                        float refinedT = childTEntry;
                        Vector3f refinedNormal = null;

                        if (contours != null && childNode != null && childNode.hasContour()) {
                            int contourMask = childNode.getContourMask();
                            if ((contourMask & (1 << childEntryFace)) != 0) {
                                int contourPtr = childNode.getContourPtr();
                                int contourOffset = Integer.bitCount(contourMask & ((1 << childEntryFace) - 1));
                                int contourIdx = contourPtr + contourOffset;

                                if (contourIdx >= 0 && contourIdx < contours.length) {
                                    int contour = contours[contourIdx];
                                    float tetScale = (float) Math.pow(0.5, MAX_DEPTH - 1 - scale);
                                    contourRayOrigin.set(rayOrigin.x, rayOrigin.y, rayOrigin.z);
                                    contourRayDir.set(rayDir.x, rayDir.y, rayDir.z);
                                    float[] contourHit = ESVTContour.intersectRay(
                                        contour, contourRayOrigin, contourRayDir, tetScale);

                                    if (contourHit == null) {
                                        // Contour-invalid: continue to next child (do NOT abort subtree)
                                        continue;
                                    }

                                    var decoded = ESVTContour.decodeNormal(contour);
                                    float[] posThick = ESVTContour.decodePosThick(contour);
                                    float contourPos = posThick[0] * tetScale;
                                    float denom = decoded.x * rayDir.x + decoded.y * rayDir.y + decoded.z * rayDir.z;
                                    if (Math.abs(denom) > 1e-10f) {
                                        float originDot = decoded.x * rayOrigin.x + decoded.y * rayOrigin.y + decoded.z * rayOrigin.z;
                                        float newT = (contourPos - originDot) / denom;
                                        if (newT >= childTEntry && newT <= tetResult.tExit) {
                                            refinedT = newT;
                                            refinedNormal = decoded;
                                            refinedNormal.normalize();
                                            if (refinedNormal.x * rayDir.x + refinedNormal.y * rayDir.y + refinedNormal.z * rayDir.z > 0) {
                                                refinedNormal.negate();
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (refinedT < bestT) {
                            bestT = refinedT;
                            byte childType = childNode != null ? childNode.getTetType() : 0;
                            bestResult.setHit(refinedT,
                                rayOrigin.x + refinedT * rayDir.x,
                                rayOrigin.y + refinedT * rayDir.y,
                                rayOrigin.z + refinedT * rayDir.z,
                                parentIdx, childIdx, childType,
                                (byte) childEntryFace, scale);
                            bestResult.exitFace = (byte) tetResult.exitFace;
                            if (refinedNormal != null) {
                                bestResult.normal = refinedNormal;
                            }
                        }
                        continue;
                    }

                    // Non-leaf: fail loud on OOB before any state mutation
                    int childNodeIdx = currentNode.getChildIndex(childIdx, parentIdx, farPointers);
                    if (childNodeIdx < 0 || childNodeIdx >= nodes.length) {
                        throw new IllegalStateException(
                            "child pointer out of bounds: " + childNodeIdx
                            + " (node " + parentIdx + ", child " + childIdx
                            + ", nodes.length " + nodes.length + ")");
                    }

                    // Push: store resume at si+1
                    stack.write(scale, parentIdx, tMax, parentType, (byte) entryFace);
                    stack.writeSiblingPos(scale, (byte) (si + 1));
                    stack.writeSortedOrder(scale, packedOrder);
                    stack.writeVerts(scale,
                        currentVerts[0], currentVerts[1], currentVerts[2],
                        currentVerts[3], currentVerts[4], currentVerts[5],
                        currentVerts[6], currentVerts[7], currentVerts[8],
                        currentVerts[9], currentVerts[10], currentVerts[11]);

                    currentVerts[0] = scratchVerts[0].x; currentVerts[1] = scratchVerts[0].y; currentVerts[2] = scratchVerts[0].z;
                    currentVerts[3] = scratchVerts[1].x; currentVerts[4] = scratchVerts[1].y; currentVerts[5] = scratchVerts[1].z;
                    currentVerts[6] = scratchVerts[2].x; currentVerts[7] = scratchVerts[2].y; currentVerts[8] = scratchVerts[2].z;
                    currentVerts[9] = scratchVerts[3].x; currentVerts[10] = scratchVerts[3].y; currentVerts[11] = scratchVerts[3].z;

                    parentIdx = childNodeIdx;
                    parentType = nodes[childNodeIdx].getTetType();
                    entryFace = childEntryFace >= 0 ? childEntryFace : 0;
                    tMax = tetResult.tExit;
                    scale--;
                    sortedPos = 0;
                    descendedResume = true;
                    descended = true;
                    break;
                }
                descended = descendedResume;
            }

            if (!descended) {
                // No more children to visit — pop to parent
                if (scale >= MAX_DEPTH - 1) {
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

                // Resume at the stored sorted position for this level
                sortedPos = stack.readSiblingPos(scale);
            }
        }

        // Return global best hit after stack exhaustion (bestT preserved across all pop/resume)
        bestResult.iterations = iterations;
        return bestResult;
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
     * <p><b>Important:</b> Child indices in the tree are Morton-ordered, but vertex
     * computation requires Bey ordering. This method converts Morton to Bey internally
     * using TetreeConnectivity.INDEX_TO_BEY_NUMBER.
     *
     * @param parentVerts Parent vertices as float[12] (v0.xyz, v1.xyz, v2.xyz, v3.xyz)
     * @param mortonIdx Morton-ordered child index (0-7) from tree traversal
     * @param parentType Type of parent tetrahedron (0-5) for Morton-to-Bey conversion
     * @param childVerts Output array for child vertex positions
     */
    private void getChildVerticesFromParent(float[] parentVerts, int mortonIdx, byte parentType, Point3f[] childVerts) {
        // Convert Morton index to Bey index using type-dependent lookup
        int beyIdx = TetreeConnectivity.INDEX_TO_BEY_NUMBER[parentType][mortonIdx];

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

        // Bey subdivision children (using Bey index, not Morton index)
        // Reference: BeySubdivision.java subdivide() method
        // Corner children (0-3): corner vertex is at position 0 to match traversal expectations
        // Octahedral children (4-7): vertices selected from edge midpoints
        switch (beyIdx) {
            case 0 -> { // Corner child at v0: vertices [v0, m01, m02, m03]
                childVerts[0].set(p0x, p0y, p0z);
                childVerts[1].set(m01x, m01y, m01z);
                childVerts[2].set(m02x, m02y, m02z);
                childVerts[3].set(m03x, m03y, m03z);
            }
            case 1 -> { // T1 = [x01, x1, x12, x13] - anchor at m01, corner v1 at position 1
                childVerts[0].set(m01x, m01y, m01z);
                childVerts[1].set(p1x, p1y, p1z);
                childVerts[2].set(m12x, m12y, m12z);
                childVerts[3].set(m13x, m13y, m13z);
            }
            case 2 -> { // T2 = [x02, x12, x2, x23] - anchor at m02, corner v2 at position 2
                childVerts[0].set(m02x, m02y, m02z);
                childVerts[1].set(m12x, m12y, m12z);
                childVerts[2].set(p2x, p2y, p2z);
                childVerts[3].set(m23x, m23y, m23z);
            }
            case 3 -> { // T3 = [x03, x13, x23, x3] - anchor at m03, corner v3 at position 3
                childVerts[0].set(m03x, m03y, m03z);
                childVerts[1].set(m13x, m13y, m13z);
                childVerts[2].set(m23x, m23y, m23z);
                childVerts[3].set(p3x, p3y, p3z);
            }
            case 4 -> { // Octahedral: T4 = [x01, x02, x03, x13] (fixed: was m12, should be m13)
                childVerts[0].set(m01x, m01y, m01z);
                childVerts[1].set(m02x, m02y, m02z);
                childVerts[2].set(m03x, m03y, m03z);
                childVerts[3].set(m13x, m13y, m13z);
            }
            case 5 -> { // Octahedral: T5 = [x01, x02, x12, x13]
                childVerts[0].set(m01x, m01y, m01z);
                childVerts[1].set(m02x, m02y, m02z);
                childVerts[2].set(m12x, m12y, m12z);
                childVerts[3].set(m13x, m13y, m13z);
            }
            case 6 -> { // Octahedral: T6 = [x02, x03, x13, x23] (fixed: was m12, should be m13)
                childVerts[0].set(m02x, m02y, m02z);
                childVerts[1].set(m03x, m03y, m03z);
                childVerts[2].set(m13x, m13y, m13z);
                childVerts[3].set(m23x, m23y, m23z);
            }
            case 7 -> { // Octahedral: T7 = [x02, x12, x13, x23] (fixed: was m03, should be m02)
                childVerts[0].set(m02x, m02y, m02z);
                childVerts[1].set(m12x, m12y, m12z);
                childVerts[2].set(m13x, m13y, m13z);
                childVerts[3].set(m23x, m23y, m23z);
            }
        }
    }


    /**
     * Cast multiple rays (batch processing).
     */
    public ESVTResult[] castRays(ESVTRay[] rays, ESVTNodeUnified[] nodes, int rootIdx) {
        return castRays(rays, nodes, null, null, rootIdx);
    }

    /**
     * Cast multiple rays with contour refinement and far pointer support (batch processing).
     */
    public ESVTResult[] castRays(ESVTRay[] rays, ESVTNodeUnified[] nodes, int[] contours, int[] farPointers, int rootIdx) {
        var results = new ESVTResult[rays.length];
        for (int i = 0; i < rays.length; i++) {
            results[i] = castRay(rays[i], nodes, contours, farPointers, rootIdx);
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
