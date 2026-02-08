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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test SocketConnectionManager implementation.
 * <p>
 * Validates:
 * <ul>
 *   <li>Localhost enforcement (127.0.0.1, ::1, localhost accepted; others rejected)</li>
 *   <li>Connection lifecycle (listenOn, connectTo)</li>
 *   <li>Message sending without SocketClient leak</li>
 *   <li>isRunning() bug fix (false on construction, true after first connection)</li>
 *   <li>Edge cases (double listenOn, concurrent operations)</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
class SocketConnectionManagerTest {

    private final List<ConnectionManager> managers = new ArrayList<>();

    @AfterEach
    void cleanup() throws IOException {
        for (var mgr : managers) {
            mgr.closeAll();
        }
        managers.clear();
    }

    /**
     * Test listenOn() accepts loopback addresses.
     */
    @Test
    void testListenOnLoopback() throws IOException {
        var port1 = findAvailablePort();
        var mgr1 = new SocketConnectionManager(
            ProcessAddress.localhost("p1", port1),
            msg -> {}
        );
        managers.add(mgr1);

        // IPv4 loopback
        assertDoesNotThrow(() -> mgr1.listenOn(ProcessAddress.localhost("p1", port1)),
                          "Should accept 127.0.0.1");

        var port2 = findAvailablePort();
        var mgr2 = new SocketConnectionManager(
            new ProcessAddress("p2", "localhost", port2),
            msg -> {}
        );
        managers.add(mgr2);

        // DNS name "localhost"
        assertDoesNotThrow(() -> mgr2.listenOn(new ProcessAddress("p2", "localhost", port2)),
                          "Should accept 'localhost'");
    }

    /**
     * Test listenOn() rejects non-loopback addresses.
     */
    @Test
    void testListenOnRejectsNonLoopback() {
        var mgr = new SocketConnectionManager(
            new ProcessAddress("p1", "0.0.0.0", 9999),
            msg -> {}
        );
        managers.add(mgr);

        var ex = assertThrows(IllegalArgumentException.class,
                             () -> mgr.listenOn(new ProcessAddress("p1", "0.0.0.0", 9999)),
                             "Should reject 0.0.0.0");

        assertTrue(ex.getMessage().contains("localhost") || ex.getMessage().contains("loopback"),
                  "Error should mention localhost requirement: " + ex.getMessage());
    }

    /**
     * Test connectTo() accepts loopback addresses.
     */
    @Test
    void testConnectToLoopback() throws IOException {
        // Start a server to connect to
        var serverPort = findAvailablePort();
        var server = new SocketConnectionManager(
            ProcessAddress.localhost("server", serverPort),
            msg -> {}
        );
        managers.add(server);
        server.listenOn(ProcessAddress.localhost("server", serverPort));

        // Create client and connect
        var clientPort = findAvailablePort();
        var client = new SocketConnectionManager(
            ProcessAddress.localhost("client", clientPort),
            msg -> {}
        );
        managers.add(client);

        assertDoesNotThrow(() -> client.connectTo(ProcessAddress.localhost("server", serverPort)),
                          "Should accept 127.0.0.1 for connectTo");
    }

    /**
     * Test connectTo() rejects non-loopback addresses.
     */
    @Test
    void testConnectToRejectsNonLoopback() {
        var mgr = new SocketConnectionManager(
            ProcessAddress.localhost("p1", findAvailablePort()),
            msg -> {}
        );
        managers.add(mgr);

        var ex = assertThrows(IllegalArgumentException.class,
                             () -> mgr.connectTo(new ProcessAddress("remote", "192.168.1.1", 9999)),
                             "Should reject non-loopback address");

        assertTrue(ex.getMessage().contains("localhost") || ex.getMessage().contains("loopback"),
                  "Error should mention localhost requirement: " + ex.getMessage());
    }

    /**
     * Test getConnectedProcesses() returns accurate list.
     */
    @Test
    void testGetConnectedProcesses() throws IOException {
        // Start server
        var serverPort = findAvailablePort();
        var server = new SocketConnectionManager(
            ProcessAddress.localhost("server", serverPort),
            msg -> {}
        );
        managers.add(server);
        server.listenOn(ProcessAddress.localhost("server", serverPort));

        // Create client
        var clientPort = findAvailablePort();
        var client = new SocketConnectionManager(
            ProcessAddress.localhost("client", clientPort),
            msg -> {}
        );
        managers.add(client);

        // Before connection
        assertEquals(0, client.getConnectedProcesses().size(),
                    "Should have no connections initially");

        // After connection
        client.connectTo(ProcessAddress.localhost("server", serverPort));

        // Give connection time to establish
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        var connected = client.getConnectedProcesses();
        assertEquals(1, connected.size(), "Should have 1 connection");
        assertEquals("server", connected.get(0).processId(), "Should be connected to server");
    }

