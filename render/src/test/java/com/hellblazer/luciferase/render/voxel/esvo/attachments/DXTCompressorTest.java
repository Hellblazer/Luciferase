package com.hellblazer.luciferase.render.voxel.esvo.attachments;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DXT Compressor Tests")
class DXTCompressorTest {
    
    @Test
    @DisplayName("Should compress and decompress solid color block")
    void testSolidColorBlock() {
        // Create solid red block
        int red = 0xFF0000;
        int[] pixels = new int[16];
        for (int i = 0; i < 16; i++) {
            pixels[i] = red;
        }
        
        // Compress and decompress
        byte[] compressed = DXTCompressor.compressBlock(pixels);
        assertEquals(8, compressed.length);
        
        int[] decompressed = DXTCompressor.decompressBlock(compressed);
        assertEquals(16, decompressed.length);
        
        // Should be close to original (some loss due to 565 conversion)
        for (int pixel : decompressed) {
            int r = (pixel >> 16) & 0xFF;
            int g = (pixel >> 8) & 0xFF;
            int b = pixel & 0xFF;
            
            // Red should be dominant
            assertTrue(r > 240, "Red channel should be preserved");
            assertTrue(g < 16, "Green should be minimal");
            assertTrue(b < 16, "Blue should be minimal");
        }
    }
    
    @Test
    @DisplayName("Should compress gradient block")
    void testGradientBlock() {
        // Create gradient from black to white
        int[] pixels = new int[16];
        for (int i = 0; i < 16; i++) {
            int gray = (i * 255) / 15;
            pixels[i] = (gray << 16) | (gray << 8) | gray;
        }
        
        byte[] compressed = DXTCompressor.compressBlock(pixels);
        int[] decompressed = DXTCompressor.decompressBlock(compressed);
        
        // Check that we have a range of values
        int minGray = 255;
        int maxGray = 0;
        for (int pixel : decompressed) {
            int gray = pixel & 0xFF; // Blue channel (should equal red and green)
            minGray = Math.min(minGray, gray);
            maxGray = Math.max(maxGray, gray);
        }
        
        // Should preserve some range
        assertTrue(maxGray - minGray > 100, "Should preserve gradient range");
    }
    
    @Test
    @DisplayName("Should handle two-color pattern")
    void testTwoColorPattern() {
        // Checkerboard pattern
        int black = 0x000000;
        int white = 0xFFFFFF;
        int[] pixels = new int[16];
        
        for (int i = 0; i < 16; i++) {
            pixels[i] = ((i % 2) == 0) ? black : white;
        }
        
        byte[] compressed = DXTCompressor.compressBlock(pixels);
        int[] decompressed = DXTCompressor.decompressBlock(compressed);
        
        // Should have distinct light and dark pixels
        int lightCount = 0;
        int darkCount = 0;
        
        for (int pixel : decompressed) {
            int gray = pixel & 0xFF;
            if (gray > 128) lightCount++;
            else darkCount++;
        }
        
        assertTrue(lightCount > 0 && darkCount > 0, "Should preserve contrast");
    }
    
    @Test
    @DisplayName("Should compress full texture")
    void testTextureCompression() {
        // Create 8x8 texture with gradient
        int width = 8;
        int height = 8;
        int[] pixels = new int[width * height];
        
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int r = (x * 255) / (width - 1);
                int g = (y * 255) / (height - 1);
                int b = 128;
                pixels[y * width + x] = (r << 16) | (g << 8) | b;
            }
        }
        
        var compressed = DXTCompressor.compressTexture(pixels, width, height);
        assertNotNull(compressed);
        
        // Should be 4 blocks (2x2), each 8 bytes
        assertEquals(4 * 8, compressed.byteSize());
    }
    
    @Test
    @DisplayName("Should reject invalid dimensions")
    void testInvalidDimensions() {
        // Not multiple of 4
        int[] pixels = new int[15];
        
        assertThrows(IllegalArgumentException.class, () -> {
            DXTCompressor.compressBlock(new int[15]); // Not 16 pixels
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            DXTCompressor.compressTexture(new int[25], 5, 5); // Not 4x4 aligned
        });
    }
    
    @Test
    @DisplayName("Should handle edge values")
    void testEdgeValues() {
        // Test pure colors
        int[][] testColors = {
            {0xFF0000}, // Pure red
            {0x00FF00}, // Pure green
            {0x0000FF}, // Pure blue
            {0x000000}, // Black
            {0xFFFFFF}  // White
        };
        
        for (int[] color : testColors) {
            int[] pixels = new int[16];
            for (int i = 0; i < 16; i++) {
                pixels[i] = color[0];
            }
            
            byte[] compressed = DXTCompressor.compressBlock(pixels);
            int[] decompressed = DXTCompressor.decompressBlock(compressed);
            
            // All pixels should be similar
            int first = decompressed[0];
            for (int pixel : decompressed) {
                int dr = Math.abs(((pixel >> 16) & 0xFF) - ((first >> 16) & 0xFF));
                int dg = Math.abs(((pixel >> 8) & 0xFF) - ((first >> 8) & 0xFF));
                int db = Math.abs((pixel & 0xFF) - (first & 0xFF));
                
                // Allow some variance due to compression
                assertTrue(dr < 16 && dg < 16 && db < 16, 
                    "Solid color should be mostly preserved");
            }
        }
    }
}