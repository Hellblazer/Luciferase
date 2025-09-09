// ESVO Ray Traversal OpenCL Kernel
// Port of GLSL compute shader for CI-compatible testing

// Structure definitions matching CUDA reference (UNIFIED)
typedef struct {
    int childDescriptor;  // [childptr(14)|far(1)|childmask(8)|leafmask(8)]
    int contourData;      // [contour_ptr(24)|contour_mask(8)]
} OctreeNode;

typedef struct {
    float3 origin;
    float3 direction;
    float tMin;
    float tMax;
} Ray;

typedef struct {
    int hit;
    float t;
    float3 normal;
    int voxelValue;
    int nodeIndex;
    int iterations;
} IntersectionResult;

typedef struct {
    int nodeIndex;
    float tEntry;
    float tExit;
    int level;
} StackEntry;

typedef struct {
    int maxDepth;
    int maxIterations;
    float epsilon;
    float rootSize;
    float3 rootOrigin;
    int beamOptimization;
    int contourEnabled;
    int shadowRays;
    int padding[5];
} TraversalParams;

// Utility functions
float3 transformPoint(float3 p, float3 origin, float size) {
    return (p - origin) / size;
}

int getChildIndex(float3 pos) {
    int idx = 0;
    if (pos.x >= 0.5f) idx |= 1;
    if (pos.y >= 0.5f) idx |= 2;
    if (pos.z >= 0.5f) idx |= 4;
    return idx;
}

float3 getChildOffset(int childIndex) {
    float3 offset;
    offset.x = (childIndex & 1) ? 0.5f : 0.0f;
    offset.y = (childIndex & 2) ? 0.5f : 0.0f;
    offset.z = (childIndex & 4) ? 0.5f : 0.0f;
    return offset;
}

bool rayBoxIntersection(float3 rayOrigin, float3 rayDir,
                        float3 boxMin, float3 boxMax,
                        float* tEntry, float* tExit) {
    float3 invDir = 1.0f / rayDir;
    float3 t0 = (boxMin - rayOrigin) * invDir;
    float3 t1 = (boxMax - rayOrigin) * invDir;
    
    float3 tMin = min(t0, t1);
    float3 tMax = max(t0, t1);
    
    *tEntry = max(max(tMin.x, tMin.y), tMin.z);
    *tExit = min(min(tMax.x, tMax.y), tMax.z);
    
    return *tEntry <= *tExit && *tExit >= 0.0f;
}

