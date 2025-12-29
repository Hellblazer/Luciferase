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
package com.hellblazer.luciferase.esvt.core;

import com.hellblazer.luciferase.lucien.tetree.TetreeConnectivity;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ESVTNodeUnified - the 8-byte ESVT node structure.
 *
 * @author hal.hildebrand
 */
public class ESVTNodeUnifiedTest {

    @Test
    void testNodeSize() {
        assertEquals(8, ESVTNodeUnified.SIZE_BYTES, "Node should be exactly 8 bytes");
    }

    @Test
    void testEmptyNode() {
        var node = new ESVTNodeUnified();
        assertFalse(node.isValid());
        assertEquals(0, node.getChildMask());
        assertEquals(0, node.getLeafMask());
        assertEquals(0, node.getTetType());
        assertEquals(0, node.getChildPtr());
        assertFalse(node.isFar());
    }

    @Test
    void testNodeWithType() {
        for (byte type = 0; type < 6; type++) {
            var node = new ESVTNodeUnified(type);
            // Validity is now derived from masks (matches ESVO)
            assertFalse(node.isValid(), "Node without children/leaves should not be valid");
            assertEquals(type, node.getTetType(), "Type should match constructor arg");

            // Set masks to make valid
            node.setChildMask(0x01);
            assertTrue(node.isValid(), "Node with children should be valid");
        }
    }

    @Test
    void testInvalidType() {
        var node = new ESVTNodeUnified();
        assertThrows(IllegalArgumentException.class, () -> node.setTetType((byte) -1));
        assertThrows(IllegalArgumentException.class, () -> node.setTetType((byte) 6));
        assertThrows(IllegalArgumentException.class, () -> node.setTetType((byte) 7));
    }

    @Test
    void testChildTypeDerivation() {
        // Test that child types are correctly derived from TetreeConnectivity
        // The mapping is: Morton index -> Bey index -> child type
        // ESVTNodeUnified.getChildType uses Morton index, so we compare against
        // the Morton-indexed table TYPE_TO_TYPE_OF_CHILD_MORTON
        for (byte parentType = 0; parentType < 6; parentType++) {
            var node = new ESVTNodeUnified(parentType);

            for (int mortonIdx = 0; mortonIdx < 8; mortonIdx++) {
                // Convert Morton to Bey, then look up child type
                byte beyIdx = TetreeConnectivity.INDEX_TO_BEY_NUMBER[parentType][mortonIdx];
                byte expectedType = TetreeConnectivity.PARENT_TYPE_TO_CHILD_TYPE[parentType][beyIdx];
                byte actualType = node.getChildType(mortonIdx);

                assertEquals(expectedType, actualType,
                    String.format("Child type mismatch for parent=%d, morton=%d (bey=%d)",
                        parentType, mortonIdx, beyIdx));
            }
        }
    }

    @Test
    void testChildMask() {
        var node = new ESVTNodeUnified((byte) 0);

        // Set all children present
        node.setChildMask(0xFF);
        assertEquals(0xFF, node.getChildMask());
        for (int i = 0; i < 8; i++) {
            assertTrue(node.hasChild(i));
        }

        // Set only even children
        node.setChildMask(0x55); // 0101_0101
        assertEquals(0x55, node.getChildMask());
        assertTrue(node.hasChild(0));
        assertFalse(node.hasChild(1));
        assertTrue(node.hasChild(2));
        assertFalse(node.hasChild(3));
    }

    @Test
    void testLeafMask() {
        var node = new ESVTNodeUnified((byte) 0);
        node.setChildMask(0xFF);

        // Set children 0,1,2 as leaves
        node.setLeafMask(0x07);
        assertEquals(0x07, node.getLeafMask());
        assertTrue(node.isChildLeaf(0));
        assertTrue(node.isChildLeaf(1));
        assertTrue(node.isChildLeaf(2));
        assertFalse(node.isChildLeaf(3));
    }

    @Test
    void testChildPointer() {
        var node = new ESVTNodeUnified((byte) 0);

        node.setChildPtr(0);
        assertEquals(0, node.getChildPtr());

        node.setChildPtr(1000);
        assertEquals(1000, node.getChildPtr());

        // Test 16384 - now valid with 15 bits (was invalid with 14 bits)
        node.setChildPtr(16384);
        assertEquals(16384, node.getChildPtr());

        // Max value: 15 bits = 32767
        node.setChildPtr(32767);
        assertEquals(32767, node.getChildPtr());

        // Over max should throw
        assertThrows(IllegalArgumentException.class, () -> node.setChildPtr(32768));
    }

    @Test
    void testSparseIndexing() {
        var node = new ESVTNodeUnified((byte) 0);

        // Set children 0, 2, 5 present
        node.setChildMask(0x25); // 0010_0101 = children 0, 2, 5
        node.setChildPtr(100);

        // Child 0 is at offset 0 (relative pointer, node at index 0)
        assertEquals(0, node.getChildOffset(0));
        assertEquals(100, node.getChildIndex(0, 0)); // currentNodeIdx=0

        // Child 2 is at offset 1 (one child before it)
        assertEquals(1, node.getChildOffset(2));
        assertEquals(101, node.getChildIndex(2, 0)); // currentNodeIdx=0

        // Child 5 is at offset 2 (two children before it)
        assertEquals(2, node.getChildOffset(5));
        assertEquals(102, node.getChildIndex(5, 0)); // currentNodeIdx=0

        // Child count
        assertEquals(3, node.getChildCount());
    }

