# F3.1 Stream B: Workgroup Tuning & GPU Occupancy Optimization Plan

**Stream**: B (Workgroup Tuning)
**Phase**: 3 (GPU Optimization)
**Status**: Planning Complete
**Date**: 2026-01-21
**Target**: 10-15% latency improvement via occupancy tuning

---

## Executive Summary

This plan outlines the strategy for optimizing DAG ray traversal GPU performance through workgroup size tuning and occupancy optimization. The current configuration (LOCAL_WORK_SIZE=64) achieves an estimated 50-60% occupancy. Our target is >70% occupancy with 10-15% latency reduction.

---

## I. Current Configuration Analysis

### Baseline Configuration

| Parameter | Current Value | Source |
|-----------|---------------|--------|
| LOCAL_WORK_SIZE | 64 | GPUConstants.java, AbstractOpenCLRenderer.java |
| MAX_TRAVERSAL_DEPTH | 32 | dag_ray_traversal.cl |
| Stack per workitem | 256 bytes | 32 levels * 8 bytes (uint + padding) |
| Local mem per workgroup | 16 KB | 64 * 256 bytes |
| RAY_SIZE_FLOATS | 8 (32 bytes) | AbstractOpenCLRenderer.java |
| RESULT_SIZE_FLOATS | 4 (16 bytes) | AbstractOpenCLRenderer.java |

### Resource Usage per Workitem

```
+-----------------------------------+
| Resource               | Bytes   |
+-----------------------------------+
| Traversal Stack        | 256     |
| Ray struct copy        | 32      |
| Result struct          | 40      |
| Local variables/regs   | ~100    |
| Total Private Memory   | ~430    |
+-----------------------------------+
```

### Current Kernel Structure

```opencl
// dag_ray_traversal.cl - Key memory consumers
#define MAX_TRAVERSAL_DEPTH 32          // Dominates local memory
uint stack[MAX_TRAVERSAL_DEPTH];         // 256 bytes per workitem

IntersectionResult traverseDAG(...) {
    IntersectionResult result;           // ~40 bytes
    uint stack[MAX_TRAVERSAL_DEPTH];     // 256 bytes
    float nodeSize = 1.0f;               // Various floats
    float3 nodeMin = (float3)(0.0f);     // ...
    // ...
}
```

---

## II. Occupancy Analysis

### Theoretical Occupancy by Workgroup Size

| Size | Local Mem | Warps/WF | Expected Occupancy | Notes |
|------|-----------|----------|-------------------|-------|
| 32   | 8 KB      | 1/0.5    | 70-80%            | Underutilizes execution units |
| 64   | 16 KB     | 2/1      | 50-60%            | Current, balanced |
| 128  | 32 KB     | 4/2      | 40-50%            | Near local mem limit |
| 256  | 64 KB     | 8/4      | INFEASIBLE        | Exceeds typical local mem |

### GPU Architecture Constraints

**Apple M-Series (M1/M2/M3/M4)**:
- Local memory: 32 KB per SIMD group
- Max workgroup: 1024
- Unified memory (lower transfer overhead)
- Recommendation: SIZE=64 or 128 with reduced stack

**NVIDIA (CUDA Compute 7.0+)**:
- Shared memory: 48-96 KB per SM
- Warp size: 32
- Max workgroup: 1024
- Recommendation: SIZE=64 (2 warps) or 128 (4 warps)

**AMD RDNA/RDNA2/RDNA3**:
- LDS: 32-64 KB per CU
- Wavefront: 64 (RDNA) or 32 (RDNA3)
- Max workgroup: 1024
- Recommendation: SIZE=64

**Intel (Gen 9+)**:
- Local memory: varies (16-64 KB)
- EU-based execution
- Recommendation: SIZE=32-64 (conservative)

---

## III. Optimization Strategies

### Strategy 1: Configurable Stack Depth (Quick Win)

**Rationale**: Most practical scenes require only 8-16 levels of traversal.

**Implementation**:
```opencl
// dag_ray_traversal.cl
#ifndef MAX_TRAVERSAL_DEPTH
#define MAX_TRAVERSAL_DEPTH 16    // Reduced from 32
#endif

uint stack[MAX_TRAVERSAL_DEPTH];  // Now 64 bytes vs 128 bytes
```

