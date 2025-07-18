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
 * Scalar implementation of geometric predicates.
 * This is the default implementation that works without preview features.
 * 
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 */
public class ScalarGeometricPredicates implements GeometricPredicates {
    
    @Override
    public double orientation(double ax, double ay, double az, 
                            double bx, double by, double bz,
                            double cx, double cy, double cz,
                            double dx, double dy, double dz) {
        // Use existing fast implementation
        return Geometry.leftOfPlaneFast(ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz);
    }
    
    @Override
    public double inSphere(double ax, double ay, double az,
                          double bx, double by, double bz,
                          double cx, double cy, double cz,
                          double dx, double dy, double dz,
                          double ex, double ey, double ez) {
        // Use existing fast implementation
        return Geometry.inSphereFast(ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz, ex, ey, ez);
    }
    
    @Override
    public String getImplementationName() {
        return "Scalar";
    }
}