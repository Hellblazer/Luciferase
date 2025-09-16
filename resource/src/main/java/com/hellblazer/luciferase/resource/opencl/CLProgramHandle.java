package com.hellblazer.luciferase.resource.opencl;

import com.hellblazer.luciferase.resource.ResourceHandle;
import com.hellblazer.luciferase.resource.ResourceTracker;
import org.lwjgl.opencl.CL10;
import org.lwjgl.opencl.CL12;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * RAII handle for OpenCL programs
 */
public class CLProgramHandle extends ResourceHandle<Long> {
    private static final Logger log = LoggerFactory.getLogger(CLProgramHandle.class);
    
    private final long context;
    private final String source;
    private final List<Long> devices;
    private final String buildOptions;
    private BuildStatus buildStatus = BuildStatus.NOT_BUILT;
    
    public enum BuildStatus {
        NOT_BUILT,
        BUILD_SUCCESS,
        BUILD_ERROR
    }
    
    /**
     * Create a program from source
     */
    public static CLProgramHandle createFromSource(long context, String source) {
        return createFromSource(context, source, null, null);
    }
    
    /**
     * Create a program from source with specific devices and options
     */
    public static CLProgramHandle createFromSource(long context, String source, 
                                                   List<Long> devices, String buildOptions) {
        try (var stack = MemoryStack.stackPush()) {
            IntBuffer errcode = stack.mallocInt(1);
            
            // Create program from source
            long program = CL10.clCreateProgramWithSource(context, source, errcode);
            checkError(errcode.get(0));
            
            if (program == 0) {
                throw new IllegalStateException("Failed to create OpenCL program");
            }
            
            var handle = new CLProgramHandle(program, context, source, devices, buildOptions);
            var tracker = ResourceTracker.getGlobalTracker();
            if (tracker != null) {
                tracker.track(handle);
            }
            log.debug("Created OpenCL program: {}", program);
            
            return handle;
        }
    }
    
    private CLProgramHandle(long program, long context, String source, 
                           List<Long> devices, String buildOptions) {
        super(program, ResourceTracker.getGlobalTracker());
        this.context = context;
        this.source = source;
        this.devices = devices != null ? new ArrayList<>(devices) : new ArrayList<>();
        this.buildOptions = buildOptions;
    }
    
    /**
     * Build the program
     */
    public void build() {
        build(devices, buildOptions);
    }
    
    /**
     * Build the program with specific options
     */
    public void build(List<Long> targetDevices, String options) {
        get(); // Ensure not closed
        
        try (var stack = MemoryStack.stackPush()) {
            var deviceBuffer = targetDevices != null && !targetDevices.isEmpty() ?
                stack.mallocPointer(targetDevices.size()) : null;
            
            if (deviceBuffer != null) {
                for (long device : targetDevices) {
                    deviceBuffer.put(device);
                }
                deviceBuffer.flip();
            }
            
            int error = CL10.clBuildProgram(
                get(),
                deviceBuffer,
                options,
                null,
                0
            );
            
            if (error == CL10.CL_SUCCESS) {
                buildStatus = BuildStatus.BUILD_SUCCESS;
                log.debug("Successfully built OpenCL program");
            } else {
                buildStatus = BuildStatus.BUILD_ERROR;
                
                // Get build log for debugging
                if (targetDevices != null && !targetDevices.isEmpty()) {
                    String buildLog = getBuildLog(targetDevices.get(0));
                    log.error("OpenCL program build failed: {}", buildLog);
                }
                
                checkError(error);
            }
        }
    }
    