**Memory Impact**:
| Depth | Stack Size | Savings | Voxel Space Coverage |
|-------|------------|---------|---------------------|
| 8     | 32 bytes   | 87%     | 256^3               |
| 12    | 48 bytes   | 81%     | 4096^3              |
| 16    | 64 bytes   | 75%     | 65536^3             |
| 24    | 96 bytes   | 63%     | 16M^3               |
| 32    | 128 bytes  | 0%      | 4B^3 (baseline)     |

**Recommendation**: Default to 16 (covers 65K^3 voxels, sufficient for most scenes).

### Strategy 2: Compressed Stack Entries

**Rationale**: For DAGs with <65K nodes, use 16-bit indices.

**Implementation**:
```opencl
#if DAG_NODE_COUNT < 65536
    ushort stack[MAX_TRAVERSAL_DEPTH];  // 2 bytes per entry
#else
    uint stack[MAX_TRAVERSAL_DEPTH];    // 4 bytes per entry
#endif
```

**Memory Impact**: 50% reduction for qualifying DAGs.

### Strategy 3: Partial Traversal Restart (Advanced)

**Rationale**: Store only current position, re-traverse on backtrack.

**Data Structure**:
```opencl
typedef struct {
    uint currentNode;              // 4 bytes
    uchar currentDepth;            // 1 byte
    uchar octantPath[MAX_DEPTH/2]; // 12 bytes (2 octants per byte)
} CompactTraversalState;           // Total: ~20 bytes vs 256 bytes
```

**Trade-off**: May increase average traversal time by 5-10% due to re-traversal.

**Use Case**: When maximizing occupancy is critical (very large workgroups).

---

## IV. Implementation Plan

### Phase 1: Parameterization (Day 1)

**Task 1.1**: Modify GPUConstants.java
```java
public final class GPUConstants {
    // Configurable workgroup sizes
    public static final int[] WORKGROUP_SIZE_OPTIONS = {32, 64, 128};
    public static final int DEFAULT_LOCAL_WORK_SIZE = 64;

    // Configurable stack depths
    public static final int[] STACK_DEPTH_OPTIONS = {8, 12, 16, 24, 32};
    public static final int DEFAULT_STACK_DEPTH = 16;
}
```

**Task 1.2**: Modify dag_ray_traversal.cl
```opencl
// Make MAX_TRAVERSAL_DEPTH a compile-time parameter
#ifndef MAX_TRAVERSAL_DEPTH
#define MAX_TRAVERSAL_DEPTH 16
#endif
```

**Task 1.3**: Modify kernel compilation in DAGOpenCLRenderer
```java
protected String getKernelSource() {
    var source = DAGKernels.getOpenCLKernel();
    // Inject configuration
    return "#define MAX_TRAVERSAL_DEPTH " + stackDepth + "\n" + source;
}
```

### Phase 2: GPU Capabilities Detection (Day 1-2)

**New File**: `GPUCapabilities.java`

```java
package com.hellblazer.luciferase.sparse.gpu;

public record GPUCapabilities(
    String vendorName,
    String deviceName,
    long maxWorkGroupSize,
    long localMemorySize,
    int maxComputeUnits,
    long globalMemorySize
) {
    public static GPUCapabilities query(long device) {
        // Query CL_DEVICE_* properties
        // Return capabilities record
    }

    public int recommendedWorkgroupSize() {
        // Vendor-specific heuristics
        if (vendorName.contains("Apple")) {
            return localMemorySize >= 32768 ? 128 : 64;
        } else if (vendorName.contains("NVIDIA")) {
            return 64; // 2 warps
        } else if (vendorName.contains("AMD")) {
            return 64; // 1 wavefront
        }
        return 64; // Safe default
    }

    public int recommendedStackDepth() {
        // Based on typical scene requirements
        return 16; // Covers 65K^3 voxels
    }
}
```

### Phase 3: Occupancy Calculator (Day 2)

**New File**: `OccupancyCalculator.java`

```java
package com.hellblazer.luciferase.sparse.gpu;

public class OccupancyCalculator {

    public record OccupancyResult(
        double theoretical,
        double localMemLimited,
        double workgroupLimited,
        String limitingFactor
    ) {}

    public static OccupancyResult calculate(
        GPUCapabilities gpu,
        int workgroupSize,
        int localMemPerWorkitem
    ) {
        // Theoretical max workgroups per CU
        double maxWorkgroups = gpu.maxComputeUnits() * 4.0; // Typical CU capacity

        // Local memory limited
        long totalLocalMem = workgroupSize * localMemPerWorkitem;
        double localMemOccupancy = (double) gpu.localMemorySize() / totalLocalMem;

        // Workgroup size limited
        double workgroupOccupancy = (double) workgroupSize / gpu.maxWorkGroupSize();

        double theoretical = Math.min(localMemOccupancy, 1.0)
                           * Math.min(workgroupOccupancy, 1.0);

        String limiter = localMemOccupancy < workgroupOccupancy
                       ? "LOCAL_MEMORY" : "WORKGROUP_SIZE";

        return new OccupancyResult(
            theoretical, localMemOccupancy, workgroupOccupancy, limiter
        );
    }
}
```

