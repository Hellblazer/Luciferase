# Stream A: GPU Memory Optimization Plan

**F3.1 Phase 3 GPU Acceleration - Memory Optimization Stream**
**Status**: Ready for Implementation
**Date**: 2026-01-21
**Target**: 15-20% memory access improvement via shared memory and coalescing

---

## Executive Summary

This plan details the memory optimization strategy for the DAG ray traversal kernel (`dag_ray_traversal.cl`). Analysis identified the primary bottleneck as nodePool access patterns, with shared memory caching offering the highest impact optimization.

**Beads**:
- Luciferase-r150: Shared Memory Node Cache (P1, blocks all)
- Luciferase-1h4e: Stack Depth Reduction (P2, depends on r150)
- Luciferase-99s3: Benchmarking and Validation (P2, depends on 1h4e)

---

## I. Current Memory Analysis

### 1.1 Memory Access Pattern Summary

| Buffer | Size | Access Pattern | Coalescing | Bottleneck |
|--------|------|----------------|------------|------------|
| nodePool | 8 bytes/node | Random (DFS-driven) | Poor | **PRIMARY** |
| rayBuffer | 32 bytes/ray | Single read at start | Good | Minimal |
| resultBuffer | ~32 bytes/result | Single write at end | Good | Minimal |
| Stack (private) | 128 bytes | Register/private | N/A | Secondary |

### 1.2 Node Pool Access Analysis

**Current Behavior** (from `dag_ray_traversal.cl` lines 164-175):
```opencl
uint nodeIdx = stack[--stackPtr];
// ...
DAGNode node = nodePool[nodeIdx];  // 8-byte global memory read
```

**Access Characteristics**:
- Average 4-8 node accesses per ray
- Total bandwidth: 32-64 bytes per ray just for nodes
- Access pattern is stack-driven, effectively random
- No exploitation of work-group coherence

**Key Insight**: Adjacent pixels (work items 0-63 in a work group) typically traverse similar upper tree paths. At root level, 100% of rays visit the same node. At level 1, ~80% share nodes. This sharing decreases with depth but remains significant through level 3-4.

### 1.3 Ray Buffer Analysis

**Current Behavior** (line 246):
```opencl
Ray ray = rays[gid];  // 32-byte coalesced read
```

**Assessment**: Already optimal. Contiguous gid values result in coalesced 2KB reads per work group (64 x 32 bytes). No optimization needed.

### 1.4 Result Buffer Analysis

**Current Behavior** (line 252):
```opencl
results[gid] = result;  // Single coalesced write
```

**Assessment**: Already optimal. Single write at kernel end, fully coalesced.

---

## II. Optimization Strategy

### Priority-Ordered Optimizations

| Priority | Optimization | Expected Impact | Effort |
|----------|--------------|-----------------|--------|
| 1 | Shared Memory Node Cache | 15-20% latency reduction | 3 days |
| 2 | Stack Depth Reduction | 5-10% occupancy improvement | 1 day |
| 3 | Ray SoA Layout | 2-5% improvement | 2 days (optional) |

---

## III. Phase 1: Shared Memory Node Cache

**Bead**: Luciferase-r150
**Duration**: Days 1-3
**Target**: 15-20% memory bandwidth improvement

### 3.1 Design Specification

**Cache Structure**:
```opencl
#define NODE_CACHE_SIZE 1024    // 8KB shared memory (1024 x 8 bytes)
#define CACHE_HASH_SIZE 256     // Hash table for O(1) lookup

typedef struct {
    DAGNode node;               // Cached node data (8 bytes)
    uint globalIdx;             // Original global index (4 bytes)
    uint valid;                 // Cache entry valid flag (4 bytes)
} CacheEntry;                   // Total: 16 bytes per entry

__local CacheEntry nodeCache[NODE_CACHE_SIZE];
__local uint cacheHashTable[CACHE_HASH_SIZE];  // Hash → cache slot
__local atomic_int cacheCount;
```

