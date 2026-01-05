/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.simulation.ghost;

import com.hellblazer.luciferase.simulation.bubble.*;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry tracking bubble-to-server assignments. Thread-safe for concurrent updates.
 * <p>
 * Critical for same-server optimization: when two bubbles share a server, they can use direct memory access instead of
 * ghost synchronization.
 *
 * @author hal.hildebrand
 */
public class ServerRegistry {

    /**
     * Mapping from bubble ID to server ID
     */
    private final Map<UUID, UUID> bubbleToServer = new ConcurrentHashMap<>();

    /**
     * Mapping from server ID to set of bubble IDs on that server
     */
    private final Map<UUID, Set<UUID>> serverToBubbles = new ConcurrentHashMap<>();

    /**
     * Register a bubble on a server. If the bubble was previously on a different server, it will be migrated.
     *
     * @param bubbleId bubble to register
     * @param serverId server hosting the bubble
     */
    public void registerBubble(UUID bubbleId, UUID serverId) {
        var oldServer = bubbleToServer.put(bubbleId, serverId);

        // Remove from old server if migrating
        if (oldServer != null && !oldServer.equals(serverId)) {
            var oldBubbles = serverToBubbles.get(oldServer);
            if (oldBubbles != null) {
                oldBubbles.remove(bubbleId);
            }
        }

        // Add to new server
        serverToBubbles.computeIfAbsent(serverId, k -> ConcurrentHashMap.newKeySet()).add(bubbleId);
    }

    /**
     * Unregister a bubble (e.g., bubble left or merged).
     *
     * @param bubbleId bubble to unregister
     */
    public void unregisterBubble(UUID bubbleId) {
        var serverId = bubbleToServer.remove(bubbleId);
        if (serverId != null) {
            var bubbles = serverToBubbles.get(serverId);
            if (bubbles != null) {
                bubbles.remove(bubbleId);
                // Clean up empty server entries to prevent memory leak
                if (bubbles.isEmpty()) {
                    serverToBubbles.remove(serverId, bubbles);
                }
            }
        }
    }

    /**
     * Get the server hosting a bubble.
     *
     * @param bubbleId bubble to query
     * @return serverId or null if not registered
     */
    public UUID getServerId(UUID bubbleId) {
        return bubbleToServer.get(bubbleId);
    }

    /**
     * Get all bubbles on a server.
     *
     * @param serverId server to query
     * @return unmodifiable set of bubble IDs on that server
     */
    public Set<UUID> getBubblesOnServer(UUID serverId) {
        return Collections.unmodifiableSet(serverToBubbles.getOrDefault(serverId, Set.of()));
    }

    /**
     * Check if two bubbles are on the same server. Critical for same-server optimization.
     *
     * @param bubble1 first bubble
     * @param bubble2 second bubble
     * @return true if both bubbles are on the same server
     */
    public boolean isSameServer(UUID bubble1, UUID bubble2) {
        var server1 = bubbleToServer.get(bubble1);
        var server2 = bubbleToServer.get(bubble2);
        return server1 != null && server1.equals(server2);
    }

    /**
     * Get total bubble count across all servers.
     *
     * @return total number of registered bubbles
     */
    public int getTotalBubbleCount() {
        return bubbleToServer.size();
    }

    /**
     * Get server count.
     *
     * @return number of servers with at least one bubble
     */
    public int getServerCount() {
        return serverToBubbles.size();
    }
}
