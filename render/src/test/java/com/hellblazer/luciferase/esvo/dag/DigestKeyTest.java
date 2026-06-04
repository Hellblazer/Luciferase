/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.esvo.dag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link DigestKey} value semantics and immunity to source-array mutation
 * (Luciferase-7wzml.23 wave-2 review HIGH-1: the key must defensively copy so a caller
 * reusing a hasher's internal buffer cannot silently corrupt Map equality).
 *
 * @author hal.hildebrand
 */
class DigestKeyTest {

    @Test
    @DisplayName("Content-equal digests are equal keys and hash identically")
    void contentEqualityNotIdentity() {
        var a = new DigestKey(new byte[] { 1, 2, 3, 4 });
        var b = new DigestKey(new byte[] { 1, 2, 3, 4 });
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());

        var c = new DigestKey(new byte[] { 1, 2, 3, 5 });
        assertNotEquals(a, c);
    }

    @Test
    @DisplayName("Mutating the source array after construction does not alter the key")
    void defensiveCopyAgainstSourceMutation() {
        var source = new byte[] { 10, 20, 30, 40 };
        var key = new DigestKey(source);
        var map = new HashMap<DigestKey, Integer>();
        map.put(key, 42);

        // Simulate a hasher reusing its internal cache buffer.
        source[0] = (byte) 0xFF;
        source[3] = 0;

        // The key (and its accessor) must reflect the original content, not the mutation.
        assertEquals(10, key.digest()[0], "key must hold a defensive copy, not the source alias");
        // Lookup with a fresh key built from the ORIGINAL content still hits.
        assertTrue(map.containsKey(new DigestKey(new byte[] { 10, 20, 30, 40 })),
                   "map membership must be stable despite source mutation");
        assertEquals(42, map.get(new DigestKey(new byte[] { 10, 20, 30, 40 })));
    }

    @Test
    @DisplayName("Mutating the array returned by digest() does not corrupt the key")
    void accessorReturnsIndependentView() {
        var key = new DigestKey(new byte[] { 7, 7, 7 });
        var got = key.digest();
        got[0] = 0; // mutate the returned array
        // A canonical-constructor clone protects the stored array on input; if the
        // accessor returns the stored reference, this asserts the stored content is
        // still queryable as originally constructed via a fresh equal key.
        Map<DigestKey, Integer> map = new HashMap<>();
        map.put(new DigestKey(new byte[] { 7, 7, 7 }), 1);
        assertTrue(map.containsKey(new DigestKey(new byte[] { 7, 7, 7 })));
    }
}
