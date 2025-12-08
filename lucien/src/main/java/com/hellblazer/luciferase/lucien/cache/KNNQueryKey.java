/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.cache;

import com.hellblazer.luciferase.lucien.SpatialKey;

/**
 * Composite cache key for k-NN queries that includes all query parameters.
 * 
 * This ensures that queries with different k or maxDistance parameters don't
 * incorrectly share cached results. The cache key must include:
 * - Spatial position (via SpatialKey)
 * - Number of neighbors (k)
 * - Maximum search distance (maxDistance)
 *
 * @author hal.hildebrand
 * @param <Key> Spatial key type (MortonKey or TetreeKey)
 */
public record KNNQueryKey<Key extends SpatialKey<Key>>(
    Key spatialKey,
    int k,
    float maxDistance
) {
    public KNNQueryKey {
        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive: " + k);
        }
        if (maxDistance <= 0) {
            throw new IllegalArgumentException("maxDistance must be positive: " + maxDistance);
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof KNNQueryKey<?> other)) return false;
        
        return this.k == other.k &&
               Float.compare(this.maxDistance, other.maxDistance) == 0 &&
               this.spatialKey.equals(other.spatialKey);
    }

    @Override
    public int hashCode() {
        int result = spatialKey.hashCode();
        result = 31 * result + Integer.hashCode(k);
        result = 31 * result + Float.hashCode(maxDistance);
        return result;
    }

    @Override
    public String toString() {
        return "KNNQueryKey[key=" + spatialKey + ", k=" + k + ", maxDist=" + maxDistance + "]";
    }
}
