package com.hellblazer.luciferase.simulation;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

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

    public BubbleLifecycle(Consumer<BubbleEvent> eventEmitter) {
        this.eventEmitter = eventEmitter;
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
     * Perform join operation, merging two bubbles.
     */
    public EnhancedBubble performJoin(EnhancedBubble b1, EnhancedBubble b2) {
        int size1 = b1.entityCount();
        int size2 = b2.entityCount();

        // Calculate merged bounds BEFORE transferring (while both have bounds)
        BubbleBounds mergedBounds = calculateMergedBounds(b1.bounds(), b2.bounds());

        // Create a new merged bubble with a new ID
        // Use the same spatial level and target frame time as the first bubble
        var merged = new EnhancedBubble(UUID.randomUUID(), (byte) 10, 10L);

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

        // Emit merge event (bucket = current time, can be refined later)
        long bucket = System.currentTimeMillis();
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
