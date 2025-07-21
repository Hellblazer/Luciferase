/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 * 
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.geometry;

/**
 * Adaptive geometric predicates that use fast computation with fallback to exact arithmetic.
 * These methods provide the best of both worlds: speed for most cases and correctness when needed.
 * 
 * @author hal.hildebrand
 */
public class GeometryAdaptive {

    /**
     * Adaptive version of leftOfPlane that uses fast computation with fallback to exact arithmetic.
     * Determines if a point d is left of the plane defined by the points a, b, and c.
     * The latter are assumed to be in CCW order, as viewed from the right side of the plane.
     *
     * @return positive, if left of plane; negative, if right of plane; zero, otherwise.
     */
    public static double leftOfPlaneAdaptive(double xa, double ya, double za, double xb, double yb, double zb,
                                             double xc, double yc, double zc, double xd, double yd, double zd) {
        double result = Geometry.leftOfPlaneFast(xa, ya, za, xb, yb, zb, xc, yc, zc, xd, yd, zd);
        
        // Compute error bound for orientation test
        double detsum = Math.abs(xa - xd) * (Math.abs((yb - yd) * (zc - zd)) + Math.abs((yc - yd) * (zb - zd))) +
                        Math.abs(xb - xd) * (Math.abs((yc - yd) * (za - zd)) + Math.abs((ya - yd) * (zc - zd))) +
                        Math.abs(xc - xd) * (Math.abs((ya - yd) * (zb - zd)) + Math.abs((yb - yd) * (za - zd)));
        
        double errbound = 3.3306690738754716e-16 * detsum;
        
        // If result is clearly non-zero (outside error bounds), fast result is reliable
        if (Math.abs(result) > errbound) {
            return result;
        }
        
        // Fall back to exact computation
        return Geometry.leftOfPlane(xa, ya, za, xb, yb, zb, xc, yc, zc, xd, yd, zd);
    }

    /**
     * Adaptive version of leftOfPlane for double arrays.
     */
    public static double leftOfPlaneAdaptive(double[] pa, double[] pb, double[] pc, double[] pd) {
        return leftOfPlaneAdaptive(pa[0], pa[1], pa[2], pb[0], pb[1], pb[2], pc[0], pc[1], pc[2], pd[0], pd[1], pd[2]);
    }

    /**
     * Adaptive version of leftOfPlane for float arrays.
     */
    public static double leftOfPlaneAdaptive(float[] pa, float[] pb, float[] pc, float[] pd) {
        return leftOfPlaneAdaptive(pa[0], pa[1], pa[2], pb[0], pb[1], pb[2], pc[0], pc[1], pc[2], pd[0], pd[1], pd[2]);
    }

    /**
     * Adaptive version of inSphere that uses fast computation with fallback to exact arithmetic.
     * Determines if a point e is inside the sphere defined by the points a, b, c, and d.
     * The latter are assumed to be in CCW order, such that the method leftOfPlane would return a positive number.
     *
     * @return positive, if inside the sphere; negative, if outside the sphere; zero, otherwise.
     */
    public static double inSphereAdaptive(double xa, double ya, double za, double xb, double yb, double zb,
                                          double xc, double yc, double zc, double xd, double yd, double zd,
                                          double xe, double ye, double ze) {
        double result = Geometry.inSphereFast(xa, ya, za, xb, yb, zb, xc, yc, zc, xd, yd, zd, xe, ye, ze);
        
        // Compute error bound for insphere test
        double aex = xa - xe;
        double bex = xb - xe;
        double cex = xc - xe;
        double dex = xd - xe;
        double aey = ya - ye;
        double bey = yb - ye;
        double cey = yc - ye;
        double dey = yd - ye;
        double aez = za - ze;
        double bez = zb - ze;
        double cez = zc - ze;
        double dez = zd - ze;
        
        double aexbey = Math.abs(aex * bey);
        double bexaey = Math.abs(bex * aey);
        double bexcey = Math.abs(bex * cey);
        double cexbey = Math.abs(cex * bey);
        double cexdey = Math.abs(cex * dey);
        double dexcey = Math.abs(dex * cey);
        double dexaey = Math.abs(dex * aey);
        double aexdey = Math.abs(aex * dey);
        double aexcey = Math.abs(aex * cey);
        double cexaey = Math.abs(cex * aey);
        double bexdey = Math.abs(bex * dey);
        double dexbey = Math.abs(dex * bey);
        
        double alift = aex * aex + aey * aey + aez * aez;
        double blift = bex * bex + bey * bey + bez * bez;
        double clift = cex * cex + cey * cey + cez * cez;
        double dlift = dex * dex + dey * dey + dez * dez;
        
        double det = alift * (bexcey + cexbey) * Math.abs(dez) +
                     blift * (cexdey + dexcey) * Math.abs(aez) +
                     clift * (dexaey + aexdey) * Math.abs(bez) +
                     dlift * (aexbey + bexaey) * Math.abs(cez) +
                     (aexbey + bexaey) * Math.abs(cez * dlift) +
                     (bexcey + cexbey) * Math.abs(dez * alift) +
                     (cexdey + dexcey) * Math.abs(aez * blift) +
                     (dexaey + aexdey) * Math.abs(bez * clift);
        
        double errbound = 1.11022302e-15 * det;
        
        // If result is clearly non-zero (outside error bounds), fast result is reliable
        if (Math.abs(result) > errbound) {
            return result;
        }
        
        // Fall back to exact computation
        return Geometry.inSphere(xa, ya, za, xb, yb, zb, xc, yc, zc, xd, yd, zd, xe, ye, ze);
    }

    /**
     * Adaptive version of inSphere for double arrays.
     */
    public static double inSphereAdaptive(double[] pa, double[] pb, double[] pc, double[] pd, double[] pe) {
        return inSphereAdaptive(pa[0], pa[1], pa[2], pb[0], pb[1], pb[2], pc[0], pc[1], pc[2], pd[0], pd[1], pd[2],
                                pe[0], pe[1], pe[2]);
    }

    /**
     * Adaptive version of inSphere for float arrays.
     */
    public static double inSphereAdaptive(float[] pa, float[] pb, float[] pc, float[] pd, float[] pe) {
        return inSphereAdaptive(pa[0], pa[1], pa[2], pb[0], pb[1], pb[2], pc[0], pc[1], pc[2], pd[0], pd[1], pd[2],
                                pe[0], pe[1], pe[2]);
    }
}