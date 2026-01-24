package com.hellblazer.luciferase.lucien.balancing.fault.testinfra;

import java.util.Map;
import java.util.Objects;

/**
 * Event statistics summary.
 * <p>
 * Provides aggregate statistics about captured events including total count and
 * per-category counts.
 *
 * @param totalEvents Total number of events captured
 * @param categoryCounts Number of events per category
 */
public record EventStatistics(
    int totalEvents,
    Map<String, Integer> categoryCounts
) {
    /**
     * Compact constructor with validation.
     */
    public EventStatistics {
        Objects.requireNonNull(categoryCounts, "categoryCounts cannot be null");
        if (totalEvents < 0) {
            throw new IllegalArgumentException("totalEvents must be non-negative");
        }

        // Defensive copy for immutability
        categoryCounts = Map.copyOf(categoryCounts);
    }

    /**
     * Get event count for a specific category.
     *
     * @param category category name
     * @return event count for category, or 0 if category not found
     */
    public int getCountForCategory(String category) {
        return categoryCounts.getOrDefault(category, 0);
    }

    /**
     * Get event count for a specific category (alias for getCountForCategory).
     *
     * @param category category name
     * @return event count for category, or 0 if category not found
     */
    public int getEventCount(String category) {
        return getCountForCategory(category);
    }

    /**
     * Get number of deadlock warnings.
     * <p>
     * Checks for events in the "deadlock" category.
     *
     * @return number of deadlock warning events
     */
    public int getDeadlockWarnings() {
        return getCountForCategory("deadlock");
    }

    /**
     * Get number of unique categories.
     *
     * @return category count
     */
    public int categoryCount() {
        return categoryCounts.size();
    }
}
