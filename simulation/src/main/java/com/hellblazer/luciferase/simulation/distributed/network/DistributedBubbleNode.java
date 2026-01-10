/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 * Part of Luciferase Simulation Framework. Licensed under AGPL v3.0.
 */

package com.hellblazer.luciferase.simulation.distributed.network;

import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.bubble.EnhancedBubbleMigrationIntegration;
import com.hellblazer.luciferase.simulation.causality.*;
import com.hellblazer.luciferase.simulation.distributed.migration.*;
import com.hellblazer.luciferase.simulation.events.*;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Distributed network-enabled bubble node wrapper.
 * Wraps EnhancedBubble to add network communication capabilities for
 * distributed entity migration across multiple bubble nodes.
 */
public class DistributedBubbleNode implements BubbleNetworkChannel.EntityDepartureListener,
                                              BubbleNetworkChannel.ViewSynchronyAckListener,
                                              BubbleNetworkChannel.EntityRollbackListener {

    private static final Logger log = LoggerFactory.getLogger(DistributedBubbleNode.class);

    private final UUID nodeId;
    private final EnhancedBubble bubble;
    private final BubbleNetworkChannel networkChannel;
    private final EnhancedBubbleMigrationIntegration migrationIntegration;
    private final EntityMigrationStateMachine fsm;

    /**
     * Create a distributed bubble node with network communication.
     *
     * @param nodeId                 Unique identifier for this node
     * @param bubble                 EnhancedBubble instance for this node
     * @param networkChannel         Network communication channel
     * @param migrationIntegration   Migration coordination integration
     * @param fsm                    Entity migration state machine
     */
    public DistributedBubbleNode(UUID nodeId,
                                EnhancedBubble bubble,
                                BubbleNetworkChannel networkChannel,
                                EnhancedBubbleMigrationIntegration migrationIntegration,
                                EntityMigrationStateMachine fsm) {
        this.nodeId = nodeId;
        this.bubble = bubble;
        this.networkChannel = networkChannel;
        this.migrationIntegration = migrationIntegration;
        this.fsm = fsm;

        // Register as listener for migration events from network
        networkChannel.setEntityDepartureListener(this);
        networkChannel.setViewSynchronyAckListener(this);
        networkChannel.setEntityRollbackListener(this);

        log.info("Distributed bubble node created: {} with bubble {}", nodeId, bubble.id());
    }

    /**
     * Get the node's unique identifier.
     *
     * @return UUID of this node
     */
    public UUID getNodeId() {
        return nodeId;
    }

    /**
     * Get the underlying EnhancedBubble.
     *
     * @return EnhancedBubble instance
     */
    public EnhancedBubble getBubble() {
        return bubble;
    }

    /**
     * Get the network channel.
     *
     * @return BubbleNetworkChannel for inter-node communication
     */
    public BubbleNetworkChannel getNetworkChannel() {
        return networkChannel;
    }

    /**
     * Process simulation tick including migration coordination and network delivery.
     *
     * @param currentTimeMs Current simulation time in milliseconds
     */
    public void processTick(long currentTimeMs) {
        // Process local bubble migrations
        migrationIntegration.processMigrations(currentTimeMs);

        // Process any pending network deliveries
        if (networkChannel instanceof FakeNetworkChannel) {
            ((FakeNetworkChannel) networkChannel).flushPendingMessages();
        }
    }

    /**
     * Initiate an optimistic migration to a remote node.
     *
     * @param entityId    UUID of entity to migrate
     * @param targetNodeId UUID of target node
     * @return true if migration successfully initiated
     */
    public boolean initiateRemoteMigration(UUID entityId, UUID targetNodeId) {
        // Check if target is reachable
        if (!networkChannel.isNodeReachable(targetNodeId)) {
            log.warn("Cannot migrate {}: target node {} unreachable", entityId, targetNodeId);
            return false;
        }

        // Initiate local migration
        migrationIntegration.getOptimisticMigrator()
            .initiateOptimisticMigration(entityId, bubble.id());

        log.debug("Initiated remote migration: {} from {} to {}", entityId, nodeId, targetNodeId);
        return true;
    }

    /**
     * Send entity departure event to target node.
     * Called when source bubble wants to notify target of incoming entity.
     *
     * @param targetNodeId UUID of target node
     * @param event        EntityDepartureEvent with migration details
     * @return true if message successfully sent
     */
    public boolean sendEntityDeparture(UUID targetNodeId, EntityDepartureEvent event) {
        return networkChannel.sendEntityDeparture(targetNodeId, event);
    }

    /**
     * Send view synchrony acknowledgment to source node.
     * Called when target bubble has received and processed entity.
     *
     * @param sourceNodeId UUID of source node
     * @param ack          ViewSynchronyAck with confirmation
     * @return true if message successfully sent
     */
    public boolean sendViewSynchronyAck(UUID sourceNodeId, ViewSynchronyAck ack) {
        return networkChannel.sendViewSynchronyAck(sourceNodeId, ack);
    }

    /**
     * Send rollback event to target node.
     * Called when source bubble rolls back due to view change.
     *
     * @param targetNodeId UUID of target node
     * @param event        EntityRollbackEvent with rollback details
     * @return true if message successfully sent
     */
    public boolean sendEntityRollback(UUID targetNodeId, EntityRollbackEvent event) {
        return networkChannel.sendEntityRollback(targetNodeId, event);
    }

    /**
     * Handle incoming entity departure event from remote node.
     *
     * @param sourceNodeId UUID of source node
     * @param event        EntityDepartureEvent from source
     */
    @Override
    public void onEntityDeparture(UUID sourceNodeId, EntityDepartureEvent event) {
        log.debug("Received EntityDepartureEvent from {}: entity {}", sourceNodeId, event.getEntityId());
        // Transition to GHOST state and queue deferred updates
        fsm.transition(event.getEntityId(), EntityMigrationState.GHOST);
    }

    /**
     * Handle incoming view synchrony acknowledgment from remote node.
     *
     * @param sourceNodeId UUID of source node
     * @param event        ViewSynchronyAck from target
     */
    @Override
    public void onViewSynchronyAck(UUID sourceNodeId, ViewSynchronyAck event) {
        log.debug("Received ViewSynchronyAck from {}: entity {}", sourceNodeId, event.getEntityId());
        // Transition to DEPARTED after acknowledgment
        fsm.transition(event.getEntityId(), EntityMigrationState.DEPARTED);
    }

    /**
     * Handle incoming entity rollback event from remote node.
     *
     * @param sourceNodeId UUID of source node
     * @param event        EntityRollbackEvent from source
     */
    @Override
    public void onEntityRollback(UUID sourceNodeId, EntityRollbackEvent event) {
        log.debug("Received EntityRollbackEvent from {}: entity {} reason {}",
                 sourceNodeId, event.getEntityId(), event.getReason());
        // Handle rollback: transition entity back to a stable state
        // If we were in MIGRATING_IN, go back to GHOST
        // If we were in OWNED, stay in OWNED (no change)
        var currentState = fsm.getState(event.getEntityId());
        if (currentState == EntityMigrationState.MIGRATING_IN) {
            fsm.transition(event.getEntityId(), EntityMigrationState.GHOST);
        }
    }

    /**
     * Configure network simulation latency (one-way, in milliseconds).
     *
     * @param latencyMs One-way latency in milliseconds
     */
    public void setNetworkLatency(long latencyMs) {
        networkChannel.setNetworkLatency(latencyMs);
    }

    /**
     * Configure network simulation packet loss rate (0.0 to 1.0).
     *
     * @param lossRate Probability of packet loss
     */
    public void setPacketLoss(double lossRate) {
        networkChannel.setPacketLoss(lossRate);
    }

    /**
     * Check if remote node is reachable.
     *
     * @param targetNodeId UUID of target node
     * @return true if node is reachable
     */
    public boolean isNodeReachable(UUID targetNodeId) {
        return networkChannel.isNodeReachable(targetNodeId);
    }

    /**
     * Get count of pending network messages.
     *
     * @return Number of pending messages awaiting delivery
     */
    public int getPendingMessageCount() {
        return networkChannel.getPendingMessageCount();
    }

    /**
     * Get migration oracle for boundary detection.
     *
     * @return MigrationOracle from integration
     */
    public MigrationOracle getMigrationOracle() {
        return migrationIntegration.getMigrationOracle();
    }

    /**
     * Get optimistic migrator for migration coordination.
     *
     * @return OptimisticMigrator from integration
     */
    public OptimisticMigrator getOptimisticMigrator() {
        return migrationIntegration.getOptimisticMigrator();
    }
}
