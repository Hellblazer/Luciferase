package com.hellblazer.luciferase.portal.web;

import java.time.Instant;
import java.util.UUID;

/**
 * Session state container for web-based spatial inspector.
 * Each session holds references to spatial indices, render data, and configuration.
 *
 * @param id        Unique session identifier
 * @param created   Timestamp when session was created
 * @param lastAccessed Timestamp of last access (for timeout management)
 */
public record SpatialSession(
    String id,
    Instant created,
    Instant lastAccessed
) implements AutoCloseable {

    /**
     * Create a new session with generated ID.
     */
    public static SpatialSession create() {
        var now = Instant.now();
        return new SpatialSession(
            UUID.randomUUID().toString(),
            now,
            now
        );
    }

    /**
     * Create a copy with updated lastAccessed timestamp.
     */
    public SpatialSession touch() {
        return new SpatialSession(id, created, Instant.now());
    }

    /**
     * Check if session has expired based on timeout duration.
     */
    public boolean isExpired(long timeoutMillis) {
        return Instant.now().toEpochMilli() - lastAccessed.toEpochMilli() > timeoutMillis;
    }

    @Override
    public void close() {
        // Placeholder for resource cleanup
        // Will be expanded in Phase 2+ to dispose GPU resources, spatial indices, etc.
    }
}
