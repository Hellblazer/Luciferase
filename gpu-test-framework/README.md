# GPU Test Framework

A comprehensive, reusable headless GPU testing framework for Java 24 projects using LWJGL and OpenCL. Based on the LWJGL headless testing patterns and designed specifically for the ART (Adaptive Resonance Theory) project's GPU compute testing needs.

## Features

- **Headless Operation**: Runs without display or windowing systems - perfect for CI/CD
- **CI/CD Compatible**: Graceful handling of missing OpenCL libraries in CI environments
- **Platform Detection**: Automatic detection of macOS, Linux, Windows with ARM64/AMD64 support  
- **GPU Discovery**: Automatic OpenCL platform and device enumeration
- **Memory Management**: Safe LWJGL memory operations with automatic cleanup
- **JUnit 5 Integration**: Modern testing with conditional execution and assumptions
- **Graceful Degradation**: Tests skip gracefully when GPU hardware is unavailable
- **Mock Platform Support**: Provides mock platforms when real OpenCL is unavailable
- **Comprehensive Logging**: Detailed logging for debugging GPU issues

## Architecture

### Core Classes

- **`LWJGLHeadlessTest`** - Abstract base class for all LWJGL headless tests
- **`OpenCLHeadlessTest`** - OpenCL-specific base class with initialization and cleanup
- **`GPUComputeHeadlessTest`** - GPU compute testing with platform/device discovery
- **`CICompatibleGPUTest`** - CI-compatible base class with automatic OpenCL detection
- **`MockPlatform`** - Mock platform/device system for CI environments without OpenCL
- **`PlatformTestSupport`** - Utilities for platform-conditional test execution

### Test Infrastructure

- **`HeadlessPlatformValidationTest`** - Platform validation (run this FIRST!)
- **`BasicGPUComputeTest`** - Example implementation showing framework usage

## Quick Start

### 1. Run Platform Validation

First, run the platform validation test to ensure your environment is properly configured:

```bash
mvn test -Dtest=HeadlessPlatformValidationTest
```

Expected output on macOS M4:
```
=== LWJGL Headless Platform Validation ===
Platform: macOS (MACOSX)
Architecture: ARM64 (64-bit: true)
✅ Headless platform validation PASSED - Framework safe to use!
```

### 2. Basic Usage

**For CI-Compatible Tests** (Recommended):
Extend `CICompatibleGPUTest` for automatic CI compatibility:

```java
public class MyGPUTest extends CICompatibleGPUTest {
    
    @Test
    void testMyGPUKernel() {
        var platforms = discoverPlatforms();
        // CICompatibleGPUTest automatically skips when OpenCL unavailable
        
        var platform = platforms.get(0);
        var gpuDevices = discoverDevices(platform.platformId(), CL_DEVICE_TYPE_GPU);
        
        if (MockPlatform.isMockPlatform(platform)) {
            // Skip actual GPU operations with mock platforms
            log.info("Using mock platform - skipping GPU kernel test");
            return;
        }
        
        var device = gpuDevices.get(0);
        testGPUVectorAddition(platform.platformId(), device.deviceId());
    }
}
```

**For Direct GPU Testing**:
Extend `GPUComputeHeadlessTest` with manual assumptions:

```java  
public class DirectGPUTest extends GPUComputeHeadlessTest {
    
    @Test
    void testMyGPUKernel() {
        var platforms = discoverPlatforms();
        assumeTrue(!platforms.isEmpty(), "No OpenCL platforms available");
        
        var platform = platforms.get(0);
        var gpuDevices = discoverDevices(platform.platformId(), CL_DEVICE_TYPE_GPU);
        assumeTrue(!gpuDevices.isEmpty(), "No GPU devices available");
        
        var device = gpuDevices.get(0);
        testGPUVectorAddition(platform.platformId(), device.deviceId());
    }
}
```

### 3. Platform-Specific Testing

Use `PlatformTestSupport` for conditional execution:

