package com.hellblazer.luciferase.simulation.bubble;

import com.hellblazer.luciferase.simulation.bubble.*;
import com.hellblazer.luciferase.common.time.Clock;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Handles bubble merge (join) based on interaction affinity.
 * <p>
 * Join Algorithm:
 * 1. Detect overlapping bounds
 * 2. Calculate interaction affinity
 * 3. If affinity > 0.6, trigger join
 * 4. Determine smaller/larger by entity count
 * 5. Transfer entities from smaller to larger
 * 6. Recalculate tetrahedral bounds
 * 7. Update VON neighbors
 * 8. Emit BubbleEvent.Merge
 * 9. Shutdown dissolved bubble
 *
 * @author hal.hildebrand
 */
public class BubbleLifecycle {
    private static final float MERGE_THRESHOLD = 0.6f;  // 60% cross-bubble interactions

    private final Consumer<BubbleEvent> eventEmitter;
    private volatile Clock clock = Clock.system();
    private volatile Supplier<UUID> uuidSupplier = UUID::randomUUID;
    // Logical simulation-time bucket source for Merge events. Every other bubble
    // event uses a logical tick count as the bucket; wall-clock milliseconds
    // (the previous behavior) mixed incompatible timestamp semantics and made
    // cross-event causality comparison impossible (Luciferase-0frcy.82). Default
    // 0L until a sim-time source is injected via setBucketSupplier().
    private volatile LongSupplier bucketSupplier = () -> 0L;

    public BubbleLifecycle(Consumer<BubbleEvent> eventEmitter) {
        this.eventEmitter = eventEmitter;
    }

    /**
     * Set the clock for deterministic testing.
     *
     * @param clock Clock instance to use
     */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Inject a UUID supplier for deterministic merged-bubble IDs in tests.
     *
     * @param uuidSupplier supplier of merged-bubble IDs
     */
    public void setUuidSupplier(Supplier<UUID> uuidSupplier) {
        this.uuidSupplier = uuidSupplier;
    }

    /**
     * Inject a logical simulation-time source used to stamp Merge events.
     * The supplied value must be the same logical tick count used by all other
     * bubble events (e.g. {@code executionEngine.getCurrentBucket()}), never
     * wall-clock milliseconds.
     *
     * @param bucketSupplier logical simulation-time (tick count) supplier
     */
    public void setBucketSupplier(LongSupplier bucketSupplier) {
        this.bucketSupplier = bucketSupplier;
    }

    /**
     * Determine if two bubbles should join based on affinity.
     */
    public boolean shouldJoin(EnhancedBubble b1, EnhancedBubble b2, float affinity) {
        return affinity > MERGE_THRESHOLD;
    }

    /**
     * Calculate interaction affinity between two bubbles.
     */
    public float calculateAffinity(EnhancedBubble b1, EnhancedBubble b2, int crossBubbleInteractions, int totalInteractions) {
        if (totalInteractions == 0) {
            return 0.5f; // Boundary value for zero interactions
        }
        return (float) crossBubbleInteractions / totalInteractions;
    }

    /**
     * Perform join operation, merging two bubbles. The Merge event is stamped
     * with the logical simulation-time bucket from the injected bucket supplier.
     */
    public EnhancedBubble performJoin(EnhancedBubble b1, EnhancedBubble b2) {
        return performJoin(b1, b2, bucketSupplier.getAsLong());
    }

    /**
     * Perform join operation, merging two bubbles, stamping the Merge event with
     * the supplied logical simulation-time {@code bucket} (a tick count — never
     * wall-clock milliseconds; see Luciferase-0frcy.82).
     */
    public EnhancedBubble performJoin(EnhancedBubble b1, EnhancedBubble b2, long bucket) {
        int size1 = b1.entityCount();
        int size2 = b2.entityCount();

        // Calculate merged bounds BEFORE transferring (while both have bounds)
        BubbleBounds mergedBounds = calculateMergedBounds(b1.bounds(), b2.bounds());

        // Create a new merged bubble with a (possibly injected) ID.
        // Use the finer (min) spatial level and the larger frame budget of the
        // source bubbles so the merge never coarsens spatial resolution and the
        // result has enough frame time to cover the combined entity load.
        byte mergedLevel = (byte) Math.min(b1.getSpatialLevel(), b2.getSpatialLevel());
        long mergedFrameMs = Math.max(b1.getTargetFrameMs(), b2.getTargetFrameMs());
        var merged = new EnhancedBubble(uuidSupplier.get(), mergedLevel, mergedFrameMs);

        // Transfer all entities from both bubbles to the new merged bubble
        transferEntities(b1, merged);
        transferEntities(b2, merged);

        // Merge VON neighbors: merged gets union of both neighbor sets
        for (UUID neighbor : b1.getVonNeighbors()) {
            merged.addVonNeighbor(neighbor);
        }
        for (UUID neighbor : b2.getVonNeighbors()) {
            merged.addVonNeighbor(neighbor);
        }

        // Emit merge event stamped with the logical simulation-time bucket
        // (tick count), consistent with all other bubble events.
        eventEmitter.accept(new BubbleEvent.Merge(
            b1.id(), b2.id(), merged.id(),
            bucket, size1, size2
        ));

        return merged;
    }

    /**
     * Calculate merged bounds encompassing both bubbles.
     */
    public BubbleBounds calculateMergedBounds(BubbleBounds bounds1, BubbleBounds bounds2) {
        return BubbleBounds.encompassing(bounds1, bounds2);
    }

    /**
     * Update VON neighbors after merge.
     */
    public void updateVonNeighbors(EnhancedBubble merged, EnhancedBubble dissolved1, EnhancedBubble dissolved2, List<EnhancedBubble> affectedNeighbors) {
        // For each affected neighbor, update their VON neighbor references
        for (EnhancedBubble neighbor : affectedNeighbors) {
            // Remove references to both dissolved bubbles
            // (One will be the merged bubble, but remove+add ensures correct state)
            neighbor.removeVonNeighbor(dissolved1.id());
            neighbor.removeVonNeighbor(dissolved2.id());

            // Add reference to merged bubble
            neighbor.addVonNeighbor(merged.id());
        }
    }

    /**
     * Transfer entities from source to target bubble.
     */
    public void transferEntities(EnhancedBubble source, EnhancedBubble target) {
        // Idempotent: if source is already empty, nothing to do
        if (source.entityCount() == 0) {
            return;
        }

        // Get all entities from source
        var entities = source.getAllEntityRecords();

        // Transfer each entity to target
        for (var entity : entities) {
            target.addEntity(entity.id(), entity.position(), entity.content());
        }

        // Remove all entities from source
        for (var entity : entities) {
            source.removeEntity(entity.id());
        }
    }
}
