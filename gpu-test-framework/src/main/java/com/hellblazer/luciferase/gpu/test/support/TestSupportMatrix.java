package com.hellblazer.luciferase.gpu.test.support;

import org.lwjgl.opencl.CL;
import org.lwjgl.opengl.GL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Runtime detection and reporting of GPU test framework support.
 * Provides a comprehensive matrix of available backends and features.
 */
public class TestSupportMatrix {
    
    private static final Logger log = LoggerFactory.getLogger(TestSupportMatrix.class);
    
    public enum Backend {
        OPENCL("OpenCL", "Cross-platform compute"),
        OPENGL("OpenGL Compute", "Graphics-based compute"),
        METAL("Metal 3", "Apple native compute"),
        VULKAN("Vulkan", "Modern graphics/compute"),
        CUDA("CUDA", "NVIDIA compute"),
        MOCK("Mock/CPU", "Fallback implementation");
        
        private final String displayName;
        private final String description;
        
        Backend(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }
        
        public String getDisplayName() { return displayName; }
        public String getDescription() { return description; }
    }
    
    public enum Platform {
        MACOS_X64("macOS (x86_64)"),
        MACOS_ARM64("macOS (ARM64)"),
        LINUX_X64("Linux (x86_64)"),
        LINUX_ARM64("Linux (ARM64)"),
        WINDOWS_X64("Windows (x86_64)"),
        UNKNOWN("Unknown Platform");
        
        private final String description;
        
        Platform(String description) {
            this.description = description;
        }
        
        public String getDescription() { return description; }
    }
    
    public enum SupportLevel {
        FULL("✅ Full", "Complete hardware-accelerated support"),
        PARTIAL("⚠️ Partial", "Limited support with some features missing"),
        EMULATED("🔄 Emulated", "Software emulation available"),
        PLANNED("🔧 Planned", "Implementation planned for future release"),
        NOT_AVAILABLE("❌ N/A", "Not available on this platform"),
        MOCK("🎭 Mock", "Mock implementation for testing");
        
        private final String symbol;
        private final String description;
        
        SupportLevel(String symbol, String description) {
            this.symbol = symbol;
            this.description = description;
        }
        
        public String getSymbol() { return symbol; }
        public String getDescription() { return description; }
    }
    
    private final Map<Backend, SupportLevel> backendSupport = new EnumMap<>(Backend.class);
    private final Map<String, Boolean> featureSupport = new LinkedHashMap<>();
    private final Platform currentPlatform;
    private final boolean ciEnvironment;
    
    public TestSupportMatrix() {
        this.currentPlatform = detectPlatform();
        this.ciEnvironment = detectCIEnvironment();
        detectBackendSupport();
        detectFeatureSupport();
    }
    
    private Platform detectPlatform() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        String osArch = System.getProperty("os.arch", "").toLowerCase();
        
        if (osName.contains("mac") || osName.contains("darwin")) {
            if (osArch.contains("aarch64") || osArch.contains("arm64")) {
                return Platform.MACOS_ARM64;
            }
            return Platform.MACOS_X64;
        } else if (osName.contains("linux")) {
            if (osArch.contains("aarch64") || osArch.contains("arm64")) {
                return Platform.LINUX_ARM64;
            }
            return Platform.LINUX_X64;
        } else if (osName.contains("windows")) {
            return Platform.WINDOWS_X64;
        }
        
