# GPU Compatibility Matrix

**Project**: Luciferase ESVO Optimization  
**Last Updated**: 2025-12-08  
**Bead**: Luciferase-srk (Bead 0.3)  
**Status**: Active Tracking

---

## Purpose

This document tracks GPU compatibility across ESVO optimization Epics (0-5). It serves as:
- Hardware requirement documentation for each Epic
- Test result tracking across GPU vendors (NVIDIA, AMD, Intel, Apple)
- Platform-specific configuration guide
- Performance baseline reference

---

## Detected GPU Devices

### Detection Framework

Uses `GPUDeviceDetector` (Bead 0.2) via LWJGL OpenCL for GPU enumeration.

**Test Command**:
```bash
mvn test -Dtest=GPUDetectionTest -pl lucien
```

### Known Tested Devices

| Vendor | Device Name | Compute Units | Global Memory | OpenCL Version | Date Tested | Status |
|--------|-------------|---------------|---------------|----------------|-------------|--------|
| Apple | Apple M4 Max | 40 | 107.52 GB | 1.2 | 2025-12-08 | ✅ Tested |
| NVIDIA | (To be tested) | - | - | - | - | ⚪ Not Tested |
| AMD | (To be tested) | - | - | - | - | ⚪ Not Tested |
| Intel | (To be tested) | - | - | - | - | ⚪ Not Tested |

**Note**: Add new devices as they are tested. Use `GPUCapabilityReporter.generateMarkdownReport()` to auto-generate device information.

---

## Epic GPU Requirements

### Epic 0: Baseline Measurement and Infrastructure

**GPU Requirements**: OpenCL 1.2+

**Purpose**: Establish performance baselines and test GPU access

**GPU Operations**:
- GPU enumeration and capability reporting
- OpenCL kernel compilation test
- Basic compute shader execution (vector addition benchmark)

**Compatibility Status**:

| GPU Type | Status | Notes | Test Date |
|----------|--------|-------|-----------|
| Apple M4 Max | ✅ Passing | All tests pass, OpenCL 1.2 | 2025-12-08 |
| NVIDIA RTX 30xx | ⚪ Not Tested | - | - |
| NVIDIA RTX 40xx | ⚪ Not Tested | - | - |
| AMD RX 6000 | ⚪ Not Tested | - | - |
| AMD RX 7000 | ⚪ Not Tested | - | - |
| Intel Arc | ⚪ Not Tested | - | - |

**Platform Requirements**:
- **macOS**: `-XstartOnFirstThread` JVM flag (for GLFW/OpenGL tests)
- **Linux/CI**: `dangerouslyDisableSandbox=true` (sandbox blocks GPU access)
- **Windows**: TBD (not yet tested)

---

### Epic 1: SIMD Acceleration for Morton Encoding

**GPU Requirements**: None (CPU-only optimization)

**Purpose**: Use Java Vector API for 2-4x speedup in Morton encoding

**GPU Operations**: N/A - CPU SIMD only

**Compatibility Status**: Not applicable (CPU-only)

**Notes**: 
- Requires AVX2/AVX512 CPU support
- No GPU operations in this epic
- Gracefully falls back to scalar implementation if SIMD unavailable

---

### Epic 2: Beam Optimization for Ray Traversal

**GPU Requirements**: OpenCL 1.2+, Compute Shader support

**Purpose**: Hierarchical beam optimization for 30-50% traversal reduction

**GPU Operations**:
- Beam-geometry intersection (AABB, OBB, Sphere)
- Modified ray traversal with beam culling
- Integration with DSOC (Dynamic Spatial Occlusion Culling)

**Compatibility Status**:

| GPU Type | Status | Notes | Test Date |
|----------|--------|-------|-----------|
| Apple M4 Max | ⚪ Not Tested | OpenCL 1.2 capable | - |
| NVIDIA RTX 30xx | ⚪ Not Tested | - | - |
| NVIDIA RTX 40xx | ⚪ Not Tested | - | - |
| AMD RX 6000 | ⚪ Not Tested | - | - |
| AMD RX 7000 | ⚪ Not Tested | - | - |
| Intel Arc | ⚪ Not Tested | - | - |

