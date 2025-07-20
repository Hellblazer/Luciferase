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
 * Factory for creating GeometricPredicates implementations.
 * Automatically selects SIMD implementation if available and enabled.
 * 
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 */
public class GeometricPredicatesFactory {
    
    private static GeometricPredicates instance;
    
    /**
     * Predicate modes available.
     */
    public enum PredicateMode {
        SCALAR,    // Basic scalar implementation
        SIMD,      // SIMD-accelerated implementation (if available)
        EXACT,     // Exact implementation using Shewchuk's robust predicates
        HYBRID,    // Hybrid mode with fallback to exact (when implemented)
        ADAPTIVE   // Adaptive mode with exact fallback (future)
    }
    
    /**
     * Create a new GeometricPredicates instance using configuration.
     * 
     * @param config The configuration to use
     * @return A new GeometricPredicates instance
     */
    public static GeometricPredicates create(SentryConfiguration config) {
        PredicateMode mode = config.getPredicateMode();
        
        // If mode is SIMD but not available, fall back to SCALAR
        if (mode == PredicateMode.SIMD && !config.isSIMDEnabled()) {
            mode = PredicateMode.SCALAR;
        }
        
        // Create appropriate implementation based on mode
        switch (mode) {
            case EXACT:
                System.out.println("Using exact geometric predicates");
                return new ExactGeometricPredicates();
                
            case HYBRID:
                System.out.println("Using hybrid geometric predicates");
                return new HybridGeometricPredicates();
                
            case SIMD:
                if (SIMDSupport.isAvailable()) {
                    try {
                        // Try to load SIMD implementation via reflection
                        // This allows the code to compile without preview features
                        Class<?> simdClass = Class.forName(
                            "com.hellblazer.sentry.SIMDGeometricPredicates"
                        );
                        GeometricPredicates simd = (GeometricPredicates) simdClass
                            .getDeclaredConstructor()
                            .newInstance();
                        System.out.println("Using SIMD geometric predicates");
                        return simd;
                    } catch (Exception e) {
                        System.err.println("Failed to load SIMD implementation: " + e.getMessage());
                        // Fall back to scalar
                    }
                }
                System.out.println("SIMD requested but not available, falling back to scalar");
                return new ScalarGeometricPredicates();
                
            case ADAPTIVE:
                System.out.println("Using adaptive geometric predicates");
                AdaptiveGeometricPredicates adaptive = new AdaptiveGeometricPredicates();
                if (config.getAdaptiveEpsilon() != null) {
                    adaptive.setEpsilon(config.getAdaptiveEpsilon());
                }
                return adaptive;
                
            case SCALAR:
            default:
                System.out.println("Using scalar geometric predicates");
                return new ScalarGeometricPredicates();
        }
    }
    
    /**
     * Create a new GeometricPredicates instance using default configuration.
     * Provided for backward compatibility.
     */
    public static GeometricPredicates create() {
        return create(SentryConfiguration.getDefault());
    }
    
    /**
     * Get a singleton instance of GeometricPredicates.
     * The instance is created on first access and cached.
     */
    public static synchronized GeometricPredicates getInstance() {
        if (instance == null) {
            instance = create();
        }
        return instance;
    }
    
    /**
     * Force recreation of the singleton instance.
     * Useful if SIMD settings change at runtime.
     */
    public static synchronized void reset() {
        instance = null;
    }
    
    /**
     * Set a custom instance as the singleton.
     * This is useful for testing with specific predicate implementations.
     * 
     * @param predicates The predicates instance to use as singleton
     */
    public static synchronized void setInstance(GeometricPredicates predicates) {
        instance = predicates;
    }
    
    /**
     * Get information about the current implementation.
     */
    public static String getImplementationInfo() {
        GeometricPredicates current = getInstance();
        return String.format("Implementation: %s, SIMD Status: %s",
            current.getImplementationName(),
            SIMDSupport.getStatus());
    }
}