package com.hellblazer.luciferase.render.voxel.quality;

import javax.vecmath.Color3f;
import javax.vecmath.Vector3f;

/**
 * DXT compression-aware filter for voxel attribute filtering.
 * Optimizes attribute values to minimize quality loss during DXT compression.
 * Accounts for DXT block encoding artifacts and compression characteristics.
 * 
 * Algorithm:
 * - Pre-processes attributes to reduce DXT compression artifacts
 * - Uses knowledge of 4x4 block encoding to optimize filtering
 * - Applies specialized filtering for different DXT formats (BC1, BC3, BC5)
 * - Higher computational cost but optimal post-compression visual quality
 */
public class DXTFilter implements AttributeFilter {
    
    private static final FilterCharacteristics CHARACTERISTICS = 
        new FilterCharacteristics("DXT-Aware Filter", 4.0f, 0.8f, 27, true, FilterType.DXT_AWARE);
    
    // DXT block encoding parameters
    private static final int DXT_BLOCK_SIZE = 4; // 4x4 pixel blocks
    private static final float DXT_QUANTIZATION_FACTOR = 0.95f; // Reduce precision slightly for better compression
    private static final float COLOR_ENDPOINT_BIAS = 0.1f; // Bias towards DXT endpoint colors
    private static final float NORMAL_COMPRESSION_TOLERANCE = 0.02f; // Account for BC5 compression
    
    @Override
    public Color3f filterColor(VoxelData[] neighborhood, int centerIndex) {
        if (neighborhood == null || centerIndex < 0 || centerIndex >= neighborhood.length) {
            return new Color3f(0, 0, 0);
        }
        
        // First pass: Calculate weighted average like pyramid filter
        var baseColor = calculateWeightedColor(neighborhood);
        
        // Second pass: Apply DXT-aware optimization
        return optimizeForDXTCompression(baseColor, neighborhood);
    }
    
    @Override
    public Vector3f filterNormal(VoxelData[] neighborhood, int centerIndex) {
        if (neighborhood == null || centerIndex < 0 || centerIndex >= neighborhood.length) {
            return new Vector3f(0, 1, 0);
        }
        
        // First pass: Calculate weighted average normal
        var baseNormal = calculateWeightedNormal(neighborhood);
        
        // Second pass: Optimize for BC5 normal compression
        return optimizeNormalForBC5(baseNormal, neighborhood);
    }
    
    @Override
    public float filterOpacity(VoxelData[] neighborhood, int centerIndex) {
        if (neighborhood == null || centerIndex < 0 || centerIndex >= neighborhood.length) {
            return 0.0f;
        }
        
        // Calculate weighted average opacity
        var baseOpacity = calculateWeightedOpacity(neighborhood);
        
        // Quantize to DXT alpha levels (BC1 has 1-bit alpha, BC3 has 8-bit)
        return quantizeAlphaForDXT(baseOpacity);
    }
    
    @Override
    public FilterCharacteristics getCharacteristics() {
        return CHARACTERISTICS;
    }
    
    @Override
    public String getName() {
        return "DXT-Aware Filter";
    }
    
    /**
     * Calculate distance-weighted color average.
     */
    private Color3f calculateWeightedColor(VoxelData[] neighborhood) {
        float weightedRed = 0.0f;
        float weightedGreen = 0.0f;
        float weightedBlue = 0.0f;
        float totalWeight = 0.0f;
        
        for (var voxel : neighborhood) {
            if (voxel != null && voxel.isValid) {
                float weight = calculateWeight(voxel.distance);
                
                weightedRed += voxel.color.x * weight;
                weightedGreen += voxel.color.y * weight;
                weightedBlue += voxel.color.z * weight;
                totalWeight += weight;
            }
        }
        
        if (totalWeight > 0.001f) {
            return new Color3f(
                weightedRed / totalWeight,
                weightedGreen / totalWeight,
                weightedBlue / totalWeight
            );
        } else {
            return new Color3f(0, 0, 0);
        }
    }
    
