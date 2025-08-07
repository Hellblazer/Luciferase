// Octree traversal compute shader for ESVO
// Performs ray-octree intersection testing using GPU parallelism

struct Ray {
    origin: vec3<f32>,
    direction: vec3<f32>,
    tMin: f32,
    tMax: f32
}

struct VoxelNode {
    // Packed 64-bit node data matching VoxelOctreeNode.java layout
    // Bits 0-7:   validMask (which children exist)
    // Bits 8-15:  leafMask (which children are leaves)
    // Bits 16-47: childPointer (32-bit pointer to first child)
    // Bits 48-63: contourPointer (16-bit pointer to contour data)
    packedData: u64
}

struct HitResult {
    hit: u32,          // 0 = miss, 1 = hit
    t: f32,            // Distance along ray
    nodeIndex: u32,    // Index of hit node
    normal: vec3<f32>  // Surface normal at hit point
}

struct OctreeInfo {
    rootNodeIndex: u32,
    maxDepth: u32,
    worldSize: f32,
    nodeCount: u32
}

struct StackEntry {
    nodeIndex: u32,
    tMin: f32,
    tMax: f32,
    level: u32,
    origin: vec3<f32>
}

// Bind groups
@group(0) @binding(0) var<storage, read> octreeNodes: array<VoxelNode>;
@group(0) @binding(1) var<storage, read> rays: array<Ray>;
@group(0) @binding(2) var<storage, write> results: array<HitResult>;
@group(0) @binding(3) var<uniform> octreeInfo: OctreeInfo;

// Constants
const STACK_SIZE: u32 = 23u; // Max tree depth + 1
const EPSILON: f32 = 1e-6;

// Unpack node data
fn unpackValidMask(packed: u64) -> u32 {
    return u32(packed & 0xFFu);
}

fn unpackLeafMask(packed: u64) -> u32 {
    return u32((packed >> 8u) & 0xFFu);
}

fn unpackChildPointer(packed: u64) -> u32 {
    return u32((packed >> 16u) & 0xFFFFFFFFu);
}

fn unpackContourPointer(packed: u64) -> u32 {
    return u32((packed >> 48u) & 0xFFFFu);
}

// Ray-AABB intersection test
fn rayBoxIntersection(ray: Ray, boxMin: vec3<f32>, boxMax: vec3<f32>) -> vec2<f32> {
    let invDir = 1.0 / ray.direction;
    let t1 = (boxMin - ray.origin) * invDir;
    let t2 = (boxMax - ray.origin) * invDir;
    
    let tMin = min(t1, t2);
    let tMax = max(t1, t2);
    
    let tNear = max(max(tMin.x, tMin.y), max(tMin.z, ray.tMin));
    let tFar = min(min(tMax.x, tMax.y), min(tMax.z, ray.tMax));
    
    if (tNear > tFar || tFar < 0.0) {
        return vec2<f32>(-1.0, -1.0); // No intersection
    }
    
    return vec2<f32>(tNear, tFar);
}

// Get child octant index for a point
fn getOctant(point: vec3<f32>, center: vec3<f32>) -> u32 {
    var octant: u32 = 0u;
    if (point.x >= center.x) { octant |= 1u; }
    if (point.y >= center.y) { octant |= 2u; }
    if (point.z >= center.z) { octant |= 4u; }
    return octant;
}

// Compute child node bounds
fn getChildBounds(parentOrigin: vec3<f32>, parentSize: f32, octant: u32) -> vec3<f32> {
    let halfSize = parentSize * 0.5;
    var childOrigin = parentOrigin;
    
    if ((octant & 1u) != 0u) { childOrigin.x += halfSize; }
    if ((octant & 2u) != 0u) { childOrigin.y += halfSize; }
    if ((octant & 4u) != 0u) { childOrigin.z += halfSize; }
    
    return childOrigin;
}

// Compute surface normal for voxel hit
fn computeNormal(hitPoint: vec3<f32>, voxelCenter: vec3<f32>, voxelSize: f32) -> vec3<f32> {
    let halfSize = voxelSize * 0.5;
    let relPos = hitPoint - voxelCenter;
    let absPos = abs(relPos);
    
    // Find which face was hit by checking which component is closest to the voxel boundary
    let distX = halfSize - absPos.x;
    let distY = halfSize - absPos.y;
    let distZ = halfSize - absPos.z;
    
    if (distX < distY && distX < distZ) {
        return vec3<f32>(sign(relPos.x), 0.0, 0.0);
    } else if (distY < distZ) {
        return vec3<f32>(0.0, sign(relPos.y), 0.0);
    } else {
        return vec3<f32>(0.0, 0.0, sign(relPos.z));
    }
}

