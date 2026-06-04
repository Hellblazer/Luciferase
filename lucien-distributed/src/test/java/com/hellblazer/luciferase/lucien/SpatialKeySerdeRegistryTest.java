/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.forest.ghost.grpc.MortonKeySerde;
import com.hellblazer.luciferase.lucien.forest.ghost.grpc.PrismKeySerde;
import com.hellblazer.luciferase.lucien.forest.ghost.grpc.ProtobufConverters;
import com.hellblazer.luciferase.lucien.forest.ghost.grpc.TetreeKeySerde;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.tetree.CompactTetreeKey;
import com.hellblazer.luciferase.lucien.tetree.ExtendedTetreeKey;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link SpatialKeySerdeRegistry} and the wire-level dispatch
 * exposed by {@link ProtobufConverters} (Luciferase-546).
 * <p>
 * Built-in serdes ({@link MortonKeySerde}, {@link TetreeKeySerde}) round-trip
 * through the registry. A {@code FakeKey} is registered at test time to
 * exercise the extension path — demonstrating that adding a new SpatialKey
 * type requires no edits to {@code SpatialKey.java},
 * {@code ProtobufConverters.java}, or {@code ghost.proto}.
 *
 * @author hal.hildebrand
 */
public class SpatialKeySerdeRegistryTest {

    static {
        // Force registry class-init so built-in serdes register before any test runs.
        SpatialKeySerdeRegistry.forTypeId("morton");
        // Register the FakeKey serde once (test is idempotent — registry permits
        // re-registering the same instance).
        SpatialKeySerdeRegistry.register(FakeKeySerde.INSTANCE);
    }

    @Test
    void builtinMortonKeyRoundTripsThroughRegistry() {
        var original = new MortonKey(0xC0FFEE_AB_CDL, (byte) 7);
        var envelope = ProtobufConverters.spatialKeyToProtobuf(original);
        assertEquals("morton", envelope.getTypeId(), "envelope must carry the morton type_id");

        var roundtrip = ProtobufConverters.spatialKeyFromProtobuf(envelope);
        assertInstanceOf(MortonKey.class, roundtrip);
        assertEquals(original, roundtrip);
    }

    @Test
    void builtinCompactTetreeKeyRoundTripsThroughRegistry() {
        var original = new CompactTetreeKey((byte) 5, 0x12345_6789_ABCDEL);
        var envelope = ProtobufConverters.spatialKeyToProtobuf(original);
        assertEquals("tetree", envelope.getTypeId());

        var roundtrip = ProtobufConverters.spatialKeyFromProtobuf(envelope);
        assertInstanceOf(CompactTetreeKey.class, roundtrip,
                         "level <= 10 must deserialise as CompactTetreeKey");
        assertEquals(original, roundtrip);
    }

    @Test
    void builtinExtendedTetreeKeyRoundTripsThroughRegistry() {
        var original = new ExtendedTetreeKey((byte) 15, 0x1111_2222_3333_4444L, 0x5555_6666_7777_8888L);
        var envelope = ProtobufConverters.spatialKeyToProtobuf(original);
        assertEquals("tetree", envelope.getTypeId());

        var roundtrip = ProtobufConverters.spatialKeyFromProtobuf(envelope);
        assertInstanceOf(ExtendedTetreeKey.class, roundtrip,
                         "level > 10 must deserialise as ExtendedTetreeKey");
        assertEquals(original, roundtrip);
    }

    // ── TetreeKeySerde direct round-trip and guard tests (Luciferase-7wzml.88) ──

    @Test
    void tetreeKeySerdeCompactRoundTrip() {
        var serde = new TetreeKeySerde();
        var original = new CompactTetreeKey((byte) 8, 0xDEAD_BEEF_CAFE_1234L);
        var bytes = serde.serialize(original);
        var roundtrip = serde.deserialize(bytes);
        assertInstanceOf(CompactTetreeKey.class, roundtrip, "level<=10 must deserialise as CompactTetreeKey");
        assertEquals(original, roundtrip);
    }

    @Test
    void tetreeKeySerdeExtendedRoundTrip() {
        var serde = new TetreeKeySerde();
        var original = new ExtendedTetreeKey((byte) 11, 0xAAAA_BBBB_CCCC_DDDDL, 0x1111_2222_3333_4444L);
        var bytes = serde.serialize(original);
        var roundtrip = serde.deserialize(bytes);
        assertInstanceOf(ExtendedTetreeKey.class, roundtrip, "level>10 must deserialise as ExtendedTetreeKey");
        assertEquals(original, roundtrip);
    }

    @Test
    void tetreeKeySerdeCompactWithNonZeroHighThrows() {
        // Craft a proto payload with level<=10 but high!=0 — simulates a future or
        // buggy encoder sending non-zero high bits for a compact key.
        var malformed = com.hellblazer.luciferase.lucien.forest.ghost.proto.TetreeKey.newBuilder()
            .setLow(0x1234_5678_9ABC_DEF0L)
            .setHigh(0xFFFF_FFFF_FFFF_FFFFL)  // non-zero: must be rejected
            .setLevel(5)                        // level<=10 → compact path
            .build()
            .toByteArray();
        var serde = new TetreeKeySerde();
        var thrown = assertThrows(IllegalArgumentException.class, () -> serde.deserialize(malformed));
        assertNotNull(thrown.getMessage());
        assertEquals(true, thrown.getMessage().contains("high bits"),
                     "exception must mention 'high bits': " + thrown.getMessage());
    }

