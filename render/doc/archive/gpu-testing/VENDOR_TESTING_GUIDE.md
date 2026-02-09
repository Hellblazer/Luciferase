# F3.1.4: Multi-Vendor GPU Testing Guide

**Last Updated**: 2026-01-21
**Status**: Phase 3.1 Stream D - D1 Complete

## Overview

This guide describes the multi-vendor GPU testing infrastructure for validating OpenCL kernels across NVIDIA, AMD, Intel, and Apple GPUs.

## Architecture

### Core Components

1. **GPUVendor Enum** (`GPUVendor.java`)
   - Identifies GPU vendors: NVIDIA, AMD, INTEL, APPLE, UNKNOWN
   - Case-insensitive vendor detection from device strings
   - Workaround requirement checking

2. **GPUVendorDetector** (`GPUVendorDetector.java`)
   - Singleton for runtime GPU detection
   - Queries OpenCL device info via LWJGL
   - Caches detection results for performance
   - Thread-safe lazy initialization

3. **GPUCapabilities** (`GPUCapabilities.java`)
   - Immutable record containing:
     - Vendor, device name, vendor string
     - Compute units, max workgroup size
     - Global/local memory size
     - Max clock frequency, OpenCL version

4. **VendorKernelConfig** (`VendorKernelConfig.java`)
   - Applies vendor-specific preprocessor definitions
   - Implements source code workarounds
   - Provides compiler flags per vendor

## Vendor-Specific Issues

### NVIDIA
- **Status**: Baseline (no workarounds needed)
- **OpenCL Support**: Excellent
- **Compiler Flags**: `-cl-fast-relaxed-math -cl-mad-enable`

### AMD (RDNA/RDNA2/RDNA3)
- **Known Issues**:
  - Different atomic operation semantics
  - Shared memory access patterns differ from NVIDIA
- **Workarounds**:
  - Preprocessor: `AMD_ATOMIC_WORKAROUND`, `USE_RELAXED_ATOMICS`
- **Compiler Flags**: `-cl-fast-relaxed-math -cl-mad-enable -cl-unsafe-math-optimizations`

### Intel (Arc, Iris, UHD Graphics)
- **Known Issues**:
  - Ray-AABB intersection precision differences
  - Different shared memory behavior on Arc GPUs
- **Workarounds**:
  - Preprocessor: `INTEL_PRECISION_WORKAROUND`, `RAY_EPSILON 1e-5f` (relaxed from 1e-6f)
- **Compiler Flags**: `-cl-fast-relaxed-math` (no unsafe-math due to precision)

### Apple (M1/M2/M3/M4)
- **Known Issues**:
  - macOS `fabs()` function conflicts with system headers
  - OpenCL 1.2 deprecated (Metal Compute preferred)
  - Different coordinate space conventions
- **Workarounds**:
  - Source transformation: `fabs(x) < EPSILON` → `(x < EPSILON && x > -EPSILON)`
  - Preprocessor: `APPLE_MACOS_WORKAROUND`, `USE_INTEGER_ABS`, `METAL_COMPUTE_COORD_SPACE`
- **Compiler Flags**: Minimal (empty string)

## Testing Strategy

### Tier 1: CI Pipeline (Mock GPU)
- **Environment**: GitHub Actions (no real GPU)
- **Method**: Syntax validation, kernel structure tests
- **Tests**: GPUVendorTest, basic GPUVendorDetectorTest
- **Runtime**: ~30 seconds
- **Coverage**: All commits

### Tier 2: Local Optional Testing (Real GPU)
- **Environment**: Developer machine with GPU
- **Method**: Run with `RUN_GPU_TESTS=true`
- **Tests**: Hardware detection, capability queries, functional tests
- **Runtime**: ~2 minutes
- **Coverage**: Feature validation before PR

```bash
# Run local GPU tests
export RUN_GPU_TESTS=true
mvn test -pl render
```

### Tier 3: Nightly Vendor-Specific Testing
- **Environment**: Multi-GPU CI infrastructure or external runners
- **Method**: Matrix testing across vendors
- **Tests**: Full test suite + performance benchmarking
- **Runtime**: ~15 minutes per vendor
- **Coverage**: Comprehensive multi-vendor validation

```yaml
# Example GitHub Actions matrix (future)
strategy:
  matrix:
    gpu:
      - vendor: NVIDIA
        runner: gpu-nvidia-rtx-3060
      - vendor: AMD
        runner: gpu-amd-rx-6700
      - vendor: Intel
        runner: gpu-intel-arc-a770
      - vendor: Apple
        runner: macos-latest # M-series Mac
```

## Usage

### Detecting GPU Vendor

