/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.forest.ghost;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Luciferase-7wzml.53: STRING_SERIALIZER wire-format contract.
 *
 * <p>Disposition (doc-only): the serializer uses a frameless wire format — the serialized bytes ARE
 * the UTF-8 content, copied verbatim into the gRPC ghost {@code content} field. As a documented
 * limitation, {@code null} and {@code ""} are indistinguishable (both -> empty bytes -> null);
 * adding a presence-flag byte would change the on-wire representation of every ghost string and
 * break raw-bytes consumers (see GhostCommunicationIntegrationTest, which reads content as raw UTF-8)
 * and cross-version peers. These tests pin the frameless contract so it is not silently re-framed.
 *
 * @author hal.hildebrand
 */
class ContentSerializerTest {

    @Test
    void nullRoundTripsToNull() throws Exception {
        var bytes = ContentSerializer.STRING_SERIALIZER.serialize(null);
        assertNull(ContentSerializer.STRING_SERIALIZER.deserialize(bytes), "null must deserialize back to null");
    }

    @Test
    void nonEmptyRoundTripsExactly() throws Exception {
        var original = "rank0-tree1-content";
        var bytes = ContentSerializer.STRING_SERIALIZER.serialize(original);
        assertEquals(original, ContentSerializer.STRING_SERIALIZER.deserialize(bytes),
                     "non-empty string must round-trip unchanged");
    }

    @Test
    void serializedBytesAreFramelessUtf8() throws Exception {
        // Contract: the serialized bytes are the raw UTF-8 content with NO framing byte, because the
        // gRPC transport copies them verbatim into the protobuf content field and consumers read them
        // as the literal payload. Re-introducing a presence flag would break that contract.
        var original = "ghost";
        var bytes = ContentSerializer.STRING_SERIALIZER.serialize(original);
        assertArrayEquals(original.getBytes(StandardCharsets.UTF_8), bytes,
                          "serialized form must be frameless raw UTF-8 (Luciferase-7wzml.53)");
    }

    @Test
    void nullBytesDeserializeToNull() throws Exception {
        assertNull(ContentSerializer.STRING_SERIALIZER.deserialize(null), "null byte array must deserialize to null");
    }

    @Test
    void emptyStringAndNullAreIndistinguishableByDesign() throws Exception {
        // Documented P3 limitation: "" and null both collapse to empty bytes / null. This test pins the
        // limitation so any future change that distinguishes them (and thus re-frames the wire format)
        // is a deliberate, reviewed decision rather than an accident.
        var nullBytes = ContentSerializer.STRING_SERIALIZER.serialize(null);
        var emptyBytes = ContentSerializer.STRING_SERIALIZER.serialize("");
        assertArrayEquals(nullBytes, emptyBytes, "serialized null and \"\" are intentionally identical (frameless)");
        assertNull(ContentSerializer.STRING_SERIALIZER.deserialize(emptyBytes), "\"\" deserializes to null (documented)");
    }
}