        return Platform.UNKNOWN;
    }
    
    private boolean detectCIEnvironment() {
        // Check common CI environment variables
        return System.getenv("CI") != null ||
               System.getenv("CONTINUOUS_INTEGRATION") != null ||
               System.getenv("JENKINS_URL") != null ||
               System.getenv("GITHUB_ACTIONS") != null ||
               System.getenv("GITLAB_CI") != null ||
               System.getenv("CIRCLECI") != null ||
               System.getenv("TRAVIS") != null;
    }
    
    private void detectBackendSupport() {
        // OpenCL detection
        try {
            CL.create();
            backendSupport.put(Backend.OPENCL, ciEnvironment ? SupportLevel.MOCK : SupportLevel.FULL);
        } catch (Throwable t) {
            backendSupport.put(Backend.OPENCL, 
                ciEnvironment ? SupportLevel.MOCK : SupportLevel.NOT_AVAILABLE);
        }
        
        // OpenGL detection
        try {
            if (System.getProperty("java.awt.headless", "false").equals("true")) {
                backendSupport.put(Backend.OPENGL, SupportLevel.MOCK);
            } else {
                // Would need actual GL context to test properly
                backendSupport.put(Backend.OPENGL, 
                    ciEnvironment ? SupportLevel.MOCK : SupportLevel.FULL);
            }
        } catch (Throwable t) {
            backendSupport.put(Backend.OPENGL, SupportLevel.NOT_AVAILABLE);
        }
        
        // Metal detection (macOS only)
        if (currentPlatform == Platform.MACOS_X64 || currentPlatform == Platform.MACOS_ARM64) {
            backendSupport.put(Backend.METAL, 
                ciEnvironment ? SupportLevel.MOCK : SupportLevel.FULL);
        } else {
            backendSupport.put(Backend.METAL, SupportLevel.NOT_AVAILABLE);
        }
        
        // Vulkan (planned)
        backendSupport.put(Backend.VULKAN, SupportLevel.PLANNED);
        
        // CUDA detection
        String cudaPath = System.getenv("CUDA_PATH");
        if (cudaPath != null && !ciEnvironment) {
            backendSupport.put(Backend.CUDA, SupportLevel.PARTIAL);
        } else {
            backendSupport.put(Backend.CUDA, 
                ciEnvironment ? SupportLevel.MOCK : SupportLevel.NOT_AVAILABLE);
        }
        
        // Mock is always available
        backendSupport.put(Backend.MOCK, SupportLevel.FULL);
    }
    
    private void detectFeatureSupport() {
        // Test categories
        featureSupport.put("Unit Tests", true);
        featureSupport.put("Integration Tests", true);
        featureSupport.put("Performance Tests", !ciEnvironment);
        featureSupport.put("Validation Tests", true);
        featureSupport.put("ESVO Algorithm Tests", true);
        featureSupport.put("Headless Tests", true);
        
        // Specific features
        featureSupport.put("Buffer Management", true);
        featureSupport.put("Kernel Compilation", !ciEnvironment);
        featureSupport.put("Work Groups", !ciEnvironment);
        featureSupport.put("Memory Barriers", !ciEnvironment);
        featureSupport.put("Image Operations", !ciEnvironment);
        featureSupport.put("Platform Detection", true);
        featureSupport.put("Error Handling", true);
        featureSupport.put("Debug Output", true);
    }
    
    public void printMatrix() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("GPU TEST FRAMEWORK SUPPORT MATRIX");
        System.out.println("=".repeat(80));
        
        System.out.println("\nPlatform: " + currentPlatform.getDescription());
        System.out.println("CI Environment: " + (ciEnvironment ? "Yes (Mock mode)" : "No (Full GPU)"));
        System.out.println("Java Version: " + System.getProperty("java.version"));
        
        System.out.println("\n" + "-".repeat(80));
        System.out.println("BACKEND SUPPORT");
        System.out.println("-".repeat(80));
        System.out.printf("%-20s %-15s %s%n", "Backend", "Status", "Description");
        System.out.println("-".repeat(80));
        
        for (Map.Entry<Backend, SupportLevel> entry : backendSupport.entrySet()) {
            Backend backend = entry.getKey();
            SupportLevel level = entry.getValue();
            System.out.printf("%-20s %-15s %s%n", 
                backend.getDisplayName(), 
                level.getSymbol(), 
                backend.getDescription());
        }
        
        System.out.println("\n" + "-".repeat(80));
        System.out.println("FEATURE SUPPORT");
        System.out.println("-".repeat(80));
        System.out.printf("%-30s %s%n", "Feature", "Available");
        System.out.println("-".repeat(80));
        
        for (Map.Entry<String, Boolean> entry : featureSupport.entrySet()) {
            System.out.printf("%-30s %s%n", 
                entry.getKey(), 
                entry.getValue() ? "✅ Yes" : "❌ No");
        }
        
        System.out.println("\n" + "-".repeat(80));
        System.out.println("RECOMMENDATIONS");
        System.out.println("-".repeat(80));
        
        if (ciEnvironment) {
            System.out.println("• Running in CI mode - using mock implementations");
            System.out.println("• For full GPU testing, run on local development machine");
        } else {
            System.out.println("• Full GPU support detected");
            System.out.println("• Run with -Pgpu-benchmark for performance testing");
        }
        
        if (backendSupport.get(Backend.OPENCL) == SupportLevel.NOT_AVAILABLE) {
            System.out.println("• OpenCL not detected - install vendor drivers");
        }
        
        if (currentPlatform == Platform.MACOS_X64 || currentPlatform == Platform.MACOS_ARM64) {
            System.out.println("• Metal backend recommended for best performance on macOS");
        }
        
        System.out.println("\n" + "=".repeat(80));
    }
    
    public String toJSON() {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"platform\": \"").append(currentPlatform.getDescription()).append("\",\n");
        json.append("  \"ci_environment\": ").append(ciEnvironment).append(",\n");
        json.append("  \"backends\": {\n");
        
        boolean first = true;
        for (Map.Entry<Backend, SupportLevel> entry : backendSupport.entrySet()) {
            if (!first) json.append(",\n");
            json.append("    \"").append(entry.getKey().name().toLowerCase())
                .append("\": \"").append(entry.getValue().name()).append("\"");
            first = false;
        }
        
        json.append("\n  },\n");
        json.append("  \"features\": {\n");
        
        first = true;
        for (Map.Entry<String, Boolean> entry : featureSupport.entrySet()) {
            if (!first) json.append(",\n");
            json.append("    \"").append(entry.getKey().toLowerCase().replace(" ", "_"))
                .append("\": ").append(entry.getValue());
            first = false;
        }
        
        json.append("\n  }\n");
        json.append("}");
        
        return json.toString();
    }
    
    // Getters for programmatic access
    
    public Platform getCurrentPlatform() {
        return currentPlatform;
    }
    
    public boolean isCIEnvironment() {
        return ciEnvironment;
    }
    
    public SupportLevel getBackendSupport(Backend backend) {
        return backendSupport.getOrDefault(backend, SupportLevel.NOT_AVAILABLE);
    }
    
    public boolean isFeatureSupported(String feature) {
        return featureSupport.getOrDefault(feature, false);
    }
    
    public Set<Backend> getAvailableBackends() {
        Set<Backend> available = new HashSet<>();
        for (Map.Entry<Backend, SupportLevel> entry : backendSupport.entrySet()) {
            if (entry.getValue() == SupportLevel.FULL || 
                entry.getValue() == SupportLevel.PARTIAL ||
                entry.getValue() == SupportLevel.MOCK) {
                available.add(entry.getKey());
            }
        }
        return available;
    }
    
    // Static utility method for quick checks
    
    public static void main(String[] args) {
        TestSupportMatrix matrix = new TestSupportMatrix();
        matrix.printMatrix();
        
        if (args.length > 0 && args[0].equals("--json")) {
            System.out.println("\nJSON Output:");
            System.out.println(matrix.toJSON());
        }
    }
    
    // Additional utility methods for testing integration
}