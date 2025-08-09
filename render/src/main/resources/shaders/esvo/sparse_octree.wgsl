// Sparse voxel octree construction compute shader
// Builds bottom-up octree from voxel grid

struct OctreeNode {
    childMask: u32,      // Bitmask for child existence
    dataOffset: u32,     // Offset to children or voxel data
    nodeType: u32,       // 0=internal, 1=leaf
    voxelData: u32,      // Packed color/material for leaves
}

struct OctreeParams {
    gridResolution: vec3<u32>,
    maxDepth: u32,
    nodePoolSize: u32,
    leafThreshold: u32,  // Min voxels to create a leaf
}

struct BuildState {
    nodeCount: atomic<u32>,
    levelOffsets: array<u32, 16>,  // Offset for each octree level
}

@group(0) @binding(0) var<storage, read> voxelGrid: array<u32>;
@group(0) @binding(1) var<storage, read> voxelColors: array<vec4<f32>>;
@group(0) @binding(2) var<storage, read_write> octreeNodes: array<OctreeNode>;
@group(0) @binding(3) var<uniform> params: OctreeParams;
@group(0) @binding(4) var<storage, read_write> buildState: BuildState;

// Get voxel grid index
fn getVoxelIndex(coord: vec3<u32>) -> u32 {
    if (any(coord >= params.gridResolution)) {
        return 0xFFFFFFFFu;
    }
    return coord.z * params.gridResolution.x * params.gridResolution.y +
           coord.y * params.gridResolution.x +
           coord.x;
}

// Check if a region contains any voxels
fn regionHasVoxels(minCoord: vec3<u32>, size: u32) -> bool {
    let maxCoord = min(minCoord + vec3<u32>(size), params.gridResolution);
    
    for (var z = minCoord.z; z < maxCoord.z; z++) {
        for (var y = minCoord.y; y < maxCoord.y; y++) {
            for (var x = minCoord.x; x < maxCoord.x; x++) {
                let idx = getVoxelIndex(vec3<u32>(x, y, z));
                if (idx != 0xFFFFFFFFu && voxelGrid[idx] != 0u) {
                    return true;
                }
            }
        }
    }
    return false;
}

// Count voxels in a region
fn countVoxelsInRegion(minCoord: vec3<u32>, size: u32) -> u32 {
    let maxCoord = min(minCoord + vec3<u32>(size), params.gridResolution);
    var count = 0u;
    
    for (var z = minCoord.z; z < maxCoord.z; z++) {
        for (var y = minCoord.y; y < maxCoord.y; y++) {
            for (var x = minCoord.x; x < maxCoord.x; x++) {
                let idx = getVoxelIndex(vec3<u32>(x, y, z));
                if (idx != 0xFFFFFFFFu && voxelGrid[idx] != 0u) {
                    count++;
                }
            }
        }
    }
    return count;
}

// Calculate average color for a region
fn getRegionAverageColor(minCoord: vec3<u32>, size: u32) -> vec4<f32> {
    let maxCoord = min(minCoord + vec3<u32>(size), params.gridResolution);
    var colorSum = vec4<f32>(0.0);
    var count = 0.0;
    
    for (var z = minCoord.z; z < maxCoord.z; z++) {
        for (var y = minCoord.y; y < maxCoord.y; y++) {
            for (var x = minCoord.x; x < maxCoord.x; x++) {
                let idx = getVoxelIndex(vec3<u32>(x, y, z));
                if (idx != 0xFFFFFFFFu && voxelGrid[idx] != 0u) {
                    colorSum += voxelColors[idx];
                    count += 1.0;
                }
            }
        }
    }
    
    if (count > 0.0) {
        return colorSum / count;
    }
    return vec4<f32>(0.0);
}

// Pack color to u32
fn packColor(color: vec4<f32>) -> u32 {
    let r = u32(clamp(color.r * 255.0, 0.0, 255.0));
    let g = u32(clamp(color.g * 255.0, 0.0, 255.0));
    let b = u32(clamp(color.b * 255.0, 0.0, 255.0));
    let a = u32(clamp(color.a * 255.0, 0.0, 255.0));
    return (a << 24u) | (r << 16u) | (g << 8u) | b;
}

// Stack entry for iterative octree building
struct BuildStackEntry {
    minCoord: vec3<u32>,
    size: u32,
    depth: u32,
    nodeIdx: u32,
    parentIdx: u32,
    octant: u32,
}

