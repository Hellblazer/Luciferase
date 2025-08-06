// Triangle voxelization compute shader
// Parallel voxelization using GPU compute

struct Triangle {
    v0: vec3<f32>,
    v1: vec3<f32>,
    v2: vec3<f32>,
    material: u32,
}

struct VoxelGrid {
    resolution: u32,
    min: vec3<f32>,
    max: vec3<f32>,
}

struct Voxel {
    position: vec3<u32>,
    material: u32,
    coverage: f32,
}

// Input buffers
@group(0) @binding(0) var<storage, read> triangles: array<Triangle>;
@group(0) @binding(1) var<uniform> grid: VoxelGrid;

// Output buffer
@group(0) @binding(2) var<storage, read_write> voxels: array<atomic<u32>>;
@group(0) @binding(3) var<storage, read_write> voxel_data: array<Voxel>;
@group(0) @binding(4) var<storage, read_write> voxel_count: atomic<u32>;

// Constants
const EPSILON: f32 = 0.000001;

// Encode 3D voxel position to 1D index
fn encode_voxel_key(x: u32, y: u32, z: u32) -> u32 {
    return x + y * grid.resolution + z * grid.resolution * grid.resolution;
}

// Triangle-box intersection using SAT
fn triangle_box_intersect(v0: vec3<f32>, v1: vec3<f32>, v2: vec3<f32>,
                          box_center: vec3<f32>, box_half: vec3<f32>) -> bool {
    // Translate triangle to box coordinate system
    let tv0 = v0 - box_center;
    let tv1 = v1 - box_center;
    let tv2 = v2 - box_center;
    
    // Test box face normals (x, y, z axes)
    let tri_min = min(min(tv0, tv1), tv2);
    let tri_max = max(max(tv0, tv1), tv2);
    
    if (tri_min.x > box_half.x || tri_max.x < -box_half.x ||
        tri_min.y > box_half.y || tri_max.y < -box_half.y ||
        tri_min.z > box_half.z || tri_max.z < -box_half.z) {
        return false;
    }
    
    // Compute triangle edges
    let e0 = tv1 - tv0;
    let e1 = tv2 - tv1;
    let e2 = tv0 - tv2;
    
    // Test 9 edge cross products
    // Edge0 x Box axes
    if (!test_axis(tv0, tv1, tv2, vec3<f32>(0.0, -e0.z, e0.y), box_half)) { return false; }
    if (!test_axis(tv0, tv1, tv2, vec3<f32>(e0.z, 0.0, -e0.x), box_half)) { return false; }
    if (!test_axis(tv0, tv1, tv2, vec3<f32>(-e0.y, e0.x, 0.0), box_half)) { return false; }
    
    // Edge1 x Box axes
    if (!test_axis(tv0, tv1, tv2, vec3<f32>(0.0, -e1.z, e1.y), box_half)) { return false; }
    if (!test_axis(tv0, tv1, tv2, vec3<f32>(e1.z, 0.0, -e1.x), box_half)) { return false; }
    if (!test_axis(tv0, tv1, tv2, vec3<f32>(-e1.y, e1.x, 0.0), box_half)) { return false; }
    
    // Edge2 x Box axes
    if (!test_axis(tv0, tv1, tv2, vec3<f32>(0.0, -e2.z, e2.y), box_half)) { return false; }
    if (!test_axis(tv0, tv1, tv2, vec3<f32>(e2.z, 0.0, -e2.x), box_half)) { return false; }
    if (!test_axis(tv0, tv1, tv2, vec3<f32>(-e2.y, e2.x, 0.0), box_half)) { return false; }
    
    // Test triangle plane
    let normal = cross(e0, e1);
    let d = abs(dot(tv0, normal));
    let box_radius = dot(box_half, abs(normal));
    
    return d <= box_radius;
}

// Test separation along an axis
fn test_axis(v0: vec3<f32>, v1: vec3<f32>, v2: vec3<f32>,
             axis: vec3<f32>, box_half: vec3<f32>) -> bool {
    // Skip degenerate axes
    if (length(axis) < EPSILON) {
        return true;
    }
    
    // Project triangle onto axis
    let p0 = dot(v0, axis);
    let p1 = dot(v1, axis);
    let p2 = dot(v2, axis);
    
    let tri_min = min(min(p0, p1), p2);
    let tri_max = max(max(p0, p1), p2);
    
    // Project box onto axis
    let box_radius = dot(box_half, abs(axis));
    
    return !(tri_min > box_radius || tri_max < -box_radius);
}

// Convert world position to voxel coordinates
fn world_to_voxel(world_pos: vec3<f32>) -> vec3<f32> {
    let grid_size = grid.max - grid.min;
    let normalized = (world_pos - grid.min) / grid_size;
    return normalized * f32(grid.resolution);
}

