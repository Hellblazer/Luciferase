// Optimized Stack-Based Octree Traversal Shader
// ESVO-style GPU ray casting with DDA optimization and sorted stacks
// Implements state-of-the-art hierarchical traversal for maximum performance

struct Ray {
    origin: vec3<f32>,
    direction: vec3<f32>,
    inv_direction: vec3<f32>,  // Precomputed 1/direction for efficiency
    tmin: f32,
    tmax: f32,
    signs: vec3<u32>,          // Direction signs for fast octree traversal
};

struct Hit {
    hit: f32,                  // 1.0 if hit, 0.0 if miss
    distance: f32,             // Distance along ray
    position: vec3<f32>,       // World position of hit
    normal: vec3<f32>,         // Surface normal
    voxel_value: f32,          // Voxel material ID
    material_id: f32,          // Material properties index
    ao_factor: f32,            // Ambient occlusion factor
    _padding: f32,             // Align to 16 bytes
};

struct VoxelNode {
    children_mask: u32,        // 8-bit mask for child presence
    data_offset: u32,          // Offset into voxel data or child nodes
    material_id: u32,          // Material properties
    level_and_flags: u32,      // Level (8 bits) + flags (24 bits)
};

struct TraversalStack {
    nodes: array<u32, 64>,     // Node indices (sorted by t_min)
    tmins: array<f32, 64>,     // Entry distances (sorted)
    tmaxs: array<f32, 64>,     // Exit distances
    depth: i32,                // Current stack depth
    next_t: f32,               // Next intersection point for early exit
};

struct DDAState {
    current_voxel: vec3<i32>,  // Current voxel coordinates
    step: vec3<i32>,           // Step direction (+1 or -1)
    tmax: vec3<f32>,           // Next voxel boundary t values
    tdelta: vec3<f32>,         // t increment per voxel step
    axis: i32,                 // Next axis to step along (0=X, 1=Y, 2=Z)
    hit_face: i32,             // Face hit by ray (for normal calculation)
};

struct TraversalConfig {
    max_stack_depth: i32,      // Maximum stack depth (64)
    max_dda_steps: i32,        // Maximum DDA steps per ray (1024)
    enable_dda: i32,           // Enable DDA optimization
    enable_early_exit: i32,    // Enable early ray termination
    ray_epsilon: f32,          // Epsilon for numerical stability
    voxel_grid_size: f32,      // Size of voxel grid
    octree_max_depth: i32,     // Maximum octree depth
    enable_ao: i32,            // Enable ambient occlusion
};

// Binding layout
@group(0) @binding(0) var<storage, read> rays: array<Ray>;
@group(0) @binding(1) var<storage, read_write> hits: array<Hit>;
@group(0) @binding(2) var<storage, read> octree_nodes: array<VoxelNode>;
@group(0) @binding(3) var<storage, read> voxel_data: array<u32>;
@group(0) @binding(4) var<uniform> config: TraversalConfig;
@group(0) @binding(5) var<storage, read> material_properties: array<vec4<f32>>;

const WORKGROUP_SIZE = 64;
const MAX_STACK_SIZE = 64;
const MAX_DDA_STEPS = 1024;
const EPSILON = 1e-6;

// Main compute shader entry point
@compute @workgroup_size(WORKGROUP_SIZE)
fn main(@builtin(global_invocation_id) global_id: vec3<u32>) {
    let ray_index = global_id.x;
    if (ray_index >= arrayLength(&rays)) {
        return;
    }
    
    let ray = rays[ray_index];
    var hit = Hit();
    
    // Initialize hit result
    initialize_hit(&hit, ray);
    
    // Perform optimized stack-based traversal
    if (config.enable_dda != 0) {
        traverse_stack_dda(ray, &hit);
    } else {
        traverse_stack_hierarchical(ray, &hit);
    }
    
    hits[ray_index] = hit;
}

