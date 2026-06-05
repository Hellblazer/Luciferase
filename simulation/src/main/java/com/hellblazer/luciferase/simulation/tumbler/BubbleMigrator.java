/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.tumbler;

import com.hellblazer.luciferase.common.time.Clock;
import com.hellblazer.luciferase.simulation.persistence.PersistenceManager;
import com.hellblazer.luciferase.simulation.von.Bubble;
import com.hellblazer.luciferase.simulation.von.Manager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiFunction;

/**
 * Orchestrates bubble migration between servers for load balancing.
 * <p>
 * Migration Protocol (per v4.0 architecture):
 * <ol>
 *   <li>Bubble B selected for migration from Server1 to Server2</li>
 *   <li>Server2 creates new Bubble B' (same bubbleId)</li>
 *   <li>B' sends VON MOVE to neighbors (position unchanged!)</li>
 *   <li>Neighbors update routing: bubbleId → Server2</li>
 *   <li>Server1 deactivates B after confirmation</li>
 *   <li>Ghost sync re-established with new server</li>
 * </ol>
 * <p>
 * Note: Migration does NOT change bubble position - only server assignment.
 * VON neighbors are notified via overlay maintenance protocol.
 */
public class BubbleMigrator {

    private static final Logger log = LoggerFactory.getLogger(BubbleMigrator.class);

    private volatile Clock clock = Clock.system();

    private final SpatialTumbler tumbler;
    private final Duration migrationTimeout;
    private final Duration cooldownPeriod;
    private final int maxConcurrentMigrations;

    // How long to wait for neighbor ACKs after broadcastMoveAsync().
    // Defaults to half of migrationTimeout so the overall timeout still catches hangs.
    // Settable for testing (TestClock / deterministic scenarios).
    private volatile Duration neighborAckTimeout;

    // Track in-flight migrations
    private final Map<UUID, MigrationState> inFlightMigrations = new ConcurrentHashMap<>();

    // Cooldown tracking (bubbleId -> last migration time)
    private final Map<UUID, Long> migrationCooldowns = new ConcurrentHashMap<>();

    // Server-specific managers (serverId -> Manager)
    private final Map<UUID, Manager> serverManagers = new ConcurrentHashMap<>();

    // Bubble factory for creating bubbles on target server
    private BiFunction<UUID, Bubble, Bubble> bubbleTransferFactory;

    // Optional WAL persistence manager. When set, ENTITY_DEPARTURE and MIGRATION_COMMIT
    // events are logged to bracket the transactional commit window (Luciferase-7wzml.45).
    // Null = WAL logging disabled (default).
    private volatile PersistenceManager persistenceManager;

    // Dedicated executor for migration tasks. executeMigration() blocks (it waits for neighbor
    // acknowledgments), so it must NOT run on ForkJoinPool.commonPool() — blocking common-pool
    // workers starves every other async operation in the JVM (Luciferase-0frcy.116). The pool is
    // sized to maxConcurrentMigrations since that is the ceiling of simultaneous in-flight tasks.
    private final ExecutorService migrationExecutor;

    public BubbleMigrator(SpatialTumbler tumbler) {
        this(tumbler, Duration.ofSeconds(1), Duration.ofSeconds(5), 3);
    }

    public BubbleMigrator(SpatialTumbler tumbler, Duration migrationTimeout,
                          Duration cooldownPeriod, int maxConcurrentMigrations) {
        this.tumbler = tumbler;
        this.migrationTimeout = migrationTimeout;
        this.cooldownPeriod = cooldownPeriod;
        this.maxConcurrentMigrations = maxConcurrentMigrations;
        // Floor at 50 ms: integer division produces 0 when migrationTimeout < 2 ms, which
        // causes ~100% migration failure because every neighbor-ack times out immediately.
        this.neighborAckTimeout = Duration.ofMillis(Math.max(50, migrationTimeout.toMillis() / 2));
        this.migrationExecutor = Executors.newFixedThreadPool(
            Math.max(1, maxConcurrentMigrations),
            r -> {
                var t = new Thread(r, "bubble-migrator");
                t.setDaemon(true);
                return t;
            });
        log.info("BubbleMigrator created: timeout={}ms, cooldown={}ms, maxConcurrent={}",
                 migrationTimeout.toMillis(), cooldownPeriod.toMillis(), maxConcurrentMigrations);
    }

