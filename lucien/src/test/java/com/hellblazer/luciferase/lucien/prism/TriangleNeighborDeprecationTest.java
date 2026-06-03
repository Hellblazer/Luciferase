/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.prism;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Luciferase-mfe6y: {@code Triangle.neighbors()/neighbor()} are coordinate-shift approximations inconsistent with
 * the t8code-faithful {@link Triangle#faceNeighbor(int)}. They are deprecated so callers are steered to
 * {@code faceNeighbor}; {@code faceNeighbor} itself must NOT be deprecated (it is the documented path).
 *
 * @author hal.hildebrand
 */
class TriangleNeighborDeprecationTest {

    @Test
    void coordinateShiftNeighborMethodsAreDeprecated() throws NoSuchMethodException {
        assertTrue(Triangle.class.getMethod("neighbors").isAnnotationPresent(Deprecated.class),
                   "Triangle.neighbors() must be deprecated (Luciferase-mfe6y)");
        assertTrue(Triangle.class.getMethod("neighbor", int.class).isAnnotationPresent(Deprecated.class),
                   "Triangle.neighbor(int) must be deprecated (Luciferase-mfe6y)");
        assertFalse(Triangle.class.getMethod("faceNeighbor", int.class).isAnnotationPresent(Deprecated.class),
                    "faceNeighbor(int) is the documented t8code path and must NOT be deprecated");
    }
}
