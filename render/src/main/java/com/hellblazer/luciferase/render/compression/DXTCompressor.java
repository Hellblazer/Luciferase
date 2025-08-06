package com.hellblazer.luciferase.render.compression;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * DXT/BC texture compression implementation.
 * Supports DXT1 (BC1) for RGB and DXT5 (BC3) for RGBA textures.
 * 
 * DXT1: 4:1 compression ratio (8 bytes per 4x4 block)
 * DXT5: 4:1 compression ratio (16 bytes per 4x4 block)
 */
public class DXTCompressor {
    
    public enum CompressionFormat {
        DXT1(8, false),   // BC1: 8 bytes per block, no alpha
        DXT1A(8, true),   // BC1: 8 bytes per block, 1-bit alpha
        DXT3(16, true),   // BC2: 16 bytes per block, explicit alpha
        DXT5(16, true);   // BC3: 16 bytes per block, interpolated alpha
        
        private final int blockSize;
        private final boolean hasAlpha;
        
        CompressionFormat(int blockSize, boolean hasAlpha) {
            this.blockSize = blockSize;
            this.hasAlpha = hasAlpha;
        }
        
        public int getBlockSize() { return blockSize; }
        public boolean hasAlpha() { return hasAlpha; }
    }
    
    private static final int BLOCK_WIDTH = 4;
    private static final int BLOCK_HEIGHT = 4;
    
    /**
     * Compress RGBA texture data to DXT format.
     */
    public ByteBuffer compress(ByteBuffer input, int width, int height, CompressionFormat format) {
        if (width % BLOCK_WIDTH != 0 || height % BLOCK_HEIGHT != 0) {
            throw new IllegalArgumentException("Texture dimensions must be multiples of 4");
        }
        
        // Ensure input buffer is at the beginning
        input.rewind();
        
        int blocksX = width / BLOCK_WIDTH;
        int blocksY = height / BLOCK_HEIGHT;
        int totalBlocks = blocksX * blocksY;
        
        ByteBuffer output = ByteBuffer.allocateDirect(totalBlocks * format.getBlockSize());
        output.order(ByteOrder.LITTLE_ENDIAN);
        
        byte[] block = new byte[BLOCK_WIDTH * BLOCK_HEIGHT * 4]; // RGBA
        
        for (int by = 0; by < blocksY; by++) {
            for (int bx = 0; bx < blocksX; bx++) {
                extractBlock(input, width, height, bx, by, block);
                
                switch (format) {
                    case DXT1:
                    case DXT1A:
                        compressDXT1Block(block, output, format == CompressionFormat.DXT1A);
                        break;
                    case DXT3:
                        compressDXT3Block(block, output);
                        break;
                    case DXT5:
                        compressDXT5Block(block, output);
                        break;
                }
            }
        }
        
        output.flip();
        return output;
    }
    
    /**
     * Decompress DXT format to RGBA texture data.
     */
    public ByteBuffer decompress(ByteBuffer input, int width, int height, CompressionFormat format) {
        if (width % BLOCK_WIDTH != 0 || height % BLOCK_HEIGHT != 0) {
            throw new IllegalArgumentException("Texture dimensions must be multiples of 4");
        }
        
        // Ensure input buffer is at the beginning
        input.rewind();
        
        int blocksX = width / BLOCK_WIDTH;
        int blocksY = height / BLOCK_HEIGHT;
        
        ByteBuffer output = ByteBuffer.allocateDirect(width * height * 4);
        output.order(ByteOrder.LITTLE_ENDIAN);
        
        byte[] block = new byte[BLOCK_WIDTH * BLOCK_HEIGHT * 4];
        
        for (int by = 0; by < blocksY; by++) {
            for (int bx = 0; bx < blocksX; bx++) {
                switch (format) {
                    case DXT1:
                    case DXT1A:
                        decompressDXT1Block(input, block, format == CompressionFormat.DXT1A);
                        break;
                    case DXT3:
                        decompressDXT3Block(input, block);
                        break;
                    case DXT5:
                        decompressDXT5Block(input, block);
                        break;
                }
                
                writeBlock(output, width, height, bx, by, block);
            }
        }
        
        output.flip();
        return output;
    }
    
