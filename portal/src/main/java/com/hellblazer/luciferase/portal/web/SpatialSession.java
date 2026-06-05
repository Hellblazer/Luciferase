package com.hellblazer.luciferase.portal.web;

import com.hellblazer.luciferase.common.time.Clock;

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
     * Create a new session with generated ID, using the system clock.
     */
    public static SpatialSession create() {
        return create(Clock.system());
    }

    /**
     * Create a new session with generated ID, using the supplied clock.
     * Preferred in server code — pass the server's injected {@link Clock} so all
     * timestamps share a single consistent time source.
     */
    public static SpatialSession create(Clock clock) {
        var now = Instant.ofEpochMilli(clock.currentTimeMillis());
        return new SpatialSession(
            UUID.randomUUID().toString(),
            now,
            now
        );
    }

    /**
     * Create a copy with updated lastAccessed timestamp, using the system clock.
     */
    public SpatialSession touch() {
        return touch(Clock.system());
    }

    /**
     * Create a copy with updated lastAccessed timestamp, using the supplied clock.
     * Preferred in server code — pass the server's injected {@link Clock}.
     */
    public SpatialSession touch(Clock clock) {
        return new SpatialSession(id, created, Instant.ofEpochMilli(clock.currentTimeMillis()));
    }

    /**
     * Check if session has expired based on timeout duration, using the system clock.
     */
    public boolean isExpired(long timeoutMillis) {
        return isExpired(timeoutMillis, Instant.now().toEpochMilli());
    }

    /**
     * Check if session has expired based on timeout duration and an explicit current time.
     * Preferred for testable code — pass {@code clock.currentTimeMillis()} as {@code nowMillis}.
     */
    public boolean isExpired(long timeoutMillis, long nowMillis) {
        return nowMillis - lastAccessed.toEpochMilli() > timeoutMillis;
    }

    @Override
    public void close() {
        // No per-session resources held here; heavyweight cleanup is in the service
        // layer (GpuService, RenderService, SpatialIndexService) and must be driven
        // by the server via cleanupSessionResources().
    }
}
