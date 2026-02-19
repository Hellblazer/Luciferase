/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.viz.render.protocol;

import com.hellblazer.luciferase.lucien.Frustum3D;
import javax.vecmath.Point3f;
import java.util.Map;

/**
 * Messages sent from browser client to server.
 * clientId is never in message payloads — server identifies client from WS session.
 *
 * @author hal.hildebrand
 */
public sealed interface ClientMessage permits
    ClientMessage.Hello,
    ClientMessage.SnapshotRequest,
    ClientMessage.Subscribe,
    ClientMessage.ViewportUpdate,
    ClientMessage.Unsubscribe {

    /** Initial handshake. */
    record Hello(String version) implements ClientMessage {}

    /**
     * Request a snapshot manifest for all occupied keys at the given level.
     * @param requestId  echoed back in SnapshotManifest for correlation
     * @param level      LOD level (0–10 for Tet, 0–21 for Morton)
     */
    record SnapshotRequest(String requestId, int level) implements ClientMessage {}

    /**
     * Begin Phase B push subscription.
     * @param snapshotToken  token from the preceding SnapshotManifest
     * @param knownVersions  map of keyString → version from snapshot
     */
    record Subscribe(long snapshotToken, Map<String, Long> knownVersions)
        implements ClientMessage {
        public Subscribe {
            knownVersions = Map.copyOf(knownVersions);
        }
    }

    /** Camera moved — server updates visible set. Throttled by client to ≤100ms. */
    record ViewportUpdate(Frustum3D frustum, Point3f cameraPos, int level)
        implements ClientMessage {}

    /** End subscription and stop receiving push updates. */
    record Unsubscribe() implements ClientMessage {}
}
