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

import com.hellblazer.luciferase.simulation.distributed.migration.EntitySnapshot;
import com.hellblazer.luciferase.simulation.distributed.migration.IdempotencyToken;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3d;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;

/**
 * Regression + hardening test for Luciferase-l5gr9 (RDR-004 class silent-drop).
 * <p>
 * Pre-fix, the six {@link MigrationProtocolMessages} subtypes were permitted by the sealed
 * {@code Message} interface but absent from {@link MessageConverter}, so {@code toTransport} threw
 * {@code IllegalArgumentException} and the 2PC migration protocol was inoperative over transport.
 * <p>
 * This test enforces RDR-004 hygiene: every message type the converter produces must
 * <ol>
 *   <li>round-trip through {@link MessageConverter} without {@code IllegalArgumentException},</li>
 *   <li>survive the strict {@code VonTransportFilter} deserialization allow-list, and</li>
 *   <li>reconstruct non-null with control fields preserved.</li>
 * </ol>
 * The allow-list is kept NARROW: the rich domain types ({@code IdempotencyToken},
 * {@code EntitySnapshot}, arbitrary {@code Object content}) are decomposed into the primitive-only
 * {@code TransportMigrationMessage}, so no domain type is ever admitted to deserialization.
 */
class MigrationProtocol2PCWireRoundTripTest {

    // Mirror of the production VonTransportFilter allow-list pattern. Kept here (the production
    // class is package-private in von.transport) to assert the wire payload survives the NARROW
    // list — if a future change admits a rich domain type, this test fails.
    private static final String FILTER_PATTERN =
        "com.hellblazer.luciferase.simulation.von.TransportVonMessage;"
        + "com.hellblazer.luciferase.simulation.von.TransportGhostData;"
        + "com.hellblazer.luciferase.simulation.von.TransportNeighborInfo;"
        + "com.hellblazer.luciferase.simulation.von.TransportBubbleBounds;"
        + "com.hellblazer.luciferase.simulation.von.TransportMigrationMessage;"
        + "java.util.ArrayList;"
        + "java.util.Collections$UnmodifiableList;"
        + "java.util.Arrays$ArrayList;"
        + "java.lang.*;"
        + "java.time.*;"
        + "java.math.*;"
        + "!*";

    private final UUID txId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID sourceId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private final UUID destId = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private final long ts = 123456789L;

    private IdempotencyToken token() {
        return new IdempotencyToken("entity-7", sourceId, destId, ts,
                                    UUID.fromString("44444444-4444-4444-4444-444444444444"));
    }

    private EntitySnapshot snapshot() {
        // content is a String so the narrow-allow-list round-trip is exact.
        return new EntitySnapshot("entity-7", new Point3d(1.0, 2.0, 3.0), "payload-content",
                                  sourceId, 5L, 9L, ts);
    }

    private List<Message> allSubtypes() {
        return List.of(
            new MigrationProtocolMessages.PrepareRequest(txId, token(), snapshot(), sourceId, destId, ts),
            new MigrationProtocolMessages.PrepareResponse(txId, true, null, destId, ts),
            new MigrationProtocolMessages.CommitRequest(txId, true, ts),
            new MigrationProtocolMessages.CommitResponse(txId, true, null, ts),
            new MigrationProtocolMessages.AbortRequest(txId, "view-changed", ts),
            new MigrationProtocolMessages.AbortResponse(txId, true, ts));
    }

