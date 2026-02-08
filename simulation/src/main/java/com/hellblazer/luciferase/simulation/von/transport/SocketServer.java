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

import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Server-side socket listener for inter-process communication.
 * <p>
 * Accepts inbound TCP connections and deserializes TransportVonMessage objects
 * from connected clients. Each client connection is handled in a separate thread
 * from the executor.
 * <p>
 * Thread Model:
 * <ul>
 *   <li>Accept loop runs in background executor thread</li>
 *   <li>Each client handled in separate executor thread</li>
 *   <li>Message handler invoked from client thread</li>
 * </ul>
 * <p>
 * Usage:
 * <pre>
 * var server = new SocketServer(bindAddress, msg -> handleIncoming(msg));
 * server.start();
 * // ... later ...
 * server.shutdown();
 * </pre>
 *
 * @author hal.hildebrand
 */
public class SocketServer {

    private static final Logger log = LoggerFactory.getLogger(SocketServer.class);

    private final ProcessAddress bindAddress;
    private final Consumer<TransportVonMessage> messageHandler;
    private final ExecutorService executor;
    private final Set<Socket> clientSockets = Collections.synchronizedSet(new java.util.HashSet<>());
    private ServerSocket serverSocket;
    private volatile boolean running = false;

    /**
     * Create a SocketServer.
     *
     * @param bindAddress    Address to bind (hostname and port)
     * @param messageHandler Callback for received messages
     */
    public SocketServer(ProcessAddress bindAddress, Consumer<TransportVonMessage> messageHandler) {
        this.bindAddress = bindAddress;
        this.messageHandler = messageHandler;
        this.executor = Executors.newCachedThreadPool(r -> {
            var t = new Thread(r, "socket-server-" + bindAddress.processId());
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Start the server socket and begin accepting connections.
     * <p>
     * Non-blocking: returns immediately after starting accept loop.
     *
     * @throws IOException if bind fails
     */
    public void start() throws IOException {
        this.serverSocket = new ServerSocket(
            bindAddress.port(),
            50, // backlog
            InetAddress.getByName(bindAddress.hostname())
        );
        this.running = true;

        log.info("SocketServer started on {}", bindAddress.toUrl());

        // Accept connections in background
        executor.execute(() -> {
            while (running) {
                try {
                    var clientSocket = serverSocket.accept();
                    clientSockets.add(clientSocket);
                    log.info("Accepted connection from {}", clientSocket.getRemoteSocketAddress());
                    executor.execute(() -> handleClient(clientSocket));
                } catch (SocketException e) {
                    if (running) {
                        log.error("Socket error in accept loop", e);
                    }
                    // Normal during shutdown
                } catch (IOException e) {
                    if (running) {
                        log.error("IO error accepting connection", e);
                    }
                }
            }
        });
    }

    /**
     * Handle a connected client socket.
     * <p>
     * Reads TransportVonMessage objects until EOF or error, invoking
     * messageHandler for each message.
     *
     * @param clientSocket Connected client socket
     */
    private void handleClient(Socket clientSocket) {
        try (var stream = new ObjectInputStream(clientSocket.getInputStream())) {
            while (running) {
                var message = (TransportVonMessage) stream.readObject();
                log.debug("Received message type={} from {}", message.type(), clientSocket.getRemoteSocketAddress());
                messageHandler.accept(message);
            }
        } catch (EOFException | SocketException e) {
            // Normal client disconnect
            log.info("Client disconnected: {}", clientSocket.getRemoteSocketAddress());
        } catch (IOException e) {
            if (running) {
                log.error("IO error reading from client {}", clientSocket.getRemoteSocketAddress(), e);
            }
        } catch (ClassNotFoundException e) {
            log.error("Unknown message class from client {}", clientSocket.getRemoteSocketAddress(), e);
        } finally {
            clientSockets.remove(clientSocket);
            try {
                clientSocket.close();
            } catch (IOException e) {
                log.debug("Error closing client socket", e);
            }
        }
    }

    /**
     * Shut down the server and close all connections.
     * <p>
     * Stops accepting new connections, closes server socket, and waits
     * for executor to terminate.
     *
     * @throws IOException if socket close fails
     */
    public void shutdown() throws IOException {
        log.info("Shutting down SocketServer on {}", bindAddress.toUrl());
        running = false;

        if (serverSocket != null && !serverSocket.isClosed()) {
            serverSocket.close();
        }

        // Close all connected client sockets to unblock readObject() calls
        // Synchronized because clientSockets is Collections.synchronizedSet(HashSet)
        // which requires external synchronization for iteration
        synchronized (clientSockets) {
            for (var clientSocket : clientSockets) {
                try {
                    clientSocket.close();
                } catch (IOException e) {
                    log.debug("Error closing client socket during shutdown", e);
                }
            }
            clientSockets.clear();
        }

        executor.shutdown();
        try {
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                log.warn("Executor did not terminate within 3 seconds, forcing shutdown");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    /**
     * Get the bound address.
     *
     * @return ProcessAddress this server is bound to
     */
    public ProcessAddress getBindAddress() {
        return bindAddress;
    }

    /**
     * Check if server is running.
     *
     * @return true if accepting connections
     */
    public boolean isRunning() {
        return running;
    }
}
