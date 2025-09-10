#include <metal_stdlib>
using namespace metal;

struct Ray {
    float3 origin;
    float3 direction;
    float tMin;
    float tMax;
};

struct Box {
    float3 min;
    float3 max;
};

struct IntersectionResult {
    uint hit;
    float t;
    float3 normal;
};

bool ray_box_intersection(Ray ray, Box box, thread float& tEntry, thread float& tExit) {
    float3 invDir = 1.0 / ray.direction;
    float3 t0 = (box.min - ray.origin) * invDir;
    float3 t1 = (box.max - ray.origin) * invDir;
    
    float3 tMin = min(t0, t1);
    float3 tMax = max(t0, t1);
    
    tEntry = max(max(tMin.x, tMin.y), tMin.z);
    tExit = min(min(tMax.x, tMax.y), tMax.z);
    
    return tEntry <= tExit && tExit >= 0.0;
}

kernel void ray_traversal(
    device Ray* rays [[buffer(0)]],
    device Box* boxes [[buffer(1)]],
    device IntersectionResult* results [[buffer(2)]],
    uint id [[thread_position_in_grid]])
{
    Ray ray = rays[id];
    Box box = boxes[0]; // Test against single box
    
    float tEntry, tExit;
    if (ray_box_intersection(ray, box, tEntry, tExit)) {
        results[id].hit = 1;
        results[id].t = max(tEntry, ray.tMin);
        results[id].normal = float3(1.0, 0.0, 0.0);
    } else {
        results[id].hit = 0;
        results[id].t = 1e30;
        results[id].normal = float3(0.0);
    }
}