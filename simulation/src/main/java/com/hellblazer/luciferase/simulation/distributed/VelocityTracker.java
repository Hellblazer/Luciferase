package com.hellblazer.luciferase.simulation.distributed;

import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.von.VonBubble;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Vector3f;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Manages velocity tracking for entities across two bubbles.
 * <p>
 * Responsibilities:
 * - Initialize random velocities for all entities
 * - Track velocities per bubble (bubble1, bubble2)
 * - Clean up orphaned velocities for deleted entities
 * - Transfer velocities during cross-bubble migration
 * <p>
 * Thread-safe: Uses ConcurrentHashMap for velocity storage.
 */
public class VelocityTracker {
    private static final Logger log = LoggerFactory.getLogger(VelocityTracker.class);

    private final VonBubble bubble1;
    private final VonBubble bubble2;
    private final Random random;
    private final ConcurrentHashMap<String, Vector3f> velocities1;
    private final ConcurrentHashMap<String, Vector3f> velocities2;

    public VelocityTracker(VonBubble bubble1, VonBubble bubble2, Random random) {
        this.bubble1 = bubble1;
        this.bubble2 = bubble2;
        this.random = random;
        this.velocities1 = new ConcurrentHashMap<>();
        this.velocities2 = new ConcurrentHashMap<>();
    }

    /**
     * Initialize random velocities for all entities in both bubbles.
     */
    public void initializeVelocities(float maxSpeedBubble1, float maxSpeedBubble2) {
        velocities1.clear();
        velocities2.clear();

        for (var entity : bubble1.getAllEntityRecords()) {
            velocities1.put(entity.id(), randomVelocity(maxSpeedBubble1));
        }
        for (var entity : bubble2.getAllEntityRecords()) {
            velocities2.put(entity.id(), randomVelocity(maxSpeedBubble2));
        }
    }

    /**
     * Clean up velocity entries for entities that no longer exist.
     * Prevents memory leaks from orphaned velocity entries.
     */
    public void cleanupOrphanedVelocities() {
        var bubble1Ids = bubble1.getAllEntityRecords().stream()
            .map(EnhancedBubble.EntityRecord::id)
            .collect(Collectors.toSet());
        int removed1 = velocities1.size();
        velocities1.keySet().retainAll(bubble1Ids);
        removed1 -= velocities1.size();

        var bubble2Ids = bubble2.getAllEntityRecords().stream()
            .map(EnhancedBubble.EntityRecord::id)
            .collect(Collectors.toSet());
        int removed2 = velocities2.size();
        velocities2.keySet().retainAll(bubble2Ids);
        removed2 -= velocities2.size();

        if (removed1 > 0 || removed2 > 0) {
            log.debug("Velocity cleanup: removed {} from bubble1, {} from bubble2", removed1, removed2);
        }
    }

    /**
     * Get velocity for an entity in a specific bubble.
     *
     * @param entityId Entity ID
     * @param bubble Bubble containing the entity
     * @return Velocity vector, or null if not found
     */
    public Vector3f getVelocity(String entityId, VonBubble bubble) {
        return (bubble == bubble1) ? velocities1.get(entityId)
                                    : velocities2.get(entityId);
    }

    /**
     * Remove velocity for an entity in a specific bubble.
     *
     * @param entityId Entity ID
     * @param bubble Bubble containing the entity
     */
    public void removeVelocity(String entityId, VonBubble bubble) {
        if (bubble == bubble1) {
            velocities1.remove(entityId);
        } else {
            velocities2.remove(entityId);
        }
    }

    /**
     * Transfer velocity from one bubble to another during migration.
     *
     * @param entityId Entity ID
     * @param from Source bubble
     * @param to Target bubble
     */
    public void transferVelocity(String entityId, VonBubble from, VonBubble to) {
        var velocity = getVelocity(entityId, from);
        if (velocity != null) {
            removeVelocity(entityId, from);
            if (to == bubble1) {
                velocities1.put(entityId, velocity);
            } else {
                velocities2.put(entityId, velocity);
            }
        }
    }

    /**
     * Package-private accessor for CrossBubbleMigrationManager.
     * Direct map access needed for migration commit phase.
     *
     * @return Velocity map for bubble1
     */
    Map<String, Vector3f> getVelocities1() {
        return velocities1;
    }

    /**
     * Package-private accessor for CrossBubbleMigrationManager.
     * Direct map access needed for migration commit phase.
     *
     * @return Velocity map for bubble2
     */
    Map<String, Vector3f> getVelocities2() {
        return velocities2;
    }

    /**
     * Generate a random velocity vector within max speed bounds.
     *
     * @param maxSpeed Maximum speed magnitude
     * @return Random velocity vector
     */
    private Vector3f randomVelocity(float maxSpeed) {
        return new Vector3f(
            (random.nextFloat() - 0.5f) * 2.0f * maxSpeed,
            (random.nextFloat() - 0.5f) * 2.0f * maxSpeed,
            (random.nextFloat() - 0.5f) * 2.0f * maxSpeed
        );
    }
}