// Main ray traversal kernel
__kernel void esvo_raycast(
    __global const OctreeNode* nodes,
    __global const Ray* rays,
    __global IntersectionResult* results,
    __constant TraversalParams* params,
    const int numRays)
{
    int rayId = get_global_id(0);
    if (rayId >= numRays) return;
    
    // Initialize result
    IntersectionResult result;
    result.hit = 0;
    result.t = FLT_MAX;
    result.normal = (float3)(0.0f, 0.0f, 0.0f);
    result.voxelValue = 0;
    result.nodeIndex = -1;
    result.iterations = 0;
    
    // Load ray
    Ray ray = rays[rayId];
    
    // Initialize traversal stack
    StackEntry stack[32];  // Max depth
    int stackPtr = 0;
    
    // Check root intersection
    float3 boxMin = params->rootOrigin;
    float3 boxMax = params->rootOrigin + params->rootSize;
    float tEntry, tExit;
    
    if (!rayBoxIntersection(ray.origin, ray.direction, boxMin, boxMax, &tEntry, &tExit)) {
        results[rayId] = result;
        return;
    }
    
    // Push root onto stack
    stack[0].nodeIndex = 0;
    stack[0].tEntry = max(tEntry, ray.tMin);
    stack[0].tExit = min(tExit, ray.tMax);
    stack[0].level = 0;
    stackPtr = 1;
    
    // Main traversal loop
    while (stackPtr > 0 && result.iterations < params->maxIterations) {
        result.iterations++;
        
        // Pop from stack
        stackPtr--;
        StackEntry current = stack[stackPtr];
        
        // Skip if we've already found a closer intersection
        if (current.tEntry > result.t) continue;
        
        // Load node
        OctreeNode node = nodes[current.nodeIndex];
        
        // Check if this is a leaf
        bool isLeaf = (node.childDescriptor == 0) || (current.level >= params->maxDepth);
        
        if (isLeaf) {
            // Check voxel intersection
            if (node.attributes > 0) {  // Non-empty voxel
                result.hit = 1;
                result.t = current.tEntry;
                result.nodeIndex = current.nodeIndex;
                result.voxelValue = node.attributes;
                
                // Calculate normal (simplified - actual implementation would be more complex)
                float3 hitPoint = ray.origin + ray.direction * current.tEntry;
                float3 nodeCenter = params->rootOrigin + params->rootSize * 0.5f;
                float nodeSize = params->rootSize / (float)(1 << current.level);
                
                // Find which face we hit
                float3 localPos = (hitPoint - nodeCenter) / nodeSize;
                float3 absPos = fabs(localPos);
                
                if (absPos.x > absPos.y && absPos.x > absPos.z) {
                    result.normal = (float3)(sign(localPos.x), 0.0f, 0.0f);
                } else if (absPos.y > absPos.z) {
                    result.normal = (float3)(0.0f, sign(localPos.y), 0.0f);
                } else {
                    result.normal = (float3)(0.0f, 0.0f, sign(localPos.z));
                }
                
                // Early termination for first hit
                if (!params->shadowRays) {
                    break;
                }
            }
        } else {
            // Process children
            float childSize = params->rootSize / (float)(1 << (current.level + 1));
            
            // Calculate ray entry point in node space
            float3 entryPoint = ray.origin + ray.direction * current.tEntry;
            float3 nodeOrigin = params->rootOrigin;  // Should calculate actual node origin
            float3 localEntry = transformPoint(entryPoint, nodeOrigin, childSize * 2.0f);
            
            // Traverse children in ray order
            for (int i = 0; i < 8; i++) {
                int childIdx = i;  // Should be sorted by ray direction
                
                // Check if child exists
                if (!(node.childDescriptor & (1 << childIdx))) continue;
                
                // Calculate child bounds
                float3 childOffset = getChildOffset(childIdx);
                float3 childMin = nodeOrigin + childOffset * childSize;
                float3 childMax = childMin + childSize;
                
                float childTEntry, childTExit;
                if (rayBoxIntersection(ray.origin, ray.direction, 
                                     childMin, childMax, 
                                     &childTEntry, &childTExit)) {
                    
                    // Only process if potentially closer than current result
                    if (childTEntry < result.t && stackPtr < 31) {
                        // CUDA reference sparse indexing: parent_ptr + popcount(child_masks & ((1 << childIdx) - 1))
                        int childMask = (current.node.childDescriptor >> 8) & 0xFF;
                        int childPtr = (current.node.childDescriptor >> 17) & 0x3FFF;
                        int popCount = popcount(childMask & ((1 << childIdx) - 1));
                        int childNodeIndex = childPtr + popCount;
                        
                        stack[stackPtr].nodeIndex = childNodeIndex;
                        stack[stackPtr].tEntry = max(childTEntry, current.tEntry);
                        stack[stackPtr].tExit = min(childTExit, current.tExit);
                        stack[stackPtr].level = current.level + 1;
                        stackPtr++;
                    }
                }
            }
        }
    }
    
    // Store result
    results[rayId] = result;
}

// Beam optimization kernel (simplified version)
__kernel void esvo_beam_raycast(
    __global const OctreeNode* nodes,
    __global const float* contourData,
    __global const Ray* rays,
    __global IntersectionResult* results,
    __constant TraversalParams* params,
    const int numRays)
{
    int rayId = get_global_id(0);
    if (rayId >= numRays) return;
    
    // For CI testing, fall back to standard traversal
    // Full beam optimization would require additional data structures
    esvo_raycast(nodes, rays, results, params, numRays);
}

// Shadow ray kernel (optimized for binary visibility)
__kernel void esvo_shadow_ray(
    __global const OctreeNode* nodes,
    __global const Ray* rays,
    __global int* visibility,  // 1 = visible, 0 = occluded
    __constant TraversalParams* params,
    const int numRays)
{
    int rayId = get_global_id(0);
    if (rayId >= numRays) return;
    
    Ray ray = rays[rayId];
    
    // Simple traversal looking for any intersection
    float3 boxMin = params->rootOrigin;
    float3 boxMax = params->rootOrigin + params->rootSize;
    float tEntry, tExit;
    
    if (!rayBoxIntersection(ray.origin, ray.direction, boxMin, boxMax, &tEntry, &tExit)) {
        visibility[rayId] = 1;  // No intersection with scene
        return;
    }
    
    // Simplified shadow test - check root node only for CI testing
    OctreeNode root = nodes[0];
    visibility[rayId] = (root.attributes == 0) ? 1 : 0;
}