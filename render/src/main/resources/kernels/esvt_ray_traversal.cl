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

// Ray data is passed as raw floats to avoid ALL struct alignment issues
// Layout per ray (8 floats = 32 bytes):
//   [0] origin.x, [1] origin.y, [2] origin.z
//   [3] direction.x, [4] direction.y, [5] direction.z
//   [6] tmin, [7] tmax
#define RAY_STRIDE 8

// Helper to extract ray data from raw float buffer
inline float3 getRayOriginFromBuffer(__global const float* rays, int rayIdx) {
    int base = rayIdx * RAY_STRIDE;
    return (float3)(rays[base + 0], rays[base + 1], rays[base + 2]);
}

inline float3 getRayDirectionFromBuffer(__global const float* rays, int rayIdx) {
    int base = rayIdx * RAY_STRIDE;
    return (float3)(rays[base + 3], rays[base + 4], rays[base + 5]);
}

inline float getRayTminFromBuffer(__global const float* rays, int rayIdx) {
    return rays[rayIdx * RAY_STRIDE + 6];
}

inline float getRayTmaxFromBuffer(__global const float* rays, int rayIdx) {
    return rays[rayIdx * RAY_STRIDE + 7];
}

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
    // Store actual parent vertices for correct multi-level traversal
    float3 v0, v1, v2, v3;
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

// Note: Validity is derived from masks, matching Java ESVTNodeUnified.isValid()
// A node is valid if it has children or is a leaf.
bool isValid(ESVTNode node) {
    uint childMask = (node.childDescriptor >> 8) & 0xFFu;
    uint leafMask = node.childDescriptor & 0xFFu;
    return childMask != 0 || leafMask != 0;
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

// ARITHMETIC VERSION: Avoids fabs() which crashes on macOS OpenCL
float2 intersectContour(int contour, float3 rayOrigin, float3 rayDir, float tetScale) {
    float3 normal = decodeContourNormal(contour);
    float2 posThick = decodeContourPosThick(contour);

    float pos = posThick.x * tetScale;
    float halfThick = posThick.y * tetScale * 0.5f;

    float denom = dot(normal, rayDir);
    float originDot = dot(normal, rayOrigin);

    // MANUAL absolute value (fabs() crashes on macOS OpenCL)
    float absDenom = (denom >= 0.0f) ? denom : -denom;

    if (absDenom < 1e-10f) {
        // MANUAL absolute value for distance check
        float diff = originDot - pos;
        float dist = (diff >= 0.0f) ? diff : -diff;
        if (dist <= halfThick) {
            return (float2)(-1e30f, 1e30f);
        }
        return (float2)(-1.0f, -1.0f);
    }

    float t1 = (pos - halfThick - originDot) / denom;
    float t2 = (pos + halfThick - originDot) / denom;

    // Use arithmetic swap instead of conditional
    float minT = (t1 < t2) ? t1 : t2;
    float maxT = (t1 < t2) ? t2 : t1;

    return (float2)(minT, maxT);
}

// INPLACE VERSION: Avoids struct return AND fabs() crashes on macOS OpenCL
void refineHitWithContoursInplace(ESVTNode node, int entryFace,
                                   float tEntry, float tExit,
                                   float3 rayOrigin, float3 rayDir,
                                   float tetScale,
                                   __global const int* contours,
                                   float* tOut, float3* normalOut, bool* validOut) {
    *tOut = tEntry;
    *normalOut = (float3)(0.0f);
    *validOut = true;

    uint contourMask = getContourMask(node);
    if (contourMask == 0u) {
        return;
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
            // MANUAL absolute value (fabs() crashes on macOS OpenCL)
            float absDenom = (denom >= 0.0f) ? denom : -denom;
            if (absDenom > 1e-10f) {
                float refinedT = (pos - dot(contourNormal, rayOrigin)) / denom;
                if (refinedT >= tEntry && refinedT <= tExit) {
                    *tOut = refinedT;
                    *normalOut = normalize(contourNormal);
                    if (dot(*normalOut, rayDir) > 0) {
                        *normalOut = -(*normalOut);
                    }
                }
            }
        } else if (contourHit.x == -1.0f && contourHit.y == -1.0f) {
            *validOut = false;
        }
    }
}

