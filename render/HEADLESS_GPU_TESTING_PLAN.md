# Headless GPU Testing Execution Plan for Render Module

## Executive Summary

This document outlines a comprehensive plan to enable real GPU testing in headless mode for the render module, leveraging the existing gpu-test-framework and extending it to support OpenGL compute shaders.

## Strategic Approach: Hybrid Testing Framework

### Three-Tier Testing Strategy

1. **Tier 1: Algorithm Validation (OpenCL)**
   - Port ESVO algorithms to OpenCL for cross-validation
   - Use existing gpu-test-framework infrastructure
   - Ensures mathematical correctness independent of graphics API

2. **Tier 2: API Contract Testing (Mocked OpenGL)**
   - Test ComputeShaderRenderer interfaces without GPU
   - Validate shader compilation and linking logic
   - Ensure proper resource management

3. **Tier 3: Integration Testing (Headless OpenGL)**
   - Create headless OpenGL contexts for real GPU execution
   - Platform-specific implementations
   - Performance benchmarking

## Phase 1: OpenCL Algorithm Validation

### 1.1 Create OpenCL Kernel Equivalents

**Location**: `render/src/test/java/com/hellblazer/luciferase/esvo/gpu/opencl/`

```java
package com.hellblazer.luciferase.esvo.gpu.opencl;

import com.hellblazer.luciferase.gpu.test.CICompatibleGPUTest;

public class ESVORaycastOpenCLTest extends CICompatibleGPUTest {
    
    private static final String RAYCAST_KERNEL = """
        __kernel void esvo_raycast(
            __global const uchar8* octree_nodes,
            __global float4* ray_origins,
            __global float4* ray_directions,
            __global float4* output_colors,
            const int frame_width,
            const int frame_height
        ) {
            int x = get_global_id(0);
            int y = get_global_id(1);
            if (x >= frame_width || y >= frame_height) return;
            
            // ESVO ray traversal algorithm
            // Port from GLSL compute shader
        }
        """;
    
    @Test
    void testRaycastKernel() {
        // Implementation
    }
}
```

### 1.2 Data Structure Validation

Create tests for each ESVO component:
- Octree node layout validation
- Morton code generation
- Ray-box intersection
- Stack-based traversal

## Phase 2: Headless OpenGL Context Creation

### 2.1 Platform-Specific Context Managers

**Location**: `gpu-test-framework/src/main/java/com/hellblazer/luciferase/gpu/test/opengl/`

```java
package com.hellblazer.luciferase.gpu.test.opengl;

public abstract class HeadlessGLContext {
    
    public static HeadlessGLContext create() {
        return switch (Platform.get()) {
            case MACOSX -> new MacOSHeadlessGL();
            case LINUX -> new LinuxHeadlessGL();
            case WINDOWS -> new WindowsHeadlessGL();
            default -> throw new UnsupportedOperationException();
        };
    }
    
    public abstract long createContext();
    public abstract void makeCurrent(long context);
    public abstract void destroy(long context);
}
```

### 2.2 macOS Implementation (CGL)

```java
public class MacOSHeadlessGL extends HeadlessGLContext {
    
    static {
        // Disable thread check for headless operation
        Configuration.GLFW_CHECK_THREAD0.set(false);
    }
    
    @Override
    public long createContext() {
        // Use CGL to create offscreen context
        long pixelFormat = CGL.CGLChoosePixelFormat(...);
        long context = CGL.CGLCreateContext(pixelFormat, 0);
        return context;
    }
}
```

### 2.3 Linux Implementation (EGL)

```java
public class LinuxHeadlessGL extends HeadlessGLContext {
    
    @Override
    public long createContext() {
        // Use EGL for headless contexts
        long display = EGL.eglGetDisplay(EGL.EGL_DEFAULT_DISPLAY);
        EGL.eglInitialize(display, ...);
        
        // Create pbuffer surface for headless
        long surface = EGL.eglCreatePbufferSurface(...);
        long context = EGL.eglCreateContext(...);
        return context;
    }
}
```

## Phase 3: OpenGL Compute Shader Test Base

### 3.1 Create GLComputeHeadlessTest

**Location**: `gpu-test-framework/src/main/java/com/hellblazer/luciferase/gpu/test/`

