/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.pyramid;

import com.hellblazer.luciferase.lucien.SpatialKeySerde;

import java.nio.ByteBuffer;

/**
 * {@link SpatialKeySerde} for {@link PyramidKey} (RDR-010). Registers under type-id {@code "pyramid"}
 * and serialises the key as a fixed 17-byte payload: {@code level} (1 byte) followed by
 * {@code lowBits} and {@code highBits} (8 bytes each, big-endian). Register the singleton with
 * {@code SpatialKeySerdeRegistry.register(PyramidKeySerde.INSTANCE)} (or via the ServiceLoader SPI
 * in the transport module) before pyramid ghost exchange.
 *
 * @author hal.hildebrand
 */
public final class PyramidKeySerde implements SpatialKeySerde<PyramidKey> {

    public static final PyramidKeySerde INSTANCE = new PyramidKeySerde();

    private static final int PAYLOAD_BYTES = Byte.BYTES + 2 * Long.BYTES;

    private PyramidKeySerde() {
    }

    @Override
    public String typeId() {
        return "pyramid";
    }

    @Override
    public Class<PyramidKey> keyClass() {
        return PyramidKey.class;
    }

    @Override
    public byte[] serialize(PyramidKey key) {
        return ByteBuffer.allocate(PAYLOAD_BYTES)
                         .put(key.getLevel())
                         .putLong(key.getLowBits())
                         .putLong(key.getHighBits())
                         .array();
    }

    @Override
    public PyramidKey deserialize(byte[] payload) {
        if (payload == null || payload.length != PAYLOAD_BYTES) {
            throw new IllegalArgumentException(
            "PyramidKey payload must be " + PAYLOAD_BYTES + " bytes, got: "
            + (payload == null ? "null" : payload.length));
        }
        var buf = ByteBuffer.wrap(payload);
        byte level = buf.get();
        long low = buf.getLong();
        long high = buf.getLong();
        return new PyramidKey(level, low, high);
    }
}
