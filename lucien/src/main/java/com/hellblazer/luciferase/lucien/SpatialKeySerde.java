/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien;

/**
 * Service provider interface (SPI) for serialising {@link SpatialKey}
 * implementations to and from the wire-level protobuf envelope.
 * <p>
 * Each {@code SpatialKey} implementation supplies a singleton {@code Serde} that
 * registers itself with {@link SpatialKeySerdeRegistry}; the central dispatcher
 * in {@code ProtobufConverters} then routes serialisation by {@link #typeId()}
 * without an {@code instanceof} switch. Adding a new key type requires only
 * (a) authoring a {@code Serde}, (b) registering it. No edits to
 * {@code SpatialKey.java}, {@code ProtobufConverters.java}, or
 * {@code ghost.proto} are needed (Luciferase-546).
 *
 * @param <K> the concrete SpatialKey type this serde handles
 * @author hal.hildebrand
 */
public interface SpatialKeySerde<K extends SpatialKey<K>> {

    /**
     * The discriminator string written to the {@code SpatialKey.type_id} proto
     * field. Must be unique across all registered serdes. Conventional values
     * are short lower-case names ("morton", "tetree"). Treat as a stable wire
     * identifier; do not rename casually.
     */
    String typeId();

    /**
     * The concrete key class this serde handles. Used by
     * {@link SpatialKeySerdeRegistry#forKey(SpatialKey)} for outbound dispatch.
     * For key hierarchies (e.g. {@code TetreeKey} → {@code CompactTetreeKey},
     * {@code ExtendedTetreeKey}), return the common abstract base; the registry
     * walks the class hierarchy at lookup time.
     */
    Class<K> keyClass();

    /**
     * Serialise the key to opaque bytes for the {@code SpatialKey.payload}
     * proto field. Format is private to this serde — typically a protobuf
     * message body, but any deterministic byte encoding works as long as
     * {@link #deserialize(byte[])} can round-trip it.
     */
    byte[] serialize(K key);

    /**
     * Inverse of {@link #serialize(SpatialKey)}.
     */
    K deserialize(byte[] payload);
}
