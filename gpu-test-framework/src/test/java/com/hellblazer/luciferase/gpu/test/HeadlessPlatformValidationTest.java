package com.hellblazer.luciferase.gpu.test;

import org.lwjgl.system.*;
import org.lwjgl.opencl.*;
import org.lwjgl.PointerBuffer;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opencl.CL11.*;
import static org.lwjgl.system.MemoryStack.*;
import static org.lwjgl.system.MemoryUtil.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Self-contained test to validate headless operation capability on the current platform.
 * Run this FIRST before implementing any headless testing framework.
 * 
 * Based on LWJGL's headless testing patterns adapted for JUnit 5.
 */
class HeadlessPlatformValidationTest extends CICompatibleGPUTest {
    
    @Test
    void validateHeadlessPlatformCapability() {
        System.out.println("=== LWJGL Headless Platform Validation ===");
        
        // 1. Platform Detection
        Platform platform = Platform.get();
        Platform.Architecture arch = Platform.getArchitecture();
        
        System.out.printf("Platform: %s (%s)%n", platform.getName(), platform);
        boolean is64Bit = (arch == Platform.Architecture.X64 || arch == Platform.Architecture.ARM64);
        System.out.printf("Architecture: %s (64-bit: %s)%n", arch, is64Bit);
        
        // 2. Headless Environment Check
        System.out.printf("AWT Headless: %s%n", System.getProperty("java.awt.headless", "false"));
        System.out.printf("Display: %s%n", System.getenv("DISPLAY"));
        
        // 3. Memory Management Test
        validateMemoryOperations();
        
        // 4. Native Library Loading Test
        validateNativeLibraryAccess();
        
        // 5. Platform-Specific Validation
        validatePlatformSpecificCapabilities(platform, arch);
        
        System.out.println("✅ Headless platform validation PASSED - Framework safe to use!");
    }
    
    private void validateMemoryOperations() {
        System.out.println("\n--- Memory Operations Test ---");
        
        // Test stack allocation
        try (MemoryStack stack = stackPush()) {
            long before = stack.getPointer();
            ByteBuffer buffer = stack.malloc(1024);
            assertNotNull(buffer);
            assertEquals(1024, buffer.capacity());
            
            // Test buffer operations
            buffer.putInt(0, 0x12345678);
            assertEquals(0x12345678, buffer.getInt(0));
            
            long after = stack.getPointer();
            assertTrue(before > after, "Stack should have allocated memory");
            
            System.out.println("  ✅ Stack allocation works");
        }
        
        // Test direct allocation
        ByteBuffer direct = memAlloc(256);
        try {
            assertNotNull(direct);
            assertEquals(256, direct.capacity());
            direct.putLong(0, 0x123456789ABCDEFL);
            assertEquals(0x123456789ABCDEFL, direct.getLong(0));
            System.out.println("  ✅ Direct allocation works");
        } finally {
            memFree(direct);
        }
    }
    
    private void validateNativeLibraryAccess() {
        System.out.println("\n--- Native Library Access Test ---");
        
        // Test core LWJGL functionality
        try {
            // This should work on all platforms without display
            String version = org.lwjgl.Version.getVersion();
            assertNotNull(version);
            System.out.printf("  ✅ LWJGL Core accessible: %s%n", version);
        } catch (Exception e) {
            fail("Core LWJGL functionality failed: " + e.getMessage());
        }
    }
    
