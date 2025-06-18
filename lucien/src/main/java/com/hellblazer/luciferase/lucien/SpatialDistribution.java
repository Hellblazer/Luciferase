/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
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

/**
 * Interface for describing expected spatial distribution patterns
 * for pre-allocation optimization in spatial indices.
 * 
 * @author hal.hildebrand
 */
public interface SpatialDistribution {
    
    /**
     * Get the optimal levels for pre-allocation based on the bounds
     * and distribution characteristics.
     * 
     * @param bounds the volume bounds
     * @return list of levels to pre-allocate, ordered by priority
     */
    List<Byte> getOptimalLevels(VolumeBounds bounds);
    
    /**
     * Get the density factor for a specific level.
     * 0.0 = sparse, 1.0 = dense
     * 
     * @param level the level to query
     * @return density factor between 0.0 and 1.0
     */
    float getDensityAtLevel(byte level);
    
    /**
     * Get seed points for pre-allocation at a specific level.
     * These points indicate where nodes should be pre-allocated.
     * 
     * @param bounds the volume bounds
     * @param level the level to generate seeds for
     * @return list of seed points
     */
    List<Point3f> getSeedPoints(VolumeBounds bounds, byte level);
    
    /**
     * Uniform distribution across the entire volume
     */
    class UniformDistribution implements SpatialDistribution {
        private final float density;
        
        public UniformDistribution(float density) {
            this.density = Math.max(0.0f, Math.min(1.0f, density));
        }
        
        @Override
        public List<Byte> getOptimalLevels(VolumeBounds bounds) {
            // For uniform distribution, use mid-range levels
            float volume = (bounds.maxX() - bounds.minX()) * 
                          (bounds.maxY() - bounds.minY()) * 
                          (bounds.maxZ() - bounds.minZ());
            
            byte optimalLevel = (byte) Math.min(20, Math.max(0, 
                Math.round(Math.log(volume) / Math.log(8))));
            
            return List.of(optimalLevel);
        }
        
        @Override
        public float getDensityAtLevel(byte level) {
            return density;
        }
        
        @Override
        public List<Point3f> getSeedPoints(VolumeBounds bounds, byte level) {
            // For uniform distribution, return grid points
            int cellSize = Constants.lengthAtLevel(level);
            List<Point3f> seeds = new java.util.ArrayList<>();
            
            for (float x = bounds.minX(); x < bounds.maxX(); x += cellSize) {
                for (float y = bounds.minY(); y < bounds.maxY(); y += cellSize) {
                    for (float z = bounds.minZ(); z < bounds.maxZ(); z += cellSize) {
                        seeds.add(new Point3f(x + cellSize/2, y + cellSize/2, z + cellSize/2));
                    }
                }
            }
            
            return seeds;
        }
    }
    
    /**
     * Clustered distribution with hotspots
     */
    class ClusteredDistribution implements SpatialDistribution {
        private final List<Point3f> clusterCenters;
        private final float clusterRadius;
        
        public ClusteredDistribution(List<Point3f> clusterCenters, float clusterRadius) {
            this.clusterCenters = clusterCenters;
            this.clusterRadius = clusterRadius;
        }
        
        @Override
        public List<Byte> getOptimalLevels(VolumeBounds bounds) {
            // For clusters, use finer levels near cluster size
            byte fineLevel = 0;
            for (byte level = 0; level <= 20; level++) {
                if (Constants.lengthAtLevel(level) <= clusterRadius * 2) {
                    fineLevel = level;
                    break;
                }
            }
            
            // Also include one coarser level
            byte coarseLevel = (byte) Math.max(0, fineLevel - 2);
            
            return List.of(fineLevel, coarseLevel);
        }
        
        @Override
        public float getDensityAtLevel(byte level) {
            // High density at cluster level, lower elsewhere
            float cellSize = Constants.lengthAtLevel(level);
            if (cellSize <= clusterRadius * 2 && cellSize >= clusterRadius / 2) {
                return 0.9f;
            }
            return 0.2f;
        }
        
