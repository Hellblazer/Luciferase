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

import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Detects when entities escape bubble bounds and identifies destination bubbles.
 * <p>
 * Key Operations:
 * <ul>
 *   <li><b>Containment Check</b> - Test if entity position within bubble bounds</li>
 *   <li><b>Destination Routing</b> - Use Tetree.locate() to find destination bubble</li>
 *   <li><b>Migration Records</b> - Generate migration records for escaped entities</li>
 * </ul>
 * <p>
 * Critical Difference from 2D:
 * <ul>
 *   <li>2D: Boundary crossing detection (X/Y bounds)</li>
 *   <li>3D: Tetrahedral containment check (deterministic single destination)</li>
 * </ul>
 * <p>
 * Thread-safe for read-only operations. Assumes bubble structure is stable during checks.
 *
 * @author hal.hildebrand
 */
public class TetrahedralContainmentChecker {

    private final Tetree<?, ?> tetree;
    private final TetreeBubbleGrid bubbleGrid;

    /**
     * Create a containment checker for tetrahedral migration.
     *
     * @param tetree     Spatial index for location queries
     * @param bubbleGrid Bubble grid for destination validation
     */
    public TetrahedralContainmentChecker(Tetree<?, ?> tetree, TetreeBubbleGrid bubbleGrid) {
        this.tetree = Objects.requireNonNull(tetree, "Tetree cannot be null");
        this.bubbleGrid = Objects.requireNonNull(bubbleGrid, "BubbleGrid cannot be null");
    }

    /**
     * Check which entities need migration from a bubble.
     * <p>
     * Algorithm:
     * <ol>
     *   <li>For each entity in bubble:</li>
     *   <li>  a. Check if position within bubble.bounds()</li>
     *   <li>  b. If escaped, find destination via Tetree.locate()</li>
     *   <li>  c. Record migration if destination exists</li>
     *   <li>Return list of migration records (empty if none)</li>
     * </ol>
     *
     * @param bubble Bubble to check for migrations
     * @return List of migration records (empty if no migrations needed)
     * @throws NullPointerException if bubble is null
     */
    public List<MigrationRecord> checkMigrations(EnhancedBubble bubble) {
        Objects.requireNonNull(bubble, "Bubble cannot be null");

        var migrations = new ArrayList<MigrationRecord>();

        // Source key is the bubble's FIXED registration cell — O(1), stable across ticks (RDR-015).
        // The previous implementation tested escape against bubble.bounds(), the ADAPTIVE entity-derived
        // AABB that BubbleBoundsTracker recomputes from the entities every tick. That box always wraps
        // its own entities, so !contains(position) was never true and NO migration candidate was ever
        // produced — a dead-migration cause independent of the coordinate-space mismatch. Containment
        // must be tested against the fixed partition cell the bubble owns.
        TetreeKey<?> bubbleKey = bubble.spatialKey();
        if (bubbleKey == null) {
            // Bubble not registered in a partition grid (e.g. legacy/standalone) - skip migration check.
            return migrations;
        }

        // EXACT tetrahedral containment (Luciferase-6kod9): an entity has escaped iff the partition cell that
        // geometrically contains its position is NOT this bubble's own cell. This replaces the
        // BubbleBounds.fromTetreeKey(bubbleKey).contains(position) test, which applied toRDG (a basis ROTATION)
        // then an AABB check — an OUTER approximation of the tetrahedron. That mixed coordinate spaces (the
        // partition is tiled in RAW Cartesian, but escape was tested in RDG-rotated AABB space) and only
        // registered an escape once the entity was ~1.5 cell-edges out, producing false negatives and asymmetric
        // escape timing across reflected directions. contains12DOP is the exact 11-op Kuhn-simplex test; the
        // source cell's Tet is both the authority and a cheap fast-path for the common "entity stayed put" case.
        var srcTet = Tet.tetrahedron(bubbleKey);

        for (var entity : bubble.getAllEntityRecords()) {
            var position = entity.position();

            // Exact fast-path: still inside its own partition cell → not escaped.
            if (srcTet.contains12DOP(position.x, position.y, position.z)) {
                continue;
            }

            // Outside the source cell — find the destination cell that exactly contains the position.
            // locateDestinationBubble queries locateTetrahedron(position, partitionLevel); the located key is the
            // authority for whether (and where) the entity migrates. A null/equal key means stay (e.g. the
            // position resolves to an empty cell, or a boundary tie-break maps it back to the source).
            var destKey = locateDestinationBubble(position);
            if (destKey != null && !destKey.equals(bubbleKey)) {
                // Velocity is not stored in EntityRecord, so pass null (caller retrieves it separately).
                migrations.add(new MigrationRecord(entity.id(), bubbleKey, destKey, position, null));
            }
        }

        return migrations;
    }

    /**
     * Locate the destination bubble for an entity position.
     * <p>
     * Uses Tetree.locate() to find the containing tetrahedron,
     * then validates that a bubble exists at that key.
     * <p>
     * Returns null if:
     * <ul>
     *   <li>Position is out of bounds (no tetrahedron contains it)</li>
     *   <li>Destination bubble doesn't exist in grid</li>
     *   <li>Any error occurs during lookup</li>
     * </ul>
     *
     * @param position Entity position to locate
     * @return TetreeKey of destination bubble, or null if invalid
     */
    public TetreeKey<?> locateDestinationBubble(Point3f position) {
        try {
            // RDR-018 AC-2: resolve the destination via the deepest-leaf up-walk over the Option B
            // mixed-level refinement forest. The grid locates the position at its finest plausible level
            // (maxLeafLevel watermark), then walks the parent-key chain to the deepest existing leaf,
            // terminating at the base partition level — never the L0 catch-all (RDR-015 R1). This
            // supersedes the fixed-partition-level query: after a Bey split the destination may be an
            // L+1 child leaf invisible to a query pinned at the base level L.
            return bubbleGrid.resolveLeafKey(tetree, position);
        } catch (Exception e) {
            // Handle gracefully - return null on any error
            // (This prevents migration failures from crashing simulation)
            return null;
        }
    }

    /**
     * Migration record for entity needing to move between bubbles.
     * <p>
     * Contains all information needed to execute migration:
     * <ul>
     *   <li>Entity ID (unique identifier)</li>
     *   <li>Source bubble key (where entity currently resides)</li>
     *   <li>Destination bubble key (where entity should move)</li>
     *   <li>Position (entity's current location)</li>
     *   <li>Velocity (entity's current velocity, may be null)</li>
     * </ul>
     *
     * @param entityId       Unique entity identifier
     * @param sourceBubbleKey Source bubble TetreeKey
     * @param destBubbleKey   Destination bubble TetreeKey
     * @param position        Entity position
     * @param velocity        Entity velocity (may be null)
     */
    public record MigrationRecord(
        String entityId,
        TetreeKey<?> sourceBubbleKey,
        TetreeKey<?> destBubbleKey,
        Point3f position,
        Vector3f velocity
    ) {
    }
}
