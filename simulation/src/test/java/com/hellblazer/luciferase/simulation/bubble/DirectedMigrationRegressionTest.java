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
package com.hellblazer.luciferase.simulation.bubble;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import com.hellblazer.luciferase.simulation.config.WorldBounds;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Directed-migration regression for RDR-015 (AC5) — <b>non-vacuous and end-to-end</b>.
 * <p>
 * Drives the full migration subsystem ({@link TetrahedralMigration#checkMigrations}: containment →
 * routing → two-phase execute) and asserts a known entity, placed at a position that escapes its source
 * cell {@code S} and lies in a specific other partition cell {@code B}, actually <b>migrates to {@code B}
 * specifically</b> — it leaves {@code S} and arrives in {@code B}, with exactly one successful migration
 * recorded. This is the load-bearing, anti-catch-all guarantee the RDR exists to restore; asserting merely
 * {@code getTotalMigrations() > 0} would be satisfied by a catch-all router routing to the wrong bubble.
 * <p>
 * <b>Escape is now EXACT (Luciferase-6kod9):</b> an entity escapes {@code S} iff its position is not inside
 * {@code S}'s tetrahedron by the exact {@code contains12DOP} test (the partition cell's true Kuhn simplex),
 * not the former RDG-AABB outer approximation. {@code B} is the cell that exactly geometrically contains the
 * escaped position. We assert the entity migrates to {@code B} specifically — the strong, exact anti-catch-all
 * guarantee. We deliberately do NOT additionally assert that {@code B} is an SFC face-neighbor of {@code S}
 * (e.g. via {@code findAllFaceNeighbors}): tet face neighbors are the non-conforming Bey-SFC neighbors that
 * share 0–3 vertices, so the SFC face-neighbor set need not equal the geometric containing-neighbor of a point
 * just past a face. Per the project caveat, tet adjacency is validated by involution reciprocity, never by
 * assuming geometric-neighbor == SFC-neighbor; the exact geometric-destination assertion below is the correct,
 * non-fragile guarantee.
 * <p>
 * The migration path was dead through three independent causes, all resolved across RDR-015 P1–P3:
 * (1) entity coordinates decoupled from the bubble-grid domain; (2) a mixed-level non-partition grid
 * with an L0 catch-all; (3) escape tested against the <em>adaptive</em> entity-derived bounds (which
 * re-wrap the entities every tick and so never report an escape). Containment now tests against each
 * bubble's FIXED partition cell.
 *
 * @author hal.hildebrand
 */
class DirectedMigrationRegressionTest {

    private static final int         BUBBLE_COUNT = 8;
    private static final long        TARGET_FRAME = 10L;
    private static final WorldBounds WORLD        = new WorldBounds(0.0f, 100.0f);

    @Test
    void escapedEntityMigratesToTheSpecificAdjacentNeighbor() {
        var grid = new TetreeBubbleGrid((byte) 21);
        grid.createBubbles(BUBBLE_COUNT, WORLD, TARGET_FRAME);

        byte level = grid.getPartitionLevel();
        assertTrue(level > 0, "grid must be a single-level partition");
        var spatial = grid.getSpatialIndex();
        int edge = Constants.lengthAtLevel(level);

        // Find a source cell S and a displaced position P that (a) provably escapes S's fixed cell and
        // (b) lands in a DIFFERENT existing partition cell B. Using the actual containing cell of P as
        // the expected destination keeps the assertion specific (== B), not a weak "some migration".
        TetreeKey<?> sourceKey = null;
        TetreeKey<?> expectedDestKey = null;
        Point3f probe = null;
        outer:
        for (var key : grid.getBubblesWithKeys().keySet()) {
            var srcTet = key.toTet();
            var sc = centroid(srcTet);
            for (var offset : displacements(edge)) {
                var p = new Point3f(sc.x + offset[0], sc.y + offset[1], sc.z + offset[2]);
                if (p.x < 0 || p.y < 0 || p.z < 0) {
                    continue; // Tetree coordinate domain is non-negative
                }
                if (srcTet.contains12DOP(p.x, p.y, p.z)) {
                    continue; // EXACT containment: not escaped from S's own cell (Luciferase-6kod9)
                }
                var destTet = spatial.locateTetrahedron(p, level);
                if (destTet == null) {
                    continue;
                }
                var destKey = destTet.tmIndex();
                if (destKey.equals(key) || !grid.containsBubble(destKey)) {
                    continue; // must be a different, existing partition cell
                }
                sourceKey = key;
                expectedDestKey = destKey;
                probe = p;
                break outer;
            }
        }
        assertNotNull(expectedDestKey,
                      "setup: partition must expose a source cell with an escaped position landing in a "
                      + "different existing cell");

        var sourceBubble = grid.getBubble(sourceKey);
        var destBubble = grid.getBubble(expectedDestKey);

        var entityId = "directed-1";
        sourceBubble.addEntity(entityId, probe, probe);

        var migration = new TetrahedralMigration(grid, spatial);
        migration.checkMigrations(1L);

        assertEquals(1, migration.getMetrics().getTotalMigrations(), "exactly one entity must migrate");
        assertEquals(0, migration.getMetrics().getFailureCount(), "no migration may fail");
        assertTrue(containsEntity(destBubble, entityId),
                   "entity must arrive in the specific destination bubble that contains its position");
        assertFalse(containsEntity(sourceBubble, entityId),
                    "entity must no longer reside in the source bubble S");
    }

    /** Axis displacements of 1.5 cell-edges in each direction — far enough to clear the source cell's AABB. */
    private static float[][] displacements(int edge) {
        float d = edge * 1.5f;
        return new float[][] {
            {d, 0, 0}, {-d, 0, 0}, {0, d, 0}, {0, -d, 0}, {0, 0, d}, {0, 0, -d}
        };
    }

    private static boolean containsEntity(EnhancedBubble bubble, String entityId) {
        return bubble.getAllEntityRecords().stream().anyMatch(r -> r.id().equals(entityId));
    }

    private static Point3f centroid(Tet tet) {
        Point3i[] c = tet.coordinates();
        float cx = (c[0].x + c[1].x + c[2].x + c[3].x) / 4.0f;
        float cy = (c[0].y + c[1].y + c[2].y + c[3].y) / 4.0f;
        float cz = (c[0].z + c[1].z + c[2].z + c[3].z) / 4.0f;
        return new Point3f(cx, cy, cz);
    }
}
