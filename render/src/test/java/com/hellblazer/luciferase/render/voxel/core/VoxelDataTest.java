package com.hellblazer.luciferase.render.voxel.core;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.vecmath.Vector3f;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Comprehensive test suite for VoxelData implementation.
 * Tests cover attribute encoding/decoding, DXT compression preparation,
 * normal compression, interpolation, and thread safety.
 */
@DisplayName("VoxelData Tests")
public class VoxelDataTest {
    
    private static final Logger log = LoggerFactory.getLogger(VoxelDataTest.class);
    
    private VoxelData voxelData;
    private Arena testArena;
    
    @BeforeEach
    void setUp() {
        voxelData = new VoxelData();
        testArena = Arena.ofConfined();
    }
    
    @AfterEach
    void tearDown() {
        if (testArena != null) {
            testArena.close();
        }
    }
    
    // ================================================================================
    // Constructor and Basic Setup Tests
    // ================================================================================
    
    @Test
    @DisplayName("Default constructor creates empty voxel")
    void testDefaultConstructor() {
        VoxelData emptyVoxel = new VoxelData();
        
        assertTrue(emptyVoxel.isEmpty());
        assertEquals(0, emptyVoxel.getRed());
        assertEquals(0, emptyVoxel.getGreen());
        assertEquals(0, emptyVoxel.getBlue());
        assertEquals(0, emptyVoxel.getOpacity());
        assertEquals(0, emptyVoxel.getMaterialId());
        assertEquals(0, emptyVoxel.getPackedData());
        assertFalse(emptyVoxel.hasColor());
        assertTrue(emptyVoxel.isTransparent());
        assertFalse(emptyVoxel.isOpaque());
    }
    
    @Test
    @DisplayName("Packed data constructor preserves values")
    void testPackedDataConstructor() {
        long packedData = 0x123456789ABCDEFL;
        VoxelData packedVoxel = new VoxelData(packedData);
        
        assertEquals(packedData, packedVoxel.getPackedData());
        assertFalse(packedVoxel.isEmpty());
    }
    
    @Test
    @DisplayName("RGB constructor sets color correctly")
    void testRGBConstructor() {
        VoxelData colorVoxel = new VoxelData(255, 128, 64);
        
        assertEquals(255, colorVoxel.getRed());
        assertEquals(128, colorVoxel.getGreen());
        assertEquals(64, colorVoxel.getBlue());
        assertEquals(255, colorVoxel.getOpacity()); // Default opaque
        assertEquals(0, colorVoxel.getMaterialId()); // Default material
        assertFalse(colorVoxel.isEmpty());
        assertTrue(colorVoxel.hasColor());
        assertTrue(colorVoxel.isOpaque());
    }
    
    @Test
    @DisplayName("RGBA + material constructor works correctly")
    void testRGBAMaterialConstructor() {
        VoxelData voxel = new VoxelData(200, 150, 100, 128, 5);
        
        assertEquals(200, voxel.getRed());
        assertEquals(150, voxel.getGreen());
        assertEquals(100, voxel.getBlue());
        assertEquals(128, voxel.getOpacity());
        assertEquals(5, voxel.getMaterialId());
        assertTrue(voxel.hasColor());
        assertTrue(voxel.isTransparent());
        assertFalse(voxel.isOpaque());
    }
    
    @Test
    @DisplayName("Full constructor with normal works correctly")
    void testFullConstructor() {
        Vector3f normal = new Vector3f(0.707f, 0.707f, 0.0f);
        VoxelData voxel = new VoxelData(255, 0, 0, normal, 200, 10);
        
        assertEquals(255, voxel.getRed());
        assertEquals(0, voxel.getGreen());
        assertEquals(0, voxel.getBlue());
        assertEquals(200, voxel.getOpacity());
        assertEquals(10, voxel.getMaterialId());
        
        Vector3f retrievedNormal = voxel.getNormal();
        assertEquals(normal.x, retrievedNormal.x, 0.01f);
        assertEquals(normal.y, retrievedNormal.y, 0.01f);
        assertEquals(normal.z, retrievedNormal.z, 0.01f);
    }
    
    @Test
    @DisplayName("Memory segment constructor reads data correctly")
    void testMemorySegmentConstructor() {
        MemorySegment segment = testArena.allocate(VoxelData.VOXEL_DATA_SIZE_BYTES);
        long testData = 0xFEDCBA9876543210L;
        segment.set(ValueLayout.JAVA_LONG, 0, testData);
        
        VoxelData segmentVoxel = new VoxelData(segment, 0);
        
        assertEquals(testData, segmentVoxel.getPackedData());
        assertNotNull(segmentVoxel.getNativeSegment());
        assertEquals(VoxelData.VOXEL_DATA_SIZE_BYTES, segmentVoxel.getNativeSegment().byteSize());
    }
    
