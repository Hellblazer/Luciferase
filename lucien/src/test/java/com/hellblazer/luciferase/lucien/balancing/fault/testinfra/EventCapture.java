package com.hellblazer.luciferase.lucien.balancing.fault.testinfra;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Thread-safe event capture for distributed system testing.
 * <p>
 * Records system events (fault detection, recovery phases, VON topology changes, etc.)
 * and provides filtering, sequencing, and statistical analysis capabilities.
 * <p>
 * <b>Thread Safety</b>: All methods are thread-safe. Multiple threads can record
 * events concurrently without external synchronization.
 * <p>
 * <b>Example Usage</b>:
 * <pre>{@code
 * var capture = new EventCapture();
 *
 * // Record events from different subsystems
 * capture.recordEvent("recovery", new Event(1000L, "recovery", "PHASE_CHANGE", phase));
 * capture.recordEvent("fault", new Event(1001L, "fault", "DETECTED", partitionId));
 *
 * // Filter by category
 * var recoveryEvents = capture.getEventsByCategory("recovery");
 *
 * // Get temporal sequence across categories
 * var sequence = capture.getEventSequence("recovery", "fault", "von");
 *
 * // Analyze statistics
 * var stats = capture.getStatistics();
 * System.out.println("Total events: " + stats.totalEvents());
 * }</pre>
 */
public class EventCapture {

    // ConcurrentHashMap for thread-safe category storage
    // CopyOnWriteArrayList for thread-safe event list per category
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Event>> eventsByCategory;

    /**
     * Create new EventCapture instance.
     */
    public EventCapture() {
        this.eventsByCategory = new ConcurrentHashMap<>();
    }

    /**
     * Record an event in the specified category.
     * <p>
     * Thread-safe: Multiple threads can call this method concurrently.
     *
     * @param category event category (e.g., "recovery", "fault", "von")
     * @param event event to record
     * @throws NullPointerException if category or event is null
     */
    public void recordEvent(String category, Event event) {
        Objects.requireNonNull(category, "category cannot be null");
        Objects.requireNonNull(event, "event cannot be null");

        // Atomically get or create event list for category
        eventsByCategory.computeIfAbsent(category, k -> new CopyOnWriteArrayList<>())
                        .add(event);
    }

    /**
     * Get all events in a specific category.
     * <p>
     * Returns a snapshot of events at the time of the call. Subsequent modifications
     * to the event capture will not affect the returned list.
     *
     * @param category category name
     * @return unmodifiable list of events in category (empty if category not found)
     */
    public List<Event> getEventsByCategory(String category) {
        var events = eventsByCategory.get(category);
        if (events == null) {
            return List.of();
        }
        return List.copyOf(events);
    }

    /**
     * Get temporal sequence of events across multiple categories.
     * <p>
     * Returns events from specified categories sorted by timestamp in ascending order.
     * Useful for analyzing cross-subsystem event sequences.
     *
     * @param categories categories to include in sequence
     * @return list of events sorted by timestamp
     */
    public List<Event> getEventSequence(String... categories) {
        return Arrays.stream(categories)
                     .map(this::getEventsByCategory)
                     .flatMap(List::stream)
                     .sorted(Comparator.comparingLong(Event::timestamp))
                     .collect(Collectors.toList());
    }

    /**
     * Get aggregate event statistics.
     * <p>
     * Includes total event count and per-category counts.
     *
     * @return EventStatistics with current counts
     */
    public EventStatistics getStatistics() {
        var categoryCounts = new HashMap<String, Integer>();
        var totalEvents = 0;

        for (var entry : eventsByCategory.entrySet()) {
            var count = entry.getValue().size();
            categoryCounts.put(entry.getKey(), count);
            totalEvents += count;
        }

        return new EventStatistics(totalEvents, categoryCounts);
    }

    /**
     * Reset event capture, clearing all recorded events.
     * <p>
     * Thread-safe: Safe to call concurrently with recordEvent.
     */
    public void reset() {
        eventsByCategory.clear();
    }
}
