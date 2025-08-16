#version 450 core

layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec2 aTexCoord;
layout(location = 3) in int aVoxelData;

uniform mat4 uProjection;
uniform mat4 uView;
uniform mat4 uModel;

out vec3 FragPos;
out vec3 Normal;
out vec3 VoxelColor;
out float VoxelDensity;

void main() {
    FragPos = vec3(uModel * vec4(aPos, 1.0));
    Normal = mat3(transpose(inverse(uModel))) * aNormal;
    
    // Unpack voxel data
    VoxelColor = vec3(
        float((aVoxelData >> 16) & 0xFF) / 255.0,
        float((aVoxelData >> 8) & 0xFF) / 255.0,
        float(aVoxelData & 0xFF) / 255.0
    );
    VoxelDensity = float((aVoxelData >> 24) & 0xFF) / 255.0;
    
    gl_Position = uProjection * uView * vec4(FragPos, 1.0);
}