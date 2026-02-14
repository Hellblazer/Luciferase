/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.viz.render.protocol;

/**
 * Binary protocol constants for WebSocket region streaming.
 * <p>
 * Thread-safe: immutable constants.
 *
 * @author hal.hildebrand
 */
public final class ProtocolConstants {
    /** Magic number for binary frame header: "ESVR" in hex */
    public static final int FRAME_MAGIC = 0x45535652;

    /** Format code for ESVO data */
    public static final byte FORMAT_ESVO = 0x01;

    /** Format code for ESVT data */
    public static final byte FORMAT_ESVT = 0x02;

    /** Size of binary frame header in bytes */
    public static final int FRAME_HEADER_SIZE = 24;

    /** Protocol version */
    public static final int PROTOCOL_VERSION = 1;

    private ProtocolConstants() {
        // Prevent instantiation
    }
}
