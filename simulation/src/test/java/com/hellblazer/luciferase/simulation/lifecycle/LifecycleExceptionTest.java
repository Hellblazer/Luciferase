/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.lifecycle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for LifecycleException.
 * Validates exception construction and behavior.
 *
 * @author hal.hildebrand
 */
class LifecycleExceptionTest {

    @Test
    void testExceptionWithMessage() {
        var message = "Invalid lifecycle transition";
        var exception = new LifecycleException(message);

        assertEquals(message, exception.getMessage(),
            "Exception message should match constructor argument");
        assertNull(exception.getCause(),
            "Exception with message-only constructor should have null cause");
    }

    @Test
    void testExceptionWithCause() {
        var cause = new RuntimeException("Root cause");
        var exception = new LifecycleException(cause);

        assertNotNull(exception.getMessage(),
            "Exception should have a message derived from cause");
        assertEquals(cause, exception.getCause(),
            "Exception cause should match constructor argument");
    }

    @Test
    void testExceptionWithMessageAndCause() {
        var message = "Lifecycle operation failed";
        var cause = new IllegalStateException("Component in wrong state");
        var exception = new LifecycleException(message, cause);

        assertEquals(message, exception.getMessage(),
            "Exception message should match constructor argument");
        assertEquals(cause, exception.getCause(),
            "Exception cause should match constructor argument");
    }

    @Test
    void testExceptionIsRuntimeException() {
        var exception = new LifecycleException("test");
        assertTrue(exception instanceof RuntimeException,
            "LifecycleException should be a RuntimeException");
    }

    @Test
    void testExceptionCanBeThrown() {
        assertThrows(LifecycleException.class, () -> {
            throw new LifecycleException("Test exception");
        }, "Should be able to throw and catch LifecycleException");
    }

    @Test
    void testExceptionCanBeCaught() {
        try {
            throw new LifecycleException("Test message");
        } catch (LifecycleException e) {
            assertEquals("Test message", e.getMessage());
        }
    }

    @Test
    void testExceptionWithNullMessage() {
        var exception = new LifecycleException((String) null);
        assertNull(exception.getMessage(),
            "Exception with null message should have null message");
    }

    @Test
    void testExceptionWithNullCause() {
        var message = "Test message";
        var exception = new LifecycleException(message, null);
        assertEquals(message, exception.getMessage());
        assertNull(exception.getCause(),
            "Exception with null cause should have null cause");
    }
}
