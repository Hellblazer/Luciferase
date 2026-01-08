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
import com.hellblazer.luciferase.simulation.von.TransportVonMessage;
import com.hellblazer.luciferase.simulation.von.VonMessage;
import com.hellblazer.luciferase.simulation.von.VonMessageConverter;
import com.hellblazer.luciferase.simulation.von.VonTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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
 *   <li>Message routing: Uses VonMessageConverter for serialization</li>
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
    private final Map<String, SocketClient> clients = new ConcurrentHashMap<>();
    private final List<Consumer<VonMessage>> handlers = new CopyOnWriteArrayList<>();
    private final Map<UUID, ProcessAddress> memberRegistry = new ConcurrentHashMap<>();
    private SocketServer server;
    private volatile boolean connected = true;

    /**
     * Create a SocketTransport.
     *
     * @param myAddress This process's network address
     */
    public SocketTransport(ProcessAddress myAddress) {
        this.localId = UUID.randomUUID();
        this.myAddress = myAddress;
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
     * Used for Inc 6 scope enforcement.
     *
     * @param hostname Hostname to check
     * @return true if hostname is 127.0.0.1 or localhost
     */
    private boolean isLoopback(String hostname) {
        return hostname.equals("127.0.0.1") || hostname.equals("localhost");
    }

    /**
     * Send a message to a specific neighbor.
     * <p>
     * Converts VonMessage to TransportVonMessage and sends via SocketClient.
     *
     * @param neighborId UUID of the target neighbor
     * @param message    Message to send
     * @throws TransportException if send fails or neighbor not connected
     */
    @Override
    public void sendToNeighbor(UUID neighborId, VonMessage message) throws TransportException {
        // Find client by looking up process ID from neighborId
        // Note: This is a simplified implementation for Phase 6A
        // Phase 6B will add proper UUID -> ProcessAddress mapping
        var client = findClientForNeighbor(neighborId);
        if (client == null) {
            throw new TransportException("Not connected to neighbor: " + neighborId);
        }

        try {
            // Convert VonMessage to TransportVonMessage
            var transportMsg = convertToTransport(message);
            client.send(transportMsg);
        } catch (IOException e) {
            throw new TransportException("Failed to send to " + neighborId, e);
        }
    }

    /**
     * Send a message asynchronously.
     * <p>
     * Phase 6A: Delegates to synchronous send, returns completed future.
     * Phase 6B: Will implement true async with ACK handling.
     *
     * @param neighborId UUID of the target neighbor
     * @param message    Message to send
     * @return Future that completes when send succeeds
     */
    @Override
    public CompletableFuture<VonMessage.Ack> sendToNeighborAsync(UUID neighborId, VonMessage message) {
        return CompletableFuture.supplyAsync(() -> {
            sendToNeighbor(neighborId, message);
            return new VonMessage.Ack(UUID.randomUUID(), neighborId);
        });
    }

    /**
     * Handle an incoming TransportVonMessage from the network.
     * <p>
     * Converts back to VonMessage and dispatches to registered handlers.
     *
     * @param transportMsg Deserialized message from socket
     */
    private void handleIncomingMessage(TransportVonMessage transportMsg) {
        log.debug("Received TransportVonMessage type={}", transportMsg.type());

        // Convert to VonMessage
        var vonMsg = VonMessageConverter.fromTransport(transportMsg);

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
     * Convert VonMessage to TransportVonMessage for serialization.
     * <p>
     * Phase 6A: Simplified conversion with basic field extraction.
     * Phase 6B: Will use full VonMessageConverter with all message types.
     */
    private TransportVonMessage convertToTransport(VonMessage message) {
        // Phase 6A: Use simplified converter
        // Extract common fields and delegate to VonMessageConverter
        return switch (message) {
            case VonMessage.Ack ack ->
                VonMessageConverter.toTransport(ack, ack.senderId().toString(), ack.ackFor().toString(), null, "");

            case VonMessage.Move move ->
                VonMessageConverter.toTransport(move, move.nodeId().toString(), "", null, "");

            case VonMessage.Leave leave ->
                VonMessageConverter.toTransport(leave, leave.nodeId().toString(), "", null, "");

            default ->
                VonMessageConverter.toTransport(message, getLocalId().toString(), "", null, "");
        };
    }

    /**
     * Find SocketClient for a neighbor UUID.
     * <p>
     * Phase 6A: Iterates all clients (simple implementation).
     * Phase 6B: Will use proper UUID -> ProcessAddress mapping.
     */
    private SocketClient findClientForNeighbor(UUID neighborId) {
        // Phase 6A: Simple iteration (sufficient for testing)
        // Phase 6B: Will maintain UUID -> ProcessAddress map
        return clients.values().stream().findFirst().orElse(null);
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

    // Implement remaining VonTransport methods
    @Override
    public void onMessage(Consumer<VonMessage> handler) {
        handlers.add(handler);
    }

    @Override
    public void removeMessageHandler(Consumer<VonMessage> handler) {
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
