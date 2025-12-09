package com.hellblazer.luciferase.gpu.test;

import com.hellblazer.luciferase.gpu.test.GPUDeviceDetector.GPUDevice;

import java.util.List;

/**
 * GPU Capability Reporter
 * 
 * Generates detailed reports about GPU capabilities for testing and benchmarking.
 * Provides formatted output for test results, documentation, and diagnostics.
 */
public final class GPUCapabilityReporter {
    
    private GPUCapabilityReporter() {
        // Utility class
    }
    
    /**
     * Generate a comprehensive GPU capability report
     * 
     * @return Formatted report as multi-line string
     */
    public static String generateReport() {
        StringBuilder report = new StringBuilder();
        
        report.append("=".repeat(80)).append("\n");
        report.append("GPU CAPABILITY REPORT\n");
        report.append("=".repeat(80)).append("\n\n");
        
        // Platform information
        report.append("Platform Information:\n");
        report.append("-".repeat(80)).append("\n");
        report.append(String.format("  Platform: %s\n", PlatformTestSupport.getPlatformName()));
        report.append(String.format("  Architecture: %s\n", PlatformTestSupport.getArchitecture()));
        report.append(String.format("  Java Version: %s\n", System.getProperty("java.version")));
        report.append(String.format("  OS: %s %s\n", System.getProperty("os.name"), System.getProperty("os.version")));
        report.append(String.format("  CI Environment: %s\n", PlatformTestSupport.isCI()));
        
        if ("macosx".equalsIgnoreCase(PlatformTestSupport.getPlatformName())) {
            report.append(String.format("  -XstartOnFirstThread: %s\n", 
                PlatformTestSupport.hasStartOnFirstThread()));
        }
        report.append("\n");
        
        // GPU detection
        report.append("GPU Detection:\n");
        report.append("-".repeat(80)).append("\n");
        
        List<GPUDevice> devices = GPUDeviceDetector.detectGPUs();
        
        if (devices.isEmpty()) {
            report.append("  ⚠️  No GPU devices detected\n");
            report.append("  This may indicate:\n");
            report.append("    - No GPU hardware available\n");
            report.append("    - OpenCL drivers not installed\n");
            report.append("    - GPU access blocked by sandbox (use dangerouslyDisableSandbox=true)\n");
        } else {
            report.append(String.format("  ✅ Found %d GPU device(s)\n\n", devices.size()));
            
            for (int i = 0; i < devices.size(); i++) {
                GPUDevice device = devices.get(i);
                report.append(formatDeviceInfo(i + 1, device));
                report.append("\n");
            }
        }
        
        report.append("=".repeat(80)).append("\n");
        
        return report.toString();
    }
    
    /**
     * Generate a concise summary of GPU capabilities
     * 
     * @return Single-line summary
     */
    public static String generateSummary() {
        List<GPUDevice> devices = GPUDeviceDetector.detectGPUs();
        
        if (devices.isEmpty()) {
            return "No GPUs detected";
        }
        
        long totalComputeUnits = devices.stream()
            .mapToInt(GPUDevice::getMaxComputeUnits)
            .sum();
        
        long totalMemory = devices.stream()
            .mapToLong(GPUDevice::getGlobalMemSize)
            .sum();
        
        return String.format("%d GPU(s) | %d CUs total | %.2f GB total RAM",
            devices.size(), totalComputeUnits, totalMemory / (1024.0 * 1024.0 * 1024.0));
    }
    
    /**
     * Check if system meets minimum GPU requirements for testing
     * 
     * @param minComputeUnits Minimum compute units required
     * @param minMemoryGB Minimum memory in GB required
     * @return true if requirements met
     */
    public static boolean meetsRequirements(int minComputeUnits, double minMemoryGB) {
        GPUDevice gpu = GPUDeviceDetector.getFirstGPU();
        
        if (gpu == null) {
            return false;
        }
        
        boolean hasEnoughCUs = gpu.getMaxComputeUnits() >= minComputeUnits;
        double memoryGB = gpu.getGlobalMemSize() / (1024.0 * 1024.0 * 1024.0);
        boolean hasEnoughMemory = memoryGB >= minMemoryGB;
        
        return hasEnoughCUs && hasEnoughMemory;
    }
    
