package com.hellblazer.luciferase.render.voxel.esvo.attachments;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * DXT1 texture compression for ESVO voxel attributes.
 * Implements BC1 compression (4:1 ratio) for RGB color data.
 */
public class DXTCompressor {
    
    // DXT1 block is 8 bytes: 2 bytes for colors, 4x4 2-bit indices
    public static final int DXT_BLOCK_SIZE = 8;
    public static final int PIXELS_PER_BLOCK = 16; // 4x4
    
    /**
     * Compress a 4x4 block of RGB pixels to DXT1 format
     */
    public static byte[] compressBlock(int[] pixels) {
        if (pixels.length != PIXELS_PER_BLOCK) {
            throw new IllegalArgumentException("DXT1 requires exactly 16 pixels");
        }
        
        // Find min/max colors for endpoints
        int minR = 255, minG = 255, minB = 255;
        int maxR = 0, maxG = 0, maxB = 0;
        
        for (int pixel : pixels) {
            int r = (pixel >> 16) & 0xFF;
            int g = (pixel >> 8) & 0xFF;
            int b = pixel & 0xFF;
            
            minR = Math.min(minR, r);
            minG = Math.min(minG, g);
            minB = Math.min(minB, b);
            maxR = Math.max(maxR, r);
            maxG = Math.max(maxG, g);
            maxB = Math.max(maxB, b);
        }
        
        // Convert to 565 format
        short color0 = packColor565(maxR, maxG, maxB);
        short color1 = packColor565(minR, minG, minB);
        
        // Ensure color0 > color1 for 4-color mode
        if (color0 < color1) {
            short temp = color0;
            color0 = color1;
            color1 = temp;
        }
        
        // Generate palette
        int[] palette = generatePalette(color0, color1);
        
        // Find best indices for each pixel
        int indices = 0;
        for (int i = 0; i < PIXELS_PER_BLOCK; i++) {
            int bestIdx = findBestPaletteIndex(pixels[i], palette);
            indices |= (bestIdx << (i * 2));
        }
        
        // Pack into 8 bytes
        ByteBuffer buffer = ByteBuffer.allocate(DXT_BLOCK_SIZE);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.putShort(color0);
        buffer.putShort(color1);
        buffer.putInt(indices);
        
        return buffer.array();
    }
    
    /**
     * Decompress a DXT1 block to RGB pixels
     */
    public static int[] decompressBlock(byte[] dxtData) {
        if (dxtData.length != DXT_BLOCK_SIZE) {
            throw new IllegalArgumentException("Invalid DXT1 block size");
        }
        
        ByteBuffer buffer = ByteBuffer.wrap(dxtData);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        
        short color0 = buffer.getShort();
        short color1 = buffer.getShort();
        int indices = buffer.getInt();
        
        // Generate palette
        int[] palette = generatePalette(color0, color1);
        
        // Decode pixels
        int[] pixels = new int[PIXELS_PER_BLOCK];
        for (int i = 0; i < PIXELS_PER_BLOCK; i++) {
            int idx = (indices >> (i * 2)) & 0x3;
            pixels[i] = palette[idx];
        }
        
        return pixels;
    }
    
    /**
     * Pack RGB values into 565 format
     */
    private static short packColor565(int r, int g, int b) {
        int r5 = (r * 31 + 127) / 255;
        int g6 = (g * 63 + 127) / 255;
        int b5 = (b * 31 + 127) / 255;
        return (short)((r5 << 11) | (g6 << 5) | b5);
    }
    
    /**
     * Unpack 565 format to RGB
     */
    private static int unpackColor565(short color565) {
        int r = ((color565 >> 11) & 0x1F) * 255 / 31;
        int g = ((color565 >> 5) & 0x3F) * 255 / 63;
        int b = (color565 & 0x1F) * 255 / 31;
        return (r << 16) | (g << 8) | b;
    }
    
    /**
     * Generate 4-color palette from two endpoints
     */
    private static int[] generatePalette(short color0, short color1) {
        int[] palette = new int[4];
        
        int c0 = unpackColor565(color0);
        int c1 = unpackColor565(color1);
        
        palette[0] = c0;
        palette[1] = c1;
        
        if (color0 > color1) {
            // 4-color mode: interpolate 2 middle colors
            palette[2] = interpolateColor(c0, c1, 2, 3);
            palette[3] = interpolateColor(c0, c1, 1, 3);
        } else {
            // 3-color + transparent mode
            palette[2] = interpolateColor(c0, c1, 1, 2);
            palette[3] = 0; // Transparent black
        }
        
        return palette;
    }
    
    /**
     * Interpolate between two colors
     */
    private static int interpolateColor(int c0, int c1, int num, int denom) {
        int r0 = (c0 >> 16) & 0xFF;
        int g0 = (c0 >> 8) & 0xFF;
        int b0 = c0 & 0xFF;
        
        int r1 = (c1 >> 16) & 0xFF;
        int g1 = (c1 >> 8) & 0xFF;
        int b1 = c1 & 0xFF;
        
        int r = (r0 * (denom - num) + r1 * num) / denom;
        int g = (g0 * (denom - num) + g1 * num) / denom;
        int b = (b0 * (denom - num) + b1 * num) / denom;
        
        return (r << 16) | (g << 8) | b;
    }
    
    /**
     * Find best matching palette index for a pixel
     */
    private static int findBestPaletteIndex(int pixel, int[] palette) {
        int bestIdx = 0;
        int bestDist = Integer.MAX_VALUE;
        
        int pr = (pixel >> 16) & 0xFF;
        int pg = (pixel >> 8) & 0xFF;
        int pb = pixel & 0xFF;
        
        for (int i = 0; i < palette.length; i++) {
            int cr = (palette[i] >> 16) & 0xFF;
            int cg = (palette[i] >> 8) & 0xFF;
            int cb = palette[i] & 0xFF;
            
            int dr = pr - cr;
            int dg = pg - cg;
            int db = pb - cb;
            int dist = dr * dr + dg * dg + db * db;
            
            if (dist < bestDist) {
                bestDist = dist;
                bestIdx = i;
            }
        }
        
        return bestIdx;
    }
    
    /**
     * Compress texture data using DXT1
     */
    public static MemorySegment compressTexture(int[] pixels, int width, int height) {
        if (width % 4 != 0 || height % 4 != 0) {
            throw new IllegalArgumentException("Texture dimensions must be multiples of 4");
        }
        
        int blocksX = width / 4;
        int blocksY = height / 4;
        int totalBlocks = blocksX * blocksY;
        
        var compressed = MemorySegment.ofArray(new byte[totalBlocks * DXT_BLOCK_SIZE]);
        
        for (int by = 0; by < blocksY; by++) {
            for (int bx = 0; bx < blocksX; bx++) {
                // Extract 4x4 block
                int[] block = new int[PIXELS_PER_BLOCK];
                for (int y = 0; y < 4; y++) {
                    for (int x = 0; x < 4; x++) {
                        int px = bx * 4 + x;
                        int py = by * 4 + y;
                        block[y * 4 + x] = pixels[py * width + px];
                    }
                }
                
                // Compress block
                byte[] compressedBlock = compressBlock(block);
                
                // Write to output
                int offset = (by * blocksX + bx) * DXT_BLOCK_SIZE;
                for (int i = 0; i < DXT_BLOCK_SIZE; i++) {
                    compressed.set(ValueLayout.JAVA_BYTE, offset + i, compressedBlock[i]);
                }
            }
        }
        
        return compressed;
    }
}