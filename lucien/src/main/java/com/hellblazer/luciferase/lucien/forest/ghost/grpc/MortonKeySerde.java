/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.forest.ghost.grpc;

import com.google.protobuf.InvalidProtocolBufferException;
import com.hellblazer.luciferase.lucien.SpatialKeySerde;
import com.hellblazer.luciferase.lucien.octree.MortonKey;

/**
 * Built-in {@link SpatialKeySerde} for {@link MortonKey} (Luciferase-546).
 * <p>
 * Wire format: the existing {@code ghost.proto MortonKey} message
 * (morton_code + level) marshalled to bytes. Discriminator: {@code "morton"}.
 * <p>
 * Discovered via {@link java.util.ServiceLoader} (declared in
 * {@code META-INF/services/com.hellblazer.luciferase.lucien.SpatialKeySerde}) and
 * registered by {@code SpatialKeySerdeRegistry} from its static initialiser.
 *
 * @author hal.hildebrand
 */
public final class MortonKeySerde implements SpatialKeySerde<MortonKey> {

    public static final String TYPE_ID = "morton";

    /**
     * Public no-arg constructor required by {@link java.util.ServiceLoader} on the classpath
     * (the {@code META-INF/services} mechanism instantiates each provider via its no-arg
     * constructor). The serde is stateless.
     */
    public MortonKeySerde() {
    }

    @Override
    public String typeId() {
        return TYPE_ID;
    }

    @Override
    public Class<MortonKey> keyClass() {
        return MortonKey.class;
    }

    @Override
    public byte[] serialize(MortonKey key) {
        return com.hellblazer.luciferase.lucien.forest.ghost.proto.MortonKey.newBuilder()
            .setMortonCode(key.getMortonCode())
            .setLevel(key.getLevel())
            .build()
            .toByteArray();
    }

    @Override
    public MortonKey deserialize(byte[] payload) {
        try {
            var proto = com.hellblazer.luciferase.lucien.forest.ghost.proto.MortonKey.parseFrom(payload);
            return new MortonKey(proto.getMortonCode(), (byte) proto.getLevel());
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalArgumentException("MortonKeySerde payload is not a valid proto MortonKey", e);
        }
    }
}
