/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.forest.ghost.grpc;

import com.google.protobuf.InvalidProtocolBufferException;
import com.hellblazer.luciferase.lucien.SpatialKeySerde;
import com.hellblazer.luciferase.lucien.tetree.CompactTetreeKey;
import com.hellblazer.luciferase.lucien.tetree.ExtendedTetreeKey;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;

/**
 * Built-in {@link SpatialKeySerde} for {@link TetreeKey} and its concrete
 * subclasses ({@link CompactTetreeKey}, {@link ExtendedTetreeKey})
 * (Luciferase-546).
 * <p>
 * Wire format: the existing {@code ghost.proto TetreeKey} message
 * (low + high + level) marshalled to bytes. Discriminator: {@code "tetree"}.
 * <p>
 * <b>Concrete-class dispatch.</b> On deserialise, {@code level <= 10} produces
 * a {@link CompactTetreeKey}; higher levels produce an
 * {@link ExtendedTetreeKey}. This preserves the level-based dispatch the
 * legacy {@code ProtobufConverters.createTetreeKey} performed; the boundary
 * is intentionally inclusive at level 10 to keep wire-compatible behaviour
 * for callers that have round-tripped existing payloads.
 * <p>
 * Registered against the {@link TetreeKey} abstract base; the
 * {@code SpatialKeySerdeRegistry} walks the class hierarchy at lookup time so
 * both compact and extended runtime instances route to this serde.
 * <p>
 * Discovered via {@link java.util.ServiceLoader} (declared in
 * {@code META-INF/services/com.hellblazer.luciferase.lucien.SpatialKeySerde}) and
 * registered by {@code SpatialKeySerdeRegistry} from its static initialiser.
 *
 * @author hal.hildebrand
 */
public final class TetreeKeySerde implements SpatialKeySerde<TetreeKey<?>> {

    public static final String TYPE_ID = "tetree";

    /** Inclusive level boundary below which {@link CompactTetreeKey} is the natural concrete type. */
    private static final int COMPACT_LEVEL_MAX = 10;

    /**
     * Public no-arg constructor required by {@link java.util.ServiceLoader} on the classpath
     * (the {@code META-INF/services} mechanism instantiates each provider via its no-arg
     * constructor). The serde is stateless.
     */
    public TetreeKeySerde() {
    }

    @Override
    public String typeId() {
        return TYPE_ID;
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Class<TetreeKey<?>> keyClass() {
        // Raw cast: Class literals for parameterised types are not expressible in Java.
        return (Class) TetreeKey.class;
    }

    @Override
    public byte[] serialize(TetreeKey<?> key) {
        return com.hellblazer.luciferase.lucien.forest.ghost.proto.TetreeKey.newBuilder()
            .setLow(key.getLowBits())
            .setHigh(key.getHighBits())
            .setLevel(key.getLevel())
            .build()
            .toByteArray();
    }

    @Override
    public TetreeKey<?> deserialize(byte[] payload) {
        try {
            var proto = com.hellblazer.luciferase.lucien.forest.ghost.proto.TetreeKey.parseFrom(payload);
            var level = (byte) proto.getLevel();
            return level <= COMPACT_LEVEL_MAX
                ? new CompactTetreeKey(level, proto.getLow())
                : new ExtendedTetreeKey(level, proto.getLow(), proto.getHigh());
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalArgumentException("TetreeKeySerde payload is not a valid proto TetreeKey", e);
        }
    }
}