```java
@Test
void testLinuxSpecificFeature() {
    PlatformTestSupport.requirePlatform(Platform.LINUX);
    // Test code here
}

@Test
void testWith64BitArch() {
    PlatformTestSupport.require64Bit();
    // Test code here
}

@Test 
void testSkippingARM() {
    PlatformTestSupport.skipOnARMForStackTests(); // For JVM stack tests
    // Test code here
}
```

## Maven Integration

The framework automatically handles platform-specific native libraries:

```xml
<dependency>
    <groupId>com.hellblazer.art</groupId>
    <artifactId>gpu-test-framework</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
```

### Supported Platforms

- **macOS**: ARM64 (Apple Silicon), x86_64 (Intel)
- **Linux**: AMD64, ARM64
- **Windows**: AMD64

Native libraries are automatically selected based on your platform.

## Configuration

### Maven Test Configuration

Tests run with headless configuration by default:

```xml
<systemPropertyVariables>
    <java.awt.headless>true</java.awt.headless>
    <lwjgl.opencl.explicitInit>true</lwjgl.opencl.explicitInit>
</systemPropertyVariables>
```

### JVM Arguments

The framework sets these JVM arguments automatically:
- `-Djava.awt.headless=true` - Headless operation
- `--add-modules jdk.incubator.vector` - Vector API support
- LWJGL debug settings for memory tracking

## Testing Strategy

### Test Categories

1. **Platform Validation** - Verify LWJGL and OpenCL work on current platform
2. **Device Discovery** - Enumerate available compute devices  
3. **Compute Testing** - Run actual GPU kernels with verification
4. **Memory Testing** - Validate memory allocation patterns

### Conditional Execution

Tests use JUnit 5's `@EnabledIf` and `Assumptions` for graceful handling:

```java
@Test
@EnabledIf("hasGPUDevice")  // Only run if GPU available
void testGPUKernel() { ... }

@Test 
void testWithGracefulFallback() {
    var devices = discoverDevices(platformId, CL_DEVICE_TYPE_GPU);
    assumeTrue(!devices.isEmpty(), "No GPU - skipping test");
    // Test code here
}
```

## CI/CD Integration

### GitHub Actions Example

```yaml
- name: Run GPU Tests
  run: mvn test -Dtest="*GPUTest" 
  env:
    JAVA_TOOL_OPTIONS: -Djava.awt.headless=true
```

### Expected Behavior

- **With GPU**: All tests run and validate GPU compute functionality
- **Without GPU**: Tests skip gracefully with informative messages
- **CI Systems**: Framework detects headless environment and adapts accordingly

## Troubleshooting

### Common Issues

1. **"No OpenCL platforms found"** - Normal on systems without OpenCL drivers
2. **ARM64 JVM stack tests fail** - Expected limitation, library functions work fine  
3. **macOS windowing errors** - Use headless tests, or add `-XstartOnFirstThread` for windowing

### Debug Logging

Enable debug logging for detailed GPU discovery information:

```xml
<systemPropertyVariables>
    <org.lwjgl.util.Debug>true</org.lwjgl.util.Debug>
    <org.lwjgl.util.DebugAllocator>true</org.lwjgl.util.DebugAllocator>
</systemPropertyVariables>
```

### Platform Validation

If tests fail, first run the platform validation:

```bash
mvn test -Dtest=HeadlessPlatformValidationTest
```

This will provide detailed information about what's working and what isn't.

## Example Output

Successful GPU discovery and testing:
```
[INFO] Found 1 OpenCL platform(s):
[INFO]   Apple - Apple (OpenCL 1.2 (Aug 25 2024 22:07:56))
[INFO] Found 1 device(s) on platform Apple:
[INFO]   Apple M4 [GPU] - 10 CUs, 21474.8 MB mem
[INFO] Testing GPU vector addition on: Apple M4 [GPU] - 10 CUs, 21474.8 MB mem
[INFO] ✅ GPU vector addition test PASSED - 1024 elements processed
```

## Integration with ART Project

This framework is designed specifically for testing ART neural network GPU acceleration:

- Vector operations (addition, multiplication, dot products)
- Matrix operations for weight updates
- Parallel pattern matching computations
- Memory bandwidth testing for large datasets

The framework provides the foundation for testing GPU-accelerated ART algorithms in a headless CI/CD environment.