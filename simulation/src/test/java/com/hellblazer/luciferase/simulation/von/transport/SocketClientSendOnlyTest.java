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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for Luciferase-ihy0s: SocketClient must be send-only and must NOT construct a
 * receive-side {@link java.io.ObjectInputStream}.
 * <p>
 * Root cause guarded here: SocketServer opens only an ObjectInputStream on each accepted
 * connection and never an ObjectOutputStream. A client-side
 * {@code new ObjectInputStream(socket.getInputStream())} blocks forever in its constructor reading
 * the serialization stream header the server never emits, silently leaking a daemon thread and a
 * socket. These tests bound the connect+send round-trip with a hard timeout so a regression
 * (reintroduction of the receive loop) fails fast rather than hanging CI, and assert that no
 * {@code socket-client-recv-*} thread is ever spawned.
 *
 * @author hal.hildebrand
 */
class SocketClientSendOnlyTest {

    private SocketServer server;
    private SocketClient client;

    @AfterEach
    void cleanup() throws IOException {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.shutdown();
        }
    }

    /**
     * connect()+send() against a real (input-only) SocketServer completes within a bounded time and
     * the server actually receives the message. If the client reintroduced a receive-side OIS
     * constructor it would deadlock; the assertTimeoutPreemptively bound makes that fail fast.
     */
    @Test
    void connectAndSendCompletesWithoutDeadlock() {
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
            var received = new AtomicReference<TransportVonMessage>();
            var latch = new CountDownLatch(1);

            // Server mirrors the production wiring: input-only, never opens an ObjectOutputStream.
            server = new SocketServer(ProcessAddress.localhost("server", 0), msg -> {
                received.set(msg);
                latch.countDown();
            });
            server.start();

            var serverAddr = ProcessAddress.localhost("server", server.getPort());
            client = new SocketClient(serverAddr, msg -> fail("client must never receive: " + msg));
            client.connect();
            assertTrue(client.isConnected(), "client should be connected after connect()");

            var outbound = new TransportVonMessage(
                "Leave", "11111111-1111-1111-1111-111111111111",
                "11111111-1111-1111-1111-111111111111", 0f, 0f, 0f,
                "11111111-1111-1111-1111-111111111111", 42L);
            client.send(outbound);

            assertTrue(latch.await(4, TimeUnit.SECONDS), "server should receive the sent message");
            assertNotNull(received.get());
            assertEquals("Leave", received.get().type());
            assertEquals(42L, received.get().timestamp());
        });
    }

    /**
     * No receive thread is spawned by connect(). The old implementation started a daemon thread
     * named {@code socket-client-recv-<host>:<port>} that blocked forever in the OIS constructor.
     */
    @Test
    void connectStartsNoReceiveThread() throws Exception {
        server = new SocketServer(ProcessAddress.localhost("server", 0), msg -> {});
        server.start();

        var serverAddr = ProcessAddress.localhost("server", server.getPort());
        client = new SocketClient(serverAddr, msg -> {});
        client.connect();

        // Give any (erroneously-spawned) receive thread a chance to register.
        Thread.sleep(200);

        var leaked = Thread.getAllStackTraces().keySet().stream()
            .map(Thread::getName)
            .filter(n -> n.startsWith("socket-client-recv-"))
            .toList();
        assertTrue(leaked.isEmpty(),
            "SocketClient is send-only and must not start a receive thread; found: " + leaked);
    }
}
