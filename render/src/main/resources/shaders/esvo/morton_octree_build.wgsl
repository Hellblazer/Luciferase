// Morton Code-Based Sparse Voxel Octree Construction Shader
// Efficiently builds octree using Morton curve spatial encoding for cache-friendly access

struct VoxelParams {
    resolution: vec3<u32>,
    voxelSize: f32,
    boundsMin: vec3<f32>,
    boundsMax: vec3<f32>,
    maxDepth: u32,
    nodePoolSize: u32,
    _padding: vec2<u32>
}

struct PackedOctreeNode {
    // 128-bit packed structure for optimal GPU memory access
    data0: u32, // childMask(8) | nodeType(8) | depth(8) | flags(8)
    data1: u32, // childPointer or dataOffset
    data2: u32, // packed RGBA color (8-8-8-8)
    data3: u32, // voxel count or material data
}

struct BuildStats {
    totalNodes: atomic<u32>,
    leafNodes: atomic<u32>,
    internalNodes: atomic<u32>,
    maxDepth: atomic<u32>,
    totalVoxels: atomic<u32>,
    compressedSize: atomic<u32>,
    _padding: vec2<u32>
}

// Buffer bindings
@group(0) @binding(0) var<storage, read> voxelGrid: array<u32>;
@group(0) @binding(1) var<storage, read_write> octreeNodes: array<PackedOctreeNode>;
@group(0) @binding(2) var<uniform> params: VoxelParams;
@group(0) @binding(3) var<storage, read_write> mortonCodes: array<u32>;
@group(0) @binding(4) var<storage, read_write> nodeAllocator: atomic<u32>;
@group(0) @binding(5) var<storage, read_write> buildStats: BuildStats;
@group(0) @binding(6) var<storage, read> voxelColors: array<vec4<f32>>;

// Shared memory for workgroup-level operations
var<workgroup> sharedNodes: array<PackedOctreeNode, 64>;
var<workgroup> sharedReduction: array<u32, 256>;

// Morton encoding functions
fn expandBits(v: u32) -> u32 {
    var x = v & 0x3FF; // 10 bits
    x = (x | (x << 16)) & 0x030000FF;
    x = (x | (x << 8)) & 0x0300F00F;
    x = (x | (x << 4)) & 0x030C30C3;
    x = (x | (x << 2)) & 0x09249249;
    return x;
}

fn morton3D(p: vec3<u32>) -> u32 {
    return expandBits(p.x) | (expandBits(p.y) << 1) | (expandBits(p.z) << 2);
}

fn getVoxelIndex(x: u32, y: u32, z: u32) -> u32 {
    return z * params.resolution.x * params.resolution.y + y * params.resolution.x + x;
}

fn isVoxelOccupied(x: u32, y: u32, z: u32) -> bool {
    if (x >= params.resolution.x || y >= params.resolution.y || z >= params.resolution.z) {
        return false;
    }
    let idx = getVoxelIndex(x, y, z);
    return voxelGrid[idx] != 0u;
}

// Compute child mask for a node at given position and level
fn computeChildMask(nodePos: vec3<u32>, level: u32) -> u32 {
    let childSize = 1u << level;
    var mask = 0u;
    
    for (var i = 0u; i < 8u; i++) {
        let childOffset = vec3<u32>(
            (i & 1u) * childSize,
            ((i >> 1u) & 1u) * childSize,
            ((i >> 2u) & 1u) * childSize
        );
        let childPos = nodePos + childOffset;
        
        // Check if child region contains any voxels
        var hasVoxels = false;
        for (var z = childPos.z; z < min(childPos.z + childSize, params.resolution.z); z++) {
            for (var y = childPos.y; y < min(childPos.y + childSize, params.resolution.y); y++) {
                for (var x = childPos.x; x < min(childPos.x + childSize, params.resolution.x); x++) {
                    if (isVoxelOccupied(x, y, z)) {
                        hasVoxels = true;
                        break;
                    }
                }
                if (hasVoxels) { break; }
            }
            if (hasVoxels) { break; }
        }
        
        if (hasVoxels) {
            mask |= (1u << i);
        }
    }
    
    return mask;
}