    @Test
    void testContourOperations() {
        var node = new ESVTNodeUnified((byte) 0);

        // Test contour mask (4 bits for 4 tet faces)
        node.setContourMask(0xF);
        assertEquals(0xF, node.getContourMask());
        for (int i = 0; i < 4; i++) {
            assertTrue(node.hasContour(i));
        }

        node.setContourMask(0x5); // faces 0 and 2
        assertTrue(node.hasContour(0));
        assertFalse(node.hasContour(1));
        assertTrue(node.hasContour(2));
        assertFalse(node.hasContour(3));

        // Invalid face index
        assertThrows(IllegalArgumentException.class, () -> node.hasContour(4));
    }

    @Test
    void testContourPointer() {
        var node = new ESVTNodeUnified((byte) 0);

        node.setContourPtr(0);
        assertEquals(0, node.getContourPtr());

        node.setContourPtr(1000000);
        assertEquals(1000000, node.getContourPtr());

        // Max value: 20 bits = 1048575
        node.setContourPtr(1048575);
        assertEquals(1048575, node.getContourPtr());

        assertThrows(IllegalArgumentException.class, () -> node.setContourPtr(1048576));
    }

    @Test
    void testNormalMask() {
        var node = new ESVTNodeUnified((byte) 0);

        node.setNormalMask(0xF);
        assertEquals(0xF, node.getNormalMask());

        node.setNormalMask(0xA);
        assertEquals(0xA, node.getNormalMask());
    }

    @Test
    void testFarFlag() {
        var node = new ESVTNodeUnified((byte) 0);

        assertFalse(node.isFar());

        node.setFar(true);
        assertTrue(node.isFar());

        node.setFar(false);
        assertFalse(node.isFar());
    }

    @Test
    void testValidFlag() {
        // Validity is now derived from masks (matches ESVO approach)
        var node = new ESVTNodeUnified();
        assertFalse(node.isValid(), "Empty node should be invalid");

        // Setting childMask makes it valid
        node.setChildMask(0x01);
        assertTrue(node.isValid(), "Node with children should be valid");

        node.setChildMask(0x00);
        assertFalse(node.isValid(), "Node without children should be invalid");

        // Setting leafMask also makes it valid
        node.setLeafMask(0xFF);
        assertTrue(node.isValid(), "Node with leaves should be valid");
    }

    @Test
    void testByteBufferRoundTrip() {
        var original = new ESVTNodeUnified((byte) 3);
        original.setChildMask(0x5A);
        original.setLeafMask(0x12);
        original.setChildPtr(12345);
        original.setFar(true);
        original.setContourMask(0x7);
        original.setNormalMask(0xC);
        original.setContourPtr(987654);

        // Write to buffer
        var buffer = ByteBuffer.allocate(ESVTNodeUnified.SIZE_BYTES)
                               .order(ByteOrder.nativeOrder());
        original.writeTo(buffer);
        buffer.flip();

        // Read back
        var restored = ESVTNodeUnified.readFrom(buffer);

        assertEquals(original.getTetType(), restored.getTetType());
        assertEquals(original.getChildMask(), restored.getChildMask());
        assertEquals(original.getLeafMask(), restored.getLeafMask());
        assertEquals(original.getChildPtr(), restored.getChildPtr());
        assertEquals(original.isFar(), restored.isFar());
        assertEquals(original.isValid(), restored.isValid());
        assertEquals(original.getContourMask(), restored.getContourMask());
        assertEquals(original.getNormalMask(), restored.getNormalMask());
        assertEquals(original.getContourPtr(), restored.getContourPtr());
    }

    @Test
    void testAllBitsCombined() {
        // Create a node with all fields set to non-zero values
        var node = new ESVTNodeUnified((byte) 5);
        node.setChildMask(0xAB);
        node.setLeafMask(0xCD);
        node.setChildPtr(0x1234);
        node.setFar(true);
        node.setContourMask(0xE);
        node.setNormalMask(0xF);
        node.setContourPtr(0xFFFFF);

        // Verify all fields are independent
        assertEquals(5, node.getTetType());
        assertEquals(0xAB, node.getChildMask());
        assertEquals(0xCD, node.getLeafMask());
        assertEquals(0x1234, node.getChildPtr());
        assertTrue(node.isFar());
        assertTrue(node.isValid());
        assertEquals(0xE, node.getContourMask());
        assertEquals(0xF, node.getNormalMask());
        assertEquals(0xFFFFF, node.getContourPtr());
    }

    @Test
    void testToString() {
        var node = new ESVTNodeUnified((byte) 2);
        node.setChildMask(0xFF);
        node.setChildPtr(100);

        String str = node.toString();
        assertTrue(str.contains("type=2"));
        assertTrue(str.contains("childMask=FF"));
        assertTrue(str.contains("ptr=100"));
    }
}