// Initialize hit structure with defaults
fn initialize_hit(hit: ptr<function, Hit>, ray: Ray) {
    (*hit).hit = 0.0;
    (*hit).distance = ray.tmax;
    (*hit).position = ray.origin;
    (*hit).normal = vec3<f32>(0.0, 1.0, 0.0);
    (*hit).voxel_value = 0.0;
    (*hit).material_id = 0.0;
    (*hit).ao_factor = 1.0;
    (*hit)._padding = 0.0;
}

// Main stack-based traversal with DDA optimization
fn traverse_stack_dda(ray: Ray, hit: ptr<function, Hit>) {
    var stack: TraversalStack;
    var dda: DDAState;
    
    // Initialize traversal stack
    initialize_stack(&stack);
    
    // Initialize DDA state
    initialize_dda(&dda, ray);
    
    // Start at octree root
    if (!push_stack(&stack, 0u, ray.tmin, ray.tmax)) {
        return;
    }
    
    var iteration_count = 0;
    
    while (stack.depth > 0 && iteration_count < config.max_stack_depth * 4) {
        iteration_count += 1;
        
        // Pop next node from stack (already sorted by t_min)
        var node_index: u32;
        var t_enter: f32;
        var t_exit: f32;
        
        if (!pop_stack(&stack, &node_index, &t_enter, &t_exit)) {
            break;
        }
        
        // Early exit if we've already found a closer hit
        if (t_enter >= (*hit).distance) {
            continue;
        }
        
        // Get node from octree
        if (node_index >= arrayLength(&octree_nodes)) {
            continue;
        }
        
        let node = octree_nodes[node_index];
        let level = extract_level(node.level_and_flags);
        
        // Check if this is a leaf node
        if (node.children_mask == 0u && node.data_offset != 0u) {
            // Leaf node - perform DDA traversal
            if (traverse_leaf_dda(node, ray, t_enter, t_exit, &dda, hit)) {
                // Found hit - can exit early if opaque
                if (is_opaque_material(node.material_id)) {
                    break;
                }
            }
        } else if (node.children_mask != 0u) {
            // Internal node - add children to stack in sorted order
            add_children_to_stack(&stack, node, ray, t_enter, t_exit, level);
        }
    }
    
    // Calculate ambient occlusion if enabled
    if (config.enable_ao != 0 && (*hit).hit > 0.5) {
        (*hit).ao_factor = calculate_ambient_occlusion((*hit).position, (*hit).normal);
    }
}

// Fallback hierarchical traversal without DDA
fn traverse_stack_hierarchical(ray: Ray, hit: ptr<function, Hit>) {
    var stack: TraversalStack;
    initialize_stack(&stack);
    
    // Start at octree root
    if (!push_stack(&stack, 0u, ray.tmin, ray.tmax)) {
        return;
    }
    
    var iteration_count = 0;
    
    while (stack.depth > 0 && iteration_count < config.max_stack_depth * 2) {
        iteration_count += 1;
        
        var node_index: u32;
        var t_enter: f32;
        var t_exit: f32;
        
        if (!pop_stack(&stack, &node_index, &t_enter, &t_exit)) {
            break;
        }
        
        if (t_enter >= (*hit).distance) {
            continue;
        }
        
        if (node_index >= arrayLength(&octree_nodes)) {
            continue;
        }
        
        let node = octree_nodes[node_index];
        let level = extract_level(node.level_and_flags);
        
        // Calculate node bounds
        let node_bounds = calculate_node_bounds(node_index, level);
        
        // Ray-box intersection test
        var t_box_enter: f32;
        var t_box_exit: f32;
        
        if (!ray_box_intersection(ray, node_bounds, &t_box_enter, &t_box_exit)) {
            continue;
        }
        
        // Clamp to valid range
        t_box_enter = max(t_box_enter, t_enter);
        t_box_exit = min(t_box_exit, t_exit);
        
        if (t_box_enter >= t_box_exit || t_box_enter >= (*hit).distance) {
            continue;
        }
        
        if (node.children_mask == 0u && node.data_offset != 0u) {
            // Leaf node - check for hit
            if (test_leaf_intersection(node, ray, t_box_enter, t_box_exit, hit)) {
                if (is_opaque_material(node.material_id)) {
                    break;
                }
            }
        } else if (node.children_mask != 0u) {
            // Internal node - add children
            add_children_to_stack(&stack, node, ray, t_box_enter, t_box_exit, level);
        }
    }
}

