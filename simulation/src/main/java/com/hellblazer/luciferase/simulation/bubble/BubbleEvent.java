package com.hellblazer.luciferase.simulation.bubble;

import com.hellblazer.luciferase.simulation.ghost.*;

import com.hellblazer.luciferase.simulation.bubble.*;

import com.hellblazer.luciferase.lucien.entity.EntityID;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Sealed interface for bubble lifecycle events in Phase 4 (Bubble Dynamics).
 * <p>
 * Events represent state changes in the bubble system:
 * - Merge: Two bubbles combine into one
 * - Split: One bubble divides into multiple bubbles
 * - EntityTransfer: Entity moves from one bubble to another
 * - BubbleMigration: Entire bubble moves to different node
 * <p>
 * All events are immutable records with bucket timestamp for causal ordering.
 * <p>
 * Thread-safe: Events are immutable, safe to share across threads.
 *
 * @author hal.hildebrand
 */
public sealed interface BubbleEvent {

    /**
     * Get the bucket (simulation time) when this event occurred.
     *
     * @return Bucket timestamp
     */
    long bucket();

    /**
     * Get the event type for logging/debugging.
     *
     * @return Event type name
     */
    default String eventType() {
        return this.getClass().getSimpleName();
    }

    /**
     * Bubble merge event: Two bubbles combine into one.
     * <p>
     * Triggered when:
     * - Cross-bubble affinity > MERGE_THRESHOLD (0.6)
     * - High interaction count between bubbles
     * - Entities drifting from one bubble have high affinity with another
     * <p>
     * Protocol:
     * 1. Smaller bubble absorbed into larger bubble
     * 2. All entities transferred with epoch increment
     * 3. Ghost zones merged
     * 4. Affinity trackers combined
     *
     * @param bubble1 First bubble ID (typically smaller)
     * @param bubble2 Second bubble ID (typically larger)
     * @param result  Resulting merged bubble ID (typically bubble2)
     * @param bucket  Bucket when merge occurred
     * @param size1   Number of entities in bubble1
     * @param size2   Number of entities in bubble2
     */
    record Merge(
        UUID bubble1,
        UUID bubble2,
        UUID result,
        long bucket,
        int size1,
        int size2
    ) implements BubbleEvent {

        /**
         * Get the smaller bubble (being absorbed).
         *
         * @return UUID of smaller bubble
         */
        public UUID smallerBubble() {
            return size1 < size2 ? bubble1 : bubble2;
        }

        /**
         * Get the larger bubble (absorbing).
         *
         * @return UUID of larger bubble
         */
        public UUID largerBubble() {
            return size1 < size2 ? bubble2 : bubble1;
        }

        /**
         * Get total entities after merge.
         *
         * @return Combined entity count
         */
        public int totalSize() {
            return size1 + size2;
        }
    }

    /**
     * Bubble split event: One bubble divides into multiple bubbles.
     * <p>
     * Triggered when:
     * - Interaction graph becomes disconnected (union-find detection)
     * - Entity clusters have low internal affinity
     * - Spatial separation exceeds threshold
     * <p>
     * Protocol:
     * 1. Original bubble becomes first component
     * 2. New bubbles created for other components
     * 3. Entities reassigned to appropriate bubbles
     * 4. All entities preserved (no loss)
     *
     * @param source     Original bubble ID
     * @param components List of resulting bubble IDs (includes source)
     * @param bucket     Bucket when split occurred
     * @param sizes      Entity count for each component
     */
    record Split(
        UUID source,
        List<UUID> components,
        long bucket,
        List<Integer> sizes
    ) implements BubbleEvent {

        /**
         * Validate that components and sizes match.
         */
        public Split {
            if (components.size() != sizes.size()) {
                throw new IllegalArgumentException(
                    "Component count must match size count: " +
                    components.size() + " != " + sizes.size()
                );
            }
            if (components.isEmpty()) {
                throw new IllegalArgumentException("Split must have at least one component");
            }
            if (!components.contains(source)) {
                throw new IllegalArgumentException("Components must include source bubble");
            }
        }

        /**
         * Get number of resulting bubbles.
         *
         * @return Component count
         */
        public int componentCount() {
            return components.size();
        }

        /**
         * Get total entities across all components.
         *
         * @return Sum of all component sizes
         */
        public int totalSize() {
            return sizes.stream().mapToInt(Integer::intValue).sum();
        }

        /**
         * Get entity count for a specific component.
         *
         * @param componentIndex Index in components list
         * @return Entity count for that component
         */
        public int sizeOf(int componentIndex) {
            return sizes.get(componentIndex);
        }
    }

