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

import com.hellblazer.luciferase.lucien.BulkOperationConfig;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;

import java.util.ArrayList;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Luciferase-q8z (RDR-009 GATE-B follow-up): promote the StackBasedTreeBuilder S0-only-seed safety
 * from a documentation promise to a tested invariant.
 *
 * <p>The opt-in {@code StackBasedTreeBuilder.buildTopDown} seeds its top frame from
 * {@code entities.getFirst().sfcIndex.root()} = {@code PrismKey.createRoot()} = the S0 root only.
 * GATE-B concluded this is structurally safe for the two-prism cover because the builder is only
 * engaged for batches {@code >= stackBuilderThreshold (10000) >> maxEntitiesPerNode (100)}, so the
 * root frame always subdivides (never becomes a storage node), and {@code groupByChildNode} routes
 * each entity through the half-aware {@code calculateSpatialIndex} — so S1 ({@code y > x}) entities
 * land at correct S1 keys, not under the S0 root. That reasoning was guaranteed only by a config
 * constant + prose; this test exercises the real production path
 * ({@code BulkOperationConfig.highPerformance()} -> {@code insertBatch} -> {@code performStackBasedBulkInsert})
 * with a large mixed-half batch and asserts S1 entities are present and queryable.
 *
 * @author hal.hildebrand
 */
class PrismStackBuilderBothHalvesTest {

    @Test
    @DisplayName("StackBasedTreeBuilder bulk insert of a large mixed-half batch reaches both prism families")
    void stackBuilderBulkInsertReachesBothHalves() {
        var prism = new Prism<LongEntityID, String>(new SequentialLongIDGenerator(), 1.0f, 21);
        // highPerformance() sets useStackBasedBuilder=true, stackBuilderThreshold=10000 — so a batch
        // larger than the threshold routes through StackBasedTreeBuilder (the S0-only-seed path).
        prism.configureBulkOperations(BulkOperationConfig.highPerformance());

        var rnd = new Random(20260527L);
        var n = 12_000; // > stackBuilderThreshold (10000) so the stack builder is actually engaged
        var positions = new ArrayList<Point3f>(n);
        var contents = new ArrayList<String>(n);
        var s1Samples = new ArrayList<Point3f>();
        var s0Samples = new ArrayList<Point3f>();
        var s1Count = 0;
        for (int i = 0; i < n; i++) {
            var x = 0.001f + 0.997f * rnd.nextFloat();
            var y = 0.001f + 0.997f * rnd.nextFloat();
            var z = 0.001f + 0.997f * rnd.nextFloat();
            var p = new Point3f(x, y, z);
            positions.add(p);
            contents.add("e" + i);
            if (y > x) { // S1 (upper-left)
                s1Count++;
                if (s1Samples.size() < 40) {
                    s1Samples.add(p);
                }
            } else if (s0Samples.size() < 40) { // S0 (lower-right, y <= x)
                s0Samples.add(p);
            }
        }
        assertTrue(s1Count > 1_000, "fixture must contain a substantial S1 population, was " + s1Count);

        // NOTE: highPerformance() configures the stack builder NOT to track individual ids (a memory
        // optimization), so insertBatch returns an empty list by design and entityCount() is the
        // authoritative "nothing dropped" check (the builder logs the same guidance).
        prism.insertBatch(positions, contents, (byte) 12);

        assertEquals(n, prism.entityCount(), "every entity (both halves) must be stored — none dropped");

        // The load-bearing check: S1 entities must be queryable in their own region. If the builder
        // had stored them under the S0 root key (the feared carry-over), a kNN at the S1 position
        // would find no node near it. We inserted an entity AT each sampled position, so kNN(p,1)
        // must return a neighbor at ~0 distance.
        for (var p : s1Samples) {
            var nn = prism.kNearestNeighbors(p, 1, 0.02f);
            assertFalse(nn.isEmpty(),
                "an S1 (y>x) entity inserted at " + p + " must be queryable — not lost under the S0 root");
        }
        // Sanity: S0 entities are equally queryable (guards against a symmetric regression).
        for (var p : s0Samples) {
            var nn = prism.kNearestNeighbors(p, 1, 0.02f);
            assertFalse(nn.isEmpty(), "an S0 (y<=x) entity inserted at " + p + " must be queryable");
        }
    }
}
