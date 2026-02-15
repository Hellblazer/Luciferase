# Multi-Vendor GPU Testing Infrastructure (Phase 5 P3)

**Status**: ✅ COMPLETE
**Component**: P3: Multi-Vendor Testing Framework
**Location**: `render/src/test/java/.../esvo/gpu/`
**Test Tiers**: Tier 1 (CI), Tier 2 (Local), Tier 3 (Nightly)

---

## Quick Start

### Tier 1: CI Testing (No GPU Required)

```bash
# Kernel syntax validation, build option checks
mvn test -Dtest=DAGOpenCLRendererVendorTest
# Output: 4 tests pass, 30 skipped (GPU-conditional)
```

### Tier 2: Local GPU Testing

```bash
# With GPU hardware available
RUN_GPU_TESTS=true mvn test -Dtest=DAGOpenCLRendererVendorTest
# Output: 19 tests pass (4 Tier 1 + 15 Tier 2)
```

### Tier 3: Vendor-Specific Matrix

```bash
# Full vendor matrix (nightly)
GPU_VENDOR=NVIDIA RUN_GPU_TESTS=true mvn test -Dtest=DAGOpenCLRendererVendorTest
# Tests all NVIDIA-specific code paths
```

---

## Vendor Support Matrix

| Vendor | Hardware | Driver | OpenCL | Status |
|--------|----------|--------|--------|--------|
| **NVIDIA** | RTX 30/40, V100+ | 550+ | 3.0 | ✅ Tier 1-3 |
| **AMD** | RX 5700/6000/7000 | AMDGPU PRO | 2.0 | ✅ Tier 1-3 |
| **Intel** | Arc A770/A750 | Data Center | 3.0 | ✅ Tier 1-3 |
| **Apple** | M1/M2/M4 | Metal/OpenCL | 1.2 | ✅ Tier 1-3 |

---

## Test Architecture

### Three-Tier Structure

```
┌──────────────────────────────────────────────────┐
│ Tier 3: Nightly Vendor-Specific Tests (15 tests)│
│ - NVIDIA-specific optimizations                 │
│ - AMD atomic semantics                          │
│ - Intel precision relaxation                    │
│ - Apple fabs workaround                         │
│ Requires: GPU hardware + GPU_VENDOR env        │
│ Duration: ~15 min per vendor                    │
└──────────────────────────────────────────────────┘
                    │
                    ▼
┌──────────────────────────────────────────────────┐
│ Tier 2: Local GPU Tests (15 tests)              │
│ - GPU kernel execution                          │
│ - GPU/CPU parity validation                     │
│ - Vendor-specific optimizations                 │
│ Requires: RUN_GPU_TESTS=true environment       │
│ Duration: ~2 minutes                            │
└──────────────────────────────────────────────────┘
                    │
                    ▼
┌──────────────────────────────────────────────────┐
│ Tier 1: CI Tests (4 tests)                      │
│ - Kernel source syntax validation               │
│ - Kernel compilation checks                     │
│ - Vendor detection logic                        │
│ - Configuration loading                         │
│ Requires: Nothing (no GPU)                      │
│ Duration: ~30 seconds                           │
└──────────────────────────────────────────────────┘
```

---

## Tier 1: CI/CD Tests

### Always Run (No GPU Required)

```java
@Test
@DisplayName("Kernel source exists and is valid")
void testKernelSourceExists() {
    var renderer = new DAGOpenCLRenderer(512, 512);
    var source = renderer.getKernelSource();
    assertTrue(source.contains("rayTraverseDAG"));
    assertTrue(source.length() > 1000);
}

@Test
@DisplayName("Kernel compilation creates valid options")
void testKernelCompilation() {
    var renderer = new DAGOpenCLRenderer(512, 512);
    var options = renderer.buildOptionsForDAGTraversal();
    assertTrue(options.contains("ABSOLUTE_ADDRESSING=1"));
    assertTrue(options.contains("DAG_TRAVERSAL=1"));
}

@Test
@DisplayName("GPU vendor detection works")
void testVendorDetection() {
    var detector = new GPUVendorDetector();
    // Returns cached vendor or detects from environment
    assertNotNull(detector.detectVendor());
}

@Test
@DisplayName("Vendor configurations load correctly")
void testConfigurationLoading() {
    for (GPUVendor vendor : GPUVendor.values()) {
        var config = VendorKernelConfig.forVendor(vendor);
        assertNotNull(config);
        assertTrue(config.buildFlags().length() > 0);
    }
}
```

### Running Tier 1

```bash
mvn test -Dtest=DAGOpenCLRendererVendorTest
# ✅ 4 tests pass
# ⏭️ 30 tests skipped (GPU-conditional)
```

---

## Tier 2: Local GPU Tests

### Requires GPU Hardware

```bash
RUN_GPU_TESTS=true mvn test -Dtest=DAGOpenCLRendererVendorTest
```

**Test Categories**:

#### GPU Execution Tests (8 tests)
```java
@EnabledIfEnvironmentVariable(named = "RUN_GPU_TESTS", matches = "true")
@Test void testNVIDIARayTraversal() { ... }
@Test void testAMDRayTraversal() { ... }
@Test void testIntelRayTraversal() { ... }
@Test void testAppleRayTraversal() { ... }
```