```java
package com.hellblazer.luciferase.gpu.test;

public abstract class GLComputeHeadlessTest extends LWJGLHeadlessTest {
    
    private HeadlessGLContext glContext;
    private long contextHandle;
    
    @BeforeEach
    protected void initializeGLContext(TestInfo testInfo) {
        super.initializeNativeLibraries(testInfo);
        
        try {
            glContext = HeadlessGLContext.create();
            contextHandle = glContext.createContext();
            glContext.makeCurrent(contextHandle);
            GL.createCapabilities();
            
            // Verify compute shader support
            int maxWorkGroupCount = glGetInteger(GL_MAX_COMPUTE_WORK_GROUP_COUNT);
            assumeTrue(maxWorkGroupCount > 0, "Compute shaders not supported");
            
        } catch (Exception e) {
            assumeTrue(false, "Failed to create headless GL context: " + e.getMessage());
        }
    }
    
    @AfterEach
    protected void cleanupGLContext() {
        if (glContext != null && contextHandle != 0) {
            glContext.destroy(contextHandle);
        }
    }
    
    protected int compileComputeShader(String source) {
        int shader = glCreateShader(GL_COMPUTE_SHADER);
        glShaderSource(shader, source);
        glCompileShader(shader);
        
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            String log = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new RuntimeException("Shader compilation failed: " + log);
        }
        
        return shader;
    }
}
```

## Phase 4: ESVO GPU Tests

### 4.1 Port Existing Tests to GPU

**Location**: `render/src/test/java/com/hellblazer/luciferase/esvo/gpu/`

```java
package com.hellblazer.luciferase.esvo.gpu;

public class ESVOGPURaycastTest extends GLComputeHeadlessTest {
    
    private ComputeShaderRenderer renderer;
    private OctreeGPUMemory octreeMemory;
    
    @BeforeEach
    void setupRenderer() {
        renderer = new ComputeShaderRenderer(256, 256);
        renderer.initialize();
        
        octreeMemory = new OctreeGPUMemory();
        octreeMemory.allocate(1024); // 1024 nodes
    }
    
    @Test
    @DisplayName("Test ESVO raycast on actual GPU")
    void testGPURaycast() {
        // Create test octree data
        OctreeNode[] testNodes = createTestOctree();
        octreeMemory.upload(testNodes);
        
        // Setup camera matrices
        Matrix4f view = createTestViewMatrix();
        Matrix4f proj = createTestProjectionMatrix();
        Matrix4f objToWorld = new Matrix4f();
        objToWorld.setIdentity();
        Matrix4f octreeToObj = CoordinateSpace.createOctreeToObjectMatrix(
            new Vector3f(0, 0, 0), 2.0f
        );
        
        // Render frame
        renderer.renderFrame(octreeMemory, view, proj, objToWorld, octreeToObj);
        
        // Read back and validate results
        FloatBuffer pixels = readbackTexture(renderer.getOutputTexture());
        validateRaycastResults(pixels);
    }
    
    private void validateRaycastResults(FloatBuffer pixels) {
        // Validate that rays hit expected voxels
        // Check for correct depth values
        // Verify shading calculations
    }
}
```

### 4.2 Performance Benchmarks

```java
public class ESVOGPUBenchmark extends GLComputeHeadlessTest {
    
    @Test
    @EnabledIf("hasRealGPU")
    void benchmarkRayTraversal() {
        // Warm-up
        for (int i = 0; i < 10; i++) {
            renderer.renderFrame(...);
        }
        
        // Benchmark
        long startTime = System.nanoTime();
        int iterations = 100;
        
        for (int i = 0; i < iterations; i++) {
            renderer.renderFrame(...);
            glFinish(); // Ensure GPU completion
        }
        
        long elapsed = System.nanoTime() - startTime;
        double fps = iterations * 1_000_000_000.0 / elapsed;
        
        log.info("GPU Performance: {:.2f} FPS at 256x256", fps);
        assertTrue(fps > 30, "Performance below acceptable threshold");
    }
}
```

## Phase 5: CI/CD Integration

### 5.1 Maven Configuration

```xml
<!-- render/pom.xml -->
<build>
  <plugins>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-surefire-plugin</artifactId>
      <configuration>
        <systemPropertyVariables>
          <java.awt.headless>true</java.awt.headless>
          <lwjgl.opencl.explicitInit>true</lwjgl.opencl.explicitInit>
        </systemPropertyVariables>
        <groups>${test.groups}</groups>
      </configuration>
      <executions>
        <!-- Default: algorithm tests (always run) -->
        <execution>
          <id>default-test</id>
          <goals><goal>test</goal></goals>
          <configuration>
            <groups>algorithm</groups>
          </configuration>
        </execution>
        
        <!-- GPU tests (optional) -->
        <execution>
          <id>gpu-test</id>
          <goals><goal>test</goal></goals>
          <configuration>
            <groups>gpu</groups>
            <skipTests>${skip.gpu.tests}</skipTests>
          </configuration>
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>

<profiles>
  <!-- Profile for running GPU tests -->
  <profile>
    <id>gpu-tests</id>
    <properties>
      <skip.gpu.tests>false</skip.gpu.tests>
    </properties>
  </profile>
</profiles>
```

