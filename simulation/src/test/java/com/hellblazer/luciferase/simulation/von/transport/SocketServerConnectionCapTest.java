/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * Licensed under AGPL v3.0. See LICENSE.
 */
package com.hellblazer.luciferase.simulation.von.transport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies Luciferase-7wzml.69: SocketServer enforces a max-connections cap and uses a bounded
 * thread pool. Connections exceeding the cap must be rejected (closed immediately), leaving
 * clientSockets bounded. Shutdown must still drain after rejection.
 *
 * @author hal.hildebrand
 */
class SocketServerConnectionCapTest {

    private SocketServer server;
    private final List<Socket> openedSockets = new ArrayList<>();

    @AfterEach
    void tearDown() throws IOException {
        for (var s : openedSockets) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        openedSockets.clear();
        if (server != null) {
            server.shutdown();
        }
    }

    // -----------------------------------------------------------------------
    // Helper: connect a raw TCP socket to the running server
    // -----------------------------------------------------------------------
    private Socket connect() throws IOException {
        var s = new Socket("127.0.0.1", server.getPort());
        openedSockets.add(s);
        return s;
    }

    // -----------------------------------------------------------------------
    // Helper: reflectively read the clientSockets field
    // -----------------------------------------------------------------------
    @SuppressWarnings("unchecked")
    private Set<Socket> clientSockets() throws Exception {
        Field f = SocketServer.class.getDeclaredField("clientSockets");
        f.setAccessible(true);
        return (Set<Socket>) f.get(server);
    }

    // -----------------------------------------------------------------------
    // Helper: reflectively read the executor field
    // -----------------------------------------------------------------------
    private java.util.concurrent.ExecutorService executor() throws Exception {
        Field f = SocketServer.class.getDeclaredField("executor");
        f.setAccessible(true);
        return (java.util.concurrent.ExecutorService) f.get(server);
    }

    // -----------------------------------------------------------------------
    // Test 1: connections beyond the cap are closed; clientSockets stays bounded
    // -----------------------------------------------------------------------
    @Test
    @Timeout(10)
    void excessConnectionsRejectedAndClientSocketsBounded() throws Exception {
        int cap = 3;
        server = new SocketServer(ProcessAddress.localhost("cap-test", 0), msg -> {}, cap);
        server.start();

        // Open cap connections — all must be accepted
        for (int i = 0; i < cap; i++) {
            connect();
        }

        // Give the accept loop time to track all cap connections
        waitForClientSocketCount(cap, 2_000);
        assertEquals(cap, clientSockets().size(),
                     "clientSockets should hold exactly cap=" + cap + " connections");

        // Open excess connections — server must close them immediately
        int excess = 3;
        for (int i = 0; i < excess; i++) {
            connect();
        }

        // Give accept loop time to process excess
        Thread.sleep(500);

        // clientSockets must remain <= cap (excess were closed, not tracked)
        int tracked = clientSockets().size();
        assertTrue(tracked <= cap,
                   "clientSockets must not exceed cap=" + cap + " after excess connections; got " + tracked);
    }

    // -----------------------------------------------------------------------
    // Test 2: shutdown still completes cleanly after rejection
    // -----------------------------------------------------------------------
    @Test
    @Timeout(10)
    void shutdownDrainsAfterRejection() throws Exception {
        int cap = 2;
        server = new SocketServer(ProcessAddress.localhost("shutdown-test", 0), msg -> {}, cap);
        server.start();

        // Fill to cap then send excess
        for (int i = 0; i < cap + 2; i++) {
            connect();
        }
        Thread.sleep(400);

        // Shutdown must not hang
        assertDoesNotThrow(() -> server.shutdown());
    }

    // -----------------------------------------------------------------------
    // Test 3: executor is a ThreadPoolExecutor (bounded), not cached pool
    // -----------------------------------------------------------------------
    @Test
    void executorIsBoundedThreadPool() throws Exception {
        server = new SocketServer(ProcessAddress.localhost("pool-test", 0), msg -> {});
        var exec = executor();
        assertInstanceOf(ThreadPoolExecutor.class, exec,
                         "executor must be a ThreadPoolExecutor (bounded), not newCachedThreadPool");
        var tpe = (ThreadPoolExecutor) exec;
        assertTrue(tpe.getMaximumPoolSize() < Integer.MAX_VALUE,
                   "ThreadPoolExecutor max pool size must be finite");
    }

    // -----------------------------------------------------------------------
    // Test 4: default constructor still enforces a cap (field present)
    // -----------------------------------------------------------------------
    @Test
    void defaultConstructorHasCap() throws Exception {
        server = new SocketServer(ProcessAddress.localhost("default-cap", 0), msg -> {});
        Field f = SocketServer.class.getDeclaredField("maxConnections");
        f.setAccessible(true);
        int maxConn = (int) f.get(server);
        assertTrue(maxConn > 0, "maxConnections must be positive in default constructor; got " + maxConn);
    }

    // -----------------------------------------------------------------------
    // Helper: wait until clientSockets.size() >= expected, up to timeoutMs
    // -----------------------------------------------------------------------
    private void waitForClientSocketCount(int expected, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (clientSockets().size() >= expected) {
                return;
            }
            Thread.sleep(50);
        }
    }
}
