#include <metal_stdlib>
using namespace metal;

// Fragment shader for ESVO ray-casting

// Vertex output structure
struct VertexOut {
    float4 position [[position]];
    float2 texCoord;
};

// ESVO node structure (8 bytes)
struct ESVONode {
    uint validMask;      // 8 bits - which children exist
    uint nonLeafMask;    // 8 bits - which children are non-leaves
    uint childPointer;   // 16 bits - pointer to first child
    uint contourMask;    // 8 bits - contour information
    uint flags;          // 8 bits - additional flags
    uint contourPointer; // 16 bits - pointer to contour data
};

// Metadata structure
struct MetadataBuffer {
    float3 boundingMin;
    float3 boundingMax;
    int nodeCount;
    int maxDepth;
    int lodCount;
    int leafCount;
    int hasContours;
    int isComplete;
    int reserved[4];
};

// Uniforms structure
struct FragmentUniforms {
    float4x4 viewMatrix;
    float4x4 projMatrix;
    float4x4 invViewMatrix;
    float4x4 invProjMatrix;
    float3 cameraPos;
    float time;
};

// Stack for tree traversal
constant int MAX_STACK_DEPTH = 23;

struct StackEntry {
    int nodeIndex;
    float tMin;
    float tMax;
};

// Ray-AABB intersection
bool rayAABBIntersection(float3 origin, float3 invDir, float3 boxMin, float3 boxMax, 
                         thread float& tMin, thread float& tMax) {
    float3 t1 = (boxMin - origin) * invDir;
    float3 t2 = (boxMax - origin) * invDir;
    
    float3 tMinV = min(t1, t2);
    float3 tMaxV = max(t1, t2);
    
    tMin = max(max(tMinV.x, tMinV.y), tMinV.z);
    tMax = min(min(tMaxV.x, tMaxV.y), tMaxV.z);
    
    return tMax >= tMin && tMax >= 0.0;
}

// Get child bounds for octant
void getChildBounds(float3 parentMin, float3 parentMax, int octant,
                   thread float3& childMin, thread float3& childMax) {
    float3 center = (parentMin + parentMax) * 0.5;
    
    childMin = parentMin;
    childMax = center;
    
    if ((octant & 1) != 0) {
        childMin.x = center.x;
        childMax.x = parentMax.x;
    }
    if ((octant & 2) != 0) {
        childMin.y = center.y;
        childMax.y = parentMax.y;
    }
    if ((octant & 4) != 0) {
        childMin.z = center.z;
        childMax.z = parentMax.z;
    }
}

