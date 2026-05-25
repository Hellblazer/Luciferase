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

import com.hellblazer.luciferase.simulation.von.Message;
import com.hellblazer.luciferase.simulation.von.MessageConverter;
import com.hellblazer.luciferase.simulation.von.TransportGhostData;
import com.hellblazer.luciferase.simulation.von.TransportNeighborInfo;
import com.hellblazer.luciferase.simulation.von.TransportVonMessage;
import javafx.geometry.Point3D;
import javax.vecmath.Point3f;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InvalidClassException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RDR-004 Direction A: network-deserialization hardening on the VoN socket transport.
 * <p>
 * Verifies (1) the {@link VonTransportFilter} allow-list rejects gadget-bearing collections such as
 * {@link PriorityQueue} while accepting the concrete {@link TransportVonMessage} wire payload, and
 * (2) {@link SocketServer} refuses to bind to a non-loopback address.
 *
 * @author hal.hildebrand
 */
class VonDeserializationHardeningTest {

    private static byte[] serialize(Serializable object) throws IOException {
        var baos = new ByteArrayOutputStream();
        try (var oos = new ObjectOutputStream(baos)) {
            oos.writeObject(object);
        }
        return baos.toByteArray();
    }

    private static Object deserializeFiltered(byte[] bytes) throws IOException, ClassNotFoundException {
        try (var ois = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            ois.setObjectInputFilter(VonTransportFilter.create());
            return ois.readObject();
        }
    }

    /**
     * The canonical gadget surface: a {@link PriorityQueue} (admitted by a broad {@code java.util.*}
     * wildcard) must be rejected by the narrowed allow-list before any of its contents deserialize.
     */
    @Test
    void filterRejectsPriorityQueue() throws IOException {
        var bytes = serialize(new PriorityQueue<>(List.of("a", "b", "c")));
        assertThrows(InvalidClassException.class, () -> deserializeFiltered(bytes),
                     "PriorityQueue must be rejected by the VoN deserialization filter");
    }

    /**
     * A second gadget-bearing collection ({@code java.util.HashMap}) is likewise rejected — confirms the
     * allow-list is not merely excluding one named type.
     */
    @Test
    void filterRejectsHashMap() throws IOException {
        var map = new java.util.HashMap<String, String>();
        map.put("k", "v");
        var bytes = serialize(map);
        assertThrows(InvalidClassException.class, () -> deserializeFiltered(bytes),
                     "HashMap must be rejected by the VoN deserialization filter");
    }

    /**
     * The legitimate wire payload — a fully populated {@link TransportVonMessage} carrying
     * {@code ArrayList}s of ghost and neighbor records — must round-trip through the filter unharmed.
     */
    @Test
    void filterAcceptsTransportVonMessageRoundTrip() throws IOException, ClassNotFoundException {
        var ghosts = new ArrayList<>(List.of(
            new TransportGhostData("e1", 1f, 2f, 3f, "java.lang.String", "payload", "tree-1", 1L, 2L, 3L)));
        var neighbors = new ArrayList<>(List.of(
            new TransportNeighborInfo("n1", 10.0, 20.0, 30.0)));
        var original = new TransportVonMessage(
            "GHOST_SYNC", "src-bubble", "tgt-bubble", 1f, 2f, 3f, "entity-1", 42L,
            ghosts, 7L, neighbors, "query-1");

        var bytes = serialize(original);
        var decoded = assertDoesNotThrow(() -> deserializeFiltered(bytes),
                                         "A legitimate TransportVonMessage must pass the filter");

        assertEquals(original, decoded, "Round-tripped message must equal the original");
    }

    /**
     * Producer↔filter contract guard. The filter is only correct if every {@link MessageConverter}
     * producer emits allow-listed concrete types onto the wire. That coupling is implicit: a JoinResponse
     * regression already occurred when the neighbor list was built with {@code Stream.toList()} (an
     * {@code ImmutableCollections} type not on the allow-list), which would have silently dropped every
     * JoinResponse carrying neighbors. This test drives <em>every</em> {@code Message} subtype through the
     * real {@code toTransport} → serialize → filtered-deserialize path, so the same class of regression on
     * any message type (e.g. a future collection-typed field) fails CI rather than production traffic.
     */
    @Test
    void filterAcceptsEveryConverterProducedMessageType() throws IOException {
        var ghost = new Message.TransportGhost("e1", new Point3f(1, 2, 3), "java.lang.String", "v", "tree-1", 1L, 2L, 3L);
        var neighbor = new Message.NeighborInfo(UUID.randomUUID(), new Point3D(1, 2, 3), null);
        List<Message> messages = List.of(
            new Message.JoinRequest(UUID.randomUUID(), new Point3D(1, 2, 3), null, 1L),
            new Message.JoinResponse(UUID.randomUUID(), Set.of(neighbor), 2L),
            new Message.Move(UUID.randomUUID(), new Point3D(4, 5, 6), null, 3L),
            new Message.Leave(UUID.randomUUID(), 4L),
            new Message.GhostSync(UUID.randomUUID(), List.of(ghost), 9L, 5L),
            new Message.Ack(UUID.randomUUID(), UUID.randomUUID(), 6L),
            new Message.Query(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "position", 7L),
            new Message.QueryResponse(UUID.randomUUID(), UUID.randomUUID(), "{}", 8L));

        for (var message : messages) {
            var wire = MessageConverter.toTransport(message);
            var bytes = serialize(wire);
            var decoded = assertDoesNotThrow(() -> deserializeFiltered(bytes),
                () -> message.getClass().getSimpleName() + " produced by MessageConverter must pass the filter");
            assertEquals(wire, decoded,
                () -> message.getClass().getSimpleName() + " must round-trip through the filter unchanged");
        }
    }

    /**
     * {@link SocketServer#start()} must reject a non-loopback bind address (RDR-004 bind hardening).
     * {@code 8.8.8.8} is an IPv4 literal — {@code getByName} resolves it without a DNS lookup — so the
     * guard fires deterministically before {@code new ServerSocket(...)} is reached.
     * <p>
     * <b>Inc 7+ enforcement artifact (Luciferase-ah3).</b> This test is the CI tripwire that keeps the
     * loopback restriction from being silently deleted: removing the guard in {@code SocketServer.start()}
     * (or in {@code SocketConnectionManager.isLoopback()}) fails here. Do not relax or delete it without
     * landing the deserialization hardening that {@code Luciferase-ah3} gates.
     */
    @Test
    void socketServerRejectsNonLoopbackBind() {
        var server = new SocketServer(new ProcessAddress("p1", "8.8.8.8", 0), msg -> {});
        var ex = assertThrows(IllegalArgumentException.class, server::start,
                              "SocketServer must reject a non-loopback bind address");
        assertTrue(ex.getMessage().contains("loopback"),
                   "Error should mention the loopback requirement: " + ex.getMessage());
    }

    /**
     * {@link SocketServer#start()} must still accept a loopback bind address.
     */
    @Test
    void socketServerAcceptsLoopbackBind() throws IOException {
        var server = new SocketServer(ProcessAddress.localhost("p1", 0), msg -> {});
        try {
            assertDoesNotThrow(server::start, "SocketServer must accept a loopback bind address");
            assertTrue(server.isRunning(), "Server should be running after a successful loopback bind");
        } finally {
            server.shutdown();
        }
    }
}
