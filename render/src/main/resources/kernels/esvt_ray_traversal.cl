// ESVT Ray Traversal Kernel - OpenCL Implementation
// Tetrahedral adaptation of ESVO for macOS GPU support
// Based on raycast_esvt.comp (GLSL version)

// ============================================================================
// CONSTANTS
// ============================================================================

#define CAST_STACK_DEPTH 22
#define MAX_RAYCAST_ITERATIONS 10000
#define EPSILON 1e-7f
#define TET_TYPES 6

// ============================================================================
// DATA STRUCTURES
// ============================================================================

typedef struct {
    float3 origin;
    float3 direction;
    float tmin;
    float tmax;
} Ray;

// ESVT Node Structure - 8 bytes (matches ESVTNodeUnified.java)
typedef struct {
    uint childDescriptor;   // [valid(1)|childptr(14)|far(1)|childmask(8)|leafmask(8)]
    uint contourDescriptor; // [contour_ptr(20)|normals(4)|contour(4)|type(3)|pad(1)]
} ESVTNode;

typedef struct {
    uint nodeIdx;
    float tMax;
    int parentType;
    int entryFace;
} StackEntry;

typedef struct {
    bool hit;
    float t;
    float u;
    float v;
} TriangleHit;

typedef struct {
    bool hit;
    float tEntry;
    float tExit;
    int entryFace;
    int exitFace;
} TetrahedronHit;

typedef struct {
    float t;
    float3 normal;
    bool valid;
} ContourRefinement;

// ============================================================================
// LOOKUP TABLES
// ============================================================================

// SIMPLEX_STANDARD[6][4] - 6 types x 4 vertices
// Stored as flat array: type * 4 + vertex
constant float3 SIMPLEX_STANDARD[24] = {
    // Type 0
    (float3)(0,0,0), (float3)(1,0,0), (float3)(1,0,1), (float3)(1,1,1),
    // Type 1
    (float3)(0,0,0), (float3)(1,1,1), (float3)(1,1,0), (float3)(1,0,0),
    // Type 2
    (float3)(0,0,0), (float3)(0,1,0), (float3)(1,1,0), (float3)(1,1,1),
    // Type 3
    (float3)(0,0,0), (float3)(1,1,1), (float3)(0,1,1), (float3)(0,1,0),
    // Type 4
    (float3)(0,0,0), (float3)(0,0,1), (float3)(0,1,1), (float3)(1,1,1),
    // Type 5
    (float3)(0,0,0), (float3)(1,1,1), (float3)(1,0,1), (float3)(0,0,1)
};

// PARENT_TYPE_TO_CHILD_TYPE[6][8]
constant int PARENT_TYPE_TO_CHILD_TYPE[48] = {
    0, 0, 0, 0, 4, 5, 2, 1,  // Parent type 0
    1, 1, 1, 1, 3, 2, 5, 0,  // Parent type 1
    2, 2, 2, 2, 0, 1, 4, 3,  // Parent type 2
    3, 3, 3, 3, 5, 4, 1, 2,  // Parent type 3
    4, 4, 4, 4, 2, 3, 0, 5,  // Parent type 4
    5, 5, 5, 5, 1, 0, 3, 4   // Parent type 5
};

// CHILD_ORDER[6][4][4] - flattened to [96]
// [tetType * 16 + entryFace * 4 + position]
constant int CHILD_ORDER[96] = {
    // Type 0
    7, 6, 5, 4,  6, 7, 2, 3,  5, 1, 7, 3,  4, 5, 1, 2,
    // Type 1
    7, 5, 6, 4,  6, 3, 7, 2,  7, 5, 3, 1,  5, 4, 2, 1,
    // Type 2
    7, 6, 5, 4,  6, 7, 2, 3,  5, 1, 7, 3,  4, 5, 1, 2,
    // Type 3
    7, 5, 6, 4,  6, 3, 7, 2,  7, 5, 3, 1,  5, 4, 2, 1,
    // Type 4
    7, 6, 5, 4,  6, 7, 2, 3,  5, 1, 7, 3,  4, 5, 1, 2,
    // Type 5
    7, 5, 6, 4,  6, 3, 7, 2,  7, 5, 3, 1,  5, 4, 2, 1
};

