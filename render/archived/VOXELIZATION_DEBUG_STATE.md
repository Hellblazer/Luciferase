# Voxelization Debug State Save
Date: January 9, 2025, 12:58 PM PST
Branch: visi

## Critical Issue
Voxelization compute shaders are not executing in the standard pipeline despite successful pipeline creation.

## Current Status
- ShaderDiagnosticTest: ✅ Works perfectly - proves basic compute works
- testMinimalWriteOnly: ✅ Works - proves 4-binding pipeline can work
- testUltraSimpleVoxelShader: ❌ Fails - shader doesn't execute in voxelization pipeline
- VoxelizationComputeTest main tests: ❌ Fail - no shader execution

## Root Cause Analysis

### What We've Proven Works
1. Basic compute shaders execute correctly (ShaderDiagnosticTest)
2. 4-binding pipeline with mixed buffer types CAN work (testMinimalWriteOnly)
3. Pipeline creation succeeds (valid handles returned)
4. Buffer creation and mapping work

### What's Broken
1. Shaders loaded via executeGPUVoxelizationWithColorsAndShader don't execute
2. Buffer mapping timeouts occur (500ms warnings)
3. Buffers retain initialization values or zeros - no GPU writes

## Key Discoveries

### 1. Struct Alignment Issue (FIXED)
- **Problem**: CPU packing vec4 (80 bytes) vs shader expecting vec3 (64 bytes)
- **Solution**: Updated shader structs to use vec4 with padding
- **Files Modified**:
  - `/shaders/esvo/voxelization.wgsl` - Added vec4 padding
  - `/shaders/diagnostic/voxelization_debug.wgsl` - Matched alignment

### 2. Atomic Operations Mismatch (FIXED)
- **Problem**: Shader uses `array<atomic<u32>>` but buffer wasn't configured for atomics
- **Solution**: Updated diagnostic shaders to use atomic operations
- **Note**: This didn't fix the execution issue

### 3. Shader Caching Bug (IDENTIFIED)
- **Problem**: ComputeShaderManager caches by filename, not content
- **Impact**: Changes to shaders weren't being picked up
- **Workaround**: Clear cache with `shaderManager.cleanup()`
- **Location**: `ComputeShaderManager.java` lines 34-35, 59-61

### 4. Mysterious Buffer Patterns
- **0xAAAAAAAA Pattern Test**: Revealed shader isn't modifying buffers
- **All 1s Pattern**: Unknown source - possibly from uninitialized memory
- **All 0s Pattern**: Current state after fixing initialization

## Test Results Summary

### Working Test (testMinimalWriteOnly)
```java
// Creates its own pipeline from scratch
// Successfully writes 0x12345678 to buffer
var shader = shaderManager.loadShaderFromResource("/shaders/diagnostic/test_write_only.wgsl")
// ... creates buffers, bind groups, pipeline manually
// Result: ✅ Shader executes
```

### Failing Test (testUltraSimpleVoxelShader)
```java
// Uses executeGPUVoxelizationWithColorsAndShader
var simpleShader = shaderManager.loadShaderFromResource("/shaders/diagnostic/ultra_simple_voxel.wgsl")
int[] voxelGrid = executeGPUVoxelizationWithShader(triangles, simpleShader)
// Result: ❌ Shader doesn't execute, all values remain 0
```

## File Inventory

### Modified Files
1. `/render/src/main/resources/shaders/esvo/voxelization.wgsl`
   - Fixed Triangle struct alignment (vec3 → vec4)
   - Fixed VoxelParams struct padding

2. `/render/src/main/resources/shaders/diagnostic/voxelization_debug.wgsl`
   - Debug version with diagnostic output markers
   - Matched struct alignment

3. `/render/src/main/resources/shaders/diagnostic/ultra_simple_voxel.wgsl`
   - Minimal test shader with atomic operations
   - Writes patterns 0x11111111, 0x22222222, etc.

4. `/render/src/main/resources/shaders/diagnostic/test_write_only.wgsl`
   - Absolute minimal shader that WORKS
   - Just writes 0x12345678

5. `/render/src/test/java/com/hellblazer/luciferase/render/voxel/gpu/compute/VoxelizationComputeTest.java`
   - Added multiple diagnostic tests
   - Modified buffer initialization (0xAAAAAAAA → zeros)
   - Added shader cache clearing

6. `/render/src/test/java/com/hellblazer/luciferase/render/voxel/gpu/compute/ShaderDiagnosticTest.java`
   - Comprehensive diagnostic framework
   - Proves basic compute works

## Critical Code Sections

### Buffer Initialization (Line 537-539)
```java
// Initialize voxel grid with zeros to avoid any issues
byte[] zeroData = new byte[voxelGridSize];
context.writeBuffer(voxelGridBuffer, zeroData, 0);
```

### Pipeline Creation (Lines 602-618)
```java
var pipelineDescriptor = new Device.ComputePipelineDescriptor(voxelizationShader)
    .withLabel("voxelization_pipeline")
    .withLayout(pipelineLayout)
    .withEntryPoint("main");

var pipeline = device.createComputePipeline(pipelineDescriptor);
log.info("Created native compute pipeline: {}", pipeline.getHandle());
log.info("Shader module handle: {}", voxelizationShader.getHandle());
```

