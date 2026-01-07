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

package com.hellblazer.luciferase.simulation.integration;

import java.io.IOException;
import java.net.ServerSocket;

/**
 * Thread-safe dynamic port allocator for integration tests.
 * <p>
 * Uses ServerSocket(0) to find available ports, avoiding hardcoded
 * port conflicts in test environments.
 * <p>
 * Usage:
 * <pre>
 * int port = DynamicPortAllocator.allocatePort();
 * String endpoint = "localhost:" + port;
 * </pre>
 *
 * @author hal.hildebrand
 */
public class DynamicPortAllocator {

    /**
     * Allocate a random available port.
     * <p>
     * This method is thread-safe and avoids port conflicts by using
     * the operating system's ephemeral port allocation.
     *
     * @return an available port number
     * @throws RuntimeException if no port is available
     */
    public static synchronized int allocatePort() {
        try (var socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("Failed to allocate dynamic port", e);
        }
    }
}
