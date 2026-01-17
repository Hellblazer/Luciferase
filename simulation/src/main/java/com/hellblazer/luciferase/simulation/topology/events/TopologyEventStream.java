/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.simulation.topology.events;

import io.javalin.websocket.WsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Streams topology events to WebSocket clients.
 * <p>
 * Acts as a bridge between topology components (TopologyExecutor,
 * DensityMonitor, TopologyConsensusCoordinator) and WebSocket
 * visualization clients.
 * <p>
 * Features:
 * <ul>
 *   <li>Thread-safe client management</li>
 *   <li>Automatic JSON serialization</li>
 *   <li>Error handling for disconnected clients</li>
 *   <li>Client registration/deregistration</li>
 * </ul>
 * <p>
 * Usage:
 * <pre>
 * var stream = new TopologyEventStream();
 * topologyExecutor.addListener(stream);
 * densityMonitor.addListener(stream);
 * consensusCoordinator.addListener(stream);
 * </pre>
 * <p>
 * Interactive Visualization Demo Enhancement
 *
 * @author hal.hildebrand
 */
public class TopologyEventStream implements TopologyEventListener {

    private static final Logger log = LoggerFactory.getLogger(TopologyEventStream.class);

    private final Set<WsContext> clients = ConcurrentHashMap.newKeySet();

    /**
     * Register a WebSocket client to receive topology events.
     *
     * @param client WebSocket context
     */
    public void addClient(WsContext client) {
        clients.add(client);
        log.debug("Topology event client connected: {} (total: {})", client.sessionId(), clients.size());
    }

    /**
     * Unregister a WebSocket client.
     *
     * @param client WebSocket context
     */
    public void removeClient(WsContext client) {
        clients.remove(client);
        log.debug("Topology event client disconnected: {} (total: {})", client.sessionId(), clients.size());
    }

    /**
     * Get the current number of connected clients.
     *
     * @return client count
     */
    public int getClientCount() {
        return clients.size();
    }

    @Override
    public void onTopologyEvent(TopologyEvent event) {
        if (clients.isEmpty()) {
            return;
        }

        var json = eventToJson(event);
        broadcastToClients(json);
    }

    /**
     * Broadcast JSON to all connected clients.
     * <p>
     * Handles disconnected clients gracefully by removing them.
     *
     * @param json JSON string to broadcast
     */
    private void broadcastToClients(String json) {
        var disconnected = new ArrayList<WsContext>();

        for (var client : clients) {
            try {
                client.send(json);
            } catch (Exception e) {
                log.warn("Failed to send topology event to client {}: {}", client.sessionId(), e.getMessage());
                disconnected.add(client);
            }
        }

        disconnected.forEach(clients::remove);
    }

    /**
     * Convert topology event to JSON string.
     * <p>
     * Uses manual JSON construction for performance (no reflection overhead).
     *
     * @param event topology event
     * @return JSON string
     */
    private String eventToJson(TopologyEvent event) {
        return switch (event) {
            case SplitEvent e -> splitEventToJson(e);
            case MergeEvent e -> mergeEventToJson(e);
            case MoveEvent e -> moveEventToJson(e);
            case DensityStateChangeEvent e -> densityStateChangeEventToJson(e);
            case ConsensusVoteEvent e -> consensusVoteEventToJson(e);
        };
    }

    private String splitEventToJson(SplitEvent event) {
        return String.format(
            """
            {"eventType":"split","eventId":"%s","timestamp":%d,"sourceBubbleId":"%s","newBubbleId":"%s","entitiesMoved":%d,"success":%b}""",
            event.eventId(), event.timestamp(), event.sourceBubbleId(), event.newBubbleId(),
            event.entitiesMoved(), event.success()
        );
    }

    private String mergeEventToJson(MergeEvent event) {
        return String.format(
            """
            {"eventType":"merge","eventId":"%s","timestamp":%d,"sourceBubbleId":"%s","targetBubbleId":"%s","entitiesMoved":%d,"success":%b}""",
            event.eventId(), event.timestamp(), event.sourceBubbleId(), event.targetBubbleId(),
            event.entitiesMoved(), event.success()
        );
    }

    private String moveEventToJson(MoveEvent event) {
        return String.format(
            """
            {"eventType":"move","eventId":"%s","timestamp":%d,"bubbleId":"%s","oldCentroid":{"x":%f,"y":%f,"z":%f},"newCentroid":{"x":%f,"y":%f,"z":%f},"success":%b}""",
            event.eventId(), event.timestamp(), event.bubbleId(),
            event.oldCentroidX(), event.oldCentroidY(), event.oldCentroidZ(),
            event.newCentroidX(), event.newCentroidY(), event.newCentroidZ(),
            event.success()
        );
    }

    private String densityStateChangeEventToJson(DensityStateChangeEvent event) {
        return String.format(
            """
            {"eventType":"density_state_change","eventId":"%s","timestamp":%d,"bubbleId":"%s","oldState":"%s","newState":"%s","entityCount":%d,"densityRatio":%f}""",
            event.eventId(), event.timestamp(), event.bubbleId(),
            event.oldState(), event.newState(), event.entityCount(), event.densityRatio()
        );
    }

    private String consensusVoteEventToJson(ConsensusVoteEvent event) {
        return String.format(
            """
            {"eventType":"consensus_vote","eventId":"%s","timestamp":%d,"proposalId":"%s","voterId":"%s","vote":"%s","quorum":%d,"needed":%d}""",
            event.eventId(), event.timestamp(), event.proposalId(), event.voterId(),
            event.vote(), event.quorum(), event.needed()
        );
    }
}
