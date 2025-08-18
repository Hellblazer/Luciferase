#include <metal_stdlib>
#include <metal_compute>
using namespace metal;

// Compute kernel configuration
// local_size_x = 8, local_size_y = 8, local_size_z = 1

// Import common structures
struct ESVONode {
    uint validMask;
    uint nonLeafMask;
    uint farPointerFlag;
    uint childPointer;
    uint contourMask;
    uint contourPointer;
};

struct Ray {
    float3 origin;
    float3 direction;
    float tMin;
    float tMax;
    uint pixelX;
    uint pixelY;
};

struct HitInfo {
    float3 position;
    float3 normal;
    float4 color;
    float distance;
    uint nodeIndex;
    uint hit;  // Changed from bool to uint for compatibility
};

// Beam structure for coherent ray groups
struct Beam {
    float3 origin;
    float3 directions[4];  // Corner rays of beam frustum
    float tMin;
    float tMax;
    float3 beamMin;       // AABB of beam
    float3 beamMax;
    float coherence;    // Coherence measure [0,1]
    uint rayStartIndex;
    uint rayCount;
};

struct BeamHit {
    uint hit;  // Changed from bool to uint
    float minDistance;
    float maxDistance;
    float3 avgNormal;
    float4 avgColor;
    uint hitCount;
};

// Configuration
#define MAX_BEAM_STACK_DEPTH 20
#define COHERENCE_THRESHOLD 0.7
#define SPLIT_THRESHOLD 0.3
#define MAX_RAYS_PER_BEAM 64

// Beam stack entry
struct BeamStackEntry {
    uint nodeIndex;
    float tEnter;
    float tExit;
    float3 cellMin;
    float3 cellMax;
    uint level;
};

