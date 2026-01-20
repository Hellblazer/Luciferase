/*
 * Copyright (c) 2024 Hal Hildebrand. All rights reserved.
 * This file is part of Luciferase, licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See LICENSE file for details.
 */

package com.hellblazer.luciferase.esvo.dag;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for sealed DAGBuildException hierarchy.
 */
class DAGBuildExceptionTest {

    @Test
    void testInvalidInputException() {
        var ex = new DAGBuildException.InvalidInputException("null SVO");
        assertEquals("null SVO", ex.getMessage());
        assertTrue(ex instanceof DAGBuildException);
    }

    @Test
    void testOutOfMemoryException() {
        var ex = new DAGBuildException.OutOfMemoryException(1000L, 500L);
        assertEquals(1000L, ex.getRequiredBytes());
        assertEquals(500L, ex.getAvailableBytes());
        assertTrue(ex.getMessage().contains("1,000"));  // Formatted with comma
        assertTrue(ex.getMessage().contains("500"));
    }

    @Test
    void testBuildTimeoutException() {
        var ex = new DAGBuildException.BuildTimeoutException("Build exceeded 60s timeout");
        assertTrue(ex.getMessage().contains("60s"));
    }

    @Test
    void testCorruptedDataException() {
        var ex = new DAGBuildException.CorruptedDataException("Invalid child pointer at index 123");
        assertTrue(ex.getMessage().contains("123"));
    }

    @Test
    void testValidationFailedException() {
        var ex = new DAGBuildException.ValidationFailedException("Compression ratio below threshold");
        assertTrue(ex.getMessage().contains("threshold"));
    }

    @Test
    void testCauseChaining() {
        var cause = new IOException("Disk full");
        var ex = new DAGBuildException.OutOfMemoryException(cause);
        assertEquals(cause, ex.getCause());
    }

    @Test
    void testSealedHierarchy() {
        assertTrue(DAGBuildException.class.isSealed());
        var permittedSubclasses = DAGBuildException.class.getPermittedSubclasses();
        assertEquals(5, permittedSubclasses.length);
    }

    @Test
    void testExceptionThrown_InvalidInput() {
        assertThrows(DAGBuildException.InvalidInputException.class, () -> {
            throw new DAGBuildException.InvalidInputException("test");
        });
    }

    @Test
    void testExceptionThrown_OutOfMemory() {
        assertThrows(DAGBuildException.OutOfMemoryException.class, () -> {
            throw new DAGBuildException.OutOfMemoryException(1000, 500);
        });
    }

    @Test
    void testExceptionThrown_CorruptedData() {
        assertThrows(DAGBuildException.CorruptedDataException.class, () -> {
            throw new DAGBuildException.CorruptedDataException("test");
        });
    }

    @Test
    void testExceptionCatchingParent() {
        try {
            throw new DAGBuildException.InvalidInputException("test");
        } catch (DAGBuildException e) {
            assertTrue(e instanceof DAGBuildException.InvalidInputException);
        }
    }

    @Test
    void testExceptionHierarchy() {
        DAGBuildException[] exceptions = new DAGBuildException[] {
            new DAGBuildException.InvalidInputException(""),
            new DAGBuildException.OutOfMemoryException(0, 0),
            new DAGBuildException.BuildTimeoutException(""),
            new DAGBuildException.CorruptedDataException(""),
            new DAGBuildException.ValidationFailedException("")
        };

        for (var ex : exceptions) {
            assertTrue(ex instanceof DAGBuildException);
        }
    }

    @Test
    void testOutOfMemoryExceptionWithMemoryValues() {
        var ex = new DAGBuildException.OutOfMemoryException(2_000_000_000L, 1_000_000_000L);
        assertEquals(2_000_000_000L, ex.getRequiredBytes());
        assertEquals(1_000_000_000L, ex.getAvailableBytes());
        assertTrue(ex.getMessage().contains("GB"));
    }

    @Test
    void testBuildTimeoutExceptionWithTiming() {
        var ex = new DAGBuildException.BuildTimeoutException(60_000L, 75_000L);
        assertTrue(ex.getMessage().contains("60"));
        assertTrue(ex.getMessage().contains("75"));
    }

    @Test
    void testInvalidInputExceptionWithCause() {
        var cause = new IllegalArgumentException("Invalid parameter");
        var ex = new DAGBuildException.InvalidInputException("SVO validation failed", cause);
        assertEquals(cause, ex.getCause());
        assertTrue(ex.getMessage().contains("SVO validation"));
    }

    @Test
    void testCorruptedDataExceptionWithCause() {
        var cause = new IOException("Checksum mismatch");
        var ex = new DAGBuildException.CorruptedDataException("Data integrity check failed", cause);
        assertEquals(cause, ex.getCause());
        assertTrue(ex.getMessage().contains("integrity"));
    }

    @Test
    void testValidationFailedExceptionWithCause() {
        var cause = new RuntimeException("Constraint violated");
        var ex = new DAGBuildException.ValidationFailedException("Structural validation failed", cause);
        assertEquals(cause, ex.getCause());
        assertTrue(ex.getMessage().contains("Structural"));
    }
}
