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

import com.hellblazer.luciferase.simulation.von.TransportVonMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Thread-safe implementation of ConnectionManager using SocketClient and SocketServer.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Manage client connections (SocketClient instances)</li>
 *   <li>Manage server socket (SocketServer instance)</li>
 *   <li>Handle message sending without leaking SocketClient</li>
 *   <li>Track lifecycle state (isRunning)</li>
 * </ul>
 * <p>
 * <strong>BUG FIX:</strong> isRunning() is false on construction, becomes true after
 * first listenOn() or connectTo(). This fixes the bug in SocketTransport line 84 where
 * connected = true on construction.
 *
 * @author hal.hildebrand
 */
public class SocketConnectionManager implements ConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(SocketConnectionManager.class);

    private final ProcessAddress myAddress;
    private final Consumer<TransportVonMessage> incomingMessageHandler;
    private final Map<String, SocketClient> clients = new ConcurrentHashMap<>();
    private volatile SocketServer server;
    private volatile boolean running = false;  // BUG FIX: start as false (not true like SocketTransport line 84)
    private volatile ProcessAddress currentBindAddress;

    /**
     * Create a SocketConnectionManager.
     *
     * @param myAddress               This process's address
     * @param incomingMessageHandler Callback for handling incoming messages
     */
    public SocketConnectionManager(ProcessAddress myAddress,
                                  Consumer<TransportVonMessage> incomingMessageHandler) {
        this.myAddress = Objects.requireNonNull(myAddress, "myAddress must not be null");
        this.incomingMessageHandler = Objects.requireNonNull(incomingMessageHandler,
                                                             "incomingMessageHandler must not be null");
    }

    @Override
    public void connectTo(ProcessAddress remote) throws IOException {
        if (!isLoopback(remote.hostname())) {
            throw new IllegalArgumentException(
                "Inc 6 supports localhost only. Got: " + remote.hostname()
            );
        }

        // Idempotent: if already connected to this process, reuse existing client
        if (clients.containsKey(remote.processId())) {
            log.debug("Already connected to {}, reusing connection", remote.processId());
            return;
        }

        var client = new SocketClient(remote, incomingMessageHandler);
        client.connect();
        clients.put(remote.processId(), client);
        running = true;  // BUG FIX: set true after first connection
        log.info("Connected to {}", remote.toUrl());
    }

    @Override
    public void listenOn(ProcessAddress bind) throws IOException {
        if (!isLoopback(bind.hostname())) {
            throw new IllegalArgumentException(
                "Inc 6 supports localhost only; use Inc 7+ for distributed hosts. Got: " + bind.hostname()
            );
        }

        // If already listening, check if same address (idempotent) or different (error)
        if (server != null) {
            if (currentBindAddress != null && currentBindAddress.equals(bind)) {
                log.debug("Already listening on {}, idempotent", bind.toUrl());
                return;
            } else {
                throw new IllegalStateException(
                    "Already listening on " + (currentBindAddress != null ? currentBindAddress.toUrl() : "unknown") +
                    ", cannot listen on different address " + bind.toUrl()
                );
            }
        }

        this.server = new SocketServer(bind, incomingMessageHandler);
        this.currentBindAddress = bind;
        this.server.start();
        running = true;  // BUG FIX: set true after server starts
        log.info("Listening on {}", bind.toUrl());
    }

    @Override
    public void sendToProcess(String processId, TransportVonMessage message) throws IOException {
        var client = clients.get(processId);
        if (client == null) {
            throw new IOException("Not connected to process: " + processId);
        }

        client.send(message);
    }

    @Override
    public List<ProcessAddress> getConnectedProcesses() {
        // Return defensive copy (thread-safe snapshot)
        return new ArrayList<>(
            clients.values().stream().map(SocketClient::getRemoteAddress).toList()
        );
    }

    @Override
    public void closeAll() throws IOException {
        log.info("Closing SocketConnectionManager");
        running = false;

        if (server != null) {
            server.shutdown();
            server = null;
        }

        for (var client : clients.values()) {
            client.close();
        }
        clients.clear();
        currentBindAddress = null;
    }

    @Override
    public boolean isRunning() {
        return running;
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
}
