/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
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

import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link PrismSubdivisionStrategy} over the two-prism cover (RDR-009 P5). The strategy
 * selects among a prism's children (produced by {@link PrismKey#child(int)} / {@link Triangle#child(int)});
 * since those propagate the root {@code half} (P3) and the Bey transitions (P2), the strategy's
 * children stay in the parent's half and tile it. This test confirms the strategy emits valid,
 * same-half, next-level children for both S0 and S1 parents.
 *
 * @author hal.hildebrand
 */
class PrismSubdivisionStrategyTest {

    private final PrismSubdivisionStrategy<LongEntityID, String> strategy = PrismSubdivisionStrategy.balanced();
    private final Prism<LongEntityID, String> prism = new Prism<>(new SequentialLongIDGenerator(), 1.0f, 21);

    @Test
    @DisplayName("full subdivision of an S0 parent yields only valid, same-half (S0), next-level children")
    void s0FullSubdivisionStaysInS0() {
        assertSubdivisionWellFormed(PrismKey.fromWorldCoordinates(0.8f, 0.3f, 0.5f, 3), (byte) 3, 0);
    }

    @Test
    @DisplayName("full subdivision of an S1 parent yields only valid, same-half (S1), next-level children")
    void s1FullSubdivisionStaysInS1() {
        var s1Parent = PrismKey.fromWorldCoordinates(0.3f, 0.8f, 0.5f, 3); // y > x -> S1
        assertEquals(1, s1Parent.getTriangle().getHalf(), "fixture must be an S1 prism");
        assertSubdivisionWellFormed(s1Parent, (byte) 3, 1);
    }

    @Test
    @DisplayName("horizontal-biased subdivision (entity thin in z) yields valid same-half children, no crash")
    void horizontalBiasedSubdivisionDoesNotCrash() {
        // Entity spans the full triangle (x,y) but only a thin z slice -> the horizontal-refinement
        // path. Must produce valid level-synchronized children (regression: the old branch built
        // PrismKey(triangle@level+1, line@level) which violates level sync and threw).
        var parent = PrismKey.fromWorldCoordinates(0.8f, 0.3f, 0.5f, 3); // S0
        var wb = parent.getWorldBounds();
        float zThin = wb[2] + 0.1f * (wb[5] - wb[2]); // ~10% of the line height
        var bounds = new EntityBounds(new Point3f(wb[0], wb[1], wb[2]), new Point3f(wb[3], wb[4], zThin));
        assertChildrenValid(parent, (byte) 3, 0, bounds);
    }

    @Test
    @DisplayName("vertical-biased subdivision (entity thin in x,y) yields valid same-half children, no crash")
    void verticalBiasedSubdivisionDoesNotCrash() {
        // Entity spans the full z range but a thin x,y footprint -> the vertical-refinement path.
        var parent = PrismKey.fromWorldCoordinates(0.3f, 0.8f, 0.5f, 3); // S1
        var wb = parent.getWorldBounds();
        float xThin = wb[0] + 0.05f * (wb[3] - wb[0]);
        float yThin = wb[1] + 0.05f * (wb[4] - wb[1]);
        var bounds = new EntityBounds(new Point3f(wb[0], wb[1], wb[2]), new Point3f(xThin, yThin, wb[5]));
        assertChildrenValid(parent, (byte) 3, 1, bounds);
    }

    /** Assert the strategy emits only valid, same-half, next-level children (any non-empty result). */
    private void assertChildrenValid(PrismKey parent, byte parentLevel, int expectedHalf, EntityBounds bounds) {
        var targets = strategy.calculateTargetNodes(parent, parentLevel, bounds, prism);
        var validChildren = new HashSet<PrismKey>();
        for (int i = 0; i < PrismKey.CHILDREN; i++) {
            validChildren.add(parent.child(i));
        }
        for (var child : targets) {
            assertTrue(validChildren.contains(child), "strategy emitted a non-child: " + child);
            assertEquals(expectedHalf, child.getTriangle().getHalf(), "child must stay in the parent's half");
            assertEquals(parentLevel + 1, child.getLevel(), "child must be level-synchronized at level+1");
        }
    }

    private void assertSubdivisionWellFormed(PrismKey parent, byte parentLevel, int expectedHalf) {
        assertEquals(expectedHalf, parent.getTriangle().getHalf());

        // EntityBounds covering the whole parent prism -> triggers full (horizontal + vertical)
        // subdivision and intersects every child.
        var wb = parent.getWorldBounds(); // [minX,minY,minZ, maxX,maxY,maxZ]
        var bounds = new EntityBounds(new Point3f(wb[0], wb[1], wb[2]), new Point3f(wb[3], wb[4], wb[5]));

        var targets = strategy.calculateTargetNodes(parent, parentLevel, bounds, prism);
        assertEquals(PrismKey.CHILDREN, targets.size(),
            "an entity covering the whole parent must subdivide into all 8 children");

        // The valid children of this parent (the 8 PrismKey children).
        var validChildren = new HashSet<PrismKey>();
        for (int i = 0; i < PrismKey.CHILDREN; i++) {
            validChildren.add(parent.child(i));
        }
        for (var child : targets) {
            assertTrue(validChildren.contains(child), "strategy emitted a non-child: " + child);
            assertEquals(expectedHalf, child.getTriangle().getHalf(), "child must stay in the parent's half");
            assertEquals(parentLevel + 1, child.getLevel(), "child must be one level finer");
        }
    }
}
