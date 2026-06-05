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
import java.io.ObjectOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.function.Consumer;

/**
 * Client-side socket connection for inter-process communication.
 * <p>
 * Establishes a TCP connection to a remote SocketServer and provides <em>send-only</em>
 * message transport via Java Serialization. The transport is unidirectional: a SocketClient
 * only ever writes to the remote SocketServer; it never reads.
 * <p>
 * <b>Why send-only (Luciferase-ihy0s):</b> SocketServer opens only an {@link ObjectInputStream}
 * on each accepted connection and never an {@code ObjectOutputStream}. A receive-side
 * {@code new ObjectInputStream(socket.getInputStream())} on the client therefore blocks
 * <em>forever</em> in its constructor, which synchronously reads the Java serialization stream
 * header ({@code STREAM_MAGIC}/{@code STREAM_VERSION}) that the server never emits. The earlier
 * receive loop deadlocked silently, leaking a thread and a socket. Inbound messages from a peer
 * arrive instead via that peer's own SocketClient connecting to this node's SocketServer.
 * <p>
 * Thread Model:
 * <ul>
 *   <li>Send is synchronous (caller thread blocks during write)</li>
 *   <li>No receive thread — the client never reads from the socket</li>
 * </ul>
 * <p>
 * Usage:
 * <pre>
 * var client = new SocketClient(remoteAddress, msg -> {});  // handler is a no-op placeholder
 * client.connect();
 * client.send(message);
 * // ... later ...
 * client.close();
 * </pre>
 *
 * @author hal.hildebrand
 */
public class SocketClient {

    private static final Logger log = LoggerFactory.getLogger(SocketClient.class);

    /**
     * Default TCP connect timeout in milliseconds. A value of 5 000 ms bounds a hung-connect to a
     * dead or firewalled host; override via the {@link #SocketClient(ProcessAddress, Consumer, int)}
     * constructor for tighter or looser requirements.
     */
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 5_000;

    private final int connectTimeoutMs;

    private final ProcessAddress remoteAddress;
    /**
     * Retained for source/binary compatibility with {@code SocketConnectionManager}, which
     * constructs every client with an inbound handler. The VoN socket transport is unidirectional
     * (send-only on the client side — see class docs / Luciferase-ihy0s), so this handler is a
     * no-op placeholder: the client never reads from the socket and therefore never invokes it.
     */
    @SuppressWarnings("unused")
    private final Consumer<TransportVonMessage> messageHandler;
    private Socket socket;
    private ObjectOutputStream outStream;
    private volatile boolean connected = false;

    /**
     * Create a SocketClient with the default connect timeout ({@value #DEFAULT_CONNECT_TIMEOUT_MS} ms).
     *
     * @param remoteAddress  Target process address
     * @param messageHandler Inbound-message callback; <b>never invoked</b> — the client is
     *                       send-only (Luciferase-ihy0s). Retained as a no-op placeholder so the
     *                       unidirectional contract is explicit at the call site.
     */
    public SocketClient(ProcessAddress remoteAddress, Consumer<TransportVonMessage> messageHandler) {
        this(remoteAddress, messageHandler, DEFAULT_CONNECT_TIMEOUT_MS);
    }

    /**
     * Create a SocketClient with a configurable connect timeout.
     *
     * @param remoteAddress    Target process address
     * @param messageHandler   Inbound-message callback; <b>never invoked</b> (send-only, Luciferase-ihy0s).
     * @param connectTimeoutMs TCP connect timeout in milliseconds; 0 means OS default (not recommended).
     */
    public SocketClient(ProcessAddress remoteAddress, Consumer<TransportVonMessage> messageHandler,
                        int connectTimeoutMs) {
        this.remoteAddress = remoteAddress;
        this.messageHandler = messageHandler;
        this.connectTimeoutMs = connectTimeoutMs;
    }

    /**
     * Establish connection to remote server.
     * <p>
     * Opens the socket and the outbound {@link ObjectOutputStream}. No receive thread is started:
     * the client is send-only (see class docs / Luciferase-ihy0s).
     *
     * @throws IOException if connection fails
     */
    public void connect() throws IOException {
        var sock = new Socket();
        try {
            sock.connect(new InetSocketAddress(remoteAddress.hostname(), remoteAddress.port()), connectTimeoutMs);
            this.outStream = new ObjectOutputStream(sock.getOutputStream());
            this.outStream.flush();
            this.socket = sock;
            this.connected = true;
        } catch (IOException e) {
            try {
                sock.close();
            } catch (IOException ignored) {
            }
            throw e;
        }

        log.info("Connected to {}", remoteAddress.toUrl());
    }

    /**
     * Send a message to the remote server.
     * <p>
     * Synchronous: blocks until message is written and flushed to socket.
     * Thread-safe via synchronization.
     *
     * @param message Message to send
     * @throws IOException if send fails or client is disconnected
     */
    public synchronized void send(TransportVonMessage message) throws IOException {
        if (!connected) {
            throw new IOException("Client not connected to " + remoteAddress.toUrl());
        }

        log.debug("Sending message type={} to {}", message.type(), remoteAddress.toUrl());
        outStream.writeObject(message);
        outStream.flush();
        // Luciferase-zwyf2: clear the ObjectOutputStream handle table after each message. Without
        // reset() the table retains a strong back-reference to every object ever written on this
        // long-lived connection, growing unbounded and eventually causing OOM. TransportVonMessage
        // records are value objects, so losing cross-message reference dedup is harmless.
        outStream.reset();
    }

    /**
     * Get the remote process address.
     *
     * @return ProcessAddress of remote server
     */
    public ProcessAddress getRemoteAddress() {
        return remoteAddress;
    }

    /**
     * Check if client is connected.
     *
     * @return true if connected and operational
     */
    public boolean isConnected() {
        return connected;
    }

    /**
     * Close the connection and release resources.
     *
     * @throws IOException if socket close fails
     */
    public synchronized void close() throws IOException {
        log.info("Closing connection to {}", remoteAddress.toUrl());
        connected = false;

        // Close outStream first (flushes buffered bytes and cascades to socket).
        // Then close the socket explicitly as a safety net in case outStream is null.
        // Use try/finally so a failure on the first close does not skip the second.
        IOException firstEx = null;
        if (outStream != null) {
            try {
                outStream.close();
            } catch (IOException e) {
                firstEx = e;
            }
        }
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException e) {
                if (firstEx != null) {
                    firstEx.addSuppressed(e);
                } else {
                    firstEx = e;
                }
            }
        }
        if (firstEx != null) {
            throw firstEx;
        }
    }
}
