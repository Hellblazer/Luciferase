#include <metal_stdlib>
#include <metal_compute>
using namespace metal;

// Compute kernel configuration
// local_size_x = 32, local_size_y = 1, local_size_z = 1

// ESVO Node structure matching Java ESVONode (8 bytes total)
// Packed as two 32-bit uints to match Java implementation
struct ESVONode {
    uint packedData1;     // validMask(8) | nonLeafMask(8) | farPointerFlag(1) | childPointer(15)
    uint packedData2;     // contourMask(8) | contourPointer(24)
};

// Ray structure for traversal
struct Ray {
    float3 origin;
    float3 direction;
    float tMin;
    float tMax;
    uint pixelX;
    uint pixelY;
};

// Intersection result
struct HitInfo {
    float3 position;
    float3 normal;
    float4 color;
    float distance;
    uint nodeIndex;
    uint hit;            // Changed from bool to uint for SSBO compatibility
};

// Traversal stack for iterative traversal
#define MAX_STACK_DEPTH 23
struct StackEntry {
    uint nodeIndex;
    float tEnter;
    float tExit;
    float3 cellMin;
    float3 cellMax;
    uint level;
};

// Shader configuration macros
#ifdef ENABLE_SHADOWS
    #define EARLY_TERMINATION_THRESHOLD 0.99
#endif

#ifdef ENABLE_LOD
    uniform float lodBias;
    uniform float lodDistance;
#endif

// Utility functions

// Manual bit counting function (popcount equivalent)
uint popcount_manual(uint x) {
    x = x - ((x >> 1) & 0x55555555u);
    x = (x & 0x33333333u) + ((x >> 2) & 0x33333333u);
    x = (x + (x >> 4)) & 0x0F0F0F0Fu;
    x = x + (x >> 8);
    x = x + (x >> 16);
    return x & 0x0000003Fu;
}

// Extract fields from packed node data
uint getValidMask(ESVONode node) {
    return (node.packedData1 >> 24) & 0xFFu;
}

uint getNonLeafMask(ESVONode node) {
    return (node.packedData1 >> 16) & 0xFFu;
}

uint getFarPointerFlag(ESVONode node) {
    return (node.packedData1 >> 15) & 0x1u;
}

uint getChildPointer(ESVONode node) {
    return node.packedData1 & 0x7FFFu;
}

uint getContourMask(ESVONode node) {
    return (node.packedData2 >> 24) & 0xFFu;
}

uint getContourPointer(ESVONode node) {
    return node.packedData2 & 0xFFFFFFu;
}

float3 safe_inverse(float3 v) {
    const float epsilon = 1e-6;
    const float large_val = 1e6;
    return float3(
        abs(v.x) > epsilon ? 1.0 / v.x : large_val * sign(v.x),
        abs(v.y) > epsilon ? 1.0 / v.y : large_val * sign(v.y),
        abs(v.z) > epsilon ? 1.0 / v.z : large_val * sign(v.z)
    );
}

bool intersectAABB(float3 rayOrigin, float3 rayDir, float3 boxMin, float3 boxMax, thread float& tEnter, thread float& tExit) {
    float3 invDir = safe_inverse(rayDir);
    float3 t1 = (boxMin - rayOrigin) * invDir;
    float3 t2 = (boxMax - rayOrigin) * invDir;
    
    float3 tMin = min(t1, t2);
    float3 tMax = max(t1, t2);
    
    tEnter = max(max(tMin.x, tMin.y), tMin.z);
    tExit = min(min(tMax.x, tMax.y), tMax.z);
    
    return tEnter <= tExit && tExit > 0.0;
}

