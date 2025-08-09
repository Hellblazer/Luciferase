// Triangle mesh voxelization compute shader
// Uses conservative rasterization for accurate voxel coverage

struct VoxelParams {
    resolution: vec3<u32>,
    voxelSize: f32,
    boundsMin: vec3<f32>,
    boundsMax: vec3<f32>,
}

struct Triangle {
    v0: vec3<f32>,
    v1: vec3<f32>,
    v2: vec3<f32>,
    normal: vec3<f32>,
    color: vec4<f32>,
}

@group(0) @binding(0) var<storage, read> triangles: array<Triangle>;
@group(0) @binding(1) var<storage, read_write> voxelGrid: array<atomic<u32>>;
@group(0) @binding(2) var<uniform> params: VoxelParams;
@group(0) @binding(3) var<storage, read_write> voxelColors: array<vec4<f32>>;

// Convert world position to voxel grid coordinates
fn worldToVoxel(worldPos: vec3<f32>) -> vec3<i32> {
    let normalized = (worldPos - params.boundsMin) / (params.boundsMax - params.boundsMin);
    return vec3<i32>(normalized * vec3<f32>(params.resolution));
}

// Get linear index from 3D voxel coordinates
fn getVoxelIndex(coord: vec3<i32>) -> u32 {
    if (any(coord < vec3<i32>(0)) || any(coord >= vec3<i32>(params.resolution))) {
        return 0xFFFFFFFFu; // Invalid index
    }
    return u32(coord.z) * params.resolution.x * params.resolution.y +
           u32(coord.y) * params.resolution.x +
           u32(coord.x);
}

// Check if a point is inside a triangle (2D projection)
fn pointInTriangle2D(p: vec2<f32>, a: vec2<f32>, b: vec2<f32>, c: vec2<f32>) -> bool {
    let v0 = c - a;
    let v1 = b - a;
    let v2 = p - a;
    
    let dot00 = dot(v0, v0);
    let dot01 = dot(v0, v1);
    let dot02 = dot(v0, v2);
    let dot11 = dot(v1, v1);
    let dot12 = dot(v1, v2);
    
    let invDenom = 1.0 / (dot00 * dot11 - dot01 * dot01);
    let u = (dot11 * dot02 - dot01 * dot12) * invDenom;
    let v = (dot00 * dot12 - dot01 * dot02) * invDenom;
    
    return (u >= 0.0) && (v >= 0.0) && (u + v <= 1.0);
}

// Separating Axis Theorem for triangle-box intersection
fn triangleBoxIntersection(
    tri: Triangle,
    boxMin: vec3<f32>,
    boxMax: vec3<f32>
) -> bool {
    let boxCenter = (boxMin + boxMax) * 0.5;
    let boxHalfSize = (boxMax - boxMin) * 0.5;
    
    // Translate triangle to box center coordinate system
    let v0 = tri.v0 - boxCenter;
    let v1 = tri.v1 - boxCenter;
    let v2 = tri.v2 - boxCenter;
    
    // Test box normals (x, y, z axes)
    let minV = min(min(v0, v1), v2);
    let maxV = max(max(v0, v1), v2);
    
    if (any(maxV < -boxHalfSize) || any(minV > boxHalfSize)) {
        return false;
    }
    
    // Test triangle normal
    let e0 = v1 - v0;
    let e1 = v2 - v1;
    let e2 = v0 - v2;
    let normal = cross(e0, -e2);
    
    let r = boxHalfSize.x * abs(normal.x) + 
            boxHalfSize.y * abs(normal.y) + 
            boxHalfSize.z * abs(normal.z);
    let s = dot(normal, v0);
    
    if (abs(s) > r) {
        return false;
    }
    
    // Test cross products of edges with box axes
    // This is simplified - full SAT test would check all 9 cross products
    
    return true;
}

// Conservative voxelization of a triangle
fn voxelizeTriangle(triangleIdx: u32) {
    let tri = triangles[triangleIdx];
    
    // Calculate triangle bounding box in voxel space
    let minWorld = min(min(tri.v0, tri.v1), tri.v2);
    let maxWorld = max(max(tri.v0, tri.v1), tri.v2);
    
    let minVoxel = worldToVoxel(minWorld);
    let maxVoxel = worldToVoxel(maxWorld);
    
    // Expand bounds by 1 for conservative rasterization
    let expandedMin = max(minVoxel - vec3<i32>(1), vec3<i32>(0));
    let expandedMax = min(maxVoxel + vec3<i32>(1), vec3<i32>(params.resolution) - vec3<i32>(1));
    
    // Iterate over voxels in bounding box
    for (var z = expandedMin.z; z <= expandedMax.z; z++) {
        for (var y = expandedMin.y; y <= expandedMax.y; y++) {
            for (var x = expandedMin.x; x <= expandedMax.x; x++) {
                let voxelCoord = vec3<i32>(x, y, z);
                
                // Calculate voxel bounds in world space
                let voxelMin = params.boundsMin + 
                    vec3<f32>(voxelCoord) * params.voxelSize;
                let voxelMax = voxelMin + vec3<f32>(params.voxelSize);
                
                // Check triangle-voxel intersection
                if (triangleBoxIntersection(tri, voxelMin, voxelMax)) {
                    let voxelIdx = getVoxelIndex(voxelCoord);
                    if (voxelIdx != 0xFFFFFFFFu) {
                        // Mark voxel as occupied
                        atomicOr(&voxelGrid[voxelIdx], 1u);
                        
                        // Store color (simplified - should handle multiple triangles per voxel)
                        voxelColors[voxelIdx] = tri.color;
                    }
                }
            }
        }
    }
}

