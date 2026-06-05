/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.forest.ghost;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Luciferase-7wzml.53: STRING_SERIALIZER must round-trip empty string without collapsing "" -> null.
 *
 * @author hal.hildebrand
 */
class ContentSerializerTest {

    @Test
    void nullRoundTrips() throws Exception {
        var bytes = ContentSerializer.STRING_SERIALIZER.serialize(null);
        assertNull(ContentSerializer.STRING_SERIALIZER.deserialize(bytes),
                   "null must deserialize back to null");
    }

    @Test
    void emptyStringRoundTrips() throws Exception {
        // Bug: "" serializes to new byte[0]; deserialize(new byte[0]) returns null,
        // so "" -> null is a value-changing round-trip. Expects "".equals("") after fix.
        var bytes = ContentSerializer.STRING_SERIALIZER.serialize("");
        var result = ContentSerializer.STRING_SERIALIZER.deserialize(bytes);
        assertEquals("", result, "empty string must round-trip as \"\" not null (Luciferase-7wzml.53)");
    }

    @Test
    void nonEmptyRoundTrips() throws Exception {
        var original = "hello ghost";
        var bytes = ContentSerializer.STRING_SERIALIZER.serialize(original);
        assertEquals(original, ContentSerializer.STRING_SERIALIZER.deserialize(bytes),
                     "non-empty string must round-trip unchanged");
    }

    @Test
    void nullBytesDeserializeToNull() throws Exception {
        assertNull(ContentSerializer.STRING_SERIALIZER.deserialize(null),
                   "null byte array must deserialize to null");
    }

    @Test
    void nullAndEmptyAreDistinguishable() throws Exception {
        // After the fix, serialize(null) and serialize("") must produce distinguishable bytes.
        var nullBytes = ContentSerializer.STRING_SERIALIZER.serialize(null);
        var emptyBytes = ContentSerializer.STRING_SERIALIZER.serialize("");
        assertFalse(java.util.Arrays.equals(nullBytes, emptyBytes),
                    "serialized null and serialized \"\" must not be identical (presence-flag required)");
    }
}
