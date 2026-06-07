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
package com.hellblazer.luciferase.simulation.bubble;

import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import com.hellblazer.luciferase.simulation.config.WorldBounds;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RDR-018 AC-2 (Luciferase-pg344): deepest-leaf up-walk routing over the Option B mixed-level
 * refinement forest.
 * <p>
 * Pins {@link TetreeBubbleGrid#resolveLeafKey}: locate the position at the finest plausible level
 * ({@code maxLeafLevel}), then walk the parent-key chain and return the FIRST (deepest) existing
 * leaf bubble. The walk terminates at the base partition level — it NEVER resolves to the L0 root
 * catch-all (RDR-015 R1) and never above the base level. A position with no owning leaf (out of
 * WorldBounds) returns null.
 *
 * @author hal.hildebrand
 */
class TetreeBubbleGridUpWalkRoutingTest {

    private static final WorldBounds WORLD = new WorldBounds(0.0f, 100.0f);

    private static TetreeBubbleGrid partitionGrid() {
        var grid = new TetreeBubbleGrid((byte) 21);
        grid.createBubbles(8, WORLD, 10L);
        return grid;
    }

    /** Centroid of a tet (average of its 4 vertices) — strictly interior, never on a face. */
    private static Point3f centroid(Tet tet) {
        var v = tet.coordinates();
        return new Point3f((v[0].x + v[1].x + v[2].x + v[3].x) / 4f,
                           (v[0].y + v[1].y + v[2].y + v[3].y) / 4f,
                           (v[0].z + v[1].z + v[2].z + v[3].z) / 4f);
    }

    @Test
    void singleLevelPartition_resolvesToTheBaseLevelCell() {
        var grid = partitionGrid();
        var tetree = grid.getSpatialIndex();
        byte base = grid.getBaseLevel();
        assertTrue(base > 0, "must be a single-level partition");

        // maxLeafLevel starts at the base level (no refinement yet).
        assertEquals(base, grid.getMaxLeafLevel(), "watermark starts at base level");

        for (var key : grid.getBubblesWithKeys().keySet()) {
            var here = centroid(Tet.tetrahedron(key));
            var resolved = grid.resolveLeafKey(tetree, here);
            assertEquals(key, resolved, "interior point must resolve to its own base-level cell");
        }
    }

    @Test
    void refinedRegion_resolvesToTheDeepChildLeaf_notTheBaseAncestor() {
        var grid = partitionGrid();
        var tetree = grid.getSpatialIndex();
        byte base = grid.getBaseLevel();

        // Pick one base-level bubble and refine it into its 8 Bey children (the AC-2.5 effect),
        // by hand here so this test is independent of the splitter.
        var parentKey = grid.getBubblesWithKeys().keySet().iterator().next();
        var parentTet = Tet.tetrahedron(parentKey);
        var children = parentTet.geometricSubdivide();

        grid.removeBubble(grid.getBubble(parentKey).id());
        var childKeys = new TetreeKey<?>[8];
        for (int i = 0; i < 8; i++) {
            childKeys[i] = children[i].tmIndex();
            grid.addBubble(new EnhancedBubble(UUID.randomUUID(), (byte) (base + 1), 10L), childKeys[i]);
        }

        // Watermark must have been raised by adding the deeper leaves.
        assertEquals((byte) (base + 1), grid.getMaxLeafLevel(), "watermark raised to base+1 after refinement");

        // A point in each child's interior must resolve to THAT child key — the deepest existing
        // leaf — NOT the (now-removed) base ancestor and NOT any coarser key.
        for (int i = 0; i < 8; i++) {
            var here = centroid(children[i]);
            var resolved = grid.resolveLeafKey(tetree, here);
            assertEquals(childKeys[i], resolved,
                         "interior point of child " + i + " must resolve to the deep child leaf");
            assertNotEquals(parentKey, resolved, "must NOT resolve to the removed base ancestor");
            assertTrue(resolved.getLevel() == base + 1, "resolved leaf must be at the deeper level");
        }
    }

    @Test
    void unrefinedRegion_stillResolvesToItsBaseCell_inAMixedLevelGrid() {
        var grid = partitionGrid();
        var tetree = grid.getSpatialIndex();
        byte base = grid.getBaseLevel();

        var it = grid.getBubblesWithKeys().keySet().iterator();
        var refinedKey = it.next();
        var stillCoarseKey = it.next(); // a DIFFERENT base cell we leave unrefined

        // Refine only refinedKey.
        var children = Tet.tetrahedron(refinedKey).geometricSubdivide();
        grid.removeBubble(grid.getBubble(refinedKey).id());
        for (int i = 0; i < 8; i++) {
            grid.addBubble(new EnhancedBubble(UUID.randomUUID(), (byte) (base + 1), 10L), children[i].tmIndex());
        }

        // The unrefined cell must still route to its base-level key even though the grid is now mixed-level.
        var here = centroid(Tet.tetrahedron(stillCoarseKey));
        assertEquals(stillCoarseKey, grid.resolveLeafKey(tetree, here),
                     "unrefined region must still resolve to its base-level cell in a mixed-level grid");
    }

    @Test
    void prefersDeepestLeaf_whenBothBaseAncestorAndChildAreRegistered() {
        // The real R1 tripwire: register a base-level bubble AND one of its Bey children SIMULTANEOUSLY
        // (a state that violates the leaf-partition invariant, but directly exercises the router's
        // "deepest existing leaf wins" preference — which the refined-region test cannot, because there
        // the ancestor was removed first). If the up-walk ever regressed to base-first / level-0-first
        // scanning, it would return the ancestor and THIS assertion would fail.
        var grid = partitionGrid();
        var tetree = grid.getSpatialIndex();
        byte base = grid.getBaseLevel();

        var parentKey = grid.getBubblesWithKeys().keySet().iterator().next();
        var child0 = Tet.tetrahedron(parentKey).geometricSubdivide()[0];
        var childKey = child0.tmIndex();

        // Keep the parent bubble; ALSO register the child (co-present, both candidates).
        grid.addBubble(new EnhancedBubble(UUID.randomUUID(), (byte) (base + 1), 10L), childKey);

        assertTrue(grid.containsBubble(parentKey), "base ancestor must still be registered");
        assertTrue(grid.containsBubble(childKey), "child leaf must be registered");

        var here = centroid(child0);
        var resolved = grid.resolveLeafKey(tetree, here);
        assertEquals(childKey, resolved,
                     "with both ancestor and child present, the up-walk must return the DEEPER child leaf");
        assertNotEquals(parentKey, resolved,
                        "must NOT resolve to the coarser ancestor when a deeper leaf owns the position (R1)");
    }

    @Test
    void outOfWorldBounds_resolvesToNull_neverACatchAll() {
        var grid = partitionGrid();
        var tetree = grid.getSpatialIndex();
        // A point far outside the [0,100] world domain has no owning leaf.
        var outside = new Point3f(10_000f, 10_000f, 10_000f);
        assertNull(grid.resolveLeafKey(tetree, outside),
                   "out-of-bounds position must resolve to null, not an L0 catch-all (R1)");
    }
}