// Build octree node for a region (iterative version)
fn buildOctreeNode(
    startMinCoord: vec3<u32>,
    startSize: u32,
    startDepth: u32
) -> u32 {
    // Check if region is empty
    if (!regionHasVoxels(startMinCoord, startSize)) {
        return 0xFFFFFFFFu; // Empty node
    }
    
    // Use a fixed-size stack for building (max depth 16)
    var stack: array<BuildStackEntry, 128>; // Larger stack for octree building
    var stackTop = 0u;
    
    // Allocate root node
    let rootIdx = atomicAdd(&buildState.nodeCount, 1u);
    if (rootIdx >= params.nodePoolSize) {
        return 0xFFFFFFFFu; // Out of memory
    }
    
    // Push root entry
    stack[0] = BuildStackEntry(startMinCoord, startSize, startDepth, rootIdx, 0xFFFFFFFFu, 0u);
    stackTop = 1u;
    
    while (stackTop > 0u) {
        stackTop--;
        let entry = stack[stackTop];
        
        var node: OctreeNode;
        
        // Check if we should create a leaf
        let voxelCount = countVoxelsInRegion(entry.minCoord, entry.size);
        // Only create a leaf if:
        // - We've reached max depth, OR
        // - We're at voxel level (size = 1), OR  
        // - The region is completely empty (voxelCount = 0), OR
        // - We're at a small size AND have uniform voxels (for LOD optimization)
        let shouldBeLeaf = (entry.depth >= params.maxDepth) || 
                           (entry.size <= 1u) || 
                           (voxelCount == 0u) ||
                           (entry.size <= 2u && voxelCount > 0u); // Small uniform regions become leaves
        
        if (shouldBeLeaf) {
            // Create leaf node
            node.nodeType = 1u;
            node.childMask = 0u;
            node.dataOffset = 0u;
            
            // Store average color
            let avgColor = getRegionAverageColor(entry.minCoord, entry.size);
            node.voxelData = packColor(avgColor);
            
            // Store node
            octreeNodes[entry.nodeIdx] = node;
        } else {
            // Create internal node
            node.nodeType = 0u;
            node.childMask = 0u;
            
            // First, determine which children exist
            let halfSize = entry.size / 2u;
            var childrenToCreate = 0u;
            var childExists: array<bool, 8>;
            
            for (var octant = 0u; octant < 8u; octant++) {
                let childMin = entry.minCoord + vec3<u32>(
                    select(0u, halfSize, (octant & 1u) != 0u),
                    select(0u, halfSize, (octant & 2u) != 0u),
                    select(0u, halfSize, (octant & 4u) != 0u)
                );
                
                childExists[octant] = regionHasVoxels(childMin, halfSize);
                if (childExists[octant]) {
                    node.childMask |= (1u << octant);
                    childrenToCreate++;
                }
            }
            
            // Only allocate nodes for children that exist
            if (childrenToCreate > 0u) {
                let childBaseIdx = atomicAdd(&buildState.nodeCount, childrenToCreate);
                node.dataOffset = childBaseIdx;
                
                // Add existing children to stack
                var childOffset = 0u;
                for (var octant = 0u; octant < 8u; octant++) {
                    if (childExists[octant]) {
                        let childMin = entry.minCoord + vec3<u32>(
                            select(0u, halfSize, (octant & 1u) != 0u),
                            select(0u, halfSize, (octant & 2u) != 0u),
                            select(0u, halfSize, (octant & 4u) != 0u)
                        );
                        
                        let childNodeIdx = childBaseIdx + childOffset;
                        childOffset++;
                        
                        // Add child to stack for processing
                        if (stackTop < 128u) {
                            stack[stackTop] = BuildStackEntry(
                                childMin, halfSize, entry.depth + 1u,
                                childNodeIdx, entry.nodeIdx, octant
                            );
                            stackTop++;
                        }
                    }
                }
            } else {
                // No children, make it a leaf
                node.nodeType = 1u;
                node.dataOffset = 0u;
            }
            
            // Average color for LOD
            let avgColor = getRegionAverageColor(entry.minCoord, entry.size);
            node.voxelData = packColor(avgColor);
            
            // Store node
            octreeNodes[entry.nodeIdx] = node;
        }
    }
    
    return rootIdx;
}

// Parallel bottom-up construction (more efficient for large grids)
fn buildOctreeBottomUp(workgroupId: u32, localId: u32) {
    // Calculate which leaf-level region this thread handles
    let leafSize = 1u << params.maxDepth;
    let leafsPerDim = (params.gridResolution.x + leafSize - 1u) / leafSize;
    let totalLeafs = leafsPerDim * leafsPerDim * leafsPerDim;
    
    let leafIdx = workgroupId * 256u + localId;
    if (leafIdx >= totalLeafs) {
        return;
    }
    
    // Convert linear index to 3D coordinates
    let leafZ = leafIdx / (leafsPerDim * leafsPerDim);
    let leafY = (leafIdx / leafsPerDim) % leafsPerDim;
    let leafX = leafIdx % leafsPerDim;
    
    let minCoord = vec3<u32>(leafX, leafY, leafZ) * leafSize;
    
    // Build leaf node
    let nodeIdx = buildOctreeNode(minCoord, leafSize, params.maxDepth);
    
    // Store leaf node index for parent construction
    // (Would need additional buffer for multi-level construction)
}

@compute @workgroup_size(1, 1, 1)
fn main(
    @builtin(workgroup_id) workgroupId: vec3<u32>,
    @builtin(local_invocation_id) localId: vec3<u32>,
    @builtin(global_invocation_id) globalId: vec3<u32>
) {
    // Only run on the first thread
    if (globalId.x == 0u && globalId.y == 0u && globalId.z == 0u) {
        // Initialize counter to 0 before building
        atomicStore(&buildState.nodeCount, 0u);
        
        // Calculate the size needed to encompass the entire grid
        // Use next power of 2 to ensure proper octree subdivision
        let maxDim = max(params.gridResolution.x, max(params.gridResolution.y, params.gridResolution.z));
        var rootSize = 1u;
        while (rootSize < maxDim) {
            rootSize = rootSize * 2u;
        }
        
        // Build the octree from the voxel grid
        let rootIdx = buildOctreeNode(vec3<u32>(0u), rootSize, 0u);
        
        // If the build failed or returned empty, create a default empty root
        if (rootIdx == 0xFFFFFFFFu) {
            // No voxels found - create an empty leaf node
            var emptyRoot: OctreeNode;
            emptyRoot.childMask = 0u;
            emptyRoot.dataOffset = 0u;
            emptyRoot.nodeType = 1u;  // Leaf node
            emptyRoot.voxelData = 0u; // Empty/transparent
            
            octreeNodes[0] = emptyRoot;
            atomicStore(&buildState.nodeCount, 1u);
        }
        // Otherwise, the octree has been built successfully by buildOctreeNode
    }
}