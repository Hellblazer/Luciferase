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

import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;

import java.util.Objects;

/**
 * Routes entity migrations to correct destination bubbles.
 * <p>
 * Key Differences from 2D Grid:
 * <ul>
 *   <li><b>2D</b>: Direction-based routing (N, NE, E, SE, S, SW, W, NW)</li>
 *   <li><b>3D</b>: Deterministic containment routing (Tetree.locate())</li>
 * </ul>
 * <p>
 * In 3D tetrahedral topology, there is NO ambiguity:
 * <ul>
 *   <li>Each point has exactly ONE containing tetrahedron</li>
 *   <li>No diagonal tie-breaking needed</li>
 *   <li>Confidence score always 1.0 (deterministic)</li>
 * </ul>
 * <p>
 * Thread-safe for read-only operations.
 *
 * @author hal.hildebrand
 */
public class TetrahedralMigrationRouter {

    private final Tetree<?, ?> tetree;
    private final TetreeBubbleGrid bubbleGrid;

    /**
     * Create a migration router for tetrahedral topology.
     *
     * @param tetree     Spatial index for location queries
     * @param bubbleGrid Bubble grid for destination validation
     */
    public TetrahedralMigrationRouter(Tetree<?, ?> tetree, TetreeBubbleGrid bubbleGrid) {
        this.tetree = Objects.requireNonNull(tetree, "Tetree cannot be null");
        this.bubbleGrid = Objects.requireNonNull(bubbleGrid, "BubbleGrid cannot be null");
    }

    /**
     * Route a migration to its destination.
     * <p>
     * Unlike 2D grid routing (which needs direction logic and tie-breaking),
     * 3D tetrahedral routing is deterministic:
     * <ol>
     *   <li>Destination already determined by Tetree.locate()</li>
     *   <li>Simply validate destination exists</li>
     *   <li>Return decision with 100% confidence</li>
     * </ol>
     * <p>
     * If destination doesn't exist, attempts to relocate. Returns null on failure.
     *
     * @param migration Migration record to route
     * @return MigrationDecision with routing info, or null if routing failed
     * @throws NullPointerException if migration is null
     */
    public MigrationDecision routeMigration(TetrahedralContainmentChecker.MigrationRecord migration) {
        Objects.requireNonNull(migration, "Migration cannot be null");

        // Destination is already deterministic from Tetree.locate()
        // No tie-breaking or routing logic needed

        // Validate destination exists
        var destBubble = bubbleGrid.containsBubble(migration.destBubbleKey()) ?
                         bubbleGrid.getBubble(migration.destBubbleKey()) : null;

        if (destBubble == null) {
            // Fallback: try to relocate using current position
            try {
                // Try multiple levels to find a bubble
                for (byte level = 0; level <= 10; level++) {
                    var tet = tetree.locateTetrahedron(migration.position(), level);
                    if (tet != null) {
                        var newDest = tet.tmIndex();

                        // Verify new destination exists
                        if (bubbleGrid.containsBubble(newDest)) {
                            return new MigrationDecision(
                                migration.entityId(),
                                migration.sourceBubbleKey(),
                                newDest,
                                1.0f,  // Confidence: high (deterministic)
                                false  // Single destination (never multi-tet path)
                            );
                        }
                    }
                }
            } catch (Exception e) {
                // Relocation failed - can't route
                return null;
            }

            // Can't route - return null
            return null;
        }

        // Valid routing: destination exists
        return new MigrationDecision(
            migration.entityId(),
            migration.sourceBubbleKey(),
            migration.destBubbleKey(),
            1.0f,  // High confidence (deterministic)
            false  // Always single destination in 3D
        );
    }

    /**
     * Migration decision with routing information.
     * <p>
     * Unlike 2D grid (which has variable confidence due to diagonal ambiguity),
     * 3D tetrahedral routing is always deterministic:
     * <ul>
     *   <li>confidenceScore: Always 1.0 (100% confidence)</li>
     *   <li>needsMultiTetPath: Always false (single destination)</li>
     * </ul>
     *
     * @param entityId         Unique entity identifier
     * @param sourceKey        Source bubble TetreeKey
     * @param destinationKey   Destination bubble TetreeKey
     * @param confidenceScore  Confidence in routing decision (always 1.0)
     * @param needsMultiTetPath Whether entity spans multiple tetrahedra (always false)
     */
    public record MigrationDecision(
        String entityId,
        TetreeKey<?> sourceKey,
        TetreeKey<?> destinationKey,
        float confidenceScore,
        boolean needsMultiTetPath
    ) {
    }
}