    /**
     * Test closeAll() closes all connections.
     */
    @Test
    void testCloseAll() throws IOException {
        // Start server
        var serverPort = findAvailablePort();
        var server = new SocketConnectionManager(
            ProcessAddress.localhost("server", serverPort),
            msg -> {}
        );
        managers.add(server);
        server.listenOn(ProcessAddress.localhost("server", serverPort));

        // Create and connect client
        var clientPort = findAvailablePort();
        var client = new SocketConnectionManager(
            ProcessAddress.localhost("client", clientPort),
            msg -> {}
        );
        managers.add(client);
        client.connectTo(ProcessAddress.localhost("server", serverPort));

        // Close all
        client.closeAll();

        // Verify disconnected
        assertEquals(0, client.getConnectedProcesses().size(),
                    "Should have no connections after closeAll");
        assertFalse(client.isRunning(), "Should not be running after closeAll");
    }

    /**
     * Test isRunning() returns false on construction (BUG FIX).
     * <p>
     * Current SocketTransport.connected = true on line 84 is a bug.
     */
    @Test
    void testIsRunningFalseOnConstruction() {
        var mgr = new SocketConnectionManager(
            ProcessAddress.localhost("p1", findAvailablePort()),
            msg -> {}
        );
        managers.add(mgr);

        assertFalse(mgr.isRunning(),
                   "isRunning() should be FALSE on construction (BUG FIX for SocketTransport line 84)");
    }

    /**
     * Test isRunning() becomes true after listenOn().
     */
    @Test
    void testIsRunningTrueAfterListenOn() throws IOException {
        var port = findAvailablePort();
        var mgr = new SocketConnectionManager(
            ProcessAddress.localhost("p1", port),
            msg -> {}
        );
        managers.add(mgr);

        // Before listenOn
        assertFalse(mgr.isRunning(), "Should be false before listenOn");

        // After listenOn
        mgr.listenOn(ProcessAddress.localhost("p1", port));
        assertTrue(mgr.isRunning(), "Should be true after listenOn");
    }

    /**
     * Test isRunning() becomes true after connectTo().
     */
    @Test
    void testIsRunningTrueAfterConnectTo() throws IOException {
        // Start server
        var serverPort = findAvailablePort();
        var server = new SocketConnectionManager(
            ProcessAddress.localhost("server", serverPort),
            msg -> {}
        );
        managers.add(server);
        server.listenOn(ProcessAddress.localhost("server", serverPort));

        // Create client
        var clientPort = findAvailablePort();
        var client = new SocketConnectionManager(
            ProcessAddress.localhost("client", clientPort),
            msg -> {}
        );
        managers.add(client);

        // Before connectTo
        assertFalse(client.isRunning(), "Should be false before connectTo");

        // After connectTo
        client.connectTo(ProcessAddress.localhost("server", serverPort));
        assertTrue(client.isRunning(), "Should be true after connectTo");
    }

    /**
     * Test sendToProcess() sends message without exposing SocketClient.
     */
    @Test
    void testSendToProcess() throws Exception {
        var received = new AtomicReference<TransportVonMessage>();
        var latch = new CountDownLatch(1);

        // Start server
        var serverPort = findAvailablePort();
        var server = new SocketConnectionManager(
            ProcessAddress.localhost("server", serverPort),
            msg -> {
                received.set(msg);
                latch.countDown();
            }
        );
        managers.add(server);
        server.listenOn(ProcessAddress.localhost("server", serverPort));

        // Create client
        var clientPort = findAvailablePort();
        var client = new SocketConnectionManager(
            ProcessAddress.localhost("client", clientPort),
            msg -> {}
        );
        managers.add(client);
        client.connectTo(ProcessAddress.localhost("server", serverPort));

        // Send message
        var testMsg = new TransportVonMessage(
            "JOIN_REQUEST",
            "client", "server", 0f, 0f, 0f, null, 0L, null, null, null, null
        );

        client.sendToProcess("server", testMsg);

        // Verify received
        assertTrue(latch.await(2, TimeUnit.SECONDS), "Should receive message within timeout");
        assertNotNull(received.get(), "Should receive message");
        assertEquals("JOIN_REQUEST", received.get().type(), "Message type should match");
    }

    /**
     * EDGE CASE: Double listenOn with same address (idempotent).
     */
    @Test
    void testDoubleListenOnSameAddress() throws IOException {
        var port = findAvailablePort();
        var addr = ProcessAddress.localhost("p1", port);
        var mgr = new SocketConnectionManager(addr, msg -> {});
        managers.add(mgr);

        // First listenOn
        mgr.listenOn(addr);
        assertTrue(mgr.isRunning(), "Should be running after first listenOn");

        // Second listenOn with same address (should be idempotent, no error)
        assertDoesNotThrow(() -> mgr.listenOn(addr),
                          "Double listenOn with same address should be idempotent");
        assertTrue(mgr.isRunning(), "Should still be running");
    }

    /**
     * EDGE CASE: Double listenOn with different address (throws IllegalStateException).
     */
    @Test
    void testDoubleListenOnDifferentAddress() throws IOException {
        var port1 = findAvailablePort();
        var addr1 = ProcessAddress.localhost("p1", port1);
        var mgr = new SocketConnectionManager(addr1, msg -> {});
        managers.add(mgr);

        // First listenOn
        mgr.listenOn(addr1);

        // Second listenOn with different address
        var port2 = findAvailablePort();
        var addr2 = ProcessAddress.localhost("p1", port2);
        var ex = assertThrows(IllegalStateException.class,
                             () -> mgr.listenOn(addr2),
                             "Should throw IllegalStateException for different address");

        assertTrue(ex.getMessage().contains("already listening") || ex.getMessage().contains("different address"),
                  "Error should mention already listening: " + ex.getMessage());
    }

