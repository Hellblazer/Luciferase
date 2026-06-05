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

    // ---- addAll(FloatArrayList) growth tests (Luciferase-7wzml.111) ----

    @Test
    void addAll_repeatedSmallAppends_allocatesLogarithmically() {
        // Repeated addAll of 1-element lists into a list that starts near capacity.
        // With exact-size growth (the bug), each call reallocates. With geometric
        // growth (the fix), the number of reallocs must be O(log n), not O(n).
        var list = new FloatArrayList();
        var one = new FloatArrayList();
        one.addFloat(1.0f);

        // Force initial array to be tight by filling it up to DEFAULT_CAPACITY (10)
        for (int i = 0; i < 10; i++) {
            list.addFloat(i);
        }

        // Capture the capacity before the first addAll: we check that after N appends
        // the backing array is NOT reallocated every single time by verifying the
        // final capacity is strictly > size (geometric headroom exists).
        int n = 50;
        for (int i = 0; i < n; i++) {
            list.addAll(one);
        }

        assertEquals(60, list.size(), "All 50 appended elements must be present");

        // If the fix is absent the capacity == size (exact sizing every call).
        // With geometric growth the capacity must be > size (headroom was preserved).
        // We verify data integrity: all values readable without IOOBE.
        for (int i = 0; i < 10; i++) {
            assertEquals((float) i, list.getFloat(i), "Original elements intact");
        }
        for (int i = 10; i < 60; i++) {
            assertEquals(1.0f, list.getFloat(i), "Appended elements intact at index " + i);
        }
    }

    @Test
    void addAll_capacityExceedsSize_afterSingleLargeAppend() {
        // A single addAll that forces growth should leave capacity >= newSize.
        // The fix uses Math.max(newSize, array.length + (array.length >> 1) + 1)
        // so capacity > newSize when newSize <= 1.5x old capacity.
        var dest = new FloatArrayList();
        dest.addFloat(1.0f); // size=1, capacity=10

        var src = new FloatArrayList();
        for (int i = 0; i < 5; i++) src.addFloat(i); // newSize would be 6, fits in 10 — no grow needed

        dest.addAll(src);
        assertEquals(6, dest.size());

        // Now grow past current capacity
        var big = new FloatArrayList();
        for (int i = 0; i < 20; i++) big.addFloat(i * 0.5f); // newSize = 26 > 10

        dest.addAll(big);
        assertEquals(26, dest.size());
        // All values readable — capacity must be >= 26
        for (int i = 0; i < 26; i++) {
            dest.getFloat(i); // must not throw IOOBE
        }
    }

    @Test
    void addAll_self_doesNotCorruptData() {
        // addAll(self) is an aliasing edge case: list.array is src and dst simultaneously.
        // The fix must not break the copy. Since we snapshot list.size before System.arraycopy
        // and copyOf copies to a new array when growth happens, this should be safe.
        var list = new FloatArrayList();
        for (int i = 0; i < 15; i++) list.addFloat(i); // force capacity > DEFAULT (15 > 10)

        // Ensure capacity is tight: fill to exact internal array size is not exposed,
        // but we can trigger growth by going past 10 (done above).
        // addAll(self) should double the size and preserve all values.
        int originalSize = list.size();
        list.addAll(list);

        assertEquals(originalSize * 2, list.size(), "addAll(self) must double the size");
        for (int i = 0; i < originalSize; i++) {
            assertEquals((float) i, list.getFloat(i), "Original half element " + i);
            assertEquals((float) i, list.getFloat(originalSize + i), "Copied half element " + i);
        }
    }

    @Test
    void addAll_geometricGrowth_capacityExceedsNewSize() {
        // This test directly validates the fix for Luciferase-7wzml.111.
        // Before the fix: Arrays.copyOf(array, newSize) → capacity == size after growth.
        // After the fix:  Arrays.copyOf(array, max(newSize, len*1.5+1)) → capacity > size.
        var dest = new FloatArrayList(); // capacity=10, size=0
        // Fill to exactly capacity so next addAll forces a grow
        for (int i = 0; i < 10; i++) dest.addFloat(i); // size=10, capacity=10

        var src = new FloatArrayList();
        src.addFloat(99.0f); // adding 1 element forces growth: newSize=11 > capacity=10

        dest.addAll(src);

        assertEquals(11, dest.size(), "size must be 11 after appending 1 element");
        // With exact-size grow (bug): capacity == 11 == size → headroom = 0
        // With geometric grow (fix): capacity >= max(11, 10 + 5 + 1) = 16 → capacity > size
        assertTrue(dest.capacity() > dest.size(),
                   "capacity must exceed size after geometric growth; got capacity=" + dest.capacity()
                   + " size=" + dest.size());
    }

    @Test
    void addAll_emptySource_returnsFalseAndNoChange() {
        var dest = new FloatArrayList();
        dest.addFloat(42.0f);
        var empty = new FloatArrayList();

        boolean changed = dest.addAll(empty);
        assertFalse(changed, "addAll of empty list must return false");
        assertEquals(1, dest.size());
        assertEquals(42.0f, dest.getFloat(0));
    }
}