    private void extractBlock(ByteBuffer input, int width, int height, int bx, int by, byte[] block) {
        int blockStartX = bx * BLOCK_WIDTH;
        int blockStartY = by * BLOCK_HEIGHT;
        
        // Clear block first
        java.util.Arrays.fill(block, (byte) 0);
        
        for (int y = 0; y < BLOCK_HEIGHT; y++) {
            for (int x = 0; x < BLOCK_WIDTH; x++) {
                int pixelX = blockStartX + x;
                int pixelY = blockStartY + y;
                
                if (pixelX < width && pixelY < height) {
                    int srcIndex = (pixelY * width + pixelX) * 4;
                    int dstIndex = (y * BLOCK_WIDTH + x) * 4;
                    
                    if (srcIndex + 3 < input.capacity()) {
                        // Use absolute get methods to avoid position manipulation
                        block[dstIndex] = input.get(srcIndex);         // R
                        block[dstIndex + 1] = input.get(srcIndex + 1); // G
                        block[dstIndex + 2] = input.get(srcIndex + 2); // B
                        block[dstIndex + 3] = input.get(srcIndex + 3); // A
                    }
                }
            }
        }
    }
    
    private void writeBlock(ByteBuffer output, int width, int height, int bx, int by, byte[] block) {
        int blockStartX = bx * BLOCK_WIDTH;
        int blockStartY = by * BLOCK_HEIGHT;
        
        for (int y = 0; y < BLOCK_HEIGHT; y++) {
            for (int x = 0; x < BLOCK_WIDTH; x++) {
                int pixelX = blockStartX + x;
                int pixelY = blockStartY + y;
                
                if (pixelX < width && pixelY < height) {
                    int dstIndex = (pixelY * width + pixelX) * 4;
                    int srcIndex = (y * BLOCK_WIDTH + x) * 4;
                    
                    if (dstIndex + 3 < output.capacity()) {
                        // Use absolute put methods to write at specific indices
                        output.put(dstIndex, block[srcIndex]);         // R
                        output.put(dstIndex + 1, block[srcIndex + 1]); // G
                        output.put(dstIndex + 2, block[srcIndex + 2]); // B
                        output.put(dstIndex + 3, block[srcIndex + 3]); // A
                        
                        // Update the limit to track the highest position written
                        int endPos = dstIndex + 4;
                        if (endPos > output.position()) {
                            output.position(endPos);
                        }
                    }
                }
            }
        }
    }
    
    private void compressDXT1Block(byte[] block, ByteBuffer output, boolean hasAlpha) {
        // Find two representative colors using principal component analysis approach
        // First, calculate average color
        int avgR = 0, avgG = 0, avgB = 0;
        for (int i = 0; i < 16; i++) {
            int offset = i * 4;
            avgR += block[offset] & 0xFF;
            avgG += block[offset + 1] & 0xFF;
            avgB += block[offset + 2] & 0xFF;
        }
        avgR /= 16;
        avgG /= 16;
        avgB /= 16;
        
        // Find the colors furthest from average in opposite directions
        int maxDist = 0;
        int minDist = 0;
        int[] maxColor = {avgR, avgG, avgB};
        int[] minColor = {avgR, avgG, avgB};
        
        for (int i = 0; i < 16; i++) {
            int offset = i * 4;
            int r = block[offset] & 0xFF;
            int g = block[offset + 1] & 0xFF;
            int b = block[offset + 2] & 0xFF;
            
            int dr = r - avgR;
            int dg = g - avgG;
            int db = b - avgB;
            int dist = dr * dr + dg * dg + db * db;
            
            // Use signed distance to separate colors on opposite sides of average
            int signedDist = dr + dg + db;
            
            if (signedDist > maxDist || (signedDist == maxDist && dist > maxDist)) {
                maxDist = signedDist;
                maxColor[0] = r;
                maxColor[1] = g;
                maxColor[2] = b;
            }
            if (signedDist < minDist || (signedDist == minDist && dist > -minDist)) {
                minDist = signedDist;
                minColor[0] = r;
                minColor[1] = g;
                minColor[2] = b;
            }
        }
        
        // Convert to RGB565
        short color0 = toRGB565(maxColor[0], maxColor[1], maxColor[2]);
        short color1 = toRGB565(minColor[0], minColor[1], minColor[2]);
        
        // Ensure color0 > color1 for 4-color mode (unless we need 1-bit alpha)
        if (!hasAlpha && (color0 & 0xFFFF) < (color1 & 0xFFFF)) {
            short temp = color0;
            color0 = color1;
            color1 = temp;
        }
        
        output.putShort(color0);
        output.putShort(color1);
        
        // Calculate color indices
        int indices = 0;
        for (int i = 0; i < 16; i++) {
            int offset = i * 4;
            int r = block[offset] & 0xFF;
            int g = block[offset + 1] & 0xFF;
            int b = block[offset + 2] & 0xFF;
            
            int index = findClosestColorIndex(r, g, b, color0, color1, hasAlpha);
            indices |= (index << (i * 2));
        }
        
        output.putInt(indices);
    }
    
