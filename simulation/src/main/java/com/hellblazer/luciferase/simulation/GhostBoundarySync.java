package com.hellblazer.luciferase.simulation;

import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostZoneManager;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * Batched ghost entity synchronization with TTL and memory limits.
 * <p>
 * GhostBoundarySync manages ghost entities at bubble boundaries:
 * - Batch ghosts at bucket boundaries (100ms intervals)
 * - Expire stale ghosts (500ms TTL = 5 buckets)
 * - Enforce memory limits (1000 ghosts per neighbor)
 * - Group entities by neighbor region for efficient transmission
 * <p>
 * VON integration:
 * - Ghost layer implements VON "boundary neighbors" pattern
 * - When ghost arrives, learn about source bubble (distributed discovery)
 * - No global bubble registry needed
 * <p>
 * Usage:
 * <pre>
 * var sync = new GhostBoundarySync<>(
 *     bubbleTracker,
 *     health,
 *     (neighborId, ghosts) -> sendToNeighbor(neighborId, ghosts)
 * );
 *
 * // On bucket boundary
 * sync.onBucketComplete(currentBucket);
 *
 * // When entity near boundary
 * sync.addGhost(ghostEntity, sourceBubbleId, neighborId, currentBucket);
 * </pre>
 *
 * @param <ID>      Entity identifier type
 * @param <Content> Entity content type
 * @author hal.hildebrand
 */
public class GhostBoundarySync<ID extends EntityID, Content> {

    /**
     * Ghost TTL in buckets (500ms = 5 buckets @ 100ms/bucket).
     */
    public static final int GHOST_TTL_BUCKETS = 5;

    /**
     * Maximum ghosts per neighbor (memory limit).
     */
    public static final int MAX_GHOSTS_PER_NEIGHBOR = 1000;

    /**
     * Ghost entry with metadata.
     */
    private static class GhostEntry<ID extends EntityID, Content> {
        final GhostZoneManager.GhostEntity<ID, Content> ghost;
        final UUID sourceBubbleId;
        final long bucket;

        GhostEntry(GhostZoneManager.GhostEntity<ID, Content> ghost, UUID sourceBubbleId, long bucket) {
            this.ghost = ghost;
            this.sourceBubbleId = sourceBubbleId;
            this.bucket = bucket;
        }
    }

    private final ExternalBubbleTracker bubbleTracker;
    private final GhostLayerHealth health;
    private final BiConsumer<UUID, List<SimulationGhostEntity<ID, Content>>> ghostSender;

    // neighborId -> (entityId -> GhostEntry)
    private final Map<UUID, Map<ID, GhostEntry<ID, Content>>> ghostsByNeighbor;
    private final Map<ID, Long> expiredGhosts;

    /**
     * Create a ghost boundary sync manager.
     *
     * @param bubbleTracker External bubble tracker for discovery
     * @param health        Ghost layer health monitor
     * @param ghostSender   Callback to send ghost batch to neighbor
     */
    public GhostBoundarySync(
        ExternalBubbleTracker bubbleTracker,
        GhostLayerHealth health,
        BiConsumer<UUID, List<SimulationGhostEntity<ID, Content>>> ghostSender
    ) {
        this.bubbleTracker = bubbleTracker;
        this.health = health;
        this.ghostSender = ghostSender;
        this.ghostsByNeighbor = new ConcurrentHashMap<>();
        this.expiredGhosts = new ConcurrentHashMap<>();
    }

    /**
     * Add or update a ghost entity for a neighbor.
     * <p>
     * Call when entity is near boundary: ghost zone overlaps with neighbor.
     *
     * @param ghostEntity    Ghost entity from GhostZoneManager
     * @param sourceBubbleId Source bubble ID (for VON discovery)
     * @param neighborId     Neighbor to send ghost to
     * @param bucket         Current bucket number
     */
    public void addGhost(
        GhostZoneManager.GhostEntity<ID, Content> ghostEntity,
        UUID sourceBubbleId,
        UUID neighborId,
        long bucket
    ) {
        var entry = new GhostEntry<>(ghostEntity, sourceBubbleId, bucket);

        ghostsByNeighbor.computeIfAbsent(neighborId, k -> new ConcurrentHashMap<>())
                       .put(ghostEntity.getEntityId(), entry);

        // Enforce memory limit per neighbor
        enforceMemoryLimit(neighborId);
    }