uint computeChildIndex(ESVONode node, uint octant, device uint* pages, constant uint& pageCount) {
    uint validMask = getValidMask(node);
    uint childPointer = getChildPointer(node);
    uint farPointerFlag = getFarPointerFlag(node);
    
    // Count bits set before this octant to get child offset
    uint mask = validMask & ((1u << octant) - 1u);
    uint offset = popcount_manual(mask);
    
    if (farPointerFlag != 0u) {
        // Far pointer - read from separate table
        uint pageIndex = childPointer + offset;
        if (pageIndex < pageCount) {
            return pages[pageIndex];
        } else {
            return 0u; // Invalid index
        }
    } else {
        // Direct pointer
        return childPointer + offset;
    }
}

float3 computeChildMin(float3 parentMin, float3 parentMax, uint octant) {
    float3 center = (parentMin + parentMax) * 0.5;
    return float3(
        (octant & 1u) != 0u ? center.x : parentMin.x,
        (octant & 2u) != 0u ? center.y : parentMin.y,
        (octant & 4u) != 0u ? center.z : parentMin.z
    );
}

float3 computeChildMax(float3 parentMin, float3 parentMax, uint octant) {
    float3 center = (parentMin + parentMax) * 0.5;
    return float3(
        (octant & 1u) != 0u ? parentMax.x : center.x,
        (octant & 2u) != 0u ? parentMax.y : center.y,
        (octant & 4u) != 0u ? parentMax.z : center.z
    );
}

uint getOctant(float3 rayOrigin, float3 rayDir, float3 cellMin, float3 cellMax, float t) {
    float3 hitPoint = rayOrigin + rayDir * t;
    float3 center = (cellMin + cellMax) * 0.5;
    
    uint octant = 0u;
    if (hitPoint.x >= center.x) octant |= 1u;
    if (hitPoint.y >= center.y) octant |= 2u;
    if (hitPoint.z >= center.z) octant |= 4u;
    
    return octant;
}

HitInfo processLeafNode(ESVONode node, Ray ray, float3 cellMin, float3 cellMax, float tEnter, uint nodeIndex) {
    HitInfo hit;
    hit.hit = 1u;
    hit.position = ray.origin + ray.direction * tEnter;
    hit.distance = tEnter;
    hit.nodeIndex = nodeIndex;
    
    // Compute normal based on which face was hit
    float3 center = (cellMin + cellMax) * 0.5;
    float3 hitLocal = hit.position - center;
    float3 absHit = abs(hitLocal);
    
    // Determine which face was hit by finding the largest component
    if (absHit.x >= absHit.y && absHit.x >= absHit.z) {
        hit.normal = float3(sign(hitLocal.x), 0.0, 0.0);
    } else if (absHit.y >= absHit.z) {
        hit.normal = float3(0.0, sign(hitLocal.y), 0.0);
    } else {
        hit.normal = float3(0.0, 0.0, sign(hitLocal.z));
    }
    
    // Basic color based on normal for now
    hit.color = float4(hit.normal * 0.5 + 0.5, 1.0);
    
    return hit;
}

