# F3.1 Phase 1: Kernel Architecture Analysis

**Stream A Deliverable**: Kernel Architecture Research & Design
**Status**: Phase 1 (Exploration)
**Date**: 2026-01-21
**Analysis Duration**: Days 1-4 (concurrent with 4 parallel streams)

---

## Overview

This document consolidates the architectural analysis of existing GPU kernels (ESVO, ESVT) and derives design patterns for the DAG ray traversal kernel (F3.1.1).

---

## I. Existing Kernel Patterns

### ESVO Ray Traversal Kernel (esvo_ray_traversal.cl)

**File Location**: `/Users/hal.hildebrand/git/Luciferase/render/src/main/resources/kernels/esvo_ray_traversal.cl`

**Key Characteristics**:
- **Ray Structure** (32 bytes, 8 floats):
  - `float3 origin` (12 bytes)
  - `float3 direction` (12 bytes)
  - `float tMin` (4 bytes)
  - `float tMax` (4 bytes)

- **Node Structure** (8 bytes):
  - `uint childDescriptor` (bits packed):
    - Bits 0-7: `leafMask` (8 children as potential leaves)
    - Bits 8-15: `childMask` (8 children exist/missing sparse indexing)
    - Bit 16: `farFlag` (indicates far node)
    - Bits 17-30: `childPtr` (14 bits = 16,384 max children)
    - Bit 31: `validFlag`
  - `uint contourData` (optional contour information)

- **Traversal Algorithm**: Stack-based depth-first
  - Stack depth: Configurable (typically 32 for 2^32 space)
  - Per iteration: Load node, compute children, push to stack
  - Termination: Stack empty OR leaf found

- **Addressing Mode**: RELATIVE (SVO)
  - Child index = `parentPtr + popcount(childMask & ((1<<octant)-1))`
  - Parent offset built into childPtr field
  - Efficient for cache locality

### ESVT Tetrahedral Kernel (esvt_ray_traversal.cl)

**File Location**: `/Users/hal.hildebrand/git/Luciferase/render/src/main/resources/kernels/esvt_ray_traversal.cl`

**Key Differences from ESVO**:
- **Node Structure**: Same 8-byte format (compatible)
- **Traversal Complexity**: Multi-level tetrahedralization (S0-S5 subdivision)
- **Stack Content**: Enhanced to store parent vertices + tetrahedron type
- **Ray-Primitive Intersection**: Tetrahedron-ray vs AABB-ray

**Lessons Learned**:
- macOS OpenCL workarounds documented:
  - ❌ AVOID: `fabs()` crashes on macOS
  - ✅ USE: `(value >= 0.0f) ? value : -value`
  - ❌ AVOID: Conditional equality comparisons
  - ✅ USE: Integer-based face index comparisons
  - ❌ AVOID: Struct returns from functions
  - ✅ USE: Inplace versions with pointer parameters

---

## II. DAG-Specific Adaptations

### Child Resolution Difference

**SVO (Relative Addressing)**:
```c
// In SVO kernel:
uint sparseIndex = popcount(childMask & ((1u << octant) - 1));
uint childIdx = parentPtr + sparseIndex;
```

**DAG (Absolute Addressing)**:
```c
// In DAG kernel (PROPOSED):
uint childIdx = childPtr + octant;  // Direct absolute index
```

**Implication**:
- DAG removes parent offset calculation
- Requires pre-computed `childPointers[]` array from Java
- Java maps: `[original childPtr, octant] → [DAG absolute address]`

### Memory Layout Optimization

**Current (SVO) Node Structure**:
```
Byte 0-3:   [valid|childptr(14)|far|childmask|leafmask] (32 bits)
Byte 4-7:   [contour_ptr(24)|contour_mask(8)]           (32 bits)
Total:      8 bytes per node
```

**DAG Node Structure**:
```
Byte 0-3:   [valid|childptr(14)|far|childmask|leafmask] (32 bits)  ← IDENTICAL
Byte 4-7:   [contour_ptr(24)|contour_mask(8)]           (32 bits)  ← IDENTICAL
Total:      8 bytes per node
```

