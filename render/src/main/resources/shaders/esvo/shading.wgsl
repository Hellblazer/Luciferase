// Deferred shading compute shader for voxel rendering
// Applies lighting, shadows, and post-processing effects

struct LightingParams {
    sunDirection: vec3<f32>,
    sunIntensity: f32,
    sunColor: vec3<f32>,
    ambientIntensity: f32,
    ambientColor: vec3<f32>,
    fogDensity: f32,
    fogColor: vec3<f32>,
    shadowBias: f32,
}

struct GBuffer {
    position: vec3<f32>,
    normal: vec3<f32>,
    albedo: vec3<f32>,
    depth: f32,
    materialId: u32,
    occlusion: f32,
}

@group(0) @binding(0) var<storage, read> gBuffer: array<GBuffer>;
@group(0) @binding(1) var<storage, read_write> frameBuffer: array<vec4<f32>>;
@group(0) @binding(2) var<uniform> lighting: LightingParams;
@group(0) @binding(3) var<uniform> cameraPosition: vec3<f32>;

// Shadow map for directional light
@group(1) @binding(0) var shadowMap: texture_2d<f32>;
@group(1) @binding(1) var<uniform> shadowViewProj: mat4x4<f32>;

// Environment maps for IBL
@group(2) @binding(0) var envMap: texture_cube<f32>;
@group(2) @binding(1) var envSampler: sampler;

// Material properties
fn getMaterialProperties(materialId: u32) -> vec3<f32> {
    // Returns (roughness, metallic, specular)
    switch (materialId) {
        case 0u: { return vec3<f32>(0.8, 0.0, 0.04); } // Diffuse
        case 1u: { return vec3<f32>(0.2, 0.9, 0.9); }  // Metal
        case 2u: { return vec3<f32>(0.1, 0.0, 0.5); }  // Glossy
        case 3u: { return vec3<f32>(0.9, 0.0, 0.02); } // Rough
        default: { return vec3<f32>(0.5, 0.0, 0.04); }
    }
}

// Fresnel-Schlick approximation
fn fresnelSchlick(cosTheta: f32, F0: vec3<f32>) -> vec3<f32> {
    return F0 + (1.0 - F0) * pow(1.0 - cosTheta, 5.0);
}

// GGX/Trowbridge-Reitz normal distribution
fn distributionGGX(N: vec3<f32>, H: vec3<f32>, roughness: f32) -> f32 {
    let a = roughness * roughness;
    let a2 = a * a;
    let NdotH = max(dot(N, H), 0.0);
    let NdotH2 = NdotH * NdotH;
    
    let num = a2;
    let denom = NdotH2 * (a2 - 1.0) + 1.0;
    let denom2 = denom * denom * 3.14159265;
    
    return num / denom2;
}

// Geometry function (Smith's method)
fn geometrySchlickGGX(NdotV: f32, roughness: f32) -> f32 {
    let r = roughness + 1.0;
    let k = (r * r) / 8.0;
    
    let num = NdotV;
    let denom = NdotV * (1.0 - k) + k;
    
    return num / denom;
}

fn geometrySmith(N: vec3<f32>, V: vec3<f32>, L: vec3<f32>, roughness: f32) -> f32 {
    let NdotV = max(dot(N, V), 0.0);
    let NdotL = max(dot(N, L), 0.0);
    let ggx2 = geometrySchlickGGX(NdotV, roughness);
    let ggx1 = geometrySchlickGGX(NdotL, roughness);
    
    return ggx1 * ggx2;
}

// PBR BRDF calculation
fn calculateBRDF(
    albedo: vec3<f32>,
    normal: vec3<f32>,
    viewDir: vec3<f32>,
    lightDir: vec3<f32>,
    roughness: f32,
    metallic: f32
) -> vec3<f32> {
    let H = normalize(viewDir + lightDir);
    
    // Calculate F0 (base reflectivity)
    var F0 = vec3<f32>(0.04);
    F0 = mix(F0, albedo, metallic);
    
    // Cook-Torrance BRDF
    let NDF = distributionGGX(normal, H, roughness);
    let G = geometrySmith(normal, viewDir, lightDir, roughness);
    let F = fresnelSchlick(max(dot(H, viewDir), 0.0), F0);
    
    let kS = F;
    let kD = (vec3<f32>(1.0) - kS) * (1.0 - metallic);
    
    let NdotL = max(dot(normal, lightDir), 0.0);
    let NdotV = max(dot(normal, viewDir), 0.0);
    
    let numerator = NDF * G * F;
    let denominator = 4.0 * NdotV * NdotL + 0.001;
    let specular = numerator / denominator;
    
    return (kD * albedo / 3.14159265 + specular) * NdotL;
}

