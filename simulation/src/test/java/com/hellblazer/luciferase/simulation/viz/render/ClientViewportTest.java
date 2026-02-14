/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.viz.render;

import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ClientViewport record validation and helper methods.
 *
 * @author hal.hildebrand
 */
class ClientViewportTest {

    @Test
    void testValidConstruction() {
        // Happy path: all parameters valid
        var viewport = new ClientViewport(
            new Point3f(512f, 512f, 100f),
            new Point3f(512f, 512f, 512f),
            new Vector3f(0f, 1f, 0f),
            (float) (Math.PI / 3),  // 60 degrees
            16f / 9f,
            0.1f,
            2000f
        );

        assertNotNull(viewport);
        assertEquals(512f, viewport.eye().x);
        assertEquals(512f, viewport.lookAt().x);
        assertEquals(1f, viewport.up().y);
        assertEquals(Math.PI / 3, viewport.fovY(), 0.001);
        assertEquals(16f / 9f, viewport.aspectRatio(), 0.001);
        assertEquals(0.1f, viewport.nearPlane());
        assertEquals(2000f, viewport.farPlane());
    }

    @Test
    void testInvalidParameters() {
        // Test nearPlane <= 0
        assertThrows(IllegalArgumentException.class, () -> {
            new ClientViewport(
                new Point3f(0, 0, 0),
                new Point3f(0, 0, 1),
                new Vector3f(0, 1, 0),
                (float) Math.PI / 3,
                1.0f,
                0.0f,  // Invalid: nearPlane must be positive
                100f
            );
        }, "nearPlane must be positive");

        assertThrows(IllegalArgumentException.class, () -> {
            new ClientViewport(
                new Point3f(0, 0, 0),
                new Point3f(0, 0, 1),
                new Vector3f(0, 1, 0),
                (float) Math.PI / 3,
                1.0f,
                -1.0f,  // Invalid: negative nearPlane
                100f
            );
        }, "nearPlane must be positive");

        // Test farPlane <= nearPlane
        assertThrows(IllegalArgumentException.class, () -> {
            new ClientViewport(
                new Point3f(0, 0, 0),
                new Point3f(0, 0, 1),
                new Vector3f(0, 1, 0),
                (float) Math.PI / 3,
                1.0f,
                10f,
                10f  // Invalid: farPlane must be > nearPlane
            );
        }, "farPlane must be > nearPlane");

        assertThrows(IllegalArgumentException.class, () -> {
            new ClientViewport(
                new Point3f(0, 0, 0),
                new Point3f(0, 0, 1),
                new Vector3f(0, 1, 0),
                (float) Math.PI / 3,
                1.0f,
                100f,
                50f  // Invalid: farPlane < nearPlane
            );
        }, "farPlane must be > nearPlane");

        // Test fovY out of range (0, π)
        assertThrows(IllegalArgumentException.class, () -> {
            new ClientViewport(
                new Point3f(0, 0, 0),
                new Point3f(0, 0, 1),
                new Vector3f(0, 1, 0),
                0.0f,  // Invalid: fovY must be > 0
                1.0f,
                0.1f,
                100f
            );
        }, "fovY must be in (0, pi)");

        assertThrows(IllegalArgumentException.class, () -> {
            new ClientViewport(
                new Point3f(0, 0, 0),
                new Point3f(0, 0, 1),
                new Vector3f(0, 1, 0),
                (float) Math.PI,  // Invalid: fovY must be < π
                1.0f,
                0.1f,
                100f
            );
        }, "fovY must be in (0, pi)");

        assertThrows(IllegalArgumentException.class, () -> {
            new ClientViewport(
                new Point3f(0, 0, 0),
                new Point3f(0, 0, 1),
                new Vector3f(0, 1, 0),
                (float) (Math.PI + 0.1),  // Invalid: fovY > π
                1.0f,
                0.1f,
                100f
            );
        }, "fovY must be in (0, pi)");

        // Test aspectRatio <= 0
        assertThrows(IllegalArgumentException.class, () -> {
            new ClientViewport(
                new Point3f(0, 0, 0),
                new Point3f(0, 0, 1),
                new Vector3f(0, 1, 0),
                (float) Math.PI / 3,
                0.0f,  // Invalid: aspectRatio must be positive
                0.1f,
                100f
            );
        }, "aspectRatio must be positive");

        assertThrows(IllegalArgumentException.class, () -> {
            new ClientViewport(
                new Point3f(0, 0, 0),
                new Point3f(0, 0, 1),
                new Vector3f(0, 1, 0),
                (float) Math.PI / 3,
                -1.0f,  // Invalid: negative aspectRatio
                0.1f,
                100f
            );
        }, "aspectRatio must be positive");
    }

    @Test
    void testDefault() {
        // Test default factory method
        var viewport = ClientViewport.testDefault();

        assertNotNull(viewport);
        assertEquals(512f, viewport.eye().x);
        assertEquals(512f, viewport.eye().y);
        assertEquals(100f, viewport.eye().z);
        assertEquals(512f, viewport.lookAt().x);
        assertEquals(512f, viewport.lookAt().y);
        assertEquals(512f, viewport.lookAt().z);
        assertEquals(0f, viewport.up().x);
        assertEquals(1f, viewport.up().y);
        assertEquals(0f, viewport.up().z);
        assertEquals(Math.PI / 3, viewport.fovY(), 0.001);
        assertEquals(16f / 9f, viewport.aspectRatio(), 0.001);
        assertEquals(0.1f, viewport.nearPlane());
        assertEquals(2000f, viewport.farPlane());
    }

    @Test
    void testDistanceTo() {
        // Test distance calculation
        var viewport = new ClientViewport(
            new Point3f(0f, 0f, 0f),
            new Point3f(0f, 0f, 1f),
            new Vector3f(0f, 1f, 0f),
            (float) Math.PI / 3,
            16f / 9f,
            0.1f,
            100f
        );

        // Distance to (3, 4, 0) should be 5 (3-4-5 triangle)
        assertEquals(5f, viewport.distanceTo(3f, 4f, 0f), 0.001f);

        // Distance to self should be 0
        assertEquals(0f, viewport.distanceTo(0f, 0f, 0f), 0.001f);

        // Distance to (10, 0, 0) should be 10
        assertEquals(10f, viewport.distanceTo(10f, 0f, 0f), 0.001f);
    }
}
