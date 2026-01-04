package com.hellblazer.luciferase.simulation;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Tracks discovered external bubbles for VON-based bubble discovery and merge coordination.
 * <p>
 * Implements the "watchmen" pattern from VON research:
 * - Discovers external bubbles through ghost entity interactions
 * - Tracks interaction frequency as affinity metric
 * - Identifies high-affinity bubbles as merge candidates
 * <p>
 * Discovery Pattern:
 * <pre>
 * When a ghost entity from bubble B enters bubble A's ghost zone:
 * 1. Bubble A records the interaction with bubble B
 * 2. Interaction count accumulates over time
 * 3. High interaction count = spatial proximity + high entity flow
 * 4. Bubbles with >= threshold interactions are merge candidates
 * </pre>
 * <p>
 * Thread-safe for concurrent ghost interactions.
 *
 * @author hal.hildebrand
 */
public class ExternalBubbleTracker {

    /**
     * Contact metadata for a discovered external bubble.
     */
    private static class BubbleContact {
        private final UUID bubbleId;
        private final AtomicInteger interactionCount;
        private volatile long lastSeenTimestamp;

        BubbleContact(UUID bubbleId) {
            this.bubbleId = bubbleId;
            this.interactionCount = new AtomicInteger(0);
            this.lastSeenTimestamp = System.currentTimeMillis();
        }

        void recordInteraction() {
            interactionCount.incrementAndGet();
            lastSeenTimestamp = System.currentTimeMillis();
        }

        int getInteractionCount() {
            return interactionCount.get();
        }

        UUID getBubbleId() {
            return bubbleId;
        }

        long getLastSeen() {
            return lastSeenTimestamp;
        }
    }

    private final Map<UUID, BubbleContact> contacts;

    /**
     * Create a new external bubble tracker.
     */
    public ExternalBubbleTracker() {
        this.contacts = new ConcurrentHashMap<>();
    }

    /**
     * Record a ghost entity interaction with an external bubble.
     * <p>
     * Called when a ghost entity from another bubble enters this bubble's ghost zone.
     *
     * @param bubbleId Source bubble ID of the ghost entity
     */
    public void recordGhostInteraction(UUID bubbleId) {
        contacts.computeIfAbsent(bubbleId, BubbleContact::new)
                .recordInteraction();
    }

    /**
     * Get all discovered external bubbles.
     *
     * @return Unmodifiable set of discovered bubble IDs
     */
    public Set<UUID> getDiscoveredBubbles() {
        return Collections.unmodifiableSet(contacts.keySet());
    }

    /**
     * Get interaction count with a specific bubble.
     *
     * @param bubbleId Bubble to query
     * @return Number of ghost interactions recorded, or 0 if bubble not discovered
     */
    public int getInteractionCount(UUID bubbleId) {
        var contact = contacts.get(bubbleId);
        return contact != null ? contact.getInteractionCount() : 0;
    }

    /**
     * Get merge candidates - bubbles with interaction count >= threshold.
     * <p>
     * High interaction count indicates:
     * - Spatial proximity (ghost zones overlap)
     * - High entity flow between bubbles
     * - Good candidate for bubble merge or coordination
     * <p>
     * Results are ordered by interaction count (descending).
     *
     * @param threshold Minimum interaction count to be considered a candidate
     * @return List of bubble IDs meeting threshold, ordered by affinity (highest first)
     */
    public List<UUID> getMergeCandidates(int threshold) {
        return contacts.values().stream()
            .filter(contact -> contact.getInteractionCount() >= threshold)
            .sorted((a, b) -> Integer.compare(b.getInteractionCount(), a.getInteractionCount()))
            .map(BubbleContact::getBubbleId)
            .collect(Collectors.toList());
    }

    /**
     * Get last seen timestamp for a bubble.
     *
     * @param bubbleId Bubble to query
     * @return Last interaction timestamp in milliseconds, or 0 if bubble not discovered
     */
    public long getLastSeen(UUID bubbleId) {
        var contact = contacts.get(bubbleId);
        return contact != null ? contact.getLastSeen() : 0;
    }

    /**
     * Get all bubble contacts (for debugging/monitoring).
     *
     * @return Unmodifiable map of bubble ID to interaction count
     */
    public Map<UUID, Integer> getAllContacts() {
        return contacts.entrySet().stream()
            .collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey,
                e -> e.getValue().getInteractionCount()
            ));
    }

    @Override
    public String toString() {
        return "ExternalBubbleTracker{discovered=" + contacts.size() + "}";
    }
}