**Critical Files**:
- `render/src/main/resources/kernels/esvo_ray_traversal.cl` - OpenCL kernel

**Test Procedure**:
1. Run existing DSOC tests to establish baseline
2. Enable beam optimization via `DSOCConfiguration`
3. Measure nodes visited per ray (target: 30-50% reduction)
4. Validate visual output matches baseline (SSIM >= 99%)

---

### Epic 3: Contour Compression with Octahedral Encoding

**GPU Requirements**: OpenCL 1.2+, Compute Shader support

**Purpose**: 75% memory reduction for contour normals (8 bytes → 2 bytes)

**GPU Operations**:
- Decode octahedral normals in ray traversal kernel
- Normal reconstruction from 2-byte compressed format
- Lighting calculations with decompressed normals

**Compatibility Status**:

| GPU Type | Status | Notes | Test Date |
|----------|--------|-------|-----------|
| Apple M4 Max | ⚪ Not Tested | OpenCL 1.2 capable | - |
| NVIDIA RTX 30xx | ⚪ Not Tested | - | - |
| NVIDIA RTX 40xx | ⚪ Not Tested | - | - |
| AMD RX 6000 | ⚪ Not Tested | - | - |
| AMD RX 7000 | ⚪ Not Tested | - | - |
| Intel Arc | ⚪ Not Tested | - | - |

**Critical Files**:
- `render/src/main/resources/kernels/esvo_ray_traversal.cl` - Octahedral decode logic

**Test Procedure**:
1. Verify contours exist in current implementation
2. Implement octahedral encoding/decoding
3. Validate decode performance (target: minimal overhead)
4. Visual regression test (SSIM >= 95%)
5. Memory measurement (target: 75% reduction)

**Vendor-Specific Concerns**:
- ⚠️ **AMD**: Potential precision differences in normalize() operations
- ⚠️ **Intel**: Need to validate signed byte handling
- ⚠️ **Apple**: Metal/OpenCL interop considerations

---

### Epic 4: Out-of-Core Streaming for Massive Datasets

**GPU Requirements**: OpenCL 1.2+, 4-8GB VRAM, FFM API support

**Purpose**: Interactive rendering of datasets larger than GPU VRAM (4096³+)

**GPU Operations**:
- Async brick upload via FFM (Foreign Function & Memory API)
- GPU memory pool management with LRU eviction
- Brick indirection in ray traversal kernel
- Texture/buffer management for streaming

**Compatibility Status**:

| GPU Type | VRAM | Status | Notes | Test Date |
|----------|------|--------|-------|-----------|
| Apple M4 Max | Unified 107GB | ⚪ Not Tested | Unified memory architecture | - |
| NVIDIA RTX 3060 | 8GB | ⚪ Not Tested | Reference target | - |
| NVIDIA RTX 3080 | 10GB | ⚪ Not Tested | - | - |
| NVIDIA RTX 4060 | 8GB | ⚪ Not Tested | - | - |
| AMD RX 6600 | 8GB | ⚪ Not Tested | Reference target | - |
| AMD RX 6800 | 16GB | ⚪ Not Tested | - | - |
| Intel Arc A750 | 8GB | ⚪ Not Tested | - | - |

**Critical Files**:
- `render/src/main/resources/kernels/esvo_ray_traversal.cl` - Brick indirection
- `com.hellblazer.luciferase.render.streaming.brick.*` - Java streaming classes

**Test Procedure**:
1. Generate 4096³ test dataset
2. Verify cache hit rate >95% in steady state
3. Measure frame rate (target: 30+ fps at 1920x1080)
4. 24-hour stress test for memory leaks
5. Test async I/O and GPU upload pipeline

**Vendor-Specific Concerns**:
- ⚠️ **NVIDIA**: CUDA memory model vs OpenCL (validate FFM transfers)
- ⚠️ **AMD**: Different memory hierarchy (check cache thrashing)
- ⚠️ **Intel**: Limited VRAM on Arc (need efficient eviction)
- ⚠️ **Apple**: Unified memory may behave differently (may need special handling)