    /**
     * Generate a markdown-formatted report suitable for documentation
     * 
     * @return Markdown report
     */
    public static String generateMarkdownReport() {
        StringBuilder md = new StringBuilder();
        
        md.append("# GPU Capability Report\n\n");
        
        // Platform section
        md.append("## Platform Information\n\n");
        md.append("| Property | Value |\n");
        md.append("|----------|-------|\n");
        md.append(String.format("| Platform | %s |\n", PlatformTestSupport.getPlatformName()));
        md.append(String.format("| Architecture | %s |\n", PlatformTestSupport.getArchitecture()));
        md.append(String.format("| Java Version | %s |\n", System.getProperty("java.version")));
        md.append(String.format("| OS | %s %s |\n", System.getProperty("os.name"), System.getProperty("os.version")));
        md.append(String.format("| CI Environment | %s |\n\n", PlatformTestSupport.isCI()));
        
        // GPU section
        md.append("## Detected GPU Devices\n\n");
        
        List<GPUDevice> devices = GPUDeviceDetector.detectGPUs();
        
        if (devices.isEmpty()) {
            md.append("⚠️ **No GPU devices detected**\n\n");
        } else {
            md.append(String.format("**Total Devices:** %d\n\n", devices.size()));
            
            for (int i = 0; i < devices.size(); i++) {
                GPUDevice device = devices.get(i);
                md.append(String.format("### Device %d: %s\n\n", i + 1, device.getName()));
                md.append("| Property | Value |\n");
                md.append("|----------|-------|\n");
                md.append(String.format("| Vendor | %s |\n", device.getVendor()));
                md.append(String.format("| OpenCL Version | %s |\n", device.getVersion()));
                md.append(String.format("| Compute Units | %d |\n", device.getMaxComputeUnits()));
                md.append(String.format("| Global Memory | %.2f GB |\n", 
                    device.getGlobalMemSize() / (1024.0 * 1024.0 * 1024.0)));
                md.append(String.format("| Local Memory | %.2f KB |\n", 
                    device.getLocalMemSize() / 1024.0));
                md.append(String.format("| Max Work Group Size | %d |\n", device.getMaxWorkGroupSize()));
                md.append(String.format("| Available | %s |\n\n", device.isAvailable() ? "✅ Yes" : "❌ No"));
            }
        }
        
        return md.toString();
    }
    
    /**
     * Print report to console (for tests)
     */
    public static void printReport() {
        System.out.println(generateReport());
    }
    
    /**
     * Print summary to console (for tests)
     */
    public static void printSummary() {
        System.out.println("GPU Summary: " + generateSummary());
    }
    
    // === Private Helper Methods ===
    
    private static String formatDeviceInfo(int index, GPUDevice device) {
        StringBuilder info = new StringBuilder();
        
        info.append(String.format("  Device %d:\n", index));
        info.append(String.format("    Name: %s\n", device.getName()));
        info.append(String.format("    Vendor: %s\n", device.getVendor()));
        info.append(String.format("    OpenCL Version: %s\n", device.getVersion()));
        info.append(String.format("    Compute Units: %d\n", device.getMaxComputeUnits()));
        info.append(String.format("    Global Memory: %.2f GB\n", 
            device.getGlobalMemSize() / (1024.0 * 1024.0 * 1024.0)));
        info.append(String.format("    Local Memory: %.2f KB\n", 
            device.getLocalMemSize() / 1024.0));
        info.append(String.format("    Max Work Group Size: %d\n", device.getMaxWorkGroupSize()));
        info.append(String.format("    Available: %s\n", device.isAvailable() ? "Yes" : "No"));
        
        return info.toString();
    }
}
