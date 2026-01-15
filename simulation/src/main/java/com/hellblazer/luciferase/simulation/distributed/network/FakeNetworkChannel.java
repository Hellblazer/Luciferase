/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 * Part of Luciferase Simulation Framework. Licensed under AGPL v3.0.
 */

package com.hellblazer.luciferase.simulation.distributed.network;

import com.hellblazer.luciferase.simulation.distributed.migration.*;
import com.hellblazer.luciferase.simulation.events.*;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Testable in-memory network channel implementation.
 * Simulates network latency and packet loss for testing distributed scenarios.
 */
public class FakeNetworkChannel implements BubbleNetworkChannel {

    private static final Logger log = LoggerFactory.getLogger(FakeNetworkChannel.class);

    private final UUID nodeId;
    private final Map<UUID, String> nodeAddresses = new ConcurrentHashMap<>();
    private final Queue<PendingMessage> outboundMessages = new ConcurrentLinkedQueue<>();
    private final Random random = new Random();

    private EntityDepartureListener departureListener;
    private ViewSynchronyAckListener ackListener;
    private EntityRollbackListener rollbackListener;

    private long networkLatencyMs = 0;
    private double packetLossRate = 0.0;

    private static final Map<UUID, FakeNetworkChannel> NETWORK = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    /**
     * Create a fake network channel for this node.
     *
     * @param nodeId Unique identifier for this node
     */
    public FakeNetworkChannel(UUID nodeId) {
        this.nodeId = nodeId;
    }

    @Override
    public void initialize(UUID nodeId, String nodeAddress) {
        nodeAddresses.put(nodeId, nodeAddress);
        NETWORK.put(nodeId, this);
        log.info("Network channel initialized: {} at {}", nodeId, nodeAddress);
    }

    @Override
    public void registerNode(UUID nodeId, String nodeAddress) {
        nodeAddresses.put(nodeId, nodeAddress);
        log.debug("Registered remote node: {} at {}", nodeId, nodeAddress);
    }

    @Override
    public boolean sendEntityDeparture(UUID targetNodeId, EntityDepartureEvent event) {
        if (!isNodeReachable(targetNodeId)) {
            log.warn("Target node {} unreachable", targetNodeId);
            return false;
        }

        if (shouldDropPacket()) {
            log.debug("Simulated packet loss: EntityDepartureEvent to {}", targetNodeId);
            return false;
        }

        var message = new PendingMessage(
            targetNodeId,
            () -> {
                var channel = NETWORK.get(targetNodeId);
                if (channel != null && channel.departureListener != null) {
                    channel.departureListener.onEntityDeparture(nodeId, event);
                }
            },
            System.currentTimeMillis() + networkLatencyMs
        );

        outboundMessages.offer(message);

        // If no latency, deliver immediately
        if (networkLatencyMs == 0) {
            message.action.run();
            outboundMessages.remove(message);
        } else {
            scheduleDelivery(message);
        }
        return true;
    }

    @Override
    public boolean sendViewSynchronyAck(UUID sourceNodeId, ViewSynchronyAck event) {
        if (!isNodeReachable(sourceNodeId)) {
            log.warn("Target node {} unreachable", sourceNodeId);
            return false;
        }

        if (shouldDropPacket()) {
            log.debug("Simulated packet loss: ViewSynchronyAck to {}", sourceNodeId);
            return false;
        }

        var message = new PendingMessage(
            sourceNodeId,
            () -> {
                var channel = NETWORK.get(sourceNodeId);
                if (channel != null && channel.ackListener != null) {
                    channel.ackListener.onViewSynchronyAck(nodeId, event);
                }
            },
            System.currentTimeMillis() + networkLatencyMs
        );

        outboundMessages.offer(message);

        // If no latency, deliver immediately
        if (networkLatencyMs == 0) {
            message.action.run();
            outboundMessages.remove(message);
        } else {
            scheduleDelivery(message);
        }
        return true;
    }

