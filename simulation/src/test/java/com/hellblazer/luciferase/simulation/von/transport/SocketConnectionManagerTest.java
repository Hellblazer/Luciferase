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
 * <p>Port allocation: All tests use port 0 (OS-assigned) via listenOn(), then read the
 * actual bound port from getBoundAddress(). This eliminates the find-then-bind TOCTOU
 * race where a port is discovered free but grabbed by another process before we bind.
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
        // IPv4 loopback (127.0.0.1) with OS-assigned port
        var mgr1 = new SocketConnectionManager(ProcessAddress.localhost("p1", 0), msg -> {});
        managers.add(mgr1);
        assertDoesNotThrow(() -> mgr1.listenOn(ProcessAddress.localhost("p1", 0)),
                          "Should accept 127.0.0.1");

        // DNS name "localhost" with OS-assigned port
        var mgr2 = new SocketConnectionManager(new ProcessAddress("p2", "localhost", 0), msg -> {});
        managers.add(mgr2);
        assertDoesNotThrow(() -> mgr2.listenOn(new ProcessAddress("p2", "localhost", 0)),
                          "Should accept 'localhost'");
    }

    /**
     * Test listenOn() rejects non-loopback addresses.
     */
    @Test
    void testListenOnRejectsNonLoopback() {
        var mgr = new SocketConnectionManager(new ProcessAddress("p1", "0.0.0.0", 9999), msg -> {});
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
        // Start a server on OS-assigned port; get actual address from getBoundAddress()
        var server = new SocketConnectionManager(ProcessAddress.localhost("server", 0), msg -> {});
        managers.add(server);
        server.listenOn(ProcessAddress.localhost("server", 0));
        var serverAddr = server.getBoundAddress();

        // Create client and connect using the actual server address
        var client = new SocketConnectionManager(ProcessAddress.localhost("client", 0), msg -> {});
        managers.add(client);

        assertDoesNotThrow(() -> client.connectTo(serverAddr),
                          "Should accept 127.0.0.1 for connectTo");
    }

    /**
     * Test connectTo() rejects non-loopback addresses.
     */
    @Test
    void testConnectToRejectsNonLoopback() {
        var mgr = new SocketConnectionManager(ProcessAddress.localhost("p1", 0), msg -> {});
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
        // Start server on OS-assigned port
        var server = new SocketConnectionManager(ProcessAddress.localhost("server", 0), msg -> {});
        managers.add(server);
        server.listenOn(ProcessAddress.localhost("server", 0));
        var serverAddr = server.getBoundAddress();

        // Create client
        var client = new SocketConnectionManager(ProcessAddress.localhost("client", 0), msg -> {});
        managers.add(client);

        // Before connection
        assertEquals(0, client.getConnectedProcesses().size(), "Should have no connections initially");

        // After connection to actual server address
        client.connectTo(serverAddr);

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
        // Start server on OS-assigned port
        var server = new SocketConnectionManager(ProcessAddress.localhost("server", 0), msg -> {});
        managers.add(server);
        server.listenOn(ProcessAddress.localhost("server", 0));
        var serverAddr = server.getBoundAddress();

        // Create and connect client using actual server address
        var client = new SocketConnectionManager(ProcessAddress.localhost("client", 0), msg -> {});
        managers.add(client);
        client.connectTo(serverAddr);

        // Close all
        client.closeAll();

        assertEquals(0, client.getConnectedProcesses().size(), "Should have no connections after closeAll");
        assertFalse(client.isRunning(), "Should not be running after closeAll");
    }

    /**
     * Test isRunning() returns false on construction (BUG FIX).
     */
    @Test
    void testIsRunningFalseOnConstruction() {
        var mgr = new SocketConnectionManager(ProcessAddress.localhost("p1", 0), msg -> {});
        managers.add(mgr);

        assertFalse(mgr.isRunning(),
                   "isRunning() should be FALSE on construction (BUG FIX for SocketTransport line 84)");
    }

    /**
     * Test isRunning() becomes true after listenOn().
     */
    @Test
    void testIsRunningTrueAfterListenOn() throws IOException {
        var mgr = new SocketConnectionManager(ProcessAddress.localhost("p1", 0), msg -> {});
        managers.add(mgr);

        assertFalse(mgr.isRunning(), "Should be false before listenOn");

        mgr.listenOn(ProcessAddress.localhost("p1", 0));
        assertTrue(mgr.isRunning(), "Should be true after listenOn");
    }

    /**
     * Test isRunning() becomes true after connectTo().
     */
    @Test
    void testIsRunningTrueAfterConnectTo() throws IOException {
        // Start server on OS-assigned port
        var server = new SocketConnectionManager(ProcessAddress.localhost("server", 0), msg -> {});
        managers.add(server);
        server.listenOn(ProcessAddress.localhost("server", 0));
        var serverAddr = server.getBoundAddress();

        // Create client
        var client = new SocketConnectionManager(ProcessAddress.localhost("client", 0), msg -> {});
        managers.add(client);

        assertFalse(client.isRunning(), "Should be false before connectTo");

        client.connectTo(serverAddr);
        assertTrue(client.isRunning(), "Should be true after connectTo");
    }

    /**
     * Test sendToProcess() sends message without exposing SocketClient.
     */
    @Test
    void testSendToProcess() throws Exception {
        var received = new AtomicReference<TransportVonMessage>();
        var latch = new CountDownLatch(1);

        // Start server on OS-assigned port
        var server = new SocketConnectionManager(
            ProcessAddress.localhost("server", 0),
            msg -> {
                received.set(msg);
                latch.countDown();
            }
        );
        managers.add(server);
        server.listenOn(ProcessAddress.localhost("server", 0));
        var serverAddr = server.getBoundAddress();

        // Create client and connect to actual server address
        var client = new SocketConnectionManager(ProcessAddress.localhost("client", 0), msg -> {});
        managers.add(client);
        client.connectTo(serverAddr);

        // Send message
        var testMsg = new TransportVonMessage(
            "JOIN_REQUEST",
            "client", "server", 0f, 0f, 0f, null, 0L, null, null, null, null
        );

        client.sendToProcess("server", testMsg);

        assertTrue(latch.await(2, TimeUnit.SECONDS), "Should receive message within timeout");
        assertNotNull(received.get(), "Should receive message");
        assertEquals("JOIN_REQUEST", received.get().type(), "Message type should match");
    }

    /**
     * EDGE CASE: Double listenOn with same address (idempotent).
     * <p>
     * After listenOn(port-0), currentBindAddress holds the actual OS-assigned port.
     * The idempotent check compares against currentBindAddress, so the second call
     * must use getBoundAddress() (not the original port-0 address).
     */
    @Test
    void testDoubleListenOnSameAddress() throws IOException {
        var mgr = new SocketConnectionManager(ProcessAddress.localhost("p1", 0), msg -> {});
        managers.add(mgr);

        // First listenOn: OS assigns an ephemeral port
        mgr.listenOn(ProcessAddress.localhost("p1", 0));
        assertTrue(mgr.isRunning(), "Should be running after first listenOn");

        // Second listenOn with the ACTUAL bound address (idempotent, no error)
        var actualAddr = mgr.getBoundAddress();
        assertDoesNotThrow(() -> mgr.listenOn(actualAddr),
                          "Double listenOn with same address should be idempotent");
        assertTrue(mgr.isRunning(), "Should still be running");
    }

    /**
     * EDGE CASE: Double listenOn with different address (throws IllegalStateException).
     */
    @Test
    void testDoubleListenOnDifferentAddress() throws IOException {
        var mgr = new SocketConnectionManager(ProcessAddress.localhost("p1", 0), msg -> {});
        managers.add(mgr);

        // First listenOn on OS-assigned port
        mgr.listenOn(ProcessAddress.localhost("p1", 0));

        // Second listenOn with a different process ID (different address)
        var ex = assertThrows(IllegalStateException.class,
                             () -> mgr.listenOn(ProcessAddress.localhost("p2", 0)),
                             "Should throw IllegalStateException for different address");

        assertTrue(ex.getMessage().contains("already listening") || ex.getMessage().contains("different address"),
                  "Error should mention already listening: " + ex.getMessage());
    }

    /**
     * EDGE CASE: Duplicate connectTo (idempotent, reuses existing connection).
     */
    @Test
    void testDuplicateConnectTo() throws IOException {
        // Start server on OS-assigned port
        var server = new SocketConnectionManager(ProcessAddress.localhost("server", 0), msg -> {});
        managers.add(server);
        server.listenOn(ProcessAddress.localhost("server", 0));
        var serverAddr = server.getBoundAddress();

        // Create client
        var client = new SocketConnectionManager(ProcessAddress.localhost("client", 0), msg -> {});
        managers.add(client);

        // First connectTo using actual server address
        client.connectTo(serverAddr);
        var firstConnections = client.getConnectedProcesses().size();

        // Second connectTo to same server (should be idempotent)
        assertDoesNotThrow(() -> client.connectTo(serverAddr),
                          "Duplicate connectTo should be idempotent");

        assertEquals(firstConnections, client.getConnectedProcesses().size(),
                    "Should not create duplicate connection");
    }

    /**
     * EDGE CASE: closeAll() during active connections.
     */
    @Test
    void testCloseAllDuringActiveConnections() throws IOException {
        // Start server on OS-assigned port
        var server = new SocketConnectionManager(ProcessAddress.localhost("server", 0), msg -> {});
        managers.add(server);
        server.listenOn(ProcessAddress.localhost("server", 0));
        var serverAddr = server.getBoundAddress();

        // Create and connect client
        var client = new SocketConnectionManager(ProcessAddress.localhost("client", 0), msg -> {});
        managers.add(client);
        client.connectTo(serverAddr);

        assertDoesNotThrow(() -> client.closeAll(), "closeAll() should handle active connections gracefully");

        assertEquals(0, client.getConnectedProcesses().size(), "Should have no connections after closeAll");
        assertFalse(client.isRunning(), "Should not be running after closeAll");
    }

    /**
     * EDGE CASE: getConnectedProcesses() concurrent access (thread-safe snapshot).
     */
    @Test
    void testGetConnectedProcessesConcurrent() throws Exception {
        // Start server on OS-assigned port
        var server = new SocketConnectionManager(ProcessAddress.localhost("server", 0), msg -> {});
        managers.add(server);
        server.listenOn(ProcessAddress.localhost("server", 0));
        var serverAddr = server.getBoundAddress();

        // Create client and connect to actual server address
        var client = new SocketConnectionManager(ProcessAddress.localhost("client", 0), msg -> {});
        managers.add(client);
        client.connectTo(serverAddr);

        var errors = new AtomicInteger(0);
        var latch = new CountDownLatch(2);

        var readThread = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                try {
                    var processes = client.getConnectedProcesses();
                    processes.clear();  // Modify snapshot (should not affect client)
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            }
            latch.countDown();
        });

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

        assertEquals(1, client.getConnectedProcesses().size(),
                    "Client should still have 1 connection after concurrent reads");
    }

    /**
     * EDGE CASE: sendToProcess() when process not connected (throws IOException).
     */
    @Test
    void testSendToProcessNotConnected() {
        var mgr = new SocketConnectionManager(ProcessAddress.localhost("p1", 0), msg -> {});
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
}
