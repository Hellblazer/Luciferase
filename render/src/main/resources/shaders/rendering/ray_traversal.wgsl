// Voxel Ray Traversal Compute Shader
// High-performance GPU ray casting through sparse voxel octrees

struct Ray {
    origin: vec3<f32>,
    direction: vec3<f32>,
    tmin: f32,
    tmax: f32,
};

struct Hit {
    hit: f32,          // 1.0 if hit, 0.0 if miss
    distance: f32,     // Distance along ray
    position: vec3<f32>, // World position of hit
    normal: vec3<f32>,   // Surface normal
    voxel_value: f32,    // Voxel material ID
    material_id: f32,    // Material properties index
};

struct VoxelNode {
    children_mask: u32,  // 8-bit mask for child presence
    data_offset: u32,    // Offset into voxel data
    material_id: u32,    // Material properties
    level: u32,          // Octree level
};

struct TraversalConfig {
    max_steps: i32,
    step_size: f32,
    enable_occlusion: i32,
    enable_early_exit: i32,
    ray_epsilon: f32,
    voxel_grid_size: i32,
    octree_max_depth: i32,
    _padding: i32,
};

// Binding layout
@group(0) @binding(0) var<storage, read> rays: array<Ray>;
@group(0) @binding(1) var<storage, read_write> hits: array<Hit>;
@group(0) @binding(2) var<storage, read> voxel_octree: array<VoxelNode>;
@group(0) @binding(3) var<storage, read> voxel_data: array<u32>;
@group(0) @binding(4) var<uniform> config: TraversalConfig;

const WORKGROUP_SIZE = 64;

@compute @workgroup_size(WORKGROUP_SIZE)
fn main(@builtin(global_invocation_id) global_id: vec3<u32>) {
    let ray_index = global_id.x;
    if (ray_index >= arrayLength(&rays)) {
        return;
    }
    
    let ray = rays[ray_index];
    var hit = Hit();
    
    // Initialize hit result
    hit.hit = 0.0;
    hit.distance = ray.tmax;
    hit.position = ray.origin;
    hit.normal = vec3<f32>(0.0, 1.0, 0.0);
    hit.voxel_value = 0.0;
    hit.material_id = 0.0;
    
    // Perform hierarchical ray traversal
    if (config.enable_early_exit != 0) {
        traverse_hierarchical(ray, &hit);
    } else {
        traverse_uniform_grid(ray, &hit);
    }
    
    hits[ray_index] = hit;
}

fn traverse_hierarchical(ray: Ray, hit: ptr<function, Hit>) {
    // Hierarchical octree traversal with early termination
    var stack: array<u32, 32>;  // Stack for octree traversal
    var stack_ptr = 0;
    
    // Start at root node (index 0)
    stack[0] = 0u;
    stack_ptr = 1;
    
    var t = ray.tmin;
    let inv_direction = 1.0 / ray.direction;
    
    while (stack_ptr > 0 && t < ray.tmax) {
        stack_ptr -= 1;
        let node_index = stack[stack_ptr];
        
        if (node_index >= arrayLength(&voxel_octree)) {
            continue;
        }
        
        let node = voxel_octree[node_index];
        
        // Calculate node bounds (simplified for uniform octree)
        let level_size = f32(1 << (config.octree_max_depth - i32(node.level)));
        let node_pos = decode_morton_position(node_index, i32(node.level));
        let node_min = node_pos * level_size;
        let node_max = node_min + vec3<f32>(level_size);
        
        // Ray-box intersection test
        let t_min_box = (node_min - ray.origin) * inv_direction;
        let t_max_box = (node_max - ray.origin) * inv_direction;
        
        let t_enter = max(max(min(t_min_box.x, t_max_box.x),
                              min(t_min_box.y, t_max_box.y)),
                          min(t_min_box.z, t_max_box.z));
        let t_exit = min(min(max(t_min_box.x, t_max_box.x),
                             max(t_min_box.y, t_max_box.y)),
                         max(t_min_box.z, t_max_box.z));
        
        // Check if ray intersects this node
        if (t_enter <= t_exit && t_exit >= t && t_enter <= ray.tmax) {
            // Check if this is a leaf node with voxel data
            if (node.children_mask == 0u && node.data_offset != 0u) {
                // Leaf node - check for voxel hit
                let voxel_value = voxel_data[node.data_offset];
                if (voxel_value != 0u) {
                    hit.hit = 1.0;
                    hit.distance = max(t_enter, t);
                    hit.position = ray.origin + hit.distance * ray.direction;
                    hit.normal = calculate_box_normal(hit.position, node_min, node_max);
                    hit.voxel_value = f32(voxel_value);
                    hit.material_id = f32(node.material_id);
                    return;
                }
            } else if (node.children_mask != 0u) {
                // Internal node - add children to stack (back to front for early exit)
                for (var child_idx = 0; child_idx < 8; child_idx += 1) {
                    if ((node.children_mask & (1u << u32(child_idx))) != 0u) {
                        let child_index = node.data_offset + u32(child_idx);
                        if (stack_ptr < 31) {
                            stack[stack_ptr] = child_index;
                            stack_ptr += 1;
                        }
                    }
                }
            }
        }
        
        t = max(t, t_exit + config.ray_epsilon);
    }
}

