package com.hellblazer.luciferase.esvo.gpu.beam;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.*;

class BeamNodeTest {

    private Ray[] rays;
    private AABB bounds;
    private int[] rayIndices;

    @BeforeEach
    void setUp() {
        // Create 10 test rays
        rays = new Ray[10];
        for (int i = 0; i < 10; i++) {
            var origin = new Point3f(i * 0.1f, i * 0.1f, i * 0.1f);
            var direction = new Vector3f(1f, 0f, 0f);
            rays[i] = new Ray(origin, direction);
        }

        rayIndices = new int[]{0, 1, 2, 3, 4};
        bounds = AABB.fromRays(rays, rayIndices);
    }

    @Test
    void testCreateBeamNode() {
        var node = new BeamNode(bounds, rayIndices, 0);

        assertNotNull(node);
        assertEquals(bounds, node.getBounds());
        assertArrayEquals(rayIndices, node.getRayIndices());
        assertEquals(0, node.getDepth());
    }

    @Test
    void testIsLeaf() {
        var node = new BeamNode(bounds, rayIndices, 0);
        assertTrue(node.isLeaf(), "New node should be a leaf");

        // Create child
        var childIndices = new int[]{0, 1};
        var childBounds = AABB.fromRays(rays, childIndices);
        var child = new BeamNode(childBounds, childIndices, 1);
        node.setChildren(new BeamNode[]{child});

        assertFalse(node.isLeaf(), "Node with children is not a leaf");
    }

    @Test
    void testGetRayCount() {
        var node = new BeamNode(bounds, rayIndices, 0);
        assertEquals(5, node.getRayCount());

        var singleRay = new int[]{0};
        var singleBounds = AABB.fromRays(rays, singleRay);
        var singleNode = new BeamNode(singleBounds, singleRay, 0);
        assertEquals(1, singleNode.getRayCount());
    }

    @Test
    void testSubdivideOctree() {
        // Simulating octree subdivision
        var allIndices = new int[8];
        for (int i = 0; i < 8; i++) {
            allIndices[i] = i;
        }

        var allBounds = AABB.fromRays(rays, allIndices);
        var root = new BeamNode(allBounds, allIndices, 0);

        // Manually create child nodes simulating octree subdivision
        var midPoint = new Point3f(0.35f, 0.35f, 0.35f);
        var octants = new int[8][];
        for (int i = 0; i < 8; i++) {
            octants[i] = new int[1];
            octants[i][0] = i;
        }

        var children = new BeamNode[8];
        for (int i = 0; i < 8; i++) {
            var childBounds = AABB.fromRays(rays, octants[i]);
            children[i] = new BeamNode(childBounds, octants[i], 1);
        }

        root.setChildren(children);

        assertFalse(root.isLeaf());
        assertEquals(8, root.getChildren().length);
    }

    @Test
    void testCoherenceMetadata() {
        var coherence = new CoherenceMetadata(0.8, 5, 10, 0.75);
        var node = new BeamNode(bounds, rayIndices, 0);
        node.setCoherence(coherence);

        assertEquals(0.8, node.getCoherenceScore());
    }

    @Test
    void testSharedNodeTracking() {
        var node = new BeamNode(bounds, rayIndices, 0);

        node.addSharedNode(1);
        node.addSharedNode(2);
        node.addSharedNode(3);

        assertEquals(3, node.getSharedNodeCount());
        assertTrue(node.getSharedNodes().contains(1));
        assertTrue(node.getSharedNodes().contains(2));
        assertTrue(node.getSharedNodes().contains(3));
    }

    @Test
    void testAABBFromRays() {
        var testIndices = new int[]{0, 4};
        var testBounds = AABB.fromRays(rays, testIndices);

        assertNotNull(testBounds);
        assertEquals(0.0f, testBounds.min().x, 1e-6f);
        assertEquals(0.4f, testBounds.max().x, 1e-6f);
    }

    @Test
    void testBeamDepthTracking() {
        var node1 = new BeamNode(bounds, rayIndices, 0);
        assertEquals(0, node1.getDepth());

        var childIndices = new int[]{0, 1};
        var childBounds = AABB.fromRays(rays, childIndices);
        var node2 = new BeamNode(childBounds, childIndices, 1);
        assertEquals(1, node2.getDepth());

        var grandchildIndices = new int[]{0};
        var grandchildBounds = AABB.fromRays(rays, grandchildIndices);
        var node3 = new BeamNode(grandchildBounds, grandchildIndices, 2);
        assertEquals(2, node3.getDepth());
    }
}
