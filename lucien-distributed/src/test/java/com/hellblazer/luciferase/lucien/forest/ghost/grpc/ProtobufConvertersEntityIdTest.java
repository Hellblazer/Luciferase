/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.forest.ghost.grpc;

import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Luciferase-m2k3u: {@code createEntityId} threw {@code IllegalArgumentException} for an unsupported configured
 * EntityID class, which the per-element catch in {@code GhostServiceClient} swallowed — silently dropping the whole
 * batch (RDR-004 D3 / 7pias class). It now throws a dedicated {@link ProtobufConverters.UnsupportedEntityIdTypeException}
 * that the client re-throws so the misconfiguration surfaces loudly.
 *
 * @author hal.hildebrand
 */
class ProtobufConvertersEntityIdTest {

    /** A third EntityID type the converter does not support (only Long/UUID exist in production). */
    private static final class UnsupportedId implements EntityID {
        @Override public String toDebugString() { return "unsupported"; }
        @Override public int compareTo(EntityID o) { return 0; }
    }

    @Test
    void unsupportedEntityIdClassThrowsDedicatedException() {
        assertThrows(ProtobufConverters.UnsupportedEntityIdTypeException.class,
                     () -> ProtobufConverters.createEntityId("1", UnsupportedId.class),
                     "unsupported EntityID class must throw the dedicated, non-swallowed exception (Luciferase-m2k3u)");
    }

    @Test
    void supportedEntityIdClassesStillConvert() {
        assertEquals(new LongEntityID(5L), ProtobufConverters.createEntityId("5", LongEntityID.class),
                     "supported types must still convert");
    }
}
