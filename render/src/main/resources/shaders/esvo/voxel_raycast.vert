#version 450 core

// Vertex shader for fullscreen quad used in ray-casting

layout(location = 0) in vec2 position;

out vec2 texCoord;

void main() {
    gl_Position = vec4(position, 0.0, 1.0);
    texCoord = position * 0.5 + 0.5; // Convert from [-1,1] to [0,1]
}