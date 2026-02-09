# Stream D: Multi-Vendor GPU Testing Infrastructure

**Project**: Luciferase F3.1 Phase 3 - GPU Acceleration
**Bead**: Luciferase-01mn (F3.1.4: Multi-Vendor Testing)
**Status**: PLANNED
**Last Updated**: 2026-01-21

---

## Executive Summary

This document defines the comprehensive multi-vendor GPU testing infrastructure for validating OpenCL kernels across NVIDIA, AMD, Intel, and Apple GPUs. The infrastructure implements a 3-tier testing strategy that maintains CI/CD independence while enabling thorough vendor-specific validation.

**Key Objectives:**
- >90% vendor consistency across all tested GPUs
- CI/CD remains mock-GPU only (no hardware dependency)
- Clear vendor-specific failure attribution
- Automated compatibility matrix updates

---

## 1. GPU Vendor Detection Infrastructure

### 1.1 GPUVendor Enumeration

**Location**: `render/src/main/java/com/hellblazer/luciferase/sparse/gpu/vendor/GPUVendor.java`

```java
public enum GPUVendor {
    NVIDIA("NVIDIA", "nvidia", "NVIDIA Corporation"),
    AMD("AMD", "amd", "Advanced Micro Devices", "Advanced Micro Devices, Inc."),
    INTEL("Intel", "intel", "Intel Corporation", "Intel(R) Corporation"),
    APPLE("Apple", "apple"),
    UNKNOWN("Unknown");

    private final String[] aliases;

    GPUVendor(String... aliases) {
        this.aliases = aliases;
    }

    public static GPUVendor fromString(String vendor) {
        if (vendor == null || vendor.isEmpty()) return UNKNOWN;
        String lower = vendor.toLowerCase();
        for (GPUVendor v : values()) {
            for (String alias : v.aliases) {
                if (lower.contains(alias.toLowerCase())) return v;
            }
        }
        return UNKNOWN;
    }

    public boolean matches(String vendorString) {
        return fromString(vendorString) == this;
    }
}
```

### 1.2 GPUVendorDetector

**Location**: `render/src/main/java/com/hellblazer/luciferase/sparse/gpu/vendor/GPUVendorDetector.java`

```java
public final class GPUVendorDetector {
    private static volatile GPUVendorDetector instance;

    private final GPUVendor detectedVendor;
    private final String rawVendorString;
    private final String deviceName;
    private final String openCLVersion;
    private final int computeUnits;
    private final long globalMemoryBytes;
    private final boolean supportsFloat16;
    private final boolean supportsFloat64;

    private GPUVendorDetector() {
        // Query OpenCL device info via CL_DEVICE_VENDOR, etc.
        // Check environment override: GPU_VENDOR
    }

    public static GPUVendorDetector getInstance() {
        if (instance == null) {
            synchronized (GPUVendorDetector.class) {
                if (instance == null) {
                    instance = new GPUVendorDetector();
                }
            }
        }
        return instance;
    }

    // Convenience methods
    public GPUVendor getVendor() { return detectedVendor; }
    public boolean isNVIDIA() { return detectedVendor == GPUVendor.NVIDIA; }
    public boolean isAMD() { return detectedVendor == GPUVendor.AMD; }
    public boolean isIntel() { return detectedVendor == GPUVendor.INTEL; }
    public boolean isApple() { return detectedVendor == GPUVendor.APPLE; }

    // Hardware capabilities
    public int getComputeUnits() { return computeUnits; }
    public long getVRAMBytes() { return globalMemoryBytes; }
    public boolean supportsFloat16() { return supportsFloat16; }
    public boolean supportsFloat64() { return supportsFloat64; }

    // For testing
    public static void resetForTesting() { instance = null; }
}
```

### 1.3 Environment Variable Integration