// Main ray-casting function
float4 castRay(float3 origin, float3 direction, device ESVONode* nodes, constant MetadataBuffer& metadata) {
    float3 invDir = 1.0 / direction;
    
    // Check root intersection
    float tMin, tMax;
    if (!rayAABBIntersection(origin, invDir, metadata.boundingMin, 
                            metadata.boundingMax, tMin, tMax)) {
        return float4(0.0); // Ray misses octree
    }
    
    // Initialize traversal stack
    StackEntry stack[MAX_STACK_DEPTH];
    int stackPtr = 0;
    
    // Push root
    stack[0].nodeIndex = 0;
    stack[0].tMin = tMin;
    stack[0].tMax = tMax;
    stackPtr = 1;
    
    // Traverse octree
    float hitT = tMax + 1.0;
    float3 hitNormal = float3(0.0);
    int iterations = 0;
    constant int MAX_ITERATIONS = 1000;
    
    while (stackPtr > 0 && iterations < MAX_ITERATIONS) {
        iterations++;
        
        // Pop from stack
        stackPtr--;
        int nodeIdx = stack[stackPtr].nodeIndex;
        float nodeTMin = stack[stackPtr].tMin;
        float nodeTMax = stack[stackPtr].tMax;
        
        // Skip if we've already found a closer hit
        if (nodeTMin > hitT) {
            continue;
        }
        
        // Load node
        ESVONode node = nodes[nodeIdx];
        
        // Check if leaf
        if (node.nonLeafMask == 0) {
            // Leaf node - register hit
            if (nodeTMin < hitT) {
                hitT = nodeTMin;
                
                // Calculate normal based on hit point
                float3 hitPoint = origin + direction * hitT;
                float3 nodeCenter = (metadata.boundingMin + metadata.boundingMax) * 0.5;
                float3 diff = hitPoint - nodeCenter;
                float3 absDiff = abs(diff);
                
                if (absDiff.x > absDiff.y && absDiff.x > absDiff.z) {
                    hitNormal = float3(sign(diff.x), 0.0, 0.0);
                } else if (absDiff.y > absDiff.z) {
                    hitNormal = float3(0.0, sign(diff.y), 0.0);
                } else {
                    hitNormal = float3(0.0, 0.0, sign(diff.z));
                }
            }
            continue;
        }
        
        // Process children in front-to-back order
        for (int i = 0; i < 8; i++) {
            uint mask = 1u << i;
            
            // Check if child exists
            if ((node.validMask & mask) == 0) {
                continue;
            }
            
            // Calculate child bounds
            float3 childMin, childMax;
            float3 nodeMin = metadata.boundingMin; // Simplified - should track per node
            float3 nodeMax = metadata.boundingMax;
            getChildBounds(nodeMin, nodeMax, i, childMin, childMax);
            
            // Test ray intersection with child
            float childTMin, childTMax;
            if (rayAABBIntersection(origin, invDir, childMin, childMax, 
                                  childTMin, childTMax)) {
                // Calculate child index
                int childIdx = int(node.childPointer) + i;
                
                // Push to stack if closer than current hit
                if (childTMin < hitT && stackPtr < MAX_STACK_DEPTH) {
                    stack[stackPtr].nodeIndex = childIdx;
                    stack[stackPtr].tMin = childTMin;
                    stack[stackPtr].tMax = childTMax;
                    stackPtr++;
                }
            }
        }
    }
    
    // Return hit color
    if (hitT < tMax + 0.001) {
        // Basic shading
        float3 lightDir = normalize(float3(1.0, 1.0, 0.5));
        float NdotL = max(dot(hitNormal, lightDir), 0.0);
        float3 color = float3(0.8, 0.8, 0.9) * (0.3 + 0.7 * NdotL);
        
        // Add some depth shading
        float depth = hitT / (tMax - tMin);
        color *= (1.0 - depth * 0.3);
        
        return float4(color, 1.0);
    }
    
    // Sky color
    float t = direction.y * 0.5 + 0.5;
    float3 skyColor = mix(float3(0.7, 0.8, 1.0), float3(0.2, 0.4, 0.8), t);
    return float4(skyColor, 1.0);
}

fragment float4 voxel_raycast_fragment(
    VertexOut in [[stage_in]],
    device ESVONode* nodes [[buffer(0)]],
    constant MetadataBuffer& metadata [[buffer(1)]],
    constant FragmentUniforms& uniforms [[buffer(2)]]
) {
    // Generate ray from camera
    float4 nearPoint = uniforms.invProjMatrix * float4(in.texCoord * 2.0 - 1.0, -1.0, 1.0);
    nearPoint /= nearPoint.w;
    float4 farPoint = uniforms.invProjMatrix * float4(in.texCoord * 2.0 - 1.0, 1.0, 1.0);
    farPoint /= farPoint.w;
    
    float3 rayOrigin = (uniforms.invViewMatrix * float4(nearPoint.xyz, 1.0)).xyz;
    float3 rayEnd = (uniforms.invViewMatrix * float4(farPoint.xyz, 1.0)).xyz;
    float3 rayDir = normalize(rayEnd - rayOrigin);
    
    // Cast ray and get color
    return castRay(uniforms.cameraPos, rayDir, nodes, metadata);
}