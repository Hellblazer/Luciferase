/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 * Part of Luciferase Simulation Framework. Licensed under AGPL v3.0.
 */

package com.hellblazer.luciferase.simulation.distributed.network;

import com.hellblazer.luciferase.simulation.distributed.migration.*;
import com.hellblazer.luciferase.simulation.events.*;
import java.util.UUID;

/**
 * Network communication channel abstraction for inter-bubble message exchange.
 * Provides message-based async communication with optional latency/loss simulation.
 */
public interface BubbleNetworkChannel {

    /**
     * Initialize the network channel for this node.
     *
     * @param nodeId    Unique identifier for this node
     * @param nodeAddress Address binding (format: "localhost:port")
     */
    void initialize(UUID nodeId, String nodeAddress);

    /**
     * Register a remote node in the network.
     *
     * @param nodeId    Unique identifier for the remote node
     * @param nodeAddress Network address for the remote node
     */
    void registerNode(UUID nodeId, String nodeAddress);

    /**
     * Send entity departure event to target bubble node.
     * Non-blocking; delivery guaranteed with retries.
     *
     * @param targetNodeId UUID of target node
     * @param event        EntityDepartureEvent to send
     * @return true if message queued for delivery
     */
    boolean sendEntityDeparture(UUID targetNodeId, EntityDepartureEvent event);

    /**
     * Send view synchrony acknowledgment to source bubble node.
     * Non-blocking; indicates target has received and processed EntityDepartureEvent.
     *
     * @param sourceNodeId UUID of source node
     * @param event        ViewSynchronyAck to send
     * @return true if message queued for delivery
     */
    boolean sendViewSynchronyAck(UUID sourceNodeId, ViewSynchronyAck event);

    /**
     * Send entity rollback notification to target bubble node.
     * Non-blocking; indicates migration should be rolled back due to view change.
     *
     * @param targetNodeId UUID of target node
     * @param event        EntityRollbackEvent to send
     * @return true if message queued for delivery
     */
    boolean sendEntityRollback(UUID targetNodeId, EntityRollbackEvent event);

    /**
     * Register listener for incoming entity departure events.
     *
     * @param listener Callback for EntityDepartureEvent reception
     */
    void setEntityDepartureListener(EntityDepartureListener listener);

    /**
     * Register listener for incoming view synchrony acknowledgments.
     *
     * @param listener Callback for ViewSynchronyAck reception
     */
    void setViewSynchronyAckListener(ViewSynchronyAckListener listener);

    /**
     * Register listener for incoming entity rollback events.
     *
     * @param listener Callback for EntityRollbackEvent reception
     */
    void setEntityRollbackListener(EntityRollbackListener listener);

    /**
     * Set network simulation latency (one-way, in milliseconds).
     * Used for testing; affects all subsequent message sends.
     *
     * @param latencyMs One-way latency in milliseconds
     */
    void setNetworkLatency(long latencyMs);

    /**
     * Set network simulation packet loss rate (0.0 to 1.0).
     * Used for testing; affects all subsequent message sends.
     *
     * @param lossRate Probability of packet loss (0.0 = no loss, 1.0 = 100% loss)
     */
    void setPacketLoss(double lossRate);

    /**
     * Check if a remote node is currently reachable.
     *
     * @param nodeId UUID of node to check
     * @return true if node is reachable
     */
    boolean isNodeReachable(UUID nodeId);

    /**
     * Get count of pending outbound messages waiting for delivery.
     *
     * @return Number of pending messages
     */
    int getPendingMessageCount();

    /**
     * Listener interface for incoming entity departure events.
     */
    interface EntityDepartureListener {
        /**
         * Invoked when EntityDepartureEvent is received from remote node.
         *
         * @param sourceNodeId UUID of source node
         * @param event        EntityDepartureEvent containing migration details
         */
        void onEntityDeparture(UUID sourceNodeId, EntityDepartureEvent event);
    }

    /**
     * Listener interface for incoming view synchrony acknowledgments.
     */
    interface ViewSynchronyAckListener {
        /**
         * Invoked when ViewSynchronyAck is received from remote node.
         *
         * @param sourceNodeId UUID of source node
         * @param event        ViewSynchronyAck confirming event receipt
         */
        void onViewSynchronyAck(UUID sourceNodeId, ViewSynchronyAck event);
    }

    /**
     * Listener interface for incoming entity rollback events.
     */
    interface EntityRollbackListener {
        /**
         * Invoked when EntityRollbackEvent is received from remote node.
         *
         * @param sourceNodeId UUID of source node
         * @param event        EntityRollbackEvent signaling migration rollback
         */
        void onEntityRollback(UUID sourceNodeId, EntityRollbackEvent event);
    }
}
