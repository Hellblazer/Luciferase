/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Luciferase-3vwqb: {@code bulkConfig} and {@code parallelOperations} are reconfigured at runtime
 * ({@code configureBulkOperations} / {@code configureParallelOperations}) while bulk/parallel ops may read them
 * without the write lock. They must be {@code volatile} so the swapped reference is published atomically and
 * visibly — otherwise a concurrent reconfigure can expose a half-updated config or a mid-flight reference swap.
 *
 * @author hal.hildebrand
 */
class AbstractSpatialIndexConfigVolatilityTest {

    private static void assertVolatile(String fieldName) throws NoSuchFieldException {
        Field f = AbstractSpatialIndex.class.getDeclaredField(fieldName);
        assertTrue(Modifier.isVolatile(f.getModifiers()),
                   fieldName + " must be volatile for safe runtime reconfiguration (Luciferase-3vwqb)");
    }

    @Test
    void runtimeReconfiguredConfigFieldsAreVolatile() throws NoSuchFieldException {
        assertVolatile("bulkConfig");
        assertVolatile("parallelOperations");
    }
}