| Variable | Purpose | Values | Priority |
|----------|---------|--------|----------|
| `GPU_VENDOR` | Manual vendor override | NVIDIA, AMD, Intel, Apple | Highest |
| `RUN_GPU_TESTS` | Enable tier 2 GPU tests | true, false | - |
| `RUN_GPU_VENDOR` | Tier 3 vendor-specific tests | NVIDIA, AMD, Intel, Apple | - |

**Detection Priority:**
1. `GPU_VENDOR` environment variable (if set)
2. OpenCL `CL_DEVICE_VENDOR` query (runtime detection)
3. Default to `UNKNOWN`

---

## 2. Three-Tier Testing Strategy

### 2.1 Tier 1: CI/CD (Mock GPU Only)

**Environment**: `RUN_GPU_TESTS=false` (default)

**Purpose**: Validate kernel syntax, algorithm correctness without GPU hardware.

**What Runs:**
- Kernel source syntax validation (via glslangValidator for GLSL, clcc for OpenCL)
- CPU mock implementations of GPU algorithms
- Unit tests with mocked GPU operations

**GitHub Actions Configuration:**
```yaml
jobs:
  test-tier1:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Run tests (mock GPU)
        run: mvn test -pl render
        # RUN_GPU_TESTS not set = tests skip gracefully
```

### 2.2 Tier 2: Local Development (GPU-Preferred)

**Environment**: `RUN_GPU_TESTS=true`

**Purpose**: Validate real GPU execution during local development.

**What Runs:**
- All tier 1 tests
- GPU kernel compilation
- GPU buffer allocation and uploads
- Basic GPU execution validation
- Performance sanity checks

**Usage:**
```bash
# Run all GPU tests
RUN_GPU_TESTS=true mvn test -pl render

# Run specific GPU test
RUN_GPU_TESTS=true mvn test -pl render -Dtest=DAGOpenCLRendererTest
```

### 2.3 Tier 3: Nightly/Weekly Vendor-Specific

**Environment**: `RUN_GPU_VENDOR=[NVIDIA|AMD|Intel|Apple]`

**Purpose**: Comprehensive vendor-specific validation and performance baselines.

**What Runs:**
- All tier 1 and tier 2 tests
- Vendor-specific workaround validation
- Precision handling tests
- Memory alignment tests
- Synchronization tests
- Performance baseline collection
- Compatibility matrix updates

**Self-Hosted Runner Configuration:**
```yaml
jobs:
  test-nvidia:
    runs-on: [self-hosted, nvidia]
    env:
      RUN_GPU_TESTS: true
      RUN_GPU_VENDOR: NVIDIA
    steps:
      - uses: actions/checkout@v4
      - name: Run NVIDIA vendor tests
        run: mvn test -pl render -Dtest.group=vendor
```

---

## 3. JUnit 5 Test Annotations

### 3.1 @GPUTest Annotation

**Location**: `render/src/test/java/com/hellblazer/luciferase/sparse/gpu/test/GPUTest.java`

```java
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(GPUTestExtension.class)
public @interface GPUTest {
    GPUVendor vendor() default GPUVendor.ANY;  // ANY = any available GPU
    String minOpenCLVersion() default "1.2";
    boolean requiresFloat16() default false;
    boolean requiresFloat64() default false;
    int minComputeUnits() default 1;
    long minVRAMBytes() default 0;
}
```

### 3.2 GPUTestExtension

**Location**: `render/src/test/java/com/hellblazer/luciferase/sparse/gpu/test/GPUTestExtension.java`

