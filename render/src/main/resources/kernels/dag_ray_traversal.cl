/**
 * F3.1: DAG Ray Traversal Kernel
 *
 * GPU-accelerated ray traversal for Sparse Voxel DAGs
 * Uses absolute addressing for child node lookups
 *
 * Phase 3 GPU Acceleration Implementation
 * Target: 10x-25x speedup over CPU DAG traversal
 */

// ==================== Constants ====================

#define MAX_TRAVERSAL_DEPTH 32
#define EPSILON 1e-6f
#define INFINITY 1e30f

// ==================== Data Structures ====================

/**
 * DAG Node Structure (matches ESVONodeUnified)
 * Total: 8 bytes per node
 */
typedef struct {
    uint childDescriptor;    // childMask (8 bits) + childPtr (24 bits)
    uint attributes;         // Material/voxel data
} DAGNode;

/**
 * Ray Structure for GPU Traversal
 */
typedef struct {
    float3 origin;
    float3 direction;
    float tMin;
    float tMax;
} Ray;

/**
 * Traversal Result Structure
 */
typedef struct {
    int hit;                 // 0 = miss, 1 = hit
    float t;                 // Distance along ray
    float3 normal;           // Surface normal (computed)
    uint voxelValue;         // Voxel data from attributes
    uint nodeIndex;          // Index of hit node
    uint iterations;         // Traversal iterations (for profiling)
} IntersectionResult;

// ==================== Utility Functions ====================

/**
 * Extract child mask from node descriptor (lower 8 bits)
 */
uint getChildMask(uint descriptor) {
    return descriptor & 0xFF;
}

/**
 * Extract child pointer from node descriptor (upper 24 bits)
 */
uint getChildPtr(uint descriptor) {
    return (descriptor >> 8) & 0xFFFFFF;
}

/**
 * Check if child exists at octant
 */
bool hasChild(uint childMask, uint octant) {
    return (childMask & (1u << octant)) != 0;
}

/**
 * Get octant from ray relative to node center
 * Returns octant 0-7 based on ray direction signs
 */
uint getOctant(float3 rayOrigin, float3 nodeCenter, float3 rayDir) {
    uint octant = 0;

    // Determine which octant ray would hit
    // Octant bits: x | (y << 1) | (z << 2)
    if (rayOrigin.x > nodeCenter.x) octant |= 1u;
    if (rayOrigin.y > nodeCenter.y) octant |= 2u;
    if (rayOrigin.z > nodeCenter.z) octant |= 4u;

    return octant;
}

// ==================== Ray-AABB Intersection ====================

/**
 * Compute intersection of ray with axis-aligned bounding box
 * Returns true if intersection exists, updates tMin and tMax
 */
bool rayAABBIntersection(Ray ray, float3 boxMin, float3 boxMax, float* tMin, float* tMax) {
    float3 inv_dir = 1.0f / (ray.direction + (float3)(EPSILON));

    float3 t0 = (boxMin - ray.origin) * inv_dir;
    float3 t1 = (boxMax - ray.origin) * inv_dir;

    float3 tmin_vals = min(t0, t1);
    float3 tmax_vals = max(t0, t1);

    float tmin = max(max(tmin_vals.x, tmin_vals.y), tmin_vals.z);
    float tmax = min(min(tmax_vals.x, tmax_vals.y), tmax_vals.z);

    if (tmin > tmax || tmax < ray.tMin || tmin > ray.tMax) {
        return false;
    }

    *tMin = max(tmin, ray.tMin);
    *tMax = min(tmax, ray.tMax);
    return true;
}

// ==================== DAG Traversal ====================

/**
 * Traverse DAG from root to find ray intersection
 *
 * nodePool: Array of DAG nodes
 * childPointers: Array of child pointer redirections (for absolute addressing)
 * nodeCount: Total number of nodes
 */
IntersectionResult traverseDAG(
    __global const DAGNode* nodePool,
    __global const uint* childPointers,
    uint nodeCount,
    Ray ray
) {
    IntersectionResult result;
    result.hit = 0;
    result.t = INFINITY;
    result.iterations = 0;
    result.nodeIndex = 0;

    // Stack-based traversal
    uint stack[MAX_TRAVERSAL_DEPTH];
    uint stackPtr = 0;

    // Start with root node (index 0)
    stack[stackPtr++] = 0;

    // Traverse until stack empty or hit found
    while (stackPtr > 0 && stackPtr < MAX_TRAVERSAL_DEPTH) {
        uint nodeIdx = stack[--stackPtr];
        result.iterations++;

        if (nodeIdx >= nodeCount) {
            continue; // Invalid node index
        }

        DAGNode node = nodePool[nodeIdx];
        uint childMask = getChildMask(node.childDescriptor);
        uint childPtr = getChildPtr(node.childDescriptor);

        // If leaf node, record intersection
        if (childMask == 0) {
            result.hit = 1;
            result.voxelValue = node.attributes;
            result.nodeIndex = nodeIdx;
            // In real implementation, compute t and normal here
            break;
        }

        // Internal node: push children to stack
        for (uint octant = 0; octant < 8; octant++) {
            if (hasChild(childMask, octant)) {
                // Absolute addressing: direct array lookup
                uint childIdx = childPtr + octant;
                if (childIdx < nodeCount && stackPtr < MAX_TRAVERSAL_DEPTH) {
                    stack[stackPtr++] = childIdx;
                }
            }
        }
    }

    return result;
}

// ==================== Kernel Entry Point ====================

/**
 * Main kernel: Traverse rays through DAG and find intersections
 *
 * Each work item processes one ray
 */
__kernel void rayTraverseDAG(
    __global const DAGNode* nodePool,
    __global const uint* childPointers,
    const uint nodeCount,

    __global const Ray* rays,
    const uint rayCount,

    __global IntersectionResult* results
) {
    uint gid = get_global_id(0);

    if (gid >= rayCount) {
        return;
    }

    // Load ray
    Ray ray = rays[gid];

    // Traverse DAG
    IntersectionResult result = traverseDAG(nodePool, childPointers, nodeCount, ray);

    // Store result
    results[gid] = result;
}

/**
 * Batch kernel variant: Process multiple rays per work item for better coherence
 * Optional optimization for later phases
 */
__kernel void rayTraverseDAGBatch(
    __global const DAGNode* nodePool,
    __global const uint* childPointers,
    const uint nodeCount,

    __global const Ray* rays,
    const uint rayCount,
    const uint raysPerItem,

    __global IntersectionResult* results
) {
    uint gid = get_global_id(0);
    uint rayStart = gid * raysPerItem;
    uint rayEnd = min(rayStart + raysPerItem, rayCount);

    for (uint i = rayStart; i < rayEnd; i++) {
        Ray ray = rays[i];
        IntersectionResult result = traverseDAG(nodePool, childPointers, nodeCount, ray);
        results[i] = result;
    }
}
