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

import jdk.incubator.vector.*;

/**
 * SIMD implementation of geometric predicates using Java Vector API.
 * This class requires --enable-preview and --add-modules jdk.incubator.vector
 * 
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 */
public class SIMDGeometricPredicates implements GeometricPredicates {
    
    // Use 256-bit vectors for AVX2 compatibility
    private static final VectorSpecies<Double> SPECIES = DoubleVector.SPECIES_256;
    
    @Override
    public double orientation(double ax, double ay, double az, 
                            double bx, double by, double bz,
                            double cx, double cy, double cz,
                            double dx, double dy, double dz) {
        // Compute edge vectors
        double abx = bx - ax, aby = by - ay, abz = bz - az;
        double acx = cx - ax, acy = cy - ay, acz = cz - az;
        double adx = dx - ax, ady = dy - ay, adz = dz - az;
        
        // Cross product AC x AB using SIMD
        // Load vectors
        var ac = DoubleVector.fromArray(SPECIES, new double[]{acx, acy, acz, 0}, 0);
        var ab = DoubleVector.fromArray(SPECIES, new double[]{abx, aby, abz, 0}, 0);
        
        // Compute cross product components
        // cross.x = ac.y * ab.z - ac.z * ab.y
        // cross.y = ac.z * ab.x - ac.x * ab.z  
        // cross.z = ac.x * ab.y - ac.y * ab.x
        
        var ac_perm1 = ac.rearrange(VectorShuffle.fromValues(SPECIES, 1, 2, 0, 3)); // y,z,x,0
        var ab_perm1 = ab.rearrange(VectorShuffle.fromValues(SPECIES, 2, 0, 1, 3)); // z,x,y,0
        var prod1 = ac_perm1.mul(ab_perm1);
        
        var ac_perm2 = ac.rearrange(VectorShuffle.fromValues(SPECIES, 2, 0, 1, 3)); // z,x,y,0
        var ab_perm2 = ab.rearrange(VectorShuffle.fromValues(SPECIES, 1, 2, 0, 3)); // y,z,x,0
        var prod2 = ac_perm2.mul(ab_perm2);
        
        var cross = prod1.sub(prod2);
        
        // Dot product with AD
        var ad = DoubleVector.fromArray(SPECIES, new double[]{adx, ady, adz, 0}, 0);
        var dot = cross.mul(ad);
        
        // Sum the components
        return dot.reduceLanes(VectorOperators.ADD);
    }
    
    @Override
    public double inSphere(double ax, double ay, double az,
                          double bx, double by, double bz,
                          double cx, double cy, double cz,
                          double dx, double dy, double dz,
                          double ex, double ey, double ez) {
        // Translate to origin at e
        double aex = ax - ex, aey = ay - ey, aez = az - ez;
        double bex = bx - ex, bey = by - ey, bez = bz - ez;
        double cex = cx - ex, cey = cy - ey, cez = cz - ez;
        double dex = dx - ex, dey = dy - ey, dez = dz - ez;
        
        // Compute squared lengths using SIMD
        var avec = DoubleVector.fromArray(SPECIES, new double[]{aex, aey, aez, 0}, 0);
        var asq = avec.mul(avec).reduceLanes(VectorOperators.ADD);
        
        var bvec = DoubleVector.fromArray(SPECIES, new double[]{bex, bey, bez, 0}, 0);
        var bsq = bvec.mul(bvec).reduceLanes(VectorOperators.ADD);
        
        var cvec = DoubleVector.fromArray(SPECIES, new double[]{cex, cey, cez, 0}, 0);
        var csq = cvec.mul(cvec).reduceLanes(VectorOperators.ADD);
        
        var dvec = DoubleVector.fromArray(SPECIES, new double[]{dex, dey, dez, 0}, 0);
        var dsq = dvec.mul(dvec).reduceLanes(VectorOperators.ADD);
        
        // Compute 4x4 determinant using SIMD for sub-determinants
        double det = computeDet4x4SIMD(
            aex, aey, aez, asq,
            bex, bey, bez, bsq,
            cex, cey, cez, csq,
            dex, dey, dez, dsq
        );
        
        return det;
    }
    