### Phase 4: Benchmark Harness (Day 2-3)

**New File**: `WorkgroupTuningBenchmark.java`

```java
package com.hellblazer.luciferase.esvo.performance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "RUN_GPU_TESTS", matches = "true")
public class WorkgroupTuningBenchmark {

    private static final int[] WORKGROUP_SIZES = {32, 64, 128};
    private static final int[] STACK_DEPTHS = {16, 24, 32};
    private static final int RAY_COUNT = 1_000_000;
    private static final int WARMUP_ITERATIONS = 5;
    private static final int BENCHMARK_ITERATIONS = 20;

    @Test
    void benchmarkWorkgroupConfigurations() {
        var results = new ArrayList<BenchmarkResult>();

        for (int wgSize : WORKGROUP_SIZES) {
            for (int stackDepth : STACK_DEPTHS) {
                var result = runBenchmark(wgSize, stackDepth);
                results.add(result);
                printResult(result);
            }
        }

        // Find optimal configuration
        var optimal = results.stream()
            .min(Comparator.comparingDouble(BenchmarkResult::avgLatencyMs))
            .orElseThrow();

        System.out.printf("\nOPTIMAL: workgroup=%d, depth=%d, latency=%.2fms%n",
            optimal.workgroupSize(), optimal.stackDepth(), optimal.avgLatencyMs());
    }

    private BenchmarkResult runBenchmark(int workgroupSize, int stackDepth) {
        // Create renderer with specific configuration
        // Run warmup iterations
        // Measure benchmark iterations
        // Return statistics
    }

    record BenchmarkResult(
        int workgroupSize,
        int stackDepth,
        double avgLatencyMs,
        double p99LatencyMs,
        double throughputRaysPerSec,
        double estimatedOccupancy
    ) {}
}
```

### Phase 5: Auto-Tuner (Day 3-4)

**New File**: `WorkgroupAutoTuner.java`

```java
package com.hellblazer.luciferase.sparse.gpu;

public class WorkgroupAutoTuner {

    private static final Path CACHE_FILE = Path.of(
        System.getProperty("user.home"),
        ".luciferase",
        "gpu_tuning_cache.json"
    );

    public record TuningResult(
        int optimalWorkgroupSize,
        int optimalStackDepth,
        double expectedLatencyMs,
        String deviceId
    ) {}

    public static TuningResult getTuningResult(GPUCapabilities gpu) {
        // Check cache first
        var cached = loadFromCache(gpu.deviceName());
        if (cached != null) {
            return cached;
        }

        // Use heuristics as fallback
        return new TuningResult(
            gpu.recommendedWorkgroupSize(),
            gpu.recommendedStackDepth(),
            -1.0, // Unknown
            gpu.deviceName()
        );
    }

    public static TuningResult runAutoTune(GPUCapabilities gpu) {
        // Run quick benchmark of key configurations
        // Select best result
        // Cache for future use
        // Return optimal configuration
    }
}
```

---

## V. Kernel Modifications

### Modified dag_ray_traversal.cl

```opencl
/**
 * F3.1: DAG Ray Traversal Kernel (Optimized)
 *
 * Configurable parameters:
 * - MAX_TRAVERSAL_DEPTH: Stack depth (default 16)
 * - USE_COMPACT_STACK: Enable 16-bit indices if DAG < 65K nodes
 */

// Configuration (injected at compile time)
#ifndef MAX_TRAVERSAL_DEPTH
#define MAX_TRAVERSAL_DEPTH 16
#endif

#ifndef USE_COMPACT_STACK
#define USE_COMPACT_STACK 0
#endif

// Stack type selection
#if USE_COMPACT_STACK
    typedef ushort StackEntry;
    #define MAX_NODE_INDEX 65535
#else
    typedef uint StackEntry;
    #define MAX_NODE_INDEX 0xFFFFFFFF
#endif

// ... rest of kernel with StackEntry type used for stack array
```

---

## VI. Success Criteria

### Primary Metrics