    /**
     * Entity transfer event: Entity moves from one bubble to another.
     * <p>
     * Triggered when:
     * - Entity affinity < DRIFT_THRESHOLD (0.5) with current bubble
     * - Entity has higher affinity with external bubble
     * - Migration candidate identified by dynamics manager
     * <p>
     * Protocol:
     * 1. Remove entity from source bubble
     * 2. Add entity to target bubble with incremented epoch
     * 3. Update ghost zones
     * 4. Log migration for idempotency
     *
     * @param entityId     Entity being transferred
     * @param sourceBubble Source bubble ID
     * @param targetBubble Target bubble ID
     * @param bucket       Bucket when transfer occurred
     * @param affinity     Entity's affinity with target bubble
     * @param epoch        New epoch after transfer
     */
    record EntityTransfer(
        EntityID entityId,
        UUID sourceBubble,
        UUID targetBubble,
        long bucket,
        float affinity,
        long epoch
    ) implements BubbleEvent {

        /**
         * Validate transfer parameters.
         */
        public EntityTransfer {
            if (sourceBubble.equals(targetBubble)) {
                throw new IllegalArgumentException(
                    "Source and target bubbles must be different"
                );
            }
            if (affinity < 0.0f || affinity > 1.0f) {
                throw new IllegalArgumentException(
                    "Affinity must be in [0.0, 1.0]: " + affinity
                );
            }
        }

        /**
         * Check if this transfer indicates entity is drifting.
         *
         * @return true if affinity with source was low
         */
        public boolean isDrifting() {
            return affinity < 0.5f;
        }
    }

    /**
     * Bubble migration event: Entire bubble moves to different node.
     * <p>
     * Triggered when:
     * - Bubble load exceeds node capacity
     * - Load balancing across cluster
     * - Node failure requires bubble evacuation
     * <p>
     * Protocol:
     * 1. Serialize bubble state (entities + metadata)
     * 2. Transfer to target node
     * 3. Reconstruct bubble on target
     * 4. Update routing tables
     * 5. Remove from source node
     *
     * @param bubbleId   Bubble being migrated
     * @param sourceNode Source node ID (if known)
     * @param targetNode Target node ID (if known)
     * @param bucket     Bucket when migration occurred
     * @param entityCount Number of entities in bubble
     */
    record BubbleMigration(
        UUID bubbleId,
        UUID sourceNode,
        UUID targetNode,
        long bucket,
        int entityCount
    ) implements BubbleEvent {

        /**
         * Check if this is a local migration (same cluster).
         *
         * @return true if source and target are both known
         */
        public boolean isLocalMigration() {
            return sourceNode != null && targetNode != null;
        }
    }

    /**
     * Partition detected event: NC metric indicates network partition.
     * <p>
     * Triggered when:
     * - GhostLayerHealth.neighborConsistency() < PARTITION_THRESHOLD (0.5)
     * - Missing > 50% of expected neighbors
     * - Minority partition indicator
     * <p>
     * Response:
     * 1. Activate partition recovery mode
     * 2. Contact stock neighbors (backup list)
     * 3. Attempt to rejoin majority partition
     * 4. Continue operation with degraded service
     *
     * @param bucket            Bucket when partition detected
     * @param knownNeighbors    Number of known neighbors
     * @param expectedNeighbors Number of expected neighbors (from membership)
     * @param nc                Neighbor Consistency value
     */
    record PartitionDetected(
        long bucket,
        int knownNeighbors,
        int expectedNeighbors,
        float nc
    ) implements BubbleEvent {

        /**
         * Validate NC metric.
         */
        public PartitionDetected {
            if (nc < 0.0f || nc > 1.0f) {
                throw new IllegalArgumentException(
                    "NC must be in [0.0, 1.0]: " + nc
                );
            }
        }

        /**
         * Get number of missing neighbors.
         *
         * @return Expected - known
         */
        public int missingNeighbors() {
            return Math.max(0, expectedNeighbors - knownNeighbors);
        }

        /**
         * Check if this is a severe partition (NC < 0.3).
         *
         * @return true if severely partitioned
         */
        public boolean isSevere() {
            return nc < 0.3f;
        }
    }

    /**
     * Partition recovered event: NC metric indicates partition healed.
     * <p>
     * Triggered when:
     * - GhostLayerHealth.neighborConsistency() >= HEALTHY_THRESHOLD (0.9)
     * - After partition recovery via stock neighbors
     * - Network partition healed
     *
     * @param bucket            Bucket when recovery detected
     * @param knownNeighbors    Number of known neighbors after recovery
     * @param expectedNeighbors Number of expected neighbors
     * @param nc                Neighbor Consistency value
     * @param recoveryDuration  Duration in buckets from detection to recovery
     */
    record PartitionRecovered(
        long bucket,
        int knownNeighbors,
        int expectedNeighbors,
        float nc,
        long recoveryDuration
    ) implements BubbleEvent {

        /**
         * Check if full recovery (NC = 1.0).
         *
         * @return true if all neighbors discovered
         */
        public boolean isFullRecovery() {
            return nc >= 1.0f;
        }
    }
}