    /**
     * Shut down the dedicated migration executor. Call when the migrator is no longer needed to
     * release its threads.
     */
    public void shutdown() {
        migrationExecutor.shutdownNow();
    }

    /**
     * Set the clock source for deterministic testing.
     */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Override the neighbor-ack timeout. Default is half of migrationTimeout.
     * Use in tests to set an explicit window (e.g., very short for timeout tests).
     */
    public void setNeighborAckTimeout(Duration timeout) {
        this.neighborAckTimeout = timeout;
    }

    /**
     * Register a Manager for a server.
     */
    public void registerServerManager(UUID serverId, Manager manager) {
        serverManagers.put(serverId, manager);
    }

    /**
     * Set the factory for transferring bubbles between servers.
     * The factory takes (targetServerId, sourceBubble) and returns
     * the new bubble on the target server.
     */
    public void setBubbleTransferFactory(BiFunction<UUID, Bubble, Bubble> factory) {
        this.bubbleTransferFactory = factory;
    }

    /**
     * Set an optional PersistenceManager for WAL-bracketed migration commit logging.
     * When set, ENTITY_DEPARTURE events are written before entity staging and
     * MIGRATION_COMMIT events are written at the commit point (after ACKs, before source.close()).
     * Null disables WAL logging (default).
     */
    public void setPersistenceManager(PersistenceManager persistenceManager) {
        this.persistenceManager = persistenceManager;
    }

