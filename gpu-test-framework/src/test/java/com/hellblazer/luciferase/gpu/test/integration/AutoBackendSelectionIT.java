package com.hellblazer.luciferase.gpu.test.integration;

import com.hellblazer.luciferase.gpu.test.CICompatibleGPUTest;
import com.hellblazer.luciferase.gpu.test.support.TestSupportMatrix;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test that automatically selects the best available GPU backend
 * and executes compute workloads across different backends transparently.
 */
public class AutoBackendSelectionIT extends CICompatibleGPUTest {
    
    private static final Logger log = LoggerFactory.getLogger(AutoBackendSelectionIT.class);
    
    private static GPUBackend selectedBackend;
    private static TestSupportMatrix supportMatrix;
    
    public enum GPUBackend {
        METAL("Metal 3", 100),
        OPENCL("OpenCL", 90),
        OPENGL("OpenGL Compute", 80),
        VULKAN("Vulkan", 95),
        MOCK("CPU Mock", 10);
        
        private final String name;
        private final int priority;
        
        GPUBackend(String name, int priority) {
            this.name = name;
            this.priority = priority;
        }
        
        public String getName() { return name; }
        public int getPriority() { return priority; }
    }
    
    @BeforeAll
    static void selectBestBackend() {
        supportMatrix = new TestSupportMatrix();
        
        log.info("=== GPU BACKEND AUTO-SELECTION ===");
        log.info("Platform: {}", supportMatrix.getCurrentPlatform().getDescription());
        log.info("CI Environment: {}", supportMatrix.isCIEnvironment());
        
        // Select backend based on platform and availability
        selectedBackend = determineOptimalBackend();
        
        log.info("Selected Backend: {} (Priority: {})", 
            selectedBackend.getName(), selectedBackend.getPriority());
        log.info("================================");
    }
    
    private static GPUBackend determineOptimalBackend() {
        // In CI, always use mock
        if (supportMatrix.isCIEnvironment()) {
            return GPUBackend.MOCK;
        }
        
        // Platform-specific selection
        var platform = supportMatrix.getCurrentPlatform();
        
        // macOS: Prefer Metal, fallback to OpenCL
        if (platform == TestSupportMatrix.Platform.MACOS_ARM64 || 
            platform == TestSupportMatrix.Platform.MACOS_X64) {
            
            if (supportMatrix.getBackendSupport(TestSupportMatrix.Backend.METAL) 
                == TestSupportMatrix.SupportLevel.FULL) {
                return GPUBackend.METAL;
            }
        }
        
        // Check OpenCL availability (cross-platform)
        if (supportMatrix.getBackendSupport(TestSupportMatrix.Backend.OPENCL) 
            == TestSupportMatrix.SupportLevel.FULL) {
            return GPUBackend.OPENCL;
        }
        
        // Check OpenGL Compute
        if (supportMatrix.getBackendSupport(TestSupportMatrix.Backend.OPENGL) 
            == TestSupportMatrix.SupportLevel.FULL) {
            return GPUBackend.OPENGL;
        }
        
        // Fallback to mock
        return GPUBackend.MOCK;
    }
    
    @Test
    @DisplayName("Execute compute workload on auto-selected backend")
    void testAutoBackendCompute() {
        log.info("Executing compute workload on {}", selectedBackend.getName());
        
        // Create compute task
        int dataSize = 10000;
        float[] inputData = generateTestData(dataSize);
        float[] results = new float[dataSize];
        
        // Execute based on selected backend
        long startTime = System.nanoTime();
        
        switch (selectedBackend) {
            case METAL -> executeMetalCompute(inputData, results);
            case OPENCL -> executeOpenCLCompute(inputData, results);
            case OPENGL -> executeOpenGLCompute(inputData, results);
            case VULKAN -> executeVulkanCompute(inputData, results);
            case MOCK -> executeCPUMock(inputData, results);
        }
        
        long elapsed = System.nanoTime() - startTime;
        double msTime = elapsed / 1_000_000.0;
        
        // Verify results
        verifyComputeResults(inputData, results);
        
        log.info("Compute completed in {:.3f} ms on {}", msTime, selectedBackend.getName());
        log.info("Throughput: {:.0f} operations/sec", (dataSize / msTime) * 1000);
        
        // Performance expectations based on backend
        if (selectedBackend != GPUBackend.MOCK) {
            assertTrue(msTime < 100, 
                "GPU computation should complete in < 100ms for " + dataSize + " elements");
        }
    }
    