// Convert voxel coordinates to world position (center)
fn voxel_to_world(voxel_pos: vec3<u32>) -> vec3<f32> {
    let grid_size = grid.max - grid.min;
    let voxel_size = grid_size / f32(grid.resolution);
    return grid.min + (vec3<f32>(voxel_pos) + vec3<f32>(0.5)) * voxel_size;
}

// Compute voxel coverage using sampling
fn compute_coverage(v0: vec3<f32>, v1: vec3<f32>, v2: vec3<f32>,
                   box_center: vec3<f32>, box_half: vec3<f32>) -> f32 {
    // Simple 2x2x2 sampling
    var hits = 0u;
    let samples = 8u;
    
    for (var i = 0u; i < 2u; i++) {
        for (var j = 0u; j < 2u; j++) {
            for (var k = 0u; k < 2u; k++) {
                let offset = vec3<f32>(
                    select(-0.5, 0.5, i == 1u),
                    select(-0.5, 0.5, j == 1u),
                    select(-0.5, 0.5, k == 1u)
                );
                let sample_point = box_center + offset * box_half;
                
                if (point_in_triangle(sample_point, v0, v1, v2)) {
                    hits++;
                }
            }
        }
    }
    
    return f32(hits) / f32(samples);
}

// Test if point is inside triangle (barycentric coordinates)
fn point_in_triangle(p: vec3<f32>, v0: vec3<f32>, v1: vec3<f32>, v2: vec3<f32>) -> bool {
    let v0v1 = v1 - v0;
    let v0v2 = v2 - v0;
    let v0p = p - v0;
    
    let dot00 = dot(v0v2, v0v2);
    let dot01 = dot(v0v2, v0v1);
    let dot02 = dot(v0v2, v0p);
    let dot11 = dot(v0v1, v0v1);
    let dot12 = dot(v0v1, v0p);
    
    let inv_denom = 1.0 / (dot00 * dot11 - dot01 * dot01);
    let u = (dot11 * dot02 - dot01 * dot12) * inv_denom;
    let v = (dot00 * dot12 - dot01 * dot02) * inv_denom;
    
    return (u >= 0.0) && (v >= 0.0) && (u + v <= 1.0);
}

@compute @workgroup_size(64)
fn main(@builtin(global_invocation_id) global_id: vec3<u32>) {
    let triangle_idx = global_id.x;
    
    // Check bounds
    if (triangle_idx >= arrayLength(&triangles)) {
        return;
    }
    
    let tri = triangles[triangle_idx];
    
    // Get triangle bounding box in voxel space
    let tri_min_world = min(min(tri.v0, tri.v1), tri.v2);
    let tri_max_world = max(max(tri.v0, tri.v1), tri.v2);
    
    let tri_min_voxel = world_to_voxel(tri_min_world);
    let tri_max_voxel = world_to_voxel(tri_max_world);
    
    // Clamp to grid bounds
    let min_x = max(0u, u32(tri_min_voxel.x));
    let min_y = max(0u, u32(tri_min_voxel.y));
    let min_z = max(0u, u32(tri_min_voxel.z));
    let max_x = min(grid.resolution - 1u, u32(ceil(tri_max_voxel.x)));
    let max_y = min(grid.resolution - 1u, u32(ceil(tri_max_voxel.y)));
    let max_z = min(grid.resolution - 1u, u32(ceil(tri_max_voxel.z)));
    
    // Get voxel size
    let grid_size = grid.max - grid.min;
    let voxel_size = grid_size / f32(grid.resolution);
    let voxel_half = voxel_size * 0.5;
    
    // Test each voxel in bounding box
    for (var x = min_x; x <= max_x; x++) {
        for (var y = min_y; y <= max_y; y++) {
            for (var z = min_z; z <= max_z; z++) {
                let voxel_center = voxel_to_world(vec3<u32>(x, y, z));
                
                if (triangle_box_intersect(tri.v0, tri.v1, tri.v2, voxel_center, voxel_half)) {
                    let coverage = compute_coverage(tri.v0, tri.v1, tri.v2, voxel_center, voxel_half);
                    
                    if (coverage > 0.0) {
                        // Atomically mark voxel as occupied
                        let voxel_key = encode_voxel_key(x, y, z);
                        let old_val = atomicOr(&voxels[voxel_key], 1u);
                        
                        // If this is the first time this voxel is hit, store data
                        if (old_val == 0u) {
                            let idx = atomicAdd(&voxel_count, 1u);
                            voxel_data[idx] = Voxel(
                                vec3<u32>(x, y, z),
                                tri.material,
                                coverage
                            );
                        }
                    }
                }
            }
        }
    }
}