### 5.2 Test Categorization

```java
public interface TestCategories {
    
    @Tag("algorithm")
    @Tag("ci")
    interface AlgorithmTests {}
    
    @Tag("gpu")
    @Tag("integration")
    interface GPUTests {}
    
    @Tag("benchmark")
    @Tag("performance")
    interface BenchmarkTests {}
}
```

### 5.3 GitHub Actions Workflow

```yaml
name: GPU Tests

on: [push, pull_request]

jobs:
  test:
    strategy:
      matrix:
        os: [ubuntu-latest, macos-latest, windows-latest]
    
    runs-on: ${{ matrix.os }}
    
    steps:
    - uses: actions/checkout@v3
    
    - name: Set up JDK 24
      uses: actions/setup-java@v3
      with:
        java-version: '24'
    
    - name: Install GPU drivers (Linux)
      if: runner.os == 'Linux'
      run: |
        sudo apt-get update
        sudo apt-get install -y mesa-utils libegl1-mesa-dev
    
    - name: Run Algorithm Tests (Always)
      run: mvn test -pl render -Dgroups=algorithm
    
    - name: Run GPU Tests (If Available)
      run: mvn test -pl render -Pqpu-tests -Dgroups=gpu
      continue-on-error: true
    
    - name: Upload Test Results
      uses: actions/upload-artifact@v3
      with:
        name: test-results-${{ matrix.os }}
        path: render/target/surefire-reports
```

## Phase 6: Validation Framework

### 6.1 Cross-Validation Tests

```java
public class ESVOCrossValidation extends CICompatibleGPUTest {
    
    @Test
    void validateOpenCLvsOpenGL() {
        // Run same algorithm in OpenCL
        float[] openclResults = runOpenCLKernel(testData);
        
        // Run in OpenGL compute shader
        float[] openglResults = runOpenGLCompute(testData);
        
        // Compare results
        for (int i = 0; i < results.length; i++) {
            assertEquals(openclResults[i], openglResults[i], 0.001f,
                "Mismatch at index " + i);
        }
    }
}
```

### 6.2 Regression Testing

```java
public class ESVORegressionTest {
    
    @ParameterizedTest
    @ValueSource(strings = {
        "test-data/octree-simple.bin",
        "test-data/octree-complex.bin",
        "test-data/octree-large.bin"
    })
    void testKnownScenes(String dataFile) {
        OctreeNode[] nodes = loadTestData(dataFile);
        float[] expectedOutput = loadExpectedOutput(dataFile + ".expected");
        
        octreeMemory.upload(nodes);
        renderer.renderFrame(...);
        
        float[] actualOutput = readbackResults();
        assertArrayEquals(expectedOutput, actualOutput, 0.001f);
    }
}
```

## Implementation Timeline

### Week 1: Foundation
- [ ] Extend gpu-test-framework with HeadlessGLContext
- [ ] Implement platform-specific context creation
- [ ] Create GLComputeHeadlessTest base class

### Week 2: OpenCL Validation
- [ ] Port ESVO ray traversal to OpenCL
- [ ] Create data structure validation tests
- [ ] Implement cross-validation framework

### Week 3: OpenGL Integration
- [ ] Adapt ComputeShaderRenderer for headless testing
- [ ] Create GPU memory management tests
- [ ] Implement shader compilation tests

### Week 4: Full Integration
- [ ] Complete test suite with all ESVO phases
- [ ] Add performance benchmarks
- [ ] CI/CD pipeline configuration

### Week 5: Documentation & Polish
- [ ] Document test architecture
- [ ] Create troubleshooting guide
- [ ] Performance baseline establishment

## Success Criteria

1. **Functional**: All ESVO algorithms validated on real GPU
2. **Performance**: Achieve >30 FPS at 256x256 resolution
3. **Coverage**: >80% code coverage for GPU components
4. **Reliability**: Tests pass on Linux, macOS, Windows
5. **CI/CD**: Automated testing in GitHub Actions

## Risk Mitigation

### Risk 1: Platform Compatibility
- **Mitigation**: Graceful fallback to OpenCL or mocks

### Risk 2: Driver Issues
- **Mitigation**: Multiple test categories with skip conditions

### Risk 3: Performance Variability
- **Mitigation**: Relative benchmarks instead of absolute

## Conclusion

This plan provides a comprehensive approach to enable real GPU testing in headless mode, leveraging the existing gpu-test-framework and extending it for OpenGL compute shaders. The three-tier strategy ensures both correctness validation and performance testing while maintaining CI/CD compatibility.