    /** Every 2PC subtype round-trips through MessageConverter without IAE and reconstructs non-null. */
    @Test
    void allSubtypesRoundTripThroughConverter() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            for (var original : allSubtypes()) {
                var transport = MessageConverter.toTransport(original);   // must NOT throw IAE (pre-fix bug)
                assertEquals("Migration", transport.type());
                assertNotNull(transport.migration(), "migration payload must be on the wire: "
                                                      + original.getClass().getSimpleName());

                var back = MessageConverter.fromTransport(transport);
                assertNotNull(back, "reconstructed message must be non-null");
                assertInstanceOf(MigrationProtocolMessages.class, back);
                assertEquals(original.getClass(), back.getClass(),
                             "subtype must be preserved: " + original.getClass().getSimpleName());
                assertEquals(txId, ((MigrationProtocolMessages) back).transactionId());
            }
        });
    }

    /** Field-level round-trip for the richest subtype (PrepareRequest carries token + snapshot). */
    @Test
    void prepareRequestPreservesTokenAndSnapshotControlFields() {
        var original = new MigrationProtocolMessages.PrepareRequest(txId, token(), snapshot(), sourceId, destId, ts);
        var back = (MigrationProtocolMessages.PrepareRequest)
            MessageConverter.fromTransport(MessageConverter.toTransport(original));

        assertEquals(txId, back.transactionId());
        assertEquals(sourceId, back.sourceId());
        assertEquals(destId, back.destId());
        assertEquals(ts, back.timestamp());

        assertNotNull(back.idempotencyToken());
        assertEquals("entity-7", back.idempotencyToken().entityId());
        assertEquals(sourceId, back.idempotencyToken().sourceProcessId());
        assertEquals(destId, back.idempotencyToken().destProcessId());

        assertNotNull(back.entitySnapshot());
        assertEquals("entity-7", back.entitySnapshot().entityId());
        assertEquals(1.0, back.entitySnapshot().position().getX(), 1e-9);
        assertEquals("payload-content", back.entitySnapshot().content());
        assertEquals(5L, back.entitySnapshot().epoch());
        assertEquals(9L, back.entitySnapshot().version());
    }

    /**
     * The serialized 2PC wire object must survive the NARROW VonTransportFilter allow-list. This is
     * the core RDR-004 hygiene assertion: no rich domain type is admitted to deserialization, only
     * the primitive-only TransportVonMessage + TransportMigrationMessage carriers.
     */
    @Test
    void wireObjectSurvivesNarrowDeserializationAllowList() throws Exception {
        var filter = ObjectInputFilter.Config.createFilter(FILTER_PATTERN);

        for (var original : allSubtypes()) {
            var transport = MessageConverter.toTransport(original);

            var baos = new ByteArrayOutputStream();
            try (var oos = new ObjectOutputStream(baos)) {
                oos.writeObject(transport);
            }

            try (var ois = new ObjectInputStream(new ByteArrayInputStream(baos.toByteArray()))) {
                ois.setObjectInputFilter(filter);
                var read = ois.readObject();   // must NOT be rejected by the narrow filter
                assertInstanceOf(TransportVonMessage.class, read);
                var roundTripped = MessageConverter.fromTransport((TransportVonMessage) read);
                assertEquals(original.getClass(), roundTripped.getClass());
            }
        }
    }

    /**
     * Rich domain types must never reach the wire. Two layers of defence:
     * (1) the domain types are not even {@link java.io.Serializable} (they cannot be serialized), and
     * (2) even were a serializable look-alike attempted, the narrow allow-list rejects unknown classes
     *     ({@code String} is on the list and round-trips; a synthetic non-listed type is rejected).
     */
    @Test
    void richDomainTypesAreNotOnTheWire() throws Exception {
        // Defence (1): the decomposed domain types are not Serializable, so they cannot be written.
        assertTrue(!(java.io.Serializable.class.isAssignableFrom(IdempotencyToken.class)),
                   "IdempotencyToken must not be Serializable (kept off the wire)");
        assertTrue(!(java.io.Serializable.class.isAssignableFrom(EntitySnapshot.class)),
                   "EntitySnapshot must not be Serializable (kept off the wire)");

        // Defence (2): the narrow allow-list rejects a class that is not an enumerated wire type.
        var filter = ObjectInputFilter.Config.createFilter(FILTER_PATTERN);
        var baos = new ByteArrayOutputStream();
        try (var oos = new ObjectOutputStream(baos)) {
            oos.writeObject(new java.util.PriorityQueue<String>());  // gadget-class collection, not on the list
        }
        try (var ois = new ObjectInputStream(new ByteArrayInputStream(baos.toByteArray()))) {
            ois.setObjectInputFilter(filter);
            boolean rejected = false;
            try {
                ois.readObject();
            } catch (java.io.InvalidClassException e) {
                rejected = true;
            }
            assertTrue(rejected, "Non-wire type (PriorityQueue) must be rejected by the narrow allow-list");
        }
    }
}
