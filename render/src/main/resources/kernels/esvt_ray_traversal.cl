// ESVT Ray Traversal Kernel - OpenCL Implementation
// Tetrahedral adaptation of ESVO for macOS GPU support
// Based on raycast_esvt.comp (GLSL version)

// ============================================================================
// CONSTANTS
// ============================================================================

#define CAST_STACK_DEPTH 22
#define MAX_RAYCAST_ITERATIONS 10000
// Vendor-configurable epsilon: Intel GPUs need relaxed precision (1e-5f)
// VendorKernelConfig prepends #define RAY_EPSILON 1e-5f for Intel
#ifdef RAY_EPSILON
#define EPSILON RAY_EPSILON
#else
#define EPSILON 1e-7f  // Default for NVIDIA/AMD/Apple (tighter for tetrahedra)
#endif
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
    int siblingPos;  // Next child index to try when we pop back to this level
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

// INDEX_TO_BEY_NUMBER[6][8] - converts Morton index to Bey child ID
// ESVT tree stores children in Morton order, but Bey subdivision uses Bey order
// [parentType * 8 + mortonIdx] -> beyIdx
constant int INDEX_TO_BEY_NUMBER[48] = {
    0, 1, 4, 5, 2, 7, 6, 3,  // Parent type 0
    0, 1, 5, 4, 7, 2, 6, 3,  // Parent type 1
    0, 4, 5, 1, 2, 7, 6, 3,  // Parent type 2
    0, 1, 5, 4, 6, 7, 2, 3,  // Parent type 3
    0, 4, 5, 1, 6, 2, 7, 3,  // Parent type 4
    0, 5, 4, 1, 6, 7, 2, 3   // Parent type 5
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
// RAY-AABB (UNIT CUBE) INTERSECTION
// ============================================================================
//
// This is used for root-level intersection because the Tetree uses CUBIC octant
// subdivision internally, not geometric Bey tetrahedron subdivision. The root
// "tetrahedron type" in the tree describes orientation for surface normals,
// but the spatial subdivision covers the FULL [0,1]^3 cube, not just 1/6 of it.

typedef struct {
    bool hit;
    float tEntry;
    float tExit;
    int entryFace;  // 0=+X, 1=-X, 2=+Y, 3=-Y, 4=+Z, 5=-Z
} AABBHit;

// Ray-AABB intersection for unit cube [0,1]^3
// Uses slab method with proper handling of direction signs
void intersectUnitCubeInplace(float3 rayOrigin, float3 rayDir,
                               bool* hitOut, float* tEntryOut, float* tExitOut,
                               int* entryFaceOut) {
    // Compute inverse direction (rayDir already has EPSILON minimum from caller)
    float3 invDir = (float3)(1.0f / rayDir.x, 1.0f / rayDir.y, 1.0f / rayDir.z);

    // Compute t values for each slab
    float tx1 = (0.0f - rayOrigin.x) * invDir.x;
    float tx2 = (1.0f - rayOrigin.x) * invDir.x;
    float ty1 = (0.0f - rayOrigin.y) * invDir.y;
    float ty2 = (1.0f - rayOrigin.y) * invDir.y;
    float tz1 = (0.0f - rayOrigin.z) * invDir.z;
    float tz2 = (1.0f - rayOrigin.z) * invDir.z;

    // Find entry/exit for each axis (avoid conditional swap - use min/max)
    float txMin = (tx1 < tx2) ? tx1 : tx2;
    float txMax = (tx1 < tx2) ? tx2 : tx1;
    float tyMin = (ty1 < ty2) ? ty1 : ty2;
    float tyMax = (ty1 < ty2) ? ty2 : ty1;
    float tzMin = (tz1 < tz2) ? tz1 : tz2;
    float tzMax = (tz1 < tz2) ? tz2 : tz1;

    // Find overall entry/exit and track entry face
    float tEntry = txMin;
    int entryFace = (rayDir.x >= 0.0f) ? 1 : 0;  // -X or +X face

    if (tyMin > tEntry) {
        tEntry = tyMin;
        entryFace = (rayDir.y >= 0.0f) ? 3 : 2;  // -Y or +Y face
    }
    if (tzMin > tEntry) {
        tEntry = tzMin;
        entryFace = (rayDir.z >= 0.0f) ? 5 : 4;  // -Z or +Z face
    }

    float tExit = txMax;
    if (tyMax < tExit) tExit = tyMax;
    if (tzMax < tExit) tExit = tzMax;

    // Check for valid intersection
    bool hit = (tEntry <= tExit) && (tExit > 0.0f);

    // If ray starts inside cube, entry is at origin
    if (hit && tEntry < 0.0f) {
        tEntry = 0.0f;
        // Determine which face is closest for entry direction
        // (simplified - octant-based would be more precise)
        if (rayOrigin.x < 0.5f) entryFace = (rayDir.x >= 0.0f) ? 1 : 0;
        else entryFace = (rayDir.x >= 0.0f) ? 0 : 1;
    }

    *hitOut = hit;
    *tEntryOut = tEntry;
    *tExitOut = tExit;
    *entryFaceOut = entryFace;
}

// Compute which of the 6 S0-S5 tetrahedra contains a point in [0,1]^3
// This determines the root type for traversal when entering the cube
int computeTetTypeAtPoint(float3 p) {
    // The 6 tetrahedra partition the cube. Each point is in exactly one.
    // We use the same logic as Tetree's locatePointS0Tree but simplified for unit cube.
    //
    // Key: All 6 types share vertices c0=(0,0,0) and c7=(1,1,1).
    // The type is determined by which octant region the point is in relative to
    // the body diagonal from c0 to c7.
    //
    // For simplicity, we map the point to an octant and use a lookup:
    int octant = 0;
    if (p.x >= 0.5f) octant |= 1;
    if (p.y >= 0.5f) octant |= 2;
    if (p.z >= 0.5f) octant |= 4;

    // Map octant to dominant tetrahedron type (simplified - each octant touches multiple types)
    // This is an approximation; the exact type depends on fine position within octant.
    // For traversal, starting with any type that covers the entry point is sufficient.
    // Types 0-5 cover: 0=(c0,c1,c5,c7), 1=(c0,c7,c3,c1), 2=(c0,c2,c3,c7),
    //                  3=(c0,c7,c6,c2), 4=(c0,c4,c6,c7), 5=(c0,c7,c5,c4)
    //
    // Actually, for CORRECT traversal we need to start from type 0 (S0) always,
    // as that's how the tree is rooted. The tree uses CUBIC subdivision internally
    // with type propagation - we don't need to find which geometric tet contains the point.
    return 0;  // Always start from S0 root - the tree handles type propagation
}

// ============================================================================
// CHILD VERTICES (BEY SUBDIVISION)
// ============================================================================

// Computes child tetrahedron vertices using Bey 8-way subdivision.
// CRITICAL: Takes ACTUAL parent vertices, not canonical SIMPLEX_STANDARD positions.
// This enables correct multi-level traversal where parent positions vary.
// In Bey subdivision, children fill the parent completely using edge midpoints.
//
// IMPORTANT: mortonIdx is the child index in MORTON order (as stored in ESVT tree).
// We must convert it to BEY order using INDEX_TO_BEY_NUMBER[parentType] to get
// the correct geometric position.
void getChildVerticesFromParent(float3 pv0, float3 pv1, float3 pv2, float3 pv3,
                                int mortonIdx, int parentType,
                                float3* cv0, float3* cv1, float3* cv2, float3* cv3) {
    // Convert Morton index to Bey index
    int beyIdx = INDEX_TO_BEY_NUMBER[parentType * 8 + mortonIdx];

    // Edge midpoints of the ACTUAL parent tetrahedron
    float3 m01 = (pv0 + pv1) * 0.5f;
    float3 m02 = (pv0 + pv2) * 0.5f;
    float3 m03 = (pv0 + pv3) * 0.5f;
    float3 m12 = (pv1 + pv2) * 0.5f;
    float3 m13 = (pv1 + pv3) * 0.5f;
    float3 m23 = (pv2 + pv3) * 0.5f;

    // Corner children (Bey 0-3): one parent vertex + three edge midpoints from that vertex
    // Interior children (Bey 4-7): four edge midpoints (octahedral region subdivided)
    switch (beyIdx) {
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
    getChildVerticesFromParent(pv0, pv1, pv2, pv3, childIdx, parentType, cv0, cv1, cv2, cv3);
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
        stack[i].siblingPos = 0;
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

    // Get root tetrahedron vertices from canonical positions (for Bey child subdivision)
    // NOTE: These are used for CHILD vertex computation, NOT for root bounds testing.
    // The Tetree uses CUBIC subdivision internally, so root bounds are the full [0,1]^3 cube.
    float3 pv0 = SIMPLEX_STANDARD[rootType * 4 + 0];
    float3 pv1 = SIMPLEX_STANDARD[rootType * 4 + 1];
    float3 pv2 = SIMPLEX_STANDARD[rootType * 4 + 2];
    float3 pv3 = SIMPLEX_STANDARD[rootType * 4 + 3];

    // =========================================================================
    // ROOT INTERSECTION: Use UNIT CUBE, not tetrahedron!
    // =========================================================================
    // The Tetree uses CUBIC octant subdivision internally. The root node
    // represents the FULL [0,1]^3 coordinate space, not just 1/6th of it.
    // The "tetrahedron type" describes orientation for Bey subdivision,
    // but spatial coverage is cubic.

    // Test ray-root intersection using CUBE bounds [0,1]^3
    float rootHit_tEntry = 1e30f;
    float rootHit_tExit = -1e30f;
    int rootHit_entryFace = -1;
    bool rootCubeHit = false;

    intersectUnitCubeInplace(rayOrigin, rayDir,
                              &rootCubeHit, &rootHit_tEntry, &rootHit_tExit,
                              &rootHit_entryFace);

    // Map cube entry face (0-5: +X,-X,+Y,-Y,+Z,-Z) to tetrahedral entry face (0-3)
    // For the S0 tree, we use entry face 0 (opposite v0) as default for cube entries
    // The actual child ordering will be determined by ray direction, not entry face
    int tetEntryFace = 0;  // Start with face 0, traversal will check all children

    // If cube was hit, proceed with traversal
    if (rootCubeHit) {
        // Initialize traversal state
        // CRITICAL: Track actual parent vertices for correct multi-level traversal
        uint parentIdx = 0u;
        int parentType = rootType;
        int entryFace = tetEntryFace;  // Use tet entry face, not cube face
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
        float bestHitScale = (float)CAST_STACK_DEPTH;  // Track depth of best hit

        // DEBUG: track which children are hit at root (bitmask)
        int debugChildrenChecked = 0;
        int debugChildrenHit = 0;
        int debugFirstHitChildIdx = -1;

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
                siblingPos = stack[scale].siblingPos;  // Resume from saved position
                pv0 = stack[scale].v0;
                pv1 = stack[scale].v1;
                pv2 = stack[scale].v2;
                pv3 = stack[scale].v3;
                scaleExp2 *= 2.0f;
                continue;
            }

            // Try each child starting from siblingPos (for resumption after pop)
            // Bey subdivision has 8 children that completely fill the parent
            bool descended = false;
            for (int childIdx = siblingPos; childIdx < 8; childIdx++) {

                if (!hasChild(node, childIdx)) {
                    continue;
                }

                // DEBUG: count at first iteration only (root level)
                if (iter == 1) debugChildrenChecked++;

                // Get child vertices from ACTUAL parent vertices (not SIMPLEX_STANDARD)
                // childIdx is in Morton order; getChildVerticesFromParent converts to Bey order
                float3 cv0, cv1, cv2, cv3;
                getChildVerticesFromParent(pv0, pv1, pv2, pv3, childIdx, parentType, &cv0, &cv1, &cv2, &cv3);

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

                // DEBUG: count hits at root level and record first hit child
                if (iter == 1) {
                    debugChildrenHit++;
                    if (debugFirstHitChildIdx < 0) debugFirstHitChildIdx = childIdx;
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
                        bestHitScale = (float)scale;  // Record depth
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
                stack[scale].siblingPos = childIdx + 1;  // Resume from next sibling when we pop back
                stack[scale].v0 = pv0;
                stack[scale].v1 = pv1;
                stack[scale].v2 = pv2;
                stack[scale].v3 = pv3;

                // Update state for child - child vertices become new parent vertices
                parentIdx = getChildIndex(nodes, node, childIdx, parentIdx);
                // childIdx is Morton index; convert to Bey index for type lookup
                int beyIdx = INDEX_TO_BEY_NUMBER[parentType * 8 + childIdx];
                parentType = PARENT_TYPE_TO_CHILD_TYPE[parentType * 8 + beyIdx];
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
                siblingPos = stack[scale].siblingPos;  // Resume from saved position
                pv0 = stack[scale].v0;
                pv1 = stack[scale].v1;
                pv2 = stack[scale].v2;
                pv3 = stack[scale].v3;
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
        // DEBUG: Output depth as color gradient
        // depth 0 = red, depth 3 = yellow, depth 6 = green, depth 9 = cyan
        float hitDepth = (float)(CAST_STACK_DEPTH - 1) - bestHitScale;
        float normalizedDepth = hitDepth / 10.0f;  // 0-1 for depths 0-10
        float4 leafNormal = (float4)(
            1.0f - normalizedDepth,     // R decreases with depth
            normalizedDepth,             // G increases with depth
            0.3f,                        // B constant to identify leaf hits
            1.0f
        );

        float4 rootOnlyResult = (float4)(0.0f, 0.5f, 0.0f, tMinLocal);
        // DEBUG: show which child was hit
        // R = first hit child index / 8 (0-7 maps to 0-0.875)
        // G = number of hits / 8
        // B = 0.5 to identify root-only
        float4 rootOnlyNormal = (float4)(
            (debugFirstHitChildIdx >= 0) ? ((float)debugFirstHitChildIdx + 1.0f) / 8.0f : 0.0f,
            (float)debugChildrenHit / 8.0f,
            0.5f,
            1.0f
        );

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
