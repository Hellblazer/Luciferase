package com.hellblazer.luciferase.render.compression;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DXT/BC texture compression.
 */
class DXTCompressorTest {
    
    private DXTCompressor compressor;
    private Random random;
    
    @BeforeEach
    void setUp() {
        compressor = new DXTCompressor();
        random = new Random(42);
    }
    
    @Test
    void testDXT1Compression() {
        // Create test texture (16x16 RGBA)
        int width = 16;
        int height = 16;
        ByteBuffer original = createTestTexture(width, height);
        
        // Compress
        ByteBuffer compressed = compressor.compress(original, width, height, 
            DXTCompressor.CompressionFormat.DXT1);
        
        // Verify compressed size (4:1 ratio, 8 bytes per 4x4 block)
        int expectedSize = (width / 4) * (height / 4) * 8;
        assertEquals(expectedSize, compressed.remaining());
        
        // Decompress
        ByteBuffer decompressed = compressor.decompress(compressed, width, height,
            DXTCompressor.CompressionFormat.DXT1);
        
        // Verify dimensions
        assertEquals(width * height * 4, decompressed.remaining());
        
        // Check quality (DXT1 is lossy, so we check similarity)
        double psnr = calculatePSNR(original, decompressed);
        assertTrue(psnr > 30.0, "PSNR should be > 30dB, got: " + psnr);
    }
    
    @Test
    void testDXT5Compression() {
        int width = 32;
        int height = 32;
        ByteBuffer original = createTestTextureWithAlpha(width, height);
        
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
        ByteBuffer texture = createTestTexture(15, 15);
        
        assertThrows(IllegalArgumentException.class, () -> 
            compressor.compress(texture, 15, 15, DXTCompressor.CompressionFormat.DXT1)
        );
    }
    
    @Test
    void testLargeTexture() {
        int width = 512;
        int height = 512;
        ByteBuffer original = createTestTexture(width, height);
        
        // Compress
        long startTime = System.nanoTime();
        ByteBuffer compressed = compressor.compress(original, width, height,
            DXTCompressor.CompressionFormat.DXT1);
        long compressTime = System.nanoTime() - startTime;
        
        // Decompress
        startTime = System.nanoTime();
        ByteBuffer decompressed = compressor.decompress(compressed, width, height,
            DXTCompressor.CompressionFormat.DXT1);
        long decompressTime = System.nanoTime() - startTime;
        
        // Verify compression ratio
        float ratio = (float)original.remaining() / compressed.remaining();
        assertEquals(4.0f, ratio, 0.01f);
        
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
    
    private ByteBuffer createTestTexture(int width, int height) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                buffer.put((byte)(random.nextInt(256))); // R
                buffer.put((byte)(random.nextInt(256))); // G
                buffer.put((byte)(random.nextInt(256))); // B
                buffer.put((byte)255);                   // A
            }
        }
        
        buffer.flip();
        return buffer;
    }
    
    private ByteBuffer createTestTextureWithAlpha(int width, int height) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                buffer.put((byte)(random.nextInt(256))); // R
                buffer.put((byte)(random.nextInt(256))); // G
                buffer.put((byte)(random.nextInt(256))); // B
                buffer.put((byte)(random.nextInt(256))); // A (varying alpha)
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
        assertTrue(avgError < 10.0, "Average alpha error too high: " + avgError);
        assertTrue(maxError < 50, "Max alpha error too high: " + maxError);
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