// Voxelization compute shader for ESVO
// Converts triangles to voxels using conservative rasterization

struct Triangle {
    v0: vec3<f32>,
    v1: vec3<f32>,
    v2: vec3<f32>,
    materialId: u32
}

struct VoxelGrid {
    resolution: u32,
    worldSize: f32,
    originX: f32,
    originY: f32,
    originZ: f32,
    _padding: array<u32, 3>
}

struct Voxel {
    position: vec3<u32>,
    color: u32,      // RGBA8
    normal: u32,     // Compressed normal
    materialId: u32
}

// Bind groups
@group(0) @binding(0) var<storage, read> triangles: array<Triangle>;
@group(0) @binding(1) var<storage, write> voxels: array<atomic<u32>>;
@group(0) @binding(2) var<uniform> grid: VoxelGrid;
@group(0) @binding(3) var<storage, write> voxelCount: atomic<u32>;

// Constants
const EPSILON: f32 = 1e-6;
const MAX_VOXELS_PER_TRIANGLE: u32 = 1000u; // Safety limit

// Convert world position to voxel coordinates
fn worldToVoxel(worldPos: vec3<f32>) -> vec3<i32> {
    let origin = vec3<f32>(grid.originX, grid.originY, grid.originZ);
    let voxelSize = grid.worldSize / f32(grid.resolution);
    let relPos = worldPos - origin;
    return vec3<i32>(relPos / voxelSize);
}

// Convert voxel coordinates to grid index
fn voxelToIndex(voxelPos: vec3<i32>) -> u32 {
    if (any(voxelPos < vec3<i32>(0)) || any(voxelPos >= vec3<i32>(i32(grid.resolution)))) {
        return 0xFFFFFFFFu; // Invalid index
    }
    return u32(voxelPos.x) + 
           u32(voxelPos.y) * grid.resolution + 
           u32(voxelPos.z) * grid.resolution * grid.resolution;
}

// Triangle-box intersection test using Separating Axis Theorem (SAT)
fn triangleBoxIntersection(tri: Triangle, boxMin: vec3<f32>, boxMax: vec3<f32>) -> bool {
    // Move triangle to box origin
    let boxCenter = (boxMin + boxMax) * 0.5;
    let boxHalfSize = (boxMax - boxMin) * 0.5;
    
    let v0 = tri.v0 - boxCenter;
    let v1 = tri.v1 - boxCenter;
    let v2 = tri.v2 - boxCenter;
    
    // Triangle edges
    let e0 = v1 - v0;
    let e1 = v2 - v1;
    let e2 = v0 - v2;
    
    // Test box normals (AABB axes)
    let minVert = min(min(v0, v1), v2);
    let maxVert = max(max(v0, v1), v2);
    if (any(maxVert < -boxHalfSize) || any(minVert > boxHalfSize)) {
        return false;
    }
    
    // Test triangle normal
    let triNormal = cross(e0, e1);
    let d = dot(triNormal, v0);
    let r = dot(abs(triNormal), boxHalfSize);
    if (abs(d) > r) {
        return false;
    }
    
    // Test cross products of edges with box axes
    // Edge 0
    var p = vec3<f32>(0.0, -e0.z, e0.y);
    var proj = dot(vec3<f32>(0.0, v0.y, v0.z), p);
    r = boxHalfSize.y * abs(e0.z) + boxHalfSize.z * abs(e0.y);
    if (abs(proj) > r) { return false; }
    
    p = vec3<f32>(e0.z, 0.0, -e0.x);
    proj = dot(vec3<f32>(v0.x, 0.0, v0.z), p);
    r = boxHalfSize.x * abs(e0.z) + boxHalfSize.z * abs(e0.x);
    if (abs(proj) > r) { return false; }
    
    p = vec3<f32>(-e0.y, e0.x, 0.0);
    proj = dot(vec3<f32>(v0.x, v0.y, 0.0), p);
    r = boxHalfSize.x * abs(e0.y) + boxHalfSize.y * abs(e0.x);
    if (abs(proj) > r) { return false; }
    
    // Similar tests for edges 1 and 2...
    // (Omitted for brevity - same pattern as edge 0)
    
    return true;
}

// Compute triangle normal
fn computeTriangleNormal(tri: Triangle) -> vec3<f32> {
    let e0 = tri.v1 - tri.v0;
    let e1 = tri.v2 - tri.v0;
    return normalize(cross(e0, e1));
}

// Pack normal into 32-bit integer (11-11-10 format)
fn packNormal(normal: vec3<f32>) -> u32 {
    let n = normal * 0.5 + 0.5; // Map from [-1,1] to [0,1]
    let x = u32(n.x * 2047.0); // 11 bits
    let y = u32(n.y * 2047.0); // 11 bits
    let z = u32(n.z * 1023.0); // 10 bits
    return (x << 21u) | (y << 10u) | z;
}

// Conservative voxelization of a single triangle
fn voxelizeTriangle(triIdx: u32) {
    let tri = triangles[triIdx];
    let normal = computeTriangleNormal(tri);
    let packedNormal = packNormal(normal);
    
    // Compute triangle bounding box in voxel space
    let minWorld = min(min(tri.v0, tri.v1), tri.v2);
    let maxWorld = max(max(tri.v0, tri.v1), tri.v2);
    
    let minVoxel = worldToVoxel(minWorld);
    let maxVoxel = worldToVoxel(maxWorld);
    
    // Clamp to grid bounds
    let gridMax = i32(grid.resolution) - 1;
    let minBound = max(minVoxel, vec3<i32>(0));
    let maxBound = min(maxVoxel, vec3<i32>(gridMax));
    
    // Conservative voxelization: test all voxels in bounding box
    var voxelizedCount = 0u;
    let voxelSize = grid.worldSize / f32(grid.resolution);
    let origin = vec3<f32>(grid.originX, grid.originY, grid.originZ);
    
    for (var z = minBound.z; z <= maxBound.z; z++) {
        for (var y = minBound.y; y <= maxBound.y; y++) {
            for (var x = minBound.x; x <= maxBound.x; x++) {
                let voxelPos = vec3<i32>(x, y, z);
                let voxelWorldMin = origin + vec3<f32>(voxelPos) * voxelSize;
                let voxelWorldMax = voxelWorldMin + vec3<f32>(voxelSize);
                
                if (triangleBoxIntersection(tri, voxelWorldMin, voxelWorldMax)) {
                    let idx = voxelToIndex(voxelPos);
                    if (idx != 0xFFFFFFFFu) {
                        // Atomic set voxel data (simplified - real implementation needs proper atomics)
                        atomicStore(&voxels[idx], packedNormal);
                        voxelizedCount += 1u;
                        
                        // Safety check
                        if (voxelizedCount >= MAX_VOXELS_PER_TRIANGLE) {
                            return;
                        }
                    }
                }
            }
        }
    }
    
    // Update global voxel count
    if (voxelizedCount > 0u) {
        atomicAdd(&voxelCount, voxelizedCount);
    }
}

@compute @workgroup_size(64)
fn voxelizeTriangles(@builtin(global_invocation_id) id: vec3<u32>) {
    let triIdx = id.x;
    if (triIdx >= arrayLength(&triangles)) {
        return;
    }
    
    voxelizeTriangle(triIdx);
}