    /**
     * Compute 4x4 determinant using SIMD operations
     */
    private double computeDet4x4SIMD(
            double a11, double a12, double a13, double a14,
            double a21, double a22, double a23, double a24,
            double a31, double a32, double a33, double a34,
            double a41, double a42, double a43, double a44) {
        
        // Use Laplace expansion along first row
        // det = a11*M11 - a12*M12 + a13*M13 - a14*M14
        
        // Compute 3x3 minors using SIMD
        double m11 = det3x3SIMD(a22, a23, a24, a32, a33, a34, a42, a43, a44);
        double m12 = det3x3SIMD(a21, a23, a24, a31, a33, a34, a41, a43, a44);
        double m13 = det3x3SIMD(a21, a22, a24, a31, a32, a34, a41, a42, a44);
        double m14 = det3x3SIMD(a21, a22, a23, a31, a32, a33, a41, a42, a43);
        
        return a11 * m11 - a12 * m12 + a13 * m13 - a14 * m14;
    }
    
    /**
     * Compute 3x3 determinant using SIMD
     */
    private double det3x3SIMD(double a11, double a12, double a13,
                             double a21, double a22, double a23,
                             double a31, double a32, double a33) {
        // det = a11(a22*a33 - a23*a32) - a12(a21*a33 - a23*a31) + a13(a21*a32 - a22*a31)
        
        var row2 = DoubleVector.fromArray(SPECIES, new double[]{a22, a21, a21, 0}, 0);
        var row3 = DoubleVector.fromArray(SPECIES, new double[]{a33, a33, a32, 0}, 0);
        var prod1 = row2.mul(row3);
        
        var row2b = DoubleVector.fromArray(SPECIES, new double[]{a23, a23, a22, 0}, 0);
        var row3b = DoubleVector.fromArray(SPECIES, new double[]{a32, a31, a31, 0}, 0);
        var prod2 = row2b.mul(row3b);
        
        var minors = prod1.sub(prod2);
        double[] minorArray = minors.toArray();
        
        return a11 * minorArray[0] - a12 * minorArray[1] + a13 * minorArray[2];
    }
    
    @Override
    public double[] batchOrientation(double[] qx, double[] qy, double[] qz,
                                    double ax, double ay, double az,
                                    double bx, double by, double bz,
                                    double cx, double cy, double cz) {
        int n = qx.length;
        double[] results = new double[n];
        
        // Precompute edge vectors
        double abx = bx - ax, aby = by - ay, abz = bz - az;
        double acx = cx - ax, acy = cy - ay, acz = cz - az;
        
        // Precompute cross product
        double nx = acy * abz - acz * aby;
        double ny = acz * abx - acx * abz;
        double nz = acx * aby - acy * abx;
        
        // Process queries in SIMD batches
        int i = 0;
        for (; i + SPECIES.length() <= n; i += SPECIES.length()) {
            // Load query coordinates
            var qxVec = DoubleVector.fromArray(SPECIES, qx, i);
            var qyVec = DoubleVector.fromArray(SPECIES, qy, i);
            var qzVec = DoubleVector.fromArray(SPECIES, qz, i);
            
            // Compute AD vectors for batch
            var adxVec = qxVec.sub(ax);
            var adyVec = qyVec.sub(ay);
            var adzVec = qzVec.sub(az);
            
            // Dot product with normal
            var dot = adxVec.mul(nx).add(adyVec.mul(ny)).add(adzVec.mul(nz));
            
            // Store results
            dot.intoArray(results, i);
        }
        
        // Handle remaining elements
        for (; i < n; i++) {
            double adx = qx[i] - ax;
            double ady = qy[i] - ay;
            double adz = qz[i] - az;
            results[i] = adx * nx + ady * ny + adz * nz;
        }
        
        return results;
    }
    
    @Override
    public double[] batchInSphere(double[] qx, double[] qy, double[] qz,
                                 double ax, double ay, double az,
                                 double bx, double by, double bz,
                                 double cx, double cy, double cz,
                                 double dx, double dy, double dz) {
        int n = qx.length;
        double[] results = new double[n];
        
        // Process in SIMD batches
        int i = 0;
        for (; i + SPECIES.length() <= n; i += SPECIES.length()) {
            // For each batch of queries, we still need to compute
            // individual determinants, but we can vectorize parts
            for (int j = 0; j < SPECIES.length() && i + j < n; j++) {
                results[i + j] = inSphere(ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz,
                                        qx[i + j], qy[i + j], qz[i + j]);
            }
        }
        
        // Handle remaining elements
        for (; i < n; i++) {
            results[i] = inSphere(ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz,
                                qx[i], qy[i], qz[i]);
        }
        
        return results;
    }
    
    @Override
    public String getImplementationName() {
        return "SIMD (Vector API)";
    }
}