/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Thoth Interest Management and Load Balancing Framework.
 */
package com.hellblazer.luciferase.lucien.von.impl;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.von.Node;
import com.hellblazer.luciferase.lucien.von.Perceiving;
import com.hellblazer.sentry.Cursor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Tuple3f;
import java.util.Collection;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the SpatialSoI k-NN based sphere of interaction.
 */
class SpatialSoITest {

    private Octree<LongEntityID, Node> octree;
    private SpatialSoI<?, LongEntityID> soi;

    @BeforeEach
    void setUp() {
        octree = new Octree<>(new SequentialLongIDGenerator(), 16, (byte) 21);
        soi = new SpatialSoI<>(octree, 5, 100f);
    }

    @Test
    void testInsertAndClosestTo() {
        var node1 = createMockNode(new Point3f(0.5f, 0.5f, 0.5f));
        var node2 = createMockNode(new Point3f(0.6f, 0.5f, 0.5f));

        soi.insert(node1, new Point3f(0.5f, 0.5f, 0.5f));
        soi.insert(node2, new Point3f(0.6f, 0.5f, 0.5f));

        var closest = soi.closestTo(new Point3f(0.55f, 0.5f, 0.5f));
        assertNotNull(closest);
    }

    @Test
    void testGetEnclosingNeighbors() {
        var center = createMockNode(new Point3f(0.5f, 0.5f, 0.5f));
        var neighbor1 = createMockNode(new Point3f(0.51f, 0.5f, 0.5f));
        var neighbor2 = createMockNode(new Point3f(0.5f, 0.51f, 0.5f));
        var farNode = createMockNode(new Point3f(0.9f, 0.9f, 0.9f));

        soi.insert(center, new Point3f(0.5f, 0.5f, 0.5f));
        soi.insert(neighbor1, new Point3f(0.51f, 0.5f, 0.5f));
        soi.insert(neighbor2, new Point3f(0.5f, 0.51f, 0.5f));
        soi.insert(farNode, new Point3f(0.9f, 0.9f, 0.9f));

        var neighbors = soi.getEnclosingNeighbors(center);
        assertFalse(neighbors.isEmpty());
        assertFalse(neighbors.contains(center)); // Should not include self
    }

    @Test
    void testRemove() {
        var node = createMockNode(new Point3f(0.5f, 0.5f, 0.5f));
        soi.insert(node, new Point3f(0.5f, 0.5f, 0.5f));

        assertTrue(soi.includes(node));
        assertTrue(soi.remove(node));
        assertFalse(soi.includes(node));
    }

    @Test
    void testUpdate() {
        var node = createMockNode(new Point3f(0.5f, 0.5f, 0.5f));
        soi.insert(node, new Point3f(0.5f, 0.5f, 0.5f));

        soi.update(node, new Point3f(0.7f, 0.7f, 0.7f));

        var closest = soi.closestTo(new Point3f(0.7f, 0.7f, 0.7f));
        assertEquals(node, closest);
    }

    @Test
    void testOverlaps() {
        var node = createMockNode(new Point3f(0.5f, 0.5f, 0.5f));
        soi.insert(node, new Point3f(0.5f, 0.5f, 0.5f));

        assertTrue(soi.overlaps(node, new Point3f(0.5f, 0.5f, 0.5f), 0.01f));
        assertFalse(soi.overlaps(node, new Point3f(0.9f, 0.9f, 0.9f), 0.01f));
    }

    private Node createMockNode(Point3f position) {
        return new Node() {
            private Point3f pos = new Point3f(position);

            @Override
            public Point3f getLocation() {
                return pos;
            }

            @Override
            public void moveBy(Tuple3f delta) {
                pos.add(delta);
            }

            @Override
            public void moveTo(Tuple3f position) {
                pos.set(position);
            }

            @Override
            public Stream<Cursor> neighbors() {
                return Stream.empty();
            }

            @Override
            public void visitNeighbors(Consumer<Cursor> consumer) {
            }

            @Override
            public void fadeFrom(Node neighbor) {
            }

            @Override
            public float getAoiRadius() {
                return 10f;
            }

            @Override
            public float getMaximumRadiusSquared() {
                return 100f;
            }

            @Override
            public Perceiving getSim() {
                return null;
            }

            @Override
            public void leave(Node leaving) {
            }

            @Override
            public void move(Node neighbor) {
            }

            @Override
            public void moveBoundary(Node neighbor) {
            }

            @Override
            public void noticePeers(Collection<Node> nodes) {
            }

            @Override
            public void perceive(Node neighbor) {
            }

            @Override
            public void query(Node from, Node joiner) {
            }
        };
    }
}
