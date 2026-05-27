/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.forest.ghost.grpc;

import com.hellblazer.luciferase.lucien.SpatialKeySerdeRegistry;
import com.hellblazer.luciferase.lucien.prism.Line;
import com.hellblazer.luciferase.lucien.prism.PrismKey;
import com.hellblazer.luciferase.lucien.prism.Triangle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RDR-009 P7 (Luciferase-3hw): versioned {@link PrismKeySerde} serialization + read-time migration.
 *
 * <p>PrismKey was never serialized before this phase (no serde existed; Prism did not participate in
 * the distributed ghost layer). This phase adds a first-class versioned serde so Prism CAN join ghost
 * exchange, with a format version byte from the outset so the two-prism key shape (the {@code half}
 * bit + per-level consecutiveIndex introduced in P2/P3) is forward-evolvable. The version byte also
 * defines a read-time upgrade path for the hypothetical legacy lower-half (S0-only) format that a
 * pre-P3 serde would have produced (version 0): such a payload deserializes to a valid S0 key
 * ({@code half == 0}) whose decoded region matches the original.</p>
 *
 * @author hal.hildebrand
 */
class PrismKeyMigrationTest {

    private static final PrismKeySerde SERDE = new PrismKeySerde();

    private static PrismKey key(int level, int type, int x, int y, int half, int z) {
        return new PrismKey(new Triangle(level, type, x, y, half), new Line(level, z));
    }

    @Test
    @DisplayName("(round-trip) v1 serialize/deserialize is identity for S0 and S1 keys at several levels")
    void v1RoundTripIdentity() {
        var keys = new PrismKey[] {
            new PrismKey(new Triangle(0, 0, 0, 0, 0), new Line(0, 0)),       // S0 root
            PrismKey.createRootS1(),                                            // S1 root
            key(2, 0, 3, 1, 0, 2),                                             // S0, type 0
            key(2, 1, 3, 2, 0, 3),                                             // S0, type 1
            key(5, 0, 20, 7, 1, 11),                                          // S1
            key(10, 1, 600, 401, 1, 512),                                    // S1, deeper
            key(21, 0, 2_097_151, 2_097_150, 0, 2_097_151),                  // MAX_LEVEL, max coords (S0)
            key(21, 1, 2_097_150, 2_097_149, 1, 2_097_148)                   // MAX_LEVEL (S1)
        };
        for (var original : keys) {
            var bytes = SERDE.serialize(original);
            var roundtrip = SERDE.deserialize(bytes);
            assertEquals(original, roundtrip, "serde round-trip must be identity (incl. half): " + original);
            assertEquals(original.getTriangle().getHalf(), roundtrip.getTriangle().getHalf(),
                "the root half must survive the round-trip");
        }
    }

    @Test
    @DisplayName("(version byte) v1 payloads carry format version 1 as the leading byte")
    void v1CarriesVersionByte() {
        var bytes = SERDE.serialize(key(3, 0, 5, 2, 0, 4));
        assertEquals(1, bytes[0], "the first byte is the format version discriminator (v1)");
    }

    @Test
    @DisplayName("(forward-compat) a hypothetical v0 S0-only payload upgrades to a valid S0 (half=0) key matching the region")
    void v0HypotheticalLegacyFormatUpgradesToS0() {
        // v0 is a forward-compat guard, not a real historical format: no PrismKey serde existed
        // before this phase, so no v0 payload was ever emitted. This pins the documented upgrade
        // contract should a v0 encoding ever be introduced. Hand-craft the lower-half layout a
        // pre-P3 serde would have written: version 0, no half field (S0 implied).
        // Layout: [v0][level][type][x:int][y:int][z:int].
        int level = 4, type = 1, x = 9, y = 5, z = 6;
        var legacy = ByteBuffer.allocate(1 + 1 + 1 + 4 + 4 + 4)
            .put((byte) 0).put((byte) level).put((byte) type).putInt(x).putInt(y).putInt(z)
            .array();

        var upgraded = SERDE.deserialize(legacy);
        var expected = key(level, type, x, y, 0, z); // S0 (half=0)
        assertEquals(expected, upgraded, "legacy v0 payload must upgrade to the equivalent S0 two-prism key");
        assertEquals(0, upgraded.getTriangle().getHalf(), "a legacy S0-only key upgrades to half=0 (S0)");
    }

    @Test
    @DisplayName("(forward-compat) an unknown future version byte is rejected with a meaningful error")
    void unknownVersionRejected() {
        var future = ByteBuffer.allocate(8).put((byte) 99).put(new byte[7]).array();
        var thrown = assertThrows(IllegalArgumentException.class, () -> SERDE.deserialize(future));
        assertNotNull(thrown.getMessage());
        assertTrue(thrown.getMessage().contains("99") || thrown.getMessage().toLowerCase().contains("version"),
            "the error must identify the unrecognised format version: " + thrown.getMessage());
    }

    @Test
    @DisplayName("(robustness) a truncated payload (version byte present, body short) is rejected, not silently misread")
    void truncatedPayloadRejected() {
        // v1 needs 16 bytes; supply only the version byte + 3 of the expected 15 body bytes.
        var truncated = ByteBuffer.allocate(4).put((byte) 1).put(new byte[3]).array();
        var thrown = assertThrows(IllegalArgumentException.class, () -> SERDE.deserialize(truncated),
            "a truncated payload must throw, exercising the BufferUnderflow guard");
        assertTrue(thrown.getMessage().toLowerCase().contains("truncated"),
            "the error must indicate truncation: " + thrown.getMessage());
    }

    @Test
    @DisplayName("(registry) the prism serde is ServiceLoader-discovered and round-trips through the registry")
    void registeredAndRoundTripsThroughRegistry() {
        var serde = SpatialKeySerdeRegistry.forTypeId(PrismKeySerde.TYPE_ID);
        assertInstanceOf(PrismKeySerde.class, serde, "ServiceLoader must register the PrismKeySerde");
        assertEquals("prism", serde.typeId());

        var original = key(6, 0, 33, 17, 1, 40);
        var byKey = SpatialKeySerdeRegistry.forKey(original);
        assertInstanceOf(PrismKeySerde.class, byKey, "registry must route PrismKey instances to the prism serde");

        var envelope = ProtobufConverters.spatialKeyToProtobuf(original);
        assertEquals("prism", envelope.getTypeId(), "envelope must carry the prism type_id");
        var roundtrip = ProtobufConverters.spatialKeyFromProtobuf(envelope);
        assertInstanceOf(PrismKey.class, roundtrip);
        assertEquals(original, roundtrip, "registry/envelope round-trip must be identity");
    }
}
