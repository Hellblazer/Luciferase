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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Coordinates entity migration across tetrahedral bubble boundaries.
 * <p>
 * Key Responsibilities:
 * <ul>
 *   <li><b>Migration Detection</b> - Identify entities that escaped bubble bounds</li>
 *   <li><b>Routing</b> - Determine correct destination bubbles</li>
 *   <li><b>Two-Phase Commit</b> - Ensure atomic migration (no entity loss)</li>
 *   <li><b>Cooldown Management</b> - Prevent rapid oscillation</li>
 *   <li><b>Hysteresis</b> - Require minimum distance from boundary</li>
 * </ul>
 * <p>
 * Migration Protocol (Two-Phase Commit):
 * <pre>
 * PREPARE:
 *   1. Check if entity escaped bounds
 *   2. Verify cooldown passed (30 ticks)
 *   3. Check hysteresis distance (2.0f from boundary)
 *   4. Find destination via Tetree.locate()
 *
 * COMMIT:
 *   1. Add entity to destination bubble
 *   2. Remove entity from source bubble
 *   3. Record cooldown timestamp
 *
 * ROLLBACK (on failure):
 *   1. Remove entity from destination
 *   2. Entity remains in source (no loss)
 * </pre>
 * <p>
 * Thread-safe via concurrent data structures.
 *
 * @author hal.hildebrand
 */
public class TetrahedralMigration {

    private static final Logger log = LoggerFactory.getLogger(TetrahedralMigration.class);

    /**
     * Cooldown period: minimum ticks between migrations for same entity.
     * Prevents rapid back-and-forth oscillation at bubble boundaries.
     */
    private static final int COOLDOWN_TICKS = 30;

    /**
     * Hysteresis distance: minimum Cartesian distance an entity must have crossed past
     * the bubble boundary before a migration is triggered. This anti-thrash band prevents
     * pathological back-and-forth migration for entities hovering near a boundary (e.g.
     * due to floating-point imprecision or small velocity jitter).
     * <p>
     * Geometric basis: 2.0f is chosen relative to the typical tetrahedral cell size at
     * boundary edges — it is large enough to filter boundary noise but small enough to
     * not create a perceptible stuck-band for entities with normal traversal velocities.
     * <p>
     * Trade-off: entities within 2.0 Cartesian units of the boundary will not migrate
     * even if technically outside the owning bubble. This is intentional — a small
     * stuck-band is preferable to continuous re-migration. The value is a tunable
     * threshold: decrease for finer-grained migration (more boundary sensitivity),
     * increase to widen the no-migrate band (more anti-thrash protection).
     */
    private static final float HYSTERESIS_DIST = 2.0f;

    private final TetreeBubbleGrid bubbleGrid;
    private final TetrahedralContainmentChecker checker;
    private final TetrahedralMigrationRouter router;
    private final Map<String, Long> migrationCooldowns;
    private final TetrahedralMigrationMetrics metrics;

    /**
     * Create a migration coordinator for tetrahedral bubbles.
     *
     * @param bubbleGrid Bubble grid for topology
     * @param tetree     Spatial index for location queries
     */
    public TetrahedralMigration(TetreeBubbleGrid bubbleGrid, Tetree<?, ?> tetree) {
        this.bubbleGrid = Objects.requireNonNull(bubbleGrid, "BubbleGrid cannot be null");
        Objects.requireNonNull(tetree, "Tetree cannot be null");

        this.checker = new TetrahedralContainmentChecker(tetree, bubbleGrid);
        this.router = new TetrahedralMigrationRouter(tetree, bubbleGrid);
        this.migrationCooldowns = new ConcurrentHashMap<>();
        this.metrics = new TetrahedralMigrationMetrics();
    }

    /**
     * Package-private constructor for testing: accepts pre-built checker and router.
     */
    TetrahedralMigration(TetreeBubbleGrid bubbleGrid, TetrahedralContainmentChecker checker,
                         TetrahedralMigrationRouter router) {
        this.bubbleGrid = Objects.requireNonNull(bubbleGrid, "BubbleGrid cannot be null");
        this.checker = Objects.requireNonNull(checker, "Checker cannot be null");
        this.router = Objects.requireNonNull(router, "Router cannot be null");
        this.migrationCooldowns = new ConcurrentHashMap<>();
        this.metrics = new TetrahedralMigrationMetrics();
    }