        @Override
        public List<Point3f> getSeedPoints(VolumeBounds bounds, byte level) {
            List<Point3f> seeds = new java.util.ArrayList<>();
            float cellSize = Constants.lengthAtLevel(level);
            
            // Add seeds around each cluster center
            for (Point3f center : clusterCenters) {
                // Add cluster center
                seeds.add(center);
                
                // Add surrounding points
                for (float dx = -clusterRadius; dx <= clusterRadius; dx += cellSize) {
                    for (float dy = -clusterRadius; dy <= clusterRadius; dy += cellSize) {
                        for (float dz = -clusterRadius; dz <= clusterRadius; dz += cellSize) {
                            Point3f seed = new Point3f(
                                center.x + dx,
                                center.y + dy,
                                center.z + dz
                            );
                            
                            // Check if within bounds
                            if (seed.x >= bounds.minX() && seed.x <= bounds.maxX() &&
                                seed.y >= bounds.minY() && seed.y <= bounds.maxY() &&
                                seed.z >= bounds.minZ() && seed.z <= bounds.maxZ()) {
                                seeds.add(seed);
                            }
                        }
                    }
                }
            }
            
            return seeds;
        }
    }
    
    /**
     * Surface distribution (entities concentrated on surfaces)
     */
    class SurfaceDistribution implements SpatialDistribution {
        private final float surfaceThickness;
        
        public SurfaceDistribution(float surfaceThickness) {
            this.surfaceThickness = surfaceThickness;
        }
        
        @Override
        public List<Byte> getOptimalLevels(VolumeBounds bounds) {
            // For surfaces, use fine levels based on surface thickness
            byte optimalLevel = 0;
            for (byte level = 20; level >= 0; level--) {
                if (Constants.lengthAtLevel(level) >= surfaceThickness) {
                    optimalLevel = level;
                    break;
                }
            }
            
            return List.of(optimalLevel, (byte)(optimalLevel + 1));
        }
        
        @Override
        public float getDensityAtLevel(byte level) {
            float cellSize = Constants.lengthAtLevel(level);
            if (cellSize <= surfaceThickness * 4) {
                return 0.7f;
            }
            return 0.1f;
        }
        
        @Override
        public List<Point3f> getSeedPoints(VolumeBounds bounds, byte level) {
            List<Point3f> seeds = new java.util.ArrayList<>();
            float cellSize = Constants.lengthAtLevel(level);
            
            // Add seeds along the surfaces of the bounding box
            // X-Y planes (top and bottom)
            for (float x = bounds.minX(); x <= bounds.maxX(); x += cellSize) {
                for (float y = bounds.minY(); y <= bounds.maxY(); y += cellSize) {
                    seeds.add(new Point3f(x, y, bounds.minZ() + surfaceThickness/2));
                    seeds.add(new Point3f(x, y, bounds.maxZ() - surfaceThickness/2));
                }
            }
            
            // X-Z planes (front and back)
            for (float x = bounds.minX(); x <= bounds.maxX(); x += cellSize) {
                for (float z = bounds.minZ(); z <= bounds.maxZ(); z += cellSize) {
                    seeds.add(new Point3f(x, bounds.minY() + surfaceThickness/2, z));
                    seeds.add(new Point3f(x, bounds.maxY() - surfaceThickness/2, z));
                }
            }
            
            // Y-Z planes (left and right)
            for (float y = bounds.minY(); y <= bounds.maxY(); y += cellSize) {
                for (float z = bounds.minZ(); z <= bounds.maxZ(); z += cellSize) {
                    seeds.add(new Point3f(bounds.minX() + surfaceThickness/2, y, z));
                    seeds.add(new Point3f(bounds.maxX() - surfaceThickness/2, y, z));
                }
            }
            
            return seeds;
        }
    }
}