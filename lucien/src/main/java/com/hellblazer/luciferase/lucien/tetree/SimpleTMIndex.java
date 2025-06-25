package com.hellblazer.luciferase.lucien.tetree;

import java.math.BigInteger;

/**
 * Simple implementation of the Tetrahedral Morton Index (TM-index) based on the
 * bit-interleaving scheme described in the tetrahedral space-filling curve papers.
 * 
 * The TM-index interleaves coordinate bits with type information to create a
 * Morton-like encoding for tetrahedra. Each level contributes 6 bits:
 * - 3 bits for spatial position (x, y, z interleaved)
 * - 3 bits for tetrahedral type (0-5)
 * 
 * This is different from the SFC index which encodes the tree path.
 */
public class SimpleTMIndex {
    
    /**
     * Encode a tetrahedron into TM-index using bit interleaving.
     * 
     * @param tet The tetrahedron to encode
     * @return The TM-index as BigInteger
     */
    public static BigInteger encode(Tet tet) {
        if (tet.l() == 0) {
            return BigInteger.ZERO;
        }
        
        BigInteger result = BigInteger.ZERO;
        
        // For each level, we encode 6 bits: 3 for coordinates, 3 for type
        // We need to extract the coordinate bits at each level
        int x = tet.x();
        int y = tet.y();
        int z = tet.z();
        
        // Start from the most significant bits (highest level)
        for (int level = tet.l() - 1; level >= 0; level--) {
            // Extract the bit at this level for each coordinate
            int xBit = (x >> level) & 1;
            int yBit = (y >> level) & 1;
            int zBit = (z >> level) & 1;
            
            // Combine into 3-bit cube position (standard Morton interleaving)
            int cubePos = (xBit << 2) | (yBit << 1) | zBit;
            
            // For now, use the tetrahedron type at the leaf level
            // In a full implementation, we'd compute the type at each level
            int typeAtLevel = (level == 0) ? tet.type() : 0;
            
            // Combine cube position and type into 6 bits
            int sixBits = (cubePos << 3) | typeAtLevel;
            
            // Shift result and add these 6 bits
            result = result.shiftLeft(6).or(BigInteger.valueOf(sixBits));
        }
        
        return result;
    }
    
    /**
     * Decode a TM-index back to a tetrahedron.
     * 
     * @param tmIndex The TM-index to decode
     * @param level The level of the tetrahedron
     * @return The decoded tetrahedron
     */
    public static Tet decode(BigInteger tmIndex, byte level) {
        if (level == 0) {
            return new Tet(0, 0, 0, (byte) 0, (byte) 0);
        }
        
        // Extract coordinate bits and type bits
        int x = 0, y = 0, z = 0;
        byte finalType = 0;
        
        BigInteger sixty_four = BigInteger.valueOf(64); // 2^6
        BigInteger index = tmIndex;
        
        // Process from least significant to most significant
        for (int i = 0; i < level; i++) {
            // Extract 6 bits
            BigInteger[] divRem = index.divideAndRemainder(sixty_four);
            index = divRem[0];
            int sixBits = divRem[1].intValue();
            
            // Lower 3 bits are type
            int typeAtLevel = sixBits & 7;
            
            // Upper 3 bits are cube position
            int cubePos = sixBits >> 3;
            
            // Extract x, y, z bits from cube position
            int zBit = cubePos & 1;
            int yBit = (cubePos >> 1) & 1;
            int xBit = (cubePos >> 2) & 1;
            
            // Build coordinates from bits (most significant first)
            x = (x << 1) | xBit;
            y = (y << 1) | yBit;
            z = (z << 1) | zBit;
            
            // The type at the finest level is what we want
            if (i == 0) {
                finalType = (byte) typeAtLevel;
            }
        }
        
        return new Tet(x, y, z, level, finalType);
    }
    
    /**
     * Create a simple TM-index that just encodes coordinates and type without
     * the full hierarchical type computation.
     * 
     * This version treats each level independently and doesn't track how
     * types change through the hierarchy.
     */
    public static BigInteger simpleEncode(int x, int y, int z, byte level, byte type) {
        BigInteger result = BigInteger.ZERO;
        
        // Interleave coordinate bits
        for (int i = level - 1; i >= 0; i--) {
            int xBit = (x >> i) & 1;
            int yBit = (y >> i) & 1;
            int zBit = (z >> i) & 1;
            
            // Standard Morton bit interleaving: x is MSB, z is LSB
            int threeBits = (xBit << 2) | (yBit << 1) | zBit;
            
            // Shift and add
            result = result.shiftLeft(3).or(BigInteger.valueOf(threeBits));
        }
        
        // Append type at the end
        result = result.shiftLeft(3).or(BigInteger.valueOf(type));
        
        return result;
    }
    
    /**
     * Decode a simple TM-index.
     */
    public static Tet simpleDecode(BigInteger tmIndex, byte level) {
        if (level == 0) {
            return new Tet(0, 0, 0, (byte) 0, (byte) 0);
        }
        
        // Extract type (last 3 bits)
        byte type = tmIndex.and(BigInteger.valueOf(7)).byteValue();
        BigInteger coords = tmIndex.shiftRight(3);
        
        // Extract interleaved coordinate bits
        int x = 0, y = 0, z = 0;
        
        for (int i = 0; i < level; i++) {
            int threeBits = coords.and(BigInteger.valueOf(7)).intValue();
            coords = coords.shiftRight(3);
            
            int zBit = threeBits & 1;
            int yBit = (threeBits >> 1) & 1;
            int xBit = (threeBits >> 2) & 1;
            
            // Build from least significant to most significant
            x = x | (xBit << i);
            y = y | (yBit << i);
            z = z | (zBit << i);
        }
        
        return new Tet(x, y, z, level, type);
    }
}