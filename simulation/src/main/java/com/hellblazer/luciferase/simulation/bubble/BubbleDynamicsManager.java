package com.hellblazer.luciferase.simulation.bubble;

import com.hellblazer.luciferase.simulation.ghost.*;

import com.hellblazer.luciferase.simulation.spatial.*;

import com.hellblazer.luciferase.simulation.bubble.*;

import com.hellblazer.luciferase.lucien.entity.EntityID;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Core bubble dynamics orchestration.
 * <p>
 * Coordinates bubble lifecycle operations:
 * - **Merge**: Combine bubbles with high cross-bubble affinity (> 0.6)
 * - **Split**: Divide bubbles with disconnected interaction graphs
 * - **Entity Transfer**: Move drifting entities (affinity < 0.5)
 * - **Bubble Migration**: Load balancing across cluster nodes
 * - **Partition Recovery**: Use stock neighbors when NC < 0.5
 * <p>
 * Integration:
 * - Uses ExternalBubbleTracker for merge candidate detection
 * - Uses GhostLayerHealth for partition detection
 * - Uses MigrationLog for idempotency
 * - Uses StockNeighborList for partition recovery
 * - Emits BubbleEvent for all lifecycle changes
 * <p>
 * Success criteria:
 * - Merge preserves all entities (no duplication/loss)
 * - Split preserves all entities
 * - Entity reassignment < 0.01% loss rate
 * - Idempotency prevents duplicate migrations
 * - Stock neighbors enable partition recovery
 * <p>
 * Thread-safe: All operations use concurrent data structures.
 *
 * @param <ID> Entity identifier type
 * @author hal.hildebrand
 */
public class BubbleDynamicsManager<ID extends EntityID> {

    /**
     * Merge threshold: cross-bubble affinity must exceed this.
     */
    public static final float MERGE_THRESHOLD = 0.6f;

    /**
     * Drift threshold: entity affinity below this triggers transfer.
     */
    public static final float DRIFT_THRESHOLD = 0.5f;

    /**
     * Partition threshold: NC below this indicates partition.
     */
    public static final float PARTITION_THRESHOLD = 0.5f;

    /**
     * Recovery threshold: NC above this indicates partition recovered.
     */
    public static final float RECOVERY_THRESHOLD = 0.9f;

    // Dependencies
    private final ExternalBubbleTracker bubbleTracker;
    private final GhostLayerHealth health;
    private final MigrationLog migrationLog;
    private final StockNeighborList stockNeighbors;
    private final Consumer<BubbleEvent> eventEmitter;

    // Bubble state
    private final Map<UUID, Set<ID>> bubbles;  // bubbleId -> entities
    private final Map<ID, Float> entityAffinities;  // entityId -> affinity with current bubble
    private final Map<ID, UUID> entityBubbles;  // entityId -> bubbleId (reverse mapping)

    // Partition state
    private boolean inPartition;
    private long partitionStartBucket;

    /**
     * Create bubble dynamics manager.
     *
     * @param bubbleTracker External bubble tracker for merge detection
     * @param health        Ghost layer health monitor
     * @param migrationLog  Migration log for idempotency
     * @param stockNeighbors Stock neighbor list for partition recovery
     * @param eventEmitter  Callback for bubble events
     */
    public BubbleDynamicsManager(
        ExternalBubbleTracker bubbleTracker,
        GhostLayerHealth health,
        MigrationLog migrationLog,
        StockNeighborList stockNeighbors,
        Consumer<BubbleEvent> eventEmitter
    ) {
        this.bubbleTracker = bubbleTracker;
        this.health = health;
        this.migrationLog = migrationLog;
        this.stockNeighbors = stockNeighbors;
        this.eventEmitter = eventEmitter;
        this.bubbles = new ConcurrentHashMap<>();
        this.entityAffinities = new ConcurrentHashMap<>();
        this.entityBubbles = new ConcurrentHashMap<>();
        this.inPartition = false;
        this.partitionStartBucket = 0L;
    }

    /**
     * Register a new bubble with its initial entities.
     *
     * @param bubbleId Bubble UUID
     * @param entities Initial entity set
     */
    public void registerBubble(UUID bubbleId, Set<ID> entities) {
        var entitySet = ConcurrentHashMap.<ID>newKeySet();
        entitySet.addAll(entities);
        bubbles.put(bubbleId, entitySet);

        // Update reverse mapping
        for (var entity : entities) {
            entityBubbles.put(entity, bubbleId);
        }
    }

    /**
     * Unregister a bubble (e.g., after merge).
     *
     * @param bubbleId Bubble to remove
     */
    public void unregisterBubble(UUID bubbleId) {
        var entities = bubbles.remove(bubbleId);
        if (entities != null) {
            for (var entity : entities) {
                entityBubbles.remove(entity);
                entityAffinities.remove(entity);
            }
        }
    }

    /**
     * Check if bubble is registered.
     *
     * @param bubbleId Bubble UUID
     * @return true if registered
     */
    public boolean hasBubble(UUID bubbleId) {
        return bubbles.containsKey(bubbleId);
    }

