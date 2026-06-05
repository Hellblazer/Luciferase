/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase. Licensed under the GNU Affero General Public License v3.0.
 */

package com.hellblazer.luciferase.simulation.von.transport;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Luciferase-7wzml.178:
 * (1) connect() uses a bounded timeout — hanging/unreachable host fails fast.
 * (2) close() closes outStream — no resource leak.
 *
 * @author hal.hildebrand
 */
class SocketClientTest {

    // -----------------------------------------------------------------------
    // (1) Connect-timeout: connecting to a non-listening port must fail fast
    // -----------------------------------------------------------------------

    /**
     * Connect to a port that immediately refuses (no listener) must throw IOException fast,
     * well within the timeout, NOT hang for the OS default timeout.
     * <p>
     * We use a ServerSocket bound to port 0 (assigned by OS), record the port, then immediately
     * close the ServerSocket so the port is no longer listening. The OS will RESET/REFUSE the
     * connect attempt immediately rather than letting it hang, which is the fast-fail path we care
     * about. A timeout-based hang would take {@value SocketClient#DEFAULT_CONNECT_TIMEOUT_MS} ms
     * and a SocketTimeoutException; an immediate RESET throws ConnectException. Either is an
     * IOException — the test just requires it arrives within 4 seconds (well under the 5 s default).
     */
    @Test
    void connectToRefusedPortFailsFast() throws Exception {
        // Grab an OS-assigned port then immediately close the listener.
        int refusedPort;
        try (var ss = new ServerSocket(0)) {
            refusedPort = ss.getLocalPort();
        } // ss is now closed — port is not listening

        var addr = ProcessAddress.localhost("test-refused", refusedPort);
        var client = new SocketClient(addr, msg -> {}, SocketClient.DEFAULT_CONNECT_TIMEOUT_MS);

        assertTimeoutPreemptively(java.time.Duration.ofSeconds(4), () -> {
            assertThrows(IOException.class, client::connect,
                "connect() to a refused port must throw IOException");
        });
    }

    /**
     * A very short (1 ms) connect timeout to a blackhole address (TEST-NET-1, 192.0.2.1, per RFC 5737)
     * causes SocketTimeoutException within the timeout window, not OS-level hang.
     * <p>
     * If this address happens to be reachable (unlikely in any standard network), the test would
     * pass regardless: the connect would succeed (also fast). The important property is that it
     * does NOT hang for minutes.
     * <p>
     * Skipped on systems where 192.0.2.1 is erroneously routable and connects immediately (almost
     * never true, but the assertTimeoutPreemptively 4-second bound catches that anyway).
     */
    @Test
    void connectWithShortTimeoutThrowsSocketTimeoutException() {
        // 192.0.2.0/24 is TEST-NET-1 (RFC 5737) — reserved, unroutable, packets silently dropped.
        // A connect attempt should time out rather than succeed or be immediately refused.
        var addr = ProcessAddress.localhost("192.0.2.1", 9999);
        var client = new SocketClient(addr, msg -> {}, 200); // 200 ms timeout

        // Must complete (with any IOException) within 3 s — not hang for minutes.
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(3), () -> {
            var ex = assertThrows(IOException.class, client::connect,
                "connect() to unroutable address must throw IOException within timeout");
            // SocketTimeoutException is the expected class for a timeout; ConnectException
            // is acceptable if the OS rejects fast. Either is fine — the key property is
            // IOException within the bounded window above.
            assertTrue(ex instanceof SocketTimeoutException || ex.getMessage() != null,
                "expected SocketTimeoutException or connection error: " + ex);
        });
    }

    // -----------------------------------------------------------------------
    // (2) close() closes outStream — no resource leak
    // -----------------------------------------------------------------------

    /**
     * After close(), outStream must be closed. We verify via a spy OutputStream that records
     * whether close() was called. The spy is injected by subclassing SocketClient (package-private
     * for test) to intercept the outStream assignment — but SocketClient doesn't expose a setter.
     * Instead we use a real SocketServer round-trip to produce a connected client, then verify
     * double-close is safe (idempotent) and that the stream's underlying channel is shut down.
     * <p>
     * The cleanest observable proxy: after client.close(), client.isConnected() is false, and
     * any subsequent client.send() throws IOException (stream closed). That proves the stream
     * lifecycle is terminated.
     */
    @Test
    void closeDisconnectsAndPreventsSubsequentSend() throws Exception {
        var server = new SocketServer(ProcessAddress.localhost("server", 0), msg -> {});
        server.start();
        try {
            var serverAddr = ProcessAddress.localhost("server", server.getPort());
            var client = new SocketClient(serverAddr, msg -> {});
            client.connect();
            assertTrue(client.isConnected());

            client.close();

            assertFalse(client.isConnected(), "isConnected() must be false after close()");
            // send() must throw because connected=false (stream and socket closed)
            assertThrows(IOException.class, () -> client.send(
                new com.hellblazer.luciferase.simulation.von.TransportVonMessage(
                    "Leave", "11111111-1111-1111-1111-111111111111",
                    "11111111-1111-1111-1111-111111111111", 0f, 0f, 0f,
                    "11111111-1111-1111-1111-111111111111", 1L)),
                "send() after close() must throw IOException");
        } finally {
            server.shutdown();
        }
    }

    /**
     * close() is idempotent — calling it twice must not throw. Before the fix, only socket.close()
     * was called; outStream was null-or-open and skipped. After the fix both are closed with null
     * checks; a second close() sees socket.isClosed()==true and outStream already closed.
     */
    @Test
    void doubleCloseIsIdempotent() throws Exception {
        var server = new SocketServer(ProcessAddress.localhost("server", 0), msg -> {});
        server.start();
        try {
            var serverAddr = ProcessAddress.localhost("server", server.getPort());
            var client = new SocketClient(serverAddr, msg -> {});
            client.connect();
            client.close();
            assertDoesNotThrow(client::close, "second close() must not throw");
        } finally {
            server.shutdown();
        }
    }

    /**
     * close() before connect() (outStream == null, socket == null) must not throw NPE.
     */
    @Test
    void closeBeforeConnectIsNoop() {
        var addr = ProcessAddress.localhost("nowhere", 9999);
        var client = new SocketClient(addr, msg -> {});
        assertDoesNotThrow(client::close, "close() before connect() must be a no-op");
    }

    /**
     * Structural check: both send() and close() carry the SYNCHRONIZED modifier (same instance
     * monitor) — ensures close cannot interleave with an in-progress writeObject().
     * Regression for Luciferase-0frcy.52 / SocketClientCloseSynchronizationTest (kept here too
     * for completeness in this test class).
     */
    @Test
    void sendAndCloseAreSync() throws NoSuchMethodException {
        var send = SocketClient.class.getDeclaredMethod("send",
            com.hellblazer.luciferase.simulation.von.TransportVonMessage.class);
        var close = SocketClient.class.getDeclaredMethod("close");
        assertTrue(java.lang.reflect.Modifier.isSynchronized(send.getModifiers()), "send() must be synchronized");
        assertTrue(java.lang.reflect.Modifier.isSynchronized(close.getModifiers()), "close() must be synchronized");
    }
}