```java
public class GPUTestExtension implements ExecutionCondition, BeforeEachCallback {

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        // Check RUN_GPU_TESTS environment variable
        if (!"true".equalsIgnoreCase(System.getenv("RUN_GPU_TESTS"))) {
            return ConditionEvaluationResult.disabled("RUN_GPU_TESTS not set");
        }

        // Check OpenCL availability
        if (!AbstractOpenCLRenderer.isOpenCLAvailable()) {
            return ConditionEvaluationResult.disabled("OpenCL not available");
        }

        // Check vendor requirements from @GPUTest annotation
        Optional<GPUTest> annotation = findAnnotation(context, GPUTest.class);
        if (annotation.isPresent()) {
            GPUTest gpuTest = annotation.get();
            GPUVendorDetector detector = GPUVendorDetector.getInstance();

            // Vendor check
            if (gpuTest.vendor() != GPUVendor.ANY &&
                detector.getVendor() != gpuTest.vendor()) {
                return ConditionEvaluationResult.disabled(
                    "Requires " + gpuTest.vendor() + ", found " + detector.getVendor());
            }

            // Capability checks
            if (gpuTest.requiresFloat16() && !detector.supportsFloat16()) {
                return ConditionEvaluationResult.disabled("Requires FP16 support");
            }
            // ... additional capability checks
        }

        return ConditionEvaluationResult.enabled("GPU requirements met");
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        // Log vendor info for debugging
        GPUVendorDetector detector = GPUVendorDetector.getInstance();
        System.out.println("[" + detector.getVendor() + "] Running: " +
            context.getDisplayName());
    }
}
```

### 3.3 @VendorTest Annotation (Tier 3)

```java
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(VendorTestExtension.class)
public @interface VendorTest {
    GPUVendor value();  // Required vendor
}
```

---

## 4. Vendor-Specific Workarounds

### 4.1 VendorKernelConfig

**Location**: `render/src/main/java/com/hellblazer/luciferase/sparse/gpu/vendor/VendorKernelConfig.java`

```java
public final class VendorKernelConfig {

    public static String getCompileFlags(GPUVendor vendor) {
        return switch (vendor) {
            case NVIDIA -> "-cl-nv-verbose -cl-fast-relaxed-math";
            case AMD -> "-cl-mad-enable -cl-fast-relaxed-math";
            case INTEL -> "-cl-denorms-are-zero -cl-fast-relaxed-math";
            case APPLE -> "";  // Apple OpenCL is deprecated, minimal flags
            default -> "";
        };
    }

    public static int getPreferredWorkGroupSize(GPUVendor vendor) {
        return switch (vendor) {
            case NVIDIA -> 64;   // 2 warps (32 threads/warp)
            case AMD -> 64;      // 1 wavefront (64 threads, RDNA may use 32)
            case INTEL -> 64;    // Subgroup size varies
            case APPLE -> 64;    // Apple M-series
            default -> 64;
        };
    }

    public static int getWarpSize(GPUVendor vendor) {
        return switch (vendor) {
            case NVIDIA -> 32;
            case AMD -> 64;      // Classic GCN; RDNA uses 32
            case INTEL -> 16;    // Subgroup size
            case APPLE -> 32;    // Estimated
            default -> 32;
        };
    }

    public static int getMemoryAlignment(GPUVendor vendor) {
        return switch (vendor) {
            case NVIDIA -> 128;  // CUDA memory coalescing
            case AMD -> 256;     // AMD prefers 256-byte alignment
            case INTEL -> 64;    // Intel cache line
            case APPLE -> 256;   // Unified memory
            default -> 128;
        };
    }

    public static float getNormalizePrecisionEpsilon(GPUVendor vendor) {
        return switch (vendor) {
            case AMD -> 1e-5f;   // AMD has known normalize() precision differences
            default -> 1e-6f;
        };
    }
}
```

### 4.2 Vendor-Specific Workarounds

#### NVIDIA
- **Warp divergence**: Ensure ray batches are coherent
- **Memory coalescing**: Access patterns must be contiguous
- **Kernel flags**: `-cl-nv-maxrregcount=64` for register pressure

#### AMD
- **normalize() precision**: Use explicit implementation:
  ```opencl
  float3 safe_normalize(float3 v) {
      float len = sqrt(v.x*v.x + v.y*v.y + v.z*v.z);
      return len > 1e-7f ? v / len : (float3)(0.0f);
  }
  ```
