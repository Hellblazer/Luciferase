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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

            // Filter by migration candidate criteria
            var bounds = bubble.bounds();
            for (var migration : bubbleMigrations) {
                if (migrationCandidate(migration, currentTick, bounds)) {
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
     * @param bounds      The source bubble's spatial bounds (used for hysteresis distance check)
     * @return true if entity should migrate, false otherwise
     */
    private boolean migrationCandidate(TetrahedralContainmentChecker.MigrationRecord migration, long currentTick,
                                       BubbleBounds bounds) {
        var entityId = migration.entityId();

        // Check cooldown: wait 30 ticks minimum
        var lastMigration = migrationCooldowns.get(entityId);
        if (lastMigration != null && currentTick - lastMigration < COOLDOWN_TICKS) {
            return false;  // Still cooling down
        }

        // Check hysteresis: entity must be sufficiently far past the boundary.
        // Uses RDGCS coordinates: toRDG computes round((-x+y+z)/√2), so
        // 1 RDGCS integer unit ≈ √2 Cartesian units (not the other way around).
        // overshootCartesian = overshootRdg_euclidean * √2.
        // For HYSTERESIS_DIST=2.0 Cartesian:
        //   rdg overshoot 1 → Cartesian ≈ 1*√2 ≈ 1.41 < 2.0 → BLOCKED
        //   rdg overshoot 2 → Cartesian ≈ 2*√2 ≈ 2.83 > 2.0 → ALLOWED
        if (bounds != null) {
            var rdg = bounds.toRDG(migration.position());
            var rdgMin = bounds.rdgMin();
            var rdgMax = bounds.rdgMax();

            // Compute the minimum overshoot (distance past the nearest boundary face
            // in RDGCS space). The entity has already escaped so at least one component
            // is outside; we want the maximum — the "most-escaped" dimension — to be
            // large enough to confirm genuine displacement rather than jitter.
            // We use the minimum non-zero overshoot (closest boundary axis).
            int overshootX = rdg.x < rdgMin.x ? rdgMin.x - rdg.x : (rdg.x > rdgMax.x ? rdg.x - rdgMax.x : 0);
            int overshootY = rdg.y < rdgMin.y ? rdgMin.y - rdg.y : (rdg.y > rdgMax.y ? rdg.y - rdgMax.y : 0);
            int overshootZ = rdg.z < rdgMin.z ? rdgMin.z - rdg.z : (rdg.z > rdgMax.z ? rdg.z - rdgMax.z : 0);

            // Total Euclidean RDGCS overshoot magnitude
            double overshootRdg = Math.sqrt(
                (double) overshootX * overshootX + (double) overshootY * overshootY + (double) overshootZ * overshootZ);

            // Convert to approximate Cartesian distance: each RDGCS unit ≈ √2 Cartesian units
            double overshootCartesian = overshootRdg * Math.sqrt(2.0);

            if (overshootCartesian < HYSTERESIS_DIST) {
                log.trace("Hysteresis suppressed migration for entity {}: overshoot={} < threshold={}",
                          entityId, String.format("%.3f", overshootCartesian), HYSTERESIS_DIST);
                return false;  // Too close to boundary — suppress thrashing migration
            }
        }

        return true;  // Passed all checks
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

        // Acquire BOTH bubble mutation locks in consistent UUID order to prevent deadlock.
        // Any two concurrent migrations involving the same pair always acquire in the same order,
        // so no lock-ordering cycle is possible.
        int cmp = srcBubble.id().compareTo(dstBubble.id());
        ReentrantLock firstLock  = cmp <= 0 ? srcBubble.getMutationLock() : dstBubble.getMutationLock();
        ReentrantLock secondLock = cmp <= 0 ? dstBubble.getMutationLock() : srcBubble.getMutationLock();

        firstLock.lock();
        try {
            secondLock.lock();
            try {
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

            } finally {
                secondLock.unlock();
            }
        } catch (Exception e) {
            return false;
        } finally {
            firstLock.unlock();
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
