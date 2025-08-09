// Filter and mipmap generation shader for ESVO
// Creates filtered voxel data and generates mipmap levels

struct VoxelData {
    color: vec4<f32>,
    normal: vec3<f32>,
    opacity: f32,
    materialId: u32
}

struct FilterParams {
    sourceLevel: u32,
    targetLevel: u32,
    filterType: u32, // 0 = box, 1 = gaussian
    filterRadius: f32
}

// Bind groups
@group(0) @binding(0) var<storage, read> sourceVoxels: array<u32>;
@group(0) @binding(1) var<storage, write> targetVoxels: array<u32>;
@group(0) @binding(2) var<uniform> params: FilterParams;
@group(0) @binding(3) var<storage, read> octreeNodes: array<u64>;

// Constants
const GAUSSIAN_KERNEL_3x3: array<f32, 9> = array<f32, 9>(
    0.0625, 0.125, 0.0625,
    0.125,  0.25,  0.125,
    0.0625, 0.125, 0.0625
);

// Unpack color from RGBA8
fn unpackColor(packed: u32) -> vec4<f32> {
    let r = f32((packed >> 24u) & 0xFFu) / 255.0;
    let g = f32((packed >> 16u) & 0xFFu) / 255.0;
    let b = f32((packed >> 8u) & 0xFFu) / 255.0;
    let a = f32(packed & 0xFFu) / 255.0;
    return vec4<f32>(r, g, b, a);
}

// Pack color to RGBA8
fn packColor(color: vec4<f32>) -> u32 {
    let r = u32(saturate(color.r) * 255.0);
    let g = u32(saturate(color.g) * 255.0);
    let b = u32(saturate(color.b) * 255.0);
    let a = u32(saturate(color.a) * 255.0);
    return (r << 24u) | (g << 16u) | (b << 8u) | a;
}

// Unpack normal from 11-11-10 format
fn unpackNormal(packed: u32) -> vec3<f32> {
    let x = f32((packed >> 21u) & 0x7FFu) / 2047.0;
    let y = f32((packed >> 10u) & 0x7FFu) / 2047.0;
    let z = f32(packed & 0x3FFu) / 1023.0;
    return normalize(vec3<f32>(x, y, z) * 2.0 - 1.0);
}

// Pack normal to 11-11-10 format
fn packNormal(normal: vec3<f32>) -> u32 {
    let n = normalize(normal) * 0.5 + 0.5;
    let x = u32(n.x * 2047.0);
    let y = u32(n.y * 2047.0);
    let z = u32(n.z * 1023.0);
    return (x << 21u) | (y << 10u) | z;
}

// Get voxel index for given level and position
fn getVoxelIndex(level: u32, pos: vec3<u32>) -> u32 {
    let resolution = 1u << level;
    if (any(pos >= vec3<u32>(resolution))) {
        return 0xFFFFFFFFu;
    }
    return pos.x + pos.y * resolution + pos.z * resolution * resolution;
}

// Box filter for 2x2x2 voxels
fn boxFilter(level: u32, targetPos: vec3<u32>) -> VoxelData {
    let sourcePos = targetPos * 2u;
    var accum = VoxelData(vec4<f32>(0.0), vec3<f32>(0.0), 0.0, 0u);
    var count = 0.0;
    
    // Sample 2x2x2 neighborhood
    for (var dz = 0u; dz < 2u; dz++) {
        for (var dy = 0u; dy < 2u; dy++) {
            for (var dx = 0u; dx < 2u; dx++) {
                let samplePos = sourcePos + vec3<u32>(dx, dy, dz);
                let idx = getVoxelIndex(level, samplePos);
                
                if (idx != 0xFFFFFFFFu && idx < arrayLength(&sourceVoxels)) {
                    let colorPacked = sourceVoxels[idx * 2u];
                    let normalPacked = sourceVoxels[idx * 2u + 1u];
                    
                    if (colorPacked != 0u) {
                        let color = unpackColor(colorPacked);
                        let normal = unpackNormal(normalPacked);
                        
                        accum.color += color;
                        accum.normal += normal;
                        accum.opacity += color.a;
                        count += 1.0;
                    }
                }
            }
        }
    }
    
    // Average the accumulated values
    if (count > 0.0) {
        accum.color /= count;
        accum.normal = normalize(accum.normal);
        accum.opacity /= count;
    }
    
    return accum;
}

// Gaussian filter for smoother results
fn gaussianFilter(level: u32, targetPos: vec3<u32>) -> VoxelData {
    let sourcePos = targetPos * 2u;
    var accum = VoxelData(vec4<f32>(0.0), vec3<f32>(0.0), 0.0, 0u);
    var totalWeight = 0.0;
    
    // 3x3x3 Gaussian kernel
    for (var dz = -1i; dz <= 1i; dz++) {
        for (var dy = -1i; dy <= 1i; dy++) {
            for (var dx = -1i; dx <= 1i; dx++) {
                let offset = vec3<i32>(dx, dy, dz);
                let samplePos = vec3<i32>(sourcePos) + offset;
                
                if (all(samplePos >= vec3<i32>(0))) {
                    let idx = getVoxelIndex(level, vec3<u32>(samplePos));
                    
                    if (idx != 0xFFFFFFFFu && idx < arrayLength(&sourceVoxels)) {
                        let colorPacked = sourceVoxels[idx * 2u];
                        let normalPacked = sourceVoxels[idx * 2u + 1u];
                        
                        if (colorPacked != 0u) {
                            // Compute 3D Gaussian weight
                            let dist2 = dot(vec3<f32>(offset), vec3<f32>(offset));
                            let weight = exp(-dist2 / (2.0 * params.filterRadius * params.filterRadius));
                            
                            let color = unpackColor(colorPacked);
                            let normal = unpackNormal(normalPacked);
                            
                            accum.color += color * weight;
                            accum.normal += normal * weight;
                            accum.opacity += color.a * weight;
                            totalWeight += weight;
                        }
                    }
                }
            }
        }
    }
    
    // Normalize by total weight
    if (totalWeight > 0.0) {
        accum.color /= totalWeight;
        accum.normal = normalize(accum.normal);
        accum.opacity /= totalWeight;
    }
    
    return accum;
}

