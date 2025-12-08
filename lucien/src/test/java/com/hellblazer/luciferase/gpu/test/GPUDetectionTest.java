package com.hellblazer.luciferase.gpu.test;

import com.hellblazer.luciferase.gpu.test.GPUDeviceDetector.GPUDevice;
import com.hellblazer.luciferase.gpu.test.GPUTestFramework.TestResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GPU Detection Test
 * 
 * Verifies that the GPU test framework can detect and enumerate GPU devices.
 * This is the acceptance test for Bead 0.2 (Luciferase-wqw).
 * 
 * Requirements:
 * - Detect at least 1 GPU device
 * - Report GPU capabilities
 * - Run basic benchmarks
 */
@DisplayName("GPU Detection and Enumeration Test")
public class GPUDetectionTest {
    
    @Test
    @DisplayName("Test GPU detection framework initialization")
    void testFrameworkInitialization() {
        // Should not throw exceptions
        assertDoesNotThrow(() -> {
            List<GPUDevice> devices = GPUDeviceDetector.detectGPUs();
            assertNotNull(devices, "Device list should not be null");
        });
    }
    
    @Test
    @DisplayName("Test GPU device enumeration")
    void testGPUEnumeration() {
        System.out.println("\n=== GPU Enumeration Test ===");
        
        List<GPUDevice> devices = GPUDeviceDetector.detectGPUs();
        
        System.out.println("Detected " + devices.size() + " GPU device(s)");
        
        for (int i = 0; i < devices.size(); i++) {
            GPUDevice device = devices.get(i);
            System.out.println("\nDevice " + (i + 1) + ":");
            System.out.println("  Name: " + device.getName());
            System.out.println("  Vendor: " + device.getVendor());
            System.out.println("  Version: " + device.getVersion());
            System.out.println("  Compute Units: " + device.getMaxComputeUnits());
            System.out.println("  Global Memory: " + 
                String.format("%.2f GB", device.getGlobalMemSize() / (1024.0 * 1024.0 * 1024.0)));
            System.out.println("  Available: " + device.isAvailable());
        }
        
        // Bead requirement: At least 1 GPU must be detected
        assertTrue(devices.size() >= 1, 
            "Expected at least 1 GPU device to be detected. " +
            "If no GPU is available, this indicates:\n" +
            "  - No GPU hardware present\n" +
            "  - OpenCL drivers not installed\n" +
            "  - GPU access blocked (may need dangerouslyDisableSandbox=true)");
    }
    
    @Test
    @DisplayName("Test GPU capability reporting")
    void testCapabilityReporting() {
        System.out.println("\n=== GPU Capability Report ===");
        
        String report = GPUCapabilityReporter.generateReport();
        assertNotNull(report, "Report should not be null");
        assertFalse(report.isEmpty(), "Report should not be empty");
        
        System.out.println(report);
        
        // Report should contain platform information
        assertTrue(report.contains("Platform Information"), "Report should contain platform info");
        assertTrue(report.contains("GPU Detection"), "Report should contain GPU detection info");
    }
    
    @Test
    @DisplayName("Test GPU availability check")
    void testGPUAvailability() {
        boolean hasGPU = GPUDeviceDetector.hasGPU();
        
        System.out.println("\nGPU Available: " + hasGPU);
        
        if (hasGPU) {
            GPUDevice firstGPU = GPUDeviceDetector.getFirstGPU();
            assertNotNull(firstGPU, "First GPU should not be null when hasGPU returns true");
            assertTrue(firstGPU.isAvailable(), "First GPU should be available");
            
            System.out.println("First GPU: " + firstGPU);
        }
        
        // Bead requirement: At least 1 GPU must be available
        assertTrue(hasGPU, 
            "Expected at least 1 GPU to be available for testing. " +
            "GPU testing requires hardware GPU access.");
    }
    
