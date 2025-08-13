// Voxel Vertex Shader
// Handles instanced rendering of voxel cubes with per-instance transforms and colors

struct Uniforms {
    viewProjection: mat4x4<f32>,
    model: mat4x4<f32>,
    lightDirection: vec3<f32>,
    time: f32,
    cameraPosition: vec3<f32>,
    _padding: f32,
}

struct VertexInput {
    // Per-vertex attributes (cube geometry)
    @location(0) position: vec3<f32>,
    @location(1) normal: vec3<f32>,
    @location(2) texCoord: vec2<f32>,
}

struct InstanceInput {
    // Per-instance attributes
    // Instance transform matrix (4x4) - split into 4 vec4s
    @location(3) instanceTransform0: vec4<f32>,
    @location(4) instanceTransform1: vec4<f32>,
    @location(5) instanceTransform2: vec4<f32>,
    @location(6) instanceTransform3: vec4<f32>,
    // Instance color with alpha
    @location(7) instanceColor: vec4<f32>,
    // Instance metadata (scale, material ID, flags, reserved)
    @location(8) instanceData: vec4<f32>,
}

struct VertexOutput {
    @builtin(position) clipPosition: vec4<f32>,
    @location(0) worldPosition: vec3<f32>,
    @location(1) worldNormal: vec3<f32>,
    @location(2) color: vec4<f32>,
    @location(3) texCoord: vec2<f32>,
    @location(4) viewDistance: f32,
    @location(5) materialId: f32,
}

@group(0) @binding(0) var<uniform> uniforms: Uniforms;

@vertex
fn vs_main(
    vertex: VertexInput,
    instance: InstanceInput,
    @builtin(instance_index) instanceIndex: u32
) -> VertexOutput {
    // Reconstruct instance transform matrix
    let instanceTransform = mat4x4<f32>(
        instance.instanceTransform0,
        instance.instanceTransform1,
        instance.instanceTransform2,
        instance.instanceTransform3
    );
    
    // Extract scale from instance data
    let scale = instance.instanceData.x;
    
    // Apply scale to vertex position
    let scaledPosition = vertex.position * scale;
    
    // Transform vertex to world space
    let worldTransform = uniforms.model * instanceTransform;
    let worldPos = worldTransform * vec4<f32>(scaledPosition, 1.0);
    
    // Transform to clip space
    let clipPos = uniforms.viewProjection * worldPos;
    
    // Transform normal to world space (extract rotation from transform)
    let normalMatrix = mat3x3<f32>(
        normalize(worldTransform[0].xyz),
        normalize(worldTransform[1].xyz),
        normalize(worldTransform[2].xyz)
    );
    let worldNormal = normalize(normalMatrix * vertex.normal);
    
    // Calculate view distance for LOD or fade effects
    let viewDistance = length(uniforms.cameraPosition - worldPos.xyz);
    
    // Prepare output
    var output: VertexOutput;
    output.clipPosition = clipPos;
    output.worldPosition = worldPos.xyz;
    output.worldNormal = worldNormal;
    output.color = instance.instanceColor;
    output.texCoord = vertex.texCoord;
    output.viewDistance = viewDistance;
    output.materialId = instance.instanceData.y;
    
    return output;
}