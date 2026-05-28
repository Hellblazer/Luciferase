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
package com.hellblazer.luciferase.lucien;

import javax.vecmath.Point3f;
import java.util.List;
import java.util.NavigableSet;

/**
 * Base interface for all spatial index keys.
 *
 * This interface defines the contract for keys used in spatial index structures. Each spatial structure (Octree,
 * Tetree, etc.) implements its own key type that encodes the necessary information for unique spatial identification.
 *
 * Keys must be immutable and implement proper equals/hashCode semantics. The Comparable ordering must preserve spatial
 * locality where possible.
 *
 * @param <K> The concrete key type (self-referential for type safety)
 * @author hal.hildebrand
 */
public interface SpatialKey<K extends SpatialKey<K>> extends Comparable<K> {

    /**
     * Generic SFC range covering a query region in the spatial-key total order. The {@code (lower, upper)} pair is
     * fed directly into {@code ConcurrentSkipListMap.subMap(lower, upper)} to prune k-NN search to the candidate
     * keys that could contain entities within the search radius. RDR-008 P3 follow-up (bead Luciferase-vpl).
     *
     * @param lower the lower bound (inclusive) of the SFC range
     * @param upper the upper bound (exclusive) of the SFC range
     * @param <K>   the spatial key type
     */
    record SFCRange<K extends SpatialKey<K>>(K lower, K upper) {}

    /**
     * Build the SFC ranges {@link KnnSearcher KnnSearcher} should scan for a k-NN query centered at {@code center}
     * with search radius {@code radius}. RDR-008 P3 follow-up (bead Luciferase-vpl) — hoisted from the per-class
     * static methods {@code MortonKey.estimateSFCRange} / {@code TetreeKey.estimateSFCRange} so {@code KnnSearcher}
     * can dispatch through the interface instead of {@code instanceof}.
     *
     * <p>Implementations decide how many ranges to return:
     * <ul>
     *     <li>{@code MortonKey} returns one range per distinct storage level present in the index — required
     *         because {@code MortonKey.compareTo} orders keys by level first, so a subMap query at level L only
     *         returns keys at level L. The level set is collected from {@code indexKeys}.</li>
     *     <li>{@code TetreeKey} returns a single range and ignores {@code indexKeys} — its ordering is not
     *         level-scoped.</li>
     *     <li>Implementations without an SFC range estimator (e.g. {@code PrismKey}) return the default empty
     *         iterable, signaling {@code KnnSearcher} to fall back to the breadth-first search path.</li>
     * </ul>
     *
     * @param center     k-NN query point
     * @param radius     k-NN search radius
     * @param indexKeys  current key set of the spatial index (used by {@code MortonKey} for level collection)
     * @return iterable of SFC ranges, or empty iterable for "no SFC pruning support"
     */
    default Iterable<SFCRange<K>> sfcRangesForKNN(Point3f center, float radius, NavigableSet<K> indexKeys) {
        return List.of();
    }

    /**
     * Get the level of this key in the spatial hierarchy. Level 0 represents the root, with increasing levels
     * representing finer subdivisions of space.
     *
     * This method is required for optimizations like SpatialIndexSet that need efficient level-based operations.
     *
     * @return the hierarchical level (0-based)
     */
    byte getLevel();

    /**
     * Check if this key represents a valid spatial location. Some key encodings may have invalid states that should be
     * rejected.
     *
     * @return true if this key represents a valid spatial location
     */
    default boolean isValid() {
        return true;
    }

    /**
     * Get the parent key of this spatial key in the hierarchy.
     *
     * @return the parent key, or null if this is the root (level 0)
     */
    K parent();

    /**
     * Answer the root cell of the subdivision
     *
     * @return K - the root cell of the subdivision
     */
    K root();

    /**
     * Get a human-readable string representation of this key. This should include all relevant components (level,
     * index, etc.) for debugging purposes.
     *
     * @return string representation of this key
     */
    @Override
    String toString();

    // toProtoSpatialKey() and fromProtoSpatialKey() were removed under
    // Luciferase-546. Serialisation now lives in ProtobufConverters and routes
    // through SpatialKeySerdeRegistry; adding a new SpatialKey implementation
    // requires registering a SpatialKeySerde, not editing this file.
}
