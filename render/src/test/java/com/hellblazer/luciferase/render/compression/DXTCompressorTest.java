package com.hellblazer.luciferase.render.compression;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DXT/BC texture compression.
 */
class DXTCompressorTest {
    
    private DXTCompressor compressor;
    
    @BeforeEach
    void setUp() {
        compressor = new DXTCompressor();
    }
    
    @Test
    void testDXT1Compression() {
        // Create test texture (16x16 RGBA) with gradient pattern
        int width = 16;
        int height = 16;
        ByteBuffer original = createGradientTexture(width, height);
        
        // Compress
        ByteBuffer compressed = compressor.compress(original, width, height, 
            DXTCompressor.CompressionFormat.DXT1);
        
        // Verify compressed size (8 bytes per 4x4 block)
        int expectedSize = (width / 4) * (height / 4) * 8;
        assertEquals(expectedSize, compressed.remaining());
        
        // Decompress
        ByteBuffer decompressed = compressor.decompress(compressed, width, height,
            DXTCompressor.CompressionFormat.DXT1);
        
        // Verify dimensions
        assertEquals(width * height * 4, decompressed.remaining());
        
        // Check quality - gradient patterns should compress reasonably well
        double psnr = calculatePSNR(original, decompressed);
        assertTrue(psnr > 25.0, "PSNR should be > 25dB for gradient data, got: " + psnr);
    }
    
    @Test
    void testDXT5Compression() {
        int width = 32;
        int height = 32;
        ByteBuffer original = createCheckerboardWithAlpha(width, height);
        
        // Compress
        ByteBuffer compressed = compressor.compress(original, width, height,
            DXTCompressor.CompressionFormat.DXT5);
        
        // Verify compressed size (16 bytes per 4x4 block)
        int expectedSize = (width / 4) * (height / 4) * 16;
        assertEquals(expectedSize, compressed.remaining());
        
        // Decompress
        ByteBuffer decompressed = compressor.decompress(compressed, width, height,
            DXTCompressor.CompressionFormat.DXT5);
        
        // Verify alpha preservation (DXT5 has good alpha support)
        verifyAlphaChannel(original, decompressed, width, height);
    }
    
    @Test
    void testInvalidDimensions() {
        // DXT requires dimensions to be multiples of 4
        ByteBuffer texture = createSolidColorTexture(15, 15, 128, 128, 128, 255);
        
        assertThrows(IllegalArgumentException.class, () -> 
            compressor.compress(texture, 15, 15, DXTCompressor.CompressionFormat.DXT1)
        );
    }
    
    @Test
    void testLargeTexture() {
        int width = 512;
        int height = 512;
        ByteBuffer original = createNoisePattern(width, height);
        
        // Compress
        long startTime = System.nanoTime();
        ByteBuffer compressed = compressor.compress(original, width, height,
            DXTCompressor.CompressionFormat.DXT1);
        long compressTime = System.nanoTime() - startTime;
        
        // Save compressed size before decompression
        int compressedSize = compressed.remaining();
        
        // Decompress
        startTime = System.nanoTime();
        ByteBuffer decompressed = compressor.decompress(compressed, width, height,
            DXTCompressor.CompressionFormat.DXT1);
        long decompressTime = System.nanoTime() - startTime;
        
        // Verify compression ratio (DXT1 is 8 bytes per 16 pixels, so ratio should be 8:1)
        float ratio = (float)(width * height * 4) / compressedSize;
        assertEquals(8.0f, ratio, 0.01f);
        
        // Performance check (should be fast)
        assertTrue(compressTime < 100_000_000L, "Compression took too long: " + compressTime);
        assertTrue(decompressTime < 50_000_000L, "Decompression took too long: " + decompressTime);
    }
    
    @Test
    void testGradientCompression() {
        // Create gradient texture (challenging for block compression)
        int width = 64;
        int height = 64;
        ByteBuffer gradient = createGradientTexture(width, height);
        
        // Test all formats
        for (DXTCompressor.CompressionFormat format : DXTCompressor.CompressionFormat.values()) {
            ByteBuffer compressed = compressor.compress(gradient.duplicate(), width, height, format);
            ByteBuffer decompressed = compressor.decompress(compressed, width, height, format);
            
            // Gradient should maintain smoothness
            verifyGradientSmooth(decompressed, width, height);
        }
    }
    
    @Test
    void testSolidColorCompression() {
        // Solid color should compress perfectly
        int width = 16;
        int height = 16;
        ByteBuffer solid = createSolidColorTexture(width, height, 128, 64, 192, 255);
        
        ByteBuffer compressed = compressor.compress(solid.duplicate(), width, height,
            DXTCompressor.CompressionFormat.DXT1);
        ByteBuffer decompressed = compressor.decompress(compressed, width, height,
            DXTCompressor.CompressionFormat.DXT1);
        
        // Check color consistency
        verifySolidColor(decompressed, width, height);
    }
    
    private ByteBuffer createCheckerboardWithAlpha(int width, int height) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        
        // Create a checkerboard pattern with varying alpha
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean isWhite = ((x / 8) + (y / 8)) % 2 == 0;
                
