/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.simulation.topology.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hellblazer.luciferase.simulation.topology.metrics.DensityState;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that TopologyEventStream JSON serialization uses Locale.ROOT for
 * floating-point values, preventing malformed JSON in non-US locales.
 *
 * @author hal.hildebrand
 */
class TopologyEventStreamTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Invoke a private method on TopologyEventStream via reflection. */
    private String invokeToJson(TopologyEventStream stream, String methodName, Class<?> paramType, Object arg)
        throws Exception {
        Method m = TopologyEventStream.class.getDeclaredMethod(methodName, paramType);
        m.setAccessible(true);
        return (String) m.invoke(stream, arg);
    }

    // -----------------------------------------------------------------------
    // moveEventToJson — correctness fix (was locale-dependent via %f x6)
    // -----------------------------------------------------------------------

    @Test
    void moveEventToJson_usesDecimalPoint_underGermanyLocale() throws Exception {
        var stream = new TopologyEventStream();
        var eventId = UUID.randomUUID();
        var bubbleId = UUID.randomUUID();
        var event = new MoveEvent(eventId, 1000L, bubbleId,
                                  1.5f, 2.5f, 3.5f,
                                  4.5f, 5.5f, 6.5f,
                                  true);

        var previousLocale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            var json = invokeToJson(stream, "moveEventToJson", MoveEvent.class, event);

            // Must contain dot-separated floats, not comma-separated
            assertTrue(json.contains("\"x\":1.5"), "oldCentroid x should use '.' not ',': " + json);
            assertTrue(json.contains("\"y\":2.5"), "oldCentroid y should use '.' not ',': " + json);
            assertTrue(json.contains("\"z\":3.5"), "oldCentroid z should use '.' not ',': " + json);
            assertTrue(json.contains("\"x\":4.5"), "newCentroid x should use '.' not ',': " + json);
            assertTrue(json.contains("\"y\":5.5"), "newCentroid y should use '.' not ',': " + json);
            assertTrue(json.contains("\"z\":6.5"), "newCentroid z should use '.' not ',': " + json);

            // Must parse as valid JSON
            JsonNode root = assertDoesNotThrow(() -> MAPPER.readTree(json),
                                               "moveEventToJson output must be valid JSON: " + json);
            assertEquals(1.5, root.path("oldCentroid").path("x").asDouble(), 0.001);
            assertEquals(1.5, root.path("newCentroid").path("x").asDouble() - 3.0, 0.001);
        } finally {
            Locale.setDefault(previousLocale);
        }
    }

    // -----------------------------------------------------------------------
    // densityStateChangeEventToJson — correctness fix (was locale-dependent via %f)
    // -----------------------------------------------------------------------

    @Test
    void densityStateChangeEventToJson_usesDecimalPoint_underGermanyLocale() throws Exception {
        var stream = new TopologyEventStream();
        var eventId = UUID.randomUUID();
        var bubbleId = UUID.randomUUID();
        var event = new DensityStateChangeEvent(eventId, 2000L, bubbleId,
                                                DensityState.NORMAL, DensityState.APPROACHING_SPLIT,
                                                42, 1.5f);

        var previousLocale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            var json = invokeToJson(stream, "densityStateChangeEventToJson", DensityStateChangeEvent.class, event);

            // densityRatio must use '.' not ','
            assertTrue(json.contains("\"densityRatio\":1.5"),
                       "densityRatio should use '.' not ',': " + json);

            // Must parse as valid JSON
            JsonNode root = assertDoesNotThrow(() -> MAPPER.readTree(json),
                                               "densityStateChangeEventToJson output must be valid JSON: " + json);
            assertEquals(1.5, root.path("densityRatio").asDouble(), 0.001);
            assertEquals(42, root.path("entityCount").asInt());
        } finally {
            Locale.setDefault(previousLocale);
        }
    }

    // -----------------------------------------------------------------------
    // Uniformity: locale-safe methods also get Locale.ROOT (no regression)
    // -----------------------------------------------------------------------

    @Test
    void splitMergeconsensusJson_remainsValidJson_underGermanyLocale() throws Exception {
        var stream = new TopologyEventStream();
        var id1 = UUID.randomUUID();
        var id2 = UUID.randomUUID();

        var splitEvent = new SplitEvent(id1, 1000L, id1, id2, 5, true);
        var mergeEvent = new MergeEvent(id1, 1000L, id1, id2, 5, true);

        var previousLocale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);

            var splitJson = invokeToJson(stream, "splitEventToJson", SplitEvent.class, splitEvent);
            assertDoesNotThrow(() -> MAPPER.readTree(splitJson),
                               "splitEventToJson must be valid JSON: " + splitJson);

            var mergeJson = invokeToJson(stream, "mergeEventToJson", MergeEvent.class, mergeEvent);
            assertDoesNotThrow(() -> MAPPER.readTree(mergeJson),
                               "mergeEventToJson must be valid JSON: " + mergeJson);
        } finally {
            Locale.setDefault(previousLocale);
        }
    }
}