    private void compressDXT3Block(byte[] block, ByteBuffer output) {
        // Explicit alpha: 4 bits per pixel
        for (int i = 0; i < 16; i += 2) {
            int alpha0 = (block[i * 4 + 3] & 0xFF) >> 4;
            int alpha1 = (block[(i + 1) * 4 + 3] & 0xFF) >> 4;
            output.put((byte)((alpha1 << 4) | alpha0));
        }
        
        // Compress color block (same as DXT1)
        compressDXT1Block(block, output, false);
    }
    
    private void compressDXT5Block(byte[] block, ByteBuffer output) {
        // Find min/max alpha
        int minAlpha = 255;
        int maxAlpha = 0;
        
        for (int i = 0; i < 16; i++) {
            int alpha = block[i * 4 + 3] & 0xFF;
            minAlpha = Math.min(minAlpha, alpha);
            maxAlpha = Math.max(maxAlpha, alpha);
        }
        
        output.put((byte)maxAlpha);
        output.put((byte)minAlpha);
        
        // Calculate alpha indices (3 bits per pixel)
        long alphaIndices = 0;
        for (int i = 0; i < 16; i++) {
            int alpha = block[i * 4 + 3] & 0xFF;
            int index = findClosestAlphaIndex(alpha, maxAlpha, minAlpha);
            alphaIndices |= ((long)index << (i * 3));
        }
        
        // Write 48 bits of alpha indices
        for (int i = 0; i < 6; i++) {
            output.put((byte)(alphaIndices >> (i * 8)));
        }
        
        // Compress color block (same as DXT1)
        compressDXT1Block(block, output, false);
    }
    
    private void decompressDXT1Block(ByteBuffer input, byte[] block, boolean hasAlpha) {
        short color0 = input.getShort();
        short color1 = input.getShort();
        int indices = input.getInt();
        
        int[] colors = new int[4];
        colors[0] = fromRGB565(color0);
        colors[1] = fromRGB565(color1);
        
        // Compare as unsigned values
        if ((color0 & 0xFFFF) > (color1 & 0xFFFF) || !hasAlpha) {
            // 4-color mode
            colors[2] = interpolateColor(colors[0], colors[1], 2, 1);
            colors[3] = interpolateColor(colors[0], colors[1], 1, 2);
        } else {
            // 3-color mode with transparency
            colors[2] = interpolateColor(colors[0], colors[1], 1, 1);
            colors[3] = 0x00000000; // Transparent black
        }
        
        for (int i = 0; i < 16; i++) {
            int index = (indices >> (i * 2)) & 0x3;
            int color = colors[index];
            
            int offset = i * 4;
            block[offset] = (byte)((color >> 16) & 0xFF);     // R
            block[offset + 1] = (byte)((color >> 8) & 0xFF);  // G
            block[offset + 2] = (byte)(color & 0xFF);         // B
            block[offset + 3] = (byte)((color >> 24) & 0xFF); // A
        }
    }
    
    private void decompressDXT3Block(ByteBuffer input, byte[] block) {
        // Read explicit alpha
        for (int i = 0; i < 8; i++) {
            byte alphaByte = input.get();
            int alpha0 = (alphaByte & 0x0F) << 4;
            int alpha1 = alphaByte & 0xF0;
            
            // Fixed: Each iteration handles 2 pixels
            int pixelIndex = i * 2;
            if (pixelIndex * 4 + 3 < block.length) {
                block[pixelIndex * 4 + 3] = (byte)alpha0;
            }
            if ((pixelIndex + 1) * 4 + 3 < block.length) {
                block[(pixelIndex + 1) * 4 + 3] = (byte)alpha1;
            }
        }
        
        // Decompress color block
        decompressDXT1Block(input, block, false);
    }
    
    private void decompressDXT5Block(ByteBuffer input, byte[] block) {
        int alpha0 = input.get() & 0xFF;
        int alpha1 = input.get() & 0xFF;
        
        // Read 48 bits of alpha indices
        long alphaIndices = 0;
        for (int i = 0; i < 6; i++) {
            alphaIndices |= ((long)(input.get() & 0xFF)) << (i * 8);
        }
        
        // Calculate alpha palette
        int[] alphas = new int[8];
        alphas[0] = alpha0;
        alphas[1] = alpha1;
        
        if (alpha0 > alpha1) {
            // 8-alpha mode: 6 interpolated values
            alphas[2] = (6 * alpha0 + 1 * alpha1) / 7;
            alphas[3] = (5 * alpha0 + 2 * alpha1) / 7;
            alphas[4] = (4 * alpha0 + 3 * alpha1) / 7;
            alphas[5] = (3 * alpha0 + 4 * alpha1) / 7;
            alphas[6] = (2 * alpha0 + 5 * alpha1) / 7;
            alphas[7] = (1 * alpha0 + 6 * alpha1) / 7;
        } else {
            // 6-alpha mode: 4 interpolated values + 0 and 255
            alphas[2] = (4 * alpha0 + 1 * alpha1) / 5;
            alphas[3] = (3 * alpha0 + 2 * alpha1) / 5;
            alphas[4] = (2 * alpha0 + 3 * alpha1) / 5;
            alphas[5] = (1 * alpha0 + 4 * alpha1) / 5;
            alphas[6] = 0;
            alphas[7] = 255;
        }
        
        // Apply alpha values
        for (int i = 0; i < 16; i++) {
            int index = (int)((alphaIndices >> (i * 3)) & 0x7);
            block[i * 4 + 3] = (byte)alphas[index];
        }
        
        // Decompress color block
        decompressDXT1Block(input, block, false);
    }
    
