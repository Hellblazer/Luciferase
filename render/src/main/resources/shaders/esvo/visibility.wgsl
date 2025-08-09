// Visibility and frustum culling compute shader
// Performs hierarchical occlusion culling on octree nodes

struct Frustum {
    planes: array<vec4<f32>, 6>,  // Frustum planes (normal.xyz, distance) - 6 * 16 = 96 bytes
    viewProjMatrix: mat4x4<f32>,  // 64 bytes
    cameraPosition: vec3<f32>,    // 12 bytes
    nearPlane: f32,                // 4 bytes
    farPlane: f32,                 // 4 bytes
    _padding: vec3<f32>,           // 12 bytes to align to 16-byte boundary
}

struct OctreeNode {
    childMask: u32,
    dataOffset: u32,
    nodeType: u32,
    voxelData: u32,
    boundsMin: vec3<f32>,
    boundsMax: vec3<f32>,
}

struct VisibilityResult {
    visible: u32,
    distance: f32,
    screenSize: f32,
    lodLevel: u32,
}

@group(0) @binding(0) var<storage, read> octreeNodes: array<OctreeNode>;
@group(0) @binding(1) var<storage, read_write> visibilityResults: array<VisibilityResult>;
@group(0) @binding(2) var<uniform> frustum: Frustum;
@group(0) @binding(3) var<storage, read_write> visibleNodeIndices: array<atomic<u32>>;
@group(0) @binding(4) var<storage, read_write> visibleNodeCount: atomic<u32>;

// Early Z-buffer for hierarchical occlusion culling
@group(1) @binding(0) var hierarchicalZBuffer: texture_2d<f32>;

const EPSILON: f32 = 0.0001;

// Test if AABB is on positive side of plane
fn aabbOnPositiveSide(boundsMin: vec3<f32>, boundsMax: vec3<f32>, plane: vec4<f32>) -> bool {
    // Find the vertex furthest in the direction of the plane normal
    let extent = (boundsMax - boundsMin) * 0.5;
    let center = (boundsMin + boundsMax) * 0.5;
    
    // Project box extent along plane normal
    let r = dot(abs(plane.xyz), extent);
    
    // Signed distance from center to plane
    let s = dot(plane.xyz, center) + plane.w;
    
    // Box intersects or is on positive side if s + r >= 0
    return s + r >= 0.0;
}

// Frustum culling test
fn frustumCullAABB(boundsMin: vec3<f32>, boundsMax: vec3<f32>) -> bool {
    // Test against all 6 frustum planes
    for (var i = 0u; i < 6u; i++) {
        if (!aabbOnPositiveSide(boundsMin, boundsMax, frustum.planes[i])) {
            return true; // Culled
        }
    }
    return false; // Not culled
}

