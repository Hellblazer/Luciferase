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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for all-8 Morton child descent in ESVTTraversal.
 *
 * <p>These tests validate two properties of the corrected traversal:
 * <ol>
 *   <li>Per-slot: for each Morton child slot 0..7, a ray aimed at that child's
 *       centroid must hit when only that slot is populated. The pre-fix traversal
 *       only checked the entry-face child set (4 slots of type-0/face-0): Bey
 *       face-0 set {1,4,5,7} converted via BEY_NUMBER_TO_INDEX gives Morton
 *       {1,2,3,5} — so only those 4 Morton slots were checked. Slots {0,4,6,7}
 *       fail with the old code.</li>
 *   <li>Global min-t: when two leaf children are both intersected by one ray,
 *       the result must be the NEAREST child (by t), not the first-encountered.
 *       The pre-fix code returns immediately on first leaf, failing this test.</li>
 * </ol>
 *
 * <p>These tests MUST be RED before the ESVTTraversal rewrite and GREEN after.
 *
 * @author hal.hildebrand
 */
class ESVTPerSlotDescendTest {

    /**
     * Build a two-node tree: root with exactly one leaf child in the given Morton slot.
     *
     * <p>Root (type 0, index 0): childMask = (1 << slot), leafMask = (1 << slot), childPtr = 1.
     * Child (type derived, index 1): no children (leaf).
     */
    private static ESVTNodeUnified[] buildSingleLeafTree(int mortonSlot) {
        var nodes = new ESVTNodeUnified[2];

        nodes[0] = new ESVTNodeUnified((byte) 0);
        nodes[0].setChildMask(1 << mortonSlot);
        nodes[0].setLeafMask(1 << mortonSlot);
        nodes[0].setChildPtr(1);

        // Child type derived from t8code connectivity
        byte childType = Constants.TYPE_TO_TYPE_OF_CHILD_MORTON[0][mortonSlot];
        nodes[1] = new ESVTNodeUnified(childType);
        // Leaf node: no children, no leaf mask (the parent's leafMask marks it as leaf)

        return nodes;
    }

    /**
     * Compute the centroid of child Morton slot {@code mortonSlot} of a type-0 root.
     *
     * <p>The root spans the unit cube [0,1]^3. The root vertices for type 0 are
     * SIMPLEX_STANDARD[0]. We use the same Bey subdivision that ESVTTraversal uses
     * in getChildVerticesFromParent, then compute the mean of the 4 child vertices.
     */
    private static Point3f computeChildCentroid(int mortonSlot) {
        byte parentType = 0;
        // Root vertices (integer coords, unit cube = scale 1.0)
        Point3i[] standard = Constants.SIMPLEX_STANDARD[parentType];
        float p0x = standard[0].x, p0y = standard[0].y, p0z = standard[0].z;
        float p1x = standard[1].x, p1y = standard[1].y, p1z = standard[1].z;
        float p2x = standard[2].x, p2y = standard[2].y, p2z = standard[2].z;
        float p3x = standard[3].x, p3y = standard[3].y, p3z = standard[3].z;

        // Edge midpoints
        float m01x = (p0x + p1x) * 0.5f, m01y = (p0y + p1y) * 0.5f, m01z = (p0z + p1z) * 0.5f;
        float m02x = (p0x + p2x) * 0.5f, m02y = (p0y + p2y) * 0.5f, m02z = (p0z + p2z) * 0.5f;
        float m03x = (p0x + p3x) * 0.5f, m03y = (p0y + p3y) * 0.5f, m03z = (p0z + p3z) * 0.5f;
        float m12x = (p1x + p2x) * 0.5f, m12y = (p1y + p2y) * 0.5f, m12z = (p1z + p2z) * 0.5f;
        float m13x = (p1x + p3x) * 0.5f, m13y = (p1y + p3y) * 0.5f, m13z = (p1z + p3z) * 0.5f;
        float m23x = (p2x + p3x) * 0.5f, m23y = (p2y + p3y) * 0.5f, m23z = (p2z + p3z) * 0.5f;

        // Convert Morton slot to Bey index (same as ESVTTraversal.getChildVerticesFromParent)
        int beyIdx = TetreeConnectivity.INDEX_TO_BEY_NUMBER[parentType][mortonSlot];

        // Child vertices in Bey ordering (mirrors ESVTTraversal switch block)
        float cv0x, cv0y, cv0z, cv1x, cv1y, cv1z, cv2x, cv2y, cv2z, cv3x, cv3y, cv3z;
        switch (beyIdx) {
            case 0 -> {
                cv0x = p0x; cv0y = p0y; cv0z = p0z;
                cv1x = m01x; cv1y = m01y; cv1z = m01z;
                cv2x = m02x; cv2y = m02y; cv2z = m02z;
                cv3x = m03x; cv3y = m03y; cv3z = m03z;
            }
            case 1 -> {
                cv0x = m01x; cv0y = m01y; cv0z = m01z;
                cv1x = p1x; cv1y = p1y; cv1z = p1z;
                cv2x = m12x; cv2y = m12y; cv2z = m12z;
                cv3x = m13x; cv3y = m13y; cv3z = m13z;
            }
            case 2 -> {
                cv0x = m02x; cv0y = m02y; cv0z = m02z;
                cv1x = m12x; cv1y = m12y; cv1z = m12z;
                cv2x = p2x; cv2y = p2y; cv2z = p2z;
                cv3x = m23x; cv3y = m23y; cv3z = m23z;
            }
            case 3 -> {
                cv0x = m03x; cv0y = m03y; cv0z = m03z;
                cv1x = m13x; cv1y = m13y; cv1z = m13z;
                cv2x = m23x; cv2y = m23y; cv2z = m23z;
                cv3x = p3x; cv3y = p3y; cv3z = p3z;
            }
            case 4 -> {
                cv0x = m01x; cv0y = m01y; cv0z = m01z;
                cv1x = m02x; cv1y = m02y; cv1z = m02z;
                cv2x = m03x; cv2y = m03y; cv2z = m03z;
                cv3x = m13x; cv3y = m13y; cv3z = m13z;
            }
            case 5 -> {
                cv0x = m01x; cv0y = m01y; cv0z = m01z;
                cv1x = m02x; cv1y = m02y; cv1z = m02z;
                cv2x = m12x; cv2y = m12y; cv2z = m12z;
                cv3x = m13x; cv3y = m13y; cv3z = m13z;
            }
            case 6 -> {
                cv0x = m02x; cv0y = m02y; cv0z = m02z;
                cv1x = m03x; cv1y = m03y; cv1z = m03z;
                cv2x = m13x; cv2y = m13y; cv2z = m13z;
                cv3x = m23x; cv3y = m23y; cv3z = m23z;
            }
            default -> { // case 7
                cv0x = m02x; cv0y = m02y; cv0z = m02z;
                cv1x = m12x; cv1y = m12y; cv1z = m12z;
                cv2x = m13x; cv2y = m13y; cv2z = m13z;
                cv3x = m23x; cv3y = m23y; cv3z = m23z;
            }
        }

        // Centroid = average of 4 vertices
        return new Point3f(
            (cv0x + cv1x + cv2x + cv3x) * 0.25f,
            (cv0y + cv1y + cv2y + cv3y) * 0.25f,
            (cv0z + cv1z + cv2z + cv3z) * 0.25f
        );
    }

