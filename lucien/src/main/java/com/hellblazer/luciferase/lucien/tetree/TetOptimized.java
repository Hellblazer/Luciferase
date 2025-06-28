package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.Constants;

/**
 * Optimized tmIndex implementation that processes bits during parent walk
 * for better CPU cache utilization.
 * 
 * This is an experimental optimization - not used in production yet.
 */
public class TetOptimized {
    
    /**
     * Optimized tmIndex that processes bits during parent walk to improve cache locality.
     * Instead of:
     * 1. Walk parent chain to collect types
     * 2. Build bit pattern from types
     * 
     * This does:
     * 1. Walk parent chain AND build bits incrementally
     * 
     * This keeps data in CPU cache while processing.
     */
    public static BaseTetreeKey<?> tmIndexOptimized(Tet tet) {
        // Fast path for cached result
        var cached = TetreeLevelCache.getCachedTetreeKey(tet.x(), tet.y(), tet.z(), tet.l(), tet.type());
        if (cached != null) {
            return cached;
        }
        
        if (tet.l() == 0) {
            return Tet.ROOT_TET;
        }
        
        // Determine coordinate format
        int maxGridCoord = (1 << tet.l()) - 1;
        boolean isGridCoordinates = tet.x() <= maxGridCoord && tet.y() <= maxGridCoord && tet.z() <= maxGridCoord;
        
        int shiftedX, shiftedY, shiftedZ;
        if (isGridCoordinates) {
            int shiftAmount = Constants.getMaxRefinementLevel() - tet.l();
            shiftedX = tet.x() << shiftAmount;
            shiftedY = tet.y() << shiftAmount;
            shiftedZ = tet.z() << shiftAmount;
        } else {
            shiftedX = tet.x();
            shiftedY = tet.y();
            shiftedZ = tet.z();
        }
        
        // Initialize bit accumulators
        long lowBits = 0L;
        long highBits = 0L;
        
        // Process bits during parent walk for better cache locality
        Tet current = tet;
        int level = tet.l();
        
        // Process from current level down to root, building bits as we go
        for (int i = level - 1; i >= 0; i--) {
            // Get coordinate bits for this level
            int bitPos = Constants.getMaxRefinementLevel() - 1 - i;
            int xBit = (shiftedX >> bitPos) & 1;
            int yBit = (shiftedY >> bitPos) & 1;
            int zBit = (shiftedZ >> bitPos) & 1;
            
            // Combine coordinate bits
            int coordBits = (zBit << 2) | (yBit << 1) | xBit;
            
            // Get type for this level
            byte typeAtLevel;
            if (i == level - 1) {
                // Current level - use current type
                typeAtLevel = current.type();
            } else {
                // Need parent type
                current = current.parent();
                typeAtLevel = current.type();
            }
            
            // Combine with type bits
            int sixBits = (coordBits << 3) | typeAtLevel;
            
            // Pack into appropriate long
            if (i < 10) {
                lowBits |= ((long) sixBits) << (6 * i);
            } else {
                highBits |= ((long) sixBits) << (6 * (i - 10));
            }
        }
        
        // Create result
        BaseTetreeKey<? extends BaseTetreeKey> result;
        if (tet.l() <= 10) {
            result = new CompactTetreeKey(tet.l(), lowBits);
        } else {
            result = new TetreeKey(tet.l(), lowBits, highBits);
        }
        
        // Cache result
        TetreeLevelCache.cacheTetreeKey(tet.x(), tet.y(), tet.z(), tet.l(), tet.type(), result);
        
        return result;
    }
    
    /**
     * Production-ready optimized tmIndex that uses better parent walk ordering.
     * This version minimizes cache misses by processing the parent chain in memory order.
     */
    public static BaseTetreeKey<?> tmIndexOptimizedV3(Tet tet) {
        // Fast path for cached result
        var cached = TetreeLevelCache.getCachedTetreeKey(tet.x(), tet.y(), tet.z(), tet.l(), tet.type());
        if (cached != null) {
            return cached;
        }
        
        if (tet.l() == 0) {
            return Tet.ROOT_TET;
        }
        
        // Build parent chain forward (improves cache locality)
        Tet[] parentChain = new Tet[tet.l() + 1];
        parentChain[tet.l()] = tet;
        
        // Walk up and cache each parent
        for (int level = tet.l() - 1; level >= 0; level--) {
            Tet parent = parentChain[level + 1].parent();
            parentChain[level] = parent;
            
            // Cache intermediate parents for future use
            if (level > 0) {
                TetreeLevelCache.cacheParent(parent.x(), parent.y(), parent.z(), 
                    parent.l(), parent.type(), parentChain[level - 1]);
            }
        }
        
        // Cache the complete parent chain
        TetreeLevelCache.cacheParentChain(tet, parentChain);
        
        // Now build tmIndex with optimal memory access pattern
        return buildTmIndexFromChain(tet, parentChain);
    }
    
