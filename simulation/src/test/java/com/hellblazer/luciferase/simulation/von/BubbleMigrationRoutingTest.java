/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.von;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Regression test for Luciferase-l5gr9: Bubble.handleMessage must ROUTE inbound 2PC
 * {@link MigrationProtocolMessages}, not silently drop them on the {@code default -> log.warn} arm.
 */
class BubbleMigrationRoutingTest {

    @Test
    void inbound2PCMessageIsRoutedToMigrationHandler() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            var transport = mock(Transport.class);
            var bubble = new Bubble(UUID.randomUUID(), (byte) 10, 16L, transport);

            // Capture the message handler the Bubble registered with the transport.
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Consumer<Message>> captor = ArgumentCaptor.forClass(Consumer.class);
            verify(transport).onMessage(captor.capture());
            var inbound = captor.getValue();

            // Register a migration handler and deliver a 2PC message through the transport seam.
            var received = new AtomicReference<MigrationProtocolMessages>();
            bubble.setMigrationHandler(received::set);

            var txId = UUID.randomUUID();
            var msg = new MigrationProtocolMessages.CommitRequest(txId, true, 1000L);
            inbound.accept(msg);  // pre-fix: hit default -> log.warn, dropped

            assertNotNull(received.get(), "2PC message must be routed, not dropped");
            assertSame(msg, received.get());
        });
    }
}
