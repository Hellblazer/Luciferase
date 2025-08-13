// WebGPU vertex shader for instanced voxel rendering
// Transforms cube geometry per instance with position, color, and scale

struct Uniforms {
    model: mat4x4<f32>,
    view: mat4x4<f32>, 
    proj: mat4x4<f32>,
}

@group(0) @binding(0) var<uniform> uniforms: Uniforms;

struct VertexInput {
    @location(0) position: vec3<f32>,      // Cube vertex position
    @location(1) normal: vec3<f32>,        // Cube vertex normal
    @location(2) instancePos: vec3<f32>,   // Instance world position
    @location(3) instanceColor: vec3<f32>, // Instance color
    @location(4) instanceScale: f32,       // Instance scale factor
}

struct VertexOutput {
    @builtin(position) clip_position: vec4<f32>,
    @location(0) world_pos: vec3<f32>,
    @location(1) world_normal: vec3<f32>,
    @location(2) color: vec3<f32>,
}

@vertex
fn vs_main(input: VertexInput) -> VertexOutput {
    var output: VertexOutput;
    
    // Scale the base cube vertex by instance scale
    let scaled_pos = input.position * input.instanceScale;
    
    // Transform to world space by adding instance position
    let world_pos = scaled_pos + input.instancePos;
    
    // Transform through model-view-projection matrices
    output.clip_position = uniforms.proj * uniforms.view * uniforms.model * vec4<f32>(world_pos, 1.0);
    
    // Pass world position for fragment shader lighting
    output.world_pos = world_pos;
    
    // Transform normal to world space (assuming uniform scale)
    output.world_normal = normalize((uniforms.model * vec4<f32>(input.normal, 0.0)).xyz);
    
    // Pass instance color to fragment shader
    output.color = input.instanceColor;
    
    return output;
}