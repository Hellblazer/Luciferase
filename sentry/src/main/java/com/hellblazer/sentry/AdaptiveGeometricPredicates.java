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

import com.hellblazer.luciferase.geometry.Geometry;

/**
 * Adaptive implementation of geometric predicates that uses the Geometry class's
 * built-in adaptive behavior. The Geometry.leftOfPlane() and Geometry.inSphere()
 * methods already implement Shewchuk's adaptive predicates:
 * 
 * 1. They first compute using fast arithmetic
 * 2. They check error bounds to determine if the result is reliable
 * 3. They fall back to exact arithmetic only when necessary
 * 
 * This provides the best of both worlds: fast performance in most cases,
 * with exact results when the geometry is challenging (nearly degenerate).
 * 
 * The adaptive threshold can be configured via system property:
 * -Dsentry.adaptive.epsilon=1e-10 (default uses Shewchuk's error bounds)
 * 
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 */
public class AdaptiveGeometricPredicates implements GeometricPredicates {
    
    // Statistics for performance monitoring
    private long orientationCount = 0;
    private long inSphereCount = 0;
    
    // Optional custom epsilon for testing (normally not needed)
    private final double customEpsilon;
    
    public AdaptiveGeometricPredicates() {
        // Allow custom epsilon override for testing purposes
        String epsilonStr = System.getProperty("sentry.adaptive.epsilon");
        double epsilon = -1; // Use default Shewchuk bounds
        if (epsilonStr != null) {
            try {
                epsilon = Double.parseDouble(epsilonStr);
                System.out.println("Using custom adaptive epsilon: " + epsilon);
            } catch (NumberFormatException e) {
                System.err.println("Invalid adaptive epsilon value: " + epsilonStr);
            }
        }
        customEpsilon = epsilon;
    }
    
    @Override
    public double orientation(double ax, double ay, double az, 
                            double bx, double by, double bz,
                            double cx, double cy, double cz,
                            double dx, double dy, double dz) {
        orientationCount++;
        
        // If custom epsilon is set, we need to implement our own adaptive logic
        if (customEpsilon > 0) {
            // First try fast computation
            double fast = Geometry.leftOfPlaneFast(ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz);
            
            // Check if result is clearly non-zero
            if (Math.abs(fast) > customEpsilon) {
                return fast;
            }
            
            // Fall back to exact computation
            return Geometry.leftOfPlane(ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz);
        }
        
        // Use Geometry's built-in adaptive behavior (recommended)
        return Geometry.leftOfPlane(ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz);
    }
    
    @Override
    public double inSphere(double ax, double ay, double az,
                          double bx, double by, double bz,
                          double cx, double cy, double cz,
                          double dx, double dy, double dz,
                          double ex, double ey, double ez) {
        inSphereCount++;
        
        // If custom epsilon is set, we need to implement our own adaptive logic
        if (customEpsilon > 0) {
            // First try fast computation
            double fast = Geometry.inSphereFast(ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz, ex, ey, ez);
            
            // Check if result is clearly non-zero
            if (Math.abs(fast) > customEpsilon) {
                return fast;
            }
            
            // Fall back to exact computation
            return Geometry.inSphere(ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz, ex, ey, ez);
        }
        
        // Use Geometry's built-in adaptive behavior (recommended)
        return Geometry.inSphere(ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz, ex, ey, ez);
    }
    
    @Override
    public String getImplementationName() {
        return "Adaptive" + (customEpsilon > 0 ? " (custom epsilon=" + customEpsilon + ")" : " (Shewchuk bounds)");
    }
    
    /**
     * Get performance statistics.
     */
    public String getStatistics() {
        return String.format(
            "Adaptive Predicate Statistics:\n" +
            "  Orientation: %d calls\n" +
            "  InSphere: %d calls\n" +
            "  Mode: %s",
            orientationCount, inSphereCount,
            customEpsilon > 0 ? "Custom epsilon" : "Shewchuk error bounds"
        );
    }
    
    /**
     * Reset statistics counters.
     */
    public void resetStatistics() {
        orientationCount = 0;
        inSphereCount = 0;
    }
}