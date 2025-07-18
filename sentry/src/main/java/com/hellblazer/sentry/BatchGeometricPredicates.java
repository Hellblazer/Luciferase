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
import java.util.List;

/**
 * Batch processing of geometric predicates to improve cache locality
 * and potentially enable SIMD optimizations.
 * 
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 */
public class BatchGeometricPredicates {
    
    /**
     * Batch compute orientation for multiple queries against the same triangle
     */
    public static double[] batchOrientation(List<Vertex> queries, Vertex a, Vertex b, Vertex c) {
        double[] results = new double[queries.size()];
        
        // Extract coordinates once to improve cache locality
        double ax = a.x, ay = a.y, az = a.z;
        double bx = b.x, by = b.y, bz = b.z;
        double cx = c.x, cy = c.y, cz = c.z;
        
        // Process queries in batch
        for (int i = 0; i < queries.size(); i++) {
            Vertex q = queries.get(i);
            double result = Geometry.leftOfPlaneFast(ax, ay, az, bx, by, bz, cx, cy, cz, q.x, q.y, q.z);
            results[i] = Math.signum(result);
        }
        
        return results;
    }
    
    /**
     * Batch compute inSphere for multiple queries against the same tetrahedron
     */
    public static double[] batchInSphere(List<Vertex> queries, Vertex a, Vertex b, Vertex c, Vertex d) {
        double[] results = new double[queries.size()];
        
        // Extract coordinates once to improve cache locality
        double ax = a.x, ay = a.y, az = a.z;
        double bx = b.x, by = b.y, bz = b.z;
        double cx = c.x, cy = c.y, cz = c.z;
        double dx = d.x, dy = d.y, dz = d.z;
        
        // Process queries in batch
        for (int i = 0; i < queries.size(); i++) {
            Vertex q = queries.get(i);
            results[i] = Geometry.inSphereFast(ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz, q.x, q.y, q.z);
        }
        
        return results;
    }
    
    /**
     * Check if all ears in a list are regular (batch processing)
     */
    public static boolean allEarsRegular(List<OrientedFace> ears) {
        // Process in groups to improve cache locality
        final int BATCH_SIZE = 8;
        
        for (int i = 0; i < ears.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, ears.size());
            
            // Check batch of ears
            for (int j = i; j < end; j++) {
                if (!ears.get(j).isRegular()) {
                    return false;
                }
            }
        }
        
        return true;
    }
    
    /**
     * Count reflex edges in batch (used in flip operations)
     */
    public static int countReflexEdges(OrientedFace face) {
        int count = 0;
        
        // Unroll loop for better performance
        if (face.isReflex(0)) count++;
        if (count < 2 && face.isReflex(1)) count++;
        if (count < 2 && face.isReflex(2)) count++;
        
        return count;
    }
}