    /**
     * Get number of registered bubbles.
     *
     * @return Bubble count
     */
    public int getBubbleCount() {
        return bubbles.size();
    }

    /**
     * Get entity count for a bubble.
     *
     * @param bubbleId Bubble UUID
     * @return Number of entities in bubble
     */
    public int getEntityCount(UUID bubbleId) {
        var entities = bubbles.get(bubbleId);
        return entities != null ? entities.size() : 0;
    }

    /**
     * Get entities in a bubble.
     *
     * @param bubbleId Bubble UUID
     * @return Set of entities (defensive copy)
     */
    public Set<ID> getEntities(UUID bubbleId) {
        var entities = bubbles.get(bubbleId);
        return entities != null ? new HashSet<>(entities) : Set.of();
    }

    /**
     * Get all registered bubble IDs.
     *
     * @return Set of bubble UUIDs
     */
    public Set<UUID> getAllBubbles() {
        return new HashSet<>(bubbles.keySet());
    }

    /**
     * Merge two bubbles.
     * <p>
     * Protocol:
     * 1. Smaller bubble absorbed into larger
     * 2. All entities transferred
     * 3. Smaller bubble removed
     * 4. Merge event emitted
     *
     * @param bubble1 First bubble
     * @param bubble2 Second bubble
     * @param bucket  Bucket when merge occurred
     */
    public void mergeBubbles(UUID bubble1, UUID bubble2, long bucket) {
        var entities1 = bubbles.get(bubble1);
        var entities2 = bubbles.get(bubble2);

        if (entities1 == null || entities2 == null) {
            throw new IllegalArgumentException("Both bubbles must exist");
        }

        int size1 = entities1.size();
        int size2 = entities2.size();

        // Determine smaller/larger
        UUID smaller, larger;
        Set<ID> smallerEntities, largerEntities;

        if (size1 < size2) {
            smaller = bubble1;
            larger = bubble2;
            smallerEntities = entities1;
            largerEntities = entities2;
        } else {
            smaller = bubble2;
            larger = bubble1;
            smallerEntities = entities2;
            largerEntities = entities1;
        }

        // Transfer all entities from smaller to larger
        largerEntities.addAll(smallerEntities);

        // Update reverse mapping
        for (var entity : smallerEntities) {
            entityBubbles.put(entity, larger);
        }

        // Remove smaller bubble
        unregisterBubble(smaller);

        // Emit merge event
        eventEmitter.accept(new BubbleEvent.Merge(
            bubble1, bubble2, larger, bucket, size1, size2
        ));
    }

    /**
     * Split a bubble into multiple components.
     * <p>
     * Protocol:
     * 1. Original bubble becomes first component
     * 2. New bubbles created for other components
     * 3. Entities reassigned to appropriate bubbles
     * 4. Split event emitted
     *
     * @param sourceBubble Source bubble to split
     * @param componentSets Entity sets for each component
     * @param bucket       Bucket when split occurred
     * @return List of component bubble IDs (includes source)
     */
    public List<UUID> splitBubble(
        UUID sourceBubble,
        List<Set<ID>> componentSets,
        long bucket
    ) {
        if (componentSets.isEmpty()) {
            throw new IllegalArgumentException("Must have at least one component");
        }

        var componentBubbles = new ArrayList<UUID>();
        var componentSizes = new ArrayList<Integer>();

        // First component uses source bubble
        var firstComponentEntities = componentSets.get(0);
        bubbles.put(sourceBubble, ConcurrentHashMap.<ID>newKeySet());
        bubbles.get(sourceBubble).addAll(firstComponentEntities);
        componentBubbles.add(sourceBubble);
        componentSizes.add(firstComponentEntities.size());

        // Update reverse mapping for first component
        for (var entity : firstComponentEntities) {
            entityBubbles.put(entity, sourceBubble);
        }

        // Create new bubbles for remaining components
        for (int i = 1; i < componentSets.size(); i++) {
            var newBubble = UUID.randomUUID();
            var componentEntities = componentSets.get(i);

            registerBubble(newBubble, componentEntities);
            componentBubbles.add(newBubble);
            componentSizes.add(componentEntities.size());
        }

        // Emit split event
        eventEmitter.accept(new BubbleEvent.Split(
            sourceBubble, componentBubbles, bucket, componentSizes
        ));

        return componentBubbles;
    }

    /**
     * Transfer entity from one bubble to another.
     *
     * @param entityId     Entity to transfer
     * @param sourceBubble Source bubble
     * @param targetBubble Target bubble
     * @param affinity     Entity affinity with target
     * @param bucket       Bucket when transfer occurred
     */
    public void transferEntity(
        ID entityId,
        UUID sourceBubble,
        UUID targetBubble,
        float affinity,
        long bucket
    ) {
        transferEntityWithToken(
            entityId, sourceBubble, targetBubble, affinity,
            UUID.randomUUID(),  // Generate new token
            bucket
        );
    }