    private void validatePlatformSpecificCapabilities(Platform platform, Platform.Architecture arch) {
        System.out.printf("%n--- Platform-Specific Validation (%s/%s) ---%n", platform, arch);
        
        // Check for known ARM64 limitations
        if (arch == Platform.Architecture.ARM64 || arch == Platform.Architecture.ARM32) {
            System.out.println("  ⚠️  ARM architecture detected - JVM stack tests may be unstable");
            System.out.println("     This affects only stress tests, not library functionality");
        }
        
        // macOS specific checks
        if (platform == Platform.MACOSX) {
            String jvmOptions = System.getProperty("java.vm.options", "");
            boolean hasStartOnFirstThread = jvmOptions.contains("-XstartOnFirstThread");
            
            System.out.printf("  -XstartOnFirstThread: %s%n", hasStartOnFirstThread ? "YES" : "NO");
            
            if (hasStartOnFirstThread) {
                System.out.println("     ✅ Suitable for GLFW/windowing tests");
            } else {
                System.out.println("     ✅ Suitable for headless tests (OpenCL, Vulkan, etc.)");
                System.out.println("     ⚠️  GLFW tests will require -XstartOnFirstThread");
            }
        }
        
        // Test OpenCL as representative headless library (if available)
        if (!isOpenCLNotSupported()) {
            validateOpenCLHeadless();
        } else {
            System.out.println("\n--- OpenCL Test Skipped ---");
            System.out.println("  ⚠️  OpenCL not available - this is normal in CI environments");
            System.out.println("  ✅ Framework correctly detects OpenCL unavailability");
        }
    }
    
    private void validateOpenCLHeadless() {
        System.out.println("\n--- OpenCL Headless Test ---");
        
        // Set explicit init for better error handling
        Configuration.OPENCL_EXPLICIT_INIT.set(true);
        
        try {
            CL.create();
            
            try (MemoryStack stack = stackPush()) {
                IntBuffer pi = stack.mallocInt(1);
                int result = clGetPlatformIDs(null, pi);
                
                if (result == CL_SUCCESS && pi.get(0) > 0) {
                    System.out.printf("  ✅ OpenCL platforms available: %d%n", pi.get(0));
                    
                    // Try to get first platform info
                    PointerBuffer platforms = stack.mallocPointer(1);
                    checkCLErrorLocal(clGetPlatformIDs(platforms, (IntBuffer)null));
                    
                    long platform = platforms.get(0);
                    
                    // Test GPU compute devices specifically
                    result = clGetDeviceIDs(platform, CL_DEVICE_TYPE_GPU, null, pi);
                    if (result == CL_SUCCESS && pi.get(0) > 0) {
                        System.out.printf("  🚀 OpenCL GPU compute devices: %d%n", pi.get(0));
                        System.out.println("  🎉 GPU COMPUTE HEADLESS ACCESS CONFIRMED");
                    } else {
                        System.out.println("  ⚠️  No GPU devices found, checking CPU...");
                        result = clGetDeviceIDs(platform, CL_DEVICE_TYPE_CPU, null, pi);
                        if (result == CL_SUCCESS && pi.get(0) > 0) {
                            System.out.printf("  ✅ OpenCL CPU devices: %d%n", pi.get(0));
                        }
                    }
                    
                    // Get all device types for comprehensive check
                    result = clGetDeviceIDs(platform, CL_DEVICE_TYPE_ALL, null, pi);
                    if (result == CL_SUCCESS && pi.get(0) > 0) {
                        System.out.printf("  ✅ Total OpenCL devices: %d%n", pi.get(0));
                        System.out.println("  🎉 OpenCL headless compute operation CONFIRMED");
                    }
                    
                } else if (result == -1001) { // CL_PLATFORM_NOT_FOUND_KHR
                    System.out.println("  ⚠️  No OpenCL platforms found (expected on some systems)");
                } else {
                    System.out.printf("  ⚠️  OpenCL error: 0x%X%n", result);
                }
                
            }
        } catch (Exception e) {
            System.out.println("  ⚠️  OpenCL not available: " + e.getMessage());
            System.out.println("     This is normal on systems without OpenCL drivers");
        } finally {
            try {
                CL.destroy();
            } catch (Exception ignored) {}
        }
    }
    
    private static boolean isOpenCLNotSupported() {
        try {
            Configuration.OPENCL_EXPLICIT_INIT.set(true);
            CL.create();
            CL.destroy();
            return false;
        } catch (Exception e) {
            return true;
        }
    }
    
    private static void checkCLErrorLocal(int errcode) {
        if (errcode != CL_SUCCESS) {
            throw new RuntimeException("OpenCL error: " + errcode);
        }
    }
}
