/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.lucien.forest;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests for ForestEvent sealed interface and ForestEventListener.
 *
 * Tests compile-time exhaustiveness checking, event immutability,
 * listener filtering, and event dispatching.
 */
class ForestEventTest {

    @Test
    void testTreeAddedEventCreation() {
        // Test Case A: cubic tree added
        var cubicBounds = new CubicBounds(null); // EntityBounds will be provided
        var event = new ForestEvent.TreeAdded(
            System.currentTimeMillis(),
            "forest-1",
            "tree-1",
            cubicBounds,
            RegionShape.CUBIC,
            null
        );

        assertEquals("forest-1", event.forestId());
        assertEquals("tree-1", event.treeId());
        assertEquals(RegionShape.CUBIC, event.regionShape());
        assertNull(event.parentId());
        assertNotNull(event.timestamp());
    }

    @Test
    void testTreeRemovedEventCreation() {
        var event = new ForestEvent.TreeRemoved(
            System.currentTimeMillis(),
            "forest-1",
            "tree-1"
        );

        assertEquals("forest-1", event.forestId());
        assertEquals("tree-1", event.treeId());
    }

    @Test
    void testTreeSubdividedEventCreation() {
        var childIds = List.of("child-0", "child-1", "child-2", "child-3", "child-4", "child-5");
        var event = new ForestEvent.TreeSubdivided(
            System.currentTimeMillis(),
            "forest-1",
            "parent-1",
            childIds,
            AdaptiveForest.AdaptationConfig.SubdivisionStrategy.TETRAHEDRAL,
            RegionShape.TETRAHEDRAL
        );

        assertEquals("forest-1", event.forestId());
        assertEquals("parent-1", event.parentId());
        assertEquals(6, event.childIds().size());
        assertEquals(AdaptiveForest.AdaptationConfig.SubdivisionStrategy.TETRAHEDRAL, event.strategy());
        assertEquals(RegionShape.TETRAHEDRAL, event.childShape());
    }

    @Test
    void testTreesMergedEventCreation() {
        var sourceIds = List.of("tree-1", "tree-2", "tree-3");
        var event = new ForestEvent.TreesMerged(
            System.currentTimeMillis(),
            "forest-1",
            sourceIds,
            "merged-tree"
        );

        assertEquals("forest-1", event.forestId());
        assertEquals(3, event.sourceIds().size());
        assertEquals("merged-tree", event.mergedId());
    }

    @Test
    void testEntityMigratedEventCreation() {
        var event = new ForestEvent.EntityMigrated(
            System.currentTimeMillis(),
            "forest-1",
            "entity-123",
            "tree-1",
            "tree-2"
        );

        assertEquals("forest-1", event.forestId());
        assertEquals("entity-123", event.entityId());
        assertEquals("tree-1", event.fromTreeId());
        assertEquals("tree-2", event.toTreeId());
    }

    @Test
    void testPatternMatchingExhaustiveness() {
        // Test that switch on sealed interface is exhaustive
        var events = List.<ForestEvent>of(
            new ForestEvent.TreeAdded(0L, "f", "t", new CubicBounds(null), RegionShape.CUBIC, null),
            new ForestEvent.TreeRemoved(0L, "f", "t"),
            new ForestEvent.TreeSubdivided(0L, "f", "p", List.of(), null, RegionShape.CUBIC),
            new ForestEvent.TreesMerged(0L, "f", List.of(), "m"),
            new ForestEvent.EntityMigrated(0L, "f", "e", "from", "to")
        );

        for (var event : events) {
            var type = getEventTypeName(event);
            assertNotNull(type, "Pattern matching should handle all event types");
        }
    }

