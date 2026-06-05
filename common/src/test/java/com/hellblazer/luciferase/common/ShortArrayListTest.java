// SPDX-License-Identifier: Apache-2.0
package com.hellblazer.luciferase.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ShortArrayListTest {

    @Test
    void removeRange_toIndexBeyondSize_throwsIOOBE() {
        var list = new ShortArrayList();
        list.add((short) 1);
        list.add((short) 2);
        list.add((short) 3);
        int size = list.size();
        assertThrows(IndexOutOfBoundsException.class, () -> list.removeRange(0, size + 1),
                     "removeRange(0, size+1) must throw IOOBE, not corrupt via negative arraycopy length");
    }

    @Test
    void removeRange_negativeFromIndex_throwsIOOBE() {
        var list = new ShortArrayList();
        list.add((short) 10);
        list.add((short) 20);
        list.add((short) 30);
        assertThrows(IndexOutOfBoundsException.class, () -> list.removeRange(-1, 2));
    }

    @Test
    void removeRange_emptyRange_isNoOp() {
        var list = new ShortArrayList();
        list.add((short) 1);
        list.add((short) 2);
        list.add((short) 3);
        list.removeRange(2, 2); // fromIndex == toIndex → no-op
        assertEquals(3, list.size(), "removeRange(2,2) must not change size");
        assertEquals((short) 1, list.getShort(0));
        assertEquals((short) 2, list.getShort(1));
        assertEquals((short) 3, list.getShort(2));
    }

    @Test
    void removeRange_validMidRange_shiftsCorrectlyAndDecrementsSize() {
        var list = new ShortArrayList();
        list.add((short) 1);
        list.add((short) 2);
        list.add((short) 3);
        list.add((short) 4);
        list.add((short) 5);
        list.removeRange(1, 3); // removes indices 1,2 → [1,4,5]
        assertEquals(3, list.size());
        assertEquals((short) 1, list.getShort(0));
        assertEquals((short) 4, list.getShort(1));
        assertEquals((short) 5, list.getShort(2));
    }
}
