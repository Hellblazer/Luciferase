package com.hellblazer.luciferase.render.compression;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Vector3f;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DXTNormalCompressor.
 */
class DXTNormalCompressorTest {
    
    private DXTNormalCompressor compressor;
    private Random random;
    
    @BeforeEach
    void setUp() {
        compressor = new DXTNormalCompressor();
        random = new Random(12345); // Fixed seed for reproducible tests
    }
    
    @Test
    void testSingleNormalCompression_CardinalDirections() {
        // Test cardinal direction normals
        var testNormals = new Vector3f[] {
            new Vector3f(1, 0, 0),    // +X
            new Vector3f(-1, 0, 0),   // -X  
            new Vector3f(0, 1, 0),    // +Y
            new Vector3f(0, -1, 0),   // -Y
            new Vector3f(0, 0, 1),    // +Z
            new Vector3f(0, 0, -1)    // -Z
        };
        
        for (var original : testNormals) {
            int compressed = compressor.compressNormal(original);
            var decompressed = compressor.decompressNormal(compressed);
            
            // Should be very close to original
            float error = calculateError(original, decompressed);
            assertTrue(error < 0.01f, String.format(
                "Cardinal normal %s error %.6f too high", original, error));
        }
    }
    
    @Test
    void testSingleNormalCompression_RandomNormals() {
        // Test with random normalized vectors
        for (int i = 0; i < 100; i++) {
            var original = generateRandomNormal();
            
            int compressed = compressor.compressNormal(original);
            var decompressed = compressor.decompressNormal(compressed);
            
            // Should be reasonably close
            float error = calculateError(original, decompressed);
            assertTrue(error < 0.05f, String.format(
                "Random normal %s error %.6f too high", original, error));
            
            // Decompressed should be normalized
            assertEquals(1.0f, decompressed.length(), 0.001f);
        }
    }
    
    @Test
    void testCompressionRatio() {
        var normals = generateRandomNormals(1000);
        
        var block = compressor.compressNormals(normals);
        
        // Should achieve close to 3:1 compression ratio (12 bytes -> 4 bytes)
        double ratio = block.getCompressionRatio();
        assertEquals(3.0, ratio, 0.1);
        
        // Verify sizes
        assertEquals(1000 * 12, block.getOriginalSize());
        assertEquals(1000 * 4, block.getCompressedSize());
    }
    
    @Test
    void testBlockCompressionDecompression() {
        var originalNormals = generateRandomNormals(50);
        
        // Compress
        var block = compressor.compressNormals(originalNormals);
        assertNotNull(block);
        assertEquals(50, block.normalCount);
        
        // Decompress
        var decompressedNormals = compressor.decompressNormals(block);
        assertEquals(50, decompressedNormals.length);
        
        // Verify quality
        for (int i = 0; i < originalNormals.length; i++) {
            float error = calculateError(originalNormals[i], decompressedNormals[i]);
            assertTrue(error < 0.05f, String.format("Normal %d error too high: %.6f", i, error));
        }
    }
    
    @Test
    void testQualityLevels() {
        var original = new Vector3f(0.6f, 0.8f, 0.0f);
        original.normalize();
        
        // Test different quality levels
        for (var quality : DXTNormalCompressor.CompressionQuality.values()) {
            var qualityCompressor = new DXTNormalCompressor(quality);
            
            int compressed = qualityCompressor.compressNormal(original);
            var decompressed = qualityCompressor.decompressNormal(compressed);
            
            float error = calculateError(original, decompressed);
            
            // Higher quality should have lower error (in general)
            assertTrue(error < 0.1f, String.format(
                "Quality %s error %.6f too high", quality, error));
        }
    }
    