    /**
     * Check for and execute migrations across all bubbles.
     * <p>
     * Algorithm:
     * <ol>
     *   <li>PREPARE: Collect all candidate migrations from all bubbles</li>
     *   <li>Filter by cooldown and hysteresis</li>
     *   <li>COMMIT: Execute valid migrations with two-phase protocol</li>
     *   <li>Update metrics</li>
     * </ol>
     *
     * @param currentTick Current simulation tick count
     */
    public void checkMigrations(long currentTick) {
        var allMigrations = new ArrayList<TetrahedralContainmentChecker.MigrationRecord>();

        // PREPARE: Collect all candidate migrations
        for (var bubble : bubbleGrid.getAllBubbles()) {
            var bubbleMigrations = checker.checkMigrations(bubble);
            for (var migration : bubbleMigrations) {
                if (migrationCandidate(migration, currentTick)) {
                    allMigrations.add(migration);
                }
            }
        }

        // Update metrics: active cooldowns
        metrics.updateActiveCooldowns(migrationCooldowns.size());

        // COMMIT: Execute migrations
        for (var migration : allMigrations) {
            var decision = router.routeMigration(migration);
            if (decision != null) {
                if (executeMigration(decision, currentTick)) {
                    metrics.recordSuccessfulMigration(
                        migration.sourceBubbleKey(),
                        migration.destBubbleKey()
                    );
                } else {
                    metrics.recordFailedMigration();
                }
            } else {
                // Routing failed
                metrics.recordFailedMigration();
            }
        }
    }

    /**
     * Check if an entity is a valid migration candidate.
     * <p>
     * Three conditions must be met:
     * <ol>
     *   <li>Entity escaped bubble bounds (checked by containment checker)</li>
     *   <li>Entity cooled down (30 ticks since last migration)</li>
     *   <li>Entity is sufficiently far from the boundary (>= HYSTERESIS_DIST in Cartesian units)
     *       to suppress migrations caused by floating-point jitter at the edge</li>
     * </ol>
     *
     * @param migration   Migration record
     * @param currentTick Current simulation tick
     * @return true if entity should migrate, false otherwise
     */
    private boolean migrationCandidate(TetrahedralContainmentChecker.MigrationRecord migration, long currentTick) {
        var entityId = migration.entityId();

        // Check cooldown: wait 30 ticks minimum
        var lastMigration = migrationCooldowns.get(entityId);
        if (lastMigration != null && currentTick - lastMigration < COOLDOWN_TICKS) {
            return false;  // Still cooling down
        }

        // EXACT spatial hysteresis (Luciferase-6kod9): require the entity to be at least HYSTERESIS_DIST Cartesian
        // units past the nearest FACE of its source partition tetrahedron before migrating. The escape test now
        // uses exact tetrahedral containment, so the old RDG-AABB overshoot collapsed to ~0 for an entity just
        // over a true face (the AABB face is not the tet face) and would re-suppress every migration the exact
        // containment check just admitted. Distance-to-nearest-face is the geometrically correct anti-jitter
        // measure and is computed in the same raw-Cartesian space as the entity position (the partition cell's
        // Tet coordinates are raw-Cartesian-aligned, matching locateTetrahedron(position, level)).
        var srcKey = migration.sourceBubbleKey();
        if (srcKey != null) {
            var srcTet = Tet.tetrahedron(srcKey);
            double overshoot = overshootPastNearestFace(srcTet, migration.position());
            if (overshoot < HYSTERESIS_DIST) {
                log.trace("Hysteresis suppressed migration for entity {}: overshoot={} < threshold={}",
                          entityId, String.format("%.3f", overshoot), HYSTERESIS_DIST);
                return false;  // Too close to the source boundary — suppress thrashing migration
            }
        }

        return true;  // Passed all checks
    }

