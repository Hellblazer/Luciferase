// SPDX-License-Identifier: Apache-2.0
package com.hellblazer.luciferase.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class IntArrayListTest {

    @Test
    void removeRange_toIndexBeyondSize_throwsIOOBE() {
        var list = new IntArrayList();
        list.add(1);
        list.add(2);
        list.add(3);
        int size = list.size();
        assertThrows(IndexOutOfBoundsException.class, () -> list.removeRange(0, size + 1),
                     "removeRange(0, size+1) must throw IOOBE, not corrupt via negative arraycopy length");
    }

    @Test
    void removeRange_negativeFromIndex_throwsIOOBE() {
        var list = new IntArrayList();
        list.add(10);
        list.add(20);
        list.add(30);
        assertThrows(IndexOutOfBoundsException.class, () -> list.removeRange(-1, 2));
    }

    @Test
    void removeRange_emptyRange_isNoOp() {
        var list = new IntArrayList();
        list.add(1);
        list.add(2);
        list.add(3);
        list.removeRange(2, 2); // fromIndex == toIndex → no-op
        assertEquals(3, list.size(), "removeRange(2,2) must not change size");
        assertEquals(1, list.getInt(0));
        assertEquals(2, list.getInt(1));
        assertEquals(3, list.getInt(2));
    }

    @Test
    void removeRange_validMidRange_shiftsCorrectlyAndDecrementsSize() {
        var list = new IntArrayList();
        list.add(1);
        list.add(2);
        list.add(3);
        list.add(4);
        list.add(5);
        list.removeRange(1, 3); // removes indices 1,2 → [1,4,5]
        assertEquals(3, list.size());
        assertEquals(1, list.getInt(0));
        assertEquals(4, list.getInt(1));
        assertEquals(5, list.getInt(2));
    }
}