- **Wavefront size**: Check RDNA vs GCN architecture
- **Memory alignment**: 256-byte boundaries for best performance

#### Intel
- **Signed byte handling**: Validate char/uchar conversions
- **Subgroup operations**: Different behavior from NVIDIA/AMD
- **Limited VRAM**: Aggressive buffer management on Arc GPUs

#### Apple
- **Unified memory**: No discrete VRAM, shared with CPU
- **OpenCL deprecated**: Monitor for removal in future macOS
- **Metal fallback**: Consider Metal Compute Shaders as backup

---

## 5. Validation Tests Per Vendor

### 5.1 Test Categories

| Category | Tests | Purpose |
|----------|-------|---------|
| Kernel Compilation | `VendorKernelCompilationTest` | Verify kernels compile with vendor flags |
| Memory Alignment | `VendorMemoryAlignmentTest` | Validate buffer alignment requirements |
| Precision Handling | `VendorPrecisionTest` | Test float32, float16 accuracy |
| Synchronization | `VendorSynchronizationTest` | Verify barrier/fence behavior |
| Performance | `VendorPerformanceBaselineTest` | Collect and compare baselines |

### 5.2 Test Implementation Pattern

**Location**: `render/src/test/java/com/hellblazer/luciferase/sparse/gpu/vendor/`

```java
@GPUTest
class VendorPrecisionTest {

    @Test
    @DisplayName("normalize() precision within vendor epsilon")
    void testNormalizePrecision() {
        var vendor = GPUVendorDetector.getInstance().getVendor();
        var epsilon = VendorKernelConfig.getNormalizePrecisionEpsilon(vendor);

        // Run precision test kernel
        var result = runNormalizeKernel(new float[]{1.0f, 2.0f, 3.0f});
        var expected = normalize(new float[]{1.0f, 2.0f, 3.0f});

        assertArrayEquals(expected, result, epsilon,
            "[" + vendor + "] normalize() precision exceeds epsilon " + epsilon);
    }

    @Test
    @GPUTest(requiresFloat16 = true)
    @DisplayName("float16 operations accurate within tolerance")
    void testFloat16Precision() {
        // Only runs on GPUs with FP16 support
        // ...
    }
}
```

### 5.3 Performance Consistency Test

```java
@GPUTest
class VendorPerformanceBaselineTest {

    @Test
    @DisplayName("Performance within 5% of baseline")
    void testPerformanceWithinTolerance() {
        var vendor = GPUVendorDetector.getInstance().getVendor();
        var deviceName = GPUVendorDetector.getInstance().getDeviceName();

        // Load baseline
        var baseline = PerformanceBaseline.load(vendor, deviceName);
        if (baseline == null) {
            // First run - create baseline
            var measured = runBenchmark();
            PerformanceBaseline.save(vendor, deviceName, measured);
            return;
        }

        // Compare to baseline
        var measured = runBenchmark();
        var delta = Math.abs(measured - baseline.raysPerSecond()) / baseline.raysPerSecond();

        assertTrue(delta <= 0.05,
            String.format("[%s] Performance delta %.1f%% exceeds 5%% tolerance. " +
                "Baseline: %.0f rays/s, Measured: %.0f rays/s",
                vendor, delta * 100, baseline.raysPerSecond(), measured));
    }
}
```

---

## 6. Performance Baseline Collection

### 6.1 Baseline File Format

**Location**: `render/src/test/resources/baselines/`

```json
{
    "vendor": "NVIDIA",
    "deviceName": "NVIDIA GeForce RTX 3080",
    "driverVersion": "535.104.05",
    "openCLVersion": "OpenCL 3.0 CUDA",
    "timestamp": "2026-01-15T14:30:00Z",
    "testParameters": {
        "frameWidth": 1920,
        "frameHeight": 1080,
        "iterations": 100,
        "warmupIterations": 10
    },
    "metrics": {
        "esvo_ray_traversal": {
            "raysPerSecond": 125000000,
            "avgFrameTimeMs": 16.5,
            "p99FrameTimeMs": 18.2
        },
        "esvt_ray_traversal": {
            "raysPerSecond": 98000000,
            "avgFrameTimeMs": 21.1,
            "p99FrameTimeMs": 23.5
        },
        "dag_ray_traversal": {
            "raysPerSecond": 145000000,
            "avgFrameTimeMs": 14.3,
            "p99FrameTimeMs": 15.8
        }
    }
}
```