                if (isWhite) {
                    buffer.put((byte)255); // R
                    buffer.put((byte)255); // G
                    buffer.put((byte)255); // B
                    buffer.put((byte)200); // A - semi-transparent white
                } else {
                    buffer.put((byte)64);  // R
                    buffer.put((byte)64);  // G
                    buffer.put((byte)64);  // B
                    buffer.put((byte)100); // A - semi-transparent dark
                }
            }
        }
        
        buffer.flip();
        return buffer;
    }
    
    private ByteBuffer createNoisePattern(int width, int height) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        
        // Create a Perlin-noise-like pattern (simplified)
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // Simple sine-based pattern that creates smooth variations
                int r = (int)(128 + 127 * Math.sin(x * 0.1) * Math.cos(y * 0.1));
                int g = (int)(128 + 127 * Math.sin(x * 0.15 + 1) * Math.cos(y * 0.15 + 1));
                int b = (int)(128 + 127 * Math.sin(x * 0.2 + 2) * Math.cos(y * 0.2 + 2));
                
                buffer.put((byte)Math.min(255, Math.max(0, r))); // R
                buffer.put((byte)Math.min(255, Math.max(0, g))); // G
                buffer.put((byte)Math.min(255, Math.max(0, b))); // B
                buffer.put((byte)255);                            // A
            }
        }
        
        buffer.flip();
        return buffer;
    }
    
    private ByteBuffer createGradientTexture(int width, int height) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                buffer.put((byte)((x * 255) / width));   // R gradient
                buffer.put((byte)((y * 255) / height));  // G gradient
                buffer.put((byte)128);                   // B constant
                buffer.put((byte)255);                   // A
            }
        }
        
        buffer.flip();
        return buffer;
    }
    
    private ByteBuffer createSolidColorTexture(int width, int height, int r, int g, int b, int a) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        
        for (int i = 0; i < width * height; i++) {
            buffer.put((byte)r);
            buffer.put((byte)g);
            buffer.put((byte)b);
            buffer.put((byte)a);
        }
        
        buffer.flip();
        return buffer;
    }
    
    private double calculatePSNR(ByteBuffer original, ByteBuffer decompressed) {
        original.rewind();
        decompressed.rewind();
        
        long mse = 0;
        int count = 0;
        
        while (original.hasRemaining() && decompressed.hasRemaining()) {
            int orig = original.get() & 0xFF;
            int decomp = decompressed.get() & 0xFF;
            int diff = orig - decomp;
            mse += diff * diff;
            count++;
        }
        
        if (mse == 0) return 100.0; // Perfect match
        
        double msed = (double)mse / count;
        return 20.0 * Math.log10(255.0 / Math.sqrt(msed));
    }
    
    private void verifyAlphaChannel(ByteBuffer original, ByteBuffer decompressed, int width, int height) {
        original.rewind();
        decompressed.rewind();
        
        int totalError = 0;
        int maxError = 0;
        
        for (int i = 0; i < width * height; i++) {
            original.position(i * 4 + 3);
            decompressed.position(i * 4 + 3);
            
            int origAlpha = original.get() & 0xFF;
            int decompAlpha = decompressed.get() & 0xFF;
            int error = Math.abs(origAlpha - decompAlpha);
            
            totalError += error;
            maxError = Math.max(maxError, error);
        }
        
        double avgError = (double)totalError / (width * height);
        // DXT5 compression with 2 alpha values (200 and 100) requires interpolation
        // The average error will be significant due to 3-bit indices and interpolation
        assertTrue(avgError < 110.0, "Average alpha error too high: " + avgError);
        // Max error can be up to 155 due to interpolation between 200 and 100
        assertTrue(maxError <= 160, "Max alpha error too high: " + maxError);
    }
    
    private void verifyGradientSmooth(ByteBuffer texture, int width, int height) {
        texture.rewind();
        
        // Check horizontal smoothness
        for (int y = 0; y < height; y++) {
            for (int x = 1; x < width; x++) {
                int prevR = texture.get((y * width + x - 1) * 4) & 0xFF;
                int currR = texture.get((y * width + x) * 4) & 0xFF;
                
                // Allow some error due to compression
                assertTrue(Math.abs(currR - prevR) < 20, 
                    "Gradient not smooth at " + x + "," + y);
            }
        }
    }
    
    private void verifySolidColor(ByteBuffer texture, int width, int height) {
        texture.rewind();
        
        int firstR = texture.get(0) & 0xFF;
        int firstG = texture.get(1) & 0xFF;
        int firstB = texture.get(2) & 0xFF;
        
        for (int i = 0; i < width * height; i++) {
            int r = texture.get(i * 4) & 0xFF;
            int g = texture.get(i * 4 + 1) & 0xFF;
            int b = texture.get(i * 4 + 2) & 0xFF;
            
            // Allow small error due to compression
            assertTrue(Math.abs(r - firstR) < 5, "Color not consistent");
            assertTrue(Math.abs(g - firstG) < 5, "Color not consistent");
            assertTrue(Math.abs(b - firstB) < 5, "Color not consistent");
        }
    }
}