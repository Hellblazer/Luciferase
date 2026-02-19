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
        clientSide.sendToServer(new ClientMessage.Hello("1.0"));
        var received = serverSide.nextClientMessage(100, TimeUnit.MILLISECONDS);
        assertInstanceOf(ClientMessage.Hello.class, received);

        // Server sends, client receives
        serverSide.send(new ServerMessage.HelloAck("sess-42"));
        var sent = clientSide.nextServerMessage(100, TimeUnit.MILLISECONDS);
        assertInstanceOf(ServerMessage.HelloAck.class, sent);
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
