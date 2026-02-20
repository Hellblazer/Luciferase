/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.simulation.viz.render;

import com.hellblazer.luciferase.lucien.SpatialKey;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe cache of built region data, keyed on SpatialKey<?>.
 *
 * <p>Each entry holds a version stamp (the scene version at which the region was last built) and
 * the serialized region bytes. Callers compare the cached version against the current scene
 * version to decide whether to re-build.
 *
 * <p>This cache has no eviction policy and is appropriate only for bounded, short-lived build
 * pipelines where the key set is finite and managed externally.
 *
 * @author hal.hildebrand
 */
public final class StreamingCache {

    /**
     * A single cached region: the scene version when it was built, and the serialized bytes.
     * Defensive copies are made on write ({@link StreamingCache#put}) and read ({@link #data()})
     * to prevent external mutation of cached state.
     */
    public record CacheEntry(long version, byte[] data) {
        /** Returns a defensive copy of the serialized data. */
        @Override
        public byte[] data() {
            return data.clone();
        }
    }

    private final ConcurrentHashMap<SpatialKey<?>, CacheEntry> entries = new ConcurrentHashMap<>();

    /**
     * Returns the cached entry for the given key, or {@code null} if no entry exists.
     */
    public CacheEntry get(SpatialKey<?> key) {
        return entries.get(key);
    }

    /**
     * Stores (or replaces) the cached entry for the given key.
     *
     * @param key     spatial key identifying the region
     * @param version scene version at which this data was built
     * @param data    serialized region bytes
     */
    public void put(SpatialKey<?> key, long version, byte[] data) {
        entries.put(key, new CacheEntry(version, data.clone()));
    }

    /**
     * Removes the cached entry for the given key, if present.
     */
    public void remove(SpatialKey<?> key) {
        entries.remove(key);
    }

    /**
     * Returns the cached version for the given key, or {@code 0L} if the key has never been built.
     */
    public long cacheVersion(SpatialKey<?> key) {
        var entry = entries.get(key);
        return entry == null ? 0L : entry.version();
    }
}
