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
package com.hellblazer.luciferase.esvo.dag.pipeline;

import com.hellblazer.luciferase.esvo.core.ESVONodeUnified;
import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.esvo.dag.DAGOctreeData;
import com.hellblazer.luciferase.sparse.core.CoordinateSpace;
import com.hellblazer.luciferase.sparse.core.PointerAddressingMode;
import com.hellblazer.luciferase.sparse.core.SparseVoxelNode;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CompressibleOctreeData marker interface.
 *
 * <p>Verifies that the marker interface can be implemented by both
 * ESVOOctreeData and DAGOctreeData, enabling polymorphic treatment
 * in the compression pipeline.
 *
 * @author hal.hildebrand
 */
class CompressibleOctreeDataTest {

    @Test
    void testMarkerInterfaceCanBeImplemented() {
        // Given: A test implementation of CompressibleOctreeData
        var testImpl = new TestCompressibleOctreeData();

        // Then: instanceof check succeeds
        assertTrue(testImpl instanceof CompressibleOctreeData,
                   "TestCompressibleOctreeData should implement CompressibleOctreeData");
    }

    @Test
    void testInstanceofCheckWithNull() {
        // Given: null reference
        CompressibleOctreeData data = null;

        // Then: instanceof returns false (not throw NPE)
        assertFalse(data instanceof CompressibleOctreeData,
                    "instanceof should return false for null");
    }

    @Test
    void testCanAssignToSparseVoxelData() {
        // Given: A CompressibleOctreeData instance
        CompressibleOctreeData compressible = new TestCompressibleOctreeData();

        // Then: Can be assigned to SparseVoxelData (since CompressibleOctreeData extends it)
        assertDoesNotThrow(() -> {
            var sparseData = (com.hellblazer.luciferase.sparse.core.SparseVoxelData<?>) compressible;
            assertNotNull(sparseData);
        });
    }

    @Test
    void testMultipleImplementationsCanCoexist() {
        // Given: Two different implementations
        CompressibleOctreeData impl1 = new TestCompressibleOctreeData();
        CompressibleOctreeData impl2 = new AnotherTestImplementation();

        // Then: Both are valid CompressibleOctreeData
        assertTrue(impl1 instanceof CompressibleOctreeData);
        assertTrue(impl2 instanceof CompressibleOctreeData);

        // And: They are distinct types
        assertNotEquals(impl1.getClass(), impl2.getClass());
    }

    // === Test Implementations ===

    /**
     * Minimal implementation for testing marker interface.
     */
    private static class TestCompressibleOctreeData implements CompressibleOctreeData {
        @Override
        public CoordinateSpace getCoordinateSpace() {
            return CoordinateSpace.UNIT_CUBE;
        }

        @Override
        public PointerAddressingMode getAddressingMode() {
            return PointerAddressingMode.RELATIVE;
        }

        @Override
        public ESVONodeUnified getNode(int index) {
            return null;
        }

        @Override
        public ESVONodeUnified[] nodes() {
            return new ESVONodeUnified[0];
        }

        @Override
        public int[] getFarPointers() {
            return new int[0];
        }

        @Override
        public ByteBuffer nodesToByteBuffer() {
            return ByteBuffer.allocate(0);
        }


        @Override
        public int nodeCount() {
            return 0;
        }

        @Override
        public int maxDepth() {
            return 0;
        }

        @Override
        public int leafCount() {
            return 0;
        }

        @Override
        public int internalCount() {
            return 0;
        }

        @Override
        public int sizeInBytes() {
            return 0;
        }
    }

    /**
     * Alternative implementation to test polymorphism.
     */
    private static class AnotherTestImplementation implements CompressibleOctreeData {
        @Override
        public CoordinateSpace getCoordinateSpace() {
            return CoordinateSpace.UNIT_CUBE;
        }

        @Override
        public PointerAddressingMode getAddressingMode() {
            return PointerAddressingMode.ABSOLUTE;
        }

        @Override
        public ESVONodeUnified getNode(int index) {
            return null;
        }

        @Override
        public ESVONodeUnified[] nodes() {
            return new ESVONodeUnified[0];
        }

        @Override
        public int[] getFarPointers() {
            return new int[0];
        }

        @Override
        public ByteBuffer nodesToByteBuffer() {
            return ByteBuffer.allocate(0);
        }


        @Override
        public int nodeCount() {
            return 0;
        }

        @Override
        public int maxDepth() {
            return 0;
        }

        @Override
        public int leafCount() {
            return 0;
        }

        @Override
        public int internalCount() {
            return 0;
        }

        @Override
        public int sizeInBytes() {
            return 0;
        }
    }
}
