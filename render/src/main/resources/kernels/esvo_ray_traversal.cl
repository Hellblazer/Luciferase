// ESVO Ray Traversal Kernel - OpenCL Implementation

typedef struct {
    float3 origin;
    float3 direction;
    float tMin;
    float tMax;
} Ray;

typedef struct {
    uint childDescriptor;  // [childptr(14)|far(1)|childmask(8)|leafmask(8)]
    uint contourData;      // [contour_ptr(24)|contour_mask(8)]
} OctreeNode;

typedef struct {
    float3 min;
    float3 max;
} AABB;

typedef struct {
    uint nodeIdx;
    float tEntry;
    float tExit;
    uint scale;
    float3 pos;
} StackEntry;

// Ray-AABB intersection
bool intersectAABB(Ray ray, AABB box, float* tEntry, float* tExit) {
    float3 invDir = 1.0f / ray.direction;
    float3 t0 = (box.min - ray.origin) * invDir;
    float3 t1 = (box.max - ray.origin) * invDir;
    
    float3 tMin = min(t0, t1);
    float3 tMax = max(t0, t1);
    
    *tEntry = max(max(tMin.x, tMin.y), max(tMin.z, ray.tMin));
    *tExit = min(min(tMax.x, tMax.y), min(tMax.z, ray.tMax));
    
    return *tEntry <= *tExit && *tExit >= 0.0f;
}

// Get child index from position within parent
uint getChildIndex(float3 pos, float3 center) {
    uint idx = 0;
    if (pos.x >= center.x) idx |= 1;
    if (pos.y >= center.y) idx |= 2;
    if (pos.z >= center.z) idx |= 4;
    return idx;
}

// Main ray traversal kernel
__kernel void traverseOctree(
    __global const Ray* rays,
    __global const OctreeNode* octree,
    __global float4* hitResults,  // xyz = hit point, w = distance
    __global uint* hitVoxels,      // Voxel data at hit point
    const uint maxDepth,
    const float3 sceneMin,
    const float3 sceneMax)
{
    int gid = get_global_id(0);
    Ray ray = rays[gid];
    
    // Initialize result
    hitResults[gid] = (float4)(0.0f, 0.0f, 0.0f, -1.0f);
    hitVoxels[gid] = 0;
    
    // Check scene bounds
    float tEntry, tExit;
    AABB sceneBounds = { sceneMin, sceneMax };
    if (!intersectAABB(ray, sceneBounds, &tEntry, &tExit)) {
        return;
    }
    
    // Stack for traversal
    __local StackEntry stack[32];
    int stackPtr = 0;
    
    // Initialize root
    stack[0].nodeIdx = 0;
    stack[0].tEntry = tEntry;
    stack[0].tExit = tExit;
    stack[0].scale = 0;
    stack[0].pos = sceneMin;
    stackPtr = 1;
    
    float closestHit = ray.tMax;
    uint hitVoxel = 0;
    float3 hitPoint = (float3)(0.0f);
    
    // Traverse octree
    while (stackPtr > 0) {
        StackEntry current = stack[--stackPtr];
        
        // Skip if beyond closest hit
        if (current.tEntry > closestHit) {
            continue;
        }
        
        OctreeNode node = octree[current.nodeIdx];
        
        // Extract child mask from packed descriptor
        uint childMask = (node.childDescriptor >> 8) & 0xFF;
        
        // Leaf node - check for voxel
        if (childMask == 0 || current.scale >= maxDepth) {
            // Extract contour data for voxel information
            uint contourPtr = (node.contourData >> 8) & 0xFFFFFF;
            if (contourPtr != 0 && current.tEntry < closestHit) {
                closestHit = current.tEntry;
                hitVoxel = contourPtr;  // Use contour pointer as voxel data
                hitPoint = ray.origin + ray.direction * closestHit;
            }
            continue;
        }
        
        // Internal node - traverse children
        float nodeSize = (sceneMax.x - sceneMin.x) / (1 << (current.scale + 1));
        float3 center = current.pos + nodeSize;
        
        // Determine traversal order based on ray direction
        uint firstChild = getChildIndex(
            ray.origin + ray.direction * current.tEntry, 
            center);
        
        // Extract child pointer from packed descriptor
        uint childPtr = (node.childDescriptor >> 17) & 0x3FFF;
        
        // Add children to stack in reverse order for correct traversal
        for (int i = 7; i >= 0; i--) {
            if (!(childMask & (1 << i))) {
                continue;
            }
            
            // Calculate child bounds
            float3 childMin = current.pos;
            float3 childMax = current.pos + nodeSize * 2.0f;
            
            if (i & 1) childMin.x += nodeSize;
            else childMax.x -= nodeSize;
            if (i & 2) childMin.y += nodeSize;
            else childMax.y -= nodeSize;
            if (i & 4) childMin.z += nodeSize;
            else childMax.z -= nodeSize;
            
            AABB childBox = { childMin, childMax };
            float childTEntry, childTExit;
            
            if (intersectAABB(ray, childBox, &childTEntry, &childTExit)) {
                if (childTEntry < closestHit && stackPtr < 31) {
                    // CUDA reference sparse indexing: parent_ptr + popcount(child_masks & ((1 << i) - 1))
                    uint childIdx = childPtr + popcount(childMask & ((1 << i) - 1));
                    stack[stackPtr].nodeIdx = childIdx;
                    stack[stackPtr].tEntry = childTEntry;
                    stack[stackPtr].tExit = childTExit;
                    stack[stackPtr].scale = current.scale + 1;
                    stack[stackPtr].pos = childMin;
                    stackPtr++;
                }
            }
        }
    }
    
    // Write results
    if (hitVoxel != 0) {
        hitResults[gid] = (float4)(hitPoint.x, hitPoint.y, hitPoint.z, closestHit);
        hitVoxels[gid] = hitVoxel;
    }
}

// Optimized beam traversal for coherent rays
__kernel void traverseBeam(
    __global const Ray* rays,
    __global const OctreeNode* octree,
    __global float4* hitResults,
    const uint raysPerBeam,
    const uint maxDepth,
    const float3 sceneMin,
    const float3 sceneMax)
{
    int beamId = get_global_id(0);
    int firstRay = beamId * raysPerBeam;
    
    // Compute beam frustum from ray bundle
    float3 frustumMin = (float3)(INFINITY);
    float3 frustumMax = (float3)(-INFINITY);
    
    for (uint i = 0; i < raysPerBeam; i++) {
        Ray ray = rays[firstRay + i];
        float3 nearPoint = ray.origin + ray.direction * ray.tMin;
        float3 farPoint = ray.origin + ray.direction * ray.tMax;
        
        frustumMin = min(frustumMin, min(nearPoint, farPoint));
        frustumMax = max(frustumMax, max(nearPoint, farPoint));
    }
    
    // Traverse using beam frustum for early rejection
    // ... beam-specific traversal logic ...
}