// ============================================================================
// NODE ACCESS FUNCTIONS
// ============================================================================

int getTetType(ESVTNode node) {
    return (node.contourDescriptor >> 1) & 0x7;
}

bool isValid(ESVTNode node) {
    return (node.childDescriptor & 0x80000000u) != 0;
}

uint getChildMask(ESVTNode node) {
    return (node.childDescriptor >> 8) & 0xFFu;
}

uint getLeafMask(ESVTNode node) {
    return node.childDescriptor & 0xFFu;
}

bool hasChild(ESVTNode node, int childIdx) {
    return (getChildMask(node) & (1u << childIdx)) != 0;
}

bool isChildLeaf(ESVTNode node, int childIdx) {
    return (getLeafMask(node) & (1u << childIdx)) != 0;
}

uint getChildPtr(ESVTNode node) {
    return (node.childDescriptor >> 17) & 0x3FFFu;
}

bool isFar(ESVTNode node) {
    return (node.childDescriptor & 0x10000u) != 0;
}

uint getChildIndex(__global const ESVTNode* nodes, ESVTNode node, int childIdx, uint parentIdx) {
    uint childPtr = getChildPtr(node);
    uint mask = getChildMask(node);
    uint offset = popcount(mask & ((1u << childIdx) - 1u));

    if (isFar(node)) {
        childPtr = nodes[parentIdx + childPtr].childDescriptor;
    }

    return parentIdx + childPtr + offset;
}

// ============================================================================
// CONTOUR ACCESS FUNCTIONS
// ============================================================================

uint getContourMask(ESVTNode node) {
    return (node.contourDescriptor >> 4) & 0xFu;
}

uint getContourPtr(ESVTNode node) {
    return node.contourDescriptor >> 12;
}

bool hasContour(ESVTNode node, int faceIdx) {
    return (getContourMask(node) & (1u << faceIdx)) != 0;
}

// ============================================================================
// CONTOUR DECODING
// ============================================================================

#define EXP2_NEG25 (1.0f / 33554432.0f)
#define EXP2_NEG26 (1.0f / 67108864.0f)

float3 decodeContourNormal(int value) {
    float x = (float)(value << 14) * EXP2_NEG26;
    float y = (float)(value << 20) * EXP2_NEG26;
    float z = (float)(value << 26) * EXP2_NEG26;
    return (float3)(x, y, z);
}

float2 decodeContourPosThick(int value) {
    float pos = (float)(value << 7) * EXP2_NEG25 * 0.75f;
    float thick = (float)((uint)value) * 0.75f * EXP2_NEG25;
    return (float2)(pos, thick);
}

float2 intersectContour(int contour, float3 rayOrigin, float3 rayDir, float tetScale) {
    float3 normal = decodeContourNormal(contour);
    float2 posThick = decodeContourPosThick(contour);

    float pos = posThick.x * tetScale;
    float halfThick = posThick.y * tetScale * 0.5f;

    float denom = dot(normal, rayDir);
    float originDot = dot(normal, rayOrigin);

    if (fabs(denom) < 1e-10f) {
        float dist = fabs(originDot - pos);
        if (dist <= halfThick) {
            return (float2)(-1e30f, 1e30f);
        }
        return (float2)(-1.0f, -1.0f);
    }

    float t1 = (pos - halfThick - originDot) / denom;
    float t2 = (pos + halfThick - originDot) / denom;

    if (t1 > t2) {
        float tmp = t1;
        t1 = t2;
        t2 = tmp;
    }

    return (float2)(t1, t2);
}

