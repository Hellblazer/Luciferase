// Ray marching shader for voxel octree rendering
// Implements DDA (Digital Differential Analyzer) for efficient voxel traversal

struct Uniforms {
    viewMatrix: mat4x4<f32>,
    projectionMatrix: mat4x4<f32>,
    cameraPosition: vec3<f32>,
    currentLOD: i32,
    lightDirection: vec3<f32>,
    ambientLight: f32,
    screenWidth: u32,
    screenHeight: u32,
    time: f32,
    _padding: f32,
}

struct OctreeNode {
    childMask: u32,      // Bitmask indicating which children exist
    dataOffset: u32,     // Offset to voxel data or child nodes
    nodeType: u32,       // 0=internal, 1=leaf
    voxelData: u32,      // Packed voxel color/material for leaf nodes
}

struct RayHit {
    hit: bool,
    position: vec3<f32>,
    normal: vec3<f32>,
    distance: f32,
    voxelColor: vec3<f32>,
    nodeLevel: u32,
}

@group(0) @binding(0) var<storage, read> octreeNodes: array<OctreeNode>;
@group(0) @binding(1) var<storage, read_write> frameBuffer: array<vec4<f32>>;
@group(0) @binding(2) var<uniform> uniforms: Uniforms;

const MAX_STEPS: u32 = 256u;
const MAX_OCTREE_DEPTH: u32 = 8u;
const EPSILON: f32 = 0.001;

// Generate ray from screen coordinates
fn getRayDirection(pixelCoord: vec2<f32>) -> vec3<f32> {
    // Convert pixel to NDC space (-1 to 1)
    let ndc = vec2<f32>(
        (pixelCoord.x / f32(uniforms.screenWidth)) * 2.0 - 1.0,
        1.0 - (pixelCoord.y / f32(uniforms.screenHeight)) * 2.0
    );
    
    // Inverse projection to get view space direction
    let viewDir = vec4<f32>(ndc, 1.0, 1.0);
    
    // Inverse view matrix to get world space direction
    let invView = transpose(uniforms.viewMatrix); // Simplified - should be proper inverse
    let worldDir = invView * viewDir;
    
    return normalize(worldDir.xyz - uniforms.cameraPosition);
}

// Check if ray intersects AABB
fn rayAABBIntersection(
    rayOrigin: vec3<f32>,
    rayDir: vec3<f32>,
    boxMin: vec3<f32>,
    boxMax: vec3<f32>
) -> vec2<f32> {
    let invDir = 1.0 / rayDir;
    let t1 = (boxMin - rayOrigin) * invDir;
    let t2 = (boxMax - rayOrigin) * invDir;
    
    let tMin = min(t1, t2);
    let tMax = max(t1, t2);
    
    let tNear = max(max(tMin.x, tMin.y), tMin.z);
    let tFar = min(min(tMax.x, tMax.y), tMax.z);
    
    if (tNear > tFar || tFar < 0.0) {
        return vec2<f32>(-1.0, -1.0);
    }
    
    return vec2<f32>(max(tNear, 0.0), tFar);
}

// Get child octant index from position
fn getOctantIndex(position: vec3<f32>, nodeCenter: vec3<f32>) -> u32 {
    var index = 0u;
    if (position.x > nodeCenter.x) { index |= 1u; }
    if (position.y > nodeCenter.y) { index |= 2u; }
    if (position.z > nodeCenter.z) { index |= 4u; }
    return index;
}

