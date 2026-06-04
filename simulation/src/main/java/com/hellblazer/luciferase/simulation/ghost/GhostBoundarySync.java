package com.hellblazer.luciferase.simulation.ghost;

import com.hellblazer.luciferase.simulation.ghost.*;

import com.hellblazer.luciferase.simulation.bubble.*;

import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostEntityHalo;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
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
     * Epoch size in buckets (epoch = bucket / EPOCH_SIZE).
     */
    public static final int EPOCH_SIZE = 100;

    /**
     * Monotonic version counter for ghost versioning.
     */
    private final AtomicLong versionCounter = new AtomicLong(0);

    /**
     * Derive epoch from bucket number.
     * Epoch changes every EPOCH_SIZE buckets (10 seconds @ 100ms/bucket).
     *
     * @param bucket Bucket number
     * @return Epoch number
     */
    private long deriveEpoch(long bucket) {
        return bucket / EPOCH_SIZE;
    }

    /**
     * Ghost entry with metadata.
     */
    private static class GhostEntry<ID extends EntityID, Content> {
        final GhostEntityHalo<ID, Content> ghost;
        final UUID sourceBubbleId;
        final long bucket;
        /**
         * Bucket at which this entry was last transmitted to its neighbor, or -1 if never sent.
         * Used to suppress redundant re-broadcast of unchanged ghosts (Luciferase-0frcy.101).
         */
        volatile long lastSentBucket = -1L;

        GhostEntry(GhostEntityHalo<ID, Content> ghost, UUID sourceBubbleId, long bucket) {
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
     * @param ghostEntity    Ghost entity halo (GhostEntityHalo)
     * @param sourceBubbleId Source bubble ID (for VON discovery)
     * @param neighborId     Neighbor to send ghost to
     * @param bucket         Current bucket number
     */
    public void addGhost(
        GhostEntityHalo<ID, Content> ghostEntity,
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

            // Luciferase-0frcy.101: only transmit ghosts that changed since their last send (dirty),
            // plus a heartbeat for unchanged ghosts approaching TTL expiry so they are refreshed before
            // they would otherwise be culled. This replaces the previous unconditional re-broadcast of
            // every tracked ghost on every bucket boundary (O(activeGhosts*neighbors) per bucket).
            var toSend = ghosts.values().stream()
                .filter(e -> shouldTransmit(e, bucket))
                .collect(Collectors.toList());

            if (toSend.isEmpty()) {
                continue;
            }

            var ghostBatch = toSend.stream()
                .map(e -> new SimulationGhostEntity<>(
                    e.ghost,
                    e.sourceBubbleId,
                    e.bucket,
                    deriveEpoch(e.bucket),           // epoch from bucket
                    versionCounter.incrementAndGet() // monotonic version
                ))
                .collect(Collectors.toList());

            ghostSender.accept(neighborId, ghostBatch);

            // Mark transmitted entries as sent at this bucket and notify trackers/health.
            for (var ghostEntry : toSend) {
                ghostEntry.lastSentBucket = bucket;
                bubbleTracker.recordGhostInteraction(ghostEntry.sourceBubbleId);
                health.recordGhostSource(ghostEntry.sourceBubbleId);
            }
        }

        // Expire stale ghosts. expireStaleGhosts() and enforceMemoryLimit() record evicted ids in
        // expiredGhosts (a per-bucket diagnostic read by getExpiredGhostCount()). Luciferase-zwyf2:
        // clear it at the start of each bucket so it tracks only this bucket's evictions instead of
        // accumulating one entry per eviction for the entire simulation lifetime (unbounded leak).
        expiredGhosts.clear();
        expireStaleGhosts(bucket);
    }

    /**
     * Decide whether a ghost entry must be transmitted at the given bucket boundary
     * (Luciferase-0frcy.101).
     * <p>
     * Returns true when the entry is <em>dirty</em> — never sent, or (re)added since it was last sent
     * ({@code lastSentBucket < bucket} for an entry whose own {@code bucket == bucket}) — or when it is
     * an unchanged entry that is approaching TTL expiry and needs a heartbeat refresh so the neighbor's
     * copy is not culled. An unchanged ghost that was recently sent is suppressed.
     *
     * @param e      the ghost entry
     * @param bucket the bucket boundary being processed
     * @return true if the entry should be included in this bucket's batch
     */
    private boolean shouldTransmit(GhostEntry<ID, Content> e, long bucket) {
        if (e.lastSentBucket < 0) {
            return true; // never sent
        }
        if (e.bucket >= bucket && e.lastSentBucket < e.bucket) {
            return true; // updated since last send (dirty)
        }
        // Heartbeat: refresh unchanged ghosts before they would expire at the neighbor.
        return (bucket - e.lastSentBucket) >= (GHOST_TTL_BUCKETS - 1);
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
                deriveEpoch(e.bucket),           // epoch from bucket
                versionCounter.incrementAndGet() // monotonic version
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
