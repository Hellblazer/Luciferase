package com.hellblazer.luciferase.gpu.test;

import org.lwjgl.opencl.CL;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

/**
 * Base class for OpenCL tests that run in headless environments.
 * Provides OpenCL initialization, cleanup, and safe memory operations.
 * 
 * Tests focus on compute operations, buffer management, and error handling
 * without requiring display or windowing systems.
 */
public abstract class OpenCLHeadlessTest extends LWJGLHeadlessTest {
    
    private static final Logger log = LoggerFactory.getLogger(OpenCLHeadlessTest.class);
    
    @Override
    protected void loadRequiredNativeLibraries() {
        log.debug("Initializing OpenCL for headless operation");
        
        try {
            Configuration.OPENCL_EXPLICIT_INIT.set(true);
            CL.create();
            
            log.info("OpenCL initialized successfully on platform: {}", getPlatformInfo());
        } catch (LinkageError e) {
            var message = String.format("OpenCL native libraries not found on %s: %s", 
                                      getPlatformInfo(), e.getMessage());
            log.warn("OpenCL not available - this is normal in CI environments without GPU support");
            log.info("Tests will be skipped. For full GPU testing, use a system with OpenCL drivers installed.");
            throw new OpenCLUnavailableException(message, e);
        } catch (Exception e) {
            var message = String.format("OpenCL initialization failed on %s: %s", 
                                      getPlatformInfo(), e.getMessage());
            
            // Check if this is a CI environment or missing OpenCL libraries
            if (isOpenCLUnavailable(e)) {
                log.warn("OpenCL not available - this is normal in CI environments without GPU support");
                log.info("Tests will be skipped. For full GPU testing, use a system with OpenCL drivers installed.");
                throw new OpenCLUnavailableException(message, e);
            } else {
                log.warn(message);
                throw new RuntimeException(message, e);
            }
        }
    }
    
    /**
     * Exception thrown when OpenCL is not available (typically in CI environments).
     * Tests should handle this gracefully by skipping GPU-specific tests.
     */
    public static class OpenCLUnavailableException extends RuntimeException {
        public OpenCLUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
    
    /**
     * Check if the exception indicates OpenCL is unavailable due to missing libraries.
     * 
     * @param e the exception to check
     * @return true if this indicates missing OpenCL support
     */
    private boolean isOpenCLUnavailable(Exception e) {
        var message = e.getMessage();
        if (message == null) return false;
        
        return message.contains("libOpenCL.so") ||
               message.contains("OpenCL.dll") ||
               message.contains("libOpenCL.dylib") ||
               message.contains("Failed to locate library") ||
               message.contains("UnsatisfiedLinkError");
    }
    
    @Override
    protected void cleanupTestEnvironment() {
        try {
            CL.destroy();
            log.debug("OpenCL cleanup completed");
        } catch (Exception e) {
            log.warn("Error during OpenCL cleanup: {}", e.getMessage());
        }
    }
    
    /**
     * Test memory allocation patterns safely with automatic stack management.
     * 
     * @param allocationTest the test code to run within memory stack context
     */
    protected void testMemoryAllocation(Runnable allocationTest) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long before = stack.getPointer();
            
            allocationTest.run();
            
            long after = stack.getPointer();
            