**Performance Targets**:

| GPU Type | VRAM | Target FPS | Dataset Size | Status |
|----------|------|------------|--------------|--------|
| RTX 3060 / RX 6600 | 8GB | 30+ fps | 4096³ | ⚪ Not Tested |
| RTX 4080 / RX 7800 | 16GB | 60+ fps | 4096³ | ⚪ Not Tested |
| Apple M4 Max | Unified | 60+ fps | 4096³ | ⚪ Not Tested |

---

### Epic 5: Integration Testing and Validation

**GPU Requirements**: All requirements from Epics 2-4 combined

**Purpose**: Validate all optimizations work together without conflicts

**GPU Operations**:
- Combined: Beam optimization + Contour compression + Streaming
- Multi-GPU vendor validation
- Interaction testing (DSOC + Beam, Compression + Streaming)
- End-to-end performance benchmarking

**Compatibility Status**:

| GPU Type | Epic 2 | Epic 3 | Epic 4 | Integrated | Test Date |
|----------|--------|--------|--------|------------|-----------|
| Apple M4 Max | ⚪ | ⚪ | ⚪ | ⚪ | - |
| NVIDIA RTX 30xx | ⚪ | ⚪ | ⚪ | ⚪ | - |
| NVIDIA RTX 40xx | ⚪ | ⚪ | ⚪ | ⚪ | - |
| AMD RX 6000 | ⚪ | ⚪ | ⚪ | ⚪ | - |
| AMD RX 7000 | ⚪ | ⚪ | ⚪ | ⚪ | - |
| Intel Arc | ⚪ | ⚪ | ⚪ | ⚪ | - |

**Test Procedure**:
1. Run all Epic 2-4 tests individually on target GPU
2. Enable all optimizations simultaneously
3. Validate no emergent negative interactions
4. Measure combined performance vs baseline
5. Target: 60+ fps at 1920x1080 with 2048³ dataset

**Success Criteria**:
- All individual epics pass on target GPU
- Combined performance >= sum of individual improvements
- No visual regressions (SSIM >= 95%)
- No memory leaks in 24-hour test
- Frame rate meets target on reference hardware

---

## Testing Procedures

### Initial GPU Detection

**Prerequisites**: LWJGL OpenCL dependencies installed

**Steps**:
1. Run GPU detection test:
   ```bash
   mvn test -Dtest=GPUDetectionTest -pl lucien
   ```

2. If tests skip with "OpenCL not available":
   - **macOS**: Add `-XstartOnFirstThread` to JVM args
   - **Linux**: Add `-DdangerouslyDisableSandbox=true`
   - **CI**: Tests will gracefully skip (expected behavior)

3. Capture device information:
   ```bash
   mvn test -Dtest=GPUDetectionTest#testMarkdownReportGeneration -pl lucien
   ```

4. Add detected device to "Known Tested Devices" table above

### Per-Epic Testing

**Epic 0** (Baseline):
```bash
# Test GPU enumeration
mvn test -Dtest=GPUDetectionTest -pl lucien

# Test capability reporting
mvn test -Dtest=GPUCapabilityReporter* -pl lucien

# Run quick benchmark
mvn test -Dtest=GPUBenchmarkRunner* -pl lucien
```

**Epic 2** (Beam Optimization):
```bash
# Run DSOC baseline tests
mvn test -Dtest=*DSOC* -pl render

# Enable beam optimization (TODO: after implementation)
# mvn test -Dtest=BeamOptimizationTest -pl render
```

**Epic 3** (Contour Compression):
```bash
# Verify contours exist
grep -r "contourDescriptor" render/src/main/java/

# Run compression tests (TODO: after implementation)
# mvn test -Dtest=OctahedralEncodingTest -pl render

# Visual regression test
# mvn test -Dtest=VisualRegressionTest -pl render
```

**Epic 4** (Out-of-Core Streaming):
```bash
# Generate test dataset (TODO: after implementation)
# mvn test -Dtest=LargeDatasetGenerator -pl render

# Run streaming tests
# mvn test -Dtest=StreamingStressTest -pl render

# 24-hour stability test
# mvn test -Dtest=StreamingStabilityTest -pl render -Dtest.duration=24h
```

