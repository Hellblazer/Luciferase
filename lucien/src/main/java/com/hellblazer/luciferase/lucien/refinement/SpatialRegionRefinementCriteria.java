/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien.refinement;

import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.VolumeBounds;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;

/**
 * Refinement criteria that refines nodes within specified spatial regions.
 * Useful for creating high-resolution meshes in areas of interest.
 *
 * @param <ID> The type of EntityID used
 * @author hal.hildebrand
 */
public class SpatialRegionRefinementCriteria<ID extends EntityID> implements RefinementCriteria<ID> {
    
    /**
     * Interface for defining regions of interest.
     */
    public interface RefinementRegion {
        /**
         * Check if a volume bounds intersects this region.
         */
        boolean intersects(VolumeBounds bounds);
        
        /**
         * Get the target refinement level for this region.
         */
        int getTargetLevel();
    }
    
    /**
     * Spherical refinement region.
     */
    public static class SphereRegion implements RefinementRegion {
        private final Point3f center;
        private final float radius;
        private final int targetLevel;
        
        public SphereRegion(Point3f center, float radius, int targetLevel) {
            this.center = new Point3f(center);
            this.radius = radius;
            this.targetLevel = targetLevel;
        }
        
        @Override
        public boolean intersects(VolumeBounds bounds) {
            // Check if sphere intersects the bounds
            // Find closest point on bounds to sphere center
            float closestX = Math.max(bounds.minX(), Math.min(center.x, bounds.maxX()));
            float closestY = Math.max(bounds.minY(), Math.min(center.y, bounds.maxY()));
            float closestZ = Math.max(bounds.minZ(), Math.min(center.z, bounds.maxZ()));
            
            // Check if closest point is within sphere
            float dx = closestX - center.x;
            float dy = closestY - center.y;
            float dz = closestZ - center.z;
            
            return (dx * dx + dy * dy + dz * dz) <= (radius * radius);
        }
        
        @Override
        public int getTargetLevel() {
            return targetLevel;
        }
    }
    
    /**
     * Box-shaped refinement region.
     */
    public static class BoxRegion implements RefinementRegion {
        private final VolumeBounds bounds;
        private final int targetLevel;
        
        public BoxRegion(Point3f min, Point3f max, int targetLevel) {
            this.bounds = new VolumeBounds(min.x, min.y, min.z, max.x, max.y, max.z);
            this.targetLevel = targetLevel;
        }
        
        @Override
        public boolean intersects(VolumeBounds other) {
            return bounds.intersects(other);
        }
        
        @Override
        public int getTargetLevel() {
            return targetLevel;
        }
    }
    
    private final List<RefinementRegion> regions;
    private final int defaultMaxLevel;
    
    /**
     * Create spatial region refinement criteria.
     *
     * @param defaultMaxLevel Default maximum level outside regions
     */
    public SpatialRegionRefinementCriteria(int defaultMaxLevel) {
        this.regions = new ArrayList<>();
        this.defaultMaxLevel = defaultMaxLevel;
    }
    
    /**
     * Add a refinement region.
     */
    public void addRegion(RefinementRegion region) {
        regions.add(region);
    }
    
    /**
     * Add a spherical refinement region.
     */
    public void addSphereRegion(Point3f center, float radius, int targetLevel) {
        addRegion(new SphereRegion(center, radius, targetLevel));
    }
    
    /**
     * Add a box refinement region.
     */
    public void addBoxRegion(Point3f min, Point3f max, int targetLevel) {
        addRegion(new BoxRegion(min, max, targetLevel));
    }
    
    @Override
    public boolean shouldRefine(RefinementContext<ID> context) {
        // Find the highest target level among intersecting regions
        int targetLevel = 0;
        boolean inRegion = false;
        
        for (var region : regions) {
            if (region.intersects(context.bounds())) {
                targetLevel = Math.max(targetLevel, region.getTargetLevel());
                inRegion = true;
            }
        }
        
        // If not in any region, use default max level
        if (!inRegion) {
            targetLevel = defaultMaxLevel;
        }
        
        // Refine if we haven't reached target level
        return context.level() < targetLevel;
    }
    
    @Override
    public boolean shouldCoarsen(RefinementContext<ID> context) {
        // For now, don't coarsen in spatial regions
        return false;
    }
    
    @Override
    public int getMaxLevel() {
        // Return the highest target level among all regions
        return regions.stream()
            .mapToInt(RefinementRegion::getTargetLevel)
            .max()
            .orElse(defaultMaxLevel);
    }
}