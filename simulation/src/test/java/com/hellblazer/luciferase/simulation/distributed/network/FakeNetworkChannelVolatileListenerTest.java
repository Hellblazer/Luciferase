/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase Simulation Framework.
 *
 * Licensed under AGPL v3.0. See LICENSE.
 */
package com.hellblazer.luciferase.simulation.distributed.network;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for Luciferase-0frcy.72: FakeNetworkChannel's listener fields must be
 * {@code volatile}. They are written from the test/setup thread but read by delivery
 * callbacks on the ScheduledExecutorService thread (networkLatencyMs &gt; 0 path); without
 * the memory barrier the scheduler thread could observe a stale null and silently drop the
 * delivery, matching the pattern already applied to the {@code clock} field.
 *
 * @author hal.hildebrand
 */
class FakeNetworkChannelVolatileListenerTest {

    @Test
    void listenerFieldsAreVolatile() throws Exception {
        for (var name : new String[] { "departureListener", "ackListener", "rollbackListener" }) {
            Field f = FakeNetworkChannel.class.getDeclaredField(name);
            assertTrue(Modifier.isVolatile(f.getModifiers()),
                       "FakeNetworkChannel." + name + " must be volatile (Luciferase-0frcy.72)");
        }
    }
}