            if (before != after) {
                log.warn("Memory stack imbalance detected - before: {}, after: {}", 
                        before, after);
            }
        }
    }
    
    /**
     * Safely test buffer operations with automatic cleanup.
     * 
     * @param bufferTest consumer that receives a ByteBuffer for testing
     */
    protected void testBufferOperations(Consumer<ByteBuffer> bufferTest) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer buffer = stack.malloc(1024);
            bufferTest.accept(buffer);
        }
    }
    
    /**
     * Check OpenCL error codes and throw descriptive exceptions.
     * 
     * @param errcode the OpenCL error code to check
     * @throws RuntimeException if errcode indicates an error
     */
    protected static void checkCLError(int errcode) {
        if (errcode != org.lwjgl.opencl.CL11.CL_SUCCESS) {
            var errorName = getOpenCLErrorName(errcode);
            throw new RuntimeException(String.format("OpenCL error: %s (0x%X)", 
                                                    errorName, errcode));
        }
    }
    
    /**
     * Get human-readable OpenCL error name.
     * 
     * @param errcode the OpenCL error code
     * @return human-readable error name
     */
    private static String getOpenCLErrorName(int errcode) {
        return switch (errcode) {
            case org.lwjgl.opencl.CL11.CL_SUCCESS -> "CL_SUCCESS";
            case org.lwjgl.opencl.CL11.CL_DEVICE_NOT_FOUND -> "CL_DEVICE_NOT_FOUND";
            case org.lwjgl.opencl.CL11.CL_DEVICE_NOT_AVAILABLE -> "CL_DEVICE_NOT_AVAILABLE";
            case org.lwjgl.opencl.CL11.CL_COMPILER_NOT_AVAILABLE -> "CL_COMPILER_NOT_AVAILABLE";
            case org.lwjgl.opencl.CL11.CL_MEM_OBJECT_ALLOCATION_FAILURE -> "CL_MEM_OBJECT_ALLOCATION_FAILURE";
            case org.lwjgl.opencl.CL11.CL_OUT_OF_RESOURCES -> "CL_OUT_OF_RESOURCES";
            case org.lwjgl.opencl.CL11.CL_OUT_OF_HOST_MEMORY -> "CL_OUT_OF_HOST_MEMORY";
            case org.lwjgl.opencl.CL11.CL_PROFILING_INFO_NOT_AVAILABLE -> "CL_PROFILING_INFO_NOT_AVAILABLE";
            case org.lwjgl.opencl.CL11.CL_MEM_COPY_OVERLAP -> "CL_MEM_COPY_OVERLAP";
            case org.lwjgl.opencl.CL11.CL_IMAGE_FORMAT_MISMATCH -> "CL_IMAGE_FORMAT_MISMATCH";
            case org.lwjgl.opencl.CL11.CL_IMAGE_FORMAT_NOT_SUPPORTED -> "CL_IMAGE_FORMAT_NOT_SUPPORTED";
            case org.lwjgl.opencl.CL11.CL_BUILD_PROGRAM_FAILURE -> "CL_BUILD_PROGRAM_FAILURE";
            case org.lwjgl.opencl.CL11.CL_MAP_FAILURE -> "CL_MAP_FAILURE";
            case org.lwjgl.opencl.CL11.CL_INVALID_VALUE -> "CL_INVALID_VALUE";
            case org.lwjgl.opencl.CL11.CL_INVALID_DEVICE_TYPE -> "CL_INVALID_DEVICE_TYPE";
            case org.lwjgl.opencl.CL11.CL_INVALID_PLATFORM -> "CL_INVALID_PLATFORM";
            case org.lwjgl.opencl.CL11.CL_INVALID_DEVICE -> "CL_INVALID_DEVICE";
            case org.lwjgl.opencl.CL11.CL_INVALID_CONTEXT -> "CL_INVALID_CONTEXT";
            case org.lwjgl.opencl.CL11.CL_INVALID_QUEUE_PROPERTIES -> "CL_INVALID_QUEUE_PROPERTIES";
            case org.lwjgl.opencl.CL11.CL_INVALID_COMMAND_QUEUE -> "CL_INVALID_COMMAND_QUEUE";
            case org.lwjgl.opencl.CL11.CL_INVALID_HOST_PTR -> "CL_INVALID_HOST_PTR";
            case org.lwjgl.opencl.CL11.CL_INVALID_MEM_OBJECT -> "CL_INVALID_MEM_OBJECT";
            case org.lwjgl.opencl.CL11.CL_INVALID_IMAGE_FORMAT_DESCRIPTOR -> "CL_INVALID_IMAGE_FORMAT_DESCRIPTOR";
            case org.lwjgl.opencl.CL11.CL_INVALID_IMAGE_SIZE -> "CL_INVALID_IMAGE_SIZE";
            case org.lwjgl.opencl.CL11.CL_INVALID_SAMPLER -> "CL_INVALID_SAMPLER";
            case org.lwjgl.opencl.CL11.CL_INVALID_BINARY -> "CL_INVALID_BINARY";
            case org.lwjgl.opencl.CL11.CL_INVALID_BUILD_OPTIONS -> "CL_INVALID_BUILD_OPTIONS";
            case org.lwjgl.opencl.CL11.CL_INVALID_PROGRAM -> "CL_INVALID_PROGRAM";
            case org.lwjgl.opencl.CL11.CL_INVALID_PROGRAM_EXECUTABLE -> "CL_INVALID_PROGRAM_EXECUTABLE";
            case org.lwjgl.opencl.CL11.CL_INVALID_KERNEL_NAME -> "CL_INVALID_KERNEL_NAME";
            case org.lwjgl.opencl.CL11.CL_INVALID_KERNEL_DEFINITION -> "CL_INVALID_KERNEL_DEFINITION";
            case org.lwjgl.opencl.CL11.CL_INVALID_KERNEL -> "CL_INVALID_KERNEL";
            case org.lwjgl.opencl.CL11.CL_INVALID_ARG_INDEX -> "CL_INVALID_ARG_INDEX";
            case org.lwjgl.opencl.CL11.CL_INVALID_ARG_VALUE -> "CL_INVALID_ARG_VALUE";
            case org.lwjgl.opencl.CL11.CL_INVALID_ARG_SIZE -> "CL_INVALID_ARG_SIZE";
            case org.lwjgl.opencl.CL11.CL_INVALID_KERNEL_ARGS -> "CL_INVALID_KERNEL_ARGS";
            case org.lwjgl.opencl.CL11.CL_INVALID_WORK_DIMENSION -> "CL_INVALID_WORK_DIMENSION";
            case org.lwjgl.opencl.CL11.CL_INVALID_WORK_GROUP_SIZE -> "CL_INVALID_WORK_GROUP_SIZE";
            case org.lwjgl.opencl.CL11.CL_INVALID_WORK_ITEM_SIZE -> "CL_INVALID_WORK_ITEM_SIZE";
            case org.lwjgl.opencl.CL11.CL_INVALID_GLOBAL_OFFSET -> "CL_INVALID_GLOBAL_OFFSET";
            case org.lwjgl.opencl.CL11.CL_INVALID_EVENT_WAIT_LIST -> "CL_INVALID_EVENT_WAIT_LIST";
            case org.lwjgl.opencl.CL11.CL_INVALID_EVENT -> "CL_INVALID_EVENT";
            case org.lwjgl.opencl.CL11.CL_INVALID_OPERATION -> "CL_INVALID_OPERATION";
            case org.lwjgl.opencl.CL11.CL_INVALID_BUFFER_SIZE -> "CL_INVALID_BUFFER_SIZE";
            case org.lwjgl.opencl.CL11.CL_INVALID_GLOBAL_WORK_SIZE -> "CL_INVALID_GLOBAL_WORK_SIZE";
            default -> "UNKNOWN_CL_ERROR";
        };
    }
}