    /**
     * Maximum outward signed distance (in Cartesian units) from {@code p} to any face plane of {@code tet}.
     * <p>
     * Each of the four faces is opposite one vertex; its plane normal is oriented outward (away from that opposite
     * apex vertex), so a positive signed distance means {@code p} lies outside that face. The maximum over the four
     * faces is how far the entity has crossed past the nearest boundary face it exited through. The value is
     * {@code <= 0} when {@code p} is inside (or on the surface of) the tet — i.e. jitter at/inside the boundary
     * yields no overshoot and is suppressed. The tet's {@code coordinates()} are raw-Cartesian-aligned partition
     * coordinates, matching the entity position's space (Luciferase-6kod9).
     *
     * @param tet the source partition tetrahedron
     * @param p   the entity position (raw Cartesian)
     * @return the maximum outward face overshoot in Cartesian units
     */
    private static double overshootPastNearestFace(Tet tet, Point3f p) {
        var v = tet.coordinates(); // Point3i[4]
        double max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 4; i++) {
            // Face i is opposite vertex i; the other three vertices span the face.
            var a = v[(i + 1) & 3];
            var b = v[(i + 2) & 3];
            var c = v[(i + 3) & 3];
            var apex = v[i];

            // Face plane normal n = (b - a) x (c - a).
            double abx = b.x - a.x, aby = b.y - a.y, abz = b.z - a.z;
            double acx = c.x - a.x, acy = c.y - a.y, acz = c.z - a.z;
            double nx = aby * acz - abz * acy;
            double ny = abz * acx - abx * acz;
            double nz = abx * acy - aby * acx;
            double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len == 0.0) {
                continue; // degenerate face — skip
            }

            // Orient outward: flip if the normal points toward the opposite apex (i.e. toward the interior).
            double towardApex = nx * (apex.x - a.x) + ny * (apex.y - a.y) + nz * (apex.z - a.z);
            double sign = towardApex > 0 ? -1.0 : 1.0;

