/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase. Licensed under the GNU Affero General Public License v3.0.
 */

package com.hellblazer.luciferase.simulation.von.transport;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for Luciferase-0frcy.52: SocketClient.close() must hold the same instance monitor
 * as send() so a concurrent close cannot interrupt an in-progress writeObject(), which would emit a
 * partial serialized object and corrupt the receiver's stream.
 * <p>
 * send() is {@code synchronized(this)}; close() must likewise be {@code synchronized}. We assert the
 * structural contract directly via reflection: both methods carry the SYNCHRONIZED modifier and
 * therefore acquire the same monitor, making them mutually exclusive. This is deterministic (no
 * socket, no timing) and fails pre-fix where close() lacked the modifier.
 */
class SocketClientCloseSynchronizationTest {

    @Test
    void closeAcquiresSameMonitorAsSend() throws NoSuchMethodException {
        Method send = SocketClient.class.getDeclaredMethod("send",
            com.hellblazer.luciferase.simulation.von.TransportVonMessage.class);
        Method close = SocketClient.class.getDeclaredMethod("close");

        assertTrue(Modifier.isSynchronized(send.getModifiers()),
                   "precondition: send() is synchronized");
        assertTrue(Modifier.isSynchronized(close.getModifiers()),
                   "close() must be synchronized so it is mutually exclusive with send() — "
                   + "otherwise a concurrent close interrupts an in-progress writeObject and corrupts the wire");

        // Both must use the instance monitor (not static) to actually exclude each other.
        assertEquals(false, Modifier.isStatic(send.getModifiers()));
        assertEquals(false, Modifier.isStatic(close.getModifiers()));
    }
}