    /**
     * Alternative optimization: Use a single loop that walks parents and builds bits together
     */
    public static BaseTetreeKey<?> tmIndexOptimizedV2(Tet tet) {
        if (tet.l() == 0) {
            return Tet.ROOT_TET;
        }
        
        // Check cache first
        var cached = TetreeLevelCache.getCachedTetreeKey(tet.x(), tet.y(), tet.z(), tet.l(), tet.type());
        if (cached != null) {
            return cached;
        }
        
        // Prepare coordinates
        int maxGridCoord = (1 << tet.l()) - 1;
        boolean isGridCoordinates = tet.x() <= maxGridCoord && tet.y() <= maxGridCoord && tet.z() <= maxGridCoord;
        
        int shiftedX, shiftedY, shiftedZ;
        if (isGridCoordinates) {
            int shiftAmount = Constants.getMaxRefinementLevel() - tet.l();
            shiftedX = tet.x() << shiftAmount;
            shiftedY = tet.y() << shiftAmount;
            shiftedZ = tet.z() << shiftAmount;
        } else {
            shiftedX = tet.x();
            shiftedY = tet.y();
            shiftedZ = tet.z();
        }
        
        // Build parent chain in reverse order while building bits
        long lowBits = 0L;
        long highBits = 0L;
        
        // Stack to hold types as we walk up
        byte[] types = new byte[tet.l()];
        Tet current = tet;
        
        // Walk up to collect types
        for (int i = tet.l() - 1; i >= 0; i--) {
            types[i] = current.type();
            if (i > 0) {
                current = current.parent();
            }
        }
        
        // Now build bits with types in correct order
        for (int i = 0; i < tet.l(); i++) {
            int bitPos = Constants.getMaxRefinementLevel() - 1 - i;
            int xBit = (shiftedX >> bitPos) & 1;
            int yBit = (shiftedY >> bitPos) & 1;
            int zBit = (shiftedZ >> bitPos) & 1;
            
            int coordBits = (zBit << 2) | (yBit << 1) | xBit;
            int sixBits = (coordBits << 3) | types[i];
            
            if (i < 10) {
                lowBits |= ((long) sixBits) << (6 * i);
            } else {
                highBits |= ((long) sixBits) << (6 * (i - 10));
            }
        }
        
        // Create and cache result
        BaseTetreeKey<? extends BaseTetreeKey> result;
        if (tet.l() <= 10) {
            result = new CompactTetreeKey(tet.l(), lowBits);
        } else {
            result = new TetreeKey(tet.l(), lowBits, highBits);
        }
        
        TetreeLevelCache.cacheTetreeKey(tet.x(), tet.y(), tet.z(), tet.l(), tet.type(), result);
        
        return result;
    }
    
    /**
     * Build tmIndex from a complete parent chain for optimal cache locality.
     */
    private static BaseTetreeKey<?> buildTmIndexFromChain(Tet tet, Tet[] parentChain) {
        // Determine coordinate format
        int maxGridCoord = (1 << tet.l()) - 1;
        boolean isGridCoordinates = tet.x() <= maxGridCoord && tet.y() <= maxGridCoord && tet.z() <= maxGridCoord;
        
        int shiftedX, shiftedY, shiftedZ;
        if (isGridCoordinates) {
            int shiftAmount = Constants.getMaxRefinementLevel() - tet.l();
            shiftedX = tet.x() << shiftAmount;
            shiftedY = tet.y() << shiftAmount;
            shiftedZ = tet.z() << shiftAmount;
        } else {
            shiftedX = tet.x();
            shiftedY = tet.y();
            shiftedZ = tet.z();
        }
        
        // Build TM-index by interleaving coordinate bits with type information
        long lowBits = 0L;
        long highBits = 0L;
        
        // Process each level in order using the parent chain (better cache locality)
        for (int i = 0; i < tet.l(); i++) {
            // Extract coordinate bits for this level
            int bitPos = Constants.getMaxRefinementLevel() - 1 - i;
            int xBit = (shiftedX >> bitPos) & 1;
            int yBit = (shiftedY >> bitPos) & 1;
            int zBit = (shiftedZ >> bitPos) & 1;
            
            // Combine coordinate bits
            int coordBits = (zBit << 2) | (yBit << 1) | xBit;
            
            // Get type from parent chain (no additional parent() calls needed)
            int typeAtLevel = parentChain[i + 1].type(); // +1 because parentChain[0] is root
            
            // Combine coordinate and type bits
            int sixBits = (coordBits << 3) | typeAtLevel;
            
            // Pack into appropriate long
            if (i < 10) {
                lowBits |= ((long) sixBits) << (6 * i);
            } else {
                highBits |= ((long) sixBits) << (6 * (i - 10));
            }
        }
        
        // Create result
        BaseTetreeKey<? extends BaseTetreeKey> result;
        if (tet.l() <= 10) {
            result = new CompactTetreeKey(tet.l(), lowBits);
        } else {
            result = new TetreeKey(tet.l(), lowBits, highBits);
        }
        
        // Cache result
        TetreeLevelCache.cacheTetreeKey(tet.x(), tet.y(), tet.z(), tet.l(), tet.type(), result);
        
        return result;
    }
}