// Utility functions
float3 safe_inverse(float3 v) {
    return float3(
        abs(v.x) > 1e-6 ? 1.0 / v.x : 1e6 * sign(v.x),
        abs(v.y) > 1e-6 ? 1.0 / v.y : 1e6 * sign(v.y),
        abs(v.z) > 1e-6 ? 1.0 / v.z : 1e6 * sign(v.z)
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

float analyzeBeamCoherence(Beam beam) {
    // Analyze directional coherence of corner rays
    float3 avgDir = normalize(beam.directions[0] + beam.directions[1] + beam.directions[2] + beam.directions[3]);
    
    float maxDot = 0.0;
    for (int i = 0; i < 4; i++) {
        float dotProduct = dot(normalize(beam.directions[i]), avgDir);
        maxDot = max(maxDot, 1.0 - dotProduct);
    }
    
    return 1.0 - maxDot; // Higher values = more coherent
}

bool beamIntersectsAABB(Beam beam, float3 boxMin, float3 boxMax) {
    // Test if beam frustum intersects AABB
    // For simplicity, test beam AABB against node AABB
    return !(beam.beamMax.x < boxMin.x || beam.beamMin.x > boxMax.x ||
             beam.beamMax.y < boxMin.y || beam.beamMin.y > boxMax.y ||
             beam.beamMax.z < boxMin.z || beam.beamMin.z > boxMax.z);
}

uint computeChildIndex(ESVONode node, uint octant, device uint* pages) {
    uint mask = node.validMask & ((1u << octant) - 1u);
    uint offset = popcount(mask);
    
    if ((node.farPointerFlag & 1u) != 0u) {
        return pages[node.childPointer + offset];
    } else {
        return node.childPointer + offset;
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

BeamHit processBeamLeaf(Beam beam, ESVONode node, float3 cellMin, float3 cellMax, device Ray* rays, device HitInfo* rayResults) {
    BeamHit result;
    result.hit = 1u;
    result.hitCount = 0;
    result.minDistance = 1e6;
    result.maxDistance = 0.0;
    result.avgNormal = float3(0.0);
    result.avgColor = float4(0.0);
    
    // Process each ray in the beam individually for leaf intersection
    for (uint i = 0; i < beam.rayCount; i++) {
        uint rayIndex = beam.rayStartIndex + i;
        // Bounds check would be done externally
        
        Ray ray = rays[rayIndex];
        
        float tEnter, tExit;
        if (intersectAABB(ray.origin, ray.direction, cellMin, cellMax, tEnter, tExit)) {
            tEnter = max(tEnter, ray.tMin);
            tExit = min(tExit, ray.tMax);
            
            if (tEnter < tExit) {
                result.hitCount++;
                result.minDistance = min(result.minDistance, tEnter);
                result.maxDistance = max(result.maxDistance, tEnter);
                
                // Compute normal
                float3 hitPos = ray.origin + ray.direction * tEnter;
                float3 center = (cellMin + cellMax) * 0.5;
                float3 hitLocal = hitPos - center;
                float3 absHit = abs(hitLocal);
                
                float3 normal;
                if (absHit.x >= absHit.y && absHit.x >= absHit.z) {
                    normal = float3(sign(hitLocal.x), 0.0, 0.0);
                } else if (absHit.y >= absHit.z) {
                    normal = float3(0.0, sign(hitLocal.y), 0.0);
                } else {
                    normal = float3(0.0, 0.0, sign(hitLocal.z));
                }
                
                result.avgNormal += normal;
                result.avgColor += float4(normal * 0.5 + 0.5, 1.0);
                
                // Store individual ray result
                HitInfo rayHit;
                rayHit.hit = 1u;
                rayHit.position = hitPos;
                rayHit.normal = normal;
                rayHit.color = float4(normal * 0.5 + 0.5, 1.0);
                rayHit.distance = tEnter;
                rayHit.nodeIndex = 0; // Will be set by caller
                
                rayResults[rayIndex] = rayHit;
            }
        }
    }
    
    if (result.hitCount > 0) {
        result.avgNormal = normalize(result.avgNormal);
        result.avgColor /= float(result.hitCount);
    } else {
        result.hit = 0u;
    }
    
    return result;
}

void splitBeam(Beam beam, thread Beam subBeams[4]) {
    // Split beam into 4 sub-beams (2x2 subdivision)
    uint raysPerSubBeam = beam.rayCount / 4;
    uint remainder = beam.rayCount % 4;
    
    for (int i = 0; i < 4; i++) {
        subBeams[i].origin = beam.origin;
        subBeams[i].tMin = beam.tMin;
        subBeams[i].tMax = beam.tMax;
        
        // Distribute rays
        subBeams[i].rayStartIndex = beam.rayStartIndex + i * raysPerSubBeam;
        subBeams[i].rayCount = raysPerSubBeam + (i < remainder ? 1 : 0);
        
        // Compute new beam bounds - simplified for now
        subBeams[i].beamMin = beam.beamMin;
        subBeams[i].beamMax = beam.beamMax;
        
        // Copy corner directions - should be refined based on subdivision
        for (int j = 0; j < 4; j++) {
            subBeams[i].directions[j] = beam.directions[j];
        }
        
        subBeams[i].coherence = analyzeBeamCoherence(subBeams[i]);
    }
}

BeamHit traverseBeam(Beam beam, device ESVONode* nodes, device Ray* rays, device HitInfo* rayResults, 
                     device uint* pages, constant float3& voxelOrigin, constant float& voxelSize, 
                     constant uint& rootNodeIndex, constant float& coherenceThreshold, 
                     constant float& splitThreshold);

BeamHit traverseBeam(Beam beam, device ESVONode* nodes, device Ray* rays, device HitInfo* rayResults, 
                     device uint* pages, constant float3& voxelOrigin, constant float& voxelSize, 
                     constant uint& rootNodeIndex, constant float& coherenceThreshold, 
                     constant float& splitThreshold) {
    BeamHit result;
    result.hit = 0u;
    
    // Check beam coherence
    float coherence = analyzeBeamCoherence(beam);
    
    if (coherence < splitThreshold && beam.rayCount > 4) {
        // Split beam and process sub-beams
        Beam subBeams[4];
        splitBeam(beam, subBeams);
        
        result.hitCount = 0;
        result.minDistance = 1e6;
        result.maxDistance = 0.0;
        result.avgNormal = float3(0.0);
        result.avgColor = float4(0.0);
        
        for (int i = 0; i < 4; i++) {
            BeamHit subResult = traverseBeam(subBeams[i], nodes, rays, rayResults, pages, 
                                           voxelOrigin, voxelSize, rootNodeIndex, 
                                           coherenceThreshold, splitThreshold);
            if (subResult.hit != 0u) {
                result.hit = 1u;
                result.hitCount += subResult.hitCount;
                result.minDistance = min(result.minDistance, subResult.minDistance);
                result.maxDistance = max(result.maxDistance, subResult.maxDistance);
                result.avgNormal += subResult.avgNormal * float(subResult.hitCount);
                result.avgColor += subResult.avgColor * float(subResult.hitCount);
            }
        }
        
        if (result.hitCount > 0) {
            result.avgNormal = normalize(result.avgNormal);
            result.avgColor /= float(result.hitCount);
        }
        
        return result;
    }
    
    // Traverse as coherent beam
    BeamStackEntry stack[MAX_BEAM_STACK_DEPTH];
    int stackPtr = 0;
    
    // Initialize with root
    float3 rootMin = voxelOrigin;
    float3 rootMax = voxelOrigin + float3(voxelSize);
    
    if (!beamIntersectsAABB(beam, rootMin, rootMax)) {
        return result;
    }
    
    stack[stackPtr].nodeIndex = rootNodeIndex;
    stack[stackPtr].tEnter = beam.tMin;
    stack[stackPtr].tExit = beam.tMax;
    stack[stackPtr].cellMin = rootMin;
    stack[stackPtr].cellMax = rootMax;
    stack[stackPtr].level = 0;
    stackPtr++;
    
    while (stackPtr > 0) {
        BeamStackEntry entry = stack[--stackPtr];
        ESVONode node = nodes[entry.nodeIndex];
        
        if (node.nonLeafMask == 0u) {
            // Process leaf
            result = processBeamLeaf(beam, node, entry.cellMin, entry.cellMax, rays, rayResults);
            break;
        } else {
            // Process interior node - push intersecting children
            for (uint octant = 0; octant < 8; octant++) {
                if ((node.validMask & (1u << octant)) == 0u) continue;
                
                float3 childMin = computeChildMin(entry.cellMin, entry.cellMax, octant);
                float3 childMax = computeChildMax(entry.cellMin, entry.cellMax, octant);
                
                if (!beamIntersectsAABB(beam, childMin, childMax)) continue;
                
                uint childIndex = computeChildIndex(node, octant, pages);
                
                if (stackPtr < MAX_BEAM_STACK_DEPTH) {
                    stack[stackPtr].nodeIndex = childIndex;
                    stack[stackPtr].tEnter = entry.tEnter;
                    stack[stackPtr].tExit = entry.tExit;
                    stack[stackPtr].cellMin = childMin;
                    stack[stackPtr].cellMax = childMax;
                    stack[stackPtr].level = entry.level + 1;
                    stackPtr++;
                }
            }
        }
    }
    
    return result;
}

kernel void beam_compute(
    uint3 thread_position_in_grid [[thread_position_in_grid]],
    uint3 thread_position_in_threadgroup [[thread_position_in_threadgroup]],
    uint thread_index_in_threadgroup [[thread_index_in_threadgroup]],
    device ESVONode* nodes [[buffer(0)]],
    device Beam* beams [[buffer(1)]],
    device BeamHit* beamResults [[buffer(2)]],
    device Ray* rays [[buffer(3)]],
    device HitInfo* rayResults [[buffer(4)]],
    device uint* pages [[buffer(5)]],
    constant float4x4& worldToVoxel [[buffer(6)]],
    constant float3& voxelOrigin [[buffer(7)]],
    constant float& voxelSize [[buffer(8)]],
    constant uint& rootNodeIndex [[buffer(9)]],
    constant float& coherenceThreshold [[buffer(10)]],
    constant float& splitThreshold [[buffer(11)]],
    threadgroup uint* sharedNodeStack [[threadgroup(0)]],   // 256 elements
    threadgroup float* sharedCoherence [[threadgroup(1)]],  // 64 elements  
    threadgroup uint* sharedShouldSplit [[threadgroup(2)]]  // 64 elements (using uint instead of bool)
) {
    uint beamIndex = thread_position_in_grid.x;
    // Bounds check would be done by checking against buffer size
    
    Beam beam = beams[beamIndex];
    
    // Initialize shared memory
    uint localIndex = thread_index_in_threadgroup;
    if (localIndex == 0) {
        sharedNodeStack[0] = 0; // Use first element as stack pointer
    }
    
    if (localIndex < 64) {
        sharedCoherence[localIndex] = 0.0;
        sharedShouldSplit[localIndex] = 0u;
    }
    
    threadgroup_barrier(mem_flags::mem_threadgroup);
    
    // Analyze beam coherence
    float coherence = analyzeBeamCoherence(beam);
    if (localIndex < 64) {
        sharedCoherence[localIndex] = coherence;
        sharedShouldSplit[localIndex] = coherence < coherenceThreshold ? 1u : 0u;
    }
    
    threadgroup_barrier(mem_flags::mem_threadgroup);
    
    // Process beam
    BeamHit result = traverseBeam(beam, nodes, rays, rayResults, pages, voxelOrigin, 
                                 voxelSize, rootNodeIndex, coherenceThreshold, splitThreshold);
    
    beamResults[beamIndex] = result;
}