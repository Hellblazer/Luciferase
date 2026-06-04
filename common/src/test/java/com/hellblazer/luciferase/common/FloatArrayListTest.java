// SPDX-License-Identifier: Apache-2.0
package com.hellblazer.luciferase.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class FloatArrayListTest {

    @Test
    void removeRange_toIndexBeyondSize_throwsIOOBE() {
        var list = new FloatArrayList();
        list.add(1.0f);
        list.add(2.0f);
        list.add(3.0f);
        int size = list.size();
        assertThrows(IndexOutOfBoundsException.class, () -> list.removeRange(0, size + 1),
                     "removeRange(0, size+1) must throw IOOBE, not corrupt via negative arraycopy length");
    }

    @Test
    void removeRange_negativeFromIndex_throwsIOOBE() {
        var list = new FloatArrayList();
        list.add(10.0f);
        list.add(20.0f);
        list.add(30.0f);
        assertThrows(IndexOutOfBoundsException.class, () -> list.removeRange(-1, 2));
    }

    @Test
    void removeRange_emptyRange_isNoOp() {
        var list = new FloatArrayList();
        list.add(1.0f);
        list.add(2.0f);
        list.add(3.0f);
        list.removeRange(2, 2); // fromIndex == toIndex → no-op
        assertEquals(3, list.size(), "removeRange(2,2) must not change size");
        assertEquals(1.0f, list.getFloat(0));
        assertEquals(2.0f, list.getFloat(1));
        assertEquals(3.0f, list.getFloat(2));
    }

    @Test
    void removeRange_validMidRange_shiftsCorrectlyAndDecrementsSize() {
        var list = new FloatArrayList();
        list.add(1.0f);
        list.add(2.0f);
        list.add(3.0f);
        list.add(4.0f);
        list.add(5.0f);
        list.removeRange(1, 3); // removes indices 1,2 → [1.0,4.0,5.0]
        assertEquals(3, list.size());
        assertEquals(1.0f, list.getFloat(0));
        assertEquals(4.0f, list.getFloat(1));
        assertEquals(5.0f, list.getFloat(2));
    }
}
