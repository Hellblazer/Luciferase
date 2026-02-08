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
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.util.function.Consumer;

/**
 * Client-side socket connection for inter-process communication.
 * <p>
 * Establishes a TCP connection to a remote SocketServer and provides bidirectional
 * message transport via Java Serialization. Received messages are dispatched to
 * a message handler on a background thread.
 * <p>
 * Thread Model:
 * <ul>
 *   <li>Send is synchronous (caller thread blocks during write)</li>
 *   <li>Receive loop runs in dedicated daemon thread</li>
 *   <li>Message handler invoked from receive thread</li>
 * </ul>
 * <p>
 * Usage:
 * <pre>
 * var client = new SocketClient(remoteAddress, msg -> handleIncoming(msg));
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

    private final ProcessAddress remoteAddress;
    private final Consumer<TransportVonMessage> messageHandler;
    private Socket socket;
    private ObjectOutputStream outStream;
    private volatile boolean connected = false;

    /**
     * Create a SocketClient.
     *
     * @param remoteAddress  Target process address
     * @param messageHandler Callback for received messages
     */
    public SocketClient(ProcessAddress remoteAddress, Consumer<TransportVonMessage> messageHandler) {
        this.remoteAddress = remoteAddress;
        this.messageHandler = messageHandler;
    }

    /**
     * Establish connection to remote server.
     * <p>
     * Blocks until connection is established, then starts background receive thread.
     *
     * @throws IOException if connection fails
     */
    public void connect() throws IOException {
        this.socket = new Socket(remoteAddress.hostname(), remoteAddress.port());
        this.outStream = new ObjectOutputStream(socket.getOutputStream());
        this.outStream.flush();
        this.connected = true;

        log.info("Connected to {}", remoteAddress.toUrl());

        // Start receive loop in background
        var threadName = String.format("socket-client-recv-%s:%d", remoteAddress.hostname(), remoteAddress.port());
        var receiveThread = new Thread(this::receiveMessages, threadName);
        receiveThread.setDaemon(true);
        receiveThread.start();
    }

    /**
     * Receive messages from remote server.
     * <p>
     * Runs in background thread, reading TransportVonMessage objects until
     * EOF or error. Invokes messageHandler for each received message.
     */
    private void receiveMessages() {
        try (var inStream = new ObjectInputStream(socket.getInputStream())) {
            while (connected) {
                var message = (TransportVonMessage) inStream.readObject();
                log.debug("Received message type={} from {}", message.type(), remoteAddress.toUrl());
                messageHandler.accept(message);
            }
        } catch (EOFException e) {
            // Normal disconnect
            log.info("Server closed connection: {}", remoteAddress.toUrl());
        } catch (SocketException e) {
            if (connected) {
                log.error("Socket error reading from {}", remoteAddress.toUrl(), e);
            }
            // Normal during close
        } catch (IOException e) {
            if (connected) {
                log.error("IO error reading from {}", remoteAddress.toUrl(), e);
            }
        } catch (ClassNotFoundException e) {
            log.error("Unknown message class from {}", remoteAddress.toUrl(), e);
        } finally {
            connected = false;
        }
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
    public void close() throws IOException {
        log.info("Closing connection to {}", remoteAddress.toUrl());
        connected = false;

        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }
}
