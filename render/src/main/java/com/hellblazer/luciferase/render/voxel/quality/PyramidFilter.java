package com.hellblazer.luciferase.render.voxel.quality;

import javax.vecmath.Color3f;
import javax.vecmath.Vector3f;

/**
 * Pyramid filter implementation for voxel attribute filtering.
 * Applies distance-weighted filtering to voxel attributes, giving higher weight to closer neighbors.
 * Provides better quality than box filter while maintaining reasonable performance.
 * 
 * Algorithm:
 * - Weight = 1 / (1 + distance^2) for each neighbor
 * - Weighted average of all valid neighboring voxels
 * - Center voxel receives highest weight due to zero distance
 * - Smooth falloff preserves important details while reducing noise
 */
public class PyramidFilter implements AttributeFilter {
    
    private static final FilterCharacteristics CHARACTERISTICS = 
        new FilterCharacteristics("Pyramid Filter", 2.5f, 0.6f, 27, true, FilterType.PYRAMID);
    
    // Distance weighting parameters
    private static final float DISTANCE_SCALE = 1.0f;
    private static final float MIN_WEIGHT = 0.001f; // Minimum weight to prevent division by zero issues
    
    @Override
    public Color3f filterColor(VoxelData[] neighborhood, int centerIndex) {
        if (neighborhood == null || centerIndex < 0 || centerIndex >= neighborhood.length) {
            return new Color3f(0, 0, 0);
        }
        
        float weightedRed = 0.0f;
        float weightedGreen = 0.0f;
        float weightedBlue = 0.0f;
        float totalWeight = 0.0f;
        
        // Calculate weighted sum based on distance
        for (var voxel : neighborhood) {
            if (voxel != null && voxel.isValid) {
                float weight = calculateWeight(voxel.distance);
                
                weightedRed += voxel.color.x * weight;
                weightedGreen += voxel.color.y * weight;
                weightedBlue += voxel.color.z * weight;
                totalWeight += weight;
            }
        }
        
        // Return weighted average if we have valid voxels
        if (totalWeight > MIN_WEIGHT) {
            return new Color3f(
                weightedRed / totalWeight,
                weightedGreen / totalWeight,
                weightedBlue / totalWeight
            );
        } else {
            // Fallback to center voxel
            return new Color3f(neighborhood[centerIndex].color);
        }
    }
    
    @Override
    public Vector3f filterNormal(VoxelData[] neighborhood, int centerIndex) {
        if (neighborhood == null || centerIndex < 0 || centerIndex >= neighborhood.length) {
            return new Vector3f(0, 1, 0);
        }
        
        float weightedX = 0.0f;
        float weightedY = 0.0f;
        float weightedZ = 0.0f;
        float totalWeight = 0.0f;
        
        // Calculate weighted sum based on distance
        for (var voxel : neighborhood) {
            if (voxel != null && voxel.isValid) {
                float weight = calculateWeight(voxel.distance);
                
                weightedX += voxel.normal.x * weight;
                weightedY += voxel.normal.y * weight;
                weightedZ += voxel.normal.z * weight;
                totalWeight += weight;
            }
        }
        
        // Return weighted average normal if we have valid voxels
        if (totalWeight > MIN_WEIGHT) {
            var weightedNormal = new Vector3f(
                weightedX / totalWeight,
                weightedY / totalWeight,
                weightedZ / totalWeight
            );
            
            // Normalize the weighted normal
            float length = weightedNormal.length();
            if (length > 0.001f) {
                weightedNormal.scale(1.0f / length);
            } else {
                // If weighted normal is zero vector, use center normal
                weightedNormal.set(neighborhood[centerIndex].normal);
            }
            
            return weightedNormal;
        } else {
            return new Vector3f(neighborhood[centerIndex].normal);
        }
    }
    
    @Override
    public float filterOpacity(VoxelData[] neighborhood, int centerIndex) {
        if (neighborhood == null || centerIndex < 0 || centerIndex >= neighborhood.length) {
            return 0.0f;
        }
        
        float weightedOpacity = 0.0f;
        float totalWeight = 0.0f;
        
        // Calculate weighted sum based on distance
        for (var voxel : neighborhood) {
            if (voxel != null && voxel.isValid) {
                float weight = calculateWeight(voxel.distance);
                
                weightedOpacity += voxel.opacity * weight;
                totalWeight += weight;
            }
        }
        
        // Return weighted average if we have valid voxels
        if (totalWeight > MIN_WEIGHT) {
            return weightedOpacity / totalWeight;
        } else {
            return neighborhood[centerIndex].opacity;
        }
    }
    
