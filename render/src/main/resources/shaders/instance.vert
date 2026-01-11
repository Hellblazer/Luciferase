#version 430 core

// Vertex input
layout(location = 0) in vec3 position;
layout(location = 1) in vec3 normal;

// Per-instance data (in storage buffer)
layout(std430, binding = 0) readonly buffer Transforms {
    mat4 transforms[];
};

// Uniforms
uniform mat4 viewProj;

// Output
out vec3 vNormal;
out vec3 vWorldPos;
out flat int vInstanceId;

void main() {
    // Get instance-specific transform
    mat4 model = transforms[gl_InstanceID];

    // Transform position to world space
    vec4 worldPos = model * vec4(position, 1.0);
    vWorldPos = worldPos.xyz;

    // Transform normal to world space
    vNormal = mat3(model) * normal;

    // Project to screen space
    gl_Position = viewProj * worldPos;

    // Pass instance ID for per-entity data
    vInstanceId = gl_InstanceID;
}
