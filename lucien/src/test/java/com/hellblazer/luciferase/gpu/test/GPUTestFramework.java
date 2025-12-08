package com.hellblazer.luciferase.gpu.test;

import com.hellblazer.luciferase.gpu.test.GPUBenchmarkRunner.BenchmarkResult;
import com.hellblazer.luciferase.gpu.test.GPUDeviceDetector.GPUDevice;

import java.util.List;

/**
 * GPU Test Framework Coordinator
 * 
 * Provides a unified interface for GPU testing functionality.
 * Coordinates detection, capability reporting, and benchmarking.
 */
public final class GPUTestFramework {
    
    /**
     * Complete test result including detection, capabilities, and benchmark
     */
    public static class TestResult {
        private final boolean gpuDetected;
        private final int gpuCount;
        private final String capabilitySummary;
        private final BenchmarkResult benchmarkResult;
        
        TestResult(boolean gpuDetected, int gpuCount, String capabilitySummary, 
                   BenchmarkResult benchmarkResult) {
            this.gpuDetected = gpuDetected;
            this.gpuCount = gpuCount;
            this.capabilitySummary = capabilitySummary;
            this.benchmarkResult = benchmarkResult;
        }
        
        public boolean isGpuDetected() { return gpuDetected; }
        public int getGpuCount() { return gpuCount; }
        public String getCapabilitySummary() { return capabilitySummary; }
        public BenchmarkResult getBenchmarkResult() { return benchmarkResult; }
        
        public boolean isFullyFunctional() {
            return gpuDetected && benchmarkResult != null && benchmarkResult.isSuccess();
        }
        
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("GPU Test Result:\n");
            sb.append(String.format("  GPU Detected: %s\n", gpuDetected));
            sb.append(String.format("  GPU Count: %d\n", gpuCount));
            sb.append(String.format("  Capabilities: %s\n", capabilitySummary));
            if (benchmarkResult != null) {
                sb.append(String.format("  Benchmark: %s\n", benchmarkResult));
            }
            sb.append(String.format("  Fully Functional: %s", isFullyFunctional()));
            return sb.toString();
        }
    }
    
    private GPUTestFramework() {
        // Utility class
    }
    
    /**
     * Run a complete GPU test suite
     * 
     * Performs detection, capability reporting, and a quick benchmark.
     * 
     * @return Complete test result
     */
    public static TestResult runCompleteTest() {
        // Detect GPUs
        List<GPUDevice> devices = GPUDeviceDetector.detectGPUs();
        boolean detected = !devices.isEmpty();
        int count = devices.size();
        
        // Generate capability summary
        String summary = GPUCapabilityReporter.generateSummary();
        
        // Run quick benchmark if GPU available
        BenchmarkResult benchmark = null;
        if (detected) {
            benchmark = GPUBenchmarkRunner.runQuickBenchmark();
        }
        
        return new TestResult(detected, count, summary, benchmark);
    }
    
    /**
     * Run a complete GPU test with detailed reporting
     * 
     * @param printReport If true, prints full report to console
     * @return Complete test result
     */
    public static TestResult runCompleteTestWithReport(boolean printReport) {
        if (printReport) {
            System.out.println("\n" + "=".repeat(80));
            System.out.println("RUNNING COMPLETE GPU TEST SUITE");
            System.out.println("=".repeat(80));
            System.out.println();
            
            PlatformTestSupport.printPlatformInfo();
            System.out.println();
        }
        
        TestResult result = runCompleteTest();
        
        if (printReport) {
            System.out.println("\n" + "=".repeat(80));
            System.out.println("GPU TEST RESULTS");
            System.out.println("=".repeat(80));
            System.out.println(result);
            System.out.println("=".repeat(80) + "\n");
        }
        
        return result;
    }
    
    /**
     * Run detection only (no benchmark)
     * 
     * @return Test result with detection info only
     */
    public static TestResult runDetectionOnly() {
        List<GPUDevice> devices = GPUDeviceDetector.detectGPUs();
        boolean detected = !devices.isEmpty();
        int count = devices.size();
        String summary = GPUCapabilityReporter.generateSummary();
        
        return new TestResult(detected, count, summary, null);
    }
    
    /**
     * Check if GPU testing is available on this system
     * 
     * @return true if at least one GPU is detected and functional
     */
    public static boolean isGPUTestingAvailable() {
        return GPUDeviceDetector.hasGPU();
    }
    
    /**
     * Get a detailed report of GPU capabilities
     * 
     * @return Multi-line formatted report
     */
    public static String getDetailedReport() {
        return GPUCapabilityReporter.generateReport();
    }
    
    /**
     * Get a markdown-formatted report suitable for documentation
     * 
     * @return Markdown report
     */
    public static String getMarkdownReport() {
        return GPUCapabilityReporter.generateMarkdownReport();
    }
    
    /**
     * Run a standard benchmark (1M elements)
     * 
     * @return Benchmark result or null if no GPU available
     */
    public static BenchmarkResult runStandardBenchmark() {
        if (!isGPUTestingAvailable()) {
            return null;
        }
        return GPUBenchmarkRunner.runStandardBenchmark();
    }
    
    /**
     * Run a comprehensive test suite with multiple benchmark sizes
     * 
     * @return Array of benchmark results [quick, standard, large]
     */
    public static BenchmarkResult[] runComprehensiveBenchmarks() {
        if (!isGPUTestingAvailable()) {
            return new BenchmarkResult[0];
        }
        
        return new BenchmarkResult[] {
            GPUBenchmarkRunner.runQuickBenchmark(),
            GPUBenchmarkRunner.runStandardBenchmark(),
            GPUBenchmarkRunner.runLargeBenchmark()
        };
    }
    
    /**
     * Check if system meets minimum requirements for GPU testing
     * 
     * @param minComputeUnits Minimum compute units required
     * @param minMemoryGB Minimum GPU memory in GB required
     * @return true if requirements met
     */
    public static boolean meetsMinimumRequirements(int minComputeUnits, double minMemoryGB) {
        return GPUCapabilityReporter.meetsRequirements(minComputeUnits, minMemoryGB);
    }
    
    /**
     * Get all detected GPU devices
     * 
     * @return List of GPU devices (may be empty)
     */
    public static List<GPUDevice> getDetectedGPUs() {
        return GPUDeviceDetector.detectGPUs();
    }
    
    /**
     * Get the first available GPU
     * 
     * @return First GPU or null if none available
     */
    public static GPUDevice getFirstGPU() {
        return GPUDeviceDetector.getFirstGPU();
    }
}