| Metric | Current | Target | Improvement |
|--------|---------|--------|-------------|
| GPU Occupancy | ~50-60% | >70% | +10-20% |
| 1M Ray Latency | TBD ms | TBD-15% | 10-15% |
| Cross-Vendor Support | 1 (Apple) | 3 (Apple, NVIDIA, AMD) | Universal |

### Validation Tests

1. **Occupancy Validation**
   - Use vendor profiling tools where available
   - Compare theoretical vs measured occupancy
   - Target: Measured within 10% of theoretical

2. **Latency Validation**
   - Baseline: Current configuration (64/32)
   - Test: Each configuration combination
   - Threshold: At least one config shows >10% improvement

3. **Correctness Validation**
   - GPU/CPU parity: >99% match on ray results
   - No correctness regression from tuning

4. **Stability Validation**
   - P99 latency within 2x of average
   - No crashes or hangs across 1000 iterations

---

## VII. Risk Mitigation

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| Register spilling at 128 workgroup | MEDIUM | Perf regression | Fall back to 64 |
| Stack overflow with depth 16 | LOW | Incorrect results | Validate scene depth requirements |
| Vendor-specific failures | MEDIUM | CI breaks | Per-vendor test matrix |
| Auto-tuner instability | LOW | Inconsistent perf | Use cached results, heuristic fallback |
| Memory alignment issues | LOW | Crashes | Use 16-byte aligned structures |

### Fallback Strategy

If all optimizations fail or cause regressions:
1. Revert to baseline (LOCAL_WORK_SIZE=64, MAX_DEPTH=32)
2. Log warning recommending manual tuning
3. Provide documentation for advanced users
4. Continue with default configuration (proven stable)

---

## VIII. File Inventory

### New Files to Create

| File | Package | Purpose |
|------|---------|---------|
| GPUCapabilities.java | sparse.gpu | Device capability query |
| OccupancyCalculator.java | sparse.gpu | Theoretical occupancy computation |
| WorkgroupConfiguration.java | sparse.gpu | Configuration data class |
| WorkgroupAutoTuner.java | sparse.gpu | Auto-tuning orchestrator |
| WorkgroupTuningBenchmark.java | esvo.performance | Benchmark test harness |

### Files to Modify

| File | Changes |
|------|---------|
| GPUConstants.java | Add configurable options |
| dag_ray_traversal.cl | Parameterize MAX_DEPTH, add compact stack option |
| DAGOpenCLRenderer.java | Accept configuration, inject compile-time defines |
| AbstractOpenCLRenderer.java | Accept workgroup size parameter |

---

## IX. Timeline

| Day | Tasks | Deliverables |
|-----|-------|--------------|
| 1 | Parameterization, kernel modification | Modified kernel, GPUConstants |
| 2 | GPU capabilities, occupancy calculator | GPUCapabilities.java, OccupancyCalculator.java |
| 3 | Benchmark harness implementation | WorkgroupTuningBenchmark.java |
| 4 | Auto-tuner, integration testing | WorkgroupAutoTuner.java, validation tests |
| 5 | Documentation, code review | WORKGROUP_TUNING_GUIDE.md, PR ready |

---

## X. Dependencies

### Upstream
- Phase 2 baseline kernel completion (DONE)
- DAGOpenCLRenderer working (DONE)

### Downstream
- Phase 3 Stream C (Cache Optimization) may benefit from occupancy improvements
- Phase 4 (Production) requires tuning stability

### Parallel
- Stream A (Memory Layout) - independent, can proceed concurrently
- Stream C (Cache Lines) - benefits from this work

---

## XI. Appendix: OpenCL Device Query Reference

```java
// Key device queries for GPUCapabilities
clGetDeviceInfo(device, CL_DEVICE_MAX_WORK_GROUP_SIZE, ...);     // Max threads per workgroup
clGetDeviceInfo(device, CL_DEVICE_LOCAL_MEM_SIZE, ...);          // Shared/local memory
clGetDeviceInfo(device, CL_DEVICE_MAX_COMPUTE_UNITS, ...);       // Compute units count
clGetDeviceInfo(device, CL_DEVICE_GLOBAL_MEM_SIZE, ...);         // Total VRAM
clGetDeviceInfo(device, CL_DEVICE_NAME, ...);                    // Device name string
clGetDeviceInfo(device, CL_DEVICE_VENDOR, ...);                  // Vendor string
```

---

**Document Author**: Stream B (Workgroup Tuning)
**Reviewers**: GPU Team, Performance Engineering
**Last Updated**: 2026-01-21