// Project AABB to screen space and get bounding rectangle
fn projectAABBToScreen(boundsMin: vec3<f32>, boundsMax: vec3<f32>) -> vec4<f32> {
    var screenMin = vec2<f32>(1.0, 1.0);
    var screenMax = vec2<f32>(-1.0, -1.0);
    var minZ = 1.0;
    
    // Project each corner (unrolled loop to avoid non-constant array indexing)
    // Corner 0: (min.x, min.y, min.z)
    var worldPos = vec4<f32>(boundsMin.x, boundsMin.y, boundsMin.z, 1.0);
    var clipPos = frustum.viewProjMatrix * worldPos;
    if (clipPos.w > EPSILON) {
        let ndc = clipPos.xyz / clipPos.w;
        screenMin = min(screenMin, ndc.xy);
        screenMax = max(screenMax, ndc.xy);
        minZ = min(minZ, ndc.z);
    }
    
    // Corner 1: (max.x, min.y, min.z)
    worldPos = vec4<f32>(boundsMax.x, boundsMin.y, boundsMin.z, 1.0);
    clipPos = frustum.viewProjMatrix * worldPos;
    if (clipPos.w > EPSILON) {
        let ndc = clipPos.xyz / clipPos.w;
        screenMin = min(screenMin, ndc.xy);
        screenMax = max(screenMax, ndc.xy);
        minZ = min(minZ, ndc.z);
    }
    
    // Corner 2: (min.x, max.y, min.z)
    worldPos = vec4<f32>(boundsMin.x, boundsMax.y, boundsMin.z, 1.0);
    clipPos = frustum.viewProjMatrix * worldPos;
    if (clipPos.w > EPSILON) {
        let ndc = clipPos.xyz / clipPos.w;
        screenMin = min(screenMin, ndc.xy);
        screenMax = max(screenMax, ndc.xy);
        minZ = min(minZ, ndc.z);
    }
    
    // Corner 3: (max.x, max.y, min.z)
    worldPos = vec4<f32>(boundsMax.x, boundsMax.y, boundsMin.z, 1.0);
    clipPos = frustum.viewProjMatrix * worldPos;
    if (clipPos.w > EPSILON) {
        let ndc = clipPos.xyz / clipPos.w;
        screenMin = min(screenMin, ndc.xy);
        screenMax = max(screenMax, ndc.xy);
        minZ = min(minZ, ndc.z);
    }
    
    // Corner 4: (min.x, min.y, max.z)
    worldPos = vec4<f32>(boundsMin.x, boundsMin.y, boundsMax.z, 1.0);
    clipPos = frustum.viewProjMatrix * worldPos;
    if (clipPos.w > EPSILON) {
        let ndc = clipPos.xyz / clipPos.w;
        screenMin = min(screenMin, ndc.xy);
        screenMax = max(screenMax, ndc.xy);
        minZ = min(minZ, ndc.z);
    }
    
    // Corner 5: (max.x, min.y, max.z)
    worldPos = vec4<f32>(boundsMax.x, boundsMin.y, boundsMax.z, 1.0);
    clipPos = frustum.viewProjMatrix * worldPos;
    if (clipPos.w > EPSILON) {
        let ndc = clipPos.xyz / clipPos.w;
        screenMin = min(screenMin, ndc.xy);
        screenMax = max(screenMax, ndc.xy);
        minZ = min(minZ, ndc.z);
    }
    
    // Corner 6: (min.x, max.y, max.z)
    worldPos = vec4<f32>(boundsMin.x, boundsMax.y, boundsMax.z, 1.0);
    clipPos = frustum.viewProjMatrix * worldPos;
    if (clipPos.w > EPSILON) {
        let ndc = clipPos.xyz / clipPos.w;
        screenMin = min(screenMin, ndc.xy);
        screenMax = max(screenMax, ndc.xy);
        minZ = min(minZ, ndc.z);
    }
    
    // Corner 7: (max.x, max.y, max.z)
    worldPos = vec4<f32>(boundsMax.x, boundsMax.y, boundsMax.z, 1.0);
    clipPos = frustum.viewProjMatrix * worldPos;
    if (clipPos.w > EPSILON) {
        let ndc = clipPos.xyz / clipPos.w;
        screenMin = min(screenMin, ndc.xy);
        screenMax = max(screenMax, ndc.xy);
        minZ = min(minZ, ndc.z);
    }
    
    // Return screen bounds (min.xy, max.xy) in NDC space
    return vec4<f32>(screenMin, screenMax);
}

// Hierarchical Z-buffer occlusion test
fn isOccludedByHZB(screenBounds: vec4<f32>, nodeDepth: f32) -> bool {
    // Convert NDC to texture coordinates
    let uvMin = (screenBounds.xy + 1.0) * 0.5;
    let uvMax = (screenBounds.zw + 1.0) * 0.5;
    
    // Calculate mip level based on screen coverage
    let screenSize = uvMax - uvMin;
    let maxDim = max(screenSize.x, screenSize.y);
    let mipLevel = max(0.0, log2(maxDim * 512.0)); // Assuming 512x512 HZB
    
    // Sample hierarchical Z-buffer at appropriate mip level
    // textureLoad requires integer coordinates and mip level
    let texCoord = vec2<i32>((uvMin + uvMax) * 0.5 * vec2<f32>(textureDimensions(hierarchicalZBuffer)));
    let mipLevelInt = i32(mipLevel);
    let sampledDepth = textureLoad(
        hierarchicalZBuffer,
        texCoord,
        mipLevelInt
    ).r;
    
    // Compare with node depth
    return nodeDepth > sampledDepth + EPSILON;
}

