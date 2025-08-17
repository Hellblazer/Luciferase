#version 450 core

// Fragment shader for ESVO ray-casting

in vec2 texCoord;
out vec4 fragColor;

// Uniforms
uniform mat4 viewMatrix;
uniform mat4 projMatrix;
uniform mat4 invViewMatrix;
uniform mat4 invProjMatrix;
uniform vec3 cameraPos;
uniform float time;

// ESVO node structure (8 bytes)
struct ESVONode {
    uint validMask;      // 8 bits - which children exist
    uint nonLeafMask;    // 8 bits - which children are non-leaves
    uint childPointer;   // 16 bits - pointer to first child
    uint contourMask;    // 8 bits - contour information
    uint flags;          // 8 bits - additional flags
    uint contourPointer; // 16 bits - pointer to contour data
};

// Storage buffers
layout(std430, binding = 0) readonly buffer NodeBuffer {
    ESVONode nodes[];
};

layout(std430, binding = 2) readonly buffer MetadataBuffer {
    vec3 boundingMin;
    vec3 boundingMax;
    int nodeCount;
    int maxDepth;
    int lodCount;
    int leafCount;
    int hasContours;
    int isComplete;
    int reserved[4];
} metadata;

// Stack for tree traversal
const int MAX_STACK_DEPTH = 23;

struct StackEntry {
    int nodeIndex;
    float tMin;
    float tMax;
};

// Ray-AABB intersection
bool rayAABBIntersection(vec3 origin, vec3 invDir, vec3 boxMin, vec3 boxMax, 
                         out float tMin, out float tMax) {
    vec3 t1 = (boxMin - origin) * invDir;
    vec3 t2 = (boxMax - origin) * invDir;
    
    vec3 tMinV = min(t1, t2);
    vec3 tMaxV = max(t1, t2);
    
    tMin = max(max(tMinV.x, tMinV.y), tMinV.z);
    tMax = min(min(tMaxV.x, tMaxV.y), tMaxV.z);
    
    return tMax >= tMin && tMax >= 0.0;
}

// Get child bounds for octant
void getChildBounds(vec3 parentMin, vec3 parentMax, int octant,
                   out vec3 childMin, out vec3 childMax) {
    vec3 center = (parentMin + parentMax) * 0.5;
    
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
vec4 castRay(vec3 origin, vec3 direction) {
    vec3 invDir = 1.0 / direction;
    
    // Check root intersection
    float tMin, tMax;
    if (!rayAABBIntersection(origin, invDir, metadata.boundingMin, 
                            metadata.boundingMax, tMin, tMax)) {
        return vec4(0.0); // Ray misses octree
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
    vec3 hitNormal = vec3(0.0);
    int iterations = 0;
    const int MAX_ITERATIONS = 1000;
    
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
                vec3 hitPoint = origin + direction * hitT;
                vec3 nodeCenter = (metadata.boundingMin + metadata.boundingMax) * 0.5;
                vec3 diff = hitPoint - nodeCenter;
                vec3 absDiff = abs(diff);
                
                if (absDiff.x > absDiff.y && absDiff.x > absDiff.z) {
                    hitNormal = vec3(sign(diff.x), 0.0, 0.0);
                } else if (absDiff.y > absDiff.z) {
                    hitNormal = vec3(0.0, sign(diff.y), 0.0);
                } else {
                    hitNormal = vec3(0.0, 0.0, sign(diff.z));
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
            vec3 childMin, childMax;
            vec3 nodeMin = metadata.boundingMin; // Simplified - should track per node
            vec3 nodeMax = metadata.boundingMax;
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
        vec3 lightDir = normalize(vec3(1.0, 1.0, 0.5));
        float NdotL = max(dot(hitNormal, lightDir), 0.0);
        vec3 color = vec3(0.8, 0.8, 0.9) * (0.3 + 0.7 * NdotL);
        
        // Add some depth shading
        float depth = hitT / (tMax - tMin);
        color *= (1.0 - depth * 0.3);
        
        return vec4(color, 1.0);
    }
    
    // Sky color
    float t = direction.y * 0.5 + 0.5;
    vec3 skyColor = mix(vec3(0.7, 0.8, 1.0), vec3(0.2, 0.4, 0.8), t);
    return vec4(skyColor, 1.0);
}

void main() {
    // Generate ray from camera
    vec4 nearPoint = invProjMatrix * vec4(texCoord * 2.0 - 1.0, -1.0, 1.0);
    nearPoint /= nearPoint.w;
    vec4 farPoint = invProjMatrix * vec4(texCoord * 2.0 - 1.0, 1.0, 1.0);
    farPoint /= farPoint.w;
    
    vec3 rayOrigin = (invViewMatrix * vec4(nearPoint.xyz, 1.0)).xyz;
    vec3 rayEnd = (invViewMatrix * vec4(farPoint.xyz, 1.0)).xyz;
    vec3 rayDir = normalize(rayEnd - rayOrigin);
    
    // Cast ray and get color
    fragColor = castRay(cameraPos, rayDir);
}