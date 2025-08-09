// Ultra simple shader to test voxelization pipeline
// Just writes thread IDs to prove execution

@group(0) @binding(0) var<storage, read> dummy1: array<u32>;
@group(0) @binding(1) var<storage, read_write> voxelGrid: array<atomic<u32>>;  // Match main shader!
@group(0) @binding(2) var<uniform> dummy2: vec4<u32>;  // Must match UNIFORM binding type!
@group(0) @binding(3) var<storage, read_write> dummy3: array<u32>;

@compute @workgroup_size(64, 1, 1)
fn main(@builtin(global_invocation_id) id: vec3<u32>) {
    let threadIdx = id.x;
    
    // Only thread 0 writes, to eliminate race conditions
    if (threadIdx == 0u) {
        atomicStore(&voxelGrid[0], 0x11111111u);
        atomicStore(&voxelGrid[1], 0x22222222u);
        atomicStore(&voxelGrid[2], 0x33333333u);
        atomicStore(&voxelGrid[3], 0x44444444u);
        atomicStore(&voxelGrid[100], 0xDEADBEEFu);
    }
}