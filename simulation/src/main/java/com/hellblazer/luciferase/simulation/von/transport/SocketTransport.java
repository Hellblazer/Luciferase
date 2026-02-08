/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.simulation.von.transport;

import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import com.hellblazer.luciferase.simulation.bubble.RealTimeController;
import com.hellblazer.luciferase.simulation.causality.FirefliesViewMonitor;
import com.hellblazer.luciferase.simulation.delos.fireflies.FirefliesMembershipView;
import com.hellblazer.luciferase.simulation.von.TransportVonMessage;
import com.hellblazer.luciferase.simulation.von.Message;
import com.hellblazer.luciferase.simulation.von.MessageConverter;
import com.hellblazer.luciferase.simulation.von.MessageFactory;
import com.hellblazer.luciferase.simulation.von.Transport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * TCP-based transport with Fireflies virtual synchrony ACK.
 * <p>
 * Composes MemberDirectory and ConnectionManager for SRP.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Message sending with Fireflies ACK semantics</li>
 *   <li>Message handler registry and dispatch</li>
 *   <li>Composition of MemberDirectory and ConnectionManager</li>
 * </ul>
 * <p>
 * Delegates member routing to {@link MemberDirectory} and connection
 * lifecycle to {@link ConnectionManager} for SRP.
 * <p>
 * Marked {@code final} - use composition for extension, not inheritance.
 *
 * @see MemberDirectory
 * @see ConnectionManager
 */
public final class SocketTransport implements NetworkTransport {

    private static final Logger log = LoggerFactory.getLogger(SocketTransport.class);

    private final UUID localId;
    private final MessageFactory factory;
    private final List<Consumer<Message>> handlers = new CopyOnWriteArrayList<>();
    private final MemberDirectory memberDirectory;
    private final ConnectionManager connectionManager;
    private final FirefliesMembershipView membership;
    private final FirefliesViewMonitor viewMonitor;
    private final RealTimeController controller;

    /**
     * Create a SocketTransport with a random local ID.
     *
     * @param myAddress    This process's network address
     * @param membership   Fireflies membership view for virtual synchrony
     * @param viewMonitor  View stability monitor for ACK semantics
     * @param controller   Simulation time controller for tick listeners
     */
    public SocketTransport(ProcessAddress myAddress,
                          FirefliesMembershipView membership,
                          FirefliesViewMonitor viewMonitor,
                          RealTimeController controller) {
        this(UUID.randomUUID(), myAddress, membership, viewMonitor, controller);
    }

    /**
     * Create a SocketTransport with a specific local ID.
     *
     * @param localId      UUID for this transport (typically matches the Bubble's UUID)
     * @param myAddress    This process's network address
     * @param membership   Fireflies membership view for virtual synchrony
     * @param viewMonitor  View stability monitor for ACK semantics
     * @param controller   Simulation time controller for tick listeners
     */
    public SocketTransport(UUID localId,
                          ProcessAddress myAddress,
                          FirefliesMembershipView membership,
                          FirefliesViewMonitor viewMonitor,
                          RealTimeController controller) {
        // CRITICAL: Initialize components BEFORE any methods can be called
        // (prevents concurrent initialization race)
        this.localId = Objects.requireNonNull(localId, "localId must not be null");
        this.membership = Objects.requireNonNull(membership, "membership must not be null");
        this.viewMonitor = Objects.requireNonNull(viewMonitor, "viewMonitor must not be null");
        this.controller = Objects.requireNonNull(controller, "controller must not be null");
        this.factory = MessageFactory.system();
        this.memberDirectory = new ConcurrentMemberDirectory();
        this.connectionManager = new SocketConnectionManager(myAddress, this::handleIncomingMessage);
    }

    @Override
    public void listenOn(ProcessAddress bindAddress) throws IOException {
        connectionManager.listenOn(bindAddress);
    }

    @Override
    public void connectTo(ProcessAddress remoteAddress) throws IOException {
        connectionManager.connectTo(remoteAddress);
    }


    @Override
    public void sendToNeighbor(UUID neighborId, Message message) throws TransportException {
        // Get address from MemberDirectory
        var address = memberDirectory.getAddressFor(neighborId);
        if (address == null) {
            throw new TransportException("No address for neighbor: " + neighborId);
        }

        try {
            // Convert and send via ConnectionManager
            var transportMsg = convertToTransport(message);
            connectionManager.sendToProcess(address.processId(), transportMsg);
        } catch (IOException e) {
            throw new TransportException("Failed to send to " + neighborId, e);
        }
    }

    /**
     * Send a message asynchronously with Fireflies virtual synchrony ACK.
     * <p>
     * <strong>Delivery Guarantee:</strong> "With high probability" delivery based on Fireflies
     * virtual synchrony. Not a traditional message-level ACK - uses view stability as a proxy
     * for delivery success. Virtual synchrony ensures that all messages sent within a stable view
     * are delivered to all live members.
     *
     * <h2>Semantics</h2>
     * <ul>
     *   <li><strong>Message sent immediately:</strong> Message is transmitted over socket before ACK monitoring begins</li>
     *   <li><strong>ACK waits for view stability:</strong> Future completes when Fireflies view has been stable for
     *       {@link com.hellblazer.luciferase.simulation.causality.FirefliesViewMonitor} threshold (default 30 ticks = 300ms at 100Hz)</li>
     *   <li><strong>View change detection:</strong> If view ID changes between send and stability, future completes
     *       exceptionally (view change may indicate member failure, message delivery uncertain)</li>
     *   <li><strong>Timeout:</strong> Future fails after 5 seconds if view never stabilizes</li>
     * </ul>
     *
     * <h2>Implementation (Option B - Round Timer Integration)</h2>
     * <p>
     * Hooks into Fireflies round timer (RealTimeController at 100Hz) instead of separate polling thread:
     * <ol>
     *   <li>Send message via {@link #sendToNeighbor}</li>
     *   <li>Capture current view ID from {@link FirefliesMembershipView}</li>
     *   <li>Register {@link com.hellblazer.luciferase.simulation.bubble.RealTimeController.TickListener}
     *       to check stability on each Fireflies tick (every 10ms)</li>
     *   <li>On each tick: check if view stable via {@link com.hellblazer.luciferase.simulation.causality.FirefliesViewMonitor#isViewStable()}</li>
     *   <li>Auto-cleanup: Listener removed when future completes (success, failure, or timeout)</li>
     * </ol>
     * <p>
     * <strong>Benefits:</strong> Zero additional threads, deterministic testing with TestClock, minimal overhead
     * (listener only active while ACK pending).
     *
     * <h2>Thread Safety</h2>
     * <p>
     * Tick listeners run on RealTimeController tick thread. CompletableFuture ensures atomic completion.
     * Listener auto-removal via {@code whenComplete()} prevents leaks.
     *
     * <h2>Example Usage</h2>
     * <pre>{@code
     * var ackFuture = transport.sendToNeighborAsync(neighborId, message);
     * ackFuture.thenAccept(ack -> log.info("Message delivered with virtual synchrony"))
     *          .exceptionally(ex -> {
     *              log.warn("Message may not have been delivered: " + ex.getMessage());
     *              return null;
     *          });
     * }</pre>
     *
     * @param neighborId UUID of the target neighbor
     * @param message    Message to send
     * @return Future that completes when view is stable, or exceptionally if view changes/timeout
     * @throws IllegalArgumentException if neighborId or message is null
     * @see com.hellblazer.luciferase.simulation.causality.FirefliesViewMonitor
     * @see com.hellblazer.luciferase.simulation.delos.fireflies.FirefliesMembershipView
     */
    @Override
    public CompletableFuture<Message.Ack> sendToNeighborAsync(UUID neighborId, Message message) {
        var future = new CompletableFuture<Message.Ack>();

        // Send message immediately (traditional Transport behavior)
        try {
            sendToNeighbor(neighborId, message);
        } catch (TransportException e) {
            future.completeExceptionally(e);
            return future;
        }

        // Capture view ID AFTER successful send to avoid TOCTOU race
        // (Fix for Luciferase-y1pd: prevents false positive failures when view changes before send)
        var sentViewId = membership.getCurrentViewId();

        // Hook into Fireflies round timer to check view stability
        RealTimeController.TickListener checkStability = (simTime, lamportClock) -> {
            if (future.isDone()) {
                return;  // Already completed or timed out
            }

            var currentViewId = membership.getCurrentViewId();
            if (!currentViewId.equals(sentViewId)) {
                // View changed - delivery uncertain, fail with exception
                future.completeExceptionally(new TransportException(
                    "View changed during send (sent in " + sentViewId + ", now " + currentViewId + ")"
                ));
            } else if (viewMonitor.isViewStable()) {
                // View stable - high probability of delivery
                future.complete(factory.createAck(UUID.randomUUID(), neighborId));
            }
            // else: view unchanged but not stable yet - keep waiting
        };

        controller.addTickListener(checkStability);

        // Remove listener when future completes (success, failure, or timeout)
        future.whenComplete((ack, error) -> controller.removeTickListener(checkStability));

        // Timeout after 5 seconds (failsafe for hung views)
        return future.orTimeout(5, TimeUnit.SECONDS);
    }

    /**
     * Handle an incoming TransportVonMessage from the network.
     * <p>
     * Converts back to Message and dispatches to registered handlers.
     *
     * @param transportMsg Deserialized message from socket
     */
    private void handleIncomingMessage(TransportVonMessage transportMsg) {
        log.debug("Received TransportVonMessage type={}", transportMsg.type());

        // Convert to Message
        var vonMsg = MessageConverter.fromTransport(transportMsg);

        // Deliver to handlers
        for (var handler : handlers) {
            try {
                handler.accept(vonMsg);
            } catch (Exception e) {
                log.error("Handler error for {}: {}", vonMsg.getClass().getSimpleName(), e.getMessage(), e);
            }
        }
    }

    /**
     * Convert Message to TransportVonMessage for serialization.
     * <p>
     * Phase 6A: Simplified conversion with basic field extraction.
     * Phase 6B: Will use full MessageConverter with all message types.
     */
    private TransportVonMessage convertToTransport(Message message) {
        // Phase 6A: Use new comprehensive converter
        // Delegates to MessageConverter which pattern matches on message type
        return MessageConverter.toTransport(message);
    }


    @Override
    public List<ProcessAddress> getConnectedProcesses() {
        return connectionManager.getConnectedProcesses();
    }

    @Override
    public void closeAll() throws IOException {
        connectionManager.closeAll();
    }


    // Implement remaining Transport methods
    @Override
    public void onMessage(Consumer<Message> handler) {
        handlers.add(handler);
    }

    @Override
    public void removeMessageHandler(Consumer<Message> handler) {
        handlers.remove(handler);
    }

    @Override
    public Optional<MemberInfo> lookupMember(UUID memberId) {
        return memberDirectory.lookupMember(memberId);
    }

    @Override
    public MemberInfo routeToKey(TetreeKey<?> key) throws TransportException {
        return memberDirectory.routeToKey(key);
    }

    @Override
    public UUID getLocalId() {
        return localId;
    }

    @Override
    public boolean isConnected() {
        return connectionManager.isRunning();  // FIXES BUG: now false until first connect/listen
    }

    @Override
    public void close() {
        try {
            closeAll();
        } catch (IOException e) {
            log.error("Error closing SocketTransport", e);
        }
    }

    /**
     * Register a member UUID with its process address.
     * <p>
     * Used for lookupMember and routeToKey.
     * Delegates to MemberDirectory for SRP.
     *
     * @param memberId UUID of the member
     * @param address  ProcessAddress of the member
     */
    public void registerMember(UUID memberId, ProcessAddress address) {
        memberDirectory.registerMember(memberId, address);
    }
}