    @Test
    @DisplayName("Constructor validation works correctly")
    void testConstructorValidation() {
        // Test invalid color components
        assertThrows(IllegalArgumentException.class, () -> new VoxelData(-1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new VoxelData(0, 256, 0));
        assertThrows(IllegalArgumentException.class, () -> new VoxelData(0, 0, -1));
        
        // Test invalid opacity and material
        assertThrows(IllegalArgumentException.class, () -> new VoxelData(0, 0, 0, 256, 0));
        assertThrows(IllegalArgumentException.class, () -> new VoxelData(0, 0, 0, 0, -1));
        
        // Test null normal
        assertThrows(IllegalArgumentException.class, () -> 
            new VoxelData(0, 0, 0, null, 255, 0));
        
        // Test invalid memory segment
        assertThrows(IllegalArgumentException.class, () -> new VoxelData(null, 0));
        
        MemorySegment tinySegment = testArena.allocate(4);
        assertThrows(IllegalArgumentException.class, () -> new VoxelData(tinySegment, 0));
    }
    
    // ================================================================================
    // Attribute Encoding/Decoding Tests
    // ================================================================================
    
    @Test
    @DisplayName("Color encoding and decoding works correctly")
    void testColorEncodingDecoding() {
        var testColors = new int[][] {
            {0, 0, 0},
            {255, 255, 255},
            {255, 0, 0},
            {0, 255, 0},
            {0, 0, 255},
            {128, 128, 128},
            {200, 150, 100},
            {32, 64, 96}
        };
        
        for (var color : testColors) {
            voxelData.setColor(color[0], color[1], color[2]);
            
            assertEquals(color[0], voxelData.getRed());
            assertEquals(color[1], voxelData.getGreen());
            assertEquals(color[2], voxelData.getBlue());
            
            // Test individual component setting
            voxelData.setRed(color[0] ^ 0xFF);
            assertEquals(color[0] ^ 0xFF, voxelData.getRed());
            assertEquals(color[1], voxelData.getGreen()); // Should be unchanged
            assertEquals(color[2], voxelData.getBlue());   // Should be unchanged
            
            // Restore and test packed color
            voxelData.setColor(color[0], color[1], color[2]);
            int expectedPacked = (color[0] << 16) | (color[1] << 8) | color[2];
            assertEquals(expectedPacked, voxelData.getPackedColor());
        }
    }
    
    @Test
    @DisplayName("Packed color operations work correctly")
    void testPackedColorOperations() {
        var testPackedColors = new int[] {
            0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF,
            0x808080, 0xC89664, 0x204060, 0x123456, 0xABCDEF
        };
        
        for (int packedColor : testPackedColors) {
            voxelData.setPackedColor(packedColor);
            
            int expectedRed = (packedColor >>> 16) & 0xFF;
            int expectedGreen = (packedColor >>> 8) & 0xFF;
            int expectedBlue = packedColor & 0xFF;
            
            assertEquals(expectedRed, voxelData.getRed());
            assertEquals(expectedGreen, voxelData.getGreen());
            assertEquals(expectedBlue, voxelData.getBlue());
            assertEquals(packedColor, voxelData.getPackedColor());
        }
    }
    
    @Test
    @DisplayName("Opacity encoding and decoding works correctly")
    void testOpacityEncodingDecoding() {
        var testOpacities = new int[] {0, 64, 128, 192, 255};
        
        for (int opacity : testOpacities) {
            voxelData.setOpacity(opacity);
            
            assertEquals(opacity, voxelData.getOpacity());
            assertEquals(opacity / 255.0f, voxelData.getOpacityFloat(), 0.01f);
            assertEquals(opacity == 255, voxelData.isOpaque());
            assertEquals(opacity < 255, voxelData.isTransparent());
        }
        
        // Test float opacity setting
        var testFloatOpacities = new float[] {0.0f, 0.25f, 0.5f, 0.75f, 1.0f};
        
        for (float floatOpacity : testFloatOpacities) {
            voxelData.setOpacityFloat(floatOpacity);
            
            int expectedIntOpacity = (int) (floatOpacity * 255.0f);
            assertEquals(expectedIntOpacity, voxelData.getOpacity());
            assertEquals(floatOpacity, voxelData.getOpacityFloat(), 0.01f);
        }
    }
    
    @Test
    @DisplayName("Material ID encoding and decoding works correctly")
    void testMaterialIdEncodingDecoding() {
        var testMaterialIds = new int[] {0, 1, 127, 128, 255};
        
        for (int materialId : testMaterialIds) {
            voxelData.setMaterialId(materialId);
            assertEquals(materialId, voxelData.getMaterialId());
        }
    }
    
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7})
    @DisplayName("Individual component validation works for all values")
    void testComponentValidation(int componentIndex) {
        // Test valid range (0-255)
        for (int value = 0; value <= 255; value += 32) {
            final int finalValue = value; // Make effectively final for lambda
            switch (componentIndex) {
                case 0: assertDoesNotThrow(() -> voxelData.setRed(finalValue)); break;
                case 1: assertDoesNotThrow(() -> voxelData.setGreen(finalValue)); break;
                case 2: assertDoesNotThrow(() -> voxelData.setBlue(finalValue)); break;
                case 3: assertDoesNotThrow(() -> voxelData.setOpacity(finalValue)); break;
                case 4: assertDoesNotThrow(() -> voxelData.setMaterialId(finalValue)); break;
            }
        }
        
        // Test invalid values
        var invalidValues = new int[] {-1, 256, 1000, Integer.MAX_VALUE, Integer.MIN_VALUE};
        
        for (int invalidValue : invalidValues) {
            switch (componentIndex) {
                case 0: assertThrows(IllegalArgumentException.class, () -> voxelData.setRed(invalidValue)); break;
                case 1: assertThrows(IllegalArgumentException.class, () -> voxelData.setGreen(invalidValue)); break;
                case 2: assertThrows(IllegalArgumentException.class, () -> voxelData.setBlue(invalidValue)); break;
                case 3: assertThrows(IllegalArgumentException.class, () -> voxelData.setOpacity(invalidValue)); break;
                case 4: assertThrows(IllegalArgumentException.class, () -> voxelData.setMaterialId(invalidValue)); break;
            }
        }
    }
    
    // ================================================================================
    // Normal Compression Tests
    // ================================================================================
    
    @Test
    @DisplayName("Normal compression and decompression works correctly")
    void testNormalCompressionDecompression() {
        var testNormals = new Vector3f[] {
            new Vector3f(0.0f, 0.0f, 1.0f),    // Positive Z
            new Vector3f(0.0f, 0.0f, -1.0f),   // Negative Z
            new Vector3f(1.0f, 0.0f, 0.0f),    // Positive X
            new Vector3f(-1.0f, 0.0f, 0.0f),   // Negative X
            new Vector3f(0.0f, 1.0f, 0.0f),    // Positive Y
            new Vector3f(0.0f, -1.0f, 0.0f),   // Negative Y
            new Vector3f(0.707f, 0.707f, 0.0f), // Diagonal
            new Vector3f(-0.577f, -0.577f, -0.577f), // Negative diagonal
            new Vector3f(0.0f, 0.0f, 0.0f),    // Zero vector
        };
        
        for (Vector3f normal : testNormals) {
            voxelData.setNormal(normal);
            
            Vector3f retrieved = voxelData.getNormal();
            
            // Allow for compression error (8-bit quantization)
            float tolerance = 1.0f / 127.5f; // Maximum quantization error
            assertEquals(normal.x, retrieved.x, tolerance * 2, 
                        "Normal X component should be preserved within tolerance");
            assertEquals(normal.y, retrieved.y, tolerance * 2, 
                        "Normal Y component should be preserved within tolerance");
            assertEquals(normal.z, retrieved.z, tolerance * 2, 
                        "Normal Z component should be preserved within tolerance");
        }
    }
    
    @Test
    @DisplayName("Individual normal component operations work correctly")
    void testIndividualNormalComponents() {
        voxelData.setNormal(0.5f, -0.3f, 0.8f);
        
        // Verify individual compressed components
        int normalXCompressed = voxelData.getNormalX();
        int normalYCompressed = voxelData.getNormalY();
        int normalZCompressed = voxelData.getNormalZ();
        
        assertTrue(normalXCompressed >= 0 && normalXCompressed <= 255);
        assertTrue(normalYCompressed >= 0 && normalYCompressed <= 255);
        assertTrue(normalZCompressed >= 0 && normalZCompressed <= 255);
        
        // Verify reconstruction
        Vector3f reconstructed = voxelData.getNormal();
        assertEquals(0.5f, reconstructed.x, 0.02f);
        assertEquals(-0.3f, reconstructed.y, 0.02f);
        assertEquals(0.8f, reconstructed.z, 0.02f);
    }
    
    @Test
    @DisplayName("Normal compression preserves direction")
    void testNormalCompressionPreservesDirection() {
        var directions = new Vector3f[] {
            new Vector3f(1.0f, 0.0f, 0.0f),
            new Vector3f(0.0f, 1.0f, 0.0f),
            new Vector3f(0.0f, 0.0f, 1.0f),
            new Vector3f(-1.0f, 0.0f, 0.0f),
            new Vector3f(0.0f, -1.0f, 0.0f),
            new Vector3f(0.0f, 0.0f, -1.0f)
        };
        
        for (Vector3f direction : directions) {
            voxelData.setNormal(direction);
            Vector3f retrieved = voxelData.getNormal();
            
            // Check that the sign is preserved, allowing for epsilon differences near zero
            // When original component is 0.0, allow retrieved to be small positive or negative
            float epsilon = 1.0f / 127.5f; // Maximum quantization error
            
            assertSignPreserved(direction.x, retrieved.x, epsilon, 
                        "Direction X sign should be preserved");
            assertSignPreserved(direction.y, retrieved.y, epsilon, 
                        "Direction Y sign should be preserved");
            assertSignPreserved(direction.z, retrieved.z, epsilon, 
                        "Direction Z sign should be preserved");
        }
    }
    
    /**
     * Helper method to check if sign is preserved, accounting for quantization near zero
     */
    private void assertSignPreserved(float original, float retrieved, float epsilon, String message) {
        if (Math.abs(original) < epsilon) {
            // Original is effectively zero, retrieved can be small positive or negative
            assertTrue(Math.abs(retrieved) <= epsilon * 2, 
                      message + " (zero component should remain near zero)");
        } else {
            // Original is non-zero, sign should be preserved
            assertEquals(Math.signum(original), Math.signum(retrieved), message);
        }
    }
    
    @Test
    @DisplayName("Normal validation works correctly")
    void testNormalValidation() {
        // Test null normal
        assertThrows(IllegalArgumentException.class, () -> voxelData.setNormal(null));
        
        // Test extreme values (should be clamped)
        voxelData.setNormal(10.0f, -10.0f, 5.0f);
        Vector3f clamped = voxelData.getNormal();
        
        assertTrue(clamped.x >= -1.0f && clamped.x <= 1.0f);
        assertTrue(clamped.y >= -1.0f && clamped.y <= 1.0f);
        assertTrue(clamped.z >= -1.0f && clamped.z <= 1.0f);
    }
    
    // ================================================================================
    // DXT Compression Preparation Tests
    // ================================================================================
    
    @Test
    @DisplayName("DXT color data extraction works correctly")
    void testDXTColorDataExtraction() {
        voxelData.setColor(255, 128, 64);
        
        int[] dxtColorData = voxelData.getDXTColorData();
        
        assertEquals(3, dxtColorData.length);
        assertEquals(255, dxtColorData[0]); // Red
        assertEquals(128, dxtColorData[1]); // Green
        assertEquals(64, dxtColorData[2]);  // Blue
    }
    
    @Test
    @DisplayName("DXT5 color data extraction includes alpha")
    void testDXT5ColorDataExtraction() {
        voxelData.setColor(200, 150, 100);
        voxelData.setOpacity(180);
        
        int[] dxt5ColorData = voxelData.getDXT5ColorData();
        
        assertEquals(4, dxt5ColorData.length);
        assertEquals(200, dxt5ColorData[0]); // Red
        assertEquals(150, dxt5ColorData[1]); // Green
        assertEquals(100, dxt5ColorData[2]); // Blue
        assertEquals(180, dxt5ColorData[3]); // Alpha
    }
    
    @Test
    @DisplayName("RGB565 conversion for DXT1 works correctly")
    void testRGB565Conversion() {
        var testColors = new int[][] {
            {0, 0, 0},       // Black
            {255, 255, 255}, // White
            {255, 0, 0},     // Pure red
            {0, 255, 0},     // Pure green
            {0, 0, 255},     // Pure blue
            {128, 128, 128}, // Gray
        };
        
        for (var color : testColors) {
            voxelData.setColor(color[0], color[1], color[2]);
            short rgb565 = voxelData.getDXTRGB565();
            
            // Extract components from RGB565
            int r5 = (rgb565 >>> 11) & 0x1F;
            int g6 = (rgb565 >>> 5) & 0x3F;
            int b5 = rgb565 & 0x1F;
            
            // Convert back to 8-bit for comparison
            int red8 = (r5 * 255) / 31;
            int green8 = (g6 * 255) / 63;
            int blue8 = (b5 * 255) / 31;
            
            // Allow for quantization error
            assertTrue(Math.abs(color[0] - red8) <= 8, 
                      "Red component should be close after RGB565 conversion");
            assertTrue(Math.abs(color[1] - green8) <= 4, 
                      "Green component should be close after RGB565 conversion");
            assertTrue(Math.abs(color[2] - blue8) <= 8, 
                      "Blue component should be close after RGB565 conversion");
        }
    }
    
    @Test
    @DisplayName("DXT compression data is consistent")
    void testDXTCompressionDataConsistency() {
        voxelData.setColor(123, 234, 56);
        voxelData.setOpacity(200);
        
        int[] dxtData = voxelData.getDXTColorData();
        int[] dxt5Data = voxelData.getDXT5ColorData();
        
        // First three components should match
        assertEquals(dxtData[0], dxt5Data[0]);
        assertEquals(dxtData[1], dxt5Data[1]);
        assertEquals(dxtData[2], dxt5Data[2]);
        
        // DXT5 should have alpha component
        assertEquals(200, dxt5Data[3]);
        
        // Packed color should match DXT components
        int packedColor = voxelData.getPackedColor();
        assertEquals(dxtData[0], (packedColor >>> 16) & 0xFF);
        assertEquals(dxtData[1], (packedColor >>> 8) & 0xFF);
        assertEquals(dxtData[2], packedColor & 0xFF);
    }
    
    // ================================================================================
    // Interpolation Tests
    // ================================================================================
    
    @Test
    @DisplayName("Color interpolation works correctly")
    void testColorInterpolation() {
        VoxelData voxel1 = new VoxelData(255, 0, 0);   // Pure red
        VoxelData voxel2 = new VoxelData(0, 255, 0);   // Pure green
        
        // Test various interpolation points
        var testPoints = new float[] {0.0f, 0.25f, 0.5f, 0.75f, 1.0f};
        
        for (float t : testPoints) {
            VoxelData interpolated = voxel1.interpolate(voxel2, t);
            
            int expectedRed = (int) (255 * (1.0f - t));
            int expectedGreen = (int) (255 * t);
            int expectedBlue = 0;
            
            assertEquals(expectedRed, interpolated.getRed(), 1, 
                        "Red component should interpolate correctly");
            assertEquals(expectedGreen, interpolated.getGreen(), 1, 
                        "Green component should interpolate correctly");
            assertEquals(expectedBlue, interpolated.getBlue(), 
                        "Blue component should remain zero");
        }
    }
    
    @Test
    @DisplayName("Opacity interpolation works correctly")
    void testOpacityInterpolation() {
        VoxelData transparent = new VoxelData(100, 100, 100, 0, 0);   // Transparent
        VoxelData opaque = new VoxelData(100, 100, 100, 255, 0);      // Opaque
        
        VoxelData halfWay = transparent.interpolate(opaque, 0.5f);
        
        assertEquals(127, halfWay.getOpacity(), 1, "Opacity should interpolate to middle value");
        assertEquals(100, halfWay.getRed(), "Color should remain unchanged");
        assertEquals(100, halfWay.getGreen(), "Color should remain unchanged");
        assertEquals(100, halfWay.getBlue(), "Color should remain unchanged");
    }
    
    @Test
    @DisplayName("Normal interpolation works correctly")
    void testNormalInterpolation() {
        Vector3f normal1 = new Vector3f(1.0f, 0.0f, 0.0f);
        Vector3f normal2 = new Vector3f(0.0f, 1.0f, 0.0f);
        
        VoxelData voxel1 = new VoxelData(255, 255, 255, normal1, 255, 0);
        VoxelData voxel2 = new VoxelData(255, 255, 255, normal2, 255, 0);
        
        VoxelData interpolated = voxel1.interpolate(voxel2, 0.5f);
        Vector3f interpNormal = interpolated.getNormal();
        
        // After normalization, should be approximately (0.707, 0.707, 0)
        assertEquals(0.707f, interpNormal.x, 0.1f, "Interpolated normal X should be correct");
        assertEquals(0.707f, interpNormal.y, 0.1f, "Interpolated normal Y should be correct");
        assertEquals(0.0f, interpNormal.z, 0.1f, "Interpolated normal Z should be correct");
        
        // Length should be approximately 1 (normalized)
        float length = (float) Math.sqrt(interpNormal.x * interpNormal.x + 
                                       interpNormal.y * interpNormal.y + 
                                       interpNormal.z * interpNormal.z);
        assertEquals(1.0f, length, 0.1f, "Interpolated normal should be normalized");
    }
    
    @Test
    @DisplayName("Material ID interpolation uses nearest neighbor")
    void testMaterialIdInterpolation() {
        VoxelData voxel1 = new VoxelData(0, 0, 0, 255, 10);
        VoxelData voxel2 = new VoxelData(0, 0, 0, 255, 20);
        
        // t < 0.5 should use first material
        VoxelData interp1 = voxel1.interpolate(voxel2, 0.25f);
        assertEquals(10, interp1.getMaterialId());
        
        // t >= 0.5 should use second material
        VoxelData interp2 = voxel1.interpolate(voxel2, 0.75f);
        assertEquals(20, interp2.getMaterialId());
        
        // Exactly 0.5 should use second material
        VoxelData interp3 = voxel1.interpolate(voxel2, 0.5f);
        assertEquals(20, interp3.getMaterialId());
    }
    
    @Test
    @DisplayName("Interpolation validation works correctly")
    void testInterpolationValidation() {
        VoxelData other = new VoxelData(255, 255, 255);
        
        // Test null other voxel
        assertThrows(IllegalArgumentException.class, () -> voxelData.interpolate(null, 0.5f));
        
        // Test invalid t values
        assertThrows(IllegalArgumentException.class, () -> voxelData.interpolate(other, -0.1f));
        assertThrows(IllegalArgumentException.class, () -> voxelData.interpolate(other, 1.1f));
        
        // Test valid boundary values
        assertDoesNotThrow(() -> voxelData.interpolate(other, 0.0f));
        assertDoesNotThrow(() -> voxelData.interpolate(other, 1.0f));
    }
    
    // ================================================================================
    // Serialization and Memory Operations Tests
    // ================================================================================
    
    @Test
    @DisplayName("Packed data serialization works correctly")
    void testPackedDataSerialization() {
        // Set up complex voxel state
        voxelData.setColor(200, 150, 100);
        voxelData.setNormal(0.6f, 0.8f, 0.0f);
        voxelData.setOpacity(180);
        voxelData.setMaterialId(25);
        
        long packedData = voxelData.getPackedData();
        assertNotEquals(0L, packedData);
        
        // Create new voxel from packed data
        VoxelData newVoxel = new VoxelData(packedData);
        
        assertEquals(voxelData.getRed(), newVoxel.getRed());
        assertEquals(voxelData.getGreen(), newVoxel.getGreen());
        assertEquals(voxelData.getBlue(), newVoxel.getBlue());
        assertEquals(voxelData.getNormalX(), newVoxel.getNormalX());
        assertEquals(voxelData.getNormalY(), newVoxel.getNormalY());
        assertEquals(voxelData.getNormalZ(), newVoxel.getNormalZ());
        assertEquals(voxelData.getOpacity(), newVoxel.getOpacity());
        assertEquals(voxelData.getMaterialId(), newVoxel.getMaterialId());
    }
    
    @Test
    @DisplayName("Memory segment serialization works correctly")
    void testMemorySegmentSerialization() {
        // Set up voxel with known data
        voxelData.setColor(123, 234, 45);
        voxelData.setNormal(-0.5f, 0.3f, 0.8f);
        voxelData.setOpacity(200);
        voxelData.setMaterialId(15);
        
        MemorySegment segment = testArena.allocate(VoxelData.VOXEL_DATA_SIZE_BYTES);
        voxelData.serializeTo(segment, 0);
        
        // Verify data was written correctly
        long writtenData = segment.get(ValueLayout.JAVA_LONG, 0);
        assertEquals(voxelData.getPackedData(), writtenData);
        
        // Deserialize and verify
        VoxelData deserializedVoxel = new VoxelData();
        deserializedVoxel.deserializeFrom(segment, 0);
        
        assertEquals(voxelData.getPackedData(), deserializedVoxel.getPackedData());
        assertEquals(voxelData, deserializedVoxel);
    }
    
    @Test
    @DisplayName("Native memory creation works correctly")
    void testNativeMemoryCreation() {
        voxelData.setColor(255, 128, 64);
        voxelData.setOpacity(200);
        voxelData.setMaterialId(10);
        
        MemorySegment nativeSegment = voxelData.toNativeMemory(testArena);
        
        assertNotNull(nativeSegment);
        assertEquals(VoxelData.VOXEL_DATA_SIZE_BYTES, nativeSegment.byteSize());
        
        // Verify data is correct in native memory
        long nativeData = nativeSegment.get(ValueLayout.JAVA_LONG, 0);
        assertEquals(voxelData.getPackedData(), nativeData);
    }
    
    @Test
    @DisplayName("Serialization validation works correctly")
    void testSerializationValidation() {
        // Test null segment for serialization
        assertThrows(IllegalArgumentException.class, () -> voxelData.serializeTo(null, 0));
        
        // Test segment too small for serialization
        MemorySegment tinySegment = testArena.allocate(4);
        assertThrows(IllegalArgumentException.class, () -> voxelData.serializeTo(tinySegment, 0));
        
        // Test null segment for deserialization
        assertThrows(IllegalArgumentException.class, () -> voxelData.deserializeFrom(null, 0));
        
        // Test segment too small for deserialization
        assertThrows(IllegalArgumentException.class, () -> voxelData.deserializeFrom(tinySegment, 0));
    }
    
    // ================================================================================
    // Utility Methods Tests
    // ================================================================================
    
    @Test
    @DisplayName("Copy operation works correctly")
    void testCopyOperation() {
        // Set up original voxel
        voxelData.setColor(255, 128, 64);
        voxelData.setNormal(0.707f, 0.707f, 0.0f);
        voxelData.setOpacity(180);
        voxelData.setMaterialId(5);
        
        VoxelData copy = voxelData.copy();
        
        assertNotSame(voxelData, copy);
        assertEquals(voxelData.getPackedData(), copy.getPackedData());
        assertEquals(voxelData, copy);
    }
    
    @Test
    @DisplayName("Clear operation works correctly")
    void testClearOperation() {
        // Set up voxel with data
        voxelData.setColor(255, 255, 255);
        voxelData.setNormal(1.0f, 0.0f, 0.0f);
        voxelData.setOpacity(255);
        voxelData.setMaterialId(255);
        
        assertFalse(voxelData.isEmpty());
        assertTrue(voxelData.hasColor());
        
        voxelData.clear();
        
        assertTrue(voxelData.isEmpty());
        assertFalse(voxelData.hasColor());
        assertEquals(0, voxelData.getPackedData());
        assertEquals(0, voxelData.getRed());
        assertEquals(0, voxelData.getGreen());
        assertEquals(0, voxelData.getBlue());
        assertEquals(0, voxelData.getOpacity());
        assertEquals(0, voxelData.getMaterialId());
    }
    
    @Test
    @DisplayName("State query methods work correctly")
    void testStateQueryMethods() {
        // Test empty voxel
        assertTrue(voxelData.isEmpty());
        assertFalse(voxelData.hasColor());
        assertTrue(voxelData.isTransparent());
        assertFalse(voxelData.isOpaque());
        
        // Test voxel with color but no opacity
        voxelData.setColor(100, 100, 100);
        assertFalse(voxelData.isEmpty());
        assertTrue(voxelData.hasColor());
        assertTrue(voxelData.isTransparent()); // Default opacity is 0
        assertFalse(voxelData.isOpaque());
        
        // Test fully opaque voxel
        voxelData.setOpacity(255);
        assertFalse(voxelData.isEmpty());
        assertTrue(voxelData.hasColor());
        assertFalse(voxelData.isTransparent());
        assertTrue(voxelData.isOpaque());
        
        // Test transparent voxel with color
        voxelData.setOpacity(128);
        assertFalse(voxelData.isEmpty());
        assertTrue(voxelData.hasColor());
        assertTrue(voxelData.isTransparent());
        assertFalse(voxelData.isOpaque());
    }
    
    // ================================================================================
    // Object Override Methods Tests
    // ================================================================================
    
    @Test
    @DisplayName("Equals and hashCode work correctly")
    void testEqualsAndHashCode() {
        VoxelData voxel1 = new VoxelData();
        VoxelData voxel2 = new VoxelData();
        VoxelData voxel3 = new VoxelData(255, 0, 0);
        
        // Test reflexivity
        assertEquals(voxel1, voxel1);
        
        // Test symmetry
        assertEquals(voxel1, voxel2);
        assertEquals(voxel2, voxel1);
        
        // Test consistency
        assertEquals(voxel1, voxel2);
        assertEquals(voxel1, voxel2);
        
        // Test null comparison
        assertNotEquals(voxel1, null);
        
        // Test different class
        assertNotEquals(voxel1, "string");
        
        // Test different data
        assertNotEquals(voxel1, voxel3);
        
        // Test hash codes
        assertEquals(voxel1.hashCode(), voxel2.hashCode());
    }
    
    @Test
    @DisplayName("ToString provides useful information")
    void testToString() {
        voxelData.setColor(255, 128, 64);
        voxelData.setNormal(0.5f, -0.3f, 0.8f);
        voxelData.setOpacity(200);
        voxelData.setMaterialId(10);
        
        String str = voxelData.toString();
        
        assertNotNull(str);
        assertTrue(str.contains("VoxelData"));
        assertTrue(str.contains("color"));
        assertTrue(str.contains("normal"));
        assertTrue(str.contains("opacity"));
        assertTrue(str.contains("material"));
        assertTrue(str.contains("packed"));
    }
    
    // ================================================================================
    // Thread Safety Tests
    // ================================================================================
    
    @Test
    @Timeout(30)
    @DisplayName("Concurrent operations are thread-safe")
    void testThreadSafety() throws InterruptedException {
        final int numThreads = 8;
        final int operationsPerThread = 1000;
        
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CompletableFuture<Void>[] futures = new CompletableFuture[numThreads];
        
        // Create tasks that perform various operations concurrently
        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            futures[i] = CompletableFuture.runAsync(() -> {
                ThreadLocalRandom random = ThreadLocalRandom.current();
                
                for (int j = 0; j < operationsPerThread; j++) {
                    // Perform various operations based on thread ID and iteration
                    switch ((threadId + j) % 8) {
                        case 0:
                            voxelData.setRed(random.nextInt(256));
                            break;
                        case 1:
                            voxelData.setGreen(random.nextInt(256));
                            break;
                        case 2:
                            voxelData.setBlue(random.nextInt(256));
                            break;
                        case 3:
                            voxelData.setOpacity(random.nextInt(256));
                            break;
                        case 4:
                            voxelData.setMaterialId(random.nextInt(256));
                            break;
                        case 5:
                            float x = random.nextFloat() * 2.0f - 1.0f;
                            float y = random.nextFloat() * 2.0f - 1.0f;
                            float z = random.nextFloat() * 2.0f - 1.0f;
                            voxelData.setNormal(x, y, z);
                            break;
                        case 6:
                            voxelData.setColor(random.nextInt(256), random.nextInt(256), random.nextInt(256));
                            break;
                        case 7:
                            voxelData.setPackedData(random.nextLong());
                            break;
                    }
                    
                    // Read operations
                    voxelData.getRed();
                    voxelData.getGreen();
                    voxelData.getBlue();
                    voxelData.getOpacity();
                    voxelData.getMaterialId();
                    voxelData.getNormal();
                    voxelData.getPackedData();
                }
            }, executor);
        }
        
        // Wait for all tasks to complete
        CompletableFuture.allOf(futures).join();
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        
        // Verify that the voxel is in a consistent state
        long finalData = voxelData.getPackedData();
        VoxelData verificationVoxel = new VoxelData(finalData);
        assertEquals(voxelData, verificationVoxel);
    }
    
    @Test
    @Timeout(30)
    @DisplayName("Concurrent interpolation operations")
    void testConcurrentInterpolation() throws InterruptedException {
        final int numThreads = 6;
        final int operationsPerThread = 500;
        
        // Create source voxels for interpolation
        VoxelData[] sourceVoxels = new VoxelData[10];
        for (int i = 0; i < sourceVoxels.length; i++) {
            sourceVoxels[i] = new VoxelData(
                ThreadLocalRandom.current().nextInt(256),
                ThreadLocalRandom.current().nextInt(256),
                ThreadLocalRandom.current().nextInt(256),
                ThreadLocalRandom.current().nextInt(256),
                ThreadLocalRandom.current().nextInt(256)
            );
        }
        
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        var exceptions = Collections.synchronizedList(new ArrayList<Throwable>());
        var operationCount = new AtomicLong(0);
        
        CompletableFuture<Void>[] futures = new CompletableFuture[numThreads];
        
        for (int i = 0; i < numThreads; i++) {
            futures[i] = CompletableFuture.runAsync(() -> {
                try {
                    ThreadLocalRandom random = ThreadLocalRandom.current();
                    
                    for (int j = 0; j < operationsPerThread; j++) {
                        VoxelData voxel1 = sourceVoxels[random.nextInt(sourceVoxels.length)];
                        VoxelData voxel2 = sourceVoxels[random.nextInt(sourceVoxels.length)];
                        float t = random.nextFloat();
                        
                        VoxelData interpolated = voxel1.interpolate(voxel2, t);
                        
                        // Verify interpolated result is valid
                        assertTrue(interpolated.getRed() >= 0 && interpolated.getRed() <= 255);
                        assertTrue(interpolated.getGreen() >= 0 && interpolated.getGreen() <= 255);
                        assertTrue(interpolated.getBlue() >= 0 && interpolated.getBlue() <= 255);
                        assertTrue(interpolated.getOpacity() >= 0 && interpolated.getOpacity() <= 255);
                        assertTrue(interpolated.getMaterialId() >= 0 && interpolated.getMaterialId() <= 255);
                        
                        Vector3f normal = interpolated.getNormal();
                        assertTrue(normal.x >= -1.0f && normal.x <= 1.0f);
                        assertTrue(normal.y >= -1.0f && normal.y <= 1.0f);
                        assertTrue(normal.z >= -1.0f && normal.z <= 1.0f);
                        
                        operationCount.incrementAndGet();
                    }
                } catch (Throwable e) {
                    exceptions.add(e);
                }
            }, executor);
        }
        
        CompletableFuture.allOf(futures).join();
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        
        assertTrue(exceptions.isEmpty(), "No exceptions should occur: " + exceptions);
        assertEquals(numThreads * operationsPerThread, operationCount.get());
        
        log.info("Concurrent interpolation test completed: {} operations", operationCount.get());
    }
    
    // ================================================================================
    // Memory Layout and Constants Tests
    // ================================================================================
    
    @Test
    @DisplayName("Memory layout constants are correct")
    void testMemoryLayoutConstants() {
        assertEquals(8, VoxelData.VOXEL_DATA_SIZE_BYTES);
        assertEquals(255, VoxelData.MAX_COMPONENT_VALUE);
        
        // Verify memory layout size
        assertEquals(8, VoxelData.MEMORY_LAYOUT.byteSize());
        assertEquals(8, VoxelData.MEMORY_LAYOUT.byteAlignment());
    }
    
    @Test
    @DisplayName("Bit field isolation works correctly")
    void testBitFieldIsolation() {
        // Set all fields to different values
        voxelData.setRed(0xAB);
        voxelData.setGreen(0xCD);
        voxelData.setBlue(0xEF);
        voxelData.setOpacity(0x12);
        voxelData.setMaterialId(0x34);
        voxelData.setNormal(0.5f, -0.3f, 0.8f);
        
        // Verify each field maintains its value despite others being set
        assertEquals(0xAB, voxelData.getRed());
        assertEquals(0xCD, voxelData.getGreen());
        assertEquals(0xEF, voxelData.getBlue());
        assertEquals(0x12, voxelData.getOpacity());
        assertEquals(0x34, voxelData.getMaterialId());
        
        // Change one field and verify others are unaffected
        int originalNormalX = voxelData.getNormalX();
        int originalNormalY = voxelData.getNormalY();
        int originalNormalZ = voxelData.getNormalZ();
        
        voxelData.setRed(0x00);
        assertEquals(0x00, voxelData.getRed());
        assertEquals(0xCD, voxelData.getGreen());
        assertEquals(0xEF, voxelData.getBlue());
        assertEquals(0x12, voxelData.getOpacity());
        assertEquals(0x34, voxelData.getMaterialId());
        assertEquals(originalNormalX, voxelData.getNormalX());
        assertEquals(originalNormalY, voxelData.getNormalY());
        assertEquals(originalNormalZ, voxelData.getNormalZ());
    }
    
    @Test
    @DisplayName("Native segment synchronization works correctly")
    void testNativeSegmentSynchronization() {
        // Create voxel with native segment
        MemorySegment segment = testArena.allocate(VoxelData.VOXEL_DATA_SIZE_BYTES);
        VoxelData voxelWithNative = new VoxelData(segment, 0);
        
        // Modify voxel and verify native memory is updated
        voxelWithNative.setColor(255, 128, 64);
        voxelWithNative.setOpacity(200);
        voxelWithNative.setMaterialId(10);
        
        // Read directly from native memory
        long nativeData = segment.get(ValueLayout.JAVA_LONG, 0);
        assertEquals(voxelWithNative.getPackedData(), nativeData);
        
        // Verify all operations sync to native memory
        voxelWithNative.setNormal(0.6f, 0.8f, 0.0f);
        voxelWithNative.clear();
        
        nativeData = segment.get(ValueLayout.JAVA_LONG, 0);
        assertEquals(0L, nativeData);
    }
}