ContourRefinement refineHitWithContours(ESVTNode node, int entryFace,
                                        float tEntry, float tExit,
                                        float3 rayOrigin, float3 rayDir,
                                        float tetScale,
                                        __global const int* contours) {
    ContourRefinement result;
    result.t = tEntry;
    result.normal = (float3)(0.0f);
    result.valid = true;

    uint contourMask = getContourMask(node);
    if (contourMask == 0u) {
        return result;
    }

    uint contourPtr = getContourPtr(node);
    uint contourOffset = popcount(contourMask & ((1u << entryFace) - 1u));

    if (hasContour(node, entryFace)) {
        int contour = contours[contourPtr + contourOffset];
        float2 contourHit = intersectContour(contour, rayOrigin, rayDir, tetScale);

        if (contourHit.x > 0.0f && contourHit.y > 0.0f) {
            float3 contourNormal = decodeContourNormal(contour);
            float2 posThick = decodeContourPosThick(contour);
            float pos = posThick.x * tetScale;

            float denom = dot(contourNormal, rayDir);
            if (fabs(denom) > 1e-10f) {
                float refinedT = (pos - dot(contourNormal, rayOrigin)) / denom;
                if (refinedT >= tEntry && refinedT <= tExit) {
                    result.t = refinedT;
                    result.normal = normalize(contourNormal);
                    if (dot(result.normal, rayDir) > 0) {
                        result.normal = -result.normal;
                    }
                }
            }
        } else if (contourHit.x == -1.0f && contourHit.y == -1.0f) {
            result.valid = false;
        }
    }

    return result;
}

// ============================================================================
// MOLLER-TRUMBORE RAY-TRIANGLE INTERSECTION
// ============================================================================

TriangleHit intersectTriangle(float3 rayOrigin, float3 rayDir,
                              float3 v0, float3 v1, float3 v2) {
    TriangleHit result;
    result.hit = false;
    result.t = 1e30f;
    result.u = 0.0f;
    result.v = 0.0f;

    float3 edge1 = v1 - v0;
    float3 edge2 = v2 - v0;

    float3 h = cross(rayDir, edge2);
    float a = dot(edge1, h);

    if (fabs(a) < EPSILON) {
        return result;
    }

    float f = 1.0f / a;
    float3 s = rayOrigin - v0;
    float u = f * dot(s, h);

    if (u < 0.0f || u > 1.0f) {
        return result;
    }

    float3 q = cross(s, edge1);
    float v = f * dot(rayDir, q);

    if (v < 0.0f || u + v > 1.0f) {
        return result;
    }

    float t = f * dot(edge2, q);

    if (t > EPSILON) {
        result.hit = true;
        result.t = t;
        result.u = u;
        result.v = v;
    }

    return result;
}

// ============================================================================
// RAY-TETRAHEDRON INTERSECTION
// ============================================================================

