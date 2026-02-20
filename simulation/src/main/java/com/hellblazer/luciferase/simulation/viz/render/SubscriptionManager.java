/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.simulation.viz.render;

import com.hellblazer.luciferase.lucien.Frustum3D;
import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import com.hellblazer.luciferase.simulation.viz.render.protocol.ServerMessage;

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages active client subscriptions and push delivery.
 *
 * <p>Thread-safe via ConcurrentHashMap for client state and CopyOnWriteArrayList
 * for fair-rotation ordering. No synchronization primitives used.
 *
 * @author hal.hildebrand
 */
public final class SubscriptionManager {

    /**
     * Per-client subscription state. The {@code knownVersions} map is intentionally
     * mutable (ConcurrentHashMap) to allow in-place version tracking without
     * replacing the record on each push. All other fields are effectively immutable.
     */
    public record ClientState(
        String sessionId,
        Transport transport,
        ConcurrentHashMap<String, Long> knownVersions,
        Frustum3D frustum,
        int level
    ) {}

    private final ConcurrentHashMap<String, ClientState> clients    = new ConcurrentHashMap<>();
    // Ordered list for fair rotation in streaming cycle; addIfAbsent ensures idempotent subscribe.
    private final CopyOnWriteArrayList<String>           orderedIds = new CopyOnWriteArrayList<>();

    /**
     * Register a client subscription.
     *
     * @param sessionId     unique session identifier
     * @param transport     server-side transport handle
     * @param knownVersions pre-existing client key versions (may be empty)
     * @param frustum       current viewport frustum
     * @param level         desired LOD level
     */
    public void subscribe(String sessionId, Transport transport,
                          Map<String, Long> knownVersions, Frustum3D frustum, int level) {
        var state = new ClientState(sessionId, transport,
                                    new ConcurrentHashMap<>(knownVersions), frustum, level);
        clients.put(sessionId, state);
        orderedIds.addIfAbsent(sessionId);
    }

    /**
     * Remove a client subscription. Idempotent — safe to call even if sessionId is unknown.
     *
     * @param sessionId the session to remove
     */
    public void unsubscribe(String sessionId) {
        clients.remove(sessionId);
        orderedIds.remove(sessionId);
    }

    /**
     * Update a client's viewport. Replaces frustum and level while preserving all other state.
     *
     * @param sessionId the session to update
     * @param frustum   new viewport frustum
     * @param level     new LOD level
     */
    public void updateViewport(String sessionId, Frustum3D frustum, int level) {
        var old = clients.get(sessionId);
        if (old == null) {
            return;
        }
        // knownVersions is shared by reference — concurrent push() writes are preserved.
        clients.put(sessionId, new ClientState(
            old.sessionId(), old.transport(), old.knownVersions(), frustum, level));
    }

    /**
     * Push a RegionUpdate control message to a specific client.
     * Records the new version in knownVersions after successful send.
     * If the transport throws, the version is not recorded and the client will
     * re-receive the region on the next streaming cycle (idempotent delivery).
     *
     * @param sessionId the target session
     * @param key       the spatial key that was updated
     * @param version   the new content version
     */
    public void push(String sessionId, SpatialKey<?> key, long version) {
        var state = clients.get(sessionId);
        if (state == null) {
            return;
        }
        state.transport().send(new ServerMessage.RegionUpdate(key, version));
        state.knownVersions().put(keyString(key), version);
    }

    /**
     * Push a pre-encoded binary frame to a specific client.
     *
     * @param sessionId the target session
     * @param frame     the binary payload
     */
    public void pushBinary(String sessionId, byte[] frame) {
        var state = clients.get(sessionId);
        if (state != null) {
            state.transport().sendBinary(frame);
        }
    }

    /**
     * Broadcast a RegionUpdate to ALL active subscribers.
     * Used by the streaming cycle when a region changes.
     *
     * @param key     the spatial key that was updated
     * @param version the new content version
     */
    public void broadcast(SpatialKey<?> key, long version) {
        // Weakly consistent: clients that unsubscribe mid-broadcast are silently skipped.
        clients.keySet().forEach(id -> push(id, key, version));
    }

    /**
     * Return the last-known version of a key for a given client.
     * Returns 0 if the client is unknown or the key has not been sent.
     *
     * @param sessionId the session to query
     * @param key       the spatial key
     * @return the known version, or 0 if unknown
     */
    public long knownVersion(String sessionId, SpatialKey<?> key) {
        var state = clients.get(sessionId);
        if (state == null) {
            return 0L;
        }
        return state.knownVersions().getOrDefault(keyString(key), 0L);
    }

    /** Returns the number of currently active subscriptions. */
    public int activeClientCount() {
        return clients.size();
    }

    /**
     * Snapshot of session IDs in insertion order for fair-rotation streaming.
     *
     * @return immutable list of session IDs
     */
    public List<String> orderedSessionIds() {
        return List.copyOf(orderedIds);
    }

    /**
     * Return the client state for a session, or null if not subscribed.
     *
     * @param sessionId the session to look up
     * @return client state or null
     */
    public ClientState get(String sessionId) {
        return clients.get(sessionId);
    }

    /**
     * Encode a SpatialKey to a stable, unique string for use as a map key.
     *
     * <p>Format:
     * <ul>
     *   <li>MortonKey: {@code oct:<level>:<base64-morton-code>}
     *   <li>TetreeKey: {@code tet:<level>:<base64-low-bits>}
     * </ul>
     *
     * @param key the spatial key to encode
     * @return a stable string representation
     * @throws IllegalArgumentException for unknown key types
     */
    public static String keyString(SpatialKey<?> key) {
        var b64 = Base64.getEncoder();
        return switch (key) {
            case MortonKey mk -> "oct:" + mk.getLevel() + ":" + b64.encodeToString(
                ByteBuffer.allocate(8).putLong(mk.getMortonCode()).array());
            case TetreeKey<?> tk -> "tet:" + tk.getLevel() + ":" + b64.encodeToString(
                ByteBuffer.allocate(8).putLong(tk.getLowBits()).array());
            default -> throw new IllegalArgumentException("Unknown SpatialKey type: " + key.getClass().getName());
        };
    }
}