            double d = sign * (nx * (p.x - a.x) + ny * (p.y - a.y) + nz * (p.z - a.z)) / len;
            if (d > max) {
                max = d;
            }
        }
        return max;
    }

    /**
     * Execute migration with two-phase commit protocol.
     * <p>
     * Two-Phase Protocol:
     * <pre>
     * PHASE 1 (PREPARE): Add to destination
     * PHASE 2 (COMMIT): Remove from source
     * PHASE 3 (ROLLBACK on failure): Remove from destination
     * </pre>
     * <p>
     * <b>Failure Modes Leading to Duplicate Entities:</b>
     * <ol>
     *   <li><b>Rollback Failure</b>: If removeEntity(src) throws and rollback removeEntity(dst)
     *       also throws, entity persists in both bubbles. Requires DuplicateEntityDetector
     *       to reconcile. Root cause: concurrent access, OutOfMemoryError, or spatial index corruption.</li>
     *   <li><b>Partial Commit</b>: If thread is forcibly stopped (Thread.stop, JVM crash)
     *       between addEntity(dst) and removeEntity(src), duplicate persists in memory.
     *       Extremely rare but possible in distributed crash scenarios.</li>
     *   <li><b>Observation Window</b>: During normal operation, entity temporarily exists
     *       in both bubbles between the two phases. This is expected and transient (microseconds),
     *       but concurrent observers may detect it.</li>
     *   <li><b>Cascading Failures</b>: If Mode 1 occurs and entity migrates again before
     *       reconciliation, duplicates can accumulate across 3+ bubbles. Example: A→B fails rollback,
     *       then B→C succeeds, resulting in entity in A+B+C.</li>
     * </ol>
     * <p>
     * <b>Mitigation</b>: DuplicateEntityDetector scans all bubbles every tick to detect and
     * reconcile duplicates using MigrationLog as source-of-truth. Detection latency is 1 tick maximum.
     * <p>
     * <b>Atomicity Guarantee</b>: While not atomic across bubbles, the two-phase protocol ensures
     * eventual consistency via detection and reconciliation. Entity loss is impossible (worst case: duplicate).
     *
     * @param decision    Migration decision
     * @param currentTick Current simulation tick
     * @return true if migration succeeded, false otherwise
     */
    private boolean executeMigration(TetrahedralMigrationRouter.MigrationDecision decision, long currentTick) {
        var entityId = decision.entityId();
        var srcBubble = bubbleGrid.containsBubble(decision.sourceKey()) ?
                        bubbleGrid.getBubble(decision.sourceKey()) : null;
        var dstBubble = bubbleGrid.containsBubble(decision.destinationKey()) ?
                        bubbleGrid.getBubble(decision.destinationKey()) : null;

        if (srcBubble == null || dstBubble == null) {
            return false;
        }

        // Same-bubble guard: migrating to itself is a no-op.  Without this guard the
        // add-then-remove sequence below would double-insert the entity in the same bubble,
        // corrupting idMapping integrity.  Check BEFORE lock acquisition to keep the path cheap.
        if (srcBubble.id().equals(dstBubble.id())) {
            log.debug("Skipping self-migration for entity {} in bubble {}", entityId, srcBubble.id());
            return false;
        }

        // Hold BOTH bubble mutation locks via the shared MutationLocks protocol (ascending UUID order,
        // deadlock-free) — the one coherent protocol shared by every multi-bubble writer
        // (Luciferase-n7io1), so no two concurrent migrations/merges/splits on the same pair can form a
        // lock-ordering cycle.
        try (var ignored = MutationLocks.lock(srcBubble, dstBubble)) {
            // PHASE 1: Get entity from source under lock (latest snapshot, not stale)
            var entityRecords = srcBubble.getAllEntityRecords();
            var entityRecord = entityRecords.stream()
                                           .filter(e -> e.id().equals(entityId))
                                           .findFirst()
                                           .orElse(null);

            if (entityRecord == null) {
                return false;  // Entity not found (already migrated or removed)
            }

            // PHASE 2: Add to destination (atomic, still under both locks)
            dstBubble.addEntity(entityId, entityRecord.position(), entityRecord.content());

            // PHASE 3: Remove from source (may fail)
            try {
                srcBubble.removeEntity(entityId);
            } catch (Exception e) {
                // ROLLBACK: Remove from destination if source remove fails
                try {
                    dstBubble.removeEntity(entityId);
                } catch (Exception rollbackEx) {
                    // Rollback failed: the entity now exists in BOTH source and destination
                    // bubbles (duplicate-entity state). This is unrecoverable here and must be
                    // observable for downstream reconciliation, so log at ERROR.
                    log.error("Rollback failed for entity {} migrating {}->{}: entity now exists in "
                              + "both source and destination (duplicate state)",
                              entityId, srcBubble.id(), dstBubble.id(), rollbackEx);
                }
                return false;
            }

            // Success: Record cooldown
            migrationCooldowns.put(entityId, currentTick);

            return true;

        } catch (Exception e) {
            // Any failure acquiring the mutation locks or executing phases 1-2 (lookup / add) lands
            // here. Previously this returned false silently, making migration failures invisible and
            // indistinguishable from a legitimate not-found/skip — log at WARN with context so they
            // are observable (Luciferase-9jea5). The inner rollback catch above already logs the
            // duplicate-state case at ERROR.
            log.warn("Migration failed unexpectedly for entity {} ({} -> {})",
                     entityId, srcBubble.id(), dstBubble.id(), e);
            return false;
        }
    }

    /**
     * Get migration metrics.
     *
     * @return TetrahedralMigrationMetrics instance
     */
    public TetrahedralMigrationMetrics getMetrics() {
        return metrics;
    }

    /**
     * Clear cooldowns for testing or reset.
     */
    public void clearCooldowns() {
        migrationCooldowns.clear();
    }

    /**
     * Get cooldown ticks constant (for testing).
     *
     * @return Cooldown period in ticks
     */
    public static int getCooldownTicks() {
        return COOLDOWN_TICKS;
    }

    /**
     * Get hysteresis distance constant (for testing).
     *
     * @return Hysteresis distance threshold
     */
    public static float getHysteresisDistance() {
        return HYSTERESIS_DIST;
    }
}
