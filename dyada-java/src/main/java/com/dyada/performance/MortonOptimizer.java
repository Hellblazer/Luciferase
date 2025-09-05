package com.dyada.performance;

/**
 * Optimized Morton order (Z-order) encoding/decoding utilities
 * Uses lookup tables and bit manipulation for maximum performance
 */
public final class MortonOptimizer {
    
    // Pre-computed lookup tables for 16-bit Morton encoding
    private static final int[] MORTON_TABLE_256 = new int[256];
    private static final int[] MORTON_DECODE_X = new int[65536];
    private static final int[] MORTON_DECODE_Y = new int[65536];
    
    static {
        initializeLookupTables();
    }
    
    private static void initializeLookupTables() {
        // Initialize Morton encoding table for 8-bit values
        for (int i = 0; i < 256; i++) {
            MORTON_TABLE_256[i] = part1By1(i);
        }
        
        // Initialize Morton decoding tables
        for (int i = 0; i < 65536; i++) {
            MORTON_DECODE_X[i] = compact1By1(i);
            MORTON_DECODE_Y[i] = compact1By1(i >> 1);
        }
    }
    
    /**
     * Interleave bits of x and y to create Morton code (2D)
     * Optimized for 32-bit coordinates
     */
    public static long encode2D(int x, int y) {
        return (long) interleave32(x) | ((long) interleave32(y) << 1);
    }
    
    /**
     * Interleave bits of x, y, and z to create Morton code (3D)
     * Optimized for 21-bit coordinates (63 bits total)
     */
    public static long encode3D(int x, int y, int z) {
        return (long) interleave21(x) | 
               ((long) interleave21(y) << 1) | 
               ((long) interleave21(z) << 2);
    }
    
    /**
     * Decode Morton code to x coordinate (2D)
     */
    public static int decodeX2D(long morton) {
        return compact1By1((int) morton);
    }
    
    /**
     * Decode Morton code to y coordinate (2D)
     */
    public static int decodeY2D(long morton) {
        return compact1By1((int) (morton >> 1));
    }
    
    /**
     * Decode Morton code to x coordinate (3D)
     */
    public static int decodeX3D(long morton) {
        return compact1By2((int) morton);
    }
    
    /**
     * Decode Morton code to y coordinate (3D)
     */
    public static int decodeY3D(long morton) {
        return compact1By2((int) (morton >> 1));
    }
    
    /**
     * Decode Morton code to z coordinate (3D)
     */
    public static int decodeZ3D(long morton) {
        return compact1By2((int) (morton >> 2));
    }
    
    /**
     * Fast 2D Morton decoding using lookup tables
     */
    public static void decode2DFast(long morton, int[] result) {
        result[0] = MORTON_DECODE_X[(int) (morton & 0xFFFF)] | 
                   (MORTON_DECODE_X[(int) ((morton >> 16) & 0xFFFF)] << 8);
        result[1] = MORTON_DECODE_Y[(int) (morton & 0xFFFF)] | 
                   (MORTON_DECODE_Y[(int) ((morton >> 16) & 0xFFFF)] << 8);
    }
    
    /**
     * Interleave bits for 32-bit value (2D encoding)
     */
    private static int interleave32(int x) {
        x = (x | (x << 16)) & 0x0000FFFF;
        x = (x | (x << 8))  & 0x00FF00FF;
        x = (x | (x << 4))  & 0x0F0F0F0F;
        x = (x | (x << 2))  & 0x33333333;
        x = (x | (x << 1))  & 0x55555555;
        return x;
    }
    
    /**
     * Interleave bits for 21-bit value (3D encoding)
     */
    private static long interleave21(int x) {
        long xl = x;
        xl = (xl | (xl << 32)) & 0x1f00000000ffffL;
        xl = (xl | (xl << 16)) & 0x1f0000ff0000ffL;
        xl = (xl | (xl << 8))  & 0x100f00f00f00f00fL;
        xl = (xl | (xl << 4))  & 0x10c30c30c30c30c3L;
        xl = (xl | (xl << 2))  & 0x1249249249249249L;
        return xl;
    }
    
    /**
     * Part-by-1 encoding for lookup table initialization
     */
    private static int part1By1(int x) {
        x &= 0x0000ffff;
        x = (x ^ (x << 8))  & 0x00ff00ff;
        x = (x ^ (x << 4))  & 0x0f0f0f0f;
        x = (x ^ (x << 2))  & 0x33333333;
        x = (x ^ (x << 1))  & 0x55555555;
        return x;
    }
    
