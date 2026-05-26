/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@link SpatialKeySerdeRegistry} initialises to an empty registry
 * when no {@link SpatialKeySerde} providers are present on the classpath (i.e.
 * when lucien-distributed is absent). This is the standalone-lucien
 * graceful-degradation acceptance test for RDR-007 Phase 1 [Luciferase-gf2].
 *
 * <p>The lucien test classpath intentionally excludes lucien-distributed and its
 * META-INF/services entry, so ServiceLoader finds no providers. The registry
 * must remain empty without throwing {@link ExceptionInInitializerError}.
 *
 * @author hal.hildebrand
 */
class GhostSerdeAbsenceTest {

    @Test
    void registryInitialisesEmptyWithNoProviders() {
        // Touch the registry to force static-init. Must not throw.
        var result = SpatialKeySerdeRegistry.forTypeId("morton");
        assertNull(result, "Expected null for 'morton' when no serde providers on classpath");
    }

    @Test
    void forTypeIdReturnsNullForUnknownType() {
        assertNull(SpatialKeySerdeRegistry.forTypeId("tetree"),
                   "Expected null for 'tetree' when no serde providers on classpath");
    }

    @Test
    void forTypeIdReturnsNullForArbitraryKey() {
        assertNull(SpatialKeySerdeRegistry.forTypeId("nonexistent-type"),
                   "Registry must return null for unknown type IDs, not throw");
    }
}