    @Test
    void testBatchCompression() {
        var batch1 = generateRandomNormals(10);
        var batch2 = generateRandomNormals(20);
        var batch3 = generateRandomNormals(15);
        
        var batches = List.of(batch1, batch2, batch3);
        var compressedBlocks = compressor.compressBatch(batches);
        
        assertEquals(3, compressedBlocks.size());
        assertEquals(10, compressedBlocks.get(0).normalCount);
        assertEquals(20, compressedBlocks.get(1).normalCount);
        assertEquals(15, compressedBlocks.get(2).normalCount);
        
        // Verify decompression works for all batches
        for (int i = 0; i < batches.size(); i++) {
            var original = batches.get(i);
            var decompressed = compressor.decompressNormals(compressedBlocks.get(i));
            
            assertEquals(original.length, decompressed.length);
            
            for (int j = 0; j < original.length; j++) {
                float error = calculateError(original[j], decompressed[j]);
                assertTrue(error < 0.05f);
            }
        }
    }
    
    @Test
    void testQualityAnalysis() {
        var originalNormals = generateRandomNormals(100);
        var compressedBlock = compressor.compressNormals(originalNormals);
        var decompressedNormals = compressor.decompressNormals(compressedBlock);
        
        var analysis = compressor.analyzeQuality(originalNormals, decompressedNormals);
        
        // Verify analysis metrics are reasonable
        assertTrue(analysis.rmsError >= 0.0);
        assertTrue(analysis.rmsError < 0.1); // Should be quite good
        assertTrue(analysis.maxError >= analysis.avgError);
        assertTrue(analysis.avgError >= 0.0);
        assertEquals(3.0, analysis.compressionRatio, 0.1);
        assertEquals(100 * 12, analysis.originalBytes);
        assertEquals(100 * 4, analysis.compressedBytes);
        
        assertNotNull(analysis.toString());
    }
    
    @Test
    void testEdgeCases_NearZeroNormals() {
        // Test normals that are nearly zero (should be handled gracefully)
        var nearZero = new Vector3f(0.001f, 0.001f, 0.001f);
        
        int compressed = compressor.compressNormal(nearZero);
        var decompressed = compressor.decompressNormal(compressed);
        
        // Should be normalized even if input wasn't
        assertEquals(1.0f, decompressed.length(), 0.001f);
    }
    
    @Test
    void testEdgeCases_UnnormalizedInput() {
        // Test with unnormalized input (should be normalized internally)
        var unnormalized = new Vector3f(10.0f, 20.0f, 30.0f);
        
        int compressed = compressor.compressNormal(unnormalized);
        var decompressed = compressor.decompressNormal(compressed);
        
        // Should be normalized
        assertEquals(1.0f, decompressed.length(), 0.001f);
        
        // Should be proportional to normalized input
        var expectedNormalized = new Vector3f(unnormalized);
        expectedNormalized.normalize();
        
        float error = calculateError(expectedNormalized, decompressed);
        assertTrue(error < 0.05f);
    }
    
    @Test
    void testCompressedBlockProperties() {
        var normals = generateRandomNormals(25);
        var block = compressor.compressNormals(normals);
        
        assertNotNull(block.data);
        assertEquals(25, block.normalCount);
        assertEquals(DXTNormalCompressor.CompressionQuality.BALANCED, block.quality);
        assertTrue(block.compressionTime > 0);
        assertEquals(25 * 4, block.getCompressedSize());
        assertEquals(25 * 12, block.getOriginalSize());
        assertEquals(3.0, block.getCompressionRatio(), 0.001);
    }
    
    @Test
    void testDominantAxisDetection() {
        // Test that dominant axis detection works correctly by checking
        // that decompression produces reasonable results for different orientations
        
        // X-dominant normal
        var xDominant = new Vector3f(0.9f, 0.3f, 0.2f);
        xDominant.normalize();
        
        int compressed = compressor.compressNormal(xDominant);
        var decompressed = compressor.decompressNormal(compressed);
        
        // X component should still be dominant
        assertTrue(Math.abs(decompressed.x) >= Math.abs(decompressed.y));
        assertTrue(Math.abs(decompressed.x) >= Math.abs(decompressed.z));
        
        float error = calculateError(xDominant, decompressed);
        assertTrue(error < 0.05f);
    }
    
