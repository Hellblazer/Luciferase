# GPU Test Framework Usage Guide

## Quick Start

### Adding to Your Project

```xml
<dependency>
    <groupId>com.hellblazer.luciferase</groupId>
    <artifactId>gpu-test-framework</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
```

### Basic GPU Test

```java
import com.hellblazer.luciferase.gpu.test.CICompatibleGPUTest;
import org.junit.jupiter.api.Test;

public class MyGPUTest extends CICompatibleGPUTest {
    
    @Test
    void testGPUComputation() {
        withGPUContext(context -> {
            // Your GPU code here
            float[] data = new float[1000];
            // Process on GPU...
            
            assertTrue(context.isValid());
        });
    }
}
```

## Framework Features

### 1. Automatic Backend Selection

The framework automatically selects the best available GPU backend:

```java
import com.hellblazer.luciferase.gpu.test.support.TestSupportMatrix;

public class AutoSelectExample {
    
    @Test
    void testWithAutoBackend() {
        var supportMatrix = new TestSupportMatrix();
        
        // Check what's available
        if (supportMatrix.getBackendSupport(TestSupportMatrix.Backend.OPENCL) 
            == TestSupportMatrix.SupportLevel.FULL) {
            // Use OpenCL
            runOpenCLTest();
        } else if (supportMatrix.getBackendSupport(TestSupportMatrix.Backend.METAL) 
            == TestSupportMatrix.SupportLevel.FULL) {
            // Use Metal (macOS)
            runMetalTest();
        } else {
            // Fallback to mock
            runMockTest();
        }
    }
}
```

### 2. CI/CD Safe Testing

Tests automatically detect CI environments and use mock implementations:

```java
public class CISafeTest extends CICompatibleGPUTest {
    
    @BeforeEach
    void checkEnvironment() {
        if (isCI()) {
            // Automatically uses mock GPU
            log.info("CI detected - using mock GPU backend");
        }
    }
    
    @Test
    void testThatWorksInCI() {
        // This test will use real GPU locally, mock in CI
        withGPUContext(context -> {
            var result = context.compute(data);
            assertNotNull(result);
        });
    }
}
```

### 3. OpenCL Testing

```java
import com.hellblazer.luciferase.gpu.test.opencl.ESVODataStructures.*;
import org.lwjgl.opencl.*;

public class OpenCLExample {
    
    @Test
    void testOpenCLRayTraversal() {
        // Create test data
        Ray[] rays = generateRays(1000);
        OctreeNode[] octree = generateOctree(8);
        
        // Create OpenCL buffers
        long rayBuffer = BufferUtils.createRayBuffer(
            context, rays, CL10.CL_MEM_READ_ONLY
        );
        
        long octreeBuffer = BufferUtils.createNodeBuffer(
            context, octree, CL10.CL_MEM_READ_ONLY
        );
        
        // Execute kernel
        CL10.clEnqueueNDRangeKernel(
            queue, kernel, 1, null,
            stackPointers(rays.length), null,
            null, null
        );
        
        // Read results
        IntersectionResult[] results = BufferUtils.readResults(
            queue, resultBuffer, rays.length
        );
        
        // Validate
        assertTrue(results.length == rays.length);
    }
}
```

### 4. Performance Benchmarking

```java
import org.openjdk.jmh.annotations.*;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class GPUBenchmark {
    
    @Benchmark
    public void benchmarkCPU() {
        processCPU(testData);
    }
    
    @Benchmark 
    public void benchmarkGPU() {
        processGPU(testData);
    }
}
```

Run benchmarks:
```bash
mvn test -Dtest=*Benchmark
```

### 5. Memory Transfer Analysis

```java
public class MemoryTransferTest {
    
    @Test
    void analyzeTransferOverhead() {
        int[] sizes = {1_000, 10_000, 100_000, 1_000_000};
        
        for (int size : sizes) {
            long cpuTime = timeCPUProcessing(size);
            long gpuTime = timeGPUProcessing(size);
            long transferTime = timeMemoryTransfer(size);
            
            double overhead = (double)transferTime / gpuTime;
            
            if (overhead < 0.1) {
                log.info("Size {}: GPU beneficial ({}x speedup)", 
                    size, cpuTime/gpuTime);
            } else {
                log.info("Size {}: CPU better (transfer overhead {}%)", 
                    size, overhead * 100);
            }
        }
    }
}
```

## Test Patterns

### Pattern 1: Cross-Validation

Ensure GPU and CPU implementations produce identical results:

```java
@Test
void testCrossValidation() {
    var input = generateTestData();
    
    var cpuResult = processCPU(input);
    var gpuResult = processGPU(input);
    
    assertArrayEquals(cpuResult, gpuResult, 1e-6f);
}
```

### Pattern 2: Graceful Degradation

```java
@Test
void testWithFallback() {
    try {
        // Try GPU first
        var result = processGPU(data);
        assertNotNull(result);
    } catch (GPUNotAvailableException e) {
        // Fallback to CPU
        var result = processCPU(data);
        assertNotNull(result);
    }
}
```

### Pattern 3: Platform-Specific Testing

```java
@Test
@EnabledOnOs(OS.MAC)
void testMetalBackend() {
    assumeTrue(metalAvailable());
    // Metal-specific test
}

@Test
@EnabledOnOs({OS.LINUX, OS.WINDOWS})
void testVulkanBackend() {
    assumeTrue(vulkanAvailable());
    // Vulkan-specific test
}
```

## Running Tests

### Basic Test Run
```bash
mvn test
```

### GPU Tests Only
```bash
mvn test -Pgpu-tests
```

### Benchmarks
```bash
mvn test -Pgpu-benchmark
```

### With Native Access (Java 21+)
```bash
mvn test -DargLine="--enable-native-access=ALL-UNNAMED"
```

### CI Environment
```bash
CI=true mvn test  # Automatically uses mock backends
```

## Debugging

### Enable Verbose Logging
```xml
<configuration>
    <logger name="com.hellblazer.luciferase.gpu" level="DEBUG"/>
</configuration>
```

### Check Platform Support
```java
var matrix = new TestSupportMatrix();
matrix.printSupportMatrix();
```

### Verify GPU Detection
```bash
mvn test -Dtest=HeadlessPlatformValidationTest
```

## Common Issues

### Issue: Tests hang on macOS
**Solution**: Add `-XstartOnFirstThread` for GLFW tests:
```bash
mvn test -DargLine="-XstartOnFirstThread"
```

### Issue: OpenCL not available
**Solution**: Install OpenCL drivers or use mock backend:
```java
@Test
void testWithMockFallback() {
    assumeTrue(openCLAvailable() || mockAvailable());
    // Test continues with available backend
}
```

### Issue: Out of GPU memory
**Solution**: Reduce data size or batch processing:
```java
var batchSize = 10000;
for (int i = 0; i < totalSize; i += batchSize) {
    processGPUBatch(data, i, Math.min(i + batchSize, totalSize));
}
```

## Best Practices

1. **Always validate GPU availability** before running GPU-specific code
2. **Provide CPU fallbacks** for critical functionality
3. **Cross-validate results** between CPU and GPU implementations
4. **Profile memory transfers** to ensure GPU benefit
5. **Use appropriate data sizes** - GPU benefits from larger workloads
6. **Clean up resources** - Always release GPU buffers and contexts
7. **Test in CI** - Ensure tests work with mock backends

## Example Projects

See the `examples/` directory for complete examples:
- `ray-tracing/` - ESVO ray traversal implementation
- `matrix-multiply/` - Basic GPU computation example
- `image-processing/` - GPU image filters
- `machine-learning/` - Neural network GPU acceleration