// Count set bits in mask
fn popcount(x: u32) -> u32 {
    var count = 0u;
    var v = x;
    while (v != 0u) {
        count += v & 1u;
        v >>= 1u;
    }
    return count;
}

// Allocate node from pool
fn allocateNode() -> u32 {
    return atomicAdd(&nodeAllocator, 1u);
}

// Create leaf node from voxel data
fn createLeafNode(voxelIdx: u32, depth: u32) -> PackedOctreeNode {
    var node: PackedOctreeNode;
    
    // Pack node type (1 = leaf), depth, and flags
    node.data0 = (1u << 8u) | (depth << 16u);
    
    // Store voxel data index
    node.data1 = voxelIdx;
    
    // Pack color if available
    if (voxelIdx < arrayLength(&voxelColors)) {
        let color = voxelColors[voxelIdx];
        node.data2 = (u32(color.r * 255.0) << 24u) |
                     (u32(color.g * 255.0) << 16u) |
                     (u32(color.b * 255.0) << 8u) |
                     u32(color.a * 255.0);
    } else {
        node.data2 = 0xFFFFFFFFu; // Default white
    }
    
    node.data3 = 1u; // Single voxel
    
    atomicAdd(&buildStats.leafNodes, 1u);
    atomicAdd(&buildStats.totalVoxels, 1u);
    
    return node;
}

// Create internal node with children
fn createInternalNode(childMask: u32, childPointer: u32, depth: u32) -> PackedOctreeNode {
    var node: PackedOctreeNode;
    
    // Pack child mask, node type (0 = internal), and depth
    node.data0 = childMask | (0u << 8u) | (depth << 16u);
    
    // Store pointer to first child
    node.data1 = childPointer;
    
    // Color will be computed as average of children (deferred)
    node.data2 = 0u;
    
    // Store child count
    node.data3 = popcount(childMask);
    
    atomicAdd(&buildStats.internalNodes, 1u);
    
    return node;
}

// Bottom-up octree construction kernel
@compute @workgroup_size(256, 1, 1)
fn buildOctreeBottom(@builtin(global_invocation_id) gid: vec3<u32>,
                     @builtin(local_invocation_id) lid: vec3<u32>) {
    let threadId = gid.x;
    let localId = lid.x;
    
    // Phase 1: Generate Morton codes for occupied voxels
    let voxelsPerThread = (params.resolution.x * params.resolution.y * params.resolution.z + 255u) / 256u;
    let startIdx = threadId * voxelsPerThread;
    let endIdx = min((threadId + 1u) * voxelsPerThread, 
                     params.resolution.x * params.resolution.y * params.resolution.z);
    
    // All threads must participate in the work phase to ensure uniform control flow
    let totalVoxels = params.resolution.x * params.resolution.y * params.resolution.z;
    if (threadId < (totalVoxels + voxelsPerThread - 1u) / voxelsPerThread) {
        for (var idx = startIdx; idx < endIdx; idx++) {
            let z = idx / (params.resolution.x * params.resolution.y);
            let y = (idx % (params.resolution.x * params.resolution.y)) / params.resolution.x;
            let x = idx % params.resolution.x;
            
            if (isVoxelOccupied(x, y, z)) {
                let morton = morton3D(vec3<u32>(x, y, z));
                mortonCodes[idx] = morton;
                
                // Create leaf node
                let nodeIdx = allocateNode();
                if (nodeIdx < params.nodePoolSize) {
                    octreeNodes[nodeIdx] = createLeafNode(idx, 0u);
                }
            } else {
                mortonCodes[idx] = 0xFFFFFFFFu; // Invalid marker
            }
        }
    }
    
    // All threads in workgroup must reach this barrier together
    workgroupBarrier();
    
    // Phase 2: Build internal nodes level by level
    // This would typically be done in multiple kernel launches
    // for proper synchronization between levels
}