    /**
     * Compact by 1 (inverse of part1By1)
     */
    private static int compact1By1(int x) {
        x &= 0x55555555;
        x = (x ^ (x >> 1))  & 0x33333333;
        x = (x ^ (x >> 2))  & 0x0f0f0f0f;
        x = (x ^ (x >> 4))  & 0x00ff00ff;
        x = (x ^ (x >> 8))  & 0x0000ffff;
        return x;
    }
    
    /**
     * Compact by 2 (for 3D decoding)
     */
    private static int compact1By2(int x) {
        x &= 0x09249249;
        x = (x ^ (x >> 2))  & 0x030c30c3;
        x = (x ^ (x >> 4))  & 0x0300f00f;
        x = (x ^ (x >> 8))  & 0xff0000ff;
        x = (x ^ (x >> 16)) & 0x000003ff;
        return x;
    }
    
    /**
     * Calculate the Morton code for a point within a bounding box
     * Optimized for floating-point coordinates
     */
    public static long encodeNormalized2D(double x, double y, double minX, double minY, 
                                        double maxX, double maxY, int precision) {
        double scaleX = (1L << precision) / (maxX - minX);
        double scaleY = (1L << precision) / (maxY - minY);
        
        int ix = (int) ((x - minX) * scaleX);
        int iy = (int) ((y - minY) * scaleY);
        
        // Clamp to valid range
        ix = Math.max(0, Math.min((1 << precision) - 1, ix));
        iy = Math.max(0, Math.min((1 << precision) - 1, iy));
        
        return encode2D(ix, iy);
    }
    
    /**
     * Get the parent Morton code at a higher level
     */
    public static long getParent2D(long morton) {
        return morton >> 2;
    }
    
    /**
     * Get the child Morton codes at a lower level
     */
    public static void getChildren2D(long morton, long[] children) {
        long base = morton << 2;
        children[0] = base;      // 00
        children[1] = base | 1;  // 01
        children[2] = base | 2;  // 10
        children[3] = base | 3;  // 11
    }
    
    /**
     * Calculate Morton distance between two codes
     * Useful for spatial proximity queries
     */
    public static int mortonDistance2D(long morton1, long morton2) {
        long xor = morton1 ^ morton2;
        return Long.numberOfTrailingZeros(xor) / 2;
    }
    
    /**
     * Check if a Morton code is within a rectangular range
     */
    public static boolean isInRange2D(long morton, long minMorton, long maxMorton) {
        return morton >= minMorton && morton <= maxMorton;
    }
    
    /**
     * Get the level of a Morton code (depth in quadtree)
     */
    public static int getLevel2D(long morton) {
        if (morton == 0) return 0;
        return (63 - Long.numberOfLeadingZeros(morton) + 1) / 2;
    }
    
    /**
     * Get the maximum Morton code at a given level
     */
    public static long getMaxMortonAtLevel2D(int level) {
        if (level <= 0) return 0;
        return (1L << (level * 2)) - 1;
    }
    
    /**
     * Morton encoding cache for frequently accessed coordinates
     */
    private static final DyAdaCache<Long, Long> MORTON_CACHE_2D = DyAdaCache.createLRU(1000);
    private static final DyAdaCache<Long, Long> MORTON_CACHE_3D = DyAdaCache.createLRU(1000);
    
    /**
     * Cached Morton encoding for 2D coordinates
     * Uses LRU cache to store recently computed values
     */
    public static long encode2DCached(int x, int y) {
        long key = ((long) x << 32) | (y & 0xFFFFFFFFL);
        return MORTON_CACHE_2D.get(key, k -> encode2D(x, y));
    }
    
    /**
     * Cached Morton encoding for 3D coordinates
     * Uses LRU cache to store recently computed values
     */
    public static long encode3DCached(int x, int y, int z) {
        long key = ((long) x << 42) | ((long) y << 21) | (z & 0x1FFFFFL);
        return MORTON_CACHE_3D.get(key, k -> encode3D(x, y, z));
    }
    
    /**
     * Clear the Morton encoding caches
     */
    public static void clearCaches() {
        MORTON_CACHE_2D.clear();
        MORTON_CACHE_3D.clear();
    }
    
    /**
     * Get cache statistics for performance monitoring
     */
    public static String getCacheStats() {
        return String.format("Morton2D cache: %d entries, Morton3D cache: %d entries",
                MORTON_CACHE_2D.size(), MORTON_CACHE_3D.size());
    }
}