    @Test
    void unknownTypeIdThrowsMeaningfulMessage() {
        var envelope = com.hellblazer.luciferase.lucien.forest.ghost.proto.SpatialKey.newBuilder()
            .setTypeId("not-registered")
            .setPayload(com.google.protobuf.ByteString.copyFrom(new byte[] {1, 2, 3}))
            .build();
        var thrown = assertThrows(IllegalArgumentException.class,
                                  () -> ProtobufConverters.spatialKeyFromProtobuf(envelope));
        assertNotNull(thrown.getMessage());
        assertEquals(true, thrown.getMessage().contains("not-registered"),
                     "exception must name the unrecognised type_id");
    }

    @Test
    void emptyTypeIdThrowsMeaningfulMessage() {
        var envelope = com.hellblazer.luciferase.lucien.forest.ghost.proto.SpatialKey.newBuilder().build();
        assertThrows(IllegalArgumentException.class,
                     () -> ProtobufConverters.spatialKeyFromProtobuf(envelope),
                     "an envelope with no type_id must be rejected");
    }

    @Test
    void registryLooksUpByConcreteSubclass() {
        // TetreeKeySerde is registered against the TetreeKey base; the registry's
        // class-hierarchy walk must route both CompactTetreeKey and
        // ExtendedTetreeKey to it.
        var serdeForCompact = SpatialKeySerdeRegistry.forKey(new CompactTetreeKey((byte) 3, 42L));
        var serdeForExtended = SpatialKeySerdeRegistry.forKey(
            new ExtendedTetreeKey((byte) 12, 1L, 2L));
        assertSame(serdeForCompact, serdeForExtended,
                   "concrete subclasses must route to the same base-registered serde");
        assertEquals("tetree", serdeForCompact.typeId());
    }

    @Test
    void newKeyTypeRegistersAndRoundTripsWithoutEditingSpatialKeyOrProto() {
        // ACCEPTANCE TEST (Luciferase-546): a fake key type, declared entirely in
        // test code, registers via the SPI and round-trips through the dispatcher
        // without any edits to SpatialKey.java, ProtobufConverters.java, or
        // ghost.proto.
        var original = new FakeKey(0xDEAD_BEEFL, (byte) 4);
        var envelope = ProtobufConverters.spatialKeyToProtobuf(original);
        assertEquals(FakeKeySerde.TYPE_ID, envelope.getTypeId());

        var roundtrip = ProtobufConverters.spatialKeyFromProtobuf(envelope);
        assertInstanceOf(FakeKey.class, roundtrip);
        assertEquals(original, roundtrip);
    }

    @Test
    void duplicateTypeIdRegistrationConflictThrows() {
        // Registering a *different* serde under an already-claimed type_id
        // (without it being the same singleton) must throw.
        var conflict = new SpatialKeySerde<FakeKey>() {
            @Override public String         typeId()    { return FakeKeySerde.TYPE_ID; }
            @Override public Class<FakeKey> keyClass()  { return FakeKey.class; }
            @Override public byte[] serialize(FakeKey key)    { return new byte[0]; }
            @Override public FakeKey deserialize(byte[] payload) { return null; }
        };
        assertThrows(IllegalStateException.class,
                     () -> SpatialKeySerdeRegistry.register(conflict));
    }

    @Test
    void serviceLoaderDiscoversBuiltInSerdes() {
        // The registry's static initializer discovers providers via ServiceLoader
        // (META-INF/services/...SpatialKeySerde) and registers the instances it creates.
        var morton = SpatialKeySerdeRegistry.forTypeId("morton");
        var tetree = SpatialKeySerdeRegistry.forTypeId("tetree");
        var prism = SpatialKeySerdeRegistry.forTypeId("prism");
        assertInstanceOf(MortonKeySerde.class, morton, "ServiceLoader must register the built-in MortonKeySerde");
        assertInstanceOf(TetreeKeySerde.class, tetree, "ServiceLoader must register the built-in TetreeKeySerde");
        assertInstanceOf(PrismKeySerde.class, prism, "ServiceLoader must register the built-in PrismKeySerde");
        assertEquals("morton", morton.typeId());
        assertEquals("tetree", tetree.typeId());
        assertEquals("prism", prism.typeId());
    }

    // ============================================================
    // Test fixtures: a minimal SpatialKey + its serde
    // ============================================================

    /** Trivial SpatialKey for testing the extension path. */
    private record FakeKey(long index, byte level) implements SpatialKey<FakeKey> {
        @Override public byte    getLevel()             { return level; }
        @Override public FakeKey parent()               { return level == 0 ? null : new FakeKey(index / 2, (byte) (level - 1)); }
        @Override public FakeKey root()                 { return new FakeKey(0, (byte) 0); }
        @Override public int     compareTo(FakeKey o)   { return Long.compare(index, o.index); }
    }

    /** Serde for {@link FakeKey} demonstrating the extension SPI. */
    private static final class FakeKeySerde implements SpatialKeySerde<FakeKey> {
        static final         String       TYPE_ID  = "test.fake";
        static final         FakeKeySerde INSTANCE = new FakeKeySerde();

        @Override public String         typeId()   { return TYPE_ID; }
        @Override public Class<FakeKey> keyClass() { return FakeKey.class; }

        @Override
        public byte[] serialize(FakeKey key) {
            return ByteBuffer.allocate(Long.BYTES + Byte.BYTES).putLong(key.index()).put(key.level()).array();
        }

        @Override
        public FakeKey deserialize(byte[] payload) {
            var bb = ByteBuffer.wrap(payload);
            return new FakeKey(bb.getLong(), bb.get());
        }
    }
}
