/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.forest.ghost.grpc;

import com.hellblazer.luciferase.lucien.SpatialKeySerde;
import com.hellblazer.luciferase.lucien.prism.Line;
import com.hellblazer.luciferase.lucien.prism.PrismKey;
import com.hellblazer.luciferase.lucien.prism.Triangle;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

/**
 * Built-in {@link SpatialKeySerde} for {@link PrismKey} (RDR-009 P7, Luciferase-3hw). Discriminator:
 * {@code "prism"}.
 *
 * <p><b>Versioned wire format.</b> Unlike the Morton/Tetree serdes (which marshal an existing
 * {@code ghost.proto} message), this serde uses a self-contained {@code ByteBuffer} encoding whose
 * <em>first byte is an explicit format version</em>. There is intentionally no {@code ghost.proto
 * PrismKey} message: PrismKey is the first key type added after the SPI generalised serialization
 * (Luciferase-546), and the leading version byte plays the role proto field-tags would (a future
 * field bumps the version). The version byte is first-class because the in-memory prism key shape
 * itself changed during RDR-009 — the {@code half} bit (S0 lower-right {@code y<=x} / S1 upper-left,
 * P3) joined the triangle, and {@code consecutiveIndex} became a per-level value (P2) — so any
 * future on-wire encoding must be unambiguously distinguishable.</p>
 *
 * <pre>
 *   v1 (current, two-prism):  [1][level:1][type:1][half:1][x:4][y:4][z:4]   = 16 bytes
 *   v0 (legacy, S0-only):     [0][level:1][type:1]        [x:4][y:4][z:4]   = 15 bytes
 * </pre>
 *
 * <p>All integers are big-endian. {@code level} is shared by the triangle and line (PrismKey enforces
 * the level-sync invariant). The triangle's auxiliary {@code n} coordinate is not stored — it is the
 * derived value {@code min(x, y)} (RDR-009 P2) and is recomputed on deserialize.</p>
 *
 * <p><b>Read-time migration.</b> {@link #serialize(PrismKey)} always writes v1. {@link
 * #deserialize(byte[])} reads the version byte and upgrades in place: a v0 payload — the lower-half
 * format a pre-P3 serde <em>would have</em> produced — has no {@code half} field and decodes to an
 * S0 key ({@code half == 0}) whose region matches the original. Note that v0 is a <b>forward-compat
 * guard, not a real historical format</b>: no PrismKey serde existed before this phase, so no v0
 * payload was ever emitted by any process. It is defined so the version discriminator is internally
 * consistent and the upgrade contract is explicit if a v0 encoding is ever introduced. This is a
 * read-time shim, NOT a bulk migration tool: there is no standalone re-encoder (RDR-009 Option B
 * trigger explicitly avoided). An unrecognised version byte is rejected with a meaningful error
 * (forward-compatibility: a reader does not silently misinterpret a newer encoding).</p>
 *
 * <p>Discovered via {@link java.util.ServiceLoader} (declared in
 * {@code META-INF/services/com.hellblazer.luciferase.lucien.SpatialKeySerde}) and registered by
 * {@code SpatialKeySerdeRegistry} from its static initialiser.</p>
 *
 * @author hal.hildebrand
 */
public final class PrismKeySerde implements SpatialKeySerde<PrismKey> {

    public static final String TYPE_ID = "prism";

    /** Current wire format: the two-prism shape carrying the {@code half} bit. */
    static final byte VERSION_TWO_PRISM = 1;

    /** Legacy lower-half (S0-only) format with no {@code half} field; upgraded on read to {@code half=0}. */
    static final byte VERSION_LEGACY_S0 = 0;

    /**
     * Public no-arg constructor required by {@link java.util.ServiceLoader} on the classpath
     * (the {@code META-INF/services} mechanism instantiates each provider via its no-arg
     * constructor). The serde is stateless.
     */
    public PrismKeySerde() {
    }

    @Override
    public String typeId() {
        return TYPE_ID;
    }

    @Override
    public Class<PrismKey> keyClass() {
        return PrismKey.class;
    }

    @Override
    public byte[] serialize(PrismKey key) {
        var triangle = key.getTriangle();
        var line = key.getLine();
        return ByteBuffer.allocate(16)
            .put(VERSION_TWO_PRISM)
            .put(triangle.getLevel())
            .put(triangle.getType())
            .put(triangle.getHalf())
            .putInt(triangle.getX())
            .putInt(triangle.getY())
            .putInt(line.getZ())
            .array();
    }

    @Override
    public PrismKey deserialize(byte[] payload) {
        var bb = ByteBuffer.wrap(payload);
        try {
            var version = bb.get();
            return switch (version) {
                case VERSION_TWO_PRISM -> {
                    int level = bb.get();
                    int type = bb.get();
                    int half = bb.get();
                    int x = bb.getInt();
                    int y = bb.getInt();
                    int z = bb.getInt();
                    yield new PrismKey(new Triangle(level, type, x, y, Math.min(x, y), half), new Line(level, z));
                }
                case VERSION_LEGACY_S0 -> {
                    // Lower-half (S0-only) legacy format: no half field — upgrade to half=0 (S0).
                    int level = bb.get();
                    int type = bb.get();
                    int x = bb.getInt();
                    int y = bb.getInt();
                    int z = bb.getInt();
                    yield new PrismKey(new Triangle(level, type, x, y, Math.min(x, y), 0), new Line(level, z));
                }
                default -> throw new IllegalArgumentException(
                    "PrismKeySerde: unrecognised format version " + (version & 0xFF)
                    + " (known: 0=legacy S0-only, 1=two-prism)");
            };
        } catch (BufferUnderflowException e) {
            throw new IllegalArgumentException("PrismKeySerde: truncated payload (" + payload.length + " bytes)", e);
        }
    }
}
