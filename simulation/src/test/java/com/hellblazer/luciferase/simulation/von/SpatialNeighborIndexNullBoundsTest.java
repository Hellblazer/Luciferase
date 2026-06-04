/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.von;

import com.hellblazer.luciferase.simulation.bubble.BubbleBounds;
import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3d;
import javax.vecmath.Point3f;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for Luciferase-0frcy.51: SpatialNeighborIndex.findOverlapping /
 * isEnclosingNeighbor must not NPE when a node's bounds() is null. BubbleBoundsTracker.bounds()
 * returns null when a Bubble holds no entities; an empty Bubble in the index previously aborted
 * JoinProtocol.getNeighborList with an NPE, leaving a permanent gap in the VON graph.
 */
class SpatialNeighborIndexNullBoundsTest {

    /** Minimal Node whose bounds() is null — exactly the BubbleBoundsTracker empty-bubble case. */
    private static Node nullBoundsNode() {
        var id = UUID.randomUUID();
        var pos = new Point3d(5, 5, 5);
        return new Node() {
            @Override public UUID id() { return id; }
            @Override public Point3d position() { return pos; }
            @Override public BubbleBounds bounds() { return null; }
            @Override public Set<UUID> neighbors() { return Set.of(); }
            @Override public void notifyMove(Node neighbor) {}
            @Override public void notifyLeave(Node neighbor) {}
            @Override public void notifyJoin(Node neighbor) {}
            @Override public void addNeighbor(UUID neighborId) {}
            @Override public void removeNeighbor(UUID neighborId) {}
        };
    }

    private Node wrap(EnhancedBubble b) {
        return new BubbleNode(b, event -> {});
    }

    private Node populatedNode() {
        var b = new EnhancedBubble(UUID.randomUUID(), (byte) 10, 16L);
        b.addEntity("e1", new Point3f(5f, 5f, 5f), new Object());
        return wrap(b);
    }

    @Test
    void findOverlappingSkipsNullBoundsNodes() {
        var index = new SpatialNeighborIndex(10.0f, 2.0f);
        index.insert(nullBoundsNode());

        var populated = populatedNode();
        BubbleBounds query = populated.bounds();
        assertNotNull(query, "precondition: populated node has bounds");
        index.insert(populated);

        // Pre-fix: NPE on the null-bounds node. Post-fix: skipped cleanly.
        assertDoesNotThrow(() -> index.findOverlapping(query));
    }

    @Test
    void isEnclosingNeighborHandlesNullBounds() {
        var index = new SpatialNeighborIndex(10.0f, 2.0f);
        var empty = nullBoundsNode();
        var populated = populatedNode();

        assertDoesNotThrow(() -> index.isEnclosingNeighbor(empty, populated));
        assertDoesNotThrow(() -> index.isEnclosingNeighbor(populated, empty));
        assertDoesNotThrow(() -> index.isEnclosingNeighbor(empty, empty));
    }

    @Test
    void findOverlappingWithNullQueryReturnsEmpty() {
        var index = new SpatialNeighborIndex(10.0f, 2.0f);
        index.insert(populatedNode());

        assertDoesNotThrow(() -> {
            var result = index.findOverlapping(null);
            assertTrue(result.isEmpty());
        });
    }
}
