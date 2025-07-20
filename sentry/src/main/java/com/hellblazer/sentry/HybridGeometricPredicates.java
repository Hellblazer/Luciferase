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
 * Hybrid geometric predicates that use fast approximate calculations first,
 * falling back to exact predicates only when results are ambiguous.
 * 
 * This implementation trades some precision for significant performance gains
 * in the common case where results are not close to zero.
 * 
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 */
public class HybridGeometricPredicates implements GeometricPredicates {
    
    // Epsilon thresholds for determining when to use exact predicates
    // These need to be conservative to avoid incorrect results
    private static final double ORIENTATION_EPSILON = 1e-6;
    private static final double INSPHERE_EPSILON = 1e-6;
    
    // Statistics for performance monitoring
    private long orientationApproxCount = 0;
    private long orientationExactCount = 0;
    private long inSphereApproxCount = 0;
    private long inSphereExactCount = 0;
    
    // Delegate for exact calculations
    private final GeometricPredicates exactPredicates;
    
    public HybridGeometricPredicates() {
        this.exactPredicates = new ExactGeometricPredicates();
    }
    
    @Override
    public double orientation(double ax, double ay, double az, 
                             double bx, double by, double bz,
                             double cx, double cy, double cz,
                             double dx, double dy, double dz) {
        // First try fast approximate calculation
        double approx = orientationApproximate(ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz);
        orientationApproxCount++;
        
        // If result is clearly non-zero, return it
        if (Math.abs(approx) > ORIENTATION_EPSILON) {
            return approx;
        }
        
        // Result is ambiguous, use exact predicate
        orientationExactCount++;
        return exactPredicates.orientation(ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz);
    }
    
    @Override
    public double inSphere(double ax, double ay, double az,
                          double bx, double by, double bz,
                          double cx, double cy, double cz,
                          double dx, double dy, double dz,
                          double ex, double ey, double ez) {
        // First try fast approximate calculation
        double approx = inSphereApproximate(ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz, ex, ey, ez);
        inSphereApproxCount++;
        
        // If result is clearly non-zero, return it
        if (Math.abs(approx) > INSPHERE_EPSILON) {
            return approx;
        }
        
        // Result is ambiguous, use exact predicate
        inSphereExactCount++;
        return exactPredicates.inSphere(ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz, ex, ey, ez);
    }
    
    @Override
    public String getImplementationName() {
        return "Hybrid (Approximate/Exact)";
    }
    
    /**
     * Fast approximate orientation predicate using single-precision floats.
     * Computes the signed volume of the tetrahedron formed by a, b, c, d.
     */
    private double orientationApproximate(double ax, double ay, double az, 
                                         double bx, double by, double bz,
                                         double cx, double cy, double cz,
                                         double dx, double dy, double dz) {
        // Use float arithmetic for speed
        float axf = (float)ax, ayf = (float)ay, azf = (float)az;
        float bxf = (float)bx, byf = (float)by, bzf = (float)bz;
        float cxf = (float)cx, cyf = (float)cy, czf = (float)cz;
        float dxf = (float)dx, dyf = (float)dy, dzf = (float)dz;
        
        // Translate to origin for better numerical stability
        float adx = axf - dxf;
        float ady = ayf - dyf;
        float adz = azf - dzf;
        float bdx = bxf - dxf;
        float bdy = byf - dyf;
        float bdz = bzf - dzf;
        float cdx = cxf - dxf;
        float cdy = cyf - dyf;
        float cdz = czf - dzf;
        
        // Compute determinant (signed volume * 6)
        return adx * (bdy * cdz - bdz * cdy) 
             + ady * (bdz * cdx - bdx * cdz) 
             + adz * (bdx * cdy - bdy * cdx);
    }
    
    /**
     * Fast approximate inSphere predicate using single-precision floats.
     * Tests if query point is inside the circumsphere of tetrahedron (a,b,c,d).
     */
    private double inSphereApproximate(double ax, double ay, double az,
                                      double bx, double by, double bz,
                                      double cx, double cy, double cz,
                                      double dx, double dy, double dz,
                                      double ex, double ey, double ez) {
        // Use float arithmetic for speed
        float axf = (float)ax, ayf = (float)ay, azf = (float)az;
        float bxf = (float)bx, byf = (float)by, bzf = (float)bz;
        float cxf = (float)cx, cyf = (float)cy, czf = (float)cz;
        float dxf = (float)dx, dyf = (float)dy, dzf = (float)dz;
        float exf = (float)ex, eyf = (float)ey, ezf = (float)ez;
        
        // Translate to origin for better numerical stability
        float aex = axf - exf;
        float aey = ayf - eyf;
        float aez = azf - ezf;
        float bex = bxf - exf;
        float bey = byf - eyf;
        float bez = bzf - ezf;
        float cex = cxf - exf;
        float cey = cyf - eyf;
        float cez = czf - ezf;
        float dex = dxf - exf;
        float dey = dyf - eyf;
        float dez = dzf - ezf;
        
        // Compute squared distances
        float ae2 = aex * aex + aey * aey + aez * aez;
        float be2 = bex * bex + bey * bey + bez * bez;
        float ce2 = cex * cex + cey * cey + cez * cez;
        float de2 = dex * dex + dey * dey + dez * dez;
        
        // Compute determinant
        float ab = aex * bey - bex * aey;
        float bc = bex * cey - cex * bey;
        float cd = cex * dey - dex * cey;
        float da = dex * aey - aex * dey;
        float ac = aex * cey - cex * aey;
        float bd = bex * dey - dex * bey;
        
        float abc = aez * bc - bez * ac + cez * ab;
        float bcd = bez * cd - cez * bd + dez * bc;
        float cda = cez * da + dez * ac - aez * cd;
        float dab = dez * ab + aez * bd - bez * da;
        
        return ae2 * bcd - be2 * cda + ce2 * dab - de2 * abc;
    }
    
    /**
     * Get performance statistics for monitoring exact vs approximate usage.
     */
    public String getStatistics() {
        long totalOrientation = orientationApproxCount;
        long totalInSphere = inSphereApproxCount;
        
        double orientationExactRatio = totalOrientation > 0 
            ? (100.0 * orientationExactCount / totalOrientation) : 0;
        double inSphereExactRatio = totalInSphere > 0 
            ? (100.0 * inSphereExactCount / totalInSphere) : 0;
            
        return String.format(
            "Hybrid Predicate Statistics:\n" +
            "  Orientation: %d calls, %.2f%% exact (%d exact)\n" +
            "  InSphere: %d calls, %.2f%% exact (%d exact)",
            totalOrientation, orientationExactRatio, orientationExactCount,
            totalInSphere, inSphereExactRatio, inSphereExactCount
        );
    }
    
    /**
     * Reset statistics counters.
     */
    public void resetStatistics() {
        orientationApproxCount = 0;
        orientationExactCount = 0;
        inSphereApproxCount = 0;
        inSphereExactCount = 0;
    }
}