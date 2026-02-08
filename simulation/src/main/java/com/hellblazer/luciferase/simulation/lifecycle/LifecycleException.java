/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.lifecycle;

/**
 * Runtime exception thrown for invalid lifecycle transitions or operations.
 * <p>
 * Examples of invalid operations:
 * <ul>
 *   <li>Calling start() when already RUNNING</li>
 *   <li>Calling stop() when not RUNNING</li>
 *   <li>Attempting an invalid state transition</li>
 *   <li>Circular dependency detected during coordination</li>
 * </ul>
 * <p>
 * As a RuntimeException, callers are not required to catch this exception,
 * but it signals a programming error or configuration problem that should be fixed.
 *
 * @author hal.hildebrand
 */
public class LifecycleException extends RuntimeException {

    /**
     * Constructs a new lifecycle exception with the specified detail message.
     *
     * @param message the detail message (may be null)
     */
    public LifecycleException(String message) {
        super(message);
    }

    /**
     * Constructs a new lifecycle exception with the specified cause.
     *
     * @param cause the cause (may be null)
     */
    public LifecycleException(Throwable cause) {
        super(cause);
    }

    /**
     * Constructs a new lifecycle exception with the specified detail message and cause.
     *
     * @param message the detail message (may be null)
     * @param cause the cause (may be null)
     */
    public LifecycleException(String message, Throwable cause) {
        super(message, cause);
    }
}