```java
// Automatic detection
var detector = GPUVendorDetector.getInstance();
var vendor = detector.getVendor();
var capabilities = detector.getCapabilities();

System.out.println("GPU: " + capabilities.summary());
// Output: APPLE - Apple M4 Max (40 CUs, 110100 MB VRAM, OpenCL OpenCL 1.2 )
```

### Applying Vendor Workarounds

```java
// Create configuration for detected GPU
var config = VendorKernelConfig.forDetectedGPU();

// Load kernel source
var kernelSource = loadKernelSource();

// Apply preprocessor definitions
var withPreprocessor = config.applyPreprocessorDefinitions(kernelSource);

// Apply source code workarounds
var final_source = config.applyWorkarounds(withPreprocessor);

// Get compiler flags
var flags = config.getCompilerFlags();

// Compile kernel with flags
compileKernel(final_source, flags);
```

### Writing Vendor-Specific Tests

```java
@EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "NVIDIA")
@Test
@DisplayName("NVIDIA-specific ray traversal test")
void testNvidiaRayTraversal() {
    var detector = GPUVendorDetector.getInstance();
    assertEquals(GPUVendor.NVIDIA, detector.getVendor());

    // Test NVIDIA-specific behavior
}

@EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "AMD")
@Test
@DisplayName("AMD atomic operation workaround test")
void testAmdAtomics() {
    // Test AMD-specific workarounds
}
```

## Environment Variables

| Variable | Values | Purpose |
|----------|--------|---------|
| `RUN_GPU_TESTS` | `true` / `false` | Enable Tier 2 local GPU tests |
| `GPU_VENDOR` | `NVIDIA` / `AMD` / `Intel` / `Apple` | Enable Tier 3 vendor-specific tests |

## Test Coverage

### Unit Tests (No GPU Required)
- ✅ GPUVendor parsing and identification (18 tests passing)
- ✅ GPUVendorDetector singleton behavior (14 tests, 9 skipped without GPU)
- ✅ VendorKernelConfig preprocessor definitions (implemented)
- ✅ VendorKernelConfig source workarounds (implemented)
- ✅ Compiler flag generation (implemented)

### Integration Tests (Require GPU)
- ⏳ Hardware detection validation (conditional on `RUN_GPU_TESTS=true`)
- ⏳ Vendor-specific ray traversal (conditional on `GPU_VENDOR`)
- ⏳ Performance consistency metrics (nightly)

## Performance Targets

- **CI Tests**: All pass in <30 seconds (mock/CPU)
- **Local Optional**: <2 minutes for full test suite (real GPU)
- **Nightly Vendor Tests**: <15 minutes per vendor (comprehensive)
- **Consistency**: >90% identical results across NVIDIA, AMD, Intel, Apple

## Future Work

### D2: @GPUTest Annotation (Bead: Luciferase-96za)
- Custom JUnit 5 extension for GPU test lifecycle
- Automatic vendor detection and test filtering
- Better test organization and reporting

### D3: Vendor-Specific Kernel Workarounds (Bead: Luciferase-7uk1)
- Implement AMD atomic operation adjustments
- Intel precision relaxation logic
- Apple Metal Compute integration (FFM-based)

### D4: CI/CD Integration (Future)
- GitHub Actions workflow for nightly vendor tests
- Multi-GPU runner configuration
- Automated consistency reporting
- Performance regression detection

## Known Limitations

1. **CI Environment**: GitHub Actions does not have GPU hardware
   - Tests requiring GPU are skipped in CI
   - Local development requires GPU for full validation

2. **Apple OpenCL Deprecation**: macOS has deprecated OpenCL in favor of Metal
   - OpenCL 1.2 still works on M-series Macs via LWJGL
   - Future: Consider Metal Compute FFM integration

3. **Vendor Detection**: Relies on OpenCL device name/vendor strings
   - New vendors may need pattern additions in `GPUVendor.fromString()`

## References

- **LWJGL OpenCL**: https://www.lwjgl.org/
- **OpenCL 1.2 Specification**: https://www.khronos.org/registry/OpenCL/
- **NVIDIA Compute Capability**: https://developer.nvidia.com/cuda-gpus
- **AMD RDNA Architecture**: https://www.amd.com/en/technologies/rdna
- **Intel Arc Graphics**: https://www.intel.com/content/www/us/en/products/details/discrete-gpus/arc.html
- **Apple Metal**: https://developer.apple.com/metal/

## Detected GPU Example

```
INFO  c.h.l.esvo.gpu.GPUVendorDetector - Detected GPU: APPLE (apple) - Apple M4 Max (40 CUs, 110100 MB VRAM, OpenCL OpenCL 1.2 )
```

This confirms the infrastructure is working correctly on Apple M4 Max hardware.
