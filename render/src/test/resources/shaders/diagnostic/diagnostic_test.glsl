#version 460 core
#extension GL_ARB_compute_shader : enable
#extension GL_ARB_shader_storage_buffer_object : enable

layout(local_size_x = 64, local_size_y = 1, local_size_z = 1) in;

layout(std430, binding = 0) buffer OutputBuffer {
    uint output[];
};

layout(std430, binding = 1) buffer DebugBuffer {
    uint debug[];
};

void main() {
    uint idx = gl_GlobalInvocationID.x;
    
    // Write debug markers
    if (idx == 0) {
        debug[0] = 0xDEADBEEF;  // Magic number
        debug[1] = 64;          // Array length
        debug[2] = 42;          // Test value
    }
    
    // Each thread writes its ID + 1
    if (idx < 64) {
        output[idx] = idx + 1;
    }
}