**Memory Budget**:
- Node cache: 1024 x 16 bytes = 16 KB
- Hash table: 256 x 4 bytes = 1 KB
- Total: 17 KB (within 32KB-48KB local memory limit on most GPUs)

### 3.2 Cooperative Loading Algorithm

```opencl
/**
 * Cooperative node loading with shared memory cache
 *
 * @param nodeIdx Global node index to load
 * @return Cached or newly loaded node
 */
DAGNode loadNodeCached(
    __global const DAGNode* nodePool,
    __local CacheEntry* cache,
    __local uint* hashTable,
    __local atomic_int* cacheCount,
    uint nodeIdx
) {
    // Hash function: simple modulo for locality
    uint hashSlot = nodeIdx % CACHE_HASH_SIZE;

    // Check hash table for quick lookup
    uint cacheSlot = hashTable[hashSlot];
    if (cacheSlot < NODE_CACHE_SIZE) {
        CacheEntry entry = cache[cacheSlot];
        if (entry.valid && entry.globalIdx == nodeIdx) {
            return entry.node;  // Cache HIT
        }
    }

    // Cache MISS: Load from global memory
    DAGNode node = nodePool[nodeIdx];

    // Attempt to cache (non-blocking)
    int slot = atomic_fetch_add(cacheCount, 1);
    if (slot < NODE_CACHE_SIZE) {
        cache[slot].node = node;
        cache[slot].globalIdx = nodeIdx;
        cache[slot].valid = 1;

        // Update hash table (race is acceptable - any valid mapping works)
        hashTable[hashSlot] = slot;
    }

    return node;
}
```

### 3.3 Cache Initialization

```opencl
// At kernel start, before traversal loop
if (get_local_id(0) == 0) {
    atomic_store(&cacheCount, 0);
}
barrier(CLK_LOCAL_MEM_FENCE);

// Initialize hash table to invalid
for (int i = get_local_id(0); i < CACHE_HASH_SIZE; i += get_local_size(0)) {
    hashTable[i] = 0xFFFFFFFF;  // Invalid slot marker
}
barrier(CLK_LOCAL_MEM_FENCE);
```

### 3.4 Integration Points

**Modify traversal loop** (lines 158-217):
```opencl
// BEFORE:
DAGNode node = nodePool[nodeIdx];

// AFTER:
DAGNode node = loadNodeCached(nodePool, nodeCache, cacheHashTable,
                              &cacheCount, nodeIdx);
```

### 3.5 Profiling Hooks

Add optional cache statistics for development:
```opencl
#ifdef CACHE_PROFILING
__local atomic_int cacheHits;
__local atomic_int cacheMisses;

// In loadNodeCached:
if (/* cache hit */) {
    atomic_fetch_add(&cacheHits, 1);
} else {
    atomic_fetch_add(&cacheMisses, 1);
}

// At kernel end:
if (get_local_id(0) == 0) {
    // Store statistics to global profiling buffer
}
#endif
```

### 3.6 Expected Performance Model

| Tree Level | Sharing Rate | Cache Benefit |
|------------|--------------|---------------|
| 0 (root) | 100% | 63/64 reads saved |
| 1 | ~85% | ~54/64 reads saved |
| 2 | ~60% | ~38/64 reads saved |
| 3 | ~35% | ~22/64 reads saved |
| 4+ | <20% | Diminishing returns |

**Estimated Overall Improvement**: 40-60% reduction in global memory reads for upper levels, translating to 15-20% total latency improvement (nodePool access is ~40% of kernel time).

---

## IV. Phase 2: Stack Depth Reduction

**Bead**: Luciferase-1h4e
**Duration**: Day 4
**Target**: 5-10% occupancy improvement

### 4.1 Current Stack Analysis

```opencl
#define MAX_TRAVERSAL_DEPTH 32
uint stack[MAX_TRAVERSAL_DEPTH];  // 128 bytes private memory
```

**Register Pressure**:
- 32 x 4 bytes = 128 bytes = 32 vector registers
- Additional traversal state: ~20 registers
- Total: ~52 registers per work item

