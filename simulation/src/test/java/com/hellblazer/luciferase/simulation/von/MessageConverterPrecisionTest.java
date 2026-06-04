/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * Licensed under AGPL v3.0. See LICENSE.
 */
package com.hellblazer.luciferase.simulation.von;

import org.junit.jupiter.api.Test;

import javax.vecmath.Point3d;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Regression test for Luciferase-0frcy.127: JoinRequest/Move coordinates are {@link Point3d}
 * (double precision). The wire type {@code TransportVonMessage} previously stored them as
 * {@code float}, silently truncating double&rarr;float&rarr;double on every round-trip. This perturbs
 * near-boundary spatial classification. The fix widens {@code posX/Y/Z} to double; this test pins the
 * full-precision round-trip and demonstrates that the old float path would have lost precision.
 *
 * @author hal.hildebrand
 */
class MessageConverterPrecisionTest {

    // A double that is NOT exactly representable as a float — round-tripping through float perturbs it.
    private static final double NON_FLOAT_X = 123.456789012345;
    private static final double NON_FLOAT_Y = 987.654321098765;
    private static final double NON_FLOAT_Z = 0.10000000000000009;

    @Test
    void joinRequestCoordinatesRoundTripWithoutPrecisionLoss() {
        var id = UUID.randomUUID();
        var original = new Message.JoinRequest(id, new Point3d(NON_FLOAT_X, NON_FLOAT_Y, NON_FLOAT_Z),
                                               null, 42L);

        var transport = MessageConverter.toTransport(original);
        var restored = (Message.JoinRequest) MessageConverter.fromTransport(transport);

        assertEquals(NON_FLOAT_X, restored.position().getX(), 0.0,
                     "X must round-trip at full double precision (Luciferase-0frcy.127)");
        assertEquals(NON_FLOAT_Y, restored.position().getY(), 0.0, "Y must round-trip at full precision");
        assertEquals(NON_FLOAT_Z, restored.position().getZ(), 0.0, "Z must round-trip at full precision");

        // Sanity: confirm these values truly would have been corrupted by the old float path.
        assertNotEquals(NON_FLOAT_X, (double) (float) NON_FLOAT_X,
                        "test fixture must use a value that float cannot represent exactly");
    }

    @Test
    void moveCoordinatesRoundTripWithoutPrecisionLoss() {
        var id = UUID.randomUUID();
        var original = new Message.Move(id, new Point3d(NON_FLOAT_X, NON_FLOAT_Y, NON_FLOAT_Z), null, 7L);

        var transport = MessageConverter.toTransport(original);
        var restored = (Message.Move) MessageConverter.fromTransport(transport);

        assertEquals(NON_FLOAT_X, restored.newPosition().getX(), 0.0,
                     "X must round-trip at full double precision (Luciferase-0frcy.127)");
        assertEquals(NON_FLOAT_Y, restored.newPosition().getY(), 0.0, "Y must round-trip at full precision");
        assertEquals(NON_FLOAT_Z, restored.newPosition().getZ(), 0.0, "Z must round-trip at full precision");
    }
}