// Calculate LOD level based on screen size and distance
fn calculateLOD(screenSize: f32, distance: f32) -> u32 {
    // Simple LOD calculation based on projected size
    if (screenSize > 0.5) { return 0u; } // Full detail
    if (screenSize > 0.25) { return 1u; }
    if (screenSize > 0.125) { return 2u; }
    if (screenSize > 0.0625) { return 3u; }
    if (screenSize > 0.03125) { return 4u; }
    return 5u; // Minimum detail
}

// Process octree node for visibility
fn processNode(nodeIdx: u32) {
    let node = octreeNodes[nodeIdx];
    var result: VisibilityResult;
    result.visible = 0u;
    
    // Frustum culling
    if (frustumCullAABB(node.boundsMin, node.boundsMax)) {
        visibilityResults[nodeIdx] = result;
        return;
    }
    
    // Calculate distance to camera
    let nodeCenter = (node.boundsMin + node.boundsMax) * 0.5;
    let distance = length(nodeCenter - frustum.cameraPosition);
    result.distance = distance;
    
    // Skip if beyond far plane
    if (distance > frustum.farPlane) {
        visibilityResults[nodeIdx] = result;
        return;
    }
    
    // Project to screen space
    let screenBounds = projectAABBToScreen(node.boundsMin, node.boundsMax);
    let screenSize = max(
        abs(screenBounds.z - screenBounds.x),
        abs(screenBounds.w - screenBounds.y)
    );
    result.screenSize = screenSize;
    
    // Calculate LOD
    result.lodLevel = calculateLOD(screenSize, distance);
    
    // Hierarchical occlusion culling (if HZB is available)
    // Note: Texture access in compute shaders requires careful setup
    // This is commented out as it requires proper texture binding
    // if (isOccludedByHZB(screenBounds, distance / frustum.farPlane)) {
    //     visibilityResults[nodeIdx] = result;
    //     return;
    // }
    
    // Node is visible
    result.visible = 1u;
    visibilityResults[nodeIdx] = result;
    
    // Add to visible list
    let visibleIdx = atomicAdd(&visibleNodeCount, 1u);
    if (visibleIdx < arrayLength(&visibleNodeIndices)) {
        atomicStore(&visibleNodeIndices[visibleIdx], nodeIdx);
    }
}

// Stack entry for iterative traversal
struct TraversalEntry {
    nodeIdx: u32,
    parentVisible: bool,
}

// Hierarchical traversal for large octrees (iterative version)
fn hierarchicalCull(startNodeIdx: u32, startParentVisible: bool) {
    // Use a fixed-size stack for traversal (max depth of 16 should be sufficient)
    var stack: array<TraversalEntry, 16>;
    var stackTop = 0u;
    
    // Push initial node
    stack[0] = TraversalEntry(startNodeIdx, startParentVisible);
    stackTop = 1u;
    
    while (stackTop > 0u) {
        stackTop--;
        let entry = stack[stackTop];
        let nodeIdx = entry.nodeIdx;
        let parentVisible = entry.parentVisible;
        
        let node = octreeNodes[nodeIdx];
        
        // Early out if parent was culled and node is small
        if (!parentVisible) {
            let nodeSize = length(node.boundsMax - node.boundsMin);
            if (nodeSize < 0.01) { // Small threshold
                continue;
            }
        }
        
        // Process this node
        processNode(nodeIdx);
        let result = visibilityResults[nodeIdx];
        
        // Add children to stack if visible
        if (result.visible == 1u && node.nodeType == 0u && stackTop < 12u) {
            var childIdx = node.dataOffset;
            for (var i = 0u; i < 8u; i++) {
                if ((node.childMask & (1u << i)) != 0u) {
                    stack[stackTop] = TraversalEntry(childIdx, true);
                    stackTop++;
                    childIdx++;
                }
            }
        }
    }
}

@compute @workgroup_size(64, 1, 1)
fn main(@builtin(global_invocation_id) id: vec3<u32>) {
    let nodeIdx = id.x;
    
    // Check bounds
    if (nodeIdx >= arrayLength(&octreeNodes)) {
        return;
    }
    
    // Initialize count on first thread
    if (nodeIdx == 0u) {
        atomicStore(&visibleNodeCount, 0u);
    }
    
    // Process node
    processNode(nodeIdx);
}