    /**
     * Called at bucket boundary to send batched ghosts.
     * <p>
     * Sends all active ghosts grouped by neighbor, then expires stale ghosts.
     *
     * @param bucket Bucket that just completed
     */
    public void onBucketComplete(long bucket) {
        // Send ghost batches to all neighbors
        for (var entry : ghostsByNeighbor.entrySet()) {
            var neighborId = entry.getKey();
            var ghosts = entry.getValue();

            if (ghosts.isEmpty()) {
                continue;
            }

            // Convert to SimulationGhostEntity and send batch
            var ghostBatch = ghosts.values().stream()
                .map(e -> new SimulationGhostEntity<>(
                    e.ghost,
                    e.sourceBubbleId,
                    e.bucket,
                    0L,  // epoch (placeholder)
                    0L   // version (placeholder)
                ))
                .collect(Collectors.toList());

            ghostSender.accept(neighborId, ghostBatch);

            // Notify bubble tracker and health of discovered bubbles
            for (var ghostEntry : ghosts.values()) {
                bubbleTracker.recordGhostInteraction(ghostEntry.sourceBubbleId);
                health.recordGhostSource(ghostEntry.sourceBubbleId);
            }
        }

        // Expire stale ghosts
        expireStaleGhosts(bucket);
    }

    /**
     * Expire ghosts beyond TTL window.
     *
     * @param currentBucket Current bucket number
     */
    public void expireStaleGhosts(long currentBucket) {
        long expirationBucket = currentBucket - GHOST_TTL_BUCKETS;

        for (var neighborEntry : ghostsByNeighbor.entrySet()) {
            var ghosts = neighborEntry.getValue();

            var toRemove = ghosts.values().stream()
                .filter(e -> e.bucket < expirationBucket)
                .collect(Collectors.toList());

            for (var entry : toRemove) {
                ghosts.remove(entry.ghost.getEntityId());
                expiredGhosts.put(entry.ghost.getEntityId(), entry.bucket);
            }
        }
    }

    /**
     * Enforce memory limit per neighbor (MAX_GHOSTS_PER_NEIGHBOR).
     * <p>
     * Evicts oldest ghosts when limit exceeded.
     *
     * @param neighborId Neighbor to check
     */
    private void enforceMemoryLimit(UUID neighborId) {
        var ghosts = ghostsByNeighbor.get(neighborId);
        if (ghosts == null || ghosts.size() <= MAX_GHOSTS_PER_NEIGHBOR) {
            return;
        }

        // Find oldest ghosts to evict
        var sorted = ghosts.values().stream()
            .sorted(Comparator.comparingLong(e -> e.bucket))
            .collect(Collectors.toList());

        int toRemove = sorted.size() - MAX_GHOSTS_PER_NEIGHBOR;
        for (int i = 0; i < toRemove; i++) {
            var entry = sorted.get(i);
            ghosts.remove(entry.ghost.getEntityId());
            expiredGhosts.put(entry.ghost.getEntityId(), entry.bucket);
        }
    }

    /**
     * Get active ghost count across all neighbors.
     *
     * @return Total number of active ghosts
     */
    public int getActiveGhostCount() {
        return ghostsByNeighbor.values().stream()
            .mapToInt(Map::size)
            .sum();
    }

    /**
     * Get expired ghost count.
     *
     * @return Number of ghosts that have expired
     */
    public int getExpiredGhostCount() {
        return expiredGhosts.size();
    }

    /**
     * Get ghosts for a specific neighbor.
     *
     * @param neighborId Neighbor UUID
     * @return List of ghost entities for this neighbor
     */
    public List<SimulationGhostEntity<ID, Content>> getGhostsByNeighbor(UUID neighborId) {
        var ghosts = ghostsByNeighbor.get(neighborId);
        if (ghosts == null) {
            return List.of();
        }

        return ghosts.values().stream()
            .map(e -> new SimulationGhostEntity<>(
                e.ghost,
                e.sourceBubbleId,
                e.bucket,
                0L,  // epoch
                0L   // version
            ))
            .collect(Collectors.toList());
    }

    /**
     * Clear expired ghosts from tracking.
     */
    public void clearExpiredGhosts() {
        expiredGhosts.clear();
    }

    /**
     * Remove all ghosts for a neighbor (e.g., neighbor left).
     *
     * @param neighborId Neighbor UUID
     */
    public void removeNeighbor(UUID neighborId) {
        ghostsByNeighbor.remove(neighborId);
    }

    /**
     * Get all tracked neighbor IDs.
     *
     * @return Set of neighbor UUIDs
     */
    public Set<UUID> getTrackedNeighbors() {
        return Collections.unmodifiableSet(ghostsByNeighbor.keySet());
    }

    @Override
    public String toString() {
        return String.format("GhostBoundarySync{neighbors=%d, activeGhosts=%d, expired=%d}",
                            ghostsByNeighbor.size(), getActiveGhostCount(), expiredGhosts.size());
    }
}