    private short toRGB565(int r, int g, int b) {
        int r5 = (r * 31 + 127) / 255;
        int g6 = (g * 63 + 127) / 255;
        int b5 = (b * 31 + 127) / 255;
        return (short)((r5 << 11) | (g6 << 5) | b5);
    }
    
    private int fromRGB565(short rgb565) {
        int unsigned = rgb565 & 0xFFFF;
        int r = ((unsigned >> 11) & 0x1F) * 255 / 31;
        int g = ((unsigned >> 5) & 0x3F) * 255 / 63;
        int b = (unsigned & 0x1F) * 255 / 31;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
    
    private int interpolateColor(int c0, int c1, int w0, int w1) {
        int total = w0 + w1;
        int r = (((c0 >> 16) & 0xFF) * w0 + ((c1 >> 16) & 0xFF) * w1) / total;
        int g = (((c0 >> 8) & 0xFF) * w0 + ((c1 >> 8) & 0xFF) * w1) / total;
        int b = ((c0 & 0xFF) * w0 + (c1 & 0xFF) * w1) / total;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
    
    private int findClosestColorIndex(int r, int g, int b, short color0, short color1, boolean hasAlpha) {
        int c0 = fromRGB565(color0);
        int c1 = fromRGB565(color1);
        
        int[] colors = new int[4];
        colors[0] = c0;
        colors[1] = c1;
        
        if ((color0 & 0xFFFF) > (color1 & 0xFFFF) || !hasAlpha) {
            colors[2] = interpolateColor(c0, c1, 2, 1);
            colors[3] = interpolateColor(c0, c1, 1, 2);
        } else {
            colors[2] = interpolateColor(c0, c1, 1, 1);
            colors[3] = 0;
        }
        
        int bestIndex = 0;
        int bestDistance = Integer.MAX_VALUE;
        
        for (int i = 0; i < 4; i++) {
            int cr = (colors[i] >> 16) & 0xFF;
            int cg = (colors[i] >> 8) & 0xFF;
            int cb = colors[i] & 0xFF;
            
            int distance = (r - cr) * (r - cr) + (g - cg) * (g - cg) + (b - cb) * (b - cb);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = i;
            }
        }
        
        return bestIndex;
    }
    
    private int findClosestAlphaIndex(int alpha, int alpha0, int alpha1) {
        int[] alphas = new int[8];
        alphas[0] = alpha0;
        alphas[1] = alpha1;
        
        if (alpha0 > alpha1) {
            // 8-alpha mode: 6 interpolated values
            alphas[2] = (6 * alpha0 + 1 * alpha1) / 7;
            alphas[3] = (5 * alpha0 + 2 * alpha1) / 7;
            alphas[4] = (4 * alpha0 + 3 * alpha1) / 7;
            alphas[5] = (3 * alpha0 + 4 * alpha1) / 7;
            alphas[6] = (2 * alpha0 + 5 * alpha1) / 7;
            alphas[7] = (1 * alpha0 + 6 * alpha1) / 7;
        } else {
            // 6-alpha mode: 4 interpolated values + 0 and 255
            alphas[2] = (4 * alpha0 + 1 * alpha1) / 5;
            alphas[3] = (3 * alpha0 + 2 * alpha1) / 5;
            alphas[4] = (2 * alpha0 + 3 * alpha1) / 5;
            alphas[5] = (1 * alpha0 + 4 * alpha1) / 5;
            alphas[6] = 0;
            alphas[7] = 255;
        }
        
        int bestIndex = 0;
        int bestDistance = Integer.MAX_VALUE;
        
        for (int i = 0; i < 8; i++) {
            int distance = Math.abs(alpha - alphas[i]);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = i;
            }
        }
        
        return bestIndex;
    }
}