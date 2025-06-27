package com.hellblazer.luciferase.lucien.tetree;

/**
 * Clean 128-bit TM-Index implementation without debug output
 */
public class TMIndex128Clean {

    // Maximum supported levels (128 bits / 6 bits per level = 21)
    private static final int MAX_LEVELS      = 21;
    private static final int LEVELS_PER_LONG = 10;
    // Child type transformation table
    private static final int[][] CHILD_TYPES = { { 0, 0, 0, 0, 4, 5, 2, 1 }, { 1, 1, 1, 1, 3, 2, 5, 0 },
                                                 { 2, 2, 2, 2, 0, 1, 4, 3 }, { 3, 3, 3, 3, 5, 4, 1, 2 },
                                                 { 4, 4, 4, 4, 2, 3, 0, 5 }, { 5, 5, 5, 5, 1, 0, 3, 4 } };

    /**
     * Compute type sequence for encoding
     */
    private static int[] computeTypeSequence(TetId tet) {
        int[] types = new int[tet.level];
        if (tet.level == 0) {
            return types;
        }

        types[0] = 0; // Root always type 0

        // Compute the type at each level based on the refinement path
        int currentType = 0;
        for (int level = 1; level < tet.level; level++) {
            int bitPos = level - 1;
            int childIdx = ((tet.z >> bitPos) & 1) << 2 | ((tet.y >> bitPos) & 1) << 1 | ((tet.x >> bitPos) & 1);
            currentType = CHILD_TYPES[currentType][childIdx];
            types[level] = currentType;
        }

        return types;
    }

    /**
     * Create a TetId with automatically computed type based on coordinates
     */
    public static TetId createWithComputedType(int x, int y, int z, int level) {
        if (level == 0) {
            return new TetId(0, 0, 0, 0, 0);
        }

        // Compute the correct type based on the refinement path
        int currentType = 0;

        // Process each level to compute the type at that level
        for (int lev = 0; lev < level; lev++) {
            int bitPos = lev;
            int childIdx = ((z >> bitPos) & 1) << 2 | ((y >> bitPos) & 1) << 1 | ((x >> bitPos) & 1);
            currentType = CHILD_TYPES[currentType][childIdx];
        }

        return new TetId(x, y, z, currentType, level);
    }

    /**
     * DECODE: Extract TetId from 128-bit TM-index
     */
    public static TetId decode(TMIndex128Bit tmIndex, int level) {
        if (level > MAX_LEVELS) {
            throw new IllegalArgumentException("Level exceeds maximum: " + level);
        }

        if (level == 0) {
            return new TetId(0, 0, 0, 0, 0);
        }

        int x = 0, y = 0, z = 0;

        // Process each level to build coordinates
        for (int i = 0; i < level; i++) {
            // Extract 6 bits for this level
            int sixBits;
            if (i < LEVELS_PER_LONG) {
                sixBits = (int) ((tmIndex.low >> (6 * i)) & 0x3F);
            } else {
                int highBit = i - LEVELS_PER_LONG;
                sixBits = (int) ((tmIndex.high >> (6 * highBit)) & 0x3F);
            }

            // Decode coordinate bits
            int coordBits = (sixBits >> 3) & 0x7;

            // Build up coordinates
            x |= (coordBits & 1) << i;
            y |= ((coordBits >> 1) & 1) << i;
            z |= ((coordBits >> 2) & 1) << i;
        }

        // Now compute the final type by following the path
        int finalType = 0; // Start at root
        for (int i = 0; i < level; i++) {
            int childIdx = ((z >> i) & 1) << 2 | ((y >> i) & 1) << 1 | ((x >> i) & 1);
            finalType = CHILD_TYPES[finalType][childIdx];
        }

        return new TetId(x, y, z, finalType, level);
    }

    /**
     * ENCODE: Build 128-bit TM-index from TetId
     */
    public static TMIndex128Bit encode(TetId tet) {
        if (tet.level > MAX_LEVELS) {
            throw new IllegalArgumentException("Level exceeds maximum: " + tet.level);
        }

        // Compute type sequence once
        int[] typeSequence = computeTypeSequence(tet);

        long low = 0L, high = 0L;

        // Process each level
        for (int level = 0; level < tet.level; level++) {
            // Extract coordinate bits for this level
            int xBit = (tet.x >> level) & 1;
            int yBit = (tet.y >> level) & 1;
            int zBit = (tet.z >> level) & 1;

            // Pack into 6 bits: zyx|ttt
            int coordBits = (zBit << 2) | (yBit << 1) | xBit;
            int typeBits = typeSequence[level] & 0x7;
            int sixBits = (coordBits << 3) | typeBits;

            // Store in appropriate long
            if (level < LEVELS_PER_LONG) {
                low |= ((long) sixBits) << (6 * level);
            } else {
                int highLevel = level - LEVELS_PER_LONG;
                high |= ((long) sixBits) << (6 * highLevel);
            }
        }

        return new TMIndex128Bit(low, high);
    }

