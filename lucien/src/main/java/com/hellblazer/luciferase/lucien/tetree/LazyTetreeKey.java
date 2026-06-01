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
    private volatile TetreeKey<? extends TetreeKey<?>> resolved;

    /**
     * Create a lazy TetreeKey<? extends TetreeKey<?>> from a Tet.
     *
     * @param tet the tetrahedron to lazily compute the key for
     */
    public LazyTetreeKey(Tet tet) {
        super(tet.l(), 0L, 0L);  // Use actual level, placeholder values
        this.tet = Objects.requireNonNull(tet, "Tet cannot be null");
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

        // Comparison with regular TetreeKey<? extends TetreeKey<?>> requires resolution
        ensureResolved();
        return resolved.compareTo(other);
    }

    // equals()/hashCode() are final in TetreeKey (Luciferase-567m). The old lazy overrides were the source of the
    // asymmetry bug: lazy.equals(concrete) resolved while concrete.equals(lazy) did not, and lazyHashCode used a
    // Tet-coordinate polynomial that never matched the concrete key's tmIndex hash. The uniform base versions
    // resolve this key's bits (via getLowBits/getHighBits) so equal lazy/concrete keys are mutually equal and share
    // a hashCode. compareTo (below) keeps its lazy-vs-lazy fast path — it is not part of the equals/hashCode contract.

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