### Dispatch (Lines 616-618)
```java
int numWorkgroups = (triangles.length + 63) / 64; // 64 threads per workgroup
computePass.dispatchWorkgroups(Math.max(1, numWorkgroups), 1, 1);
```

## Hypothesis
The issue appears to be a silent WebGPU validation failure in the voxelization pipeline. Possible causes:
1. Pipeline layout mismatch between shader and bindings
2. Buffer usage flags incompatibility
3. Shader compilation failing silently
4. Command buffer submission failing without error

## Next Steps to Try
1. Add WebGPU validation error callbacks
2. Compare exact buffer usage flags between working and failing tests
3. Try using the working pipeline setup for voxelization
4. Check if there's a size limit or alignment requirement being violated
5. Verify uniform buffer data is correctly formatted

## SOLUTION FOUND - January 9, 2025, 1:20 PM PST

### The Bug
A single line bug in `VoxelizationComputeTest.java` line 651:
```java
// WRONG - Always used class field, ignoring parameter
var pipelineDescriptor = new Device.ComputePipelineDescriptor(voxelizationShader)

// CORRECT - Uses the shader parameter passed to the method
var pipelineDescriptor = new Device.ComputePipelineDescriptor(shader)
```

### Root Cause
The `executeGPUVoxelizationWithColorsAndShader` method was:
1. Accepting a `shader` parameter for diagnostic testing
2. But always using the class field `voxelizationShader` instead
3. This caused a mismatch between the pipeline (built for main shader) and bind groups
4. WebGPU validation correctly rejected this invalid configuration

### The Fix
Changed line 651 in `VoxelizationComputeTest.java` to use the `shader` parameter instead of `voxelizationShader`.

### Test Results After Fix
- ✅ `testUltraSimpleVoxelNoAtomic` - Passes, writes 0x12345678 marker
- ✅ `testDebugVoxelization` - Passes, all debug markers written correctly
- ✅ `testUltraSimpleVoxelShader` - Passes, atomic operations work
- ✅ `testSingleTriangleVoxelization` - Passes, voxelizes to 25 voxels

### Key Lessons
1. **Parameter shadowing**: Class fields can hide method parameters
2. **WebGPU validation**: Correctly catches pipeline/shader mismatches
3. **Debugging approach**: Systematic elimination of possibilities works
4. **Minimal reproduction**: Essential for isolating issues

## Investigation Update - January 9, 2025, 1:15 PM PST

### Findings Summary

#### Primary Issue Identified
**WebGPU Shader Module Validation Failure**
- All shaders fail with "Shader module is invalid" error during pipeline creation
- This occurs regardless of shader complexity (even ultra-minimal shaders fail)
- The validation failure happens in the WebGPU native library (libwgpu_native.dylib)
- Error occurs at pipeline creation time, not during shader compilation

#### What We've Tested
1. **Atomic vs Non-Atomic**: Removed all atomic operations - still fails
2. **Struct Alignment**: Verified perfect alignment between CPU and GPU structs
3. **Workgroup Size**: Reduced to (1,1,1) to match working shader - still fails
4. **Binding Count**: Tested with 3 and 4 bindings - both fail
5. **Minimal Shaders**: Even ultra-simple shaders without structs fail validation

#### Key Observation
The `testMinimalWriteOnly` test works because it creates its own pipeline from scratch with 4 bindings. The issue is specifically with the `executeGPUVoxelizationWithShader` method's pipeline creation.

### Root Cause Analysis

The problem is NOT:
- ❌ Atomic operations (removed, still fails)
- ❌ Struct alignment (verified correct)
- ❌ Shader syntax (minimal shaders also fail)
- ❌ Buffer usage flags (match working test)

The problem IS:
- ✅ **Pipeline layout mismatch between shader and bind group**
- ✅ **WebGPU validation rejecting the compute pipeline descriptor**
- ✅ **Possible issue with how executeGPUVoxelizationWithShader creates the pipeline**

### Next Steps Required

1. **Compare Pipeline Creation**
   - Analyze exact differences between working testMinimalWriteOnly and failing executeGPUVoxelizationWithShader
   - Focus on bind group layout descriptor creation
   - Check pipeline layout creation

2. **WebGPU Validation Debugging**
   - Add WebGPU validation error callbacks
   - Enable verbose WebGPU logging
   - Capture exact validation failure reason

3. **Alternative Approach**
   - Consider rewriting executeGPUVoxelizationWithShader to match working pattern
   - Use the working 4-binding pipeline structure
   - Create a new simplified voxelization method

## Resume Commands
```bash
cd /Users/hal.hildebrand/git/Luciferase
git status  # Should be on 'visi' branch

# Run working test
mvn test -pl render -Dtest=VoxelizationComputeTest#testMinimalWriteOnly -q

# Run failing test
mvn test -pl render -Dtest=VoxelizationComputeTest#testUltraSimpleVoxelShader -q

# Check shader content
cat render/src/main/resources/shaders/diagnostic/ultra_simple_voxel.wgsl
```

## Key Insight
The fundamental issue is that the same shader and buffer setup works in one pipeline configuration but not another. This points to a WebGPU pipeline validation or setup issue rather than a shader problem.