    @Override
    public FilterCharacteristics getCharacteristics() {
        return CHARACTERISTICS;
    }
    
    @Override
    public String getName() {
        return "Pyramid Filter";
    }
    
    /**
     * Calculate weight based on distance using pyramid kernel.
     * Weight = 1 / (1 + (distance * scale)^2)
     * 
     * @param distance Distance from center voxel
     * @return Weight value (higher for closer voxels)
     */
    private float calculateWeight(float distance) {
        float scaledDistance = distance * DISTANCE_SCALE;
        return 1.0f / (1.0f + scaledDistance * scaledDistance);
    }
    
    /**
     * Advanced pyramid filter with configurable falloff.
     * Allows customization of the distance weighting function.
     */
    public static class ConfigurablePyramidFilter implements AttributeFilter {
        private final float distanceScale;
        private final float falloffPower;
        private final FilterCharacteristics characteristics;
        
        public ConfigurablePyramidFilter(float distanceScale, float falloffPower) {
            this.distanceScale = Math.max(0.1f, distanceScale);
            this.falloffPower = Math.max(0.5f, falloffPower);
            
            // Adjust characteristics based on configuration
            float computationalCost = 2.5f + (falloffPower - 2.0f) * 0.5f;
            float qualityImprovement = Math.min(0.8f, 0.6f + (falloffPower - 2.0f) * 0.1f);
            
            this.characteristics = new FilterCharacteristics(
                String.format("Configurable Pyramid Filter (scale=%.2f, power=%.2f)", 
                    distanceScale, falloffPower),
                computationalCost, qualityImprovement, 27, true, FilterType.PYRAMID);
        }
        
        @Override
        public Color3f filterColor(VoxelData[] neighborhood, int centerIndex) {
            return applyConfigurableFilter(neighborhood, centerIndex, 
                (voxel, weight) -> new Vector3f(voxel.color.x, voxel.color.y, voxel.color.z),
                (result) -> new Color3f(result.x, result.y, result.z));
        }
        
        @Override
        public Vector3f filterNormal(VoxelData[] neighborhood, int centerIndex) {
            var result = applyConfigurableFilter(neighborhood, centerIndex,
                (voxel, weight) -> new Vector3f(voxel.normal),
                (weightedResult) -> {
                    var normal = new Vector3f(weightedResult);
                    normal.normalize();
                    return normal;
                });
            return result;
        }
        
        @Override
        public float filterOpacity(VoxelData[] neighborhood, int centerIndex) {
            var result = applyConfigurableFilter(neighborhood, centerIndex,
                (voxel, weight) -> new Vector3f(voxel.opacity, 0, 0),
                (weightedResult) -> new Vector3f(weightedResult.x, 0, 0));
            return result.x;
        }
        
        @Override
        public FilterCharacteristics getCharacteristics() {
            return characteristics;
        }
        
        @Override
        public String getName() {
            return characteristics.name;
        }
        
        private <T> T applyConfigurableFilter(VoxelData[] neighborhood, int centerIndex,
                                            BiFunction<VoxelData, Float, Vector3f> extractor,
                                            Function<Vector3f, T> constructor) {
            if (neighborhood == null || centerIndex < 0 || centerIndex >= neighborhood.length) {
                return constructor.apply(new Vector3f(0, 0, 0));
            }
            
            float weightedX = 0.0f;
            float weightedY = 0.0f;
            float weightedZ = 0.0f;
            float totalWeight = 0.0f;
            
            for (var voxel : neighborhood) {
                if (voxel != null && voxel.isValid) {
                    float weight = calculateConfigurableWeight(voxel.distance);
                    var value = extractor.apply(voxel, weight);
                    
                    weightedX += value.x * weight;
                    weightedY += value.y * weight;
                    weightedZ += value.z * weight;
                    totalWeight += weight;
                }
            }
            
            if (totalWeight > MIN_WEIGHT) {
                return constructor.apply(new Vector3f(
                    weightedX / totalWeight,
                    weightedY / totalWeight,
                    weightedZ / totalWeight
                ));
            } else {
                return constructor.apply(extractor.apply(neighborhood[centerIndex], 1.0f));
            }
        }
        
        private float calculateConfigurableWeight(float distance) {
            float scaledDistance = distance * distanceScale;
            return 1.0f / (1.0f + (float) Math.pow(scaledDistance, falloffPower));
        }
    }
    
    // Functional interfaces for the configurable filter
    @FunctionalInterface
    private interface BiFunction<T, U, R> {
        R apply(T t, U u);
    }
    
    @FunctionalInterface
    private interface Function<T, R> {
        R apply(T t);
    }
}