    @Test
    void testListenerReceivesAllEventTypes() {
        var receivedTypes = new ArrayList<String>();
        ForestEventListener listener = event -> {
            receivedTypes.add(getEventTypeName(event));
        };

        // Dispatch all event types
        listener.onEvent(new ForestEvent.TreeAdded(0L, "f", "t", new CubicBounds(null), RegionShape.CUBIC, null));
        listener.onEvent(new ForestEvent.TreeRemoved(0L, "f", "t"));
        listener.onEvent(new ForestEvent.TreeSubdivided(0L, "f", "p", List.of(), null, RegionShape.CUBIC));
        listener.onEvent(new ForestEvent.TreesMerged(0L, "f", List.of(), "m"));
        listener.onEvent(new ForestEvent.EntityMigrated(0L, "f", "e", "from", "to"));

        assertEquals(5, receivedTypes.size());
        assertTrue(receivedTypes.contains("TreeAdded"));
        assertTrue(receivedTypes.contains("TreeRemoved"));
        assertTrue(receivedTypes.contains("TreeSubdivided"));
        assertTrue(receivedTypes.contains("TreesMerged"));
        assertTrue(receivedTypes.contains("EntityMigrated"));
    }

    @Test
    void testListenerFilterByType() {
        var subdivisionCount = new AtomicInteger(0);

        // Filter to only TreeSubdivided events
        ForestEventListener listener = event -> {
            if (event instanceof ForestEvent.TreeSubdivided) {
                subdivisionCount.incrementAndGet();
            }
        };

        // Send mixed events
        listener.onEvent(new ForestEvent.TreeAdded(0L, "f", "t", new CubicBounds(null), RegionShape.CUBIC, null));
        listener.onEvent(new ForestEvent.TreeSubdivided(0L, "f", "p", List.of(), null, RegionShape.CUBIC));
        listener.onEvent(new ForestEvent.TreeRemoved(0L, "f", "t"));
        listener.onEvent(new ForestEvent.TreeSubdivided(0L, "f", "p2", List.of(), null, RegionShape.TETRAHEDRAL));

        assertEquals(2, subdivisionCount.get(), "Should only count TreeSubdivided events");
    }

    @Test
    void testEventImmutability() {
        var childIds = List.of("child-0", "child-1");
        var event = new ForestEvent.TreeSubdivided(
            0L,
            "forest-1",
            "parent-1",
            childIds,
            AdaptiveForest.AdaptationConfig.SubdivisionStrategy.TETRAHEDRAL,
            RegionShape.TETRAHEDRAL
        );

        // Records are immutable - childIds() returns the same list
        assertSame(childIds, event.childIds());

        // Verify record equality
        var event2 = new ForestEvent.TreeSubdivided(
            0L,
            "forest-1",
            "parent-1",
            childIds,
            AdaptiveForest.AdaptationConfig.SubdivisionStrategy.TETRAHEDRAL,
            RegionShape.TETRAHEDRAL
        );

        assertEquals(event, event2);
        assertEquals(event.hashCode(), event2.hashCode());
    }

    @Test
    void testRegionShapeIncludedInEvents() {
        // TreeAdded includes RegionShape
        var addEvent = new ForestEvent.TreeAdded(
            0L, "f", "t", new CubicBounds(null), RegionShape.CUBIC, null
        );
        assertEquals(RegionShape.CUBIC, addEvent.regionShape());

        // TreeSubdivided includes childShape
        var subdivideEvent = new ForestEvent.TreeSubdivided(
            0L, "f", "p", List.of(), null, RegionShape.TETRAHEDRAL
        );
        assertEquals(RegionShape.TETRAHEDRAL, subdivideEvent.childShape());
    }

    @Test
    void testMultipleListenersIndependent() {
        var count1 = new AtomicInteger(0);
        var count2 = new AtomicInteger(0);

        ForestEventListener listener1 = event -> count1.incrementAndGet();
        ForestEventListener listener2 = event -> count2.incrementAndGet();

        var event = new ForestEvent.TreeRemoved(0L, "f", "t");
        listener1.onEvent(event);
        listener2.onEvent(event);

        assertEquals(1, count1.get());
        assertEquals(1, count2.get());
    }

    // Helper method for pattern matching (demonstrates exhaustiveness)
    private String getEventTypeName(ForestEvent event) {
        return switch (event) {
            case ForestEvent.TreeAdded e -> "TreeAdded";
            case ForestEvent.TreeRemoved e -> "TreeRemoved";
            case ForestEvent.TreeSubdivided e -> "TreeSubdivided";
            case ForestEvent.TreesMerged e -> "TreesMerged";
            case ForestEvent.EntityMigrated e -> "EntityMigrated";
        };
    }
}