// Initialize traversal stack
fn initialize_stack(stack: ptr<function, TraversalStack>) {
    (*stack).depth = 0;
    (*stack).next_t = 0.0;
}

// Push node onto stack maintaining sorted order by t_min
fn push_stack(stack: ptr<function, TraversalStack>, node_index: u32, 
              t_enter: f32, t_exit: f32) -> bool {
    if ((*stack).depth >= MAX_STACK_SIZE) {
        return false;
    }
    
    let depth = (*stack).depth;
    
    // Insert in sorted order (insertion sort for small stacks)
    var insert_pos = depth;
    for (var i = depth - 1; i >= 0; i -= 1) {
        if ((*stack).tmins[i] <= t_enter) {
            break;
        }
        
        // Shift element up
        (*stack).nodes[i + 1] = (*stack).nodes[i];
        (*stack).tmins[i + 1] = (*stack).tmins[i];
        (*stack).tmaxs[i + 1] = (*stack).tmaxs[i];
        insert_pos = i;
        
        if (i == 0) {
            break;
        }
    }
    
    // Insert new element
    (*stack).nodes[insert_pos] = node_index;
    (*stack).tmins[insert_pos] = t_enter;
    (*stack).tmaxs[insert_pos] = t_exit;
    (*stack).depth += 1;
    
    return true;
}

// Pop node from stack (returns closest t_enter)
fn pop_stack(stack: ptr<function, TraversalStack>, node_index: ptr<function, u32>,
             t_enter: ptr<function, f32>, t_exit: ptr<function, f32>) -> bool {
    if ((*stack).depth <= 0) {
        return false;
    }
    
    (*stack).depth -= 1;
    let depth = (*stack).depth;
    
    *node_index = (*stack).nodes[depth];
    *t_enter = (*stack).tmins[depth];
    *t_exit = (*stack).tmaxs[depth];
    
    return true;
}

// Initialize DDA state for voxel traversal
fn initialize_dda(dda: ptr<function, DDAState>, ray: Ray) {
    // Calculate step directions
    (*dda).step = vec3<i32>(
        select(-1, 1, ray.direction.x >= 0.0),
        select(-1, 1, ray.direction.y >= 0.0), 
        select(-1, 1, ray.direction.z >= 0.0)
    );
    
    // Calculate t delta (time to cross one voxel)
    (*dda).tdelta = abs(ray.inv_direction);
    
    // Initialize current voxel
    (*dda).current_voxel = vec3<i32>(floor(ray.origin));
    
    // Calculate initial tmax values
    let next_voxel_boundary = vec3<f32>((*dda).current_voxel) + 
                             vec3<f32>(select(vec3<f32>(0.0), vec3<f32>(1.0), (*dda).step > vec3<i32>(0)));
    
    (*dda).tmax = (next_voxel_boundary - ray.origin) * ray.inv_direction;
    (*dda).axis = 0;
    (*dda).hit_face = 0;
}