    /**
     * Per-slot test: for each Morton slot 0..7, build a tree with only that leaf,
     * aim a ray at its centroid from outside the unit cube, and assert hit.
     *
     * <p>The pre-fix traversal only examines CHILD_ORDER for entry-face 0 of type 0,
     * which is the Bey-face-0 set {1,4,5,7}. Converting those Bey IDs via
     * BEY_NUMBER_TO_INDEX gives Morton {1,2,3,5} for type 0 — so ONLY those 4
     * Morton slots are ever checked. Slots {0,4,6,7} will FAIL (RED) with the old code.
     *
     * <p>After the all-8 rewrite all 8 slots should pass (GREEN).
     */
    @ParameterizedTest(name = "slot {0}")
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7})
    void testPerSlotDescend(int mortonSlot) {
        var traversal = new ESVTTraversal();
        var nodes = buildSingleLeafTree(mortonSlot);

        // Compute child centroid and aim ray at it from outside (x < 0 side)
        var centroid = computeChildCentroid(mortonSlot);

        // Ray from x = -1 heading in +x direction, at (y,z) = centroid
        var ray = new ESVTRay(-1.0f, centroid.y, centroid.z, 1.0f, 0.0f, 0.0f);

        var result = traversal.castRay(ray, nodes, 0);

        assertTrue(result.hit,
            "Morton slot " + mortonSlot + " (bey " + TetreeConnectivity.INDEX_TO_BEY_NUMBER[0][mortonSlot]
            + "): ray aimed at centroid " + centroid + " should hit. "
            + "Pre-fix behavior: only Morton slots {1,2,3,5} checked → slots {0,4,6,7} fail.");
        assertEquals(mortonSlot, result.childIndex,
            "Hit should be on Morton slot " + mortonSlot + ", got " + result.childIndex);
    }

    /**
     * Global min-t test: two leaf children intersected by one ray, nearer child must win.
     *
     * <p>Build a type-0 root with TWO leaf children at Morton slots that both lie along
     * the +x ray. The one with the smaller x-centroid is nearer. The pre-fix code returns
     * the FIRST leaf found (CHILD_ORDER ordering), not necessarily the nearer one. After
     * the all-8 all-leaves rewrite, the result must be the leaf with the smaller tEntry.
     *
     * <p>We pick slots 0 and 2 for type-0 root:
     *   - slot 0 (Bey 0): corner at v0=(0,0,0), centroid ~(0.125, 0.0625, 0.0625)
     *   - slot 2 (Bey 4): octahedral, centroid ~(0.1875, 0.125, 0.125) — note: check actual values
     * If both are hit by a ray from x=-1, y=~0.09, z=~0.09, the nearer one (smaller tEntry)
     * must be returned.
     *
     * <p>Rather than hardcode specific slots (which depend on centroid geometry), we find
     * two slots whose x-ranges overlap from a chosen ray direction, verify both are hit
     * independently, then assert the combined result is the nearer of the two.
     */
    @Test
    void testGlobalMinT_NearerChildWins() {
        var traversal = new ESVTTraversal();
        var intersector = MollerTrumboreIntersection.create();

        // We need two Morton slots that are BOTH hit by a single ray through the unit cube.
        // Use type-0 root. Pick ray direction +x at y=0.1, z=0.1.
        // We'll test all pairs of slots to find at least one pair where both are hit.
        float rayY = 0.1f, rayZ = 0.1f;
        var rayOrigin = new javax.vecmath.Point3f(-1.0f, rayY, rayZ);
        var rayDir = new javax.vecmath.Vector3f(1.0f, 0.0f, 0.0f);

        var tetResult = new MollerTrumboreIntersection.TetrahedronResult();

        // For each slot, check if the ray hits the child and at what t
        float[] tEntryPerSlot = new float[8];
        boolean[] hitPerSlot = new boolean[8];

        Point3i[] standard = Constants.SIMPLEX_STANDARD[0];
        float p0x = standard[0].x, p0y = standard[0].y, p0z = standard[0].z;
        float p1x = standard[1].x, p1y = standard[1].y, p1z = standard[1].z;
        float p2x = standard[2].x, p2y = standard[2].y, p2z = standard[2].z;
        float p3x = standard[3].x, p3y = standard[3].y, p3z = standard[3].z;

        for (int slot = 0; slot < 8; slot++) {
            var centroid = computeChildCentroid(slot);
            // Compute child vertices for intersection test
            float m01x = (p0x + p1x) * 0.5f, m01y = (p0y + p1y) * 0.5f, m01z = (p0z + p1z) * 0.5f;
            float m02x = (p0x + p2x) * 0.5f, m02y = (p0y + p2y) * 0.5f, m02z = (p0z + p2z) * 0.5f;
            float m03x = (p0x + p3x) * 0.5f, m03y = (p0y + p3y) * 0.5f, m03z = (p0z + p3z) * 0.5f;
            float m12x = (p1x + p2x) * 0.5f, m12y = (p1y + p2y) * 0.5f, m12z = (p1z + p2z) * 0.5f;
            float m13x = (p1x + p3x) * 0.5f, m13y = (p1y + p3y) * 0.5f, m13z = (p1z + p3z) * 0.5f;
            float m23x = (p2x + p3x) * 0.5f, m23y = (p2y + p3y) * 0.5f, m23z = (p2z + p3z) * 0.5f;

            int beyIdx = TetreeConnectivity.INDEX_TO_BEY_NUMBER[0][slot];
            javax.vecmath.Point3f cv0 = new javax.vecmath.Point3f(), cv1 = new javax.vecmath.Point3f(),
                    cv2 = new javax.vecmath.Point3f(), cv3 = new javax.vecmath.Point3f();
            switch (beyIdx) {
                case 0 -> { cv0.set(p0x,p0y,p0z); cv1.set(m01x,m01y,m01z); cv2.set(m02x,m02y,m02z); cv3.set(m03x,m03y,m03z); }
                case 1 -> { cv0.set(m01x,m01y,m01z); cv1.set(p1x,p1y,p1z); cv2.set(m12x,m12y,m12z); cv3.set(m13x,m13y,m13z); }
                case 2 -> { cv0.set(m02x,m02y,m02z); cv1.set(m12x,m12y,m12z); cv2.set(p2x,p2y,p2z); cv3.set(m23x,m23y,m23z); }
                case 3 -> { cv0.set(m03x,m03y,m03z); cv1.set(m13x,m13y,m13z); cv2.set(m23x,m23y,m23z); cv3.set(p3x,p3y,p3z); }
                case 4 -> { cv0.set(m01x,m01y,m01z); cv1.set(m02x,m02y,m02z); cv2.set(m03x,m03y,m03z); cv3.set(m13x,m13y,m13z); }
                case 5 -> { cv0.set(m01x,m01y,m01z); cv1.set(m02x,m02y,m02z); cv2.set(m12x,m12y,m12z); cv3.set(m13x,m13y,m13z); }
                case 6 -> { cv0.set(m02x,m02y,m02z); cv1.set(m03x,m03y,m03z); cv2.set(m13x,m13y,m13z); cv3.set(m23x,m23y,m23z); }
                default -> { cv0.set(m02x,m02y,m02z); cv1.set(m12x,m12y,m12z); cv2.set(m13x,m13y,m13z); cv3.set(m23x,m23y,m23z); }
            }
            if (intersector.intersectTetrahedron(rayOrigin, rayDir, cv0, cv1, cv2, cv3, tetResult)) {
                hitPerSlot[slot] = true;
                tEntryPerSlot[slot] = tetResult.tEntry;
            }
        }

        // Find two slots that are both hit
        int slotA = -1, slotB = -1;
        for (int i = 0; i < 8 && slotB < 0; i++) {
            if (!hitPerSlot[i]) continue;
            if (slotA < 0) { slotA = i; continue; }
            slotB = i;
        }

        // If we can't find two hit slots on this ray, try a different ray
        if (slotB < 0) {
            // Try y=0.3, z=0.3 — deeper in the cube, likely to hit more children
            rayY = 0.3f; rayZ = 0.3f;
            rayOrigin.set(-1.0f, rayY, rayZ);
            slotA = slotB = -1;
            tEntryPerSlot = new float[8];
            hitPerSlot = new boolean[8];

            float m01x = (p0x + p1x) * 0.5f, m01y = (p0y + p1y) * 0.5f, m01z = (p0z + p1z) * 0.5f;
            float m02x = (p0x + p2x) * 0.5f, m02y = (p0y + p2y) * 0.5f, m02z = (p0z + p2z) * 0.5f;
            float m03x = (p0x + p3x) * 0.5f, m03y = (p0y + p3y) * 0.5f, m03z = (p0z + p3z) * 0.5f;
            float m12x = (p1x + p2x) * 0.5f, m12y = (p1y + p2y) * 0.5f, m12z = (p1z + p2z) * 0.5f;
            float m13x = (p1x + p3x) * 0.5f, m13y = (p1y + p3y) * 0.5f, m13z = (p1z + p3z) * 0.5f;
            float m23x = (p2x + p3x) * 0.5f, m23y = (p2y + p3y) * 0.5f, m23z = (p2z + p3z) * 0.5f;

            for (int slot = 0; slot < 8; slot++) {
                int beyIdx = TetreeConnectivity.INDEX_TO_BEY_NUMBER[0][slot];
                javax.vecmath.Point3f cv0 = new javax.vecmath.Point3f(), cv1 = new javax.vecmath.Point3f(),
                        cv2 = new javax.vecmath.Point3f(), cv3 = new javax.vecmath.Point3f();
                switch (beyIdx) {
                    case 0 -> { cv0.set(p0x,p0y,p0z); cv1.set(m01x,m01y,m01z); cv2.set(m02x,m02y,m02z); cv3.set(m03x,m03y,m03z); }
                    case 1 -> { cv0.set(m01x,m01y,m01z); cv1.set(p1x,p1y,p1z); cv2.set(m12x,m12y,m12z); cv3.set(m13x,m13y,m13z); }
                    case 2 -> { cv0.set(m02x,m02y,m02z); cv1.set(m12x,m12y,m12z); cv2.set(p2x,p2y,p2z); cv3.set(m23x,m23y,m23z); }
                    case 3 -> { cv0.set(m03x,m03y,m03z); cv1.set(m13x,m13y,m13z); cv2.set(m23x,m23y,m23z); cv3.set(p3x,p3y,p3z); }
                    case 4 -> { cv0.set(m01x,m01y,m01z); cv1.set(m02x,m02y,m02z); cv2.set(m03x,m03y,m03z); cv3.set(m13x,m13y,m13z); }
                    case 5 -> { cv0.set(m01x,m01y,m01z); cv1.set(m02x,m02y,m02z); cv2.set(m12x,m12y,m12z); cv3.set(m13x,m13y,m13z); }
                    case 6 -> { cv0.set(m02x,m02y,m02z); cv1.set(m03x,m03y,m03z); cv2.set(m13x,m13y,m13z); cv3.set(m23x,m23y,m23z); }
                    default -> { cv0.set(m02x,m02y,m02z); cv1.set(m12x,m12y,m12z); cv2.set(m13x,m13y,m13z); cv3.set(m23x,m23y,m23z); }
                }
                if (intersector.intersectTetrahedron(rayOrigin, rayDir, cv0, cv1, cv2, cv3, tetResult)) {
                    hitPerSlot[slot] = true;
                    tEntryPerSlot[slot] = tetResult.tEntry;
                    if (slotA < 0) slotA = slot;
                    else if (slotB < 0) slotB = slot;
                }
            }
        }

        // Must find two slots both hit — if not the test setup is wrong
        assertTrue(slotA >= 0 && slotB >= 0,
            "Test setup error: could not find two Morton slots both hit by the test ray");

        // Determine which slot is nearer
        int nearerSlot = tEntryPerSlot[slotA] <= tEntryPerSlot[slotB] ? slotA : slotB;
        int fartherSlot = nearerSlot == slotA ? slotB : slotA;

        // Verify nearer and farther are distinct (no ties — if tEntry is equal this test is degenerate)
        assertNotEquals(tEntryPerSlot[nearerSlot], tEntryPerSlot[fartherSlot],
            "Test setup: the two hit slots have the same tEntry — choose a different ray");

        System.out.println("Global min-t test: slots " + slotA + "(t=" + tEntryPerSlot[slotA] + ") and "
            + slotB + "(t=" + tEntryPerSlot[slotB] + "), nearer=" + nearerSlot
            + ", farther=" + fartherSlot + ", ray=(" + rayOrigin + ")");

        // Build tree with both leaf children
        var nodes = new ESVTNodeUnified[3];
        nodes[0] = new ESVTNodeUnified((byte) 0);
        nodes[0].setChildMask((1 << slotA) | (1 << slotB));
        nodes[0].setLeafMask((1 << slotA) | (1 << slotB));
        nodes[0].setChildPtr(1);  // child of slot A at nodes[1], slot B at nodes[2]

        byte childTypeA = Constants.TYPE_TO_TYPE_OF_CHILD_MORTON[0][slotA];
        byte childTypeB = Constants.TYPE_TO_TYPE_OF_CHILD_MORTON[0][slotB];
        nodes[1] = new ESVTNodeUnified(childTypeA);
        nodes[2] = new ESVTNodeUnified(childTypeB);

        var ray = new ESVTRay(rayOrigin.x, rayOrigin.y, rayOrigin.z, 1.0f, 0.0f, 0.0f);
        var result = traversal.castRay(ray, nodes, 0);

        assertTrue(result.hit, "Ray should hit at least one leaf child");

        // The critical assertion: result must be the NEARER child
        assertEquals(nearerSlot, result.childIndex,
            "Global min-t: expected nearer child (slot=" + nearerSlot + ", t=" + tEntryPerSlot[nearerSlot]
            + ") but got child " + result.childIndex + " (t=" + result.t + "). "
            + "Pre-fix code returns FIRST leaf found, not nearest.");

        // Additional: the returned t should be approximately the nearer child's tEntry
        assertEquals(tEntryPerSlot[nearerSlot], result.t, 0.01f,
            "Returned t should match nearer child's tEntry");
    }

    /**
     * Ordering-isolation variant A: both slots within the pre-fix CHECKED set {1,2,3,5},
     * nearer slot scanned LATER in Morton order.
     *
     * <p>Pre-fix code only checked Morton slots {1,2,3,5} (Bey face-0 set via BEY_NUMBER_TO_INDEX
     * for type 0). Even within that set, it returned on the FIRST leaf hit — a first-wins
     * overwrite bug: if the nearer child has a higher Morton index (scanned later), the pre-fix
     * code overwrites the nearer bestResult with the farther first-found result. This test
     * isolates that ordering defect without relying on the missing-slot defect.
     *
     * <p>Geometry (SIMPLEX_STANDARD[0]: v0=(0,0,0), v1=(1,0,0), v2=(1,0,1), v3=(1,1,1)):
     * <ul>
     *   <li>Morton slot 1 → Bey-1: {m01=(0.5,0,0), v1=(1,0,0), m12=(1,0,0.5), m13=(1,0.5,0.5)},
     *       x-range [0.5, 1.0]. Under +x ray at y=0.1,z=0.1: tEntry ≈ 1.6 (enters at x=0.6 approx).</li>
     *   <li>Morton slot 2 → Bey-4: {m01=(0.5,0,0), m02=(0.5,0,0.5), m03=(0.5,0.5,0.5), m13=(1,0.5,0.5)},
     *       x-range [0.5, 1.0]. Under +x ray at y=0.1,z=0.1: tEntry ≈ 1.5 (enters slightly earlier).</li>
     * </ul>
     * <p>Slot 2 (Morton-later, scanned after slot 1) is NEARER (smaller t). A correct global-min-t
     * traversal must return slot 2. The pre-fix first-wins bug returns slot 1 (first scanned).
     *
     * <p>Non-vacuity guard: both slots must independently intersect the test ray — verified
     * via the intersector before the main assertion. Fails (does not skip) if the geometry
     * does not deliver the intended configuration so degenerate ray choices are caught immediately.
     */
    @Test
    void testGlobalMinT_OrderingIsolation_NearerScannedLater() {
        // Slots 1 (scanned first, Morton order) and 2 (scanned later) — both in pre-fix set {1,2,3,5}.
        // +x ray at y=0.1, z=0.1: slot 1 (Bey-1) tEntry≈1.6, slot 2 (Bey-4) tEntry≈1.5.
        // Slot 2 (scanned later) is nearer — the pre-fix first-wins bug returns slot 1 instead.
        int slotFirst = 1;  // scanned first (lower Morton index), farther
        int slotLater = 2;  // scanned later (higher Morton index), nearer

        var intersector = MollerTrumboreIntersection.create();
        var tetResult = new MollerTrumboreIntersection.TetrahedronResult();

        Point3i[] standard = Constants.SIMPLEX_STANDARD[0];
        float p0x = standard[0].x, p0y = standard[0].y, p0z = standard[0].z;
        float p1x = standard[1].x, p1y = standard[1].y, p1z = standard[1].z;
        float p2x = standard[2].x, p2y = standard[2].y, p2z = standard[2].z;
        float p3x = standard[3].x, p3y = standard[3].y, p3z = standard[3].z;

        // +x ray: slot 2 (Bey-4) enters before slot 1 (Bey-1) despite having higher Morton index.
        // y=0.1, z=0.1 hits both (both in z=[0,0.5], y=[0,0.5]).
        var rayOrigin = new javax.vecmath.Point3f(-1.0f, 0.1f, 0.1f);
        var rayDir   = new javax.vecmath.Vector3f(1.0f, 0.0f, 0.0f);

        float[] tPerSlot = computeTPerSlot(rayOrigin, rayDir, intersector, tetResult,
            p0x, p0y, p0z, p1x, p1y, p1z, p2x, p2y, p2z, p3x, p3y, p3z);

        System.out.printf("NearerScannedLater: slot %d (Bey %d) tEntry=%.4f, slot %d (Bey %d) tEntry=%.4f%n",
            slotFirst, TetreeConnectivity.INDEX_TO_BEY_NUMBER[0][slotFirst], tPerSlot[slotFirst],
            slotLater, TetreeConnectivity.INDEX_TO_BEY_NUMBER[0][slotLater], tPerSlot[slotLater]);

        // Non-vacuity: fail, not skip, so a bad ray choice is caught immediately
        assertTrue(tPerSlot[slotFirst] > 0,
            "Non-vacuity: slot " + slotFirst + " (Bey " + TetreeConnectivity.INDEX_TO_BEY_NUMBER[0][slotFirst]
            + ") must be hit by test ray; tEntry=" + tPerSlot[slotFirst]);
        assertTrue(tPerSlot[slotLater] > 0,
            "Non-vacuity: slot " + slotLater + " (Bey " + TetreeConnectivity.INDEX_TO_BEY_NUMBER[0][slotLater]
            + ") must be hit by test ray; tEntry=" + tPerSlot[slotLater]);

        // Assert intended configuration: slot 2 (scanned later) is NEARER
        assertTrue(tPerSlot[slotLater] < tPerSlot[slotFirst],
            "Configuration: slot " + slotLater + " (t=" + tPerSlot[slotLater] + ") must be nearer "
            + "than slot " + slotFirst + " (t=" + tPerSlot[slotFirst] + ") for this test to be non-trivial");

        int expectedWinner = slotLater; // the nearer child, scanned later

        // Build two-leaf tree
        var nodes = new ESVTNodeUnified[3];
        nodes[0] = new ESVTNodeUnified((byte) 0);
        nodes[0].setChildMask((1 << slotFirst) | (1 << slotLater));
        nodes[0].setLeafMask((1 << slotFirst) | (1 << slotLater));
        nodes[0].setChildPtr(1);
        nodes[1] = new ESVTNodeUnified(Constants.TYPE_TO_TYPE_OF_CHILD_MORTON[0][slotFirst]);
        nodes[2] = new ESVTNodeUnified(Constants.TYPE_TO_TYPE_OF_CHILD_MORTON[0][slotLater]);

        var ray = new ESVTRay(rayOrigin.x, rayOrigin.y, rayOrigin.z, 1.0f, 0.0f, 0.0f);
        var traversal = new ESVTTraversal();
        var result = traversal.castRay(ray, nodes, 0);

        assertTrue(result.hit, "Ray should hit at least one of slots " + slotFirst + " and " + slotLater);
        assertEquals(expectedWinner, result.childIndex,
            "Global min-t ordering isolation (nearer scanned later): expected slot " + expectedWinner
            + " (t=" + tPerSlot[expectedWinner] + ") but got slot " + result.childIndex
            + " (t=" + result.t + "). "
            + "Pre-fix first-wins bug returns the FIRST leaf hit (slot " + slotFirst + "), not nearest.");
    }

    /**
     * Ordering-isolation variant B: both slots within the pre-fix CHECKED set {1,2,3,5},
     * nearer slot scanned EARLIER in Morton order.
     *
     * <p>This variant catches a last-wins overwrite bug: if bestResult is overwritten
     * unconditionally on every leaf hit (not gated by {@code refinedT < bestT}), then
     * the last scanned leaf wins rather than the nearest. When slot 1 (Morton-first) is
     * nearer but slot 2 (Morton-later) is farther, a last-wins bug returns slot 2.
     *
     * <p>Geometry (SIMPLEX_STANDARD[0]: v0=(0,0,0), v1=(1,0,0), v2=(1,0,1), v3=(1,1,1)):
     * <ul>
     *   <li>Morton slot 1 → Bey-1: x-range [0.5,1.0]. Under -x ray from x=2: tEntry ≈ 1.0
     *       (enters at x=1.0 from origin x=2). This is the NEARER child, scanned FIRST.</li>
     *   <li>Morton slot 2 → Bey-4: x-range [0.5,1.0]. Under -x ray from x=2: tEntry ≈ 1.4
     *       (enters slightly later at x≈0.6). This is the FARTHER child, scanned LAST.</li>
     * </ul>
     * <p>A last-wins overwrite bug (unconditional {@code bestResult = current}) would return slot 2
     * (the last one scanned). A correct {@code refinedT < bestT} guard returns slot 1 (nearer).
     *
     * <p>Non-vacuity guard: both slots must independently intersect. Fails (not skips) if the
     * geometry does not deliver the intended configuration.
     */
    @Test
    void testGlobalMinT_OrderingIsolation_NearerScannedFirst() {
        // Slots 1 (scanned first, Morton order, nearer) and 2 (scanned later, farther).
        // Both are in the pre-fix checked set {1,2,3,5}.
        // -x ray from x=2 at y=0.1, z=0.1: slot 1 (Bey-1) tEntry≈1.0, slot 2 (Bey-4) tEntry≈1.4.
        // A last-wins overwrite bug returns slot 2; correct code returns slot 1.
        int slotFirst = 1;  // scanned first (lower Morton index), nearer
        int slotLater = 2;  // scanned later (higher Morton index), farther

        var intersector = MollerTrumboreIntersection.create();
        var tetResult = new MollerTrumboreIntersection.TetrahedronResult();

        Point3i[] standard = Constants.SIMPLEX_STANDARD[0];
        float p0x = standard[0].x, p0y = standard[0].y, p0z = standard[0].z;
        float p1x = standard[1].x, p1y = standard[1].y, p1z = standard[1].z;
        float p2x = standard[2].x, p2y = standard[2].y, p2z = standard[2].z;
        float p3x = standard[3].x, p3y = standard[3].y, p3z = standard[3].z;

        // -x ray from x=2: slot 1 (Bey-1, max x=1.0) enters first (t≈1.0); slot 2 (Bey-4, max x≈1.0
        // but different face angles) enters later (t≈1.4). y=0.1, z=0.1 is in both tets' y/z bounds.
        var rayOrigin = new javax.vecmath.Point3f(2.0f, 0.1f, 0.1f);
        var rayDir   = new javax.vecmath.Vector3f(-1.0f, 0.0f, 0.0f);

        float[] tPerSlot = computeTPerSlot(rayOrigin, rayDir, intersector, tetResult,
            p0x, p0y, p0z, p1x, p1y, p1z, p2x, p2y, p2z, p3x, p3y, p3z);

        System.out.printf("NearerScannedFirst: slot %d (Bey %d) tEntry=%.4f, slot %d (Bey %d) tEntry=%.4f%n",
            slotFirst, TetreeConnectivity.INDEX_TO_BEY_NUMBER[0][slotFirst], tPerSlot[slotFirst],
            slotLater, TetreeConnectivity.INDEX_TO_BEY_NUMBER[0][slotLater], tPerSlot[slotLater]);

        // Non-vacuity: fail, not skip
        assertTrue(tPerSlot[slotFirst] > 0,
            "Non-vacuity: slot " + slotFirst + " (Bey " + TetreeConnectivity.INDEX_TO_BEY_NUMBER[0][slotFirst]
            + ") must be hit by -x ray; tEntry=" + tPerSlot[slotFirst]);
        assertTrue(tPerSlot[slotLater] > 0,
            "Non-vacuity: slot " + slotLater + " (Bey " + TetreeConnectivity.INDEX_TO_BEY_NUMBER[0][slotLater]
            + ") must be hit by -x ray; tEntry=" + tPerSlot[slotLater]);

        // Assert intended configuration: slot 1 (scanned first) is NEARER
        assertTrue(tPerSlot[slotFirst] < tPerSlot[slotLater],
            "Configuration: slot " + slotFirst + " (t=" + tPerSlot[slotFirst] + ") must be nearer "
            + "than slot " + slotLater + " (t=" + tPerSlot[slotLater] + ") for this test to be non-trivial");

        int expectedWinner = slotFirst; // the nearer child, also scanned first

        // Build two-leaf tree (same structure as variant A, different ray)
        var nodes = new ESVTNodeUnified[3];
        nodes[0] = new ESVTNodeUnified((byte) 0);
        nodes[0].setChildMask((1 << slotFirst) | (1 << slotLater));
        nodes[0].setLeafMask((1 << slotFirst) | (1 << slotLater));
        nodes[0].setChildPtr(1);
        nodes[1] = new ESVTNodeUnified(Constants.TYPE_TO_TYPE_OF_CHILD_MORTON[0][slotFirst]);
        nodes[2] = new ESVTNodeUnified(Constants.TYPE_TO_TYPE_OF_CHILD_MORTON[0][slotLater]);

        var ray = new ESVTRay(rayOrigin.x, rayOrigin.y, rayOrigin.z, -1.0f, 0.0f, 0.0f);
        var traversal = new ESVTTraversal();
        var result = traversal.castRay(ray, nodes, 0);

        assertTrue(result.hit, "Ray should hit at least one of slots " + slotFirst + " and " + slotLater);
        assertEquals(expectedWinner, result.childIndex,
            "Global min-t ordering isolation (nearer scanned first): expected slot " + expectedWinner
            + " (t=" + tPerSlot[expectedWinner] + ") but got slot " + result.childIndex
            + " (t=" + result.t + "). "
            + "A last-wins overwrite bug returns the LAST leaf hit (slot " + slotLater + "), not nearest.");
    }

    /**
     * Helper: for each Morton slot 0..7, compute tEntry under the given ray using direct
     * Bey subdivision geometry. Returns tEntry > 0 if hit, 0 if missed or negative t.
     */
    private static float[] computeTPerSlot(javax.vecmath.Point3f rayOrigin, javax.vecmath.Vector3f rayDir,
            MollerTrumboreIntersection intersector, MollerTrumboreIntersection.TetrahedronResult tetResult,
            float p0x, float p0y, float p0z, float p1x, float p1y, float p1z,
            float p2x, float p2y, float p2z, float p3x, float p3y, float p3z) {
        float m01x = (p0x + p1x) * 0.5f, m01y = (p0y + p1y) * 0.5f, m01z = (p0z + p1z) * 0.5f;
        float m02x = (p0x + p2x) * 0.5f, m02y = (p0y + p2y) * 0.5f, m02z = (p0z + p2z) * 0.5f;
        float m03x = (p0x + p3x) * 0.5f, m03y = (p0y + p3y) * 0.5f, m03z = (p0z + p3z) * 0.5f;
        float m12x = (p1x + p2x) * 0.5f, m12y = (p1y + p2y) * 0.5f, m12z = (p1z + p2z) * 0.5f;
        float m13x = (p1x + p3x) * 0.5f, m13y = (p1y + p3y) * 0.5f, m13z = (p1z + p3z) * 0.5f;
        float m23x = (p2x + p3x) * 0.5f, m23y = (p2y + p3y) * 0.5f, m23z = (p2z + p3z) * 0.5f;

        float[] t = new float[8];
        for (int slot = 0; slot < 8; slot++) {
            int beyIdx = TetreeConnectivity.INDEX_TO_BEY_NUMBER[0][slot];
            var cv0 = new javax.vecmath.Point3f(); var cv1 = new javax.vecmath.Point3f();
            var cv2 = new javax.vecmath.Point3f(); var cv3 = new javax.vecmath.Point3f();
            switch (beyIdx) {
                case 0 -> { cv0.set(p0x,p0y,p0z); cv1.set(m01x,m01y,m01z); cv2.set(m02x,m02y,m02z); cv3.set(m03x,m03y,m03z); }
                case 1 -> { cv0.set(m01x,m01y,m01z); cv1.set(p1x,p1y,p1z); cv2.set(m12x,m12y,m12z); cv3.set(m13x,m13y,m13z); }
                case 2 -> { cv0.set(m02x,m02y,m02z); cv1.set(m12x,m12y,m12z); cv2.set(p2x,p2y,p2z); cv3.set(m23x,m23y,m23z); }
                case 3 -> { cv0.set(m03x,m03y,m03z); cv1.set(m13x,m13y,m13z); cv2.set(m23x,m23y,m23z); cv3.set(p3x,p3y,p3z); }
                case 4 -> { cv0.set(m01x,m01y,m01z); cv1.set(m02x,m02y,m02z); cv2.set(m03x,m03y,m03z); cv3.set(m13x,m13y,m13z); }
                case 5 -> { cv0.set(m01x,m01y,m01z); cv1.set(m02x,m02y,m02z); cv2.set(m12x,m12y,m12z); cv3.set(m13x,m13y,m13z); }
                case 6 -> { cv0.set(m02x,m02y,m02z); cv1.set(m03x,m03y,m03z); cv2.set(m13x,m13y,m13z); cv3.set(m23x,m23y,m23z); }
                default -> { cv0.set(m02x,m02y,m02z); cv1.set(m12x,m12y,m12z); cv2.set(m13x,m13y,m13z); cv3.set(m23x,m23y,m23z); }
            }
            if (intersector.intersectTetrahedron(rayOrigin, rayDir, cv0, cv1, cv2, cv3, tetResult)
                    && tetResult.tEntry > 0) {
                t[slot] = tetResult.tEntry;
            }
        }
        return t;
    }
}