### 6.2 PerformanceBaseline Record

```java
public record PerformanceBaseline(
    GPUVendor vendor,
    String deviceName,
    String driverVersion,
    LocalDateTime timestamp,
    Map<String, KernelMetrics> metrics
) {
    public record KernelMetrics(
        double raysPerSecond,
        double avgFrameTimeMs,
        double p99FrameTimeMs
    ) {}

    public static PerformanceBaseline load(GPUVendor vendor, String deviceName) {
        // Load from resources/baselines/{vendor}_{device}.json
    }

    public static void save(GPUVendor vendor, String deviceName, PerformanceBaseline baseline) {
        // Save to resources/baselines/{vendor}_{device}.json
    }
}
```

---

## 7. Compatibility Matrix Automation

### 7.1 CompatibilityMatrixUpdater

```java
public class CompatibilityMatrixUpdater {

    private static final Path MATRIX_PATH =
        Path.of("lucien/doc/GPU_COMPATIBILITY_MATRIX.md");

    public void updateAfterTestRun(List<VendorTestResult> results) {
        String markdown = Files.readString(MATRIX_PATH);

        for (var result : results) {
            String symbol = result.allPassed() ? "\\u2705" : // check
                           result.hasFailed() ? "\\u274C" : // X
                           "\\u26A0\\uFE0F";  // warning

            // Update vendor row in markdown tables
            markdown = updateVendorStatus(markdown, result.vendor(), symbol,
                result.testDate());
        }

        Files.writeString(MATRIX_PATH, markdown);
    }
}
```

### 7.2 Vendor Test Report

```java
public record VendorTestReport(
    LocalDateTime runDate,
    GPUVendor vendor,
    String deviceName,
    TestSummary summary,
    Map<String, PerformanceComparison> performance,
    List<String> issues
) {
    public record TestSummary(int passed, int failed, int skipped) {}
    public record PerformanceComparison(
        double baseline, double measured, String delta
    ) {}

    public void saveAsArtifact(Path outputDir) {
        // Write JSON for GitHub Actions artifact
    }
}
```

---

## 8. Implementation Phases

### Phase 1: Vendor Detection Infrastructure (Luciferase-gbns)
**Duration**: 3-5 days

| Task | Description |
|------|-------------|
| 1.1 | Create `GPUVendor` enum with alias matching |
| 1.2 | Implement `GPUVendorDetector` singleton |
| 1.3 | Add capability detection (FP16, FP64, compute units, VRAM) |
| 1.4 | Create `VendorKernelConfig` for compile flags |
| 1.5 | Integration with `AbstractOpenCLRenderer` |
| 1.6 | Unit tests for vendor detection |

### Phase 2: Conditional Test Framework (Luciferase-96za)
**Duration**: 5-7 days
**Depends on**: Phase 1

| Task | Description |
|------|-------------|
| 2.1 | Create `@GPUTest` annotation |
| 2.2 | Implement `GPUTestExtension` (JUnit 5 extension) |
| 2.3 | Create `@VendorTest` annotation for tier 3 |
| 2.4 | Add test skip logic based on environment variables |
| 2.5 | Migrate existing GPU tests to new annotations |
| 2.6 | Update test documentation |

### Phase 3: Vendor-Specific Workarounds (Luciferase-7uk1)
**Duration**: 5-7 days
**Depends on**: Phase 1

