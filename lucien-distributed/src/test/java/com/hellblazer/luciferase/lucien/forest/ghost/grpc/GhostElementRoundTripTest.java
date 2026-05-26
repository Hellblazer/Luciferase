/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.lucien.forest.ghost.grpc;

import com.google.protobuf.ByteString;
import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.UUIDEntityID;
import com.hellblazer.luciferase.lucien.forest.ghost.ContentSerializer;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostElement;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostLayer;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostType;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.tetree.CompactTetreeKey;
import com.hellblazer.luciferase.lucien.tetree.ExtendedTetreeKey;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip coverage for the relocated ghost-element protobuf conversion (Luciferase-5of). The conversion itself
 * (real deserialization via the {@code SpatialKeySerde} registry) landed with the lucien-distributed extraction
 * (RDR-007 PR-B); this test verifies it actually round-trips — {@code element -> protobuf -> element} preserving every
 * field — across both registered key types ({@link MortonKey}, {@link CompactTetreeKey}/{@link ExtendedTetreeKey})
 * and both entity-ID types ({@link LongEntityID}, {@link UUIDEntityID}). It also pins the failure contract the
 * {@code GrpcBalanceExchange} per-element skip-invalid path depends on: a content-deserialization failure surfaces as
 * {@link ContentSerializer.SerializationException}.
 *
 * @author hal.hildebrand
 */
class GhostElementRoundTripTest {

    private static final ContentSerializer<String> SERIALIZER = new ContentSerializer<>() {
        @Override
        public ByteString serialize(String content) {
            return ByteString.copyFrom(content, StandardCharsets.UTF_8);
        }

        @Override
        public String deserialize(ByteString bytes) {
            return bytes.toString(StandardCharsets.UTF_8);
        }

        @Override
        public String getContentType() {
            return "string";
        }
    };

    private static final Point3f POSITION = new Point3f(1.5f, -2.25f, 3.0f);

    @Test
    void mortonKeyWithLongIdRoundTrips() throws Exception {
        assertRoundTrips(new MortonKey(0xC0FFEE_AB_CDL, (byte) 7), new LongEntityID(42L), LongEntityID.class);
    }

    @Test
    void mortonKeyWithUuidIdRoundTrips() throws Exception {
        assertRoundTrips(new MortonKey(0x0000_1234_5678L, (byte) 5),
                         new UUIDEntityID(UUID.fromString("123e4567-e89b-12d3-a456-426614174000")),
                         UUIDEntityID.class);
    }

    @Test
    void compactTetreeKeyWithLongIdRoundTrips() throws Exception {
        // level <= 10 deserialises as CompactTetreeKey
        assertRoundTrips(new CompactTetreeKey((byte) 5, 0xABCDEL), new LongEntityID(7L), LongEntityID.class);
    }

    @Test
    void extendedTetreeKeyWithUuidIdRoundTrips() throws Exception {
        // level > 10 deserialises as ExtendedTetreeKey (128-bit tm-index)
        assertRoundTrips(new ExtendedTetreeKey((byte) 15, 0x1111_2222_3333_4444L, 0x5555_6666_7777_8888L),
                         new UUIDEntityID(UUID.fromString("00000000-0000-0000-0000-00000000beef")),
                         UUIDEntityID.class);
    }

    @Test
    void batchRoundTripsAllElements() throws Exception {
        var layer = new GhostLayer<MortonKey, LongEntityID, String>(GhostType.FACES);
        layer.addGhostElement(new GhostElement<>(new MortonKey(0x100L, (byte) 3), new LongEntityID(1L), "one",
                                                 POSITION, 3, 99L));
        layer.addGhostElement(new GhostElement<>(new MortonKey(0x200L, (byte) 3), new LongEntityID(2L), "two",
                                                 POSITION, 3, 99L));

        var batch = ProtobufConverters.ghostLayerToProtobufBatch(layer, 3, 99L, SERIALIZER);
        assertEquals(2, batch.getElementsCount());
        assertEquals(3, batch.getSourceRank());
        assertEquals(99L, batch.getSourceTreeId());

        var contents = batch.getElementsList().stream().map(p -> {
            try {
                return ProtobufConverters.<MortonKey, LongEntityID, String>ghostElementFromProtobuf(p, SERIALIZER,
                                                                                                    LongEntityID.class)
                                         .getContent();
            } catch (ContentSerializer.SerializationException e) {
                throw new AssertionError(e);
            }
        }).collect(Collectors.toSet());
        assertTrue(contents.containsAll(java.util.Set.of("one", "two")), "all batch elements survive the round trip");
    }

    @Test
    void malformedContentSurfacesSerializationException() throws Exception {
        // A valid proto (built with the working serializer) deserialised with a failing serializer must surface
        // SerializationException — the exact failure GrpcBalanceExchange's per-element skip-invalid path catches.
        var proto = ProtobufConverters.ghostElementToProtobuf(
            new GhostElement<>(new MortonKey(0x1L, (byte) 1), new LongEntityID(1L), "x", POSITION, 0, 0L), SERIALIZER);
        ContentSerializer<String> failing = new ContentSerializer<>() {
            @Override
            public ByteString serialize(String content) {
                return ByteString.EMPTY;
            }

            @Override
            public String deserialize(ByteString bytes) throws SerializationException {
                throw new SerializationException("simulated content corruption");
            }

            @Override
            public String getContentType() {
                return "failing";
            }
        };
        assertThrows(ContentSerializer.SerializationException.class,
                     () -> ProtobufConverters.ghostElementFromProtobuf(proto, failing, LongEntityID.class));
    }

    private static <K extends SpatialKey<K>, I extends EntityID> void assertRoundTrips(K key, I id, Class<I> idClass)
    throws Exception {
        var content = "payload-" + id;
        var element = new GhostElement<K, I, String>(key, id, content, POSITION, 3, 99L);

        var proto = ProtobufConverters.ghostElementToProtobuf(element, SERIALIZER);
        var back = ProtobufConverters.<K, I, String>ghostElementFromProtobuf(proto, SERIALIZER, idClass);

        assertEquals(key, back.getSpatialKey(), "spatialKey");
        assertEquals(id, back.getEntityId(), "entityId");
        assertEquals(content, back.getContent(), "content");
        assertEquals(POSITION, back.getPosition(), "position");
        assertEquals(3, back.getOwnerRank(), "ownerRank");
        assertEquals(99L, back.getGlobalTreeId(), "globalTreeId");
    }
}
