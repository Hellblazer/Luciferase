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

/**
 * Abstract interface for geometric predicates that can be implemented
 * using either scalar operations or SIMD vector operations.
 * 
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 */
public interface GeometricPredicates {
    
    /**
     * Compute orientation predicate for a point relative to a plane.
     * Returns positive if point (dx,dy,dz) is above the plane defined by (a,b,c).
     */
    double orientation(double ax, double ay, double az, 
                      double bx, double by, double bz,
                      double cx, double cy, double cz,
                      double dx, double dy, double dz);
    
    /**
     * Compute in-sphere predicate.
     * Returns positive if point (ex,ey,ez) is inside the sphere defined by (a,b,c,d).
     */
    double inSphere(double ax, double ay, double az,
                   double bx, double by, double bz,
                   double cx, double cy, double cz,
                   double dx, double dy, double dz,
                   double ex, double ey, double ez);
    
    /**
     * Batch compute orientation for multiple query points.
     * More efficient for SIMD implementations.
     */
    default double[] batchOrientation(double[] qx, double[] qy, double[] qz,
                                     double ax, double ay, double az,
                                     double bx, double by, double bz,
                                     double cx, double cy, double cz) {
        int n = qx.length;
        double[] results = new double[n];
        for (int i = 0; i < n; i++) {
            results[i] = orientation(ax, ay, az, bx, by, bz, cx, cy, cz, qx[i], qy[i], qz[i]);
        }
        return results;
    }
    
    /**
     * Batch compute in-sphere for multiple query points.
     * More efficient for SIMD implementations.
     */
    default double[] batchInSphere(double[] qx, double[] qy, double[] qz,
                                  double ax, double ay, double az,
                                  double bx, double by, double bz,
                                  double cx, double cy, double cz,
                                  double dx, double dy, double dz) {
        int n = qx.length;
        double[] results = new double[n];
        for (int i = 0; i < n; i++) {
            results[i] = inSphere(ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz, qx[i], qy[i], qz[i]);
        }
        return results;
    }
    
    /**
     * Get the implementation name for debugging/logging.
     */
    String getImplementationName();
}