    @Test
    void testPerformanceCharacteristics() {
        var normals = generateRandomNormals(1000);
        
        long startTime = System.nanoTime();
        var block = compressor.compressNormals(normals);
        long compressionTime = System.nanoTime() - startTime;
        
        startTime = System.nanoTime();
        var decompressed = compressor.decompressNormals(block);
        long decompressionTime = System.nanoTime() - startTime;
        
        // Verify we can process normals reasonably quickly
        double compressionRate = 1000.0 / (compressionTime / 1e9); // normals per second
        double decompressionRate = 1000.0 / (decompressionTime / 1e9);
        
        // Should be able to compress/decompress many thousands per second
        assertTrue(compressionRate > 10000, String.format(
            "Compression rate %.0f/sec too slow", compressionRate));
        assertTrue(decompressionRate > 10000, String.format(
            "Decompression rate %.0f/sec too slow", decompressionRate));
        
        assertEquals(normals.length, decompressed.length);
    }
    
    @Test
    void testAnalyzeQuality_MismatchedArrays() {
        var original = generateRandomNormals(10);
        var compressed = generateRandomNormals(5);
        
        assertThrows(IllegalArgumentException.class, () -> {
            compressor.analyzeQuality(original, compressed);
        });
    }
    
    @Test
    void testGetQuality() {
        assertEquals(DXTNormalCompressor.CompressionQuality.BALANCED, compressor.getQuality());
        
        var fastCompressor = new DXTNormalCompressor(DXTNormalCompressor.CompressionQuality.FAST);
        assertEquals(DXTNormalCompressor.CompressionQuality.FAST, fastCompressor.getQuality());
    }
    
    @Test
    void testShaderGeneration() {
        var shader = DXTNormalCompressor.generateDecompressionShader();
        
        assertNotNull(shader);
        assertTrue(shader.contains("decompressNormal"));
        assertTrue(shader.contains("vec3<f32>"));
        assertTrue(shader.contains("normalize"));
        assertTrue(shader.length() > 100);
    }
    
    @Test
    void testConsistentCompression() {
        // Same input should produce same output
        var normal = new Vector3f(0.577f, 0.577f, 0.577f); // Normalized (1,1,1)
        
        int compressed1 = compressor.compressNormal(normal);
        int compressed2 = compressor.compressNormal(normal);
        
        assertEquals(compressed1, compressed2);
        
        var decompressed1 = compressor.decompressNormal(compressed1);
        var decompressed2 = compressor.decompressNormal(compressed2);
        
        assertEquals(decompressed1.x, decompressed2.x, 0.001f);
        assertEquals(decompressed1.y, decompressed2.y, 0.001f);
        assertEquals(decompressed1.z, decompressed2.z, 0.001f);
    }
    
    @Test
    void testCompressionQualityEnum() {
        // Verify quality levels have expected characteristics
        var fast = DXTNormalCompressor.CompressionQuality.FAST;
        var balanced = DXTNormalCompressor.CompressionQuality.BALANCED;
        var high = DXTNormalCompressor.CompressionQuality.HIGH;
        
        assertTrue(fast.quantizationLevels < balanced.quantizationLevels);
        assertTrue(balanced.quantizationLevels < high.quantizationLevels);
        
        assertEquals(128, fast.quantizationLevels);
        assertEquals(256, balanced.quantizationLevels);
        assertEquals(512, high.quantizationLevels);
    }
    
    /**
     * Generate a random normalized normal vector.
     */
    private Vector3f generateRandomNormal() {
        // Use Box-Muller transform to generate normally distributed components
        // then normalize to create uniform distribution on unit sphere
        float x = (float) random.nextGaussian();
        float y = (float) random.nextGaussian();
        float z = (float) random.nextGaussian();
        
        var normal = new Vector3f(x, y, z);
        normal.normalize();
        return normal;
    }
    
    /**
     * Generate an array of random normal vectors.
     */
    private Vector3f[] generateRandomNormals(int count) {
        var normals = new Vector3f[count];
        for (int i = 0; i < count; i++) {
            normals[i] = generateRandomNormal();
        }
        return normals;
    }
    
    /**
     * Calculate angular error between two normal vectors.
     */
    private float calculateError(Vector3f original, Vector3f compressed) {
        float dot = original.dot(compressed);
        dot = Math.max(-1.0f, Math.min(1.0f, dot)); // Clamp for numerical stability
        return (float) Math.acos(Math.abs(dot));
    }
}