/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.portal.esvt.visualization;

import com.hellblazer.luciferase.esvt.traversal.ESVTResult;
import org.junit.jupiter.api.Test;

import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ESVTRayCastVisualizer.
 */
class ESVTRayCastVisualizerTest {

    @Test
    void testVisualizeMiss() {
        var visualizer = new ESVTRayCastVisualizer();

        var origin = new Vector3f(0, 0, 0);
        var direction = new Vector3f(1, 0, 0);
        var result = new ESVTResult(); // Miss by default

        var group = visualizer.visualize(origin, direction, result, 10.0f);

        assertNotNull(group);
        assertFalse(group.getChildren().isEmpty(), "Should contain ray line");
    }

    @Test
    void testVisualizeHit() {
        var visualizer = new ESVTRayCastVisualizer();

        var origin = new Vector3f(0, 0, 0);
        var direction = new Vector3f(1, 0, 0);

        var result = new ESVTResult();
        result.setHit(0.5f, 0.5f, 0.5f, 0.5f, 0, 0, (byte) 0, (byte) 0, 4);

        var group = visualizer.visualize(origin, direction, result, 10.0f);

        assertNotNull(group);
        // Should have ray line + hit sphere + entry face indicator
        assertTrue(group.getChildren().size() >= 2, "Should contain ray and hit point");
    }

    @Test
    void testVisualizeNull() {
        var visualizer = new ESVTRayCastVisualizer();

        var origin = new Vector3f(0, 0, 0);
        var direction = new Vector3f(1, 0, 0);

        var group = visualizer.visualize(origin, direction, null, 10.0f);

        assertNotNull(group);
        assertTrue(group.getChildren().isEmpty(), "Should be empty for null result");
    }

    @Test
    void testTetTypeColors() {
        // Verify all 6 tet types have colors
        for (int i = 0; i < 6; i++) {
            var color = ESVTRayCastVisualizer.getTetTypeColor(i);
            assertNotNull(color, "S" + i + " should have a color");
        }

        // Invalid types should return gray
        var grayColor = ESVTRayCastVisualizer.getTetTypeColor(10);
        assertNotNull(grayColor);
    }

    @Test
    void testFaceNames() {
        // Verify all 4 face names
        for (int i = 0; i < 4; i++) {
            var name = ESVTRayCastVisualizer.getFaceName(i);
            assertNotNull(name);
            assertFalse(name.contains("Unknown"));
        }

        // Invalid face should return "Unknown"
        var unknown = ESVTRayCastVisualizer.getFaceName(10);
        assertEquals("Unknown", unknown);
    }

    @Test
    void testStatisticsSummary() {
        var visualizer = new ESVTRayCastVisualizer();

        // Test hit result
        var hitResult = new ESVTResult();
        hitResult.setHit(0.75f, 0.5f, 0.25f, 0.5f, 5, 3, (byte) 2, (byte) 1, 4);
        hitResult.iterations = 42;

        var summary = visualizer.getStatisticsSummary(hitResult);
        assertTrue(summary.contains("Hit: YES"));
        assertTrue(summary.contains("S2"));  // Tet type
        assertTrue(summary.contains("42"));  // Iterations

        // Test miss result
        var missResult = new ESVTResult();
        missResult.iterations = 10;

        var missSummary = visualizer.getStatisticsSummary(missResult);
        assertTrue(missSummary.contains("Hit: NO"));
        assertTrue(missSummary.contains("10"));
    }

    @Test
    void testVisualizationModes() {
        var baseVisualizer = new ESVTRayCastVisualizer();
        assertEquals(ESVTRayCastVisualizer.VisualizationMode.RAY_HIT_FACE, baseVisualizer.getMode());

        var rayOnly = baseVisualizer.withMode(ESVTRayCastVisualizer.VisualizationMode.RAY_ONLY);
        assertEquals(ESVTRayCastVisualizer.VisualizationMode.RAY_ONLY, rayOnly.getMode());

        var fullTraversal = baseVisualizer.withMode(ESVTRayCastVisualizer.VisualizationMode.FULL_TRAVERSAL);
        assertEquals(ESVTRayCastVisualizer.VisualizationMode.FULL_TRAVERSAL, fullTraversal.getMode());
    }
}