| Task | Description |
|------|-------------|
| 3.1 | Implement NVIDIA-specific kernel optimizations |
| 3.2 | Implement AMD precision workarounds (normalize) |
| 3.3 | Implement Intel memory alignment handling |
| 3.4 | Implement Apple unified memory handling |
| 3.5 | Create vendor-specific test kernels for validation |
| 3.6 | Integration tests for each workaround |

### Phase 4: Performance Matrix Collection (Luciferase-aky0)
**Duration**: 3-5 days
**Depends on**: Phase 2

| Task | Description |
|------|-------------|
| 4.1 | Create `VendorPerformanceBaseline` record |
| 4.2 | Implement baseline file I/O (JSON) |
| 4.3 | Create performance comparison assertions |
| 4.4 | Implement `CompatibilityMatrixUpdater` |
| 4.5 | Create vendor test report generation |
| 4.6 | GitHub Actions integration for automated reporting |

**Total Estimate**: 16-24 days

---

## 9. Dependency Graph

```
Luciferase-01mn (F3.1.4: Multi-Vendor Testing)
    |
    +-- Luciferase-gbns (D1: Vendor Detection)
    |       |
    |       +-- Luciferase-96za (D2: Test Framework)
    |       |       |
    |       |       +-- Luciferase-aky0 (D4: Performance Matrix)
    |       |
    |       +-- Luciferase-7uk1 (D3: Vendor Workarounds)
    |
    +-- Luciferase-hwmk (Phase 3: GPU Acceleration) [parent]
```

---

## 10. Success Criteria

| Criterion | Target | Measurement |
|-----------|--------|-------------|
| Vendor Consistency | >90% | Same tests pass on NVIDIA, AMD, Intel, Apple |
| CI Independence | 100% | All CI builds pass without GPU hardware |
| Failure Attribution | Clear | Test output identifies vendor-specific causes |
| Performance Tolerance | +/-5% | Measured vs baseline within tolerance |
| Matrix Coverage | 4 vendors | NVIDIA, AMD, Intel, Apple tested |

---

## 11. Risk Mitigations

| Risk | Mitigation |
|------|------------|
| Driver instability | Pin driver versions in tier 3 runners |
| Performance variance | 5% tolerance window, multiple run averaging |
| Memory differences | Explicit allocation strategies per vendor |
| Deprecated OpenCL (Apple) | Monitor for removal, Metal fallback plan |
| Limited test hardware | Prioritize Apple (dev machines) + one other vendor |

---

## 12. File Structure

```
render/src/main/java/com/hellblazer/luciferase/sparse/gpu/vendor/
    GPUVendor.java
    GPUVendorDetector.java
    VendorKernelConfig.java

render/src/test/java/com/hellblazer/luciferase/sparse/gpu/test/
    GPUTest.java              # Annotation
    GPUTestExtension.java     # JUnit extension
    VendorTest.java           # Tier 3 annotation
    VendorTestExtension.java  # Tier 3 extension

render/src/test/java/com/hellblazer/luciferase/sparse/gpu/vendor/
    VendorKernelCompilationTest.java
    VendorMemoryAlignmentTest.java
    VendorPrecisionTest.java
    VendorSynchronizationTest.java
    VendorPerformanceBaselineTest.java

render/src/test/resources/baselines/
    nvidia_rtx3080.json
    amd_rx6800.json
    intel_arc_a750.json
    apple_m4_max.json
```

---

## References

- **Existing Bead**: Luciferase-01mn (F3.1.4: Multi-Vendor Testing)
- **Parent Feature**: Luciferase-hwmk (Phase 3: GPU Acceleration)
- **GPU Compatibility Matrix**: `lucien/doc/GPU_COMPATIBILITY_MATRIX.md`
- **AbstractOpenCLRenderer**: `render/src/main/java/.../sparse/gpu/AbstractOpenCLRenderer.java`
- **Existing GPU Tests**: `render/src/test/java/.../esvo/gpu/DAGOpenCLRendererTest.java`

---

**END OF STREAM D PLAN**