**Occupancy Impact**:
- GPU typically has 256 registers per SM
- At 52 registers/work item: max 4 concurrent work items
- Reducing to 20 registers/work item: max 12 concurrent work items

### 4.2 Proposed Reduction

```opencl
#define MAX_TRAVERSAL_DEPTH 16    // Reduced from 32
uint stack[MAX_TRAVERSAL_DEPTH];  // 64 bytes (saves 64 bytes)
```

**Justification**:
- DAG compression typically produces shallower trees
- 16 levels = 2^16 = 65,536 voxel resolution
- Most scenes complete traversal in <12 levels
- Deep scenes can gracefully terminate at limit

### 4.3 Overflow Handling

```opencl
// In traversal loop:
if (stackPtr >= MAX_TRAVERSAL_DEPTH) {
    // Graceful overflow: treat current node as leaf
    result.hit = 1;
    result.t = ray.tMin;
    result.iterations = iterations;
    break;
}
```

### 4.4 Validation Requirements

- Test with maximum-depth DAG (16+ levels)
- Verify no visual artifacts at depth boundary
- Compare GPU/CPU parity at boundary cases

---

## V. Phase 3: Benchmarking and Validation

**Bead**: Luciferase-99s3
**Duration**: Day 5
**Target**: Validate 15-20% improvement, >80% bandwidth utilization

### 5.1 Benchmark Suite

| Test Case | Ray Count | Purpose |
|-----------|-----------|---------|
| Simple (8 leaves) | 100K | Baseline correctness |
| Deep (16 levels) | 100K | Depth limit validation |
| Dense (1M nodes) | 1M | Memory bandwidth stress |
| Coherent (screen render) | 2M | Cache efficiency test |
| Random (Monte Carlo) | 1M | Worst-case cache |

### 5.2 Metrics to Collect

**Performance Metrics**:
- Total kernel execution time (ms)
- Average time per ray (ns)
- Memory bandwidth utilization (%)
- Cache hit rate (if profiling enabled)
- GPU occupancy (vendor profiler)

**Correctness Metrics**:
- GPU/CPU intersection parity (>99.9%)
- Hit/miss agreement
- Distance (t) accuracy (<0.001% error)

### 5.3 Profiling Tools

| Platform | Tool | Command |
|----------|------|---------|
| NVIDIA | nvprof/nsight | `nvprof --metrics all ./benchmark` |
| AMD | rocprof | `rocprof --hsa-trace ./benchmark` |
| Intel | vtune | `vtune -collect gpu-hotspots` |
| Apple | Instruments | Metal System Trace |

### 5.4 Success Criteria

| Metric | Target | Minimum |
|--------|--------|---------|
| Latency Improvement | 20% | 15% |
| Memory Bandwidth | 85% | 80% |
| GPU/CPU Parity | 100% | 99.9% |
| Cache Hit Rate | 60% | 40% |

---

## VI. Implementation Files

### 6.1 Files to Modify

| File | Changes |
|------|---------|
| `render/src/main/resources/kernels/dag_ray_traversal.cl` | Add cache, reduce stack |
| `render/src/test/java/.../DAGRayTraversalKernelTest.java` | Add cache validation tests |
| `render/src/test/java/.../DAGBenchmarkTest.java` | Add memory benchmark |

### 6.2 New Files

| File | Purpose |
|------|---------|
| `render/doc/STREAM_A_GPU_MEMORY_OPTIMIZATION_PLAN.md` | This plan (created) |
| `render/src/test/java/.../DAGMemoryBenchmarkTest.java` | Memory-focused benchmarks |

---

## VII. Detailed Task Breakdown for java-developer

### Task 1: Shared Memory Cache Implementation (Luciferase-r150)

**Context**:
- File: `/Users/hal.hildebrand/git/Luciferase/render/src/main/resources/kernels/dag_ray_traversal.cl`
- Memory Bank: Luciferase_active/stream-a-progress.md
- Search keywords: "OpenCL shared memory cache", "DAG traversal optimization"