TetrahedronHit intersectTetrahedron(float3 rayOrigin, float3 rayDir,
                                    float3 v0, float3 v1, float3 v2, float3 v3) {
    TetrahedronHit result;
    result.hit = false;
    result.tEntry = 1e30f;
    result.tExit = -1e30f;
    result.entryFace = -1;
    result.exitFace = -1;

    // Face 0: opposite v0, triangle (v1, v2, v3)
    TriangleHit tri = intersectTriangle(rayOrigin, rayDir, v1, v2, v3);
    if (tri.hit) {
        if (tri.t < result.tEntry) { result.tEntry = tri.t; result.entryFace = 0; }
        if (tri.t > result.tExit) { result.tExit = tri.t; result.exitFace = 0; }
    }

    // Face 1: opposite v1, triangle (v0, v2, v3)
    tri = intersectTriangle(rayOrigin, rayDir, v0, v2, v3);
    if (tri.hit) {
        if (tri.t < result.tEntry) { result.tEntry = tri.t; result.entryFace = 1; }
        if (tri.t > result.tExit) { result.tExit = tri.t; result.exitFace = 1; }
    }

    // Face 2: opposite v2, triangle (v0, v1, v3)
    tri = intersectTriangle(rayOrigin, rayDir, v0, v1, v3);
    if (tri.hit) {
        if (tri.t < result.tEntry) { result.tEntry = tri.t; result.entryFace = 2; }
        if (tri.t > result.tExit) { result.tExit = tri.t; result.exitFace = 2; }
    }

    // Face 3: opposite v3, triangle (v0, v1, v2)
    tri = intersectTriangle(rayOrigin, rayDir, v0, v1, v2);
    if (tri.hit) {
        if (tri.t < result.tEntry) { result.tEntry = tri.t; result.entryFace = 3; }
        if (tri.t > result.tExit) { result.tExit = tri.t; result.exitFace = 3; }
    }

    if (result.entryFace >= 0 && result.exitFace >= 0 && result.tEntry < result.tExit) {
        result.hit = true;
    } else if (result.entryFace < 0 && result.exitFace >= 0 && result.tExit > 0) {
        result.hit = true;
        result.tEntry = 0.0f;
        result.entryFace = -1;
    }

    return result;
}

// ============================================================================
// CHILD VERTICES (BEY SUBDIVISION)
// ============================================================================

void getChildVertices(int parentType, int childIdx, float scale,
                      float3* cv0, float3* cv1, float3* cv2, float3* cv3) {
    // Get parent vertices
    float3 pv0 = SIMPLEX_STANDARD[parentType * 4 + 0];
    float3 pv1 = SIMPLEX_STANDARD[parentType * 4 + 1];
    float3 pv2 = SIMPLEX_STANDARD[parentType * 4 + 2];
    float3 pv3 = SIMPLEX_STANDARD[parentType * 4 + 3];

    // Edge midpoints
    float3 m01 = (pv0 + pv1) * 0.5f;
    float3 m02 = (pv0 + pv2) * 0.5f;
    float3 m03 = (pv0 + pv3) * 0.5f;
    float3 m12 = (pv1 + pv2) * 0.5f;
    float3 m13 = (pv1 + pv3) * 0.5f;
    float3 m23 = (pv2 + pv3) * 0.5f;

    switch (childIdx) {
        case 0: *cv0 = pv0 * scale; *cv1 = m01 * scale; *cv2 = m02 * scale; *cv3 = m03 * scale; break;
        case 1: *cv0 = pv1 * scale; *cv1 = m01 * scale; *cv2 = m12 * scale; *cv3 = m13 * scale; break;
        case 2: *cv0 = pv2 * scale; *cv1 = m02 * scale; *cv2 = m12 * scale; *cv3 = m23 * scale; break;
        case 3: *cv0 = pv3 * scale; *cv1 = m03 * scale; *cv2 = m13 * scale; *cv3 = m23 * scale; break;
        case 4: *cv0 = m01 * scale; *cv1 = m02 * scale; *cv2 = m03 * scale; *cv3 = m12 * scale; break;
        case 5: *cv0 = m01 * scale; *cv1 = m02 * scale; *cv2 = m12 * scale; *cv3 = m13 * scale; break;
        case 6: *cv0 = m02 * scale; *cv1 = m03 * scale; *cv2 = m12 * scale; *cv3 = m23 * scale; break;
        case 7: *cv0 = m03 * scale; *cv1 = m12 * scale; *cv2 = m13 * scale; *cv3 = m23 * scale; break;
    }
}

// ============================================================================
// MAIN TRAVERSAL KERNEL
// ============================================================================