    /**
     * Simple test
     */
    public static void main(String[] args) {
        System.out.println("128-bit TM-Index Test\n");

        // Test a few cases
        testRoundTrip(5, 3, 6, 3);
        testRoundTrip(123, 456, 789, 10);
        testRoundTrip(123, 456, 789, 15);
        testRoundTrip(123, 456, 789, 21);

        // Performance test
        performanceTest();
    }

    private static void performanceTest() {
        System.out.println("Performance Test");
        System.out.println("================");

        int iterations = 1000000;
        TetId[] testCases = new TetId[100];

        // Generate valid test cases
        for (int i = 0; i < testCases.length; i++) {
            int level = 5 + (i % 17);
            int maxCoord = Math.min(1000, (1 << level) - 1);
            int x = (i * 127) % maxCoord;
            int y = (i * 251) % maxCoord;
            int z = (i * 509) % maxCoord;
            testCases[i] = createWithComputedType(x, y, z, level);
        }

        // Warm up
        for (int i = 0; i < 10000; i++) {
            encode(testCases[i % testCases.length]);
        }

        // Test encoding
        long startTime = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            encode(testCases[i % testCases.length]);
        }
        long encodeTime = System.nanoTime() - startTime;

        // Test decoding
        TMIndex128Bit[] encoded = new TMIndex128Bit[testCases.length];
        for (int i = 0; i < testCases.length; i++) {
            encoded[i] = encode(testCases[i]);
        }

        startTime = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            int idx = i % encoded.length;
            decode(encoded[idx], testCases[idx].level);
        }
        long decodeTime = System.nanoTime() - startTime;

        System.out.printf("Encode: %.3f ms for %d operations (%.1f ns/op)\n", encodeTime / 1_000_000.0, iterations,
                          (double) encodeTime / iterations);
        System.out.printf("Decode: %.3f ms for %d operations (%.1f ns/op)\n", decodeTime / 1_000_000.0, iterations,
                          (double) decodeTime / iterations);
    }

    private static void testRoundTrip(int x, int y, int z, int level) {
        System.out.printf("Testing level %d with coords (%d,%d,%d)\n", level, x, y, z);

        TetId original = createWithComputedType(x, y, z, level);
        TMIndex128Bit encoded = encode(original);
        TetId decoded = decode(encoded, level);

        boolean match = (original.x == decoded.x && original.y == decoded.y && original.z == decoded.z
                         && original.type == decoded.type);

        System.out.printf("  Original: %s\n", original);
        System.out.printf("  Encoded:  %s\n", encoded);
        System.out.printf("  Decoded:  %s\n", decoded);
        System.out.printf("  Match:    %s\n\n", match ? "✓" : "✗");
    }

    // Structure for 128-bit TM-index
    public static class TMIndex128Bit {
        public final long low;   // Levels 0-9
        public final long high;  // Levels 10-20

        public TMIndex128Bit(long low, long high) {
            this.low = low;
            this.high = high;
        }

        @Override
        public String toString() {
            return String.format("TMIndex128[high=0x%016X, low=0x%016X]", high, low);
        }
    }

    // Structure for tetrahedron identification
    public static class TetId {
        public final int x, y, z;
        public final int type;
        public final int level;

        public TetId(int x, int y, int z, int type, int level) {
            // Validate inputs
            int maxCoord = (1 << level) - 1;
            if (x < 0 || x > maxCoord || y < 0 || y > maxCoord || z < 0 || z > maxCoord) {
                throw new IllegalArgumentException(
                String.format("Coordinates must be in range [0, %d] for level %d", maxCoord, level));
            }
            if (type < 0 || type > 5) {
                throw new IllegalArgumentException("Type must be in range [0, 5]");
            }

            this.x = x;
            this.y = y;
            this.z = z;
            this.type = type;
            this.level = level;
        }

        @Override
        public String toString() {
            return String.format("TetId[anchor=(%d,%d,%d), type=%d, level=%d]", x, y, z, type, level);
        }
    }
}
