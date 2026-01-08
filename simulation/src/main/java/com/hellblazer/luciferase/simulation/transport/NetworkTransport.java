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

import com.hellblazer.luciferase.simulation.von.VonTransport;

import java.io.IOException;
import java.util.List;

/**
 * Extension of VonTransport for network-based inter-process communication.
 * <p>
 * In Inc 6 (multi-process), this enables socket-based communication between
 * processes on the same host. In Inc 7+, it will support cross-host communication.
 * <p>
 * Key Operations:
 * <ul>
 *   <li>{@link #connectTo} - Establish client connection to remote process</li>
 *   <li>{@link #listenOn} - Start server socket for inbound connections</li>
 *   <li>{@link #getConnectedProcesses} - List all connected remote processes</li>
 *   <li>{@link #closeAll} - Shut down all connections and server</li>
 * </ul>
 * <p>
 * Scope Enforcement (Inc 6):
 * Both connectTo() and listenOn() must throw IllegalArgumentException for
 * non-loopback addresses to enforce localhost-only operation.
 *
 * @author hal.hildebrand
 */
public interface NetworkTransport extends VonTransport {

    /**
     * Establish a client connection to a remote process.
     * <p>
     * In Inc 6, this method enforces localhost-only connections by checking
     * the remoteAddress hostname. Only loopback addresses (127.0.0.1, localhost)
     * are permitted.
     *
     * @param remoteAddress Target process address
     * @throws IOException              if connection fails
     * @throws IllegalArgumentException if remoteAddress is not a loopback address (Inc 6 constraint)
     */
    void connectTo(ProcessAddress remoteAddress) throws IOException;

    /**
     * Start server socket listening for inbound connections.
     * <p>
     * In Inc 6, this method enforces localhost-only binding by checking
     * the bindAddress hostname. Only loopback addresses (127.0.0.1, localhost)
     * are permitted.
     *
     * @param bindAddress Local address to bind
     * @throws IOException              if bind fails
     * @throws IllegalArgumentException if bindAddress is not a loopback address (Inc 6 constraint)
     */
    void listenOn(ProcessAddress bindAddress) throws IOException;

    /**
     * Get list of all connected remote processes.
     *
     * @return List of ProcessAddress for connected processes
     */
    List<ProcessAddress> getConnectedProcesses();

    /**
     * Close all client connections and stop the server socket.
     * <p>
     * This method should gracefully shut down all network resources
     * and release executors.
     *
     * @throws IOException if shutdown fails
     */
    void closeAll() throws IOException;
}