**Prerequisites**:
- DAG kernel compiles and passes existing tests
- Baseline performance established

**Execution Instructions**:
1. Use sequential thinking to plan cache data structure layout
2. Write tests FIRST for cache correctness:
   - Test cache initialization (all slots invalid)
   - Test single-node caching (load, verify cached)
   - Test hash collision handling
   - Test cache overflow behavior
3. Implement CacheEntry structure and loadNodeCached function
4. Add cache initialization at kernel start
5. Replace nodePool[nodeIdx] with loadNodeCached() call
6. Ensure compilation including all tests
7. Run existing parity tests to verify no regression

**Parallelization Guidance**:
- This task is BLOCKING for subsequent tasks
- Do not parallelize implementation steps

**Continuation State**:
- Update render/doc/STREAM_A_GPU_MEMORY_OPTIMIZATION_PLAN.md with implementation notes
- Track: cache structure finalized, tests written, integration complete

**Validation**:
- All existing tests pass (100%)
- Cache hit rate >40% on coherent ray test
- No memory access violations

---

### Task 2: Stack Depth Reduction (Luciferase-1h4e)

**Context**:
- Depends on: Luciferase-r150 completion
- File: Same kernel file
- Search keywords: "OpenCL register pressure", "stack overflow handling"

**Prerequisites**:
- Cache implementation complete and validated
- Baseline occupancy measured

**Execution Instructions**:
1. Write tests FIRST:
   - Test 16-level deep traversal succeeds
   - Test 17-level traversal gracefully terminates
   - Test overflow flag is set correctly
2. Reduce MAX_TRAVERSAL_DEPTH from 32 to 16
3. Add overflow handling code
4. Run deep-scene tests
5. Measure occupancy improvement

**Validation**:
- Occupancy improved by >5%
- No visual artifacts on normal scenes
- Graceful degradation on very deep scenes

---

### Task 3: Benchmarking and Validation (Luciferase-99s3)

**Context**:
- Depends on: Luciferase-1h4e completion
- Test file: `/Users/hal.hildebrand/git/Luciferase/render/src/test/java/.../DAGBenchmarkTest.java`

**Prerequisites**:
- All optimizations implemented
- Test framework functional

**Execution Instructions**:
1. Write benchmark tests:
   - 100K ray baseline
   - 1M ray stress test
   - 2M coherent screen render
2. Run benchmarks with profiling enabled
3. Collect metrics: latency, bandwidth, cache hits
4. Compare against pre-optimization baseline
5. Document results in this plan

**Validation**:
- 15-20% latency improvement achieved
- >80% memory bandwidth utilization
- 100% GPU/CPU parity maintained

---

## VIII. Risk Mitigation

| Risk | Probability | Mitigation |
|------|-------------|------------|
| Cache overhead exceeds benefit | LOW | Start with smaller cache (512 nodes), tune |
| Hash collisions degrade performance | MEDIUM | Use linear probing or open addressing |
| macOS OpenCL issues | MEDIUM | Apply known workarounds (no fabs, no struct returns) |
| Stack reduction causes artifacts | LOW | Add runtime depth check, fallback to 32 |
| Vendor compatibility | MEDIUM | Test on NVIDIA, AMD, Intel, Apple |

---

## IX. Appendix: macOS OpenCL Workarounds

From ESVT kernel analysis, apply these patterns:

```opencl
// AVOID: fabs() crashes on macOS
float bad = fabs(value);

// USE: Arithmetic version
float good = (value >= 0.0f) ? value : -value;

// AVOID: Struct returns from functions
TetrahedronHit bad_func(...);

// USE: Inplace versions with pointers
void good_func(..., bool* hitOut, float* tEntryOut);

// AVOID: Conditional equality on floats
if (a == b) { ... }

// USE: Integer comparisons or epsilon
if (faceIdx == 0) { ... }
```

---

**Document Author**: Strategic Planner (Stream A Analysis)
**Reviewers**: GPU Team
**Last Updated**: 2026-01-21