**Key Insight**: DAG nodes use SAME format as SVO nodes!
- Only interpretation changes (absolute vs relative)
- GPU kernel can use existing bit extraction functions
- Binary format fully compatible

---

## III. AbstractOpenCLRenderer Lifecycle

**File**: `/Users/hal.hildebrand/git/Luciferase/render/src/main/java/.../sparse/gpu/AbstractOpenCLRenderer.java`

**Lifecycle Pattern**:
```
1. Constructor(width, height)
2. initialize()                     // Compile kernel, acquire OpenCL context
3. uploadData(spatialData)          // Upload to GPU
4. renderFrame(...)                 // Generate rays, execute kernel, read results
5. dispose() or close()             // Cleanup
```

**Key Methods to Override**:
- `getKernelSource()`: Return kernel source string
- `uploadDataBuffers(data)`: Upload spatial structure to GPU
- `setKernelArguments()`: Bind data-specific kernel args

**Performance Constants**:
```java
protected static final int LOCAL_WORK_SIZE = 64;        // Work group size
protected static final int RAY_SIZE_FLOATS = 8;          // 32 bytes per ray
protected static final int RESULT_SIZE_FLOATS = 4;       // 16 bytes per result
```

---

## IV. GPU Buffer Architecture

### Ray Buffers (Input)

**CPU → GPU Transfer**:
```
FloatBuffer cpuRayBuffer (CPU staging)
    ↓ malloc(rayCount * 8 floats)
    ↓ fill with: [ox, oy, oz, dx, dy, dz, tmin, tmax]
    ↓ flip()
    ↓
OpenCLBuffer rayBuffer (GPU device memory)
    ↓ CL_MEM_READ_ONLY
    ↓ copy via clEnqueueWriteBuffer()
```

**Alignment**: 64-byte cache-line alignment (via `memAlignedAlloc`)

### Result Buffers (Output)

**GPU → CPU Transfer**:
```
OpenCLBuffer resultBuffer (GPU device memory)
    ↓ CL_MEM_WRITE_ONLY
    ↓ copy via clEnqueueReadBuffer()
    ↓
FloatBuffer cpuResultBuffer (CPU staging)
    ↓ [hitX, hitY, hitZ, distance]
    ↓ process to image (RGBA)
```

---

## V. DAG Kernel Design Specification

### Data Structures

```opencl
typedef struct {
    uint childDescriptor;    // [valid|childptr|far|childmask|leafmask]
    uint contourData;
} DAGNode;

typedef struct {
    float3 origin;
    float3 direction;
    float tMin;
    float tMax;
} Ray;

typedef struct {
    int hit;                 // 0 = miss, 1 = hit
    float t;                 // Distance along ray
    float3 normal;           // Surface normal
    uint voxelValue;         // Voxel attributes
} IntersectionResult;
```

### Kernel Function Signature

```opencl
__kernel void rayTraverseDAG(
    __global const Ray* rays,              // Input: ray batch
    __global const DAGNode* dagNodes,      // Input: node pool (absolute addressed)
    __global const uint* childPointers,    // Input: DAG child pointer array
    const uint nodeCount,                  // Input: total nodes
    __global IntersectionResult* results   // Output: intersection results
)
```

### Core Algorithm

```opencl
// Key difference: absolute addressing
for (uint octant = 0; octant < 8; octant++) {
    if (hasChild(childMask, octant)) {
        // DAG: childIdx = childPtr + octant (NO parent offset)
        uint childIdx = childPtr + octant;
        if (childIdx < nodeCount) {
            stack[stackPtr++] = childIdx;
        }
    }
}
```

---

## VI. Implementation Dependencies

### Java-Side Requirements

1. **ESVONodeUnified** (Existing)
   - Bit extraction: `getChildMask()`, `getChildPtr()`, etc.
   - Already compatible with GPU format

2. **DAGOctreeData** (Existing from Phase 2)
   - Returns `PointerAddressingMode.ABSOLUTE`
   - Provides `nodes()` array