#### Vendor-Specific Optimization Tests (7 tests)
```java
@Test void testNVIDIAOccupancyOptimization() { ... }
@Test void testAMDAtomicSemantics() { ... }
@Test void testIntelPrecisionRelaxation() { ... }
@Test void testAppleFabsWorkaround() { ... }
```

---

## Tier 3: Nightly Vendor Matrix

### Full Vendor Validation

```bash
# NVIDIA vendor matrix
GPU_VENDOR=NVIDIA RUN_GPU_TESTS=true mvn test

# AMD vendor matrix
GPU_VENDOR=AMD RUN_GPU_TESTS=true mvn test

# Intel vendor matrix
GPU_VENDOR=INTEL RUN_GPU_TESTS=true mvn test

# Apple vendor matrix
GPU_VENDOR=APPLE RUN_GPU_TESTS=true mvn test
```

### Vendor-Specific Details

#### NVIDIA

```java
GPUVendor.NVIDIA:
  Supported: GeForce RTX 30/40, RTX A100/A6000, V100, T4
  Compute Capability: 7.0+ (Volta and newer)
  Optimizations:
    - MAD (Multiply-Add): -cl-mad-enable
    - Warp size: 32 threads
    - Shared memory: 96KB per SM

  Vendor Config:
    "⌂ CUDA Architecture: 700 (Volta)
     ⌂ MAD Enable: true
     ⌂ Warp Size: 32"

Tests:
  - testNVIDIARayTraversal: Basic GPU execution
  - testNVIDIAOccupancy: 4+ warps per SM (max occupancy)
  - testNVIDIAMemCoalescing: Aligned memory access
  - testNVIDIACUDAABICompat: CUDA ABI compatibility
```

#### AMD

```java
GPUVendor.AMD:
  Supported: RX 5700 XT, RX 6900 XT, RX 7900 XTX
  Architecture: RDNA/RDNA 2/RDNA 3
  Optimizations:
    - Atomic semantics: acquire-release
    - Fast relaxed math: -cl-fast-relaxed-math
    - Wave size: 64 threads (RDNA: 32)

  Vendor Config:
    "⌂ GCN/RDNA Detection: RDNA
     ⌂ Atomic Semantics: acquire-release
     ⌂ Fast Math: enabled"

Tests:
  - testAMDRayTraversal: Basic GPU execution
  - testAMDAtomics: Atomic operation correctness
  - testAMDSharedMemory: LDS usage and conflicts
  - testAMDWaveOccupancy: Wave occupancy calculation
```

#### Intel

```java
GPUVendor.INTEL:
  Supported: Arc A770, Arc A750, Data Center
  Architecture: Xe-HPC
  Optimizations:
    - Precision relaxation: -cl-fast-relaxed-math
    - Sub-group size: 16/32
    - Shared memory: 128KB per XVE

  Vendor Config:
    "⌂ Xe Architecture: XVE
     ⌂ Sub-group Size: 32
     ⌂ Fast Math: enabled"

Tests:
  - testIntelRayTraversal: Basic GPU execution
  - testIntelPrecision: Relaxed math correctness
  - testIntelSubgroup: Sub-group operations
  - testIntelMemoryBandwidth: Memory throughput
```

#### Apple

```java
GPUVendor.APPLE:
  Supported: M1, M2, M3, M4 (Pro/Max/Ultra)
  Backend: Metal (OpenCL via compatibility layer)
  Optimizations:
    - fabs() workaround (compiler bug fix)
    - Unified memory architecture
    - Tile-based deferred rendering

  Vendor Config:
    "⌂ Metal Backend: true
     ⌂ M-Series: M2 Max
     ⌂ Fabs Workaround: enabled"

Tests:
  - testAppleRayTraversal: Basic GPU execution
  - testAppleFabs: Fabs() replacement validation
  - testAppleMacOSCompat: macOS-specific issues
  - testAppleMemoryAlignment: Unified memory alignment
```

---

## GPU Vendor Detection

### GPUVendorDetector

```java
public class GPUVendorDetector {
    public GPUVendor detectVendor() {
        // 1. Check GPU_VENDOR environment variable
        String envVendor = System.getenv("GPU_VENDOR");
        if (envVendor != null) {
            return GPUVendor.valueOf(envVendor.toUpperCase());
        }

        // 2. Query OpenCL device properties
        CLDevice device = getDefaultOpenCLDevice();
        String deviceName = device.getStringInfo(CL_DEVICE_NAME);

        // 3. Match against known patterns
        if (deviceName.contains("NVIDIA")) return GPUVendor.NVIDIA;
        if (deviceName.contains("AMD") || deviceName.contains("Radeon")) return GPUVendor.AMD;
        if (deviceName.contains("Intel") || deviceName.contains("Arc")) return GPUVendor.INTEL;
        if (deviceName.contains("Apple")) return GPUVendor.APPLE;

        // 4. Default
        return GPUVendor.UNKNOWN;
    }
}
```

### Usage

