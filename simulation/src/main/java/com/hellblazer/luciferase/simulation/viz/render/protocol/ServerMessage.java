/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.viz.render.protocol;

import com.hellblazer.luciferase.lucien.SpatialKey;
import java.util.List;

/**
 * Messages sent from server to browser client.
 *
 * @author hal.hildebrand
 */
public sealed interface ServerMessage permits
    ServerMessage.HelloAck,
    ServerMessage.SnapshotManifest,
    ServerMessage.RegionUpdate,
    ServerMessage.RegionRemoved,
    ServerMessage.SnapshotRequired,
    ServerMessage.Error {

    record HelloAck(String sessionId) implements ServerMessage {}

    /**
     * Manifest for a snapshot: one entry per occupied key.
     * Binary frames follow immediately after, tagged with snapshotToken.
     */
    record SnapshotManifest(
        String requestId,
        long snapshotToken,
        List<RegionEntry> regions
    ) implements ServerMessage {
        /** Version captured at manifest-compilation time (not live). */
        public record RegionEntry(SpatialKey<?> key, long snapshotVersion, long dataSize) {}
    }

    /**
     * A region was built/updated. Binary payload delivered separately via binary frame.
     * Sent when server has fresh content for a subscribed key.
     */
    record RegionUpdate(SpatialKey<?> key, long version) implements ServerMessage {}

    record RegionRemoved(SpatialKey<?> key) implements ServerMessage {}

    /**
     * Server has evicted this key (no entities, cache miss).
     * Client must remove it from knownVersions; per-key re-snapshot on next request.
     */
    record SnapshotRequired(SpatialKey<?> key) implements ServerMessage {}

    record Error(String code, String message) implements ServerMessage {}
}
