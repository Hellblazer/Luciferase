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
 * Verbose debug test to trace ray traversal step-by-step
 */
class TraversalDebugVerboseTest {

    @Test
    void testVerboseTraversal() {
        // Build solid cube
        int resolution = 16;
        List<Point3i> voxels = new ArrayList<>();
        for (int x = 0; x < resolution; x++) {
            for (int y = 0; y < resolution; y++) {
                for (int z = 0; z < resolution; z++) {
                    voxels.add(new Point3i(x, y, z));
                }
            }
        }

        var builder = new ESVTBuilder();
        var data = builder.buildFromVoxels(voxels, 4);
        var nodes = data.nodes();

        System.out.printf("Tree: %d nodes, %d leaves, maxDepth=%d%n",
            data.nodeCount(), data.leafCount(), data.maxDepth());

        // Ray through center
        var rayOrigin = new Point3f(-0.1f, 0.5f, 0.5f);
        var rayDir = new Vector3f(1, 0, 0);
        rayDir.normalize();

        var intersector = MollerTrumboreIntersection.create();
        var tetResult = new MollerTrumboreIntersection.TetrahedronResult();

        // Manual traversal with debug output
        System.out.println("\n=== MANUAL TRAVERSAL ===\n");

        // Level 0: Root
        byte rootType = nodes[0].getTetType();
        var s0Verts = Constants.SIMPLEX_STANDARD[rootType];
        var v0 = new Point3f(s0Verts[0].x, s0Verts[0].y, s0Verts[0].z);
        var v1 = new Point3f(s0Verts[1].x, s0Verts[1].y, s0Verts[1].z);
        var v2 = new Point3f(s0Verts[2].x, s0Verts[2].y, s0Verts[2].z);
        var v3 = new Point3f(s0Verts[3].x, s0Verts[3].y, s0Verts[3].z);

        System.out.printf("Root type=%d, verts: v0=%s, v1=%s, v2=%s, v3=%s%n", rootType, v0, v1, v2, v3);
        System.out.printf("Root: childMask=0x%02X, leafMask=0x%02X%n",
            nodes[0].getChildMask(), nodes[0].getLeafMask());

        boolean hitsRoot = intersector.intersectTetrahedron(rayOrigin, rayDir, v0, v1, v2, v3, tetResult);
        System.out.printf("Root intersection: hit=%b, tEntry=%.4f, entryFace=%d%n\n",
            hitsRoot, tetResult.tEntry, tetResult.entryFace);

        assertTrue(hitsRoot, "Ray should hit root");

        // Find first child that ray hits
        int parentIdx = 0;
        byte parentType = rootType;
        int entryFace = tetResult.entryFace;
        float[] parentVerts = new float[12];
        parentVerts[0] = v0.x; parentVerts[1] = v0.y; parentVerts[2] = v0.z;
        parentVerts[3] = v1.x; parentVerts[4] = v1.y; parentVerts[5] = v1.z;
        parentVerts[6] = v2.x; parentVerts[7] = v2.y; parentVerts[8] = v2.z;
        parentVerts[9] = v3.x; parentVerts[10] = v3.y; parentVerts[11] = v3.z;

        Point3f[] childVerts = new Point3f[4];
        for (int i = 0; i < 4; i++) childVerts[i] = new Point3f();

        // Traverse up to 5 levels
        for (int level = 0; level < 5; level++) {
            System.out.printf("--- Level %d: parentIdx=%d, parentType=%d, entryFace=%d ---%n",
                level, parentIdx, parentType, entryFace);

            var node = nodes[parentIdx];
            System.out.printf("Node %d: childMask=0x%02X, leafMask=0x%02X%n",
                parentIdx, node.getChildMask(), node.getLeafMask());

            byte[] childOrder = ESVTChildOrder.getChildOrder(parentType, entryFace);
            System.out.printf("Child order (Bey) for type=%d, face=%d: [%d, %d, %d, %d]%n",
                parentType, entryFace, childOrder[0], childOrder[1], childOrder[2], childOrder[3]);

            boolean foundHit = false;
            for (int pos = 0; pos < 4; pos++) {
                int beyIdx = childOrder[pos];
                int mortonIdx = TetreeConnectivity.BEY_NUMBER_TO_INDEX[parentType][beyIdx];

                System.out.printf("  pos=%d: Bey %d -> Morton %d", pos, beyIdx, mortonIdx);

                if (!node.hasChild(mortonIdx)) {
                    System.out.println(" - NO CHILD");
                    continue;
                }

                computeChildVerticesFromParent(parentVerts, mortonIdx, parentType, childVerts);

                boolean hits = intersector.intersectTetrahedron(rayOrigin, rayDir,
                    childVerts[0], childVerts[1], childVerts[2], childVerts[3], tetResult);

                boolean isLeaf = node.isChildLeaf(mortonIdx);
                int childNodeIdx = node.getChildIndex(mortonIdx);
                byte childType = nodes[childNodeIdx].getTetType();

                System.out.printf(" - hasChild=true, hit=%b, isLeaf=%b, childIdx=%d, childType=%d%n",
                    hits, isLeaf, childNodeIdx, childType);

                if (hits) {
                    System.out.printf("    verts: [%s, %s, %s, %s]%n",
                        childVerts[0], childVerts[1], childVerts[2], childVerts[3]);
                    System.out.printf("    tEntry=%.4f, entryFace=%d%n", tetResult.tEntry, tetResult.entryFace);

                    if (isLeaf) {
                        System.out.printf("*** FOUND LEAF at childIdx=%d, t=%.4f ***%n",
                            childNodeIdx, tetResult.tEntry);
                        return;
                    }

                    // Descend
                    parentIdx = childNodeIdx;
                    parentType = childType;
                    entryFace = tetResult.entryFace;

                    // Update parent verts for next level
                    parentVerts[0] = childVerts[0].x; parentVerts[1] = childVerts[0].y; parentVerts[2] = childVerts[0].z;
                    parentVerts[3] = childVerts[1].x; parentVerts[4] = childVerts[1].y; parentVerts[5] = childVerts[1].z;
                    parentVerts[6] = childVerts[2].x; parentVerts[7] = childVerts[2].y; parentVerts[8] = childVerts[2].z;
                    parentVerts[9] = childVerts[3].x; parentVerts[10] = childVerts[3].y; parentVerts[11] = childVerts[3].z;

                    foundHit = true;
                    break;
                }
            }

            if (!foundHit) {
                System.out.println("!!! No child hit at this level - TRAVERSAL FAILED");
                fail("No child hit at level " + level);
                break;
            }
        }
    }