3. **DAGTraversalHelper** (CPU Reference)
   - CPU baseline for GPU/CPU parity testing
   - Absolute addressing child resolution

### GPU-Side Requirements

1. **OpenCL 1.2+**
   - Supported on: NVIDIA, AMD, Intel, Apple M-series
   - Core features: kernels, buffers, events, synchronization

2. **Local Memory**
   - Traversal stack: ~256 bytes (32 levels × 8 bytes)
   - Per work item: ~1KB total for registers + stack

3. **Memory Bandwidth**
   - Node fetch: 8 bytes per traversal step
   - Estimated: 24-32 bytes per ray (3-4 node accesses average)

---

## VII. Performance Targets

### DAG Kernel Speedup Goals

| Metric | Target | Rationale |
|--------|--------|-----------|
| 100K rays | <5ms | 10x speedup vs CPU |
| 1M rays | <20ms | 10-25x speedup vs CPU |
| 10M rays | <100ms | 25x speedup vs CPU |
| Memory bandwidth | >80% utilization | Efficient coalescing |
| Occupancy | >60% | Register pressure acceptable |

### Comparison Baselines

- **CPU DAG**: 1-2ms per 100K rays (13x speedup over SVO)
- **CPU SVO**: 13-26ms per 100K rays (baseline)
- **GPU DAG Target**: <5ms per 100K rays (50-100x over SVO)

---

## VIII. Risk Mitigation

| Risk | Probability | Mitigation |
|------|-------------|------------|
| Absolute addressing correctness | MEDIUM | GPU/CPU parity tests (95%+ threshold) |
| Platform compatibility | MEDIUM | Multi-vendor CI/CD matrix |
| Performance regression | LOW | Baseline profiling gates |
| Shared memory pressure | LOW | Stack can overflow to global (fallback) |
| macOS-specific issues | MEDIUM | Documented workarounds + testing |

---

## IX. Next Steps (Phase 2)

### Dependency Sequencing

1. **DAG Kernel Implementation** (Blocker for all Phase 2 work)
   - Adapt ESVO kernel to DAG semantics
   - Implement absolute addressing child resolution
   - Stack-based traversal (copy from ESVO)

2. **DAGOpenCLRenderer** (Depends on kernel)
   - Upload `childPointers[]` array to GPU
   - Set kernel arguments properly
   - Validate data flow

3. **GPU/CPU Parity Tests** (Depends on renderer)
   - Compare results on sample ray sets
   - Validate ≥95% parity
   - Debug any discrepancies

4. **Performance Profiling** (Parallel with Phase 2)
   - Baseline: CPU DAG vs GPU DAG
   - Identify optimization hotspots
   - Plan Phase 3 optimizations

---

## X. Files to Create

### Phase 2 (Kernel Development)

| File | Purpose | Dependencies |
|------|---------|--------------|
| `dag_ray_traversal.cl` | Kernel implementation | None (standalone) |
| `DAGOpenCLRenderer.java` | GPU renderer | Kernel loader |
| `DAGKernels.java` | Kernel resource loader | dag_ray_traversal.cl |

### Phase 1 (This Stream - TDD Tests)

| File | Purpose | Status |
|------|---------|--------|
| `DAGRayTraversalKernelTest.java` | Structure validation | ✓ Created |

---

## XI. Success Criteria

**Phase 1 Gate** (End of Week 1):
- [ ] 8 TDD tests written (RED state)
- [ ] All tests run without GPU (use mock)
- [ ] Architecture documentation complete
- [ ] Design choices validated with stakeholders

**Phase 2 Gate** (End of Week 2.5):
- [ ] Kernel compiles on all vendors (OpenCL 1.2+)
- [ ] GPU/CPU parity ≥95%
- [ ] Absolute addressing validated
- [ ] Performance baseline collected

---

**Document Author**: Stream A (Kernel Architecture Analysis)
**Reviewers**: GPU Team, Architecture Committee
**Last Updated**: 2026-01-21
