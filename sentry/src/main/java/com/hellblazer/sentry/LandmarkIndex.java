/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 * 
 * This file is part of the 3D Incremental Voronoi system
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
package com.hellblazer.sentry;

import javax.vecmath.Point3f;
import javax.vecmath.Tuple3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import static com.hellblazer.sentry.V.*;

/**
 * Jump-and-Walk spatial index for accelerating point location queries.
 * Maintains a set of landmark tetrahedra distributed across the mesh
 * to reduce walking distance.
 * 
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 */
public class LandmarkIndex {
    
    // Target number of landmarks as a function of mesh size
    private static final double LANDMARK_RATIO = 0.05; // 5% of tetrahedra
    private static final int MIN_LANDMARKS = 10;
    private static final int MAX_LANDMARKS = 1000;
    
    // Landmarks distributed across the mesh
    private final List<Tetrahedron> landmarks;
    private final Random random;
    
    // Statistics for performance monitoring
    private long totalQueries = 0;
    private long totalWalkSteps = 0;
    
    public LandmarkIndex(Random random) {
        this.landmarks = new ArrayList<>();
        this.random = random;
    }
    
    /**
     * Add a tetrahedron as a potential landmark.
     * Uses probabilistic selection to maintain target landmark count.
     */
    public void addTetrahedron(Tetrahedron t, int totalTetrahedra) {
        if (t == null || t.isDeleted()) {
            return;
        }
        
        int targetLandmarks = calculateTargetLandmarks(totalTetrahedra);
        
        if (landmarks.size() < targetLandmarks) {
            // Not enough landmarks, add this one
            landmarks.add(t);
        } else {
            // Replace random landmark with probability
            double replaceProbability = (double) targetLandmarks / totalTetrahedra;
            if (random.nextDouble() < replaceProbability) {
                int index = random.nextInt(landmarks.size());
                landmarks.set(index, t);
            }
        }
    }
    
    /**
     * Remove deleted tetrahedra from landmarks.
     */
    public void cleanup() {
        landmarks.removeIf(t -> t == null || t.isDeleted());
    }
    
    /**
     * Locate the tetrahedron containing the query point using jump-and-walk.
     * 
     * @param query The point to locate
     * @param fallback Fallback tetrahedron if no landmarks available
     * @param entropy Random source for tie-breaking
     * @return The containing tetrahedron, or null if not found
     */
    public Tetrahedron locate(Tuple3f query, Tetrahedron fallback, Random entropy) {
        totalQueries++;
        
        // Find nearest landmark
        Tetrahedron start = findNearestLandmark(query);
        if (start == null) {
            start = fallback;
        }
        
        if (start == null) {
            return null;
        }
        
        // Walk from landmark to target
        Tetrahedron result = walkToTarget(start, query, entropy);
        
        return result;
    }
    
    /**
     * Find the landmark whose centroid is nearest to the query point.
     */
    private Tetrahedron findNearestLandmark(Tuple3f query) {
        if (landmarks.isEmpty()) {
            return null;
        }
        
        Tetrahedron nearest = null;
        float minDistSq = Float.MAX_VALUE;
        
        // Clean up deleted landmarks as we search
        List<Tetrahedron> toRemove = null;
        
        for (Tetrahedron landmark : landmarks) {
            if (landmark == null || landmark.isDeleted()) {
                if (toRemove == null) {
                    toRemove = new ArrayList<>();
                }
                toRemove.add(landmark);
                continue;
            }
            
            // Calculate distance to landmark centroid
            Point3f centroid = landmark.centroid();
            float dx = centroid.x - query.x;
            float dy = centroid.y - query.y;
            float dz = centroid.z - query.z;
            float distSq = dx * dx + dy * dy + dz * dz;
            
            if (distSq < minDistSq) {
                minDistSq = distSq;
                nearest = landmark;
            }
        }
        
        // Remove deleted landmarks
        if (toRemove != null) {
            landmarks.removeAll(toRemove);
        }
        
        return nearest;
    }
    
    /**
     * Walk from start tetrahedron to the one containing the query point.
     * Tracks steps for performance monitoring.
     */
    private Tetrahedron walkToTarget(Tetrahedron start, Tuple3f query, Random entropy) {
        Tetrahedron current = start;
        int steps = 0;
        
        while (current != null && steps < 10000) { // Prevent infinite loops
            steps++;
            
            // Check if query is inside current tetrahedron
            V outsideFace = null;
            for (V face : Grid.VERTICES) {
                if (current.orientationWrt(face, query) < 0.0d) {
                    outsideFace = face;
                    break;
                }
            }
            
            if (outsideFace == null) {
                // Found containing tetrahedron
                totalWalkSteps += steps;
                return current;
            }
            
            // Move to neighbor
            current = current.getNeighbor(outsideFace);
        }
        
        // Failed to find
        totalWalkSteps += steps;
        return null;
    }
    
    /**
     * Calculate target number of landmarks based on mesh size.
     */
    private int calculateTargetLandmarks(int totalTetrahedra) {
        int target = (int) (totalTetrahedra * LANDMARK_RATIO);
        return Math.max(MIN_LANDMARKS, Math.min(MAX_LANDMARKS, target));
    }
    
    /**
     * Get performance statistics.
     */
    public String getStatistics() {
        if (totalQueries == 0) {
            return "No queries performed";
        }
        
        double avgWalkSteps = (double) totalWalkSteps / totalQueries;
        return String.format("Landmarks: %d, Queries: %d, Avg walk steps: %.1f",
                           landmarks.size(), totalQueries, avgWalkSteps);
    }
    
    /**
     * Reset the index.
     */
    public void clear() {
        landmarks.clear();
        totalQueries = 0;
        totalWalkSteps = 0;
    }
    
    /**
     * Get current landmark count.
     */
    public int getLandmarkCount() {
        return landmarks.size();
    }
}