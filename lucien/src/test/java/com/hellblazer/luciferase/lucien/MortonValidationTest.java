package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.geometry.MortonCurve;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import javax.vecmath.Point3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validation tests for Morton curve encoding using known test vectors
 * from published sources and reference implementations.
 * 
 * @author hal.hildebrand
 */
public class MortonValidationTest {
    
    @Test
    @DisplayName("Validate basic 3D Morton encoding against known test vectors")
    void testBasic3DMortonEncoding() {
        // Test vectors from research and reference implementations
        
        // Basic octree first level (3-bit coordinates)
        assertEquals(0, MortonCurve.encode(0, 0, 0), "Origin should encode to 0");
        assertEquals(1, MortonCurve.encode(1, 0, 0), "(1,0,0) should encode to 1");
        assertEquals(2, MortonCurve.encode(0, 1, 0), "(0,1,0) should encode to 2");
        assertEquals(4, MortonCurve.encode(0, 0, 1), "(0,0,1) should encode to 4");
        assertEquals(3, MortonCurve.encode(1, 1, 0), "(1,1,0) should encode to 3");
        assertEquals(5, MortonCurve.encode(1, 0, 1), "(1,0,1) should encode to 5");
        assertEquals(6, MortonCurve.encode(0, 1, 1), "(0,1,1) should encode to 6");
        assertEquals(7, MortonCurve.encode(1, 1, 1), "(1,1,1) should encode to 7");
        
        // Known test vector from Jeroen Baert's blog
        assertEquals(1095, MortonCurve.encode(5, 9, 1), "(5,9,1) should encode to 1095");
    }
    
    @Test
    @DisplayName("Validate bit interleaving pattern for 3D Morton codes")
    void testBitInterleavingPattern() {
        // Test the bit interleaving pattern
        // For (5,9,1) = (0101, 1001, 0001) in 4-bit binary
        // Interleaved (zyx order): 010001000111 = 1095
        
        int x = 5; // 0101
        int y = 9; // 1001  
        int z = 1; // 0001
        
        long morton = MortonCurve.encode(x, y, z);
        assertEquals(1095, morton);
        
        // Verify by manual bit checking
        // Bit positions in Morton code (0-indexed from right):
        // x bits: 0, 3, 6, 9, 12, 15, 18, 21, ...
        // y bits: 1, 4, 7, 10, 13, 16, 19, 22, ...
        // z bits: 2, 5, 8, 11, 14, 17, 20, 23, ...
        
        // For x=5 (binary 0101): bits at positions 0 and 3 should be 1
        assertTrue((morton & (1L << 0)) != 0, "Bit 0 should be 1 (x bit 0)");
        assertTrue((morton & (1L << 6)) != 0, "Bit 6 should be 1 (x bit 2)");
        
        // For y=9 (binary 1001): bits at positions 1 and 10 should be 1
        assertTrue((morton & (1L << 1)) != 0, "Bit 1 should be 1 (y bit 0)");
        assertTrue((morton & (1L << 10)) != 0, "Bit 10 should be 1 (y bit 3)");
        
        // For z=1 (binary 0001): bit at position 2 should be 1
        assertTrue((morton & (1L << 2)) != 0, "Bit 2 should be 1 (z bit 0)");
    }
    
    @Test
    @DisplayName("Validate Morton decode produces original coordinates")
    void testMortonDecodeRoundTrip() {
        // Test vectors with known Morton codes
        long[] mortonCodes = {0, 1, 2, 3, 4, 5, 6, 7, 1095};
        int[][] expectedCoords = {
            {0, 0, 0}, {1, 0, 0}, {0, 1, 0}, {1, 1, 0},
            {0, 0, 1}, {1, 0, 1}, {0, 1, 1}, {1, 1, 1},
            {5, 9, 1}
        };
        
        for (int i = 0; i < mortonCodes.length; i++) {
            int[] decoded = MortonCurve.decode(mortonCodes[i]);
            assertArrayEquals(expectedCoords[i], decoded,
                String.format("Morton %d should decode to (%d,%d,%d)",
                    mortonCodes[i], expectedCoords[i][0], 
                    expectedCoords[i][1], expectedCoords[i][2]));
        }
    }
    
    @Test
    @DisplayName("Validate 21-bit coordinate limits")
    void test21BitCoordinateLimits() {
        // Maximum 21-bit value is 2^21 - 1 = 2097151
        int maxCoord = (1 << 21) - 1; // 2097151
        
        // Test maximum valid coordinate
        assertDoesNotThrow(() -> {
            long morton = MortonCurve.encode(maxCoord, 0, 0);
            int[] decoded = MortonCurve.decode(morton);
            assertEquals(maxCoord, decoded[0]);
        });
        
        // Test that coordinates beyond 21-bit wrap around
        int beyondMax = maxCoord + 1; // 2097152
        long mortonBeyond = MortonCurve.encode(beyondMax, 0, 0);
        int[] decodedBeyond = MortonCurve.decode(mortonBeyond);
        assertEquals(0, decodedBeyond[0], "Coordinate beyond 21-bit should wrap to 0");
    }
    