    private void computeChildVerticesFromParent(float[] parentVerts, int mortonIdx, byte parentType, Point3f[] childVerts) {
        int beyIdx = TetreeConnectivity.INDEX_TO_BEY_NUMBER[parentType][mortonIdx];

        float p0x = parentVerts[0], p0y = parentVerts[1], p0z = parentVerts[2];
        float p1x = parentVerts[3], p1y = parentVerts[4], p1z = parentVerts[5];
        float p2x = parentVerts[6], p2y = parentVerts[7], p2z = parentVerts[8];
        float p3x = parentVerts[9], p3y = parentVerts[10], p3z = parentVerts[11];

        float m01x = (p0x + p1x) * 0.5f, m01y = (p0y + p1y) * 0.5f, m01z = (p0z + p1z) * 0.5f;
        float m02x = (p0x + p2x) * 0.5f, m02y = (p0y + p2y) * 0.5f, m02z = (p0z + p2z) * 0.5f;
        float m03x = (p0x + p3x) * 0.5f, m03y = (p0y + p3y) * 0.5f, m03z = (p0z + p3z) * 0.5f;
        float m12x = (p1x + p2x) * 0.5f, m12y = (p1y + p2y) * 0.5f, m12z = (p1z + p2z) * 0.5f;
        float m13x = (p1x + p3x) * 0.5f, m13y = (p1y + p3y) * 0.5f, m13z = (p1z + p3z) * 0.5f;
        float m23x = (p2x + p3x) * 0.5f, m23y = (p2y + p3y) * 0.5f, m23z = (p2z + p3z) * 0.5f;

        switch (beyIdx) {
            case 0 -> { childVerts[0].set(p0x, p0y, p0z); childVerts[1].set(m01x, m01y, m01z); childVerts[2].set(m02x, m02y, m02z); childVerts[3].set(m03x, m03y, m03z); }
            case 1 -> { childVerts[0].set(m01x, m01y, m01z); childVerts[1].set(p1x, p1y, p1z); childVerts[2].set(m12x, m12y, m12z); childVerts[3].set(m13x, m13y, m13z); }
            case 2 -> { childVerts[0].set(m02x, m02y, m02z); childVerts[1].set(m12x, m12y, m12z); childVerts[2].set(p2x, p2y, p2z); childVerts[3].set(m23x, m23y, m23z); }
            case 3 -> { childVerts[0].set(m03x, m03y, m03z); childVerts[1].set(m13x, m13y, m13z); childVerts[2].set(m23x, m23y, m23z); childVerts[3].set(p3x, p3y, p3z); }
            case 4 -> { childVerts[0].set(m01x, m01y, m01z); childVerts[1].set(m02x, m02y, m02z); childVerts[2].set(m03x, m03y, m03z); childVerts[3].set(m13x, m13y, m13z); }
            case 5 -> { childVerts[0].set(m01x, m01y, m01z); childVerts[1].set(m02x, m02y, m02z); childVerts[2].set(m12x, m12y, m12z); childVerts[3].set(m13x, m13y, m13z); }
            case 6 -> { childVerts[0].set(m02x, m02y, m02z); childVerts[1].set(m03x, m03y, m03z); childVerts[2].set(m13x, m13y, m13z); childVerts[3].set(m23x, m23y, m23z); }
            case 7 -> { childVerts[0].set(m02x, m02y, m02z); childVerts[1].set(m12x, m12y, m12z); childVerts[2].set(m13x, m13y, m13z); childVerts[3].set(m23x, m23y, m23z); }
        }
    }
}
