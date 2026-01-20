/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.esvo.dag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for CompressionEstimate record.
 *
 * @author hal.hildebrand
 */
class CompressionEstimateTest {

    @Test
    void testCompressionEstimate() {
        var estimate = new CompressionEstimate(5.0f, 1000L, 32000L);
        assertEquals(5.0f, estimate.estimatedCompressionRatio(), 0.001f);
        assertEquals(1000L, estimate.estimatedUniqueNodeCount());
        assertEquals(32000L, estimate.estimatedMemorySaved());
    }

    @Test
    void testCompressionEstimateNoCompression() {
        var estimate = new CompressionEstimate(1.0f, 5000L, 0L);
        assertEquals(1.0f, estimate.estimatedCompressionRatio(), 0.001f);
        assertEquals(5000L, estimate.estimatedUniqueNodeCount());
        assertEquals(0L, estimate.estimatedMemorySaved());
    }

    @Test
    void testCompressionEstimateHighCompression() {
        var estimate = new CompressionEstimate(10.0f, 500L, 360000L);
        assertEquals(10.0f, estimate.estimatedCompressionRatio(), 0.001f);
        assertEquals(500L, estimate.estimatedUniqueNodeCount());
        assertEquals(360000L, estimate.estimatedMemorySaved());
    }

    @Test
    void testCompressionEstimateImmutability() {
        var estimate1 = new CompressionEstimate(5.0f, 1000L, 32000L);
        var estimate2 = new CompressionEstimate(5.0f, 1000L, 32000L);
        assertEquals(estimate1, estimate2);
        assertEquals(estimate1.hashCode(), estimate2.hashCode());
    }

    @Test
    void testCompressionEstimateNotEqual() {
        var estimate1 = new CompressionEstimate(5.0f, 1000L, 32000L);
        var estimate2 = new CompressionEstimate(5.0f, 2000L, 32000L);
        assertNotEquals(estimate1, estimate2);
    }

    @Test
    void testCompressionEstimateToString() {
        var estimate = new CompressionEstimate(5.0f, 1000L, 32000L);
        var str = estimate.toString();
        assertTrue(str.contains("5.0"));
        assertTrue(str.contains("1000"));
        assertTrue(str.contains("32000"));
    }
}
