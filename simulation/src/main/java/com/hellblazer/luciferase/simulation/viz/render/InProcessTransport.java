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

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * In-process Transport for deterministic testing. No network, no serialization.
 * Use serverTransport() as the server-side handle and clientView() for test assertions.
 *
 * @author hal.hildebrand
 */
public final class InProcessTransport {

    private final BlockingQueue<ClientMessage> fromClient    = new LinkedBlockingQueue<>();
    private final BlockingQueue<ServerMessage> toClient      = new LinkedBlockingQueue<>();
    private final BlockingQueue<byte[]>        binaryToClient = new LinkedBlockingQueue<>();

    /** The handle the server-side code (session handler) holds. */
    public Transport serverTransport() {
        return new Transport() {
            @Override
            public void send(ServerMessage msg) {
                toClient.add(msg);
            }

            @Override
            public void sendBinary(byte[] frame) {
                binaryToClient.add(frame);
            }

            @Override
            public ClientMessage nextClientMessage(long timeout, TimeUnit unit) throws InterruptedException {
                return fromClient.poll(timeout, unit);
            }

            @Override
            public void close() {
            }
        };
    }

    /** The handle test code holds to simulate client behaviour. */
    public ClientView clientView() {
        return new ClientView();
    }

    public final class ClientView {

        public void sendToServer(ClientMessage msg) {
            fromClient.add(msg);
        }

        public ServerMessage nextServerMessage(long timeout, TimeUnit unit) throws InterruptedException {
            return toClient.poll(timeout, unit);
        }

        public byte[] nextBinaryFrame(long timeout, TimeUnit unit) throws InterruptedException {
            return binaryToClient.poll(timeout, unit);
        }
    }
}