// Alternative: 2D slice-based voxelization (faster for thin triangles)
fn voxelizeTriangleSlices(triangleIdx: u32) {
    let tri = triangles[triangleIdx];
    
    // Determine dominant axis based on triangle normal
    let normal = abs(tri.normal);
    var axis = 2u; // Default to Z
    if (normal.x > normal.y && normal.x > normal.z) {
        axis = 0u; // X dominant
    } else if (normal.y > normal.z) {
        axis = 1u; // Y dominant
    }
    
    // Project triangle to 2D based on dominant axis
    var p0: vec2<f32>;
    var p1: vec2<f32>;
    var p2: vec2<f32>;
    var minSlice: i32;
    var maxSlice: i32;
    
    if (axis == 0u) {
        // YZ projection
        p0 = tri.v0.yz;
        p1 = tri.v1.yz;
        p2 = tri.v2.yz;
        minSlice = worldToVoxel(vec3<f32>(min(min(tri.v0.x, tri.v1.x), tri.v2.x), 0.0, 0.0)).x;
        maxSlice = worldToVoxel(vec3<f32>(max(max(tri.v0.x, tri.v1.x), tri.v2.x), 0.0, 0.0)).x;
    } else if (axis == 1u) {
        // XZ projection
        p0 = tri.v0.xz;
        p1 = tri.v1.xz;
        p2 = tri.v2.xz;
        minSlice = worldToVoxel(vec3<f32>(0.0, min(min(tri.v0.y, tri.v1.y), tri.v2.y), 0.0)).y;
        maxSlice = worldToVoxel(vec3<f32>(0.0, max(max(tri.v0.y, tri.v1.y), tri.v2.y), 0.0)).y;
    } else {
        // XY projection
        p0 = tri.v0.xy;
        p1 = tri.v1.xy;
        p2 = tri.v2.xy;
        minSlice = worldToVoxel(vec3<f32>(0.0, 0.0, min(min(tri.v0.z, tri.v1.z), tri.v2.z))).z;
        maxSlice = worldToVoxel(vec3<f32>(0.0, 0.0, max(max(tri.v0.z, tri.v1.z), tri.v2.z))).z;
    }
    
    // Process each slice
    for (var slice = minSlice; slice <= maxSlice; slice++) {
        // Rasterize triangle in 2D slice
        let sliceMin = worldToVoxel(params.boundsMin);
        let sliceMax = worldToVoxel(params.boundsMax);
        
        // Simple 2D rasterization (can be optimized with edge functions)
        for (var i = sliceMin.x; i <= sliceMax.x; i++) {
            for (var j = sliceMin.y; j <= sliceMax.y; j++) {
                var voxelCoord: vec3<i32>;
                var testPoint: vec2<f32>;
                
                if (axis == 0u) {
                    voxelCoord = vec3<i32>(slice, i, j);
                    testPoint = vec2<f32>(f32(i), f32(j)) * params.voxelSize + params.boundsMin.yz;
                } else if (axis == 1u) {
                    voxelCoord = vec3<i32>(i, slice, j);
                    testPoint = vec2<f32>(f32(i), f32(j)) * params.voxelSize + params.boundsMin.xz;
                } else {
                    voxelCoord = vec3<i32>(i, j, slice);
                    testPoint = vec2<f32>(f32(i), f32(j)) * params.voxelSize + params.boundsMin.xy;
                }
                
                if (pointInTriangle2D(testPoint, p0, p1, p2)) {
                    let voxelIdx = getVoxelIndex(voxelCoord);
                    if (voxelIdx != 0xFFFFFFFFu) {
                        atomicOr(&voxelGrid[voxelIdx], 1u);
                        voxelColors[voxelIdx] = tri.color;
                    }
                }
            }
        }
    }
}

@compute @workgroup_size(64, 1, 1)
fn main(@builtin(global_invocation_id) id: vec3<u32>) {
    let triangleIdx = id.x;
    
    // Check bounds
    if (triangleIdx >= arrayLength(&triangles)) {
        return;
    }
    
    // Choose voxelization method based on triangle characteristics
    let tri = triangles[triangleIdx];
    let area = length(cross(tri.v1 - tri.v0, tri.v2 - tri.v0)) * 0.5;
    
    if (area < params.voxelSize * params.voxelSize) {
        // Small triangle - use simple method
        voxelizeTriangle(triangleIdx);
    } else {
        // Large triangle - use slice-based method for efficiency
        voxelizeTriangleSlices(triangleIdx);
    }
}