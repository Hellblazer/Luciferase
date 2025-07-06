/*
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien.tetree;

import java.util.Objects;

/**
 * A lazy implementation of ExtendedTetreeKey that defers the expensive tmIndex() computation until absolutely
 * necessary. This significantly improves insertion performance by avoiding the O(level) parent chain walk during
 * initial insertion.
 *
 * @author hal.hildebrand
 */
public class LazyTetreeKey extends ExtendedTetreeKey {

    private final    Tet                            tet;
    private final    int                            lazyHashCode;
    private volatile TetreeKey<? extends TetreeKey> resolved;

    /**
     * Create a lazy TetreeKey<? extends TetreeKey> from a Tet.
     *
     * @param tet the tetrahedron to lazily compute the key for
     */
    public LazyTetreeKey(Tet tet) {
        super(tet.l(), 0L, 0L);  // Use actual level, placeholder values
        this.tet = Objects.requireNonNull(tet, "Tet cannot be null");
        this.lazyHashCode = computeLazyHash(tet);
    }

    /**
     * Compute a hash code based on Tet coordinates for HashMap efficiency. This allows the key to be used in HashMap
     * without resolving tmIndex.
     */
    private static int computeLazyHash(Tet tet) {
        int hash = 31 * tet.x();
        hash = 31 * hash + tet.y();
        hash = 31 * hash + tet.z();
        hash = 31 * hash + tet.l();
        hash = 31 * hash + tet.type();
        return hash;
    }

    @Override
    public int compareTo(TetreeKey other) {
        if (other instanceof LazyTetreeKey lazy) {
            // Both lazy - only resolve when needed
            if (tet.equals(lazy.tet)) {
                return 0;
            }
            // Must resolve both for comparison
            ensureResolved();
            lazy.ensureResolved();
            return resolved.compareTo(lazy.resolved);
        }

        // Comparison with regular TetreeKey<? extends TetreeKey> requires resolution
        ensureResolved();
        return resolved.compareTo(other);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (obj instanceof LazyTetreeKey other) {
            // Both lazy - compare Tet directly without resolution
            return tet.equals(other.tet);
        } else if (obj instanceof TetreeKey<? extends TetreeKey>) {
            // Must resolve to compare with regular TetreeKey<? extends TetreeKey>
            ensureResolved();
            return resolved.equals(obj);
        }

        return false;
    }

    @Override
    public long getHighBits() {
        ensureResolved();
        return resolved.getHighBits();
    }

    @Override
    public byte getLevel() {
        // Can return immediately without resolution
        return tet.l();
    }

    @Override
    public long getLowBits() {
        ensureResolved();
        return resolved.getLowBits();
    }

    /**
     * Get the underlying Tet without triggering resolution.
     *
     * @return the Tet this key represents
     */
    public Tet getTet() {
        return tet;
    }

    @Override
    public int hashCode() {
        // Use pre-computed hash for HashMap efficiency
        return lazyHashCode;
    }

    /**
     * Check if this key has been resolved.
     *
     * @return true if tmIndex has been computed
     */
    public boolean isResolved() {
        return resolved != null;
    }

    /**
     * Explicitly resolve this key if not already resolved. Useful for batch resolution operations.
     */
    public void resolve() {
        ensureResolved();
    }

    @Override
    public String toString() {
        if (resolved != null) {
            return resolved.toString();
        }
        return String.format("LazyTetreeKey[tet=%s, unresolved]", tet);
    }

    /**
     * Force resolution of the tmIndex if not already resolved. This method is thread-safe and ensures the expensive
     * computation happens only once.
     */
    private void ensureResolved() {
        if (resolved == null) {
            synchronized (this) {
                if (resolved == null) {
                    // Always use tmIndex() - caching is handled internally
                    resolved = tet.tmIndex();
                }
            }
        }
    }
}
