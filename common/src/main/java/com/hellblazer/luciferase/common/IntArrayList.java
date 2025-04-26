// Protocol Buffers - Google's data interchange format
// Copyright 2008 Google Inc.  All rights reserved.
// https://developers.google.com/protocol-buffers/
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are
// met:
//
//     * Redistributions of source code must retain the above copyright
// notice, this list of conditions and the following disclaimer.
//     * Redistributions in binary form must reproduce the above
// copyright notice, this list of conditions and the following disclaimer
// in the documentation and/or other materials provided with the
// distribution.
//     * Neither the name of Google Inc. nor the names of its
// contributors may be used to endorse or promote products derived from
// this software without specific prior written permission.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
// "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
// LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
// A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
// OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
// SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
// LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
// DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
// THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
// (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
// OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

package com.hellblazer.luciferase.common;

import java.util.Arrays;
import java.util.Collection;
import java.util.RandomAccess;

/**
 * Chopped down implementation specialized for sentry
 */
public final class IntArrayList implements RandomAccess {

    private static final IntArrayList EMPTY_LIST       = new IntArrayList(new int[0], 0);
    private static final int          DEFAULT_CAPACITY = 10;

    /** The backing store for the list. */
    private int[] array;
    private int   size;

    IntArrayList() {
        this(new int[DEFAULT_CAPACITY], 0);
    }

    private IntArrayList(int[] other, int size) {
        array = other;
        this.size = size;
    }

    public static IntArrayList emptyList() {
        return EMPTY_LIST;
    }

    public boolean add(Integer element) {
        addInt(element);
        return true;
    }

    public void add(int index, Integer element) {
        addInt(index, element);
    }

    public boolean addAll(Collection<? extends Integer> collection) {
        for (var e : collection)
            add(e);
        return true;
    }

    public boolean addAll(IntArrayList list) {
        if (list.size == 0) {
            return false;
        }

        int overflow = Integer.MAX_VALUE - size;
        if (overflow < list.size) {
            // We can't actually represent a list this large.
            throw new OutOfMemoryError();
        }

        int newSize = size + list.size;
        if (newSize > array.length) {
            array = Arrays.copyOf(array, newSize);
        }

        System.arraycopy(list.array, 0, array, size, list.size);
        size = newSize;
        return true;
    }

    /** Like {@link #add(Integer)} but more efficient in that it doesn't box the element. */

    public void addInt(int element) {
        if (size == array.length) {
            // Resize to 1.5x the size
            int length = ((size * 3) / 2) + 1;
            int[] newArray = new int[length];

            System.arraycopy(array, 0, newArray, 0, size);
            array = newArray;
        }

        array[size++] = element;
    }

    public boolean contains(Object element) {
        return indexOf(element) != -1;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof final IntArrayList other)) {
            return super.equals(o);
        }
        if (size != other.size) {
            return false;
        }

        final int[] arr = other.array;
        for (int i = 0; i < size; i++) {
            if (array[i] != arr[i]) {
                return false;
            }
        }

        return true;
    }

    public Integer get(int index) {
        return getInt(index);
    }

    public int getInt(int index) {
        ensureIndexInRange(index);
        return array[index];
    }

    @Override
    public int hashCode() {
        int result = 1;
        for (int i = 0; i < size; i++) {
            result = (31 * result) + array[i];
        }
        return result;
    }

    public int indexOf(Object element) {
        if (!(element instanceof Integer)) {
            return -1;
        }
        int unboxedElement = (Integer) element;
        int numElems = size();
        for (int i = 0; i < numElems; i++) {
            if (array[i] == unboxedElement) {
                return i;
            }
        }
        return -1;
    }

    public Integer remove(int index) {
        ensureIndexInRange(index);
        int value = array[index];
        if (index < size - 1) {
            System.arraycopy(array, index + 1, array, index, size - index - 1);
        }
        size--;
        return value;
    }

    public void removeRange(int fromIndex, int toIndex) {
        if (toIndex < fromIndex) {
            throw new IndexOutOfBoundsException("toIndex < fromIndex");
        }

        System.arraycopy(array, toIndex, array, fromIndex, size - toIndex);
        size -= (toIndex - fromIndex);
    }

    public Integer set(int index, Integer element) {
        return setInt(index, element);
    }

    public int setInt(int index, int element) {
        ensureIndexInRange(index);
        int previousValue = array[index];
        array[index] = element;
        return previousValue;
    }

    public int size() {
        return size;
    }

    private void addInt(int index, int element) {
        if (index < 0 || index > size) {
            throw new IndexOutOfBoundsException(makeOutOfBoundsExceptionMessage(index));
        }

        if (size < array.length) {
            // Shift everything over to make room
            System.arraycopy(array, index, array, index + 1, size - index);
        } else {
            // Resize to 1.5x the size
            int length = ((size * 3) / 2) + 1;
            int[] newArray = new int[length];

            System.arraycopy(array, 0, newArray, 0, index);

            System.arraycopy(array, index, newArray, index + 1, size - index);
            array = newArray;
        }

        array[index] = element;
        size++;
    }

    private void ensureIndexInRange(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException(makeOutOfBoundsExceptionMessage(index));
        }
    }

    private String makeOutOfBoundsExceptionMessage(int index) {
        return "Index:" + index + ", Size:" + size;
    }
}
