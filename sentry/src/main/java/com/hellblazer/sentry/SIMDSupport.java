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
 * Runtime detection for SIMD Vector API support.
 * This class can be compiled with or without preview features enabled.
 * 
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 */
public class SIMDSupport {
    
    private static final boolean VECTOR_API_AVAILABLE;
    private static boolean simdEnabled = true; // Default to enabled if available
    
    static {
        boolean available = false;
        try {
            // Check if Vector API is available
            // This will fail if not running with --enable-preview
            Class.forName("jdk.incubator.vector.Vector");
            available = true;
            System.out.println("SIMD Vector API detected and available");
        } catch (ClassNotFoundException e) {
            System.out.println("SIMD Vector API not available (preview features not enabled)");
        } catch (UnsupportedClassVersionError e) {
            System.out.println("SIMD Vector API not available (incompatible Java version)");
        }
        VECTOR_API_AVAILABLE = available;
    }
    
    /**
     * Check if SIMD optimizations are available and enabled.
     * 
     * @return true if Vector API is available and enabled
     */
    public static boolean isAvailable() {
        return VECTOR_API_AVAILABLE && simdEnabled;
    }
    
    /**
     * Check if SIMD optimizations are available (regardless of property).
     * 
     * @return true if Vector API is available
     */
    public static boolean isSupported() {
        return VECTOR_API_AVAILABLE;
    }
    
    /**
     * Enable or disable SIMD optimizations at runtime.
     * 
     * @param enabled true to enable SIMD (if available)
     */
    public static void setEnabled(boolean enabled) {
        simdEnabled = enabled;
    }
    
    /**
     * Get a description of the SIMD support status.
     * 
     * @return human-readable status string
     */
    public static String getStatus() {
        if (!VECTOR_API_AVAILABLE) {
            return "SIMD not available (preview features not enabled)";
        } else if (!isAvailable()) {
            return "SIMD available but disabled (set -Dsentry.enableSIMD=true to enable)";
        } else {
            return "SIMD enabled and active";
        }
    }
}