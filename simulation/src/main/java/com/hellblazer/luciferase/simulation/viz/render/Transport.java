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

import java.util.concurrent.TimeUnit;

/**
 * Abstraction over WebSocket connection. Enables in-process testing without network.
 * The server reads ClientMessages from the client and writes ServerMessages + binary frames.
 *
 * @author hal.hildebrand
 */
public interface Transport {

    /** Send a JSON control message to the client. */
    void send(ServerMessage msg);

    /** Send a pre-encoded binary frame to the client. */
    void sendBinary(byte[] frame);

    /**
     * Block until a ClientMessage arrives, or timeout.
     * Returns null on timeout.
     */
    ClientMessage nextClientMessage(long timeout, TimeUnit unit) throws InterruptedException;

    /** Close this transport. */
    void close();
}
