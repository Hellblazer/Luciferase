/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.viz.render.protocol;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MessagesTest {

    @Test
    void clientMessageSwitchIsExhaustive() {
        ClientMessage msg = new ClientMessage.Hello("1.0");
        String result = switch (msg) {
            case ClientMessage.Hello h -> "hello:" + h.version();
            case ClientMessage.SnapshotRequest r -> "snap:" + r.requestId();
            case ClientMessage.Subscribe s -> "sub:" + s.snapshotToken();
            case ClientMessage.ViewportUpdate v -> "viewport";
            case ClientMessage.Unsubscribe u -> "unsub";
        };
        assertEquals("hello:1.0", result);
    }

    @Test
    void serverMessageSwitchIsExhaustive() {
        ServerMessage msg = new ServerMessage.HelloAck("sess-1");
        String result = switch (msg) {
            case ServerMessage.HelloAck a -> "ack:" + a.sessionId();
            case ServerMessage.SnapshotManifest m -> "manifest";
            case ServerMessage.RegionUpdate u -> "update";
            case ServerMessage.RegionRemoved r -> "removed";
            case ServerMessage.SnapshotRequired sr -> "required";
            case ServerMessage.Error e -> "error";
        };
        assertEquals("ack:sess-1", result);
    }
}