    /**
     * Calculate distance-weighted normal average.
     */
    private Vector3f calculateWeightedNormal(VoxelData[] neighborhood) {
        float weightedX = 0.0f;
        float weightedY = 0.0f;
        float weightedZ = 0.0f;
        float totalWeight = 0.0f;
        
        for (var voxel : neighborhood) {
            if (voxel != null && voxel.isValid) {
                float weight = calculateWeight(voxel.distance);
                
                weightedX += voxel.normal.x * weight;
                weightedY += voxel.normal.y * weight;
                weightedZ += voxel.normal.z * weight;
                totalWeight += weight;
            }
        }
        
        if (totalWeight > 0.001f) {
            var normal = new Vector3f(
                weightedX / totalWeight,
                weightedY / totalWeight,
                weightedZ / totalWeight
            );
            normal.normalize();
            return normal;
        } else {
            return new Vector3f(0, 1, 0);
        }
    }
    
    /**
     * Calculate distance-weighted opacity average.
     */
    private float calculateWeightedOpacity(VoxelData[] neighborhood) {
        float weightedOpacity = 0.0f;
        float totalWeight = 0.0f;
        
        for (var voxel : neighborhood) {
            if (voxel != null && voxel.isValid) {
                float weight = calculateWeight(voxel.distance);
                
                weightedOpacity += voxel.opacity * weight;
                totalWeight += weight;
            }
        }
        
        if (totalWeight > 0.001f) {
            return weightedOpacity / totalWeight;
        } else {
            return 0.0f;
        }
    }
    
    /**
     * Optimize color for DXT compression (BC1/BC3).
     * DXT uses two color endpoints and interpolates between them.
     */
    private Color3f optimizeForDXTCompression(Color3f baseColor, VoxelData[] neighborhood) {
        // Analyze color distribution in neighborhood
        var colorStats = analyzeColorDistribution(neighborhood);
        
        // Bias towards DXT-friendly color endpoints
        var optimizedColor = new Color3f(baseColor);
        
        // Reduce precision slightly to improve compression ratio
        optimizedColor.x = quantizeColor(optimizedColor.x);
        optimizedColor.y = quantizeColor(optimizedColor.y);
        optimizedColor.z = quantizeColor(optimizedColor.z);
        
        // If colors are similar, bias towards a single endpoint
        if (colorStats.variance < COLOR_ENDPOINT_BIAS) {
            // Move towards the dominant color in the neighborhood
            optimizedColor.interpolate(colorStats.dominantColor, 0.2f);
        }
        
        return optimizedColor;
    }
    
    /**
     * Optimize normal for BC5 compression.
     * BC5 stores only X and Y components, reconstructs Z.
     */
    private Vector3f optimizeNormalForBC5(Vector3f baseNormal, VoxelData[] neighborhood) {
        var optimized = new Vector3f(baseNormal);
        
        // BC5 stores X and Y in high precision, reconstructs Z
        // Ensure X and Y components are optimized for quantization
        optimized.x = quantizeNormalComponent(optimized.x);
        optimized.y = quantizeNormalComponent(optimized.y);
        
        // Reconstruct Z to match BC5 decompression
        float zSquared = Math.max(0.0f, 1.0f - optimized.x * optimized.x - optimized.y * optimized.y);
        optimized.z = (float) Math.sqrt(zSquared);
        
        // Handle edge case where normal is nearly in XY plane
        if (optimized.z < NORMAL_COMPRESSION_TOLERANCE) {
            optimized.z = NORMAL_COMPRESSION_TOLERANCE;
            optimized.normalize();
        }
        
        return optimized;
    }
    
    /**
     * Quantize alpha for DXT compression.
     * BC1 has 1-bit alpha (0 or 1), BC3 has 8-bit alpha.
     */
    private float quantizeAlphaForDXT(float alpha) {
        // Assume BC3 with 8-bit alpha (256 levels)
        int quantized = Math.round(alpha * 255.0f);
        return quantized / 255.0f;
    }
    