fn traverse_uniform_grid(ray: Ray, hit: ptr<function, Hit>) {
    // Simple DDA traversal through uniform voxel grid
    var t = ray.tmin;
    let dt = config.step_size;
    
    for (var step = 0; step < config.max_steps; step += 1) {
        if (t >= ray.tmax) {
            break;
        }
        
        let pos = ray.origin + t * ray.direction;
        let voxel_value = sample_voxel_direct(pos);
        
        if (voxel_value != 0u) {
            hit.hit = 1.0;
            hit.distance = t;
            hit.position = pos;
            hit.normal = calculate_gradient_normal(pos);
            hit.voxel_value = f32(voxel_value);
            hit.material_id = f32(voxel_value);
            break;
        }
        
        t += dt;
    }
}

fn sample_voxel_direct(pos: vec3<f32>) -> u32 {
    let voxel_coord = vec3<i32>(floor(pos));
    
    // Bounds check
    let grid_size = config.voxel_grid_size;
    if (voxel_coord.x < 0 || voxel_coord.x >= grid_size ||
        voxel_coord.y < 0 || voxel_coord.y >= grid_size ||
        voxel_coord.z < 0 || voxel_coord.z >= grid_size) {
        return 0u;
    }
    
    // Simple test pattern for demonstration
    if (voxel_coord.y == 0) {
        return 1u;  // Ground plane
    }
    
    // Simple structure
    if (abs(voxel_coord.x) < 5 && abs(voxel_coord.z) < 5 && voxel_coord.y < 3) {
        return 2u;
    }
    
    return 0u;  // Empty space
}

fn calculate_gradient_normal(pos: vec3<f32>) -> vec3<f32> {
    let epsilon = 0.1;
    
    let dx = f32(sample_voxel_direct(pos + vec3<f32>(epsilon, 0.0, 0.0))) - 
             f32(sample_voxel_direct(pos - vec3<f32>(epsilon, 0.0, 0.0)));
    let dy = f32(sample_voxel_direct(pos + vec3<f32>(0.0, epsilon, 0.0))) - 
             f32(sample_voxel_direct(pos - vec3<f32>(0.0, epsilon, 0.0)));
    let dz = f32(sample_voxel_direct(pos + vec3<f32>(0.0, 0.0, epsilon))) - 
             f32(sample_voxel_direct(pos - vec3<f32>(0.0, 0.0, epsilon)));
    
    var normal = vec3<f32>(-dx, -dy, -dz);
    
    // Ensure non-zero normal
    if (dot(normal, normal) < 0.001) {
        normal = vec3<f32>(0.0, 1.0, 0.0);
    } else {
        normal = normalize(normal);
    }
    
    return normal;
}

fn calculate_box_normal(hit_pos: vec3<f32>, box_min: vec3<f32>, box_max: vec3<f32>) -> vec3<f32> {
    let center = (box_min + box_max) * 0.5;
    let extent = (box_max - box_min) * 0.5;
    let local_pos = (hit_pos - center) / extent;
    
    // Find the axis with the largest absolute coordinate
    let abs_pos = abs(local_pos);
    
    if (abs_pos.x > abs_pos.y && abs_pos.x > abs_pos.z) {
        return vec3<f32>(sign(local_pos.x), 0.0, 0.0);
    } else if (abs_pos.y > abs_pos.z) {
        return vec3<f32>(0.0, sign(local_pos.y), 0.0);
    } else {
        return vec3<f32>(0.0, 0.0, sign(local_pos.z));
    }
}

fn decode_morton_position(morton_code: u32, level: i32) -> vec3<f32> {
    // Decode 3D Morton code to position
    var x = 0u;
    var y = 0u;
    var z = 0u;
    var code = morton_code;
    
    for (var i = 0; i < level; i += 1) {
        x |= (code & 1u) << u32(i);
        code >>= 1u;
        y |= (code & 1u) << u32(i);
        code >>= 1u;
        z |= (code & 1u) << u32(i);
        code >>= 1u;
    }
    
    return vec3<f32>(f32(x), f32(y), f32(z));
}

// Utility functions for advanced features

fn apply_early_ray_termination(ray: Ray, current_t: f32) -> bool {
    // Terminate rays that are unlikely to contribute significantly
    return current_t > ray.tmax * 0.99;
}

fn calculate_ray_differential(ray_index: u32) -> vec2<f32> {
    // Calculate ray differentials for anti-aliasing
    // This is a simplified version - real implementation would use adjacent rays
    return vec2<f32>(1.0 / f32(WORKGROUP_SIZE));
}

fn apply_temporal_filtering(current_hit: Hit, previous_hit: Hit, blend_factor: f32) -> Hit {
    // Temporal anti-aliasing (simplified)
    var result = current_hit;
    if (previous_hit.hit > 0.5) {
        result.position = mix(previous_hit.position, current_hit.position, blend_factor);
        result.normal = normalize(mix(previous_hit.normal, current_hit.normal, blend_factor));
    }
    return result;
}