// Legacy wrapper (may crash on some OpenCL implementations)
ContourRefinement refineHitWithContours(ESVTNode node, int entryFace,
                                        float tEntry, float tExit,
                                        float3 rayOrigin, float3 rayDir,
                                        float tetScale,
                                        __global const int* contours) {
    ContourRefinement result;
    refineHitWithContoursInplace(node, entryFace, tEntry, tExit, rayOrigin, rayDir,
                                  tetScale, contours, &result.t, &result.normal, &result.valid);
    return result;
}

// ============================================================================
// MOLLER-TRUMBORE RAY-TRIANGLE INTERSECTION
// ============================================================================

// ARITHMETIC VERSION: Avoids fabs() which can crash on macOS OpenCL
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

    // MANUAL absolute value (fabs() can crash on macOS OpenCL)
    float absA = (a >= 0.0f) ? a : -a;
    if (absA < EPSILON) {
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

// INTEGER-BASED tetrahedron intersection (avoids macOS OpenCL crash patterns)
// Uses face index comparison instead of float equality to detect edge/inside cases
void intersectTetrahedronInplace(float3 rayOrigin, float3 rayDir,
                                 float3 v0, float3 v1, float3 v2, float3 v3,
                                 bool* hitOut, float* tEntryOut, float* tExitOut,
                                 int* entryFaceOut, int* exitFaceOut) {
    float tMin = 1e30f;
    float tMax = -1e30f;
    int minFace = -1;
    int maxFace = -1;
    int hitCount = 0;

    // Face 0: opposite v0, triangle (v1, v2, v3)
    TriangleHit tri0 = intersectTriangle(rayOrigin, rayDir, v1, v2, v3);
    if (tri0.hit) {
        hitCount++;
        if (tri0.t < tMin) { tMin = tri0.t; minFace = 0; }
        if (tri0.t > tMax) { tMax = tri0.t; maxFace = 0; }
    }

    // Face 1: opposite v1, triangle (v0, v2, v3)
    TriangleHit tri1 = intersectTriangle(rayOrigin, rayDir, v0, v2, v3);
    if (tri1.hit) {
        hitCount++;
        if (tri1.t < tMin) { tMin = tri1.t; minFace = 1; }
        if (tri1.t > tMax) { tMax = tri1.t; maxFace = 1; }
    }

    // Face 2: opposite v2, triangle (v0, v1, v3)
    TriangleHit tri2 = intersectTriangle(rayOrigin, rayDir, v0, v1, v3);
    if (tri2.hit) {
        hitCount++;
        if (tri2.t < tMin) { tMin = tri2.t; minFace = 2; }
        if (tri2.t > tMax) { tMax = tri2.t; maxFace = 2; }
    }

    // Face 3: opposite v3, triangle (v0, v1, v2)
    TriangleHit tri3 = intersectTriangle(rayOrigin, rayDir, v0, v1, v2);
    if (tri3.hit) {
        hitCount++;
        if (tri3.t < tMin) { tMin = tri3.t; minFace = 3; }
        if (tri3.t > tMax) { tMax = tri3.t; maxFace = 3; }
    }

    // Integer-based hit determination (avoids float == comparison crash on macOS)
    // Cases:
    //   hitCount >= 2 with minFace != maxFace: Valid intersection (normal or edge hit)
    //   hitCount == 1 with positive t: Ray starts inside tetrahedron
    //   Otherwise: Miss
    bool hit = false;
    if (hitCount >= 2) {
        if (minFace != maxFace) {
            hit = true;
            if (tMin <= 0.0f) {
                tMin = 0.0f;  // Ray starts inside
            }
        }
    } else if (hitCount == 1 && maxFace >= 0 && tMax > 0.0f) {
        hit = true;
        tMin = 0.0f;  // Ray starts inside
    }

    *hitOut = hit;
    *tEntryOut = tMin;
    *tExitOut = tMax;
    *entryFaceOut = minFace;
    *exitFaceOut = maxFace;
}

// Wrapper that returns struct (for compatibility) - may crash on some OpenCL implementations
TetrahedronHit intersectTetrahedron(float3 rayOrigin, float3 rayDir,
                                    float3 v0, float3 v1, float3 v2, float3 v3) {
    TetrahedronHit result;
    intersectTetrahedronInplace(rayOrigin, rayDir, v0, v1, v2, v3,
                                &result.hit, &result.tEntry, &result.tExit,
                                &result.entryFace, &result.exitFace);
    return result;
}

// ============================================================================
// CHILD VERTICES (BEY SUBDIVISION)
// ============================================================================

// Computes child tetrahedron vertices using Bey 8-way subdivision.
// CRITICAL: Takes ACTUAL parent vertices, not canonical SIMPLEX_STANDARD positions.
// This enables correct multi-level traversal where parent positions vary.
// In Bey subdivision, children fill the parent completely using edge midpoints.
void getChildVerticesFromParent(float3 pv0, float3 pv1, float3 pv2, float3 pv3,
                                int childIdx,
                                float3* cv0, float3* cv1, float3* cv2, float3* cv3) {
    // Edge midpoints of the ACTUAL parent tetrahedron
    float3 m01 = (pv0 + pv1) * 0.5f;
    float3 m02 = (pv0 + pv2) * 0.5f;
    float3 m03 = (pv0 + pv3) * 0.5f;
    float3 m12 = (pv1 + pv2) * 0.5f;
    float3 m13 = (pv1 + pv3) * 0.5f;
    float3 m23 = (pv2 + pv3) * 0.5f;

    // Corner children (0-3): one parent vertex + three edge midpoints from that vertex
    // Interior children (4-7): four edge midpoints (octahedral region subdivided)
    switch (childIdx) {
        case 0: *cv0 = pv0; *cv1 = m01; *cv2 = m02; *cv3 = m03; break;
        case 1: *cv0 = pv1; *cv1 = m01; *cv2 = m12; *cv3 = m13; break;
        case 2: *cv0 = pv2; *cv1 = m02; *cv2 = m12; *cv3 = m23; break;
        case 3: *cv0 = pv3; *cv1 = m03; *cv2 = m13; *cv3 = m23; break;
        case 4: *cv0 = m01; *cv1 = m02; *cv2 = m03; *cv3 = m12; break;
        case 5: *cv0 = m01; *cv1 = m02; *cv2 = m12; *cv3 = m13; break;
        case 6: *cv0 = m02; *cv1 = m03; *cv2 = m12; *cv3 = m23; break;
        case 7: *cv0 = m03; *cv1 = m12; *cv2 = m13; *cv3 = m23; break;
    }
}

// Legacy wrapper for root level (uses SIMPLEX_STANDARD)
void getChildVertices(int parentType, int childIdx, float scale,
                      float3* cv0, float3* cv1, float3* cv2, float3* cv3) {
    float3 pv0 = SIMPLEX_STANDARD[parentType * 4 + 0];
    float3 pv1 = SIMPLEX_STANDARD[parentType * 4 + 1];
    float3 pv2 = SIMPLEX_STANDARD[parentType * 4 + 2];
    float3 pv3 = SIMPLEX_STANDARD[parentType * 4 + 3];
    getChildVerticesFromParent(pv0, pv1, pv2, pv3, childIdx, cv0, cv1, cv2, cv3);
}

// ============================================================================
// MAIN TRAVERSAL KERNEL
// ============================================================================

__kernel void traverseESVT(
    __global const float* rays,     // Raw float buffer, 8 floats per ray
    __global const ESVTNode* nodes,
    __global const int* contours,
    __global float4* hitResults,    // xyz = hit point, w = distance
    __global float4* hitNormals,    // xyz = normal, w = 1 if hit
    const uint maxDepth)
    // Note: sceneMin/sceneMax removed - root tetrahedron uses SIMPLEX_STANDARD
{
    int gid = get_global_id(0);

    // Extract ray data from raw float buffer (avoids ALL struct alignment issues)
    float3 rayOrigin = getRayOriginFromBuffer(rays, gid);
    float3 rayDir = getRayDirectionFromBuffer(rays, gid);

    // EARLY DIAGNOSTIC: Show ray origin as color to verify data transfer
    // This outputs BEFORE any intersection logic
    // Set to 1 to enable early ray data diagnostic
    #if 0
    hitResults[gid] = (float4)(rayOrigin.x, rayOrigin.y, rayOrigin.z, 1.0f);
    hitNormals[gid] = (float4)(fabs(rayDir.x), fabs(rayDir.y), fabs(rayDir.z), 1.0f);
    return;
    #endif

    // Initialize results - DIAGNOSTIC: Start with dark blue background
    hitResults[gid] = (float4)(0.0f, 0.0f, 0.0f, -1.0f);
    hitNormals[gid] = (float4)(0.0f, 0.0f, 0.0f, 0.0f);  // No hit initially

    // DEBUG: Output cyan to verify kernel reaches this point
    // Set to 1 to enable
    #if 0
    hitResults[gid] = (float4)(0.0f, 1.0f, 1.0f, 1.0f);  // Cyan
    hitNormals[gid] = (float4)(0.0f, 0.0f, 1.0f, 1.0f);
    return;
    #endif

    // Avoid division by zero - USE MANUAL ABS (fabs() crashes on macOS OpenCL!)
    float absX = (rayDir.x >= 0.0f) ? rayDir.x : -rayDir.x;
    float absY = (rayDir.y >= 0.0f) ? rayDir.y : -rayDir.y;
    float absZ = (rayDir.z >= 0.0f) ? rayDir.z : -rayDir.z;
    if (absX < EPSILON) rayDir.x = EPSILON;
    if (absY < EPSILON) rayDir.y = EPSILON;
    if (absZ < EPSILON) rayDir.z = EPSILON;

    // Local stack
    StackEntry stack[CAST_STACK_DEPTH];
    for (int i = 0; i < CAST_STACK_DEPTH; i++) {
        stack[i].nodeIdx = 0xFFFFFFFFu;
    }

    // Get root node
    ESVTNode rootNode = nodes[0];
    if (!isValid(rootNode)) {
        // DIAGNOSTIC: Magenta = invalid root node
        hitResults[gid] = (float4)(1.0f, 0.0f, 1.0f, 0.1f);
        hitNormals[gid] = (float4)(0.0f, 1.0f, 0.0f, 1.0f);
        return;
    }

    int rootType = getTetType(rootNode);

    // DEBUG: Yellow = passed root validity check
    // Set to 1 to enable
    #if 0
    hitResults[gid] = (float4)(1.0f, 1.0f, 0.0f, 1.0f);  // Yellow
    hitNormals[gid] = (float4)(0.5f, 0.5f, 0.0f, 1.0f);
    return;
    #endif

    // Get root tetrahedron vertices from canonical positions
    float3 pv0 = SIMPLEX_STANDARD[rootType * 4 + 0];
    float3 pv1 = SIMPLEX_STANDARD[rootType * 4 + 1];
    float3 pv2 = SIMPLEX_STANDARD[rootType * 4 + 2];
    float3 pv3 = SIMPLEX_STANDARD[rootType * 4 + 3];

    // DEBUG: Output tetrahedron centroid as color to verify vertex lookup
    // Type 0 should have centroid at (0.75, 0.25, 0.5)
    // Set to 1 to enable
    #if 0
    float3 centroid = (pv0 + pv1 + pv2 + pv3) * 0.25f;
    hitResults[gid] = (float4)(centroid.x, centroid.y, centroid.z, 1.0f);
    hitNormals[gid] = (float4)(0.0f, 1.0f, 0.0f, 1.0f);  // Hit flag = 1
    return;
    #endif

    // DEBUG: Verify we reach just before intersection call
    // Set to 1 to enable
    #if 0
    hitResults[gid] = (float4)(pv0.x, pv1.x, pv2.x, pv3.x);  // x coordinates
    hitNormals[gid] = (float4)((float)rootType / 6.0f, 1.0f, 0.0f, 1.0f);
    return;
    #endif

    // DEBUG: Test intersection function in isolation - INCREMENTAL VERSION
    // Step 3: Add hit detection with integer comparisons
    #if 0
    {
        // Triangle intersections for each face
        TriangleHit tri0 = intersectTriangle(rayOrigin, rayDir, pv1, pv2, pv3);
        TriangleHit tri1 = intersectTriangle(rayOrigin, rayDir, pv0, pv2, pv3);
        TriangleHit tri2 = intersectTriangle(rayOrigin, rayDir, pv0, pv1, pv3);
        TriangleHit tri3 = intersectTriangle(rayOrigin, rayDir, pv0, pv1, pv2);

        // Track min/max t values and faces
        float tMin = 1e30f;
        float tMax = -1e30f;
        int minFace = -1;
        int maxFace = -1;
        int hitCount = 0;

        if (tri0.hit) {
            hitCount++;
            if (tri0.t < tMin) { tMin = tri0.t; minFace = 0; }
            if (tri0.t > tMax) { tMax = tri0.t; maxFace = 0; }
        }
        if (tri1.hit) {
            hitCount++;
            if (tri1.t < tMin) { tMin = tri1.t; minFace = 1; }
            if (tri1.t > tMax) { tMax = tri1.t; maxFace = 1; }
        }
        if (tri2.hit) {
            hitCount++;
            if (tri2.t < tMin) { tMin = tri2.t; minFace = 2; }
            if (tri2.t > tMax) { tMax = tri2.t; maxFace = 2; }
        }
        if (tri3.hit) {
            hitCount++;
            if (tri3.t < tMin) { tMin = tri3.t; minFace = 3; }
            if (tri3.t > tMax) { tMax = tri3.t; maxFace = 3; }
        }

        // PURE ARITHMETIC HIT DETECTION (no conditionals - macOS OpenCL bug workaround)
        float fHitCount = (float)hitCount;

        // Hit if hitCount > 0 (any face was intersected)
        // Using arithmetic: clamp((float)hitCount, 0.0f, 1.0f) gives 0 for miss, 1 for hit
        float hitFlag = clamp(fHitCount, 0.0f, 1.0f);

        // Entry distance: use tMin, clamped to 0 for "inside" case
        // If tMin < 0, ray starts inside the tetrahedron
        float tEntry = tMin * hitFlag;  // 0 if no hit

        // Output with hitFlag as both distance modifier and hit indicator
        hitResults[gid] = (float4)(0.0f, hitFlag, 0.0f, tEntry);
        hitNormals[gid] = (float4)(0.0f, 1.0f, 0.0f, hitFlag);
        return;
    }
    #endif

    // Test ray-root intersection - PURE ARITHMETIC (no conditionals - macOS crash workaround)
    // Declare all variables at outer scope for use in traversal
    float rootHit_tEntry = 1e30f;
    float rootHit_tExit = -1e30f;
    int rootHit_entryFace = -1;
    int rootHit_exitFace = -1;
    int rootHitCount = 0;

    // Inline intersectTetrahedron with ARITHMETIC hit detection
    {
        float tMin = 1e30f;
        float tMax_local = -1e30f;
        int minFace = -1;
        int maxFace = -1;

        // Face 0: opposite v0, triangle (v1, v2, v3)
        TriangleHit tri0 = intersectTriangle(rayOrigin, rayDir, pv1, pv2, pv3);
        if (tri0.hit) {
            rootHitCount++;
            if (tri0.t < tMin) { tMin = tri0.t; minFace = 0; }
            if (tri0.t > tMax_local) { tMax_local = tri0.t; maxFace = 0; }
        }

        // Face 1: opposite v1, triangle (v0, v2, v3)
        TriangleHit tri1 = intersectTriangle(rayOrigin, rayDir, pv0, pv2, pv3);
        if (tri1.hit) {
            rootHitCount++;
            if (tri1.t < tMin) { tMin = tri1.t; minFace = 1; }
            if (tri1.t > tMax_local) { tMax_local = tri1.t; maxFace = 1; }
        }

        // Face 2: opposite v2, triangle (v0, v1, v3)
        TriangleHit tri2 = intersectTriangle(rayOrigin, rayDir, pv0, pv1, pv3);
        if (tri2.hit) {
            rootHitCount++;
            if (tri2.t < tMin) { tMin = tri2.t; minFace = 2; }
            if (tri2.t > tMax_local) { tMax_local = tri2.t; maxFace = 2; }
        }

        // Face 3: opposite v3, triangle (v0, v1, v2)
        TriangleHit tri3 = intersectTriangle(rayOrigin, rayDir, pv0, pv1, pv2);
        if (tri3.hit) {
            rootHitCount++;
            if (tri3.t < tMin) { tMin = tri3.t; minFace = 3; }
            if (tri3.t > tMax_local) { tMax_local = tri3.t; maxFace = 3; }
        }

        // Store root hit info at outer scope
        rootHit_tEntry = tMin;
        rootHit_tExit = tMax_local;
        rootHit_entryFace = minFace;
        rootHit_exitFace = maxFace;
    }

    // If root was hit, proceed with traversal
    if (rootHitCount > 0) {
        // Initialize traversal state
        // CRITICAL: Track actual parent vertices for correct multi-level traversal
        uint parentIdx = 0u;
        int parentType = rootType;
        int entryFace = rootHit_entryFace;
        float tMinLocal = rootHit_tEntry;
        float tMaxLocal = rootHit_tExit;
        int scale = CAST_STACK_DEPTH - 1;
        float scaleExp2 = 1.0f;
        int siblingPos = 0;
        int iter = 0;

        // Track best hit (using float flag to avoid conditional crash)
        float bestT = 1e30f;
        float3 bestHitPoint = (float3)(0.0f);
        float3 bestHitNormal = (float3)(0.0f, 1.0f, 0.0f);
        float foundLeafFlag = 0.0f;  // 0.0 = no leaf, 1.0 = found leaf

        // Main traversal loop
        while (scale < CAST_STACK_DEPTH && iter < MAX_RAYCAST_ITERATIONS) {
            iter++;

            ESVTNode node = nodes[parentIdx];
            if (!isValid(node)) {
                scale++;
                if (scale >= CAST_STACK_DEPTH || stack[scale].nodeIdx == 0xFFFFFFFFu) {
                    break;
                }
                // Restore state including parent vertices
                parentIdx = stack[scale].nodeIdx;
                tMaxLocal = stack[scale].tMax;
                parentType = stack[scale].parentType;
                entryFace = stack[scale].entryFace;
                pv0 = stack[scale].v0;
                pv1 = stack[scale].v1;
                pv2 = stack[scale].v2;
                pv3 = stack[scale].v3;
                siblingPos = 0;
                scaleExp2 *= 2.0f;
                continue;
            }

            // Get child order for entry face (use 0 if entryFace is invalid)
            int safeEntryFace = (entryFace >= 0 && entryFace < 4) ? entryFace : 0;
            int orderBase = parentType * 16 + safeEntryFace * 4;

            // Try each child
            bool descended = false;
            for (int pos = siblingPos; pos < 4; pos++) {
                int childIdx = CHILD_ORDER[orderBase + pos];

                if (!hasChild(node, childIdx)) {
                    continue;
                }

                // Get child vertices from ACTUAL parent vertices (not SIMPLEX_STANDARD)
                float3 cv0, cv1, cv2, cv3;
                getChildVerticesFromParent(pv0, pv1, pv2, pv3, childIdx, &cv0, &cv1, &cv2, &cv3);

                // Test intersection using inplace version (avoids struct return issues)
                bool childHit_hit;
                float childHit_tEntry, childHit_tExit;
                int childHit_entryFace, childHit_exitFace;
                intersectTetrahedronInplace(rayOrigin, rayDir, cv0, cv1, cv2, cv3,
                                            &childHit_hit, &childHit_tEntry, &childHit_tExit,
                                            &childHit_entryFace, &childHit_exitFace);
                if (!childHit_hit) {
                    continue;
                }

                // Check if leaf
                if (isChildLeaf(node, childIdx)) {
                    uint childNodeIdx = getChildIndex(nodes, node, childIdx, parentIdx);
                    ESVTNode childNode = nodes[childNodeIdx];
                    float childScale = scaleExp2 * 0.5f;

                    // Use inplace version to avoid struct return
                    float contourT;
                    float3 contourNormal;
                    bool contourValid;
                    refineHitWithContoursInplace(
                        childNode, childHit_entryFace, childHit_tEntry, childHit_tExit,
                        rayOrigin, rayDir, childScale, contours,
                        &contourT, &contourNormal, &contourValid);

                    if (!contourValid) {
                        continue;
                    }

                    // Found valid leaf hit - check if it's closer
                    if (contourT < bestT) {
                        bestT = contourT;
                        bestHitPoint = rayOrigin + rayDir * contourT;

                        // Calculate normal
                        float normalLen = length(contourNormal);
                        if (normalLen > 0.5f) {
                            bestHitNormal = contourNormal;
                        } else {
                            // Compute face normal based on entry face
                            float3 fv0, fv1, fv2;
                            int ef = childHit_entryFace;
                            // Use safe indexing to select face vertices
                            if (ef == 0) { fv0 = cv1; fv1 = cv2; fv2 = cv3; }
                            else if (ef == 1) { fv0 = cv0; fv1 = cv2; fv2 = cv3; }
                            else if (ef == 2) { fv0 = cv0; fv1 = cv1; fv2 = cv3; }
                            else { fv0 = cv0; fv1 = cv1; fv2 = cv2; }

                            bestHitNormal = normalize(cross(fv1 - fv0, fv2 - fv0));
                            if (dot(bestHitNormal, rayDir) > 0) {
                                bestHitNormal = -bestHitNormal;
                            }
                        }
                        foundLeafFlag = 1.0f;
                    }
                    // Continue to check other children at same level (may find closer hit)
                    continue;
                }

                // Non-leaf - push and descend
                // Save current state including parent vertices
                stack[scale].nodeIdx = parentIdx;
                stack[scale].tMax = tMaxLocal;
                stack[scale].parentType = parentType;
                stack[scale].entryFace = entryFace;
                stack[scale].v0 = pv0;
                stack[scale].v1 = pv1;
                stack[scale].v2 = pv2;
                stack[scale].v3 = pv3;

                // Update state for child - child vertices become new parent vertices
                parentIdx = getChildIndex(nodes, node, childIdx, parentIdx);
                parentType = PARENT_TYPE_TO_CHILD_TYPE[parentType * 8 + childIdx];
                entryFace = (childHit_entryFace >= 0) ? childHit_entryFace : 0;
                tMinLocal = childHit_tEntry;
                tMaxLocal = childHit_tExit;
                pv0 = cv0;
                pv1 = cv1;
                pv2 = cv2;
                pv3 = cv3;
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

                // Restore state including parent vertices
                parentIdx = stack[scale].nodeIdx;
                tMaxLocal = stack[scale].tMax;
                parentType = stack[scale].parentType;
                entryFace = stack[scale].entryFace;
                pv0 = stack[scale].v0;
                pv1 = stack[scale].v1;
                pv2 = stack[scale].v2;
                pv3 = stack[scale].v3;
                siblingPos = 0;
            }
        }

        // ARITHMETIC OUTPUT: Blend based on foundLeafFlag
        // foundLeafFlag is already 0.0f or 1.0f - no conditional needed
        float leafHitFlag = foundLeafFlag;
        float noLeafFlag = 1.0f - leafHitFlag;

        // If leaf found: output hit data
        // If no leaf but root hit: output root hit (green tint)
        // The missFlag was computed above for root miss case
        float rootOnlyFlag = noLeafFlag;

        // Output using arithmetic blending
        // Leaf hit: bestHitPoint, bestT
        // Root hit only: show green tint at root intersection
        // Root miss: show ray direction as background

        float4 leafResult = (float4)(bestHitPoint.x, bestHitPoint.y, bestHitPoint.z, bestT);
        float4 leafNormal = (float4)(bestHitNormal.x, bestHitNormal.y, bestHitNormal.z, 1.0f);

        float4 rootOnlyResult = (float4)(0.0f, 0.5f, 0.0f, tMinLocal);
        float4 rootOnlyNormal = (float4)(0.0f, 1.0f, 0.0f, 1.0f);

        // Blend: prefer leaf hit, then root hit
        hitResults[gid] = leafHitFlag * leafResult + rootOnlyFlag * rootOnlyResult;
        hitNormals[gid] = leafHitFlag * leafNormal + rootOnlyFlag * rootOnlyNormal;
    } else {
        // Root miss case - output background using arithmetic
        float absRayDirX = (rayDir.x >= 0.0f) ? rayDir.x : -rayDir.x;
        float absRayDirY = (rayDir.y >= 0.0f) ? rayDir.y : -rayDir.y;
        float absRayDirZ = (rayDir.z >= 0.0f) ? rayDir.z : -rayDir.z;
        hitResults[gid] = (float4)(absRayDirX, absRayDirY, absRayDirZ, 0.1f);
        hitNormals[gid] = (float4)(1.0f, 0.0f, 0.0f, 0.0f);  // Red tint, no hit
    }
}

// ============================================================================
// BEAM OPTIMIZATION KERNEL
// ============================================================================

__kernel void traverseESVTBeam(
    __global const float* rays,     // Raw float buffer, 8 floats per ray
    __global const ESVTNode* nodes,
    __global const int* contours,
    __global float4* hitResults,
    __global float4* hitNormals,
    const uint raysPerBeam,
    const uint maxDepth)
    // Note: sceneMin/sceneMax removed - root tetrahedron uses SIMPLEX_STANDARD
{
    int beamId = get_global_id(0);
    int firstRay = beamId * raysPerBeam;

    // Compute beam frustum
    float3 frustumMin = (float3)(INFINITY);
    float3 frustumMax = (float3)(-INFINITY);

    for (uint i = 0; i < raysPerBeam; i++) {
        int rayIdx = firstRay + i;
        float3 rayO = getRayOriginFromBuffer(rays, rayIdx);
        float3 rayD = getRayDirectionFromBuffer(rays, rayIdx);
        float rayTmin = getRayTminFromBuffer(rays, rayIdx);
        float rayTmax = getRayTmaxFromBuffer(rays, rayIdx);
        float3 nearPoint = rayO + rayD * rayTmin;
        float3 farPoint = rayO + rayD * rayTmax;

        frustumMin = min(frustumMin, min(nearPoint, farPoint));
        frustumMax = max(frustumMax, max(nearPoint, farPoint));
    }

    // Process each ray in the beam
    for (uint i = 0; i < raysPerBeam; i++) {
        int rayIdx = firstRay + i;

        // Initialize results
        hitResults[rayIdx] = (float4)(0.0f, 0.0f, 0.0f, -1.0f);
        hitNormals[rayIdx] = (float4)(0.0f, 1.0f, 0.0f, 0.0f);

        // ... similar traversal logic as above ...
        // (Simplified for beam - would share stack processing across beam)
    }
}
