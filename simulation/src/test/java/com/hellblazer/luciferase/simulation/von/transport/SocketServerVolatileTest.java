/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * Licensed under AGPL v3.0. See LICENSE.
 */
package com.hellblazer.luciferase.simulation.von.transport;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for Luciferase-0frcy.71: {@code SocketServer.serverSocket} must be {@code volatile}
 * so {@code shutdown()} reliably observes the value written by {@code start()} (the JMM provides no
 * happens-before from start()'s volatile {@code running} write to shutdown()'s volatile {@code running}
 * write — only to a volatile <em>read</em>). A non-volatile {@code serverSocket} could be observed as
 * {@code null} in shutdown(), leaking the server socket and accept thread.
 *
 * @author hal.hildebrand
 */
class SocketServerVolatileTest {

    @Test
    void serverSocketFieldIsVolatile() throws Exception {
        Field f = SocketServer.class.getDeclaredField("serverSocket");
        assertTrue(Modifier.isVolatile(f.getModifiers()),
                   "SocketServer.serverSocket must be volatile (Luciferase-0frcy.71) so shutdown() "
                   + "always observes the socket written by start()");
    }
}
