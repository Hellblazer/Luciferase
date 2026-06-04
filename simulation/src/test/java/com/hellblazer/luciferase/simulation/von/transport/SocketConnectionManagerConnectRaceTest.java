/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase. Licensed under the GNU Affero General Public License v3.0.
 */

package com.hellblazer.luciferase.simulation.von.transport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Regression test for Luciferase-0frcy.53: SocketConnectionManager.connectTo() must be race-free.
 * The prior containsKey()-then-put() check-then-act let two concurrent connectTo() calls to the
 * same processId both open a TCP connection; one was leaked (never tracked, never closed). The fix
 * uses an atomic computeIfAbsent so exactly one client is created and tracked.
 */
class SocketConnectionManagerConnectRaceTest {

    private final List<SocketConnectionManager> managers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (var m : managers) {
            try {
                m.closeAll();
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    void concurrentConnectToSamePeerCreatesExactlyOneConnection() throws Exception {
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            for (int trial = 0; trial < 20; trial++) {
                var server = new SocketConnectionManager(ProcessAddress.localhost("server-" + trial, 0), msg -> {});
                managers.add(server);
                server.listenOn(ProcessAddress.localhost("server-" + trial, 0));
                var serverAddr = server.getBoundAddress();

                var client = new SocketConnectionManager(ProcessAddress.localhost("client-" + trial, 0), msg -> {});
                managers.add(client);

                int threads = 6;
                var pool = Executors.newFixedThreadPool(threads);
                try {
                    var start = new CountDownLatch(1);
                    var tasks = new ArrayList<Callable<Void>>();
                    for (int i = 0; i < threads; i++) {
                        tasks.add(() -> {
                            start.await();
                            client.connectTo(serverAddr);  // same processId for all
                            return null;
                        });
                    }
                    var futures = new ArrayList<Future<Void>>();
                    for (var t : tasks) {
                        futures.add(pool.submit(t));
                    }
                    start.countDown();
                    for (var f : futures) {
                        f.get(10, TimeUnit.SECONDS);
                    }
                } finally {
                    pool.shutdownNow();
                }

                assertEquals(1, client.getConnectedProcesses().size(),
                             "Exactly one connection must be tracked for the peer (trial " + trial + ")");

                client.closeAll();
                server.closeAll();
            }
        });
    }
}
