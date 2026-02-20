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
package com.hellblazer.luciferase.simulation.viz.render;

import com.hellblazer.luciferase.lucien.SpatialKey;

import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks keyVersion per SpatialKey.
 * dirty ≡ version(key) > cacheVersion(key).
 * Version 0 means "never dirtied".
 *
 * <p>This is the "clock" that drives the streaming pipeline. When an entity moves,
 * {@link #bumpAll(Collection)} is called for all affected SpatialKey cells. When
 * StreamingCache holds a cached region, it stores the {@code cacheVersion} it was
 * built at. {@link #isDirty(SpatialKey, long)} returns true when a rebuild is needed.
 *
 * <p>Thread-safe: all operations are lock-free via ConcurrentHashMap and AtomicLong.
 *
 * @author hal.hildebrand
 */
public final class DirtyTracker {

    private final ConcurrentHashMap<SpatialKey<?>, AtomicLong> keyVersions = new ConcurrentHashMap<>();

    /**
     * Current version for key. Returns 0 if never dirtied.
     *
     * @param key the spatial key to query
     * @return the current version, or 0 if the key has never been bumped
     */
    public long version(SpatialKey<?> key) {
        var counter = keyVersions.get(key);
        return counter == null ? 0L : counter.get();
    }

    /**
     * Increment version for key. Returns new version.
     *
     * @param key the spatial key to bump
     * @return the new version after incrementing
     */
    public long bump(SpatialKey<?> key) {
        return keyVersions.computeIfAbsent(key, k -> new AtomicLong(0L)).incrementAndGet();
    }

    /**
     * Bump all keys in the collection. Used when an entity moves.
     *
     * @param keys the collection of spatial keys to bump
     */
    public void bumpAll(Collection<SpatialKey<?>> keys) {
        Objects.requireNonNull(keys, "keys must not be null");
        keys.forEach(this::bump);
    }

    /**
     * Returns true iff version(key) > cacheVersion.
     *
     * @param key          the spatial key to check
     * @param cacheVersion the version at which the cache was last built
     * @return true if the key has been dirtied since the cache was built
     */
    public boolean isDirty(SpatialKey<?> key, long cacheVersion) {
        return version(key) > cacheVersion;
    }
}
