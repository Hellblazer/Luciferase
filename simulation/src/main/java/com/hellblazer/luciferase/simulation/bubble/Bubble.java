package com.hellblazer.luciferase.simulation.bubble;

import com.hellblazer.luciferase.simulation.spatial.*;

import com.hellblazer.luciferase.simulation.bubble.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents a Simulation Bubble - a group of entities with shared ownership and local simulation authority.
 * <p>
 * Responsibilities:
 * - Track entity membership (which entities belong to this bubble)
 * - Monitor entity affinity (internal vs external interactions)
 * - Identify migration candidates (entities drifting to other bubbles)
 * <p>
 * Thread-safe for concurrent membership and affinity updates.
 *
 * @author hal.hildebrand
 */
public class Bubble {

    private final UUID id;
    private final Set<String> members;
    private final Map<String, AffinityTracker> affinities;

    /**
     * Create a new bubble with the given ID.
     *
     * @param id Unique bubble identifier
     */
    public Bubble(UUID id) {
        this.id = id;
        this.members = ConcurrentHashMap.newKeySet();
        this.affinities = new ConcurrentHashMap<>();
    }

    /**
     * Get the bubble ID.
     *
     * @return Unique bubble identifier
     */
    public UUID id() {
        return id;
    }

    /**
     * Add an entity to this bubble.
     * Initializes affinity tracking with 0/0 (boundary classification).
     *
     * @param entityId Entity to add
     */
    public void addMember(String entityId) {
        if (members.add(entityId)) {
            // Initialize affinity tracking for new member
            affinities.put(entityId, new AffinityTracker(0, 0));
        }
    }

    /**
     * Remove an entity from this bubble.
     * Clears affinity tracking for the entity.
     *
     * @param entityId Entity to remove
     */
    public void removeMember(String entityId) {
        if (members.remove(entityId)) {
            affinities.remove(entityId);
        }
    }

    /**
     * Check if an entity is a member of this bubble.
     *
     * @param entityId Entity to check
     * @return true if entity is a member
     */
    public boolean isMember(String entityId) {
        return members.contains(entityId);
    }

    /**
     * Record an interaction for an entity.
     * Updates affinity tracking based on whether the interaction was internal (within bubble)
     * or external (with another bubble).
     *
     * @param entityId   Entity that had the interaction
     * @param isInternal true if interaction was with another entity in this bubble,
     *                   false if with entity in different bubble
     */
    public void recordInteraction(String entityId, boolean isInternal) {
        affinities.computeIfPresent(entityId, (id, tracker) ->
            isInternal ? tracker.recordInternal() : tracker.recordExternal()
        );
    }

    /**
     * Get the affinity tracker for an entity.
     *
     * @param entityId Entity to query
     * @return AffinityTracker for the entity, or null if not a member
     */
    public AffinityTracker getAffinity(String entityId) {
        return affinities.get(entityId);
    }

    /**
     * Get all members of this bubble.
     *
     * @return Unmodifiable view of member set
     */
    public Set<String> getMembers() {
        return Collections.unmodifiableSet(members);
    }

    @Override
    public String toString() {
        return "Bubble{id=" + id + ", members=" + members.size() + "}";
    }
}
