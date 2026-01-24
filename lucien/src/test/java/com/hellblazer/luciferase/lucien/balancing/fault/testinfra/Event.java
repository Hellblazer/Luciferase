package com.hellblazer.luciferase.lucien.balancing.fault.testinfra;

import java.util.Objects;

/**
 * Immutable event record for test event capture.
 * <p>
 * Records a single system event with timestamp, category, type, and associated data.
 * Used by {@link EventCapture} to track system behavior during testing.
 *
 * @param timestamp Event timestamp in milliseconds
 * @param category Event category (e.g., "fault", "recovery", "von", "listener")
 * @param type Event type (e.g., "PHASE_CHANGE", "TOPOLOGY_UPDATE")
 * @param data Event-specific data (may be null)
 */
public record Event(
    long timestamp,
    String category,
    String type,
    Object data
) {
    /**
     * Compact constructor with validation.
     */
    public Event {
        Objects.requireNonNull(category, "category cannot be null");
        Objects.requireNonNull(type, "type cannot be null");
    }

    /**
     * Create event without data payload.
     *
     * @param timestamp event timestamp
     * @param category event category
     * @param type event type
     * @return new Event with null data
     */
    public static Event of(long timestamp, String category, String type) {
        return new Event(timestamp, category, type, null);
    }
}
