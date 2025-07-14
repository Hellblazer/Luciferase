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

import com.hellblazer.luciferase.geometry.MortonCurve;
import com.hellblazer.luciferase.lucien.Spatial;
import com.hellblazer.luciferase.lucien.VolumeBounds;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * A handle representing a spatial range that defers key generation until needed.
 * This provides a first-class object for spatial ranges without materializing all keys.
 *
 * @author hal.hildebrand
 */
public class RangeHandle {
    
    private final VolumeBounds bounds;
    private final boolean includeIntersecting;
    private final byte level;
    private final Tet rootTet;
    
    /**
     * Create a range handle for the given spatial bounds.
     *
     * @param rootTet The root tetrahedron context
     * @param bounds The spatial bounds of the range
     * @param includeIntersecting Whether to include intersecting tetrahedra
     * @param level The target level for the query
     */
    public RangeHandle(Tet rootTet, VolumeBounds bounds, boolean includeIntersecting, byte level) {
        this.rootTet = Objects.requireNonNull(rootTet, "Root tetrahedron cannot be null");
        this.bounds = Objects.requireNonNull(bounds, "Bounds cannot be null");
        this.includeIntersecting = includeIntersecting;
        this.level = level;
    }
    
    /**
     * Create a range handle from a Spatial volume.
     *
     * @param rootTet The root tetrahedron context
     * @param volume The spatial volume
     * @param includeIntersecting Whether to include intersecting tetrahedra
     * @param level The target level for the query
     * @return A new range handle, or null if volume cannot be bounded
     */
    public static RangeHandle from(Tet rootTet, Spatial volume, boolean includeIntersecting, byte level) {
        var bounds = VolumeBounds.from(volume);
        if (bounds == null) {
            return null;
        }
        return new RangeHandle(rootTet, bounds, includeIntersecting, level);
    }
    
    /**
     * Check if any tetrahedron exists in this range.
     * This is optimized for early termination.
     *
     * @return true if at least one tetrahedron exists in the range
     */
    public boolean exists() {
        return stream().findAny().isPresent();
    }
    
    /**
     * Check if any tetrahedron in this range matches the predicate.
     *
     * @param predicate The test predicate
     * @return true if any tetrahedron matches
     */
    public boolean anyMatch(Predicate<TetreeKey<? extends TetreeKey>> predicate) {
        return stream().anyMatch(predicate);
    }
    
    /**
     * Count the number of tetrahedra in this range.
     * Note: This requires iterating through all keys.
     *
     * @return The count of tetrahedra
     */
    public long count() {
        return stream().count();
    }
    
    /**
     * Get the first tetrahedron in SFC order from this range.
     *
     * @return The first key, or null if range is empty
     */
    public TetreeKey<? extends TetreeKey> first() {
        return stream().findFirst().orElse(null);
    }
    
    /**
     * Get a lazy stream of all tetrahedra in this range.
     *
     * @return A lazy stream of TetreeKeys
     */
    public Stream<TetreeKey<? extends TetreeKey>> stream() {
        // Create a simple bounding box volume for the query
        var volume = new BoundingBox(bounds);
        
        // Use the public boundedBy method for contained tetrahedra
        // or create a custom implementation for intersecting ones
        if (includeIntersecting) {
            // For intersecting, we need to implement custom logic
            // since there's no public intersecting method that returns a stream
            return streamIntersecting();
        } else {
            return rootTet.boundedBy(volume);
        }
    }
    
    /**
     * Stream tetrahedra that intersect the bounds.
     */
    private Stream<TetreeKey<? extends TetreeKey>> streamIntersecting() {
        // Simple implementation: check all tetrahedra at the target level
        // In practice, this would use spatial indexing for efficiency
        int cellSize = (1 << (MortonCurve.MAX_REFINEMENT_LEVEL - level));
        
        List<TetreeKey<? extends TetreeKey>> results = new ArrayList<>();
        
        // Iterate through grid cells that could intersect
        for (int x = (int)(bounds.minX() / cellSize) * cellSize; 
             x <= bounds.maxX(); 
             x += cellSize) {
            for (int y = (int)(bounds.minY() / cellSize) * cellSize; 
                 y <= bounds.maxY(); 
                 y += cellSize) {
                for (int z = (int)(bounds.minZ() / cellSize) * cellSize; 
                     z <= bounds.maxZ(); 
                     z += cellSize) {
                    // Check all 6 tetrahedra in this cell
                    for (byte type = 0; type < 6; type++) {
                        var tet = new Tet(x, y, z, level, type);
                        if (tetrahedronIntersectsBounds(tet)) {
                            results.add(tet.tmIndex());
                        }
                    }
                }
            }
        }
        
        return results.stream();
    }
    
    /**
     * Check if a tetrahedron intersects the bounds.
     */
    private boolean tetrahedronIntersectsBounds(Tet tet) {
        // Simple AABB check for now
        var vertices = tet.coordinates();
        
        // Check if any vertex is inside bounds
        for (var v : vertices) {
            if (v.x >= bounds.minX() && v.x <= bounds.maxX() &&
                v.y >= bounds.minY() && v.y <= bounds.maxY() &&
                v.z >= bounds.minZ() && v.z <= bounds.maxZ()) {
                return true;
            }
        }
        
        // More sophisticated intersection tests would go here
        return false;
    }
    