// Generate mipmap level
@compute @workgroup_size(8, 8, 8)
fn generateMipmap(@builtin(global_invocation_id) id: vec3<u32>) {
    let targetPos = id;
    let targetResolution = 1u << params.targetLevel;
    
    if (any(targetPos >= vec3<u32>(targetResolution))) {
        return;
    }
    
    // Apply selected filter
    var filtered: VoxelData;
    if (params.filterType == 0u) {
        filtered = boxFilter(params.sourceLevel, targetPos);
    } else {
        filtered = gaussianFilter(params.sourceLevel, targetPos);
    }
    
    // Only write non-empty voxels
    if (filtered.opacity > 0.01) {
        let idx = getVoxelIndex(params.targetLevel, targetPos);
        if (idx != 0xFFFFFFFFu && idx * 2u + 1u < arrayLength(&targetVoxels)) {
            targetVoxels[idx * 2u] = packColor(filtered.color);
            targetVoxels[idx * 2u + 1u] = packNormal(filtered.normal);
        }
    }
}

// Apply bilateral filter for edge-preserving smoothing
@compute @workgroup_size(8, 8, 8)
fn bilateralFilter(@builtin(global_invocation_id) id: vec3<u32>) {
    let pos = id;
    let resolution = 1u << params.sourceLevel;
    
    if (any(pos >= vec3<u32>(resolution))) {
        return;
    }
    
    let centerIdx = getVoxelIndex(params.sourceLevel, pos);
    if (centerIdx == 0xFFFFFFFFu || centerIdx * 2u + 1u >= arrayLength(&sourceVoxels)) {
        return;
    }
    
    let centerColor = unpackColor(sourceVoxels[centerIdx * 2u]);
    let centerNormal = unpackNormal(sourceVoxels[centerIdx * 2u + 1u]);
    
    if (centerColor.a < 0.01) {
        return; // Empty voxel
    }
    
    var accumColor = vec4<f32>(0.0);
    var accumNormal = vec3<f32>(0.0);
    var totalWeight = 0.0;
    
    // Bilateral filter kernel
    let radius = i32(params.filterRadius);
    for (var dz = -radius; dz <= radius; dz++) {
        for (var dy = -radius; dy <= radius; dy++) {
            for (var dx = -radius; dx <= radius; dx++) {
                let offset = vec3<i32>(dx, dy, dz);
                let samplePos = vec3<i32>(pos) + offset;
                
                if (all(samplePos >= vec3<i32>(0)) && all(samplePos < vec3<i32>(resolution))) {
                    let sampleIdx = getVoxelIndex(params.sourceLevel, vec3<u32>(samplePos));
                    
                    if (sampleIdx != 0xFFFFFFFFu && sampleIdx * 2u + 1u < arrayLength(&sourceVoxels)) {
                        let sampleColor = unpackColor(sourceVoxels[sampleIdx * 2u]);
                        let sampleNormal = unpackNormal(sourceVoxels[sampleIdx * 2u + 1u]);
                        
                        if (sampleColor.a > 0.01) {
                            // Spatial weight
                            let spatialDist2 = dot(vec3<f32>(offset), vec3<f32>(offset));
                            let spatialWeight = exp(-spatialDist2 / (2.0 * params.filterRadius * params.filterRadius));
                            
                            // Range weight (color similarity)
                            let colorDiff = centerColor.rgb - sampleColor.rgb;
                            let colorDist2 = dot(colorDiff, colorDiff);
                            let rangeWeight = exp(-colorDist2 / 0.1);
                            
                            // Normal weight (surface continuity)
                            let normalDot = dot(centerNormal, sampleNormal);
                            let normalWeight = pow(max(normalDot, 0.0), 8.0);
                            
                            // Combined weight
                            let weight = spatialWeight * rangeWeight * normalWeight;
                            
                            accumColor += sampleColor * weight;
                            accumNormal += sampleNormal * weight;
                            totalWeight += weight;
                        }
                    }
                }
            }
        }
    }
    
    // Write filtered result
    if (totalWeight > 0.0) {
        let filteredColor = accumColor / totalWeight;
        let filteredNormal = normalize(accumNormal);
        
        let targetIdx = getVoxelIndex(params.targetLevel, pos);
        if (targetIdx != 0xFFFFFFFFu && targetIdx * 2u + 1u < arrayLength(&targetVoxels)) {
            targetVoxels[targetIdx * 2u] = packColor(filteredColor);
            targetVoxels[targetIdx * 2u + 1u] = packNormal(filteredNormal);
        }
    }
}