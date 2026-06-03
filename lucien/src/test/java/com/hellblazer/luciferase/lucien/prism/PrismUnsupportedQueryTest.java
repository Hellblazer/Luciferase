/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.prism;

import com.hellblazer.luciferase.lucien.Plane3D;
import com.hellblazer.luciferase.lucien.Spatial;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Luciferase-h9r0z: {@code Prism.getPlaneTraversalOrder} and {@code Prism.enclosing(Spatial)} are unimplemented and
 * now explicitly documented as unsupported (they log a warning instead of silently mis-serving). This pins that
 * contract so the empty/null result is a known, warned outcome rather than a silent surprise.
 *
 * @author hal.hildebrand
 */
class PrismUnsupportedQueryTest {

    @Test
    void unsupportedQueriesReturnEmptyOrNullWithWarning() {
        var prism = new Prism<LongEntityID, String>(new SequentialLongIDGenerator(), 1.0f, 21);

        // Plane-cut traversal is unsupported -> empty (warned).
        assertEquals(0L, prism.getPlaneTraversalOrder(new Plane3D(0, 1, 0, -0.5f)).count(),
                     "getPlaneTraversalOrder is documented unsupported -> empty (Luciferase-h9r0z)");

        // Volume enclosure is unsupported -> null (warned). The point/level overload IS implemented.
        assertNull(prism.enclosing(new Spatial.aabb(0.1f, 0.1f, 0.1f, 0.4f, 0.4f, 0.4f)),
                   "enclosing(Spatial) is documented unsupported -> null (Luciferase-h9r0z)");
    }
}