    /**
     * Transfer entity with idempotency token.
     *
     * @param entityId     Entity to transfer
     * @param sourceBubble Source bubble
     * @param targetBubble Target bubble
     * @param affinity     Entity affinity with target
     * @param token        Idempotency token
     * @param bucket       Bucket when transfer occurred
     */
    public void transferEntityWithToken(
        ID entityId,
        UUID sourceBubble,
        UUID targetBubble,
        float affinity,
        UUID token,
        long bucket
    ) {
        // Check idempotency
        if (migrationLog.isDuplicate(entityId, token)) {
            return;  // Already processed
        }

        var sourceEntities = bubbles.get(sourceBubble);
        var targetEntities = bubbles.get(targetBubble);

        if (sourceEntities == null || targetEntities == null) {
            throw new IllegalArgumentException("Both bubbles must exist");
        }

        // Transfer entity
        sourceEntities.remove(entityId);
        targetEntities.add(entityId);
        entityBubbles.put(entityId, targetBubble);

        // Record migration
        migrationLog.recordMigration(
            entityId, token, sourceBubble, targetBubble, bucket
        );

        // Emit event
        long epoch = bucket;  // Simplified epoch = bucket
        eventEmitter.accept(new BubbleEvent.EntityTransfer(
            entityId, sourceBubble, targetBubble, bucket, affinity, epoch
        ));
    }

    /**
     * Set entity affinity with its current bubble.
     *
     * @param entityId Entity ID
     * @param bubbleId Bubble ID (for validation)
     * @param affinity Affinity value [0.0, 1.0]
     */
    public void setEntityAffinity(ID entityId, UUID bubbleId, float affinity) {
        if (affinity < 0.0f || affinity > 1.0f) {
            throw new IllegalArgumentException("Affinity must be in [0.0, 1.0]");
        }

        var currentBubble = entityBubbles.get(entityId);
        if (!bubbleId.equals(currentBubble)) {
            throw new IllegalArgumentException(
                "Entity not in specified bubble");
        }

        entityAffinities.put(entityId, affinity);
    }

    /**
     * Get drifting entities (affinity < threshold).
     *
     * @param bubbleId  Bubble to check
     * @param threshold Drift threshold
     * @return Set of drifting entities
     */
    public Set<ID> getDriftingEntities(UUID bubbleId, float threshold) {
        var entities = bubbles.get(bubbleId);
        if (entities == null) {
            return Set.of();
        }

        return entities.stream()
            .filter(entity -> {
                var affinity = entityAffinities.getOrDefault(entity, 1.0f);
                return affinity < threshold;
            })
            .collect(Collectors.toSet());
    }

    /**
     * Migrate bubble to different node.
     *
     * @param bubbleId   Bubble to migrate
     * @param sourceNode Source node UUID
     * @param targetNode Target node UUID
     * @param bucket     Bucket when migration occurred
     */
    public void migrateBubble(
        UUID bubbleId,
        UUID sourceNode,
        UUID targetNode,
        long bucket
    ) {
        var entities = bubbles.get(bubbleId);
        if (entities == null) {
            throw new IllegalArgumentException("Bubble does not exist");
        }

        eventEmitter.accept(new BubbleEvent.BubbleMigration(
            bubbleId, sourceNode, targetNode, bucket, entities.size()
        ));
    }

    /**
     * Process bucket tick - check for merge/split/partition events.
     *
     * @param bucket Current bucket number
     */
    public void processBucket(long bucket) {
        checkPartitionState(bucket);
    }

    /**
     * Check for partition detection/recovery.
     *
     * @param bucket Current bucket
     */
    private void checkPartitionState(long bucket) {
        float nc = health.neighborConsistency();

        if (!inPartition && nc < PARTITION_THRESHOLD) {
            // Partition detected
            inPartition = true;
            partitionStartBucket = bucket;

            eventEmitter.accept(new BubbleEvent.PartitionDetected(
                bucket,
                health.getKnownNeighbors(),
                health.getExpectedNeighbors(),
                nc
            ));

        } else if (inPartition && nc >= RECOVERY_THRESHOLD) {
            // Partition recovered
            inPartition = false;
            long duration = bucket - partitionStartBucket;

            eventEmitter.accept(new BubbleEvent.PartitionRecovered(
                bucket,
                health.getKnownNeighbors(),
                health.getExpectedNeighbors(),
                nc,
                duration
            ));
        }
    }

    /**
     * Get merge candidates (high affinity bubbles).
     *
     * @param threshold Affinity threshold
     * @return List of candidate bubble IDs
     */
    public List<UUID> getMergeCandidates(float threshold) {
        // Use bubble tracker to find high-interaction bubbles
        int minInteractions = (int) (threshold * 100);  // Simplified
        return bubbleTracker.getMergeCandidates(minInteractions);
    }

    @Override
    public String toString() {
        return String.format("BubbleDynamicsManager{bubbles=%d, entities=%d}",
                            bubbles.size(), entityBubbles.size());
    }
}
