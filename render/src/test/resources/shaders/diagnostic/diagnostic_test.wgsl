// Diagnostic shader for testing basic GPU execution
@group(0) @binding(0) var<storage, read_write> output: array<u32>;
@group(0) @binding(1) var<storage, read_write> debug: array<u32>;

@compute @workgroup_size(64, 1, 1)
fn main(@builtin(global_invocation_id) global_id: vec3<u32>) {
    let idx = global_id.x;
    
    // Write debug markers
    if (idx == 0u) {
        debug[0] = 0xDEADBEEFu;  // Magic number
        debug[1] = 64u;          // Array length
        debug[2] = 42u;          // Test value
    }
    
    // Each thread writes its ID + 1
    if (idx < 64u) {
        output[idx] = idx + 1u;
    }
}