    /**
     * Execute a single migration.
     *
     * @param bubble         the bubble to migrate
     * @param sourceServerId the server currently hosting the bubble; used to decrement source metrics
     *                       after a successful migration (Luciferase-7wzml.44)
     * @param targetServerId the destination server
     * @return CompletableFuture with migration result
     */
    public CompletableFuture<MigrationResult> migrate(Bubble bubble, UUID sourceServerId, UUID targetServerId) {
        var bubbleId = bubble.id();

        // Check cooldown (read-only, safe before reservation)
        var lastMigration = migrationCooldowns.get(bubbleId);
        if (lastMigration != null) {
            var elapsed = clock.currentTimeMillis() - lastMigration;
            if (elapsed < cooldownPeriod.toMillis()) {
                return CompletableFuture.completedFuture(
                    new MigrationResult(bubbleId, targetServerId, false,
                                        "In cooldown (" + (cooldownPeriod.toMillis() - elapsed) + "ms remaining)", 0)
                );
            }
        }

        // Luciferase-0frcy.38: atomically reserve the in-flight slot. The previous code did a
        // containsKey() check, a size() check, and a put() as three separate operations on the
        // ConcurrentHashMap — two threads racing on the same bubbleId could both observe
        // containsKey=false and both proceed, the second put() silently overwriting the first
        // (duplicate migration). putIfAbsent makes the duplicate guard atomic.
        long startTime = clock.nanoTime();
        var state = new MigrationState(bubbleId, targetServerId, startTime);
        var existing = inFlightMigrations.putIfAbsent(bubbleId, state);
        if (existing != null) {
            return CompletableFuture.completedFuture(
                new MigrationResult(bubbleId, targetServerId, false, "Already migrating", 0)
            );
        }

        // Check concurrent migration limit AFTER reserving, then back out if we exceeded it. Our
        // own entry is included in size(), so the limit is honored: the (maxConcurrent+1)-th
        // concurrent reservation removes itself and is rejected.
        if (inFlightMigrations.size() > maxConcurrentMigrations) {
            inFlightMigrations.remove(bubbleId, state);
            return CompletableFuture.completedFuture(
                new MigrationResult(bubbleId, targetServerId, false,
                                    "Max concurrent migrations reached", 0)
            );
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                return executeMigration(bubble, sourceServerId, targetServerId, startTime);
            } finally {
                // Luciferase-0frcy.38: value-conditional removal so we only clear OUR reservation,
                // never a subsequent re-reservation of the same bubbleId by another thread.
                inFlightMigrations.remove(bubbleId, state);
            }
        }, migrationExecutor) // dedicated pool — never block ForkJoinPool.commonPool (0frcy.116)
          .orTimeout(migrationTimeout.toMillis(), TimeUnit.MILLISECONDS)
          .exceptionally(ex -> {
              // The supplyAsync body's finally already removed our entry on normal completion; this
              // value-conditional removal covers the timeout path (orTimeout completes the stage
              // exceptionally without running the body's finally).
              inFlightMigrations.remove(bubbleId, state);
              return new MigrationResult(bubbleId, targetServerId, false,
                                         "Timeout or error: " + ex.getMessage(), 0);
          });
    }

    /**
     * Execute the actual migration protocol.
     *
     * <p><b>Transactional guarantee (Luciferase-7wzml.45):</b> entities are never visible in
     * BOTH bubbles simultaneously at any observable point. The protocol uses a
     * WAL-bracketed two-phase commit:
     * <ol>
     *   <li>Snapshot source entities (outside the try — no side-effects yet).</li>
     *   <li>WAL bracket open: ENTITY_DEPARTURE logged per entity (if persistenceManager set).
     *       Inside the try so a fatal WAL error triggers rollback.</li>
     *   <li>Stage entities onto target (addEntity per entity). Inside the try — if addEntity
     *       throws mid-loop the catch removes all already-staged entities and leaves source open.</li>
     *   <li>Target broadcasts MOVE + waits for neighbor ACKs (the "pre-commit" window).
     *       Source is still authoritative here.</li>
     *   <li>COMMIT POINT: WAL MIGRATION_COMMIT logged (if persistenceManager set), then
     *       source.close() — source deactivated. Target becomes the sole authoritative copy.</li>
     *   <li>Source metrics decremented exactly once (.44 behavior preserved).</li>
     * </ol>
     * On ANY exception before the commit point: all staged entities are removed from the target
     * bubble and the source bubble is left open. The rollback is:
     * {@code entities.forEach(e -> targetBubble.removeEntity(e.id()));}
     * Metrics are never updated on rollback so they cannot double-count.
     */
    private MigrationResult executeMigration(Bubble sourceBubble, UUID sourceServerId, UUID targetServerId,
                                             long startTime) {
        var bubbleId = sourceBubble.id();

        log.info("Starting migration of bubble {} to server {}", bubbleId, targetServerId);

        // Step 1: Create bubble on target server (before acquiring entity snapshot)
        if (bubbleTransferFactory == null) {
            return new MigrationResult(bubbleId, targetServerId, false,
                                       "No bubble transfer factory configured", 0);
        }

        var targetBubble = bubbleTransferFactory.apply(targetServerId, sourceBubble);
        if (targetBubble == null) {
            return new MigrationResult(bubbleId, targetServerId, false,
                                       "Failed to create bubble on target server", 0);
        }

        // Step 2: Snapshot entities from source (source still authoritative).
        var entities = sourceBubble.getAllEntityRecords();

        // Steps 2b–5 run inside a try/catch so any failure (including a mid-staging
        // addEntity throw) rolls back the staging.
        // On exception: remove all staged entities from target (rollback); leave source open.
        // Metrics are NEVER updated in the rollback path — no double-count possible.
        try {
            // WAL bracket open: ENTITY_DEPARTURE logged before staging (inside try so a WAL
            // failure that is re-thrown would also be rolled back, though in practice the
            // per-entity catch absorbs WAL failures as warnings).
            if (persistenceManager != null) {
                for (var entity : entities) {
                    try {
                        persistenceManager.logEntityDeparture(
                            UUID.fromString(entity.id()), bubbleId, targetBubble.id());
                    } catch (java.io.IOException | IllegalArgumentException walEx) {
                        log.warn("WAL ENTITY_DEPARTURE log failed for entity {} in bubble {}: {}",
                                 entity.id(), bubbleId, walEx.getMessage());
                    }
                }
            }

            // Stage entities onto target — source still authoritative (not yet closed).
            // If addEntity throws mid-loop the catch below removes all already-staged entities.
            for (var entity : entities) {
                targetBubble.addEntity(entity.id(), entity.position(), entity.content());
            }
            log.debug("Staged {} entities onto target {} (source {} still authoritative)",
                      entities.size(), targetBubble.id(), bubbleId);

            // Step 3: Target bubble sends MOVE to neighbors (position unchanged) and
            // Step 4: Wait for neighbor ACKs with a clock-derived deadline.
            // broadcastMoveAsync fires sendToNeighborAsync per neighbor and returns a
            // CompletableFuture<Void> that completes when all ACKs arrive (or
            // immediately when there are no neighbors).
            // On timeout we treat the migration as failed — no silent success when
            // neighbors haven't confirmed the server change (mt7hi clock-injection sweep).
            var ackTimeoutMs = neighborAckTimeout.toMillis();
            var ackDeadline = clock.currentTimeMillis() + ackTimeoutMs;
            try {
                targetBubble.broadcastMoveAsync()
                            .get(ackTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException ackTimeout) {
                var elapsed = clock.currentTimeMillis() - (ackDeadline - ackTimeoutMs);
                log.warn("Neighbor ACK timeout after {}ms for bubble {} migration to server {}",
                         elapsed, bubbleId, targetServerId);
                throw new RuntimeException("neighbor ack timeout after " + elapsed + "ms");
            } catch (ExecutionException ackEx) {
                log.warn("Neighbor ACK failed for bubble {} migration to server {}: {}",
                         bubbleId, targetServerId, ackEx.getCause().getMessage());
                throw new RuntimeException("neighbor ack failed: " + ackEx.getCause().getMessage(), ackEx.getCause());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("neighbor ack interrupted", ie);
            }
            // ackDeadline used only for the elapsed-time log message above; no raw sleep.

            // COMMIT POINT — no rollback after this line.
            // WAL bracket close: MIGRATION_COMMIT logged per entity before source is deactivated.
            if (persistenceManager != null) {
                for (var entity : entities) {
                    try {
                        persistenceManager.logMigrationCommit(
                            UUID.fromString(entity.id()));
                    } catch (java.io.IOException | IllegalArgumentException walEx) {
                        log.warn("WAL MIGRATION_COMMIT log failed for entity {} in bubble {}: {}",
                                 entity.id(), bubbleId, walEx.getMessage());
                    }
                }
            }

            // Step 5: Deactivate source bubble — target is now the sole authoritative copy.
            sourceBubble.close();

            // Step 6: Update metrics — sourceServerId is threaded in from the caller who knows
            // which server owns the bubble. This replaces the hollow getServerForBubble() stub that
            // unconditionally returned null, which caused the source ServerMetrics to never be
            // decremented after a migration (Luciferase-7wzml.44).
            // Runs exactly once on the success path; skipped entirely on rollback.
            var sourceMetrics = tumbler.getServerMetrics(sourceServerId);
            var targetMetrics = tumbler.getServerMetrics(targetServerId);

            if (sourceMetrics != null) {
                sourceMetrics.removeBubble(sourceBubble.entityCount());
            }
            if (targetMetrics != null) {
                targetMetrics.addBubble(targetBubble.entityCount());
            }

            // Record cooldown
            migrationCooldowns.put(bubbleId, clock.currentTimeMillis());

            long durationMs = (clock.nanoTime() - startTime) / 1_000_000;
            log.info("Migration complete: bubble {} to server {} in {}ms",
                     bubbleId, targetServerId, durationMs);

            return new MigrationResult(bubbleId, targetServerId, true, "Success", durationMs);

        } catch (Exception e) {
            // ROLLBACK: remove all staged entities from target so no entity exists in both bubbles.
            // Source is left open (close() was not called); it remains authoritative.
            log.warn("Migration of bubble {} failed, rolling back {} staged entities from target {}: {}",
                     bubbleId, entities.size(), targetBubble.id(), e.getMessage());
            entities.forEach(entity -> targetBubble.removeEntity(entity.id()));
            log.info("Rollback complete: {} entities removed from target {}; source {} remains authoritative",
                     entities.size(), targetBubble.id(), bubbleId);
            return new MigrationResult(bubbleId, targetServerId, false,
                                       "Error: " + e.getMessage(), 0);
        }
    }

    /**
     * Transfer entities from source bubble to target bubble.
     *
     * @deprecated Replaced by inline staging in {@link #executeMigration} for transactional
     *             rollback support (Luciferase-7wzml.45). Retained for API compatibility.
     */
    @Deprecated
    private void transferEntities(Bubble source, Bubble target) {
        // Get all entities from source
        var entities = source.getAllEntityRecords();

        // Add each entity to target
        for (var entity : entities) {
            target.addEntity(entity.id(), entity.position(), entity.content());
        }

        log.debug("Transferred {} entities from {} to {}",
                  entities.size(), source.id(), target.id());
    }

    /**
     * Run a migration cycle based on current load imbalance.
     *
     * @return Number of migrations initiated
     */
    public int runMigrationCycle(Map<UUID, List<Bubble>> serverBubbles) {
        var candidates = tumbler.findMigrationCandidates();
        if (candidates.isEmpty()) {
            return 0;
        }

        int initiated = 0;
        for (var candidate : candidates) {
            var sourceBubbles = serverBubbles.get(candidate.sourceServer());
            if (sourceBubbles == null || sourceBubbles.isEmpty()) {
                continue;
            }

            // Select a bubble to migrate (prefer most loaded)
            var bubble = selectBubbleForMigration(sourceBubbles);
            if (bubble == null) {
                continue;
            }

            migrate(bubble, candidate.sourceServer(), candidate.targetServer());
            initiated++;

            if (initiated >= maxConcurrentMigrations) {
                break;
            }
        }

        return initiated;
    }

    /**
     * Select a bubble for migration from a list.
     * Prefers bubbles with more entities (higher load contribution).
     */
    private Bubble selectBubbleForMigration(List<Bubble> bubbles) {
        return bubbles.stream()
                      .filter(b -> !inFlightMigrations.containsKey(b.id()))
                      .filter(b -> !isInCooldown(b.id()))
                      .max(Comparator.comparingInt(Bubble::entityCount))
                      .orElse(null);
    }

    private boolean isInCooldown(UUID bubbleId) {
        var lastMigration = migrationCooldowns.get(bubbleId);
        if (lastMigration == null) {
            return false;
        }
        return (clock.currentTimeMillis() - lastMigration) < cooldownPeriod.toMillis();
    }

    /**
     * Get count of in-flight migrations.
     */
    public int inFlightCount() {
        return inFlightMigrations.size();
    }

    /**
     * Clean up stale cooldown entries.
     */
    public void cleanupCooldowns() {
        long now = clock.currentTimeMillis();
        long threshold = cooldownPeriod.toMillis() * 2;
        migrationCooldowns.entrySet().removeIf(e -> (now - e.getValue()) > threshold);
    }

    /**
     * Migration state tracking.
     */
    private record MigrationState(UUID bubbleId, UUID targetServerId, long startTimeNanos) {
    }

    /**
     * Migration result.
     */
    public record MigrationResult(
        UUID bubbleId,
        UUID targetServerId,
        boolean success,
        String message,
        long durationMs
    ) {
    }
}