    @Test
    @DisplayName("Validate spatial locality property")
    void testSpatialLocalityProperty() {
        // Morton codes should preserve spatial locality
        // Nearby points should have similar Morton codes
        
        // Test adjacent points
        long morton000 = MortonCurve.encode(10, 10, 10);
        long morton100 = MortonCurve.encode(11, 10, 10);
        long morton010 = MortonCurve.encode(10, 11, 10);
        long morton001 = MortonCurve.encode(10, 10, 11);
        
        // Adjacent points should have Morton codes that differ by small amounts
        // Due to bit interleaving, +1 in x,y,z adds 1,2,4 respectively to Morton
        assertEquals(1, morton100 - morton000, "X+1 should add 1 to Morton code");
        assertEquals(2, morton010 - morton000, "Y+1 should add 2 to Morton code");
        assertEquals(4, morton001 - morton000, "Z+1 should add 4 to Morton code");
    }
    
    @Test
    @DisplayName("Validate level determination consistency")
    void testLevelDeterminationConsistency() {
        // Test that toLevel produces consistent results
        
        // Level should be coarser (smaller number) for larger coordinates
        byte levelSmall = Constants.toLevel(MortonCurve.encode(1, 1, 1));
        byte levelMedium = Constants.toLevel(MortonCurve.encode(100, 100, 100));
        byte levelLarge = Constants.toLevel(MortonCurve.encode(10000, 10000, 10000));
        
        assertTrue(levelLarge <= levelMedium, "Larger coordinates should have coarser level");
        assertTrue(levelMedium <= levelSmall, "Medium coordinates should have level between large and small");
        
        // Specific test cases based on implementation
        assertEquals(0, Constants.toLevel(0), "Morton 0 should be level 0 (root)");
        assertEquals(21, Constants.toLevel(MortonCurve.encode(1, 1, 1)), 
            "Small coordinates should map to finest level");
    }
    
    @Test
    @DisplayName("Validate calculateMortonIndex quantization behavior")
    void testCalculateMortonIndexQuantization() {
        // Test that calculateMortonIndex properly quantizes coordinates
        
        for (byte level = 10; level <= 20; level++) {
            int length = Constants.lengthAtLevel(level);
            
            // Points within same cell should produce same Morton index
            Point3f cellOrigin = new Point3f(length * 5, length * 3, length * 7);
            Point3f cellInterior = new Point3f(
                length * 5 + length/2.0f,
                length * 3 + length/2.0f, 
                length * 7 + length/2.0f
            );
            
            long mortonOrigin = Constants.calculateMortonIndex(cellOrigin, level);
            long mortonInterior = Constants.calculateMortonIndex(cellInterior, level);
            
            assertEquals(mortonOrigin, mortonInterior,
                String.format("Points within same cell at level %d should have same Morton index", level));
            
            // Verify the Morton index represents quantized world coordinates
            int[] decoded = MortonCurve.decode(mortonOrigin);
            assertEquals(length * 5, decoded[0], "Decoded X should be quantized to cell origin");
            assertEquals(length * 3, decoded[1], "Decoded Y should be quantized to cell origin");
            assertEquals(length * 7, decoded[2], "Decoded Z should be quantized to cell origin");
        }
    }
    
    @Test
    @DisplayName("Cross-validate with reference implementation patterns")
    void testReferenceImplementationPatterns() {
        // Test patterns from libmorton and other reference implementations
        
        // Test power-of-2 coordinates
        for (int power = 0; power <= 10; power++) {
            int coord = 1 << power;
            long mortonX = MortonCurve.encode(coord, 0, 0);
            long mortonY = MortonCurve.encode(0, coord, 0);
            long mortonZ = MortonCurve.encode(0, 0, coord);
            
            // Verify bit patterns
            // For power-of-2 in X: only one bit set at position 3*power
            // For power-of-2 in Y: only one bit set at position 3*power + 1
            // For power-of-2 in Z: only one bit set at position 3*power + 2
            assertEquals(1L << (3 * power), mortonX,
                String.format("2^%d in X should set bit at position %d", power, 3*power));
            assertEquals(1L << (3 * power + 1), mortonY,
                String.format("2^%d in Y should set bit at position %d", power, 3*power + 1));
            assertEquals(1L << (3 * power + 2), mortonZ,
                String.format("2^%d in Z should set bit at position %d", power, 3*power + 2));
        }
    }
}