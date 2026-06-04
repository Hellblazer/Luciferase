/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * Licensed under AGPL v3.0. See LICENSE.
 */
package com.hellblazer.luciferase.simulation.ghost;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for Luciferase-0frcy.105: {@code SameServerOptimizer.enabled} must be {@code volatile}
 * so a {@code setEnabled(false)} from one thread is visible to concurrent {@code shouldBypassGhostSync()}
 * readers on other threads. A non-volatile boolean offers no JMM visibility guarantee, so readers could
 * keep bypassing required ghost sync using a stale {@code true}.
 *
 * @author hal.hildebrand
 */
class SameServerOptimizerVolatileTest {

    @Test
    void enabledFieldIsVolatile() throws Exception {
        Field f = SameServerOptimizer.class.getDeclaredField("enabled");
        assertTrue(Modifier.isVolatile(f.getModifiers()),
                   "SameServerOptimizer.enabled must be volatile (Luciferase-0frcy.105)");
    }
}
