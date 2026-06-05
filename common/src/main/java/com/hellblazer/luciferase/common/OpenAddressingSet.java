/** 
 * (C) Copyright 2009 Hal Hildebrand, All Rights Reserved
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 *     
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, 
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 * See the License for the specific language governing permissions and 
 * limitations under the License.
 */
package com.hellblazer.luciferase.common;

import java.util.AbstractSet;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * An open-addressing hash set with linear (double-hashing) probing.
 *
 * <p><strong>Thread-safety:</strong> This class is <em>NOT thread-safe</em>.
 * All access from multiple threads must be externally synchronized. There is
 * no internal locking. Iterators are fail-fast: if the set is structurally
 * modified at any time after the iterator is created, the iterator will throw
 * a {@link ConcurrentModificationException} on the next call to
 * {@link Iterator#hasNext()} or {@link Iterator#next()}.
 *
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 */
public abstract class OpenAddressingSet<T> extends AbstractSet<T> {

    private static final Object DELETED   = new Object();
    private static final int    PRIME     = -1640531527;
    private static final float  THRESHOLD = 0.75f;
    int                         load;
    int                         modCount  = 0;
    /** Live entries only. */
    int                         size      = 0;
    /**
     * Occupied slots = live entries + tombstone (DELETED) entries. Used to
     * trigger rehash when tombstones accumulate and the table would saturate
     * even though the live count stays below the threshold.
     */
    int                         occupied  = 0;
    Object                      table[];

    public OpenAddressingSet() {
        this(4);
    }

    public OpenAddressingSet(int initialCapacity) {
        init(initialCapacity);
    }

    @Override
    public final boolean add(Object key) {
        if (key == null) {
            throw new IllegalArgumentException("Null key");
        }
        if (table == null) {
            init(1);
        } else if (occupied >= table.length * THRESHOLD) {
            rehash();
        }
        boolean added = insert(key);
        if (added) {
            modCount++;
        }
        return added;
    }

    @Override
    public void clear() {
        modCount++;
        if (table != null) {
            java.util.Arrays.fill(table, null);
        }
        size = 0;
        occupied = 0;
    }

    @Override
    public OpenAddressingSet<T> clone() {
        try {
            @SuppressWarnings("unchecked")
            OpenAddressingSet<T> t = (OpenAddressingSet<T>) super.clone();
            if (table != null) {
                t.table = new Object[table.length];
                for (int i = table.length; i-- > 0;) {
                    t.table[i] = table[i];
                }
            }
            return t;
        } catch (CloneNotSupportedException e) {
            throw new InternalError();
        }
    }

    @Override
    public boolean contains(Object key) {
        if (key == null || size == 0) {
            return false;
        }
        int hash = PRIME * getHash(key) >>> load;
        int index = hash;
        do {
            Object ob = table[index];
            if (ob == null) {
                return false;
            }
            if (equals(key, ob)) {
                return true;
            }
            index = (index + (hash | 1)) & (table.length - 1);
        } while (index != hash);
        return false;
    }

    @Override
    public final boolean isEmpty() {
        return size() == 0;
    }

    @Override
    public Iterator<T> iterator() {
        // Snapshot the table reference and modCount at iterator-creation time.
        // Any structural modification after this point (add/remove/clear that
        // changes modCount) will be detected and throw CME.
        final Object[] snapshot = table;
        final int      expectedModCount = modCount;
        return new Iterator<T>() {
            int next = 0;

            private void checkForComodification() {
                if (modCount != expectedModCount) {
                    throw new ConcurrentModificationException();
                }
            }

            @Override
            public boolean hasNext() {
                checkForComodification();
                if (snapshot == null) {
                    return false;
                }
                while (next < snapshot.length) {
                    if (snapshot[next] != null && snapshot[next] != DELETED) {
                        return true;
                    }
                    next++;
                }
                return false;
            }

            @SuppressWarnings("unchecked")
            @Override
            public T next() {
                checkForComodification();
                if (snapshot == null) {
                    throw new NoSuchElementException("Enumerator");
                }
                while (next < snapshot.length) {
                    if (snapshot[next] != null && snapshot[next] != DELETED) {
                        return (T) snapshot[next++];
                    }
                    next++;
                }
                throw new NoSuchElementException("Enumerator");
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException("Remove is not supported");
            }
        };
    }

    @Override
    public final boolean remove(Object key) {
        if (key == null) {
            throw new IllegalArgumentException("Null key");
        }
        if (!isEmpty()) {
            int hash = PRIME * getHash(key) >>> load;
            int index = hash;
            do {
                Object ob = table[index];
                if (ob == null) {
                    return false;
                }
                if (equals(key, ob)) {
                    table[index] = DELETED;
                    size -= 1;
                    modCount++;
                    return true;
                }
                index = (index + (hash | 1)) & (table.length - 1);
            } while (index != hash);
        }
        return false;
    }

    @Override
    public final int size() {
        return size;
    }

    private boolean insert(Object key) {
        int hash = PRIME * getHash(key) >>> load;
        int index = hash;
        int firstDeleted = -1;
        do {
            Object ob = table[index];
            if (ob == null) {
                // End of the probe chain. Reuse the earliest DELETED tombstone
                // if we saw one; otherwise insert at this null slot. Required
                // to avoid duplicates when the key already exists past a
                // tombstone earlier in the chain.
                int target = (firstDeleted >= 0) ? firstDeleted : index;
                table[target] = key;
                size += 1;
                // Only consume a new null slot when no tombstone was reused;
                // reusing a tombstone does not increase occupied.
                if (firstDeleted < 0) {
                    occupied += 1;
                }
                return true;
            }
            if (ob == DELETED) {
                if (firstDeleted < 0) {
                    firstDeleted = index;
                }
            } else if (equals(key, ob)) {
                table[index] = key;
                return false;
            }
            index = (index + (hash | 1)) & (table.length - 1);
        } while (index != hash);
        // Probe wrapped without finding null. If we saw a tombstone we can
        // reuse it; otherwise the table is genuinely full and needs a rehash.
        if (firstDeleted >= 0) {
            table[firstDeleted] = key;
            size += 1;
            return true;
        }
        rehash();
        return insert(key);
    }

    private void rehash() {
        Object[] oldMap = table;
        int oldCapacity = oldMap.length;
        load -= 1;
        // Do NOT bump modCount here: the caller (add) owns the count for the single
        // structural modification. rehash() swaps the table reference, which the
        // iterator's table snapshot already protects against; counting it again would
        // double-increment modCount for one logical add.
        table = new Object[oldCapacity * 2];
        size = 0;
        occupied = 0;
        for (int i = oldCapacity - 1; i >= 0; i -= 1) {
            Object ob = oldMap[i];
            if (ob != null && ob != DELETED) {
                insert(ob);
            }
        }
    }

    abstract protected boolean equals(Object key, Object ob);

    abstract protected int getHash(Object key);

    protected void init(int initialCapacity) {
        if (initialCapacity < 4) {
            initialCapacity = 4;
        }
        int cap = 4;
        load = 2;
        while (cap < initialCapacity) {
            load += 1;
            cap += cap;
        }
        table = new Object[cap];
        load = 32 - load;
        size = 0;
        occupied = 0;
    }

}