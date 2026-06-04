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

package com.hellblazer.luciferase.simulation.von;

import com.hellblazer.luciferase.simulation.bubble.BubbleBounds;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3d;
import javax.vecmath.Point3f;
import java.io.*;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for Luciferase-vzyrf: BubbleBounds must survive the VoN wire round-trip for
 * JoinRequest, Move, and JoinResponse-neighbor messages.
 * <p>
 * Root cause guarded here: {@code MessageConverter.joinRequestFromTransport}/{@code moveFromTransport}
 * (and {@code TransportNeighborInfo.toNeighborInfo}) hard-coded {@code null} for BubbleBounds (the
 * "Phase 6A" stub). Consumers — {@code JoinProtocol.findNeighbors} → {@code joiner.bounds()},
 * {@code SpatialNeighborIndex} → {@code n.bounds().overlaps(...)} — dereference {@code bounds()}
 * without null guards, producing an NPE chain many frames after the silent data loss (RDR-004 class).
 * <p>
 * These tests assert bounds are <b>non-null and equal</b> after conversion AND after a real
 * Java-serialization round-trip through the {@link
 * com.hellblazer.luciferase.simulation.von.transport.VonTransportFilter} allow-list (which the wire
 * payload must pass, since the filter ends in {@code !*}).
 *
 * @author hal.hildebrand
 */
class BubbleBoundsWireRoundTripTest {

    private static BubbleBounds sampleBounds() {
        // Distinct, non-trivial positions so the RDGCS box and root key are non-degenerate.
        return BubbleBounds.fromEntityPositions(List.of(
            new Point3f(10f, 20f, 30f),
            new Point3f(60f, 70f, 80f),
            new Point3f(15f, 5f, 95f)));
    }

    @Test
    void joinRequestBoundsSurvivesConverterRoundTrip() {
        var bounds = sampleBounds();
        var original = new Message.JoinRequest(UUID.randomUUID(), new Point3d(1, 2, 3), bounds, 99L);

        var wire = MessageConverter.toTransport(original);
        assertNotNull(wire.bounds(), "bounds must be serialized into the wire message");

        var recovered = (Message.JoinRequest) MessageConverter.fromTransport(wire);
        assertNotNull(recovered.bounds(), "bounds must NOT be null after the wire round-trip (vzyrf)");
        assertEquals(bounds, recovered.bounds(), "bounds must round-trip equal");
        assertEquals(bounds.rootKey(), recovered.bounds().rootKey());
        assertEquals(bounds.rdgMin(), recovered.bounds().rdgMin());
        assertEquals(bounds.rdgMax(), recovered.bounds().rdgMax());
    }

    @Test
    void moveBoundsSurvivesConverterRoundTrip() {
        var bounds = sampleBounds();
        var original = new Message.Move(UUID.randomUUID(), new Point3d(4, 5, 6), bounds, 77L);

        var wire = MessageConverter.toTransport(original);
        assertNotNull(wire.bounds(), "bounds must be serialized into the wire Move message");

        var recovered = (Message.Move) MessageConverter.fromTransport(wire);
        assertNotNull(recovered.newBounds(), "newBounds must NOT be null after the wire round-trip (vzyrf)");
        assertEquals(bounds, recovered.newBounds(), "newBounds must round-trip equal");
    }

    @Test
    void joinResponseNeighborBoundsSurvivesConverterRoundTrip() {
        var bounds = sampleBounds();
        var neighbor = new Message.NeighborInfo(UUID.randomUUID(), new Point3d(7, 8, 9), bounds);
        var original = new Message.JoinResponse(UUID.randomUUID(), Set.of(neighbor), 55L);

        var wire = MessageConverter.toTransport(original);
        var recovered = (Message.JoinResponse) MessageConverter.fromTransport(wire);

        assertEquals(1, recovered.neighbors().size());
        var recoveredNeighbor = recovered.neighbors().iterator().next();
        assertNotNull(recoveredNeighbor.bounds(), "neighbor bounds must NOT be null after round-trip (vzyrf)");
        assertEquals(bounds, recoveredNeighbor.bounds(), "neighbor bounds must round-trip equal");
    }

    /**
     * End-to-end: the JoinRequest wire message carrying bounds must survive plain Java serialization
     * (the SocketServer/SocketClient byte path). The RDR-004 allow-list filter admitting
     * TransportBubbleBounds is covered separately in the transport package
     * (VonDeserializationHardeningTest).
     */
    @Test
    void joinRequestBoundsSurvivesSerializedWire() throws Exception {
        var bounds = sampleBounds();
        var original = new Message.JoinRequest(UUID.randomUUID(), new Point3d(1, 2, 3), bounds, 99L);
        var wire = MessageConverter.toTransport(original);

        var baos = new ByteArrayOutputStream();
        try (var oos = new ObjectOutputStream(baos)) {
            oos.writeObject(wire);
        }

        TransportVonMessage recoveredWire;
        try (var ois = new ObjectInputStream(new ByteArrayInputStream(baos.toByteArray()))) {
            recoveredWire = (TransportVonMessage) ois.readObject();
        }

        assertNotNull(recoveredWire.bounds());
        var recovered = (Message.JoinRequest) MessageConverter.fromTransport(recoveredWire);
        assertEquals(bounds, recovered.bounds(),
            "bounds must round-trip equal through serialized wire (vzyrf)");
    }
}
