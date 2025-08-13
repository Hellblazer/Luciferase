// WebGPU fragment shader for voxel rendering
// Applies Phong-style lighting with directional light and ambient

struct VertexOutput {
    @builtin(position) clip_position: vec4<f32>,
    @location(0) world_pos: vec3<f32>,
    @location(1) world_normal: vec3<f32>,
    @location(2) color: vec3<f32>,
}

// Lighting parameters (could be made uniform later)
const LIGHT_DIR: vec3<f32> = vec3<f32>(0.577, 0.577, 0.577);  // Normalized (1,1,1)
const LIGHT_COLOR: vec3<f32> = vec3<f32>(1.0, 0.95, 0.8);     // Warm white
const AMBIENT_STRENGTH: f32 = 0.3;
const DIFFUSE_STRENGTH: f32 = 0.7;

@fragment
fn fs_main(input: VertexOutput) -> @location(0) vec4<f32> {
    // Normalize the interpolated normal
    let normal = normalize(input.world_normal);
    
    // Calculate diffuse lighting using Lambertian reflection
    let light_dir = normalize(LIGHT_DIR);
    let diffuse_factor = max(dot(normal, light_dir), 0.0);
    
    // Combine ambient and diffuse lighting
    let ambient = AMBIENT_STRENGTH * LIGHT_COLOR;
    let diffuse = DIFFUSE_STRENGTH * diffuse_factor * LIGHT_COLOR;
    let lighting = ambient + diffuse;
    
    // Apply lighting to the instance color
    let final_color = input.color * lighting;
    
    // Gamma correction (approximate sRGB)
    let gamma_corrected = pow(final_color, vec3<f32>(1.0 / 2.2));
    
    return vec4<f32>(gamma_corrected, 1.0);
}