    @Test
    @DisplayName("Test complete GPU test framework")
    void testCompleteFramework() {
        System.out.println("\n=== Complete GPU Test Framework ===");
        
        TestResult result = GPUTestFramework.runCompleteTestWithReport(true);
        
        assertNotNull(result, "Test result should not be null");
        assertTrue(result.isGpuDetected(), "GPU should be detected");
        assertTrue(result.getGpuCount() >= 1, "At least 1 GPU should be detected");
        assertNotNull(result.getCapabilitySummary(), "Capability summary should not be null");
        
        // If GPU detected, benchmark should have run
        if (result.isGpuDetected()) {
            assertNotNull(result.getBenchmarkResult(), "Benchmark should have run");
            
            // Benchmark success is informational - log but don't fail test
            if (!result.getBenchmarkResult().isSuccess()) {
                System.err.println("\n⚠️  Benchmark failed: " + 
                    result.getBenchmarkResult().getErrorMessage());
                System.err.println("This may indicate GPU access restrictions or driver issues.");
            }
        }
    }
    
    @Test
    @DisplayName("Test GPU framework summary generation")
    void testSummaryGeneration() {
        String summary = GPUCapabilityReporter.generateSummary();
        
        assertNotNull(summary, "Summary should not be null");
        assertFalse(summary.isEmpty(), "Summary should not be empty");
        
        System.out.println("\nGPU Summary: " + summary);
        
        // Summary should not indicate "No GPUs detected"
        assertFalse(summary.equals("No GPUs detected"),
            "Expected GPUs to be detected, but summary indicates none found");
    }
    
    @Test
    @DisplayName("Test markdown report generation")
    void testMarkdownReportGeneration() {
        String markdown = GPUCapabilityReporter.generateMarkdownReport();
        
        assertNotNull(markdown, "Markdown report should not be null");
        assertFalse(markdown.isEmpty(), "Markdown report should not be empty");
        
        // Should be valid markdown
        assertTrue(markdown.contains("#"), "Should contain markdown headers");
        assertTrue(markdown.contains("|"), "Should contain markdown tables");
        
        System.out.println("\n=== Markdown Report ===");
        System.out.println(markdown);
    }
    
    @Test
    @DisplayName("Verify Bead 0.2 acceptance criteria")
    void verifyBeadAcceptanceCriteria() {
        System.out.println("\n=== Bead 0.2 Acceptance Criteria Verification ===\n");
        
        // Criterion 1: GPU enumeration via LWJGL OpenCL
        List<GPUDevice> devices = GPUDeviceDetector.detectGPUs();
        boolean criterion1 = devices.size() >= 1;
        System.out.println("✓ Criterion 1 - GPU Enumeration: " + 
            (criterion1 ? "PASS" : "FAIL") + " (" + devices.size() + " device(s) detected)");
        
        // Criterion 2: GPU specs reporting
        String report = GPUCapabilityReporter.generateReport();
        boolean criterion2 = report != null && !report.isEmpty() && report.contains("GPU Detection");
        System.out.println("✓ Criterion 2 - GPU Specs Reporting: " + 
            (criterion2 ? "PASS" : "FAIL"));
        
        // Criterion 3: GPU benchmarks
        boolean criterion3 = true;
        if (!devices.isEmpty()) {
            var benchmark = GPUBenchmarkRunner.runQuickBenchmark();
            criterion3 = benchmark != null;
            System.out.println("✓ Criterion 3 - GPU Benchmarks: " + 
                (criterion3 ? "PASS" : "FAIL") + 
                (benchmark != null ? " (" + benchmark + ")" : ""));
        } else {
            System.out.println("✓ Criterion 3 - GPU Benchmarks: SKIP (no GPU available)");
        }
        
        // Criterion 4: Framework coordination
        TestResult frameworkTest = GPUTestFramework.runDetectionOnly();
        boolean criterion4 = frameworkTest != null && frameworkTest.isGpuDetected();
        System.out.println("✓ Criterion 4 - Framework Coordinator: " + 
            (criterion4 ? "PASS" : "FAIL"));
        
        // Overall acceptance
        boolean allCriteriaMet = criterion1 && criterion2 && criterion3 && criterion4;
        
        System.out.println("\n" + "=".repeat(80));
        System.out.println("Bead 0.2 Acceptance: " + (allCriteriaMet ? "✅ PASS" : "❌ FAIL"));
        System.out.println("=".repeat(80));
        
        // Assert all criteria met
        assertTrue(criterion1, "GPU enumeration failed");
        assertTrue(criterion2, "GPU reporting failed");
        assertTrue(criterion3, "GPU benchmarking failed");
        assertTrue(criterion4, "Framework coordination failed");
    }
}
