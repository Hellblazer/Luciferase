package com.hellblazer.luciferase.simulation.bubble;

import java.io.Serializable;
import java.util.UUID;

/**
 * Immutable record representing a replicated bubble entry.
 * <p>
 * Each entry contains:
 * - bubbleId: Unique identifier for the bubble
 * - serverId: ID of the server managing this bubble
 * - bounds: Spatial extent using tetrahedral coordinates
 * - timestamp: Last update time for conflict resolution (LWW)
 * <p>
 * Equality is based solely on bubbleId for efficient lookup in ConcurrentHashMap.
 *
 * @param bubbleId  Unique bubble identifier
 * @param serverId  Server managing this bubble
 * @param bounds    Spatial bounds in tetrahedral space
 * @param timestamp Last update timestamp (milliseconds)
 * @author hal.hildebrand
 */
public record BubbleEntry(
    UUID bubbleId,
    UUID serverId,
    BubbleBounds bounds,
    long timestamp
) implements Serializable {

    /**
     * Equality based on bubbleId only.
     * Allows HashMap/Set operations to work correctly with bubble identity.
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof BubbleEntry other)) return false;
        return bubbleId.equals(other.bubbleId);
    }

    /**
     * HashCode based on bubbleId only.
     * Consistent with equals() for HashMap correctness.
     */
    @Override
    public int hashCode() {
        return bubbleId.hashCode();
    }
}