    /**
     * Simple bounding box implementation of Spatial interface.
     */
    private static class BoundingBox implements Spatial {
        private final VolumeBounds bounds;
        
        BoundingBox(VolumeBounds bounds) {
            this.bounds = bounds;
        }
        
        @Override
        public boolean containedBy(Spatial.aabt aabt) {
            // Check if this bounding box is completely contained within the tetrahedral bounds
            return bounds.minX() >= aabt.originX() && bounds.maxX() <= aabt.extentX() &&
                   bounds.minY() >= aabt.originY() && bounds.maxY() <= aabt.extentY() &&
                   bounds.minZ() >= aabt.originZ() && bounds.maxZ() <= aabt.extentZ();
        }
        
        @Override
        public boolean intersects(float originX, float originY, float originZ, 
                                 float extentX, float extentY, float extentZ) {
            // AABB intersection test
            return !(bounds.maxX() < originX || bounds.minX() > extentX ||
                    bounds.maxY() < originY || bounds.minY() > extentY ||
                    bounds.maxZ() < originZ || bounds.minZ() > extentZ);
        }
    }
    
    /**
     * Get a lazy iterator over all tetrahedra in this range.
     *
     * @return A lazy iterator
     */
    public Iterator<TetreeKey<? extends TetreeKey>> iterator() {
        return stream().iterator();
    }
    
    /**
     * Split this range handle into multiple handles for parallel processing.
     *
     * @param parts The number of parts to split into
     * @return An array of range handles
     */
    public RangeHandle[] split(int parts) {
        if (parts <= 1) {
            return new RangeHandle[] { this };
        }
        
        // Simple spatial splitting based on bounds
        float xRange = bounds.maxX() - bounds.minX();
        float yRange = bounds.maxY() - bounds.minY();
        float zRange = bounds.maxZ() - bounds.minZ();
        
        // Split along the longest dimension
        RangeHandle[] handles = new RangeHandle[parts];
        
        if (xRange >= yRange && xRange >= zRange) {
            // Split along X
            float step = xRange / parts;
            for (int i = 0; i < parts; i++) {
                float minX = bounds.minX() + i * step;
                float maxX = (i == parts - 1) ? bounds.maxX() : bounds.minX() + (i + 1) * step;
                var subBounds = new VolumeBounds(minX, maxX, bounds.minY(), bounds.maxY(), 
                                               bounds.minZ(), bounds.maxZ());
                handles[i] = new RangeHandle(rootTet, subBounds, includeIntersecting, level);
            }
        } else if (yRange >= zRange) {
            // Split along Y
            float step = yRange / parts;
            for (int i = 0; i < parts; i++) {
                float minY = bounds.minY() + i * step;
                float maxY = (i == parts - 1) ? bounds.maxY() : bounds.minY() + (i + 1) * step;
                var subBounds = new VolumeBounds(bounds.minX(), bounds.maxX(), minY, maxY,
                                               bounds.minZ(), bounds.maxZ());
                handles[i] = new RangeHandle(rootTet, subBounds, includeIntersecting, level);
            }
        } else {
            // Split along Z
            float step = zRange / parts;
            for (int i = 0; i < parts; i++) {
                float minZ = bounds.minZ() + i * step;
                float maxZ = (i == parts - 1) ? bounds.maxZ() : bounds.minZ() + (i + 1) * step;
                var subBounds = new VolumeBounds(bounds.minX(), bounds.maxX(), bounds.minY(), bounds.maxY(),
                                               minZ, maxZ);
                handles[i] = new RangeHandle(rootTet, subBounds, includeIntersecting, level);
            }
        }
        
        return handles;
    }
    
    /**
     * Estimate the size of this range without iterating.
     *
     * @return An estimate of the number of tetrahedra
     */
    public long estimateSize() {
        // Calculate based on volume and level
        int tetLength = (1 << (MortonCurve.MAX_REFINEMENT_LEVEL - level));
        
        long xCells = (long) Math.ceil((bounds.maxX() - bounds.minX()) / tetLength);
        long yCells = (long) Math.ceil((bounds.maxY() - bounds.minY()) / tetLength);
        long zCells = (long) Math.ceil((bounds.maxZ() - bounds.minZ()) / tetLength);
        
        // Each cell has 6 tetrahedra
        return xCells * yCells * zCells * 6;
    }
    
    /**
     * Get the spatial bounds of this range.
     *
     * @return The volume bounds
     */
    public VolumeBounds getBounds() {
        return bounds;
    }
    
    /**
     * Get the query level.
     *
     * @return The level
     */
    public byte getLevel() {
        return level;
    }
    
    /**
     * Check if this range includes intersecting tetrahedra.
     *
     * @return true if intersecting tetrahedra are included
     */
    public boolean isIncludeIntersecting() {
        return includeIntersecting;
    }
}