    @Override
    public boolean sendEntityRollback(UUID targetNodeId, EntityRollbackEvent event) {
        if (!isNodeReachable(targetNodeId)) {
            log.warn("Target node {} unreachable", targetNodeId);
            return false;
        }

        if (shouldDropPacket()) {
            log.debug("Simulated packet loss: EntityRollbackEvent to {}", targetNodeId);
            return false;
        }

        var message = new PendingMessage(
            targetNodeId,
            () -> {
                var channel = NETWORK.get(targetNodeId);
                if (channel != null && channel.rollbackListener != null) {
                    channel.rollbackListener.onEntityRollback(nodeId, event);
                }
            },
            System.currentTimeMillis() + networkLatencyMs
        );

        outboundMessages.offer(message);

        // If no latency, deliver immediately
        if (networkLatencyMs == 0) {
            message.action.run();
            outboundMessages.remove(message);
        } else {
            scheduleDelivery(message);
        }
        return true;
    }

    @Override
    public void setEntityDepartureListener(EntityDepartureListener listener) {
        this.departureListener = listener;
    }

    @Override
    public void setViewSynchronyAckListener(ViewSynchronyAckListener listener) {
        this.ackListener = listener;
    }

    @Override
    public void setEntityRollbackListener(EntityRollbackListener listener) {
        this.rollbackListener = listener;
    }

    @Override
    public void setNetworkLatency(long latencyMs) {
        this.networkLatencyMs = Math.max(0, latencyMs);
        log.debug("Network latency set to {}ms", networkLatencyMs);
    }

    @Override
    public void setPacketLoss(double lossRate) {
        this.packetLossRate = Math.max(0.0, Math.min(1.0, lossRate));
        log.debug("Packet loss rate set to {}", packetLossRate);
    }

    @Override
    public boolean isNodeReachable(UUID nodeId) {
        return nodeAddresses.containsKey(nodeId) && NETWORK.containsKey(nodeId);
    }

    @Override
    public int getPendingMessageCount() {
        return outboundMessages.size();
    }

    /**
     * Shutdown this network channel and clean up resources.
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        NETWORK.remove(nodeId);
        log.debug("Network channel shut down: {}", nodeId);
    }

    /**
     * Deliver all pending messages immediately (for testing).
     */
    public void flushPendingMessages() {
        var iterator = outboundMessages.iterator();
        while (iterator.hasNext()) {
            var message = iterator.next();
            if (System.currentTimeMillis() >= message.deliveryTimeMs) {
                message.action.run();
                iterator.remove();
            }
        }
    }

    private void scheduleDelivery(PendingMessage message) {
        long delayMs = Math.max(0, message.deliveryTimeMs - System.currentTimeMillis());
        scheduler.schedule(() -> {
            try {
                message.action.run();
                outboundMessages.remove(message);
            } catch (Exception e) {
                log.error("Error delivering message", e);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private boolean shouldDropPacket() {
        return random.nextDouble() < packetLossRate;
    }

    /**
     * Internal representation of a pending message in the network.
     */
    private static class PendingMessage {
        final UUID targetNodeId;
        final Runnable action;
        final long deliveryTimeMs;

        PendingMessage(UUID targetNodeId, Runnable action, long deliveryTimeMs) {
            this.targetNodeId = targetNodeId;
            this.action = action;
            this.deliveryTimeMs = deliveryTimeMs;
        }
    }

    /**
     * Static helper to get or create a channel for a node (for testing).
     *
     * @param nodeId Node identifier
     * @return FakeNetworkChannel for this node
     */
    public static FakeNetworkChannel getOrCreate(UUID nodeId) {
        return NETWORK.computeIfAbsent(nodeId, id -> new FakeNetworkChannel(id));
    }

    /**
     * Static helper to clear all network channels (for test cleanup).
     */
    public static void clearNetwork() {
        NETWORK.values().forEach(FakeNetworkChannel::shutdown);
        NETWORK.clear();
    }
}
