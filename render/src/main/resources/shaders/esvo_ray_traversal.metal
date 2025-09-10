#include <metal_stdlib>
using namespace metal;

// ESVO Ray Traversal - Metal Compute Shader Implementation

struct Ray {
    float3 origin;
    float3 direction;
    float tMin;
    float tMax;
};

struct OctreeNode {
    uint childDescriptor;  // [childptr(14)|far(1)|childmask(8)|leafmask(8)]
    uint contourData;      // [contour_ptr(24)|contour_mask(8)]
};

struct HitResult {
    float3 position;
    float distance;
    uint voxelData;
    uint padding;
};

bool intersectAABB(Ray ray, float3 boxMin, float3 boxMax, 
                  thread float& tEntry, thread float& tExit) {
    float3 invDir = 1.0 / ray.direction;
    float3 t0 = (boxMin - ray.origin) * invDir;
    float3 t1 = (boxMax - ray.origin) * invDir;
    
    float3 tMin = min(t0, t1);
    float3 tMax = max(t0, t1);
    
    tEntry = max(max(tMin.x, tMin.y), max(tMin.z, ray.tMin));
    tExit = min(min(tMax.x, tMax.y), min(tMax.z, ray.tMax));
    
    return tEntry <= tExit && tExit >= 0.0;
}

kernel void traverseOctree(
    device const Ray* rays [[buffer(0)]],
    device const OctreeNode* nodes [[buffer(1)]],
    device HitResult* results [[buffer(2)]],
    constant uint& maxDepth [[buffer(3)]],
    constant float3& sceneMin [[buffer(4)]],
    constant float3& sceneMax [[buffer(5)]],
    uint gid [[thread_position_in_grid]])
{
    Ray ray = rays[gid];
    HitResult result;
    result.position = float3(0.0);
    result.distance = -1.0;
    result.voxelData = 0;
    
    float tEntry, tExit;
    if (!intersectAABB(ray, sceneMin, sceneMax, tEntry, tExit)) {
        results[gid] = result;
        return;
    }
    
    // Traversal implementation...
    // Similar to OpenCL version but using Metal syntax
    
    results[gid] = result;
}