**Epic 5** (Integration):
```bash
# Run all Epic 2-4 tests
mvn test -pl render -Dtest.group=epic2,epic3,epic4

# Run integration test suite
# mvn test -Dtest=IntegrationTestSuite -pl render
```

### Platform-Specific Configuration

#### macOS

**Required JVM Args**:
```bash
-XstartOnFirstThread  # For GLFW/OpenGL tests
```

**Maven Command**:
```bash
mvn test -pl lucien -Dtest=GPUDetectionTest \
  -DargLine="-XstartOnFirstThread"
```

#### Linux

**Required JVM Args**:
```bash
-DdangerouslyDisableSandbox=true  # Sandbox blocks GPU access
```

**Maven Command**:
```bash
mvn test -pl lucien -Dtest=GPUDetectionTest \
  -DargLine="-DdangerouslyDisableSandbox=true"
```

#### CI Environments

**Behavior**: Tests gracefully skip when GPU/OpenCL unavailable

**Expected Output**:
```
⚠️  No GPU detected - GPU tests will be skipped
This is expected in CI environments without GPU support
Tests run: 8, Failures: 0, Errors: 0, Skipped: 8
BUILD SUCCESS
```

---

## Updating This Document

### When to Update

- After testing on a new GPU device
- After completing an Epic (update compatibility status)
- When discovering platform-specific issues
- After performance benchmark runs

### How to Update

1. **New Device Tested**:
   - Run `GPUDetectionTest#testMarkdownReportGeneration`
   - Copy device info to "Known Tested Devices" table
   - Update status: ⚪ → ✅ (or ⚠️/❌ if issues found)

2. **Epic Status Change**:
   - Update compatibility table for relevant epic
   - Add test date and notes
   - Document any vendor-specific issues discovered

3. **Platform Requirements**:
   - Add new platform-specific config to "Platform-Specific Configuration"
   - Update test procedures if needed

4. **Performance Results**:
   - Add benchmark results to relevant Epic section
   - Compare to baseline metrics
   - Note if target was met/exceeded

---

## Status Legend

| Symbol | Meaning | Description |
|--------|---------|-------------|
| ✅ | Tested & Passing | All tests pass, meets performance targets |
| ⚠️ | Partial Support | Works but with limitations or reduced performance |
| ❌ | Not Working | Tests fail or critical issues prevent usage |
| ⚪ | Not Tested | No test results available yet |

---

## Known Issues and Limitations

### Apple Silicon (M1/M2/M3/M4)

- **Unified Memory Architecture**: May behave differently than discrete GPU systems
- **OpenCL 1.2**: Older OpenCL version, Metal is preferred on macOS but LWJGL uses OpenCL
- **Status**: Tested and working for Epic 0, other epics TBD

### NVIDIA GPUs

- **Status**: Not yet tested
- **Expected**: Should work well, NVIDIA has strong OpenCL support
- **Note**: FFM API integration (Epic 4) needs validation

### AMD GPUs

- **Status**: Not yet tested
- **Concerns**: Different memory hierarchy, need to validate cache behavior (Epic 4)
- **Note**: ROCm/OpenCL driver quality varies by platform

### Intel Arc GPUs

- **Status**: Not yet tested
- **Concerns**: Limited VRAM (8GB typical), may need aggressive eviction (Epic 4)
- **Note**: Newer GPU line, OpenCL support quality unknown

---

## References

- **Bead 0.2**: GPU Compatibility Test Framework (Luciferase-wqw)
- **Bead 0.3**: Document GPU Vendor Compatibility Matrix (Luciferase-srk) - **This Document**
- **Epic Master Plan**: `memory-bank:Luciferase_ESVO_Optimization/epic_master_plan.md`
- **GPU Framework**: `lucien/src/test/java/com/hellblazer/luciferase/gpu/test/`
- **ESVO Paper**: "Efficient Sparse Voxel Octrees" (Laine & Karras, NVIDIA 2010)

---

**END OF GPU COMPATIBILITY MATRIX**