// Sort children by ray direction for front-to-back traversal
fn getTraversalOrder(rayDir: vec3<f32>) -> array<u32, 8> {
    // Determine the starting octant based on ray direction
    let dirOctant = u32(select(0u, 1u, rayDir.x >= 0.0)) |
                    u32(select(0u, 2u, rayDir.y >= 0.0)) << 1u |
                    u32(select(0u, 4u, rayDir.z >= 0.0)) << 2u;
    
    // Pre-computed traversal orders for each ray direction octant
    // This ensures front-to-back traversal for better early termination
    var order: array<u32, 8>;
    
    // XOR with 7 gives us the opposite corner, ensuring front-to-back order
    for (var i = 0u; i < 8u; i++) {
        order[i] = i ^ (7u - dirOctant);
    }
    
    return order;
}

@compute @workgroup_size(64)
fn traverseOctree(@builtin(global_invocation_id) id: vec3<u32>) {
    let rayIdx = id.x;
    if (rayIdx >= arrayLength(&rays)) {
        return;
    }
    
    let ray = rays[rayIdx];
    var result = HitResult(0u, 1e10, 0u, vec3<f32>(0.0));
    
    // Stack for traversal
    var stack: array<StackEntry, STACK_SIZE>;
    var stackPtr = 0u;
    
    // Check root node intersection
    let rootSize = octreeInfo.worldSize;
    let rootOrigin = vec3<f32>(0.0);
    let rootBounds = rayBoxIntersection(ray, rootOrigin, rootOrigin + vec3<f32>(rootSize));
    
    if (rootBounds.x < 0.0) {
        results[rayIdx] = result;
        return;
    }
    
    // Initialize stack with root
    stack[0] = StackEntry(octreeInfo.rootNodeIndex, rootBounds.x, rootBounds.y, 0u, rootOrigin);
    stackPtr = 1u;
    
    // Get traversal order for this ray
    let traversalOrder = getTraversalOrder(ray.direction);
    
    // Traverse octree
    while (stackPtr > 0u) {
        stackPtr -= 1u;
        let entry = stack[stackPtr];
        
        // Skip if we've found a closer hit
        if (entry.tMin >= result.t) {
            continue;
        }
        
        let node = octreeNodes[entry.nodeIndex];
        let validMask = unpackValidMask(node.packedData);
        let leafMask = unpackLeafMask(node.packedData);
        let childPointer = unpackChildPointer(node.packedData);
        
        let nodeSize = rootSize / f32(1u << entry.level);
        let nodeCenter = entry.origin + vec3<f32>(nodeSize * 0.5);
        
        // Check if this is a leaf node (all valid children are leaves)
        if (validMask != 0u && (validMask & leafMask) == validMask && entry.level < octreeInfo.maxDepth) {
            // Hit a voxel
            let hitPoint = ray.origin + ray.direction * entry.tMin;
            result.hit = 1u;
            result.t = entry.tMin;
            result.nodeIndex = entry.nodeIndex;
            result.normal = computeNormal(hitPoint, nodeCenter, nodeSize);
            continue; // Early termination for this branch
        }
        
        // Process children if not at max depth
        if (validMask != 0u && entry.level < octreeInfo.maxDepth) {
            let childSize = nodeSize * 0.5;
            var childIdx = 0u;
            
            // Traverse children in front-to-back order
            for (var i = 0u; i < 8u; i++) {
                let octant = traversalOrder[i];
                let mask = 1u << octant;
                
                if ((validMask & mask) != 0u) {
                    let childOrigin = getChildBounds(entry.origin, nodeSize, octant);
                    let childBounds = rayBoxIntersection(ray, childOrigin, childOrigin + vec3<f32>(childSize));
                    
                    if (childBounds.x >= 0.0 && childBounds.x < result.t) {
                        // Add child to stack
                        if (stackPtr < STACK_SIZE) {
                            let childNodeIdx = childPointer + childIdx;
                            stack[stackPtr] = StackEntry(childNodeIdx, childBounds.x, childBounds.y, 
                                                       entry.level + 1u, childOrigin);
                            stackPtr += 1u;
                        }
                    }
                    childIdx += 1u;
                }
            }
        }
    }
    
    results[rayIdx] = result;
}

// Entry point for single ray traversal (for debugging)
@compute @workgroup_size(1)
fn traverseSingleRay(@builtin(global_invocation_id) id: vec3<u32>) {
    traverseOctree(id);
}