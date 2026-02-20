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

import java.io.IOException;
import java.util.List;

/**
 * Manages socket lifecycle and message sending.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Connection management (client and server sockets)</li>
 *   <li>Message sending without leaking internal socket types</li>
 *   <li>Lifecycle state tracking (isRunning)</li>
 * </ul>
 * <p>
 * <strong>Design Principle:</strong> This interface does NOT expose SocketClient or other
 * internal socket types. All message sending is handled internally via sendToProcess().
 * <p>
 * Thread-Safety: All implementations must support concurrent access from multiple threads.
 *
 * @author hal.hildebrand
 */
public interface ConnectionManager extends AutoCloseable {

    /**
     * Connect to a remote process. Must be localhost.
     * <p>
     * Can be called multiple times with same address (idempotent - reuses existing connection).
     * Can be called multiple times with different addresses (creates additional connections).
     *
     * @param remote Target process address (must be loopback)
     * @throws IOException              if connection fails
     * @throws IllegalArgumentException if remote is not a loopback address
     */
    void connectTo(ProcessAddress remote) throws IOException;

    /**
     * Start listening on bind address. Must be localhost.
     * <p>
     * Idempotent if called with same address. Throws IllegalStateException if called
     * with a different address after server already started.
     *
     * @param bind Local address to bind (must be loopback)
     * @throws IOException              if bind fails
     * @throws IllegalArgumentException if bind is not a loopback address
     * @throws IllegalStateException    if already listening on a different address
     */
    void listenOn(ProcessAddress bind) throws IOException;

    /**
     * Send message to a specific process.
     * <p>
     * <strong>Design Principle:</strong> This method handles sending internally without
     * exposing SocketClient to callers. Prevents interface leaks.
     *
     * @param processId Process ID to send to
     * @param message   Message to send
     * @throws IOException if process not connected or send fails
     */
    void sendToProcess(String processId, TransportVonMessage message) throws IOException;

    /**
     * Get all currently connected process addresses.
     * <p>
     * Returns a thread-safe snapshot. Changes to connections do not affect the returned list.
     *
     * @return List of connected process addresses
     */
    List<ProcessAddress> getConnectedProcesses();

    /**
     * Close all connections and stop server.
     * <p>
     * After calling this, isRunning() will return false.
     *
     * @throws IOException if close fails
     */
    void closeAll() throws IOException;

    /**
     * Returns true after first listenOn() or connectTo() call succeeds.
     * <p>
     * <strong>BUG FIX:</strong> Current SocketTransport.connected is true on construction
     * (line 84). This is incorrect - connection manager should not be "running" until
     * first connection or server start.
     * <p>
     * Lifecycle:
     * <ul>
     *   <li>Initial state: false (not running)</li>
     *   <li>After listenOn(): true (server started)</li>
     *   <li>After connectTo(): true (client connected)</li>
     *   <li>After closeAll(): false (shut down)</li>
     * </ul>
     *
     * @return true if at least one connection active or server running
     */
    boolean isRunning();

    /**
     * Get the actual bound address after listenOn(). When listenOn() was called
     * with port 0, this returns the ProcessAddress with the OS-assigned port.
     *
     * @return Actual bound ProcessAddress, or null if not yet listening
     */
    ProcessAddress getBoundAddress();

    /**
     * Close all connections and stop server.
     * <p>
     * Delegates to closeAll() for consistent behavior.
     */
    @Override
    default void close() {
        try {
            closeAll();
        } catch (IOException e) {
            throw new RuntimeException("Error closing ConnectionManager", e);
        }
    }
}