// Percentage-closer filtering for soft shadows
fn calculateShadow(worldPos: vec3<f32>, normal: vec3<f32>) -> f32 {
    // Transform to shadow map space
    let shadowPos = shadowViewProj * vec4<f32>(worldPos, 1.0);
    let shadowCoords = shadowPos.xyz / shadowPos.w;
    
    // Convert to texture coordinates
    let uv = shadowCoords.xy * 0.5 + 0.5;
    let depth = shadowCoords.z - lighting.shadowBias;
    
    // PCF sampling
    var shadow = 0.0;
    let texelSize = 1.0 / 2048.0; // Assuming 2048x2048 shadow map
    
    for (var x = -1; x <= 1; x++) {
        for (var y = -1; y <= 1; y++) {
            let offset = vec2<f32>(f32(x), f32(y)) * texelSize;
            let texCoord = uv + offset;
            let texCoordInt = vec2<i32>(texCoord * 2048.0);
            let shadowDepth = textureLoad(shadowMap, texCoordInt, 0).r;
            if (depth < shadowDepth) {
                shadow += 1.0;
            }
        }
    }
    
    return shadow / 9.0;
}

// Simple fog calculation
fn applyFog(color: vec3<f32>, distance: f32) -> vec3<f32> {
    let fogFactor = exp(-distance * lighting.fogDensity);
    return mix(lighting.fogColor, color, fogFactor);
}

// Screen-space ambient occlusion (simplified)
fn calculateSSAO(position: vec3<f32>, normal: vec3<f32>) -> f32 {
    // This would typically sample nearby pixels in screen space
    // For now, return a simple approximation
    return 1.0;
}

@compute @workgroup_size(8, 8, 1)
fn main(@builtin(global_invocation_id) id: vec3<u32>) {
    let pixelIdx = id.y * 1920u + id.x; // Assuming 1920 width
    
    // Check bounds
    if (pixelIdx >= arrayLength(&gBuffer)) {
        return;
    }
    
    let g = gBuffer[pixelIdx];
    
    // Skip empty pixels
    if (g.depth <= 0.0) {
        frameBuffer[pixelIdx] = vec4<f32>(lighting.fogColor, 1.0);
        return;
    }
    
    // Calculate view direction
    let viewDir = normalize(cameraPosition - g.position);
    
    // Get material properties
    let matProps = getMaterialProperties(g.materialId);
    let roughness = matProps.x;
    let metallic = matProps.y;
    
    // Direct lighting (sun)
    let directLight = calculateBRDF(
        g.albedo,
        g.normal,
        viewDir,
        -lighting.sunDirection,
        roughness,
        metallic
    ) * lighting.sunColor * lighting.sunIntensity;
    
    // Shadow calculation
    let shadow = calculateShadow(g.position, g.normal);
    
    // Ambient lighting (simplified IBL)
    let ambient = g.albedo * lighting.ambientColor * lighting.ambientIntensity;
    
    // Combine lighting
    var finalColor = ambient + directLight * shadow;
    
    // Apply ambient occlusion
    finalColor *= g.occlusion;
    
    // Apply fog
    let distance = length(g.position - cameraPosition);
    finalColor = applyFog(finalColor, distance);
    
    // Tone mapping (Reinhard)
    finalColor = finalColor / (finalColor + vec3<f32>(1.0));
    
    // Gamma correction
    finalColor = pow(finalColor, vec3<f32>(1.0 / 2.2));
    
    // Write to frame buffer
    frameBuffer[pixelIdx] = vec4<f32>(finalColor, 1.0);
}