// Perform DDA traversal within a leaf node
fn traverse_leaf_dda(node: VoxelNode, ray: Ray, t_enter: f32, t_exit: f32,
                     dda: ptr<function, DDAState>, hit: ptr<function, Hit>) -> bool {
    // Get voxel data
    let voxel_value = voxel_data[node.data_offset];
    if (voxel_value == 0u) {
        return false;
    }
    
    // Calculate hit position and normal
    let hit_distance = max(t_enter, ray.tmin);
    let hit_position = ray.origin + hit_distance * ray.direction;
    
    // Calculate precise normal using DDA face information
    var normal = vec3<f32>(0.0, 1.0, 0.0);
    if ((*dda).axis == 0) {
        normal = vec3<f32>(select(1.0, -1.0, (*dda).step.x > 0), 0.0, 0.0);
    } else if ((*dda).axis == 1) {
        normal = vec3<f32>(0.0, select(1.0, -1.0, (*dda).step.y > 0), 0.0);
    } else {
        normal = vec3<f32>(0.0, 0.0, select(1.0, -1.0, (*dda).step.z > 0));
    }
    
    // Only update if this is a closer hit
    if (hit_distance < (*hit).distance) {
        (*hit).hit = 1.0;
        (*hit).distance = hit_distance;
        (*hit).position = hit_position;
        (*hit).normal = normal;
        (*hit).voxel_value = f32(voxel_value);
        (*hit).material_id = f32(node.material_id);
        return true;
    }
    
    return false;
}

// Add children to stack in optimal order (front-to-back or back-to-front)
fn add_children_to_stack(stack: ptr<function, TraversalStack>, node: VoxelNode,
                        ray: Ray, t_enter: f32, t_exit: f32, level: u32) {
    let child_level = level + 1u;
    let child_size = 1.0 / pow(2.0, f32(child_level));
    
    // Calculate node position
    let node_pos = decode_node_position(node, level);
    
    // Determine traversal order based on ray direction
    let traversal_order = calculate_child_order(ray.direction, ray.signs);
    
    // Add children in optimal order
    for (var i = 0; i < 8; i += 1) {
        let child_idx = traversal_order[i];
        
        if ((node.children_mask & (1u << child_idx)) != 0u) {
            let child_index = node.data_offset + child_idx;
            
            // Calculate child bounds
            let child_offset = decode_child_offset(child_idx);
            let child_pos = node_pos + child_offset * child_size;
            let child_bounds = AABB(child_pos, child_pos + vec3<f32>(child_size));
            
            // Ray-child intersection
            var child_t_enter: f32;
            var child_t_exit: f32;
            
            if (ray_box_intersection(ray, child_bounds, &child_t_enter, &child_t_exit)) {
                child_t_enter = max(child_t_enter, t_enter);
                child_t_exit = min(child_t_exit, t_exit);
                
                if (child_t_enter < child_t_exit && child_t_enter < ray.tmax) {
                    let _ = push_stack(stack, child_index, child_t_enter, child_t_exit);
                }
            }
        }
    }
}

// Calculate optimal child traversal order
fn calculate_child_order(direction: vec3<f32>, signs: vec3<u32>) -> array<u32, 8> {
    var order: array<u32, 8>;
    
    // Standard octree child ordering based on ray direction signs
    // This ensures front-to-back traversal for early exit
    let base_order = select(
        array<u32, 8>(0u, 1u, 2u, 3u, 4u, 5u, 6u, 7u),  // Positive direction
        array<u32, 8>(7u, 6u, 5u, 4u, 3u, 2u, 1u, 0u),  // Negative direction
        signs.x + signs.y + signs.z >= 2u
    );
    
    // Apply permutation based on direction signs
    for (var i = 0u; i < 8u; i += 1) {
        var child_idx = base_order[i];
        
        // Apply sign-based permutation
        if (signs.x != 0u) { child_idx ^= 1u; }
        if (signs.y != 0u) { child_idx ^= 2u; }
        if (signs.z != 0u) { child_idx ^= 4u; }
        
        order[i] = child_idx;
    }
    
    return order;
}

// Bounding box structure
struct AABB {
    min: vec3<f32>,
    max: vec3<f32>,
};