__kernel void traverseESVT(
    __global const Ray* rays,
    __global const ESVTNode* nodes,
    __global const int* contours,
    __global float4* hitResults,    // xyz = hit point, w = distance
    __global float4* hitNormals,    // xyz = normal, w = 1 if hit
    const uint maxDepth,
    const float3 sceneMin,
    const float3 sceneMax)
{
    int gid = get_global_id(0);
    Ray ray = rays[gid];

    // Initialize results
    hitResults[gid] = (float4)(0.0f, 0.0f, 0.0f, -1.0f);
    hitNormals[gid] = (float4)(0.0f, 1.0f, 0.0f, 0.0f);

    // Avoid division by zero
    if (fabs(ray.direction.x) < EPSILON) ray.direction.x = EPSILON;
    if (fabs(ray.direction.y) < EPSILON) ray.direction.y = EPSILON;
    if (fabs(ray.direction.z) < EPSILON) ray.direction.z = EPSILON;

    // Local stack
    StackEntry stack[CAST_STACK_DEPTH];
    for (int i = 0; i < CAST_STACK_DEPTH; i++) {
        stack[i].nodeIdx = 0xFFFFFFFFu;
    }

    // Get root node
    ESVTNode rootNode = nodes[0];
    if (!isValid(rootNode)) {
        return;
    }

    int rootType = getTetType(rootNode);

    // Get root tetrahedron vertices
    float3 v0 = SIMPLEX_STANDARD[rootType * 4 + 0];
    float3 v1 = SIMPLEX_STANDARD[rootType * 4 + 1];
    float3 v2 = SIMPLEX_STANDARD[rootType * 4 + 2];
    float3 v3 = SIMPLEX_STANDARD[rootType * 4 + 3];

    // Test ray-root intersection
    TetrahedronHit rootHit = intersectTetrahedron(ray.origin, ray.direction, v0, v1, v2, v3);
    if (!rootHit.hit) {
        return;
    }

    // Initialize traversal state
    uint parentIdx = 0u;
    int parentType = rootType;
    int entryFace = rootHit.entryFace;
    float tMin = rootHit.tEntry;
    float tMax = rootHit.tExit;
    int scale = CAST_STACK_DEPTH - 1;
    float scaleExp2 = 1.0f;
    int siblingPos = 0;
    int iter = 0;

    // Main traversal loop
    while (scale < CAST_STACK_DEPTH && iter < MAX_RAYCAST_ITERATIONS) {
        iter++;

        ESVTNode node = nodes[parentIdx];
        if (!isValid(node)) {
            scale++;
            if (scale >= CAST_STACK_DEPTH || stack[scale].nodeIdx == 0xFFFFFFFFu) {
                break;
            }
            parentIdx = stack[scale].nodeIdx;
            tMax = stack[scale].tMax;
            parentType = stack[scale].parentType;
            entryFace = stack[scale].entryFace;
            siblingPos = 0;
            scaleExp2 *= 2.0f;
            continue;
        }

        // Get child order for entry face
        int orderBase = parentType * 16 + max(0, entryFace) * 4;

        // Try each child
        bool descended = false;
        for (int pos = siblingPos; pos < 4; pos++) {
            int childIdx = CHILD_ORDER[orderBase + pos];

            if (!hasChild(node, childIdx)) {
                continue;
            }

            // Get child vertices
            float3 cv0, cv1, cv2, cv3;
            getChildVertices(parentType, childIdx, scaleExp2 * 0.5f, &cv0, &cv1, &cv2, &cv3);

            // Test intersection
            TetrahedronHit childHit = intersectTetrahedron(ray.origin, ray.direction, cv0, cv1, cv2, cv3);
            if (!childHit.hit) {
                continue;
            }

            // Check if leaf
            if (isChildLeaf(node, childIdx)) {
                uint childNodeIdx = getChildIndex(nodes, node, childIdx, parentIdx);
                ESVTNode childNode = nodes[childNodeIdx];
                float childScale = scaleExp2 * 0.5f;

                ContourRefinement contourRef = refineHitWithContours(
                    childNode, childHit.entryFace, childHit.tEntry, childHit.tExit,
                    ray.origin, ray.direction, childScale, contours);

                if (!contourRef.valid) {
                    continue;
                }

                float3 hitPoint = ray.origin + ray.direction * contourRef.t;
                float3 hitNormal;

                if (length(contourRef.normal) > 0.5f) {
                    hitNormal = contourRef.normal;
                } else {
                    float3 fv0, fv1, fv2;
                    int ef = childHit.entryFace;
                    if (ef == 0) { fv0 = cv1; fv1 = cv2; fv2 = cv3; }
                    else if (ef == 1) { fv0 = cv0; fv1 = cv2; fv2 = cv3; }
                    else if (ef == 2) { fv0 = cv0; fv1 = cv1; fv2 = cv3; }
                    else { fv0 = cv0; fv1 = cv1; fv2 = cv2; }

                    hitNormal = normalize(cross(fv1 - fv0, fv2 - fv0));
                    if (dot(hitNormal, ray.direction) > 0) {
                        hitNormal = -hitNormal;
                    }
                }

                hitResults[gid] = (float4)(hitPoint.x, hitPoint.y, hitPoint.z, contourRef.t);
                hitNormals[gid] = (float4)(hitNormal.x, hitNormal.y, hitNormal.z, 1.0f);
                return;
            }

            // Non-leaf - push and descend
            stack[scale].nodeIdx = parentIdx;
            stack[scale].tMax = tMax;
            stack[scale].parentType = parentType;
            stack[scale].entryFace = entryFace;

            parentIdx = getChildIndex(nodes, node, childIdx, parentIdx);
            parentType = PARENT_TYPE_TO_CHILD_TYPE[parentType * 8 + childIdx];
            entryFace = childHit.entryFace >= 0 ? childHit.entryFace : 0;
            tMin = childHit.tEntry;
            tMax = childHit.tExit;
            scale--;
            scaleExp2 *= 0.5f;
            siblingPos = 0;
            descended = true;
            break;
        }

        if (!descended) {
            if (scale >= CAST_STACK_DEPTH - 1) {
                break;
            }

            scale++;
            scaleExp2 *= 2.0f;

            if (stack[scale].nodeIdx == 0xFFFFFFFFu) {
                break;
            }

            parentIdx = stack[scale].nodeIdx;
            tMax = stack[scale].tMax;
            parentType = stack[scale].parentType;
            entryFace = stack[scale].entryFace;
            siblingPos = 0;
        }
    }
}

