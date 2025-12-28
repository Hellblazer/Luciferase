/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.esvt.traversal;

import com.hellblazer.luciferase.esvt.builder.ESVTBuilder;
import com.hellblazer.luciferase.esvt.core.ESVTNodeUnified;
import com.hellblazer.luciferase.geometry.Point3i;
import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.tetree.TetreeConnectivity;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Debug test to trace through ray traversal step by step
 */
class ESVTTraversalDebugTest {

    @Test
    void testRayTraversalStepByStep() {
        System.out.println("=== STEP BY STEP RAY TRAVERSAL DEBUG ===\n");

        // Build test cube
        var builder = new ESVTBuilder();
        List<Point3i> voxels = new ArrayList<>();
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    voxels.add(new Point3i(x, y, z));
                }
            }
        }
        var data = builder.buildFromVoxels(voxels, 4);
        var nodes = data.nodes();

        System.out.printf("Tree: %d nodes, %d leaves%n", data.nodeCount(), data.leafCount());

        // Test ray
        var rayOrigin = new Point3f(-0.1f, 0.5f, 0.5f);
        var rayDir = new Vector3f(1, 0, 0);
        rayDir.normalize();

        // Test root intersection
        var intersector = MollerTrumboreIntersection.create();
        var tetResult = new MollerTrumboreIntersection.TetrahedronResult();

        var s0Verts = Constants.SIMPLEX_STANDARD[0];
        var rootV0 = new Point3f(s0Verts[0].x, s0Verts[0].y, s0Verts[0].z);
        var rootV1 = new Point3f(s0Verts[1].x, s0Verts[1].y, s0Verts[1].z);
        var rootV2 = new Point3f(s0Verts[2].x, s0Verts[2].y, s0Verts[2].z);
        var rootV3 = new Point3f(s0Verts[3].x, s0Verts[3].y, s0Verts[3].z);

        System.out.printf("Root S0 vertices: v0=%s, v1=%s, v2=%s, v3=%s%n", rootV0, rootV1, rootV2, rootV3);

        boolean hitsRoot = intersector.intersectTetrahedron(rayOrigin, rayDir, rootV0, rootV1, rootV2, rootV3, tetResult);
        System.out.printf("Root intersection: hit=%b, tEntry=%.4f, tExit=%.4f, entryFace=%d%n%n",
            hitsRoot, tetResult.tEntry, tetResult.tExit, tetResult.entryFace);

        assertTrue(hitsRoot, "Ray should hit root");

        // Now manually traverse first level
        byte rootType = nodes[0].getTetType();
        int entryFace = tetResult.entryFace;
        byte[] childOrderBey = ESVTChildOrder.getChildOrder(rootType, entryFace);

        System.out.printf("Entry face: %d%n", entryFace);
        System.out.printf("Child order (Bey): [%d, %d, %d, %d]%n",
            childOrderBey[0], childOrderBey[1], childOrderBey[2], childOrderBey[3]);

        // Store root vertices for child computation
        float[] parentVerts = new float[12];
        parentVerts[0] = rootV0.x; parentVerts[1] = rootV0.y; parentVerts[2] = rootV0.z;
        parentVerts[3] = rootV1.x; parentVerts[4] = rootV1.y; parentVerts[5] = rootV1.z;
        parentVerts[6] = rootV2.x; parentVerts[7] = rootV2.y; parentVerts[8] = rootV2.z;
        parentVerts[9] = rootV3.x; parentVerts[10] = rootV3.y; parentVerts[11] = rootV3.z;

        Point3f[] childVerts = new Point3f[4];
        for (int i = 0; i < 4; i++) childVerts[i] = new Point3f();

        System.out.println("\nTesting children at entry face:");
        int childrenTested = 0;
        int childrenHit = 0;

        for (int pos = 0; pos < 4; pos++) {
            int beyIdx = childOrderBey[pos];
            int mortonIdx = TetreeConnectivity.BEY_NUMBER_TO_INDEX[rootType][beyIdx];

            System.out.printf("\n  Position %d: Bey %d -> Morton %d%n", pos, beyIdx, mortonIdx);

            boolean hasChild = nodes[0].hasChild(mortonIdx);
            System.out.printf("    hasChild(morton=%d) = %b%n", mortonIdx, hasChild);

            if (!hasChild) {
                continue;
            }

            childrenTested++;

            // Compute child vertices (manually matching getChildVerticesFromParent logic)
            computeChildVertices(parentVerts, mortonIdx, rootType, childVerts);

            System.out.printf("    Child verts: v0=%s, v1=%s, v2=%s, v3=%s%n",
                childVerts[0], childVerts[1], childVerts[2], childVerts[3]);

            // Test intersection
            boolean hits = intersector.intersectTetrahedron(rayOrigin, rayDir,
                childVerts[0], childVerts[1], childVerts[2], childVerts[3], tetResult);

            System.out.printf("    Intersection: hit=%b, tEntry=%.4f, tExit=%.4f%n",
                hits, tetResult.tEntry, tetResult.tExit);

            if (hits) {
                childrenHit++;
                // Check if leaf
                boolean isLeaf = nodes[0].isChildLeaf(mortonIdx);
                int childNodeIdx = nodes[0].getChildIndex(mortonIdx);
                System.out.printf("    isLeaf=%b, childNodeIdx=%d%n", isLeaf, childNodeIdx);
            }
        }

        System.out.printf("\nSummary: tested=%d, hit=%d%n", childrenTested, childrenHit);

        assertTrue(childrenHit > 0, "At least one child should be hit by ray");
    }

    /**
     * Replicate getChildVerticesFromParent logic for debugging
     */
    private void computeChildVertices(float[] parentVerts, int mortonIdx, byte parentType, Point3f[] childVerts) {
        // Convert Morton to Bey
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

        // Same switch as ESVTTraversal.getChildVerticesFromParent
        switch (beyIdx) {
            case 0 -> {
                childVerts[0].set(p0x, p0y, p0z);
                childVerts[1].set(m01x, m01y, m01z);
                childVerts[2].set(m02x, m02y, m02z);
                childVerts[3].set(m03x, m03y, m03z);
            }
            case 1 -> {
                childVerts[0].set(m01x, m01y, m01z);
                childVerts[1].set(p1x, p1y, p1z);
                childVerts[2].set(m12x, m12y, m12z);
                childVerts[3].set(m13x, m13y, m13z);
            }
            case 2 -> {
                childVerts[0].set(m02x, m02y, m02z);
                childVerts[1].set(m12x, m12y, m12z);
                childVerts[2].set(p2x, p2y, p2z);
                childVerts[3].set(m23x, m23y, m23z);
            }
            case 3 -> {
                childVerts[0].set(m03x, m03y, m03z);
                childVerts[1].set(m13x, m13y, m13z);
                childVerts[2].set(m23x, m23y, m23z);
                childVerts[3].set(p3x, p3y, p3z);
            }
            case 4 -> {
                childVerts[0].set(m01x, m01y, m01z);
                childVerts[1].set(m02x, m02y, m02z);
                childVerts[2].set(m03x, m03y, m03z);
                childVerts[3].set(m13x, m13y, m13z);
            }
            case 5 -> {
                childVerts[0].set(m01x, m01y, m01z);
                childVerts[1].set(m02x, m02y, m02z);
                childVerts[2].set(m12x, m12y, m12z);
                childVerts[3].set(m13x, m13y, m13z);
            }
            case 6 -> {
                childVerts[0].set(m02x, m02y, m02z);
                childVerts[1].set(m03x, m03y, m03z);
                childVerts[2].set(m13x, m13y, m13z);
                childVerts[3].set(m23x, m23y, m23z);
            }
            case 7 -> {
                childVerts[0].set(m02x, m02y, m02z);
                childVerts[1].set(m12x, m12y, m12z);
                childVerts[2].set(m13x, m13y, m13z);
                childVerts[3].set(m23x, m23y, m23z);
            }
        }
    }
}
