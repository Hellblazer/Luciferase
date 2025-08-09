// Absolute minimal shader - just write a constant
@group(0) @binding(0) var<storage, read> dummy1: array<u32>;
@group(0) @binding(1) var<storage, read_write> output: array<u32>;
@group(0) @binding(2) var<uniform> params: vec4<u32>;
@group(0) @binding(3) var<storage, read_write> dummy3: array<u32>;

@compute @workgroup_size(1, 1, 1)
fn main() {
    // Just write a single constant value to prove execution
    output[0] = 0x12345678u;
}