// Traverse octree using DDA
fn traverseOctree(
    rayOrigin: vec3<f32>,
    rayDir: vec3<f32>
) -> RayHit {
    var hit: RayHit;
    hit.hit = false;
    hit.distance = 1e10;
    
    // Start with root node bounding box
    let rootMin = vec3<f32>(0.0, 0.0, 0.0);
    let rootMax = vec3<f32>(1.0, 1.0, 1.0);
    
    // Check root intersection
    let rootIntersect = rayAABBIntersection(rayOrigin, rayDir, rootMin, rootMax);
    if (rootIntersect.x < 0.0) {
        return hit;
    }
    
    // Stack for octree traversal
    var nodeStack: array<u32, 64>;
    var depthStack: array<u32, 64>;
    var minStack: array<vec3<f32>, 64>;
    var maxStack: array<vec3<f32>, 64>;
    var stackPtr = 0u;
    
    // Push root node
    nodeStack[0] = 0u;
    depthStack[0] = 0u;
    minStack[0] = rootMin;
    maxStack[0] = rootMax;
    stackPtr = 1u;
    
    var steps = 0u;
    
    while (stackPtr > 0u && steps < MAX_STEPS) {
        steps += 1u;
        stackPtr -= 1u;
        
        let nodeIdx = nodeStack[stackPtr];
        let depth = depthStack[stackPtr];
        let nodeMin = minStack[stackPtr];
        let nodeMax = maxStack[stackPtr];
        
        // Skip if we've hit max depth or node index is invalid
        if (depth >= MAX_OCTREE_DEPTH || nodeIdx >= arrayLength(&octreeNodes)) {
            continue;
        }
        
        let node = octreeNodes[nodeIdx];
        
        // Check if this is a leaf node
        if (node.nodeType == 1u) {
            // Leaf node - check for voxel hit
            if (node.voxelData != 0u) {
                let intersect = rayAABBIntersection(rayOrigin, rayDir, nodeMin, nodeMax);
                if (intersect.x >= 0.0 && intersect.x < hit.distance) {
                    hit.hit = true;
                    hit.distance = intersect.x;
                    hit.position = rayOrigin + rayDir * intersect.x;
                    
                    // Compute normal based on hit position
                    let center = (nodeMin + nodeMax) * 0.5;
                    let diff = hit.position - center;
                    let absX = abs(diff.x);
                    let absY = abs(diff.y);
                    let absZ = abs(diff.z);
                    
                    if (absX > absY && absX > absZ) {
                        hit.normal = vec3<f32>(sign(diff.x), 0.0, 0.0);
                    } else if (absY > absZ) {
                        hit.normal = vec3<f32>(0.0, sign(diff.y), 0.0);
                    } else {
                        hit.normal = vec3<f32>(0.0, 0.0, sign(diff.z));
                    }
                    
                    // Extract color from packed voxel data
                    let r = f32((node.voxelData >> 16u) & 0xFFu) / 255.0;
                    let g = f32((node.voxelData >> 8u) & 0xFFu) / 255.0;
                    let b = f32(node.voxelData & 0xFFu) / 255.0;
                    hit.voxelColor = vec3<f32>(r, g, b);
                    hit.nodeLevel = depth;
                }
            }
        } else {
            // Internal node - traverse children
            let nodeSize = (nodeMax - nodeMin) * 0.5;
            let nodeCenter = nodeMin + nodeSize;
            
            // Check each child octant
            for (var i = 0u; i < 8u; i++) {
                let childBit = 1u << i;
                if ((node.childMask & childBit) != 0u) {
                    // Calculate child bounds
                    var childMin = nodeMin;
                    var childMax = nodeCenter;
                    
                    if ((i & 1u) != 0u) {
                        childMin.x = nodeCenter.x;
                        childMax.x = nodeMax.x;
                    }
                    if ((i & 2u) != 0u) {
                        childMin.y = nodeCenter.y;
                        childMax.y = nodeMax.y;
                    }
                    if ((i & 4u) != 0u) {
                        childMin.z = nodeCenter.z;
                        childMax.z = nodeMax.z;
                    }
                    
                    // Check if ray intersects child
                    let childIntersect = rayAABBIntersection(rayOrigin, rayDir, childMin, childMax);
                    if (childIntersect.x >= 0.0 && stackPtr < 64u) {
                        // Calculate child node index
                        let childIdx = node.dataOffset + countOneBits(node.childMask & ((1u << i) - 1u));
                        
                        // Push child onto stack
                        nodeStack[stackPtr] = childIdx;
                        depthStack[stackPtr] = depth + 1u;
                        minStack[stackPtr] = childMin;
                        maxStack[stackPtr] = childMax;
                        stackPtr += 1u;
                    }
                }
            }
        }
    }
    
    return hit;
}

// Apply lighting to voxel color
fn applyLighting(
    color: vec3<f32>,
    normal: vec3<f32>,
    position: vec3<f32>
) -> vec3<f32> {
    // Ambient lighting
    var finalColor = color * uniforms.ambientLight;
    
    // Directional lighting
    let NdotL = max(dot(normal, -uniforms.lightDirection), 0.0);
    finalColor += color * NdotL * (1.0 - uniforms.ambientLight);
    
    // Simple fog for depth
    let distance = length(position - uniforms.cameraPosition);
    let fogFactor = exp(-distance * 0.01);
    finalColor = mix(vec3<f32>(0.7, 0.8, 0.9), finalColor, fogFactor);
    
    return finalColor;
}

@compute @workgroup_size(8, 8, 1)
fn main(@builtin(global_invocation_id) id: vec3<u32>) {
    let pixelCoord = vec2<f32>(f32(id.x), f32(id.y));
    
    // Check bounds
    if (id.x >= uniforms.screenWidth || id.y >= uniforms.screenHeight) {
        return;
    }
    
    // Generate ray
    let rayOrigin = uniforms.cameraPosition;
    let rayDir = getRayDirection(pixelCoord);
    
    // Traverse octree
    let hit = traverseOctree(rayOrigin, rayDir);
    
    // Calculate pixel index
    let pixelIdx = id.y * uniforms.screenWidth + id.x;
    
    // Write result to frame buffer
    if (hit.hit) {
        let litColor = applyLighting(hit.voxelColor, hit.normal, hit.position);
        frameBuffer[pixelIdx] = vec4<f32>(litColor, 1.0);
    } else {
        // Sky color
        let skyGradient = mix(
            vec3<f32>(0.5, 0.7, 1.0),
            vec3<f32>(0.1, 0.2, 0.4),
            rayDir.y * 0.5 + 0.5
        );
        frameBuffer[pixelIdx] = vec4<f32>(skyGradient, 1.0);
    }
}