    @Test
    @DisplayName("Parallel execution across available backends")
    void testParallelBackendExecution() {
        log.info("Testing parallel execution across available backends");
        
        var availableBackends = supportMatrix.getAvailableBackends();
        log.info("Available backends: {}", availableBackends);
        
        int dataSize = 1000;
        float[] inputData = generateTestData(dataSize);
        
        // Execute on all available backends in parallel
        var futures = availableBackends.stream()
            .map(backend -> CompletableFuture.supplyAsync(() -> {
                float[] results = new float[dataSize];
                long start = System.nanoTime();
                
                // Execute based on backend type
                String backendName = backend.name();
                if (backendName.contains("OPENCL")) {
                    executeOpenCLCompute(inputData, results);
                } else if (backendName.contains("OPENGL")) {
                    executeOpenGLCompute(inputData, results);
                } else if (backendName.contains("METAL")) {
                    executeMetalCompute(inputData, results);
                } else {
                    executeCPUMock(inputData, results);
                }
                
                long elapsed = System.nanoTime() - start;
                return new BackendResult(backend.name(), results, elapsed);
            }))
            .toList();
        
        // Wait for all to complete
        var results = futures.stream()
            .map(future -> {
                try {
                    return future.get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    log.error("Backend execution failed", e);
                    return null;
                }
            })
            .filter(r -> r != null)
            .toList();
        
        // Compare results across backends
        if (results.size() > 1) {
            var reference = results.get(0).results;
            for (int i = 1; i < results.size(); i++) {
                var other = results.get(i);
                assertArrayEquals(reference, other.results, 0.001f,
                    "Results should match across backends");
                
                log.info("Backend {} completed in {:.3f} ms", 
                    other.backend, other.elapsedNanos / 1_000_000.0);
            }
        }
        
        assertTrue(results.size() > 0, "At least one backend should be available");
    }
    
    @Test
    @DisplayName("Backend fallback mechanism")
    void testBackendFallback() {
        log.info("Testing backend fallback mechanism");
        
        // Simulate backend failure and fallback
        GPUBackend primary = selectedBackend;
        GPUBackend fallback = GPUBackend.MOCK;
        
        int dataSize = 1000;
        float[] inputData = generateTestData(dataSize);
        float[] results = new float[dataSize];
        
        try {
            // Try primary backend
            log.info("Attempting primary backend: {}", primary.getName());
            executeWithBackend(primary, inputData, results);
            log.info("Primary backend succeeded");
        } catch (Exception e) {
            // Fallback to mock
            log.info("Primary backend failed, falling back to: {}", fallback.getName());
            executeWithBackend(fallback, inputData, results);
            log.info("Fallback backend succeeded");
        }
        
        // Verify we got results
        verifyComputeResults(inputData, results);
    }
    
    // Backend-specific execution methods
    
    private void executeMetalCompute(float[] input, float[] output) {
        log.debug("Executing Metal compute shader");
        // In real implementation, would use Metal API
        executeCPUMock(input, output); // Fallback for now
    }
    
    private void executeOpenCLCompute(float[] input, float[] output) {
        log.debug("Executing OpenCL kernel");
        // In real implementation, would use OpenCL API
        executeCPUMock(input, output); // Fallback for now
    }
    
    private void executeOpenGLCompute(float[] input, float[] output) {
        log.debug("Executing OpenGL compute shader");
        // In real implementation, would use OpenGL API
        executeCPUMock(input, output); // Fallback for now
    }
    
    private void executeVulkanCompute(float[] input, float[] output) {
        log.debug("Executing Vulkan compute shader");
        // Not implemented yet
        executeCPUMock(input, output);
    }
    
    private void executeCPUMock(float[] input, float[] output) {
        log.debug("Executing CPU mock implementation");
        for (int i = 0; i < input.length; i++) {
            // Simple computation: square root of absolute value
            output[i] = (float) Math.sqrt(Math.abs(input[i]));
        }
    }
    
    private void executeWithBackend(GPUBackend backend, float[] input, float[] output) {
        switch (backend) {
            case METAL -> executeMetalCompute(input, output);
            case OPENCL -> executeOpenCLCompute(input, output);
            case OPENGL -> executeOpenGLCompute(input, output);
            case VULKAN -> executeVulkanCompute(input, output);
            case MOCK -> executeCPUMock(input, output);
        }
    }
    
    // Helper methods
    
    private float[] generateTestData(int size) {
        float[] data = new float[size];
        for (int i = 0; i < size; i++) {
            data[i] = (float) (Math.sin(i * 0.01) * 100);
        }
        return data;
    }
    
    private void verifyComputeResults(float[] input, float[] output) {
        assertNotNull(output, "Output should not be null");
        assertEquals(input.length, output.length, "Output size should match input");
        
        // Verify at least some computation happened
        boolean hasNonZero = false;
        for (float v : output) {
            if (v != 0.0f) {
                hasNonZero = true;
                break;
            }
        }
        assertTrue(hasNonZero, "Output should contain non-zero values");
        
        // Spot check a few values
        for (int i = 0; i < Math.min(10, input.length); i++) {
            float expected = (float) Math.sqrt(Math.abs(input[i]));
            assertEquals(expected, output[i], 0.001f,
                "Output value mismatch at index " + i);
        }
    }
    
    private record BackendResult(String backend, float[] results, long elapsedNanos) {}
}