// ============================================================================
// BEAM OPTIMIZATION KERNEL
// ============================================================================

__kernel void traverseESVTBeam(
    __global const Ray* rays,
    __global const ESVTNode* nodes,
    __global const int* contours,
    __global float4* hitResults,
    __global float4* hitNormals,
    const uint raysPerBeam,
    const uint maxDepth,
    const float3 sceneMin,
    const float3 sceneMax)
{
    int beamId = get_global_id(0);
    int firstRay = beamId * raysPerBeam;

    // Compute beam frustum
    float3 frustumMin = (float3)(INFINITY);
    float3 frustumMax = (float3)(-INFINITY);

    for (uint i = 0; i < raysPerBeam; i++) {
        Ray ray = rays[firstRay + i];
        float3 nearPoint = ray.origin + ray.direction * ray.tmin;
        float3 farPoint = ray.origin + ray.direction * ray.tmax;

        frustumMin = min(frustumMin, min(nearPoint, farPoint));
        frustumMax = max(frustumMax, max(nearPoint, farPoint));
    }

    // Process each ray in the beam
    for (uint i = 0; i < raysPerBeam; i++) {
        int rayIdx = firstRay + i;
        Ray ray = rays[rayIdx];

        // Initialize results
        hitResults[rayIdx] = (float4)(0.0f, 0.0f, 0.0f, -1.0f);
        hitNormals[rayIdx] = (float4)(0.0f, 1.0f, 0.0f, 0.0f);

        // ... similar traversal logic as above ...
        // (Simplified for beam - would share stack processing across beam)
    }
}
