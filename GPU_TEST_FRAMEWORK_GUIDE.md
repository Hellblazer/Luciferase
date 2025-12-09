# GPU Test Framework Guide - Critical for Headless Testing

## CRITICAL UNDERSTANDING - READ THIS FIRST

**The GPU test framework enables headless GPU testing WITHOUT window handles or display requirements.**

### Key Principles

1. **OpenCL-based**: Uses OpenCL for compute operations, NOT OpenGL (no window needed)
2. **CI-Compatible**: Gracefully handles environments without GPU drivers  
3. **Mock Platform**: Provides mock OpenCL platform when real drivers unavailable
4. **No Window Handles**: Never creates GLFW windows or requires display context
5. **Automatic Skipping**: Tests skip gracefully in CI without crashes

## Framework Architecture

### Class Hierarchy

```text

LWJGLHeadlessTest (base)
  └── OpenCLHeadlessTest (OpenCL initialization)
      └── GPUComputeHeadlessTest (compute operations)
          └── CICompatibleGPUTest (CI detection & skipping)
              └── Your test class

```text

### Key Classes

1. **CICompatibleGPUTest**: Base class for ALL GPU tests
   - Detects OpenCL availability
   - Skips tests gracefully if unavailable  
   - Provides mock platform fallback
   - NO WINDOW CREATION

2. **MockPlatform**: Fallback for CI environments
   - Returns mock platform/device when real OpenCL unavailable
   - Allows tests to run structure validation without real GPU
   - Platform ID = 0 (special mock value)

## CORRECT Testing Pattern

### Example Test Class

```java

class MyGPUTest extends CICompatibleGPUTest {
    
    @Test
    void testGPUCompute() {
        // Discover platforms (returns mock if no real OpenCL)
        var platforms = discoverPlatforms();
        
        // Tests automatically skip if no platforms
        assumeTrue(!platforms.isEmpty());
        
        // Use first platform (may be mock)
        var platform = platforms.get(0);
        
        if (MockPlatform.isMockPlatform(platform)) {
            // Running on mock - skip actual GPU operations
            log.info("Using mock platform in CI");
            return;
        }
        
        // Real GPU operations here
        testGPUVectorAddition(platform.platformId(), deviceId);
    }
}

```text

## What NOT to Do (Common Failures)

### ❌ NEVER DO THIS

```java

// WRONG - Creates window, will crash in headless
GLFW.glfwInit();
GLFW.glfwCreateWindow(...);

// WRONG - OpenGL requires display context  
GL.createCapabilities();
glCreateShader(GL_COMPUTE_SHADER);

// WRONG - Direct GPU memory without checks
OctreeGPUMemory memory = new OctreeGPUMemory();
memory.uploadToGPU(); // Crashes without OpenGL context

```text

### ✅ CORRECT APPROACH

```java

// RIGHT - Extend CICompatibleGPUTest
class MyTest extends CICompatibleGPUTest {
    
    @Test
    void testCompute() {
        // Framework handles OpenCL init/detection
        var platforms = discoverPlatforms();
        
        // Graceful handling of no GPU
        if (platforms.isEmpty() || 
            MockPlatform.isMockPlatform(platforms.get(0))) {
            log.info("No real GPU - testing structure only");
            // Test data structures, not GPU operations
            return;
        }
        
        // Real GPU test with OpenCL (no window needed)
        testGPUVectorAddition(...);
    }
}

```text

## Testing Workflow

### 1. Local Development (GPU Available)

- Real OpenCL platforms detected
- Tests run actual GPU computations
- Full validation of GPU code

### 2. CI Environment (No GPU)

- OpenCL not found, mock platform returned
- Tests validate structure/logic only
- No crashes, tests skip gracefully

### 3. Mixed Testing

- Use `@EnabledIf("hasGPUDevice")` for GPU-only tests
- Use mock platform for structure validation
- Separate GPU operations from business logic

## Critical Files in gpu-test-framework Module

1. **CICompatibleGPUTest.java**: Main test base class
2. **GPUComputeHeadlessTest.java**: OpenCL compute operations  
3. **OpenCLHeadlessTest.java**: OpenCL initialization
4. **MockPlatform.java**: CI fallback implementation
5. **BasicGPUComputeTest.java**: Example test patterns

## OpenCL vs OpenGL

### OpenCL (What we use)

- Compute-only, no display needed
- Works headless with drivers
- Used for parallel computations
- No window handle required

### OpenGL (What we DON'T use for testing)

- Requires display context
- Needs window handle (GLFW)
- Will crash in headless CI
- Only for rendering, not compute

## Test Execution

### Running Tests

```bash

# Tests will automatically detect environment

mvn test

# Force CI mode (uses mock platform)

CI=true mvn test

# With OpenCL drivers installed

mvn test # Full GPU tests run

# Without OpenCL drivers  

mvn test # Tests skip gracefully

```text

## Key Methods

### From CICompatibleGPUTest

- `discoverPlatforms()`: Returns real or mock platforms
- `discoverDevices(platformId, deviceType)`: Returns devices
- `testGPUVectorAddition(platformId, deviceId)`: Example compute test
- `isOpenCLAvailable()`: Static check for OpenCL
- `isCIEnvironment()`: Detects CI environment

### From MockPlatform

- `shouldUseMockPlatform()`: Checks if mock needed
- `getMockPlatforms()`: Returns mock platform list
- `getMockDevices()`: Returns mock device list
- `isMockPlatform(platform)`: Checks if platform is mock

## Golden Path for New GPU Tests

1. **Always extend CICompatibleGPUTest**
2. **Never create windows or OpenGL contexts**
3. **Use OpenCL for compute operations**
4. **Check for mock platform before GPU operations**
5. **Test data structures separately from GPU code**
6. **Use assumeTrue() for graceful skipping**
7. **Log clearly when using mock platform**

## Summary

The gpu-test-framework provides headless GPU testing through OpenCL, with automatic fallback to mock platforms in CI environments. Tests NEVER create windows, NEVER use OpenGL for testing, and ALWAYS skip gracefully when GPU unavailable. This prevents the crash loops seen when trying to create OpenGL contexts in headless environments.