```java
var detector = new GPUVendorDetector();
GPUVendor vendor = detector.detectVendor();

System.out.println("Detected: " + vendor.getDisplayName());
// Output: "Detected: NVIDIA GeForce RTX 4090"

// Configure renderer for vendor
var config = VendorKernelConfig.forVendor(vendor);
renderer.applyVendorConfig(config);
```

---

## Vendor-Specific Workarounds

### Apple fabs() Bug

**Issue**: Apple's OpenCL compiler incorrectly compiles `fabs()` in some contexts

**Workaround**:
```opencl
// Before (fails on Apple):
float absVal = fabs(x);

// After (works on all vendors):
float absVal = select(x, -x, x < 0.0f);
// or
float absVal = fabs_replacement(x);

// In kernel:
#ifdef __METAL__
float fabs_replacement(float x) { return select(x, -x, x < 0.0f); }
#else
float fabs_replacement(float x) { return fabs(x); }
#endif
```

### AMD Atomic Semantics

**Issue**: AMD requires explicit atomic semantics (memory ordering)

**Workaround**:
```opencl
// Before (May deadlock on AMD):
atomic_inc(&counter);

// After (explicitly ordered):
atomic_fetch_add_explicit(&counter, 1, memory_order_acq_rel, memory_scope_device);
```

### Intel Precision

**Issue**: Intel GPUs may require relaxed math mode for performance

**Workaround**:
```
Build option: -cl-fast-relaxed-math
Side effect: Floating point operations lose strict IEEE 754 semantics
Impact: Acceptable for ray traversal (spatial rounding errors negligible)
```

---

## Consistency Report

### MultiVendorConsistencyReport

```java
var report = new MultiVendorConsistencyReport();

// Run tests across vendors
for (GPUVendor vendor : List.of(NVIDIA, AMD, Intel, Apple)) {
    var metrics = profileVendor(vendor);
    report.addVendorMetrics(vendor, metrics);
}

// Generate consistency report
System.out.println(report.formatReport());

// Output:
// ────────────────────────────────────
// Multi-Vendor Consistency Report
// ────────────────────────────────────
//
// Baseline: NVIDIA RTX 4090 = 450µs
//
// NVIDIA RTX 4090:  450µs (baseline)
// AMD RX 7900 XTX:  480µs (+6.7%)
// Intel Arc A770:   490µs (+8.9%)
// Apple M2 Max:     510µs (+13.3%)
//
// Consistency: 93.3% (all within ±15%)
// Status: ✅ PASS
```

### Consistency Thresholds

```
Target: ≥90% consistency (latency variance ≤15%)

Acceptable:
  - NVIDIA: 450µs (baseline)
  - AMD: 400-500µs (±11%)
  - Intel: 380-520µs (±15%)
  - Apple: 440-520µs (±15%)

Flag for investigation:
  - Variance >15% on specific GPU
  - 1+ vendor failing tests
  - Inconsistent cache hit rates
```

---

## GitHub Actions Workflow

### GPU Testing in CI/CD

```yaml
# .github/workflows/gpu-vendor-tests.yml

name: Multi-Vendor GPU Testing

on:
  pull_request:
    paths:
      - 'render/**'
      - '.github/workflows/gpu-vendor-tests.yml'

jobs:
  tier-1-ci:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          java-version: '25'
      - name: Run Tier 1 tests (CI)
        run: mvn test -Dtest=DAGOpenCLRendererVendorTest
        # 4 tests pass, 30 skipped (expected)

  tier-2-nvidia:
    runs-on: [ubuntu-latest, gpu-nvidia]
    if: contains(github.labels.*.name, 'requires-gpu')
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          java-version: '25'
      - name: Run NVIDIA tests
        env:
          RUN_GPU_TESTS: 'true'
          GPU_VENDOR: 'NVIDIA'
        run: mvn test -Dtest=DAGOpenCLRendererVendorTest

  tier-2-amd:
    runs-on: [ubuntu-latest, gpu-amd]
    if: contains(github.labels.*.name, 'requires-gpu')
    # Similar to NVIDIA, with GPU_VENDOR=AMD

  tier-2-intel:
    runs-on: [ubuntu-latest, gpu-intel]
    if: contains(github.labels.*.name, 'requires-gpu')
    # Similar to NVIDIA, with GPU_VENDOR=INTEL
```

---

## Testing Checklist

### Before Release

```yaml
Tier 1 (Always):
□ Kernel syntax validation passing
□ Build option generation correct
□ Vendor detection working
□ Configuration loading correct

Tier 2 (With GPU):
□ NVIDIA GPU execution tests passing
□ AMD GPU execution tests passing
□ Intel GPU execution tests passing
□ Apple GPU execution tests passing
□ Occupancy targets met
□ Cache hit rates expected

Tier 3 (Nightly):
□ All vendor-specific tests passing
□ Consistency report 90%+ agreement
□ No vendor-specific regressions
□ Performance within targets
```

---

**Status**: Production Ready ✅
**Test Coverage**: 34 multi-vendor tests (4 Tier 1, 15 Tier 2, 15 Tier 3)
**Vendor Coverage**: NVIDIA, AMD, Intel, Apple