    /**
     * EDGE CASE: Duplicate connectTo (idempotent, reuses existing connection).
     */
    @Test
    void testDuplicateConnectTo() throws IOException {
        // Start server
        var serverPort = findAvailablePort();
        var server = new SocketConnectionManager(
            ProcessAddress.localhost("server", serverPort),
            msg -> {}
        );
        managers.add(server);
        server.listenOn(ProcessAddress.localhost("server", serverPort));

        // Create client
        var clientPort = findAvailablePort();
        var client = new SocketConnectionManager(
            ProcessAddress.localhost("client", clientPort),
            msg -> {}
        );
        managers.add(client);

        // First connectTo
        client.connectTo(ProcessAddress.localhost("server", serverPort));
        var firstConnections = client.getConnectedProcesses().size();

        // Second connectTo to same server (should be idempotent)
        assertDoesNotThrow(() -> client.connectTo(ProcessAddress.localhost("server", serverPort)),
                          "Duplicate connectTo should be idempotent");

        // Should still have same number of connections (not duplicated)
        assertEquals(firstConnections, client.getConnectedProcesses().size(),
                    "Should not create duplicate connection");
    }

    /**
     * EDGE CASE: closeAll() during active connections.
     */
    @Test
    void testCloseAllDuringActiveConnections() throws IOException {
        // Start server
        var serverPort = findAvailablePort();
        var server = new SocketConnectionManager(
            ProcessAddress.localhost("server", serverPort),
            msg -> {}
        );
        managers.add(server);
        server.listenOn(ProcessAddress.localhost("server", serverPort));

        // Create and connect client
        var clientPort = findAvailablePort();
        var client = new SocketConnectionManager(
            ProcessAddress.localhost("client", clientPort),
            msg -> {}
        );
        managers.add(client);
        client.connectTo(ProcessAddress.localhost("server", serverPort));

        // Close all during active connection
        assertDoesNotThrow(() -> client.closeAll(),
                          "closeAll() should handle active connections gracefully");

        // Verify clean shutdown
        assertEquals(0, client.getConnectedProcesses().size(),
                    "Should have no connections after closeAll");
        assertFalse(client.isRunning(), "Should not be running after closeAll");
    }

    /**
     * EDGE CASE: getConnectedProcesses() concurrent access (thread-safe snapshot).
     */
    @Test
    void testGetConnectedProcessesConcurrent() throws Exception {
        // Start server
        var serverPort = findAvailablePort();
        var server = new SocketConnectionManager(
            ProcessAddress.localhost("server", serverPort),
            msg -> {}
        );
        managers.add(server);
        server.listenOn(ProcessAddress.localhost("server", serverPort));

        // Create client
        var clientPort = findAvailablePort();
        var client = new SocketConnectionManager(
            ProcessAddress.localhost("client", clientPort),
            msg -> {}
        );
        managers.add(client);
        client.connectTo(ProcessAddress.localhost("server", serverPort));

        // Thread 1: Repeatedly read connected processes
        var errors = new AtomicInteger(0);
        var latch = new CountDownLatch(2);

        var readThread = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                try {
                    var processes = client.getConnectedProcesses();
                    // Modify snapshot (should not affect client)
                    processes.clear();
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            }
            latch.countDown();
        });

        // Thread 2: Also read connected processes
        var readThread2 = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                try {
                    var processes = client.getConnectedProcesses();
                    assertNotNull(processes, "Should never return null");
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            }
            latch.countDown();
        });

        readThread.start();
        readThread2.start();

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Threads should complete");
        assertEquals(0, errors.get(), "Should have no errors from concurrent access");

        // Original client should still have connections
        assertEquals(1, client.getConnectedProcesses().size(),
                    "Client should still have 1 connection after concurrent reads");
    }

    /**
     * EDGE CASE: sendToProcess() when process not connected (throws IOException).
     */
    @Test
    void testSendToProcessNotConnected() {
        var mgr = new SocketConnectionManager(
            ProcessAddress.localhost("p1", findAvailablePort()),
            msg -> {}
        );
        managers.add(mgr);

        var testMsg = new TransportVonMessage(
            "TEST",
            "p1", "nonexistent", 0f, 0f, 0f, null, 0L, null, null, null, null
        );

        var ex = assertThrows(IOException.class,
                             () -> mgr.sendToProcess("nonexistent", testMsg),
                             "Should throw IOException when process not connected");

        var msg = ex.getMessage().toLowerCase();
        assertTrue(msg.contains("not connected") || msg.contains("not found"),
                  "Error should mention not connected: " + ex.getMessage());
    }

    /**
     * Find an available port for testing.
     */
    private int findAvailablePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("Failed to find available port", e);
        }
    }
}