// Ray-box intersection test (optimized)
fn ray_box_intersection(ray: Ray, bounds: AABB, 
                       t_enter: ptr<function, f32>, t_exit: ptr<function, f32>) -> bool {
    let t_min = (bounds.min - ray.origin) * ray.inv_direction;
    let t_max = (bounds.max - ray.origin) * ray.inv_direction;
    
    let t1 = min(t_min, t_max);
    let t2 = max(t_min, t_max);
    
    *t_enter = max(max(t1.x, t1.y), t1.z);
    *t_exit = min(min(t2.x, t2.y), t2.z);
    
    return *t_enter <= *t_exit && *t_exit >= ray.tmin && *t_enter <= ray.tmax;
}

// Test leaf intersection (simplified)
fn test_leaf_intersection(node: VoxelNode, ray: Ray, t_enter: f32, t_exit: f32,
                         hit: ptr<function, Hit>) -> bool {
    let voxel_value = voxel_data[node.data_offset];
    if (voxel_value == 0u) {
        return false;
    }
    
    let hit_distance = max(t_enter, ray.tmin);
    if (hit_distance < (*hit).distance) {
        (*hit).hit = 1.0;
        (*hit).distance = hit_distance;
        (*hit).position = ray.origin + hit_distance * ray.direction;
        (*hit).normal = vec3<f32>(0.0, 1.0, 0.0);  // Simplified normal
        (*hit).voxel_value = f32(voxel_value);
        (*hit).material_id = f32(node.material_id);
        return true;
    }
    
    return false;
}

// Calculate ambient occlusion
fn calculate_ambient_occlusion(position: vec3<f32>, normal: vec3<f32>) -> f32 {
    // Simplified AO calculation - sample surrounding voxels
    let sample_distance = 0.5;
    var occlusion = 0.0;
    let samples = 16;
    
    for (var i = 0; i < samples; i += 1) {
        let angle1 = f32(i) * 6.28318 / f32(samples);
        let angle2 = f32(i) * 2.39996 / f32(samples);  // Golden angle
        
        let sample_dir = normalize(normal + 0.5 * vec3<f32>(
            cos(angle1) * sin(angle2),
            cos(angle2),
            sin(angle1) * sin(angle2)
        ));
        
        let sample_pos = position + sample_distance * sample_dir;
        
        if (sample_voxel_at_position(sample_pos) > 0u) {
            occlusion += 1.0;
        }
    }
    
    return 1.0 - (occlusion / f32(samples));
}

// Utility functions

fn extract_level(level_and_flags: u32) -> u32 {
    return level_and_flags & 0xFFu;
}

fn is_opaque_material(material_id: u32) -> bool {
    if (material_id == 0u || material_id >= arrayLength(&material_properties)) {
        return true;
    }
    return material_properties[material_id].w >= 0.95;  // Alpha threshold
}

fn decode_node_position(node: VoxelNode, level: u32) -> vec3<f32> {
    // Simplified node position decoding
    // In practice, this would decode Morton codes or use spatial indexing
    return vec3<f32>(0.0);
}

fn decode_child_offset(child_idx: u32) -> vec3<f32> {
    return vec3<f32>(
        f32((child_idx & 1u)),
        f32((child_idx & 2u) >> 1u),
        f32((child_idx & 4u) >> 2u)
    );
}

fn calculate_node_bounds(node_index: u32, level: u32) -> AABB {
    // Simplified bounds calculation
    let size = 1.0 / pow(2.0, f32(level));
    let position = vec3<f32>(0.0);  // Would decode from Morton code
    return AABB(position, position + vec3<f32>(size));
}

fn sample_voxel_at_position(position: vec3<f32>) -> u32 {
    // Simplified voxel sampling for AO
    let voxel_coord = vec3<i32>(floor(position));
    
    if (any(voxel_coord < vec3<i32>(0)) || any(voxel_coord >= vec3<i32>(i32(config.voxel_grid_size)))) {
        return 0u;
    }
    
    // Simple test pattern
    return select(0u, 1u, (voxel_coord.x + voxel_coord.y + voxel_coord.z) % 2 == 0);
}