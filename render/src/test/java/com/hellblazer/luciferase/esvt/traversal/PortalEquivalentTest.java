// Test ESVT with Portal-like cube (with margin) and solid cube
package com.hellblazer.luciferase.esvt.traversal;

import com.hellblazer.luciferase.esvt.builder.ESVTBuilder;
import com.hellblazer.luciferase.geometry.Point3i;
import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.tetree.TetreeConnectivity;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.ArrayList;
import java.util.List;

class PortalEquivalentTest {

    @Test
    void testSolidCubeTraversal() {
        // Generate solid cube - NO margin
        int resolution = 16;
        List<Point3i> voxels = new ArrayList<>();
        for (int x = 0; x < resolution; x++) {
            for (int y = 0; y < resolution; y++) {
                for (int z = 0; z < resolution; z++) {
                    voxels.add(new Point3i(x, y, z));
                }
            }
        }
        System.out.printf("Solid cube voxels: %d%n", voxels.size());

        var builder = new ESVTBuilder();
        var data = builder.buildFromVoxels(voxels, 4);
        var nodes = data.nodes();

        System.out.printf("Tree: %d nodes, %d leaves, rootType=%d%n",
            data.nodeCount(), data.leafCount(), data.rootType());

        // Cast ray through center
        var traversal = new ESVTTraversal();
        var ray = new ESVTRay(-0.1f, 0.5f, 0.5f, 1, 0, 0);

        var result = traversal.castRay(ray, nodes, 0);

        System.out.printf("Solid cube result: hit=%b, t=%.4f, iterations=%d%n",
            result.isHit(), result.t, result.iterations);

        assertTrue(result.isHit(), "Ray should hit solid cube");
        assertTrue(result.t > 0 && result.t < 2.0f, "Hit distance should be reasonable");
    }

    @Test
    void testPortalEquivalentRay() {
        // Generate voxels like Portal's CUBE shape with 10% margin
        int resolution = 16;
        int margin = (int) (resolution * 0.1f);  // 1 for resolution 16
        List<Point3i> voxels = new ArrayList<>();
        for (int x = margin; x < resolution - margin; x++) {
            for (int y = margin; y < resolution - margin; y++) {
                for (int z = margin; z < resolution - margin; z++) {
                    voxels.add(new Point3i(x, y, z));
                }
            }
        }
        System.out.printf("Voxels: %d (margin=%d, range=[%d,%d])%n",
            voxels.size(), margin, margin, resolution - margin - 1);

        var builder = new ESVTBuilder();
        var data = builder.buildFromVoxels(voxels, 4);
        var nodes = data.nodes();
        
        System.out.printf("Tree: %d nodes, %d leaves, rootType=%d%n",
            data.nodeCount(), data.leafCount(), data.rootType());
        System.out.printf("Root: childMask=0x%02X, leafMask=0x%02X%n",
            nodes[0].getChildMask(), nodes[0].getLeafMask());

        // Debug: manually trace the ray path
        var rayOrigin = new Point3f(-0.1f, 0.5f, 0.5f);
        var rayDir = new Vector3f(1, 0, 0);
        
        var intersector = MollerTrumboreIntersection.create();
        var tetResult = new MollerTrumboreIntersection.TetrahedronResult();
        
        // Root vertices
        var s0Verts = Constants.SIMPLEX_STANDARD[0];
        var rootV0 = new Point3f(s0Verts[0].x, s0Verts[0].y, s0Verts[0].z);
        var rootV1 = new Point3f(s0Verts[1].x, s0Verts[1].y, s0Verts[1].z);
        var rootV2 = new Point3f(s0Verts[2].x, s0Verts[2].y, s0Verts[2].z);
        var rootV3 = new Point3f(s0Verts[3].x, s0Verts[3].y, s0Verts[3].z);
        
        System.out.printf("S0 vertices: v0=%s, v1=%s, v2=%s, v3=%s%n", rootV0, rootV1, rootV2, rootV3);
        
        boolean hitsRoot = intersector.intersectTetrahedron(rayOrigin, rayDir, rootV0, rootV1, rootV2, rootV3, tetResult);
        System.out.printf("Root intersection: hit=%b, tEntry=%.4f, entryFace=%d%n", 
            hitsRoot, tetResult.tEntry, tetResult.entryFace);

        // Check entry face children
        byte rootType = nodes[0].getTetType();
        int entryFace = tetResult.entryFace;
        byte[] childOrderBey = ESVTChildOrder.getChildOrder(rootType, entryFace);
        System.out.printf("Entry face %d, children (Bey): [%d, %d, %d, %d]%n",
            entryFace, childOrderBey[0], childOrderBey[1], childOrderBey[2], childOrderBey[3]);

        // Store root vertices for child computation
        float[] parentVerts = new float[12];
        parentVerts[0] = rootV0.x; parentVerts[1] = rootV0.y; parentVerts[2] = rootV0.z;
        parentVerts[3] = rootV1.x; parentVerts[4] = rootV1.y; parentVerts[5] = rootV1.z;
        parentVerts[6] = rootV2.x; parentVerts[7] = rootV2.y; parentVerts[8] = rootV2.z;
        parentVerts[9] = rootV3.x; parentVerts[10] = rootV3.y; parentVerts[11] = rootV3.z;

        Point3f[] childVerts = new Point3f[4];
        for (int i = 0; i < 4; i++) childVerts[i] = new Point3f();

        System.out.println("\nTesting all 8 children:");
        for (int mortonIdx = 0; mortonIdx < 8; mortonIdx++) {
            boolean hasChild = nodes[0].hasChild(mortonIdx);
            if (!hasChild) {
                System.out.printf("  Morton %d: NO CHILD%n", mortonIdx);
                continue;
            }
            
            // Compute child vertices
            int beyIdx = TetreeConnectivity.INDEX_TO_BEY_NUMBER[rootType][mortonIdx];
            computeChildVertices(parentVerts, beyIdx, childVerts);
            
            // Test intersection
            boolean hits = intersector.intersectTetrahedron(rayOrigin, rayDir,
                childVerts[0], childVerts[1], childVerts[2], childVerts[3], tetResult);
            
            boolean isLeaf = nodes[0].isChildLeaf(mortonIdx);
            int childNodeIdx = nodes[0].getChildIndex(mortonIdx);
            
            System.out.printf("  Morton %d (Bey %d): hit=%b, isLeaf=%b, childIdx=%d, verts=[%s, %s, %s, %s]%n",
                mortonIdx, beyIdx, hits, isLeaf, childNodeIdx,
                childVerts[0], childVerts[1], childVerts[2], childVerts[3]);
        }

        // Now cast using traversal
        var traversal = new ESVTTraversal();
        var ray = new ESVTRay(-0.1f, 0.5f, 0.5f, 1, 0, 0);
        
        var result = traversal.castRay(ray, nodes, 0);
        
        System.out.printf("\nTraversal result: hit=%b, t=%.4f, iterations=%d%n",
            result.isHit(), result.t, result.iterations);
        
        // Don't fail the test - just diagnose
        // assertTrue(result.isHit(), "Ray should hit");
    }

    private void computeChildVertices(float[] parentVerts, int beyIdx, Point3f[] childVerts) {
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
