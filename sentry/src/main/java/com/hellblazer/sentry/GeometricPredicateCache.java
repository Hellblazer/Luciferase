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

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Cache for geometric predicate calculations to avoid redundant computations.
 * Optimized for single-threaded use.
 * 
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 */
public class GeometricPredicateCache {
    private static final int INITIAL_CAPACITY = 1024;
    private static final int MAX_CACHE_SIZE = 8192;
    
    // Cache for orientation results
    private final Map<OrientationKey, Double> orientationCache;
    
    // Cache for insphere results
    private final Map<InSphereKey, Double> inSphereCache;
    
    // Statistics
    private int orientationHits = 0;
    private int orientationMisses = 0;
    private int inSphereHits = 0;
    private int inSphereMisses = 0;
    
    // Singleton instance
    private static final GeometricPredicateCache INSTANCE = new GeometricPredicateCache();
    
    private GeometricPredicateCache() {
        this.orientationCache = new HashMap<>(INITIAL_CAPACITY);
        this.inSphereCache = new HashMap<>(INITIAL_CAPACITY);
    }
    
    /**
     * Get the singleton instance
     */
    public static GeometricPredicateCache getInstance() {
        return INSTANCE;
    }
    
    /**
     * Compute orientation with caching
     */
    public double orientation(Vertex query, Vertex a, Vertex b, Vertex c) {
        OrientationKey key = new OrientationKey(query, a, b, c);
        Double cached = orientationCache.get(key);
        
        if (cached != null) {
            orientationHits++;
            return cached;
        }
        
        orientationMisses++;
        double result = query.orientation(a, b, c);
        
        // Only cache if we haven't exceeded max size
        if (orientationCache.size() < MAX_CACHE_SIZE) {
            orientationCache.put(key, result);
        }
        
        return result;
    }
    
    /**
     * Compute inSphere with caching
     */
    public double inSphere(Vertex query, Vertex a, Vertex b, Vertex c, Vertex d) {
        InSphereKey key = new InSphereKey(query, a, b, c, d);
        Double cached = inSphereCache.get(key);
        
        if (cached != null) {
            inSphereHits++;
            return cached;
        }
        
        inSphereMisses++;
        double result = query.inSphere(a, b, c, d);
        
        // Only cache if we haven't exceeded max size
        if (inSphereCache.size() < MAX_CACHE_SIZE) {
            inSphereCache.put(key, result);
        }
        
        return result;
    }
    
    /**
     * Clear all caches
     */
    public void clear() {
        orientationCache.clear();
        inSphereCache.clear();
        orientationHits = orientationMisses = 0;
        inSphereHits = inSphereMisses = 0;
    }
    
    /**
     * Clear caches if they're getting too large
     */
    public void clearIfNeeded() {
        if (orientationCache.size() >= MAX_CACHE_SIZE) {
            orientationCache.clear();
            orientationHits = orientationMisses = 0;
        }
        if (inSphereCache.size() >= MAX_CACHE_SIZE) {
            inSphereCache.clear();
            inSphereHits = inSphereMisses = 0;
        }
    }
    
    /**
     * Get cache statistics
     */
    public String getStatistics() {
        double orientationHitRate = orientationHits + orientationMisses > 0 ?
            100.0 * orientationHits / (orientationHits + orientationMisses) : 0.0;
        double inSphereHitRate = inSphereHits + inSphereMisses > 0 ?
            100.0 * inSphereHits / (inSphereHits + inSphereMisses) : 0.0;
            
        return String.format(
            "GeometricPredicateCache[orientation: %d entries, %.1f%% hit rate (%d/%d), " +
            "inSphere: %d entries, %.1f%% hit rate (%d/%d)]",
            orientationCache.size(), orientationHitRate, orientationHits, orientationHits + orientationMisses,
            inSphereCache.size(), inSphereHitRate, inSphereHits, inSphereHits + inSphereMisses
        );
    }
    
    /**
     * Key for orientation cache - uses vertex identity
     */
    private static class OrientationKey {
        private final Vertex query, a, b, c;
        private final int hashCode;
        
        OrientationKey(Vertex query, Vertex a, Vertex b, Vertex c) {
            this.query = query;
            this.a = a;
            this.b = b;
            this.c = c;
            // Pre-compute hash code using identity hash codes
            this.hashCode = Objects.hash(
                System.identityHashCode(query),
                System.identityHashCode(a),
                System.identityHashCode(b),
                System.identityHashCode(c)
            );
        }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof OrientationKey)) return false;
            OrientationKey other = (OrientationKey) obj;
            // Use identity comparison for vertices
            return query == other.query && a == other.a && 
                   b == other.b && c == other.c;
        }
        
        @Override
        public int hashCode() {
            return hashCode;
        }
    }
    
    /**
     * Key for inSphere cache - uses vertex identity
     */
    private static class InSphereKey {
        private final Vertex query, a, b, c, d;
        private final int hashCode;
        
        InSphereKey(Vertex query, Vertex a, Vertex b, Vertex c, Vertex d) {
            this.query = query;
            this.a = a;
            this.b = b;
            this.c = c;
            this.d = d;
            // Pre-compute hash code using identity hash codes
            this.hashCode = Objects.hash(
                System.identityHashCode(query),
                System.identityHashCode(a),
                System.identityHashCode(b),
                System.identityHashCode(c),
                System.identityHashCode(d)
            );
        }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof InSphereKey)) return false;
            InSphereKey other = (InSphereKey) obj;
            // Use identity comparison for vertices
            return query == other.query && a == other.a && 
                   b == other.b && c == other.c && d == other.d;
        }
        
        @Override
        public int hashCode() {
            return hashCode;
        }
    }
}