// Optimized octant ordering based on ray direction
void getOctantOrder(float3 rayDir, thread uint octantOrder[8]) {
    if (rayDir.x < 0.0) {
        if (rayDir.y < 0.0) {
            if (rayDir.z < 0.0) {
                // ---
                octantOrder[0] = 7u; octantOrder[1] = 6u; octantOrder[2] = 5u; octantOrder[3] = 4u;
                octantOrder[4] = 3u; octantOrder[5] = 2u; octantOrder[6] = 1u; octantOrder[7] = 0u;
            } else {
                // --+
                octantOrder[0] = 3u; octantOrder[1] = 2u; octantOrder[2] = 1u; octantOrder[3] = 0u;
                octantOrder[4] = 7u; octantOrder[5] = 6u; octantOrder[6] = 5u; octantOrder[7] = 4u;
            }
        } else {
            if (rayDir.z < 0.0) {
                // -+-
                octantOrder[0] = 5u; octantOrder[1] = 4u; octantOrder[2] = 7u; octantOrder[3] = 6u;
                octantOrder[4] = 1u; octantOrder[5] = 0u; octantOrder[6] = 3u; octantOrder[7] = 2u;
            } else {
                // -++
                octantOrder[0] = 1u; octantOrder[1] = 0u; octantOrder[2] = 3u; octantOrder[3] = 2u;
                octantOrder[4] = 5u; octantOrder[5] = 4u; octantOrder[6] = 7u; octantOrder[7] = 6u;
            }
        }
    } else {
        if (rayDir.y < 0.0) {
            if (rayDir.z < 0.0) {
                // +--
                octantOrder[0] = 6u; octantOrder[1] = 7u; octantOrder[2] = 4u; octantOrder[3] = 5u;
                octantOrder[4] = 2u; octantOrder[5] = 3u; octantOrder[6] = 0u; octantOrder[7] = 1u;
            } else {
                // +-+
                octantOrder[0] = 2u; octantOrder[1] = 3u; octantOrder[2] = 0u; octantOrder[3] = 1u;
                octantOrder[4] = 6u; octantOrder[5] = 7u; octantOrder[6] = 4u; octantOrder[7] = 5u;
            }
        } else {
            if (rayDir.z < 0.0) {
                // ++-
                octantOrder[0] = 4u; octantOrder[1] = 5u; octantOrder[2] = 6u; octantOrder[3] = 7u;
                octantOrder[4] = 0u; octantOrder[5] = 1u; octantOrder[6] = 2u; octantOrder[7] = 3u;
            } else {
                // +++
                octantOrder[0] = 0u; octantOrder[1] = 1u; octantOrder[2] = 2u; octantOrder[3] = 3u;
                octantOrder[4] = 4u; octantOrder[5] = 5u; octantOrder[6] = 6u; octantOrder[7] = 7u;
            }
        }
    }
}

kernel void traverse_compute(
    uint3 thread_position_in_grid [[thread_position_in_grid]],
    device ESVONode* nodes [[buffer(0)]],
    device Ray* rays [[buffer(1)]],
    device HitInfo* results [[buffer(2)]],
    device uint* pages [[buffer(3)]],
    constant uint4& counters [[buffer(4)]],  // nodeCount, rayCount, pageCount, padding
    constant float4x4& worldToVoxel [[buffer(5)]],
    constant float3& voxelOrigin [[buffer(6)]],
    constant float& voxelSize [[buffer(7)]],
    constant uint& rootNodeIndex [[buffer(8)]]
#ifdef ENABLE_STATISTICS
    , device atomic_uint* stats [[buffer(9)]]  // raysCast, nodesTraversed, leavesHit, stackOverflows
#endif
) {
    uint rayIndex = thread_position_in_grid.x;
    if (rayIndex >= counters.y) return;  // counters.y = rayCount
    
    Ray ray = rays[rayIndex];
    
    // Initialize result
    HitInfo result;
    result.hit = 0u;
    result.distance = ray.tMax;
    result.position = float3(0.0);
    result.normal = float3(0.0);
    result.color = float4(0.0);
    result.nodeIndex = 0u;
    
    // Stack-based iterative traversal
    StackEntry stack[MAX_STACK_DEPTH];
    int stackPtr = 0;
    
    // Initialize with root node
    float3 rootMin = voxelOrigin;
    float3 rootMax = voxelOrigin + float3(voxelSize);
    
    float tEnter, tExit;
    if (!intersectAABB(ray.origin, ray.direction, rootMin, rootMax, tEnter, tExit)) {
        results[rayIndex] = result;
        return;
    }
    
    tEnter = max(tEnter, ray.tMin);
    tExit = min(tExit, ray.tMax);
    
    if (tEnter >= tExit) {
        results[rayIndex] = result;
        return;
    }
    
    // Validate root node index
    if (rootNodeIndex >= counters.x) {  // counters.x = nodeCount
        results[rayIndex] = result;
        return;
    }
    
    // Push root node onto stack
    stack[stackPtr].nodeIndex = rootNodeIndex;
    stack[stackPtr].tEnter = tEnter;
    stack[stackPtr].tExit = tExit;
    stack[stackPtr].cellMin = rootMin;
    stack[stackPtr].cellMax = rootMax;
    stack[stackPtr].level = 0u;
    stackPtr++;
    
    #ifdef ENABLE_STATISTICS
        uint nodesTraversed = 0u;
    #endif
    
    // Get octant ordering for this ray direction
    uint octantOrder[8];
    getOctantOrder(ray.direction, octantOrder);
    
    while (stackPtr > 0) {
        StackEntry entry = stack[--stackPtr];
        
        // Validate node index
        if (entry.nodeIndex >= counters.x) continue;  // counters.x = nodeCount
        
        ESVONode node = nodes[entry.nodeIndex];
        
        #ifdef ENABLE_STATISTICS
            nodesTraversed++;
        #endif
        
        uint nonLeafMask = getNonLeafMask(node);
        
        // Check if this is a leaf node
        if (nonLeafMask == 0u) {
            // Process leaf
            result = processLeafNode(node, ray, entry.cellMin, entry.cellMax, entry.tEnter, entry.nodeIndex);
            
            #ifdef ENABLE_SHADOWS
                // Early termination for shadow rays
                break;
            #endif
            
            #ifdef ENABLE_STATISTICS
                atomic_fetch_add_explicit(&stats[2], 1u, memory_order_relaxed);
            #endif
            
            break;
        } else {
            // Process interior node
            uint validMask = getValidMask(node);
            
            // Traverse children in back-to-front order
            for (int i = 7; i >= 0; i--) {
                uint octant = octantOrder[i];
                
                // Check if child exists
                if ((validMask & (1u << octant)) == 0u) continue;
                
                // Compute child bounds
                float3 childMin = computeChildMin(entry.cellMin, entry.cellMax, octant);
                float3 childMax = computeChildMax(entry.cellMin, entry.cellMax, octant);
                
                // Test intersection with child
                float childTEnter, childTExit;
                if (!intersectAABB(ray.origin, ray.direction, childMin, childMax, childTEnter, childTExit)) {
                    continue;
                }
                
                childTEnter = max(childTEnter, entry.tEnter);
                childTExit = min(childTExit, entry.tExit);
                
                if (childTEnter >= childTExit) continue;
                
                #ifdef ENABLE_LOD
                    // LOD test - skip distant small nodes
                    float childSize = length(childMax - childMin);
                    float distance = childTEnter;
                    if (distance > lodDistance && childSize < lodBias / distance) {
                        continue;
                    }
                #endif
                
                // Get child node index
                uint childIndex = computeChildIndex(node, octant, pages, counters.z);  // counters.z = pageCount
                if (childIndex == 0u || childIndex >= counters.x) continue;  // counters.x = nodeCount
                
                // Push child onto stack if there's room
                if (stackPtr < MAX_STACK_DEPTH) {
                    stack[stackPtr].nodeIndex = childIndex;
                    stack[stackPtr].tEnter = childTEnter;
                    stack[stackPtr].tExit = childTExit;
                    stack[stackPtr].cellMin = childMin;
                    stack[stackPtr].cellMax = childMax;
                    stack[stackPtr].level = entry.level + 1u;
                    stackPtr++;
                } else {
                    #ifdef ENABLE_STATISTICS
                        atomic_fetch_add_explicit(&stats[3], 1u, memory_order_relaxed);
                    #endif
                }
            }
        }
    }
    
    #ifdef ENABLE_STATISTICS
        atomic_fetch_add_explicit(&stats[0], 1u, memory_order_relaxed);
        atomic_fetch_add_explicit(&stats[1], nodesTraversed, memory_order_relaxed);
    #endif
    
    results[rayIndex] = result;
}