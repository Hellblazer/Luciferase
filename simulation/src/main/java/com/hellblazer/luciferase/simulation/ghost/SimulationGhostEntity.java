package com.hellblazer.luciferase.simulation.ghost;

import com.hellblazer.luciferase.simulation.ghost.*;

import com.hellblazer.luciferase.simulation.entity.*;

import com.hellblazer.luciferase.simulation.bubble.*;

import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostZoneManager;

import javax.vecmath.Point3f;
import java.util.UUID;

/**
 * Simulation-specific ghost entity wrapper.
 * <p>
 * Extends GhostEntity (from lucien) with simulation-specific metadata:
 * - sourceBubbleId: Which bubble owns this entity (enables VON discovery)
 * - bucket: Simulation time bucket for causal ordering
 * - epoch: Entity authority epoch for stale update detection
 * - version: Entity version within epoch
 * <p>
 * This follows the wrapper pattern from Phase 0 V3 decision:
 * - GhostZoneManager remains generic (spatial indexing)
 * - SimulationGhostEntity adds simulation semantics (ownership, time, authority)
 * <p>
 * Usage:
 * <pre>
 * // Wrap a ghost with simulation metadata
 * var simGhost = new SimulationGhostEntity<>(
 *     ghostEntity,
 *     sourceBubbleId,
 *     currentBucket,
 *     entityEpoch,
 *     entityVersion
 * );
 *
 * // Access ghost data
 * var entityId = simGhost.entityId();
 * var position = simGhost.position();
 *
 * // Access simulation metadata
 * var bubbleId = simGhost.sourceBubbleId();
 * var authority = simGhost.authority();
 * </pre>
 *
 * @param <ID>      Entity ID type
 * @param <Content> Entity content type
 * @author hal.hildebrand
 */
public record SimulationGhostEntity<ID extends EntityID, Content>(
    GhostZoneManager.GhostEntity<ID, Content> ghost,
    UUID sourceBubbleId,
    long bucket,
    long epoch,
    long version
) {

    /**
     * Compact constructor for validation (Luciferase-r73c).
     * Fail-fast approach: validates ghost position is non-null at construction time.
     * This prevents null positions from entering the system.
     *
     * @throws NullPointerException if ghost or ghost.getPosition() is null
     */
    public SimulationGhostEntity {
        java.util.Objects.requireNonNull(ghost, "Ghost entity must not be null");
        java.util.Objects.requireNonNull(ghost.getPosition(),
            "Ghost entity position must not be null (id=" + (ghost.getEntityId() != null ? ghost.getEntityId() : "unknown") + ")");
        java.util.Objects.requireNonNull(sourceBubbleId, "Source bubble ID must not be null");
    }

    /**
     * Convenience accessor for entity ID (delegates to ghost).
     *
     * @return Entity identifier
     */
    public ID entityId() {
        return ghost.getEntityId();
    }

    /**
     * Convenience accessor for entity content (delegates to ghost).
     *
     * @return Entity content
     */
    public Content content() {
        return ghost.getContent();
    }

    /**
     * Convenience accessor for entity position (delegates to ghost).
     *
     * @return Entity 3D position
     */
    public Point3f position() {
        return ghost.getPosition();
    }

    /**
     * Convenience accessor for entity bounds (delegates to ghost).
     *
     * @return Entity spatial bounds
     */
    public EntityBounds bounds() {
        return ghost.getBounds();
    }

    /**
     * Convenience accessor for source tree ID (delegates to ghost).
     *
     * @return Source spatial tree identifier
     */
    public String sourceTreeId() {
        return ghost.getSourceTreeId();
    }

    /**
     * Convenience accessor for timestamp (delegates to ghost).
     *
     * @return Creation timestamp
     */
    public long timestamp() {
        return ghost.getTimestamp();
    }

    /**
     * Get entity authority (epoch and version).
     *
     * @return EntityAuthority for stale update detection
     */
    public EntityAuthority authority() {
        return new EntityAuthority(epoch, version);
    }

    /**
     * Check if this ghost is newer than another based on authority.
     *
     * @param other Other simulation ghost to compare
     * @return true if this ghost has newer authority
     */
    public boolean isNewerThan(SimulationGhostEntity<ID, Content> other) {
        return authority().isNewerThan(other.authority());
    }

    /**
     * Check if this ghost is from a specific bubble.
     *
     * @param bubbleId Bubble ID to check
     * @return true if this ghost originated from the specified bubble
     */
    public boolean isFromBubble(UUID bubbleId) {
        return sourceBubbleId.equals(bubbleId);
    }
}