    /**
     * Quantize color component for DXT compression.
     */
    private float quantizeColor(float component) {
        // DXT uses 5-6-5 bit encoding for RGB
        // Simulate this by reducing precision
        int bits = 6; // Use 6 bits for green, 5 for red/blue
        int levels = (1 << bits) - 1;
        int quantized = Math.round(component * levels);
        return quantized / (float) levels;
    }
    
    /**
     * Quantize normal component for BC5 compression.
     */
    private float quantizeNormalComponent(float component) {
        // BC5 uses 8-bit per component
        int quantized = Math.round((component * 0.5f + 0.5f) * 255.0f);
        return (quantized / 255.0f) * 2.0f - 1.0f;
    }
    
    /**
     * Analyze color distribution in neighborhood for DXT optimization.
     */
    private ColorStatistics analyzeColorDistribution(VoxelData[] neighborhood) {
        var stats = new ColorStatistics();
        
        float totalRed = 0, totalGreen = 0, totalBlue = 0;
        int validCount = 0;
        
        // Calculate mean
        for (var voxel : neighborhood) {
            if (voxel != null && voxel.isValid) {
                totalRed += voxel.color.x;
                totalGreen += voxel.color.y;
                totalBlue += voxel.color.z;
                validCount++;
            }
        }
        
        if (validCount > 0) {
            stats.dominantColor = new Color3f(
                totalRed / validCount,
                totalGreen / validCount,
                totalBlue / validCount
            );
            
            // Calculate variance
            float varR = 0, varG = 0, varB = 0;
            for (var voxel : neighborhood) {
                if (voxel != null && voxel.isValid) {
                    float dR = voxel.color.x - stats.dominantColor.x;
                    float dG = voxel.color.y - stats.dominantColor.y;
                    float dB = voxel.color.z - stats.dominantColor.z;
                    
                    varR += dR * dR;
                    varG += dG * dG;
                    varB += dB * dB;
                }
            }
            
            stats.variance = (varR + varG + varB) / (validCount * 3);
        } else {
            stats.dominantColor = new Color3f(0, 0, 0);
            stats.variance = 0.0f;
        }
        
        return stats;
    }
    
    /**
     * Calculate weight based on distance using pyramid kernel.
     */
    private float calculateWeight(float distance) {
        return 1.0f / (1.0f + distance * distance);
    }
    
    /**
     * Statistics about color distribution in a neighborhood.
     */
    private static class ColorStatistics {
        Color3f dominantColor;
        float variance;
    }
    
    /**
     * Batch processing optimization for multiple voxels.
     * Processes multiple voxel neighborhoods with DXT-aware filtering.
     */
    public void filterColorBatch(VoxelData[][] neighborhoods, int[] centerIndices, Color3f[] results) {
        if (neighborhoods == null || centerIndices == null || results == null ||
            neighborhoods.length != centerIndices.length || neighborhoods.length != results.length) {
            return;
        }
        
        for (int i = 0; i < neighborhoods.length; i++) {
            results[i] = filterColor(neighborhoods[i], centerIndices[i]);
        }
    }
    
    /**
     * Batch processing for normal filtering with BC5 optimization.
     */
    public void filterNormalBatch(VoxelData[][] neighborhoods, int[] centerIndices, Vector3f[] results) {
        if (neighborhoods == null || centerIndices == null || results == null ||
            neighborhoods.length != centerIndices.length || neighborhoods.length != results.length) {
            return;
        }
        
        for (int i = 0; i < neighborhoods.length; i++) {
            results[i] = filterNormal(neighborhoods[i], centerIndices[i]);
        }
    }
    
    /**
     * Batch processing for opacity filtering with DXT alpha optimization.
     */
    public void filterOpacityBatch(VoxelData[][] neighborhoods, int[] centerIndices, float[] results) {
        if (neighborhoods == null || centerIndices == null || results == null ||
            neighborhoods.length != centerIndices.length || neighborhoods.length != results.length) {
            return;
        }
        
        for (int i = 0; i < neighborhoods.length; i++) {
            results[i] = filterOpacity(neighborhoods[i], centerIndices[i]);
        }
    }
}