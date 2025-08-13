// Voxel Fragment Shader
// Provides lighting and material effects for voxel rendering

struct Uniforms {
    viewProjection: mat4x4<f32>,
    model: mat4x4<f32>,
    lightDirection: vec3<f32>,
    time: f32,
    cameraPosition: vec3<f32>,
    _padding: f32,
}

struct LightingParams {
    ambientStrength: f32,
    diffuseStrength: f32,
    specularStrength: f32,
    shininess: f32,
    fogStart: f32,
    fogEnd: f32,
    fogColor: vec3<f32>,
    _padding: f32,
}

struct FragmentInput {
    @builtin(position) fragCoord: vec4<f32>,
    @location(0) worldPosition: vec3<f32>,
    @location(1) worldNormal: vec3<f32>,
    @location(2) color: vec4<f32>,
    @location(3) texCoord: vec2<f32>,
    @location(4) viewDistance: f32,
    @location(5) materialId: f32,
}

@group(0) @binding(0) var<uniform> uniforms: Uniforms;
@group(0) @binding(1) var<uniform> lighting: LightingParams;
@group(0) @binding(2) var textureSampler: sampler;
@group(0) @binding(3) var voxelTexture: texture_2d<f32>;

// Calculate Phong lighting
fn calculatePhongLighting(
    normal: vec3<f32>,
    lightDir: vec3<f32>,
    viewDir: vec3<f32>,
    baseColor: vec3<f32>
) -> vec3<f32> {
    // Ambient component
    let ambient = lighting.ambientStrength * baseColor;
    
    // Diffuse component
    let NdotL = max(dot(normal, lightDir), 0.0);
    let diffuse = lighting.diffuseStrength * NdotL * baseColor;
    
    // Specular component
    let reflectDir = reflect(-lightDir, normal);
    let RdotV = max(dot(reflectDir, viewDir), 0.0);
    let specular = lighting.specularStrength * pow(RdotV, lighting.shininess) * vec3<f32>(1.0);
    
    return ambient + diffuse + specular;
}

// Apply fog effect
fn applyFog(color: vec3<f32>, distance: f32) -> vec3<f32> {
    let fogFactor = clamp((lighting.fogEnd - distance) / (lighting.fogEnd - lighting.fogStart), 0.0, 1.0);
    return mix(lighting.fogColor, color, fogFactor);
}

// Generate edge highlighting for voxel definition
fn calculateEdgeHighlight(normal: vec3<f32>, viewDir: vec3<f32>) -> f32 {
    let edgeFactor = 1.0 - abs(dot(normal, viewDir));
    return pow(edgeFactor, 2.0) * 0.15;
}

// Simple procedural texture pattern
fn proceduralPattern(texCoord: vec2<f32>, materialId: f32) -> f32 {
    let pattern1 = sin(texCoord.x * 10.0 + materialId) * cos(texCoord.y * 10.0 + materialId);
    let pattern2 = sin(length(texCoord - vec2<f32>(0.5)) * 20.0);
    return mix(pattern1, pattern2, 0.5) * 0.1 + 0.95;
}

@fragment
fn fs_main(input: FragmentInput) -> @location(0) vec4<f32> {
    // Normalize interpolated normal
    let normal = normalize(input.worldNormal);
    
    // Calculate view direction
    let viewDir = normalize(uniforms.cameraPosition - input.worldPosition);
    
    // Light direction (normalized)
    let lightDir = normalize(uniforms.lightDirection);
    
    // Base color from instance
    var baseColor = input.color.rgb;
    
    // Optional: Apply texture if available
    // Uncomment when texture is bound:
    // let textureColor = textureSample(voxelTexture, textureSampler, input.texCoord);
    // baseColor = baseColor * textureColor.rgb;
    
    // Apply procedural pattern for variation
    let pattern = proceduralPattern(input.texCoord, input.materialId);
    baseColor = baseColor * pattern;
    
    // Calculate Phong lighting
    var finalColor = calculatePhongLighting(normal, lightDir, viewDir, baseColor);
    
    // Add edge highlighting for better voxel definition
    let edgeHighlight = calculateEdgeHighlight(normal, viewDir);
    finalColor = finalColor + vec3<f32>(edgeHighlight);
    
    // Apply fog if enabled (fogEnd > fogStart)
    if (lighting.fogEnd > lighting.fogStart) {
        finalColor = applyFog(finalColor, input.viewDistance);
    }
    
    // Add subtle time-based animation (pulsing effect)
    let pulse = sin(uniforms.time * 2.0) * 0.02 + 1.0;
    finalColor = finalColor * pulse;
    
    // Output final color with alpha
    return vec4<f32>(finalColor, input.color.a);
}