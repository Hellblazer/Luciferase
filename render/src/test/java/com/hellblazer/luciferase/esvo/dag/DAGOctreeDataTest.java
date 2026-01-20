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

import com.hellblazer.luciferase.esvo.core.ESVONodeUnified;
import com.hellblazer.luciferase.sparse.core.PointerAddressingMode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for DAGOctreeData interface using a mock implementation.
 *
 * @author hal.hildebrand
 */
class DAGOctreeDataTest {

    /**
     * Mock implementation of DAGOctreeData for testing.
     */
    private static class MockDAGOctreeData implements DAGOctreeData {
        private final ESVONodeUnified[] nodes;
        private final DAGMetadata metadata;
        private final int[] farPointers;

        MockDAGOctreeData(ESVONodeUnified[] nodes, DAGMetadata metadata, int[] farPointers) {
            this.nodes = nodes;
            this.metadata = metadata;
            this.farPointers = farPointers;
        }

        @Override
        public ESVONodeUnified[] nodes() {
            return nodes;
        }

        @Override
        public int[] getFarPointers() {
            return farPointers;
        }

        @Override
        public DAGMetadata getMetadata() {
            return metadata;
        }

        @Override
        public float getCompressionRatio() {
            return metadata.compressionRatio();
        }

        @Override
        public int nodeCount() {
            return nodes.length;
        }

        @Override
        public int maxDepth() {
            return metadata.maxDepth();
        }

        @Override
        public int leafCount() {
            return 0; // Not relevant for these tests
        }

        @Override
        public int internalCount() {
            return nodes.length;
        }

        @Override
        public int sizeInBytes() {
            return nodes.length * ESVONodeUnified.SIZE_BYTES;
        }

        @Override
        public java.nio.ByteBuffer nodesToByteBuffer() {
            var buffer = java.nio.ByteBuffer.allocateDirect(sizeInBytes())
                                           .order(java.nio.ByteOrder.nativeOrder());
            for (var node : nodes) {
                buffer.putInt(node.getChildDescriptor());
                buffer.putInt(node.getContourDescriptor());
            }
            buffer.flip();
            return buffer;
        }

        @Override
        public com.hellblazer.luciferase.sparse.core.CoordinateSpace getCoordinateSpace() {
            return com.hellblazer.luciferase.sparse.core.CoordinateSpace.UNIT_CUBE;
        }
    }

    private MockDAGOctreeData createTestDAG() {
        // Create a simple test DAG with 3 nodes
        var nodes = new ESVONodeUnified[3];

        // Root node: leafMask=0, childMask=0xFF (all children), isFar=false, childPtr=1, contourMask=0, contourPtr=0
        nodes[0] = new ESVONodeUnified((byte)0, (byte)0xFF, false, 1, (byte)0, 0);

        // Child node 1: leafMask=0, childMask=0x0F (4 children), isFar=false, childPtr=2, contourMask=0, contourPtr=0
        nodes[1] = new ESVONodeUnified((byte)0, (byte)0x0F, false, 2, (byte)0, 0);

        // Leaf node: leafMask=0, childMask=0 (no children), isFar=false, childPtr=0, contourMask=0, contourPtr=0
        nodes[2] = new ESVONodeUnified((byte)0, (byte)0, false, 0, (byte)0, 0);

        var metadata = new DAGMetadata(
            3,    // uniqueNodeCount
            10,   // originalNodeCount (3:1 compression)
            3,    // maxDepth
            2,    // sharedSubtreeCount
            Map.of(1, 1, 2, 1),
            Duration.ofMillis(50),
            HashAlgorithm.SHA256,
            CompressionStrategy.BALANCED,
            12345L
        );

        return new MockDAGOctreeData(nodes, metadata, new int[0]);
    }

    @Test
    void testAddressingMode() {
        var dag = createTestDAG();
        assertEquals(PointerAddressingMode.ABSOLUTE, dag.getAddressingMode());
    }

    @Test
    void testResolveChildIndex_Absolute() {
        var dag = createTestDAG();
        var node = dag.getNode(0);
        assertNotNull(node);

        // DAG: absolute addressing (no parent context needed)
        // For octant 0: childPtr=1, sparseIdx=0 (no bits set below octant 0)
        int childIdx = dag.resolveChildIndex(0, node, 0);
        assertEquals(1, childIdx); // 1 + 0 = 1

        // For octant 3: childPtr=1, sparseIdx=3 (bits 0,1,2 are set in 0xFF)
        childIdx = dag.resolveChildIndex(0, node, 3);
        assertEquals(4, childIdx); // 1 + 3 = 4
    }

    @Test
    void testResolveChildIndex_DifferentOctants() {
        var dag = createTestDAG();
        var node = dag.getNode(0);
        assertNotNull(node);

        // Test all octants with full childMask (0xFF)
        for (int octant = 0; octant < 8; octant++) {
            int childIdx = dag.resolveChildIndex(0, node, octant);
            // With full childMask, sparseIdx equals octant
            assertEquals(1 + octant, childIdx);
        }
    }

    @Test
    void testResolveChildIndex_PartialMask() {
        var dag = createTestDAG();
        var node = dag.getNode(1); // childMask=0x0F (only octants 0-3)
        assertNotNull(node);

        // For octant 0: sparseIdx=0
        assertEquals(2, dag.resolveChildIndex(1, node, 0)); // 2 + 0 = 2

        // For octant 2: sparseIdx=2 (bits 0,1 set in 0x0F)
        assertEquals(4, dag.resolveChildIndex(1, node, 2)); // 2 + 2 = 4
    }

    @Test
    void testCompressionMetadata() {
        var dag = createTestDAG();
        var metadata = dag.getMetadata();

        assertNotNull(metadata);
        assertEquals(3, metadata.uniqueNodeCount());
        assertEquals(10, metadata.originalNodeCount());
        assertTrue(metadata.compressionRatio() > 1.0f);
        assertEquals(3.33f, metadata.compressionRatio(), 0.01f);
    }

    @Test
    void testCompressionRatio() {
        var dag = createTestDAG();
        assertEquals(3.33f, dag.getCompressionRatio(), 0.01f);
    }

    @Test
    void testNodeAccess() {
        var dag = createTestDAG();

        var root = dag.getNode(0);
        assertNotNull(root);
        assertEquals(0xFF, root.getChildMask());

        var child = dag.getNode(1);
        assertNotNull(child);
        assertEquals(0x0F, child.getChildMask());

        var leaf = dag.getNode(2);
        assertNotNull(leaf);
        assertEquals(0, leaf.getChildMask());
    }

    @Test
    void testNodeCount() {
        var dag = createTestDAG();
        assertEquals(3, dag.nodeCount());
    }

    @Test
    void testMaxDepth() {
        var dag = createTestDAG();
        assertEquals(3, dag.maxDepth());
    }

    @Test
    void testSizeInBytes() {
        var dag = createTestDAG();
        assertEquals(3 * ESVONodeUnified.SIZE_BYTES, dag.sizeInBytes());
    }

    @Test
    void testFarPointers() {
        var dag = createTestDAG();
        var farPointers = dag.getFarPointers();
        assertNotNull(farPointers);
        assertEquals(0, farPointers.length);
    }

    @Test
    void testMetadataFields() {
        var dag = createTestDAG();
        var metadata = dag.getMetadata();

        assertEquals(HashAlgorithm.SHA256, metadata.hashAlgorithm());
        assertEquals(CompressionStrategy.BALANCED, metadata.strategy());
        assertEquals(Duration.ofMillis(50), metadata.buildTime());
        assertEquals(12345L, metadata.sourceHash());
    }

    @Test
    void testSharingByDepth() {
        var dag = createTestDAG();
        var sharingByDepth = dag.getMetadata().sharingByDepth();

        assertNotNull(sharingByDepth);
        assertEquals(2, sharingByDepth.size());
        assertEquals(1, sharingByDepth.get(1));
        assertEquals(1, sharingByDepth.get(2));
    }
}
