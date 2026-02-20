/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.viz.render;

import com.hellblazer.luciferase.simulation.viz.render.protocol.ClientMessage;
import com.hellblazer.luciferase.simulation.viz.render.protocol.ServerMessage;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class InProcessTransportTest {

    @Test
    void serverCanReceiveAndSend() throws InterruptedException {
        var session = new InProcessTransport();
        var serverSide = session.serverTransport();
        var clientSide = session.clientView();

        // Client sends, server receives
        var hello = new ClientMessage.Hello("1.0");
        clientSide.sendToServer(hello);
        var received = serverSide.nextClientMessage(100, TimeUnit.MILLISECONDS);
        assertSame(hello, received, "in-process transport delivers the exact same object reference");

        // Server sends, client receives
        var ack = new ServerMessage.HelloAck("sess-42");
        serverSide.send(ack);
        var sent = clientSide.nextServerMessage(100, TimeUnit.MILLISECONDS);
        assertSame(ack, sent, "in-process transport delivers the exact same object reference");
    }

    @Test
    void returnsNullOnTimeout() throws InterruptedException {
        var session = new InProcessTransport();
        var server = session.serverTransport();
        // No message sent — expect null after short timeout
        var result = server.nextClientMessage(10, TimeUnit.MILLISECONDS);
        assertNull(result, "nextClientMessage must return null when queue is empty after timeout");
    }

    @Test
    void binaryFramesRoutedSeparately() throws InterruptedException {
        var session = new InProcessTransport();
        var server = session.serverTransport();
        var client = session.clientView();

        byte[] frame = { 0x45, 0x53, 0x56, 0x52 };
        server.sendBinary(frame);
        var received = client.nextBinaryFrame(100, TimeUnit.MILLISECONDS);
        assertArrayEquals(frame, received);
    }
}
