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

import java.util.Objects;

/**
 * Identifies a process's network address for inter-process communication.
 * <p>
 * In Inc 6 (multi-process on same host), only localhost addresses are supported.
 * In Inc 7+, remote hosts will be allowed.
 * <p>
 * Design:
 * <ul>
 *   <li>processId: Logical identifier for the process (e.g., "process-1")</li>
 *   <li>hostname: Network hostname (must be loopback in Inc 6)</li>
 *   <li>port: TCP port for socket binding (0 for dynamic allocation, 1-65535 for explicit)</li>
 * </ul>
 *
 * @param processId Logical process identifier
 * @param hostname  Network hostname
 * @param port      TCP listening port (0 = dynamic allocation, 1-65535 = explicit)
 * @author hal.hildebrand
 */
public record ProcessAddress(String processId, String hostname, int port) {
    public ProcessAddress {
        Objects.requireNonNull(processId, "processId cannot be null");
        Objects.requireNonNull(hostname, "hostname cannot be null");
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("Port must be 0-65535, got: " + port);
        }
    }

    /**
     * Create a localhost ProcessAddress (for Inc 6 multi-process).
     *
     * @param processId Process identifier
     * @param port      TCP listening port
     * @return ProcessAddress with 127.0.0.1 hostname
     */
    public static ProcessAddress localhost(String processId, int port) {
        return new ProcessAddress(processId, "127.0.0.1", port);
    }

    /**
     * Get a URL-like representation of this address.
     *
     * @return String like "socket://hostname:port"
     */
    public String toUrl() {
        return "socket://" + hostname + ":" + port;
    }
}