    /**
     * Get build log for a specific device
     */
    public String getBuildLog(long device) {
        get(); // Ensure not closed
        
        try (var stack = MemoryStack.stackPush()) {
            var sizeBuffer = stack.mallocPointer(1);
            
            // Get log size
            int error = CL10.clGetProgramBuildInfo(
                get(),
                device,
                CL10.CL_PROGRAM_BUILD_LOG,
                (ByteBuffer) null,
                sizeBuffer
            );
            checkError(error);
            
            long logSize = sizeBuffer.get(0);
            if (logSize == 0) {
                return "";
            }
            
            // Get actual log
            var logBuffer = stack.malloc((int) logSize);
            error = CL10.clGetProgramBuildInfo(
                get(),
                device,
                CL10.CL_PROGRAM_BUILD_LOG,
                logBuffer,
                null
            );
            checkError(error);
            
            byte[] bytes = new byte[logBuffer.remaining() - 1]; // Exclude null terminator
            logBuffer.get(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
    
    /**
     * Get build status for a specific device
     */
    public int getBuildStatus(long device) {
        get(); // Ensure not closed
        
        try (var stack = MemoryStack.stackPush()) {
            var statusBuffer = stack.mallocInt(1);
            
            int error = CL10.clGetProgramBuildInfo(
                get(),
                device,
                CL10.CL_PROGRAM_BUILD_STATUS,
                statusBuffer,
                null
            );
            checkError(error);
            
            return statusBuffer.get(0);
        }
    }
    
    /**
     * Create a kernel from this program
     */
    public CLKernelHandle createKernel(String kernelName) {
        get(); // Ensure not closed
        
        if (buildStatus != BuildStatus.BUILD_SUCCESS) {
            throw new IllegalStateException("Program must be built before creating kernels");
        }
        
        try (var stack = MemoryStack.stackPush()) {
            var errcode = stack.mallocInt(1);
            
            long kernel = CL10.clCreateKernel(get(), kernelName, errcode);
            checkError(errcode.get(0));
            
            if (kernel == 0) {
                throw new IllegalStateException("Failed to create kernel: " + kernelName);
            }
            
            return CLKernelHandle.create(get(), kernelName, null);
        }
    }
    
    /**
     * Get all kernel names in the program
     */
    public List<String> getKernelNames() {
        get(); // Ensure not closed
        
        if (buildStatus != BuildStatus.BUILD_SUCCESS) {
            throw new IllegalStateException("Program must be built before querying kernels");
        }
        
        try (var stack = MemoryStack.stackPush()) {
            var numKernels = stack.mallocInt(1);
            
            // Get number of kernels
            int error = CL10.clGetProgramInfo(
                get(),
                CL12.CL_PROGRAM_NUM_KERNELS,
                numKernels,
                null
            );
            checkError(error);
            
            if (numKernels.get(0) == 0) {
                return new ArrayList<>();
            }
            
            // Get kernel names size
            var sizeBuffer = stack.mallocPointer(1);
            error = CL10.clGetProgramInfo(
                get(),
                CL12.CL_PROGRAM_KERNEL_NAMES,
                (ByteBuffer) null,
                sizeBuffer
            );
            checkError(error);
            
            // Get kernel names
            var namesBuffer = stack.malloc((int) sizeBuffer.get(0));
            error = CL10.clGetProgramInfo(
                get(),
                CL12.CL_PROGRAM_KERNEL_NAMES,
                namesBuffer,
                null
            );
            checkError(error);
            
            // Parse semicolon-separated names
            byte[] bytes = new byte[namesBuffer.remaining() - 1];
            namesBuffer.get(bytes);
            String names = new String(bytes, StandardCharsets.UTF_8);
            
            var kernelNames = new ArrayList<String>();
            if (!names.isEmpty()) {
                for (String name : names.split(";")) {
                    kernelNames.add(name.trim());
                }
            }
            
            return kernelNames;
        }
    }
    
    /**
     * Get program binary size for a device
     */
    public long getBinarySize(long device) {
        get(); // Ensure not closed
        
        try (var stack = MemoryStack.stackPush()) {
            var sizeBuffer = stack.mallocPointer(1);
            
            int error = CL10.clGetProgramInfo(
                get(),
                CL10.CL_PROGRAM_BINARY_SIZES,
                sizeBuffer,
                null
            );
            checkError(error);
            
            return sizeBuffer.get(0);
        }
    }
    
    /**
     * Get program source
     */
    public String getSource() {
        return source;
    }
    
    /**
     * Get build status
     */
    public BuildStatus getBuildStatus() {
        return buildStatus;
    }
    
    /**
     * Get context
     */
    public long getContext() {
        return context;
    }
    
    @Override
    protected void doCleanup(Long program) {
        int error = CL10.clReleaseProgram(program);
        if (error != CL10.CL_SUCCESS) {
            log.error("Failed to release OpenCL program {}: {}", program, error);
        } else {
            log.debug("Released OpenCL program: {}", program);
        }
        var tracker = ResourceTracker.getGlobalTracker();
        if (tracker != null) {
            tracker.untrack(this);
        }
    }
    
    private static void checkError(int error) {
        if (error != CL10.CL_SUCCESS) {
            throw new RuntimeException("OpenCL error: " + error);
        }
    }
}