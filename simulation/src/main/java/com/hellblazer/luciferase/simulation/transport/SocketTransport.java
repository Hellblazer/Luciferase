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

package com.hellblazer.luciferase.simulation.transport;

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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Socket-based inter-process transport for VON communication.
 * <p>
 * Extends LocalServerTransport to add network capabilities via TCP sockets
 * and Java Serialization. Provides both server (accepting inbound connections)
 * and client (establishing outbound connections) functionality.
 * <p>
 * Scope Enforcement (Inc 6):
 * Both listenOn() and connectTo() enforce localhost-only operation by validating
 * that addresses are loopback (127.0.0.1 or localhost). Non-loopback addresses
 * throw IllegalArgumentException to prevent accidental scope creep.
 * <p>
 * Architecture:
 * <ul>
 *   <li>SocketServer: Accepts inbound connections on bind address</li>
 *   <li>SocketClient: Establishes outbound connections to remote processes</li>
 *   <li>Message routing: Uses MessageConverter for serialization</li>
 *   <li>Thread-safe: Concurrent client map and synchronized sends</li>
 * </ul>
 * <p>
 * Usage:
 * <pre>
 * var transport = new SocketTransport(ProcessAddress.localhost("p1", 9991));
 * transport.listenOn(transport.getMyAddress());
 * transport.connectTo(ProcessAddress.localhost("p2", 9992));
 * transport.sendToNeighbor(uuid2, message);
 * </pre>
 *
 * @author hal.hildebrand
 */
public class SocketTransport implements NetworkTransport {

    private static final Logger log = LoggerFactory.getLogger(SocketTransport.class);

    private final UUID localId;
    private final ProcessAddress myAddress;
    private final MessageFactory factory;
    private final Map<String, SocketClient> clients = new ConcurrentHashMap<>();
    private final List<Consumer<Message>> handlers = new CopyOnWriteArrayList<>();
    private final Map<UUID, ProcessAddress> memberRegistry = new ConcurrentHashMap<>();
    private final FirefliesMembershipView membership;
    private final FirefliesViewMonitor viewMonitor;
    private final RealTimeController controller;
    private SocketServer server;
    private volatile boolean connected = true;

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
        this.localId = localId;
        this.myAddress = myAddress;
        this.factory = MessageFactory.system();
        this.membership = Objects.requireNonNull(membership, "membership must not be null");
        this.viewMonitor = Objects.requireNonNull(viewMonitor, "viewMonitor must not be null");
        this.controller = Objects.requireNonNull(controller, "controller must not be null");
    }

    /**
     * Start server socket listening for inbound connections.
     * <p>
     * MANDATORY ENFORCEMENT: Only loopback addresses (127.0.0.1, localhost) are permitted
     * in Inc 6. Non-loopback addresses throw IllegalArgumentException.
     *
     * @param bindAddress Local address to bind
     * @throws IOException              if bind fails
     * @throws IllegalArgumentException if bindAddress is not a loopback address (Inc 6 constraint)
     */
    @Override
    public void listenOn(ProcessAddress bindAddress) throws IOException {
        if (!isLoopback(bindAddress.hostname())) {
            throw new IllegalArgumentException(
                "Inc 6 supports localhost only; use Inc 7+ for distributed hosts. Got: " + bindAddress.hostname()
            );
        }

        this.server = new SocketServer(bindAddress, this::handleIncomingMessage);
        this.server.start();
        log.info("SocketTransport listening on {}", bindAddress.toUrl());
    }

    /**
     * Establish a client connection to a remote process.
     * <p>
     * MANDATORY ENFORCEMENT: Only loopback addresses (127.0.0.1, localhost) are permitted
     * in Inc 6. Non-loopback addresses throw IllegalArgumentException.
     *
     * @param remoteAddress Target process address
     * @throws IOException              if connection fails
     * @throws IllegalArgumentException if remoteAddress is not a loopback address (Inc 6 constraint)
     */
    @Override
    public void connectTo(ProcessAddress remoteAddress) throws IOException {
        if (!isLoopback(remoteAddress.hostname())) {
            throw new IllegalArgumentException(
                "Inc 6 supports localhost only. Got: " + remoteAddress.hostname()
            );
        }

        var client = new SocketClient(remoteAddress, this::handleIncomingMessage);
        client.connect();
        clients.put(remoteAddress.processId(), client);
        log.info("SocketTransport connected to {}", remoteAddress.toUrl());
    }

    /**
     * Check if a hostname is a loopback address.
     * <p>
     * Used for Inc 6 scope enforcement. Accepts IPv4 (127.0.0.1),
     * IPv6 (::1), and DNS name (localhost).
     *
     * @param hostname Hostname to check
     * @return true if hostname is 127.0.0.1, ::1, or localhost
     */
    private boolean isLoopback(String hostname) {
        return hostname.equals("127.0.0.1") || hostname.equals("::1") || hostname.equals("localhost");
    }

    /**
     * Send a message to a specific neighbor.
     * <p>
     * Converts Message to TransportVonMessage and sends via SocketClient.
     *
     * @param neighborId UUID of the target neighbor
     * @param message    Message to send
     * @throws TransportException if send fails or neighbor not connected
     */
    @Override
    public void sendToNeighbor(UUID neighborId, Message message) throws TransportException {
        // Find client by looking up process ID from neighborId
        // Note: This is a simplified implementation for Phase 6A
        // Phase 6B will add proper UUID -> ProcessAddress mapping
        var client = findClientForNeighbor(neighborId);
        if (client == null) {
            throw new TransportException("Not connected to neighbor: " + neighborId);
        }

        try {
            // Convert Message to TransportVonMessage
            var transportMsg = convertToTransport(message);
            client.send(transportMsg);
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
        var sentViewId = membership.getCurrentViewId();

        // Send message immediately (traditional Transport behavior)
        try {
            sendToNeighbor(neighborId, message);
        } catch (TransportException e) {
            future.completeExceptionally(e);
            return future;
        }

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

    /**
     * Find SocketClient for a neighbor UUID.
     * <p>
     * Uses memberRegistry to map UUID to ProcessAddress, then looks up
     * the corresponding SocketClient by processId.
     *
     * @param neighborId UUID of the neighbor to find
     * @return SocketClient for the neighbor, or null if not found/connected
     */
    private SocketClient findClientForNeighbor(UUID neighborId) {
        // Look up ProcessAddress for this neighbor UUID
        var address = memberRegistry.get(neighborId);
        if (address == null) {
            log.warn("No ProcessAddress registered for neighbor {}", neighborId);
            return null;
        }

        // Find client by processId
        var client = clients.get(address.processId());
        if (client == null) {
            log.warn("No SocketClient found for processId {} (neighbor {})",
                address.processId(), neighborId);
        }
        return client;
    }

    @Override
    public List<ProcessAddress> getConnectedProcesses() {
        return new ArrayList<>(
            clients.values().stream().map(SocketClient::getRemoteAddress).toList()
        );
    }

    @Override
    public void closeAll() throws IOException {
        log.info("Closing SocketTransport");
        connected = false;

        if (server != null) {
            server.shutdown();
        }

        for (var client : clients.values()) {
            client.close();
        }
        clients.clear();
    }

    /**
     * Get this process's address.
     *
     * @return My ProcessAddress
     */
    public ProcessAddress getMyAddress() {
        return myAddress;
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
        var address = memberRegistry.get(memberId);
        if (address == null) {
            return Optional.empty();
        }
        return Optional.of(new MemberInfo(memberId, address.toUrl()));
    }

    @Override
    public MemberInfo routeToKey(TetreeKey<?> key) throws TransportException {
        var members = new ArrayList<>(memberRegistry.keySet());
        if (members.isEmpty()) {
            throw new TransportException("No members available for routing");
        }

        // Deterministic routing: hash key to member index
        var hash = key.getLowBits() ^ key.getHighBits();
        var absHash = hash == Long.MIN_VALUE ? 0 : Math.abs(hash);
        var index = (int) (absHash % members.size());

        var targetId = members.get(index);
        var address = memberRegistry.get(targetId);
        return new MemberInfo(targetId, address.toUrl());
    }

    @Override
    public UUID getLocalId() {
        return localId;
    }

    @Override
    public boolean isConnected() {
        return connected;
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
     *
     * @param memberId UUID of the member
     * @param address  ProcessAddress of the member
     */
    public void registerMember(UUID memberId, ProcessAddress address) {
        memberRegistry.put(memberId, address);
    }
}