// Top-down octree refinement kernel
@compute @workgroup_size(64, 1, 1)
fn refineOctreeTop(@builtin(global_invocation_id) gid: vec3<u32>) {
    let nodeIdx = gid.x;
    
    if (nodeIdx >= atomicLoad(&nodeAllocator)) {
        return;
    }
    
    var node = octreeNodes[nodeIdx];
    let nodeType = (node.data0 >> 8u) & 0xFFu;
    
    // Only process internal nodes
    if (nodeType != 0u) {
        return;
    }
    
    let childMask = node.data0 & 0xFFu;
    let childPointer = node.data1;
    let childCount = popcount(childMask);
    
    // Compute average color from children
    var avgColor = vec4<f32>(0.0);
    var validChildren = 0u;
    
    for (var i = 0u; i < 8u; i++) {
        if ((childMask & (1u << i)) != 0u) {
            let childIdx = childPointer + validChildren;
            if (childIdx < params.nodePoolSize) {
                let child = octreeNodes[childIdx];
                let packedColor = child.data2;
                
                avgColor.r += f32((packedColor >> 24u) & 0xFFu) / 255.0;
                avgColor.g += f32((packedColor >> 16u) & 0xFFu) / 255.0;
                avgColor.b += f32((packedColor >> 8u) & 0xFFu) / 255.0;
                avgColor.a += f32(packedColor & 0xFFu) / 255.0;
                
                validChildren += 1u;
            }
        }
    }
    
    if (validChildren > 0u) {
        avgColor /= f32(validChildren);
        node.data2 = (u32(avgColor.r * 255.0) << 24u) |
                     (u32(avgColor.g * 255.0) << 16u) |
                     (u32(avgColor.b * 255.0) << 8u) |
                     u32(avgColor.a * 255.0);
        octreeNodes[nodeIdx] = node;
    }
}

// Compaction kernel to remove empty nodes
@compute @workgroup_size(256, 1, 1)
fn compactOctree(@builtin(global_invocation_id) gid: vec3<u32>,
                 @builtin(local_invocation_id) lid: vec3<u32>) {
    let nodeIdx = gid.x;
    let localId = lid.x;
    let totalNodes = atomicLoad(&nodeAllocator);
    
    // Initialize shared memory and ensure all threads participate
    var childMask = 0u;
    if (nodeIdx < totalNodes) {
        let node = octreeNodes[nodeIdx];
        childMask = node.data0 & 0xFFu;
    }
    
    // Use parallel prefix sum to compute compacted indices
    // All threads must participate in shared memory operations
    sharedReduction[localId] = select(0u, 1u, childMask != 0u);
    workgroupBarrier();
    
    // Parallel reduction for prefix sum - all threads participate
    for (var stride = 1u; stride < 256u; stride *= 2u) {
        let temp = select(0u, sharedReduction[localId - stride], localId >= stride);
        workgroupBarrier();
        sharedReduction[localId] += temp;
        workgroupBarrier();
    }
    
    // Write compacted node if valid and non-empty
    if (nodeIdx < totalNodes && childMask != 0u) {
        let compactedIdx = sharedReduction[localId] - 1u;
        // Would write to compacted buffer here
        atomicAdd(&buildStats.compressedSize, 16u); // Size of PackedOctreeNode
    }
}

// Statistics gathering kernel
@compute @workgroup_size(1, 1, 1)
fn gatherStatistics() {
    let totalNodes = atomicLoad(&nodeAllocator);
    atomicStore(&buildStats.totalNodes, totalNodes);
    
    // Calculate max depth by traversing from root
    var maxDepth = 0u;
    for (var i = 0u; i < totalNodes; i++) {
        let depth = (octreeNodes[i].data0 >> 16u) & 0xFFu;
        maxDepth = max(maxDepth, depth);
    }
    atomicStore(&buildStats.maxDepth, maxDepth);
}