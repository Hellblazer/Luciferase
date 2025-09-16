package com.hellblazer.luciferase.resource.opencl;

import com.hellblazer.luciferase.resource.ResourceHandle;
import com.hellblazer.luciferase.resource.ResourceTracker;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opencl.CL10;
import org.lwjgl.opencl.CL11;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * RAII handle for OpenCL kernel objects.
 * Manages kernel lifecycle and argument setting.
 */
public class CLKernelHandle extends ResourceHandle<Long> {
    private static final Logger log = LoggerFactory.getLogger(CLKernelHandle.class);
    
    private final long program;
    private final String name;
    private volatile int nextArgIndex = 0;
    
    /**
     * Create a kernel from a program.
     * 
     * @param program OpenCL program
     * @param kernelName Kernel function name
     * @param tracker Optional resource tracker
     * @return New kernel handle
     */
    public static CLKernelHandle create(long program, String kernelName, ResourceTracker tracker) {
        try (var stack = MemoryStack.stackPush()) {
            var errCode = stack.callocInt(1);
            
            long kernel = CL10.clCreateKernel(program, kernelName, errCode);
            checkError(errCode.get(0), "Failed to create kernel: " + kernelName);
            
            log.trace("Created CL kernel {} for function '{}'", kernel, kernelName);
            return new CLKernelHandle(kernel, program, kernelName, tracker);
        }
    }
    
    private CLKernelHandle(Long kernel, long program, String name, ResourceTracker tracker) {
        super(kernel, tracker);
        this.program = program;
        this.name = name;
    }
    
    /**
     * Get the OpenCL kernel object.
     */
    public long getKernel() {
        return get();
    }
    
    /**
     * Get the kernel name.
     */
    public String getName() {
        return name;
    }
    
    /**
     * Set a buffer argument.
     * 
     * @param index Argument index
     * @param buffer Buffer handle
     */
    public void setArgBuffer(int index, CLBufferHandle buffer) {
        if (!isValid()) {
            throw new IllegalStateException("Cannot set argument on closed kernel");
        }
        
        try (var stack = MemoryStack.stackPush()) {
            var ptr = stack.callocPointer(1);
            ptr.put(0, buffer.get());
            
            int err = CL10.nclSetKernelArg(get(), index, 8, MemoryUtil.memAddress(ptr));
            checkError(err, "Failed to set buffer argument " + index);
        }
    }
    
    /**
     * Set a buffer argument at the next index.
     * 
     * @param buffer Buffer handle
     */
    public void setArgBuffer(CLBufferHandle buffer) {
        setArgBuffer(nextArgIndex++, buffer);
    }
    
    /**
     * Set an int argument.
     * 
     * @param index Argument index
     * @param value Int value
     */
    public void setArgInt(int index, int value) {
        if (!isValid()) {
            throw new IllegalStateException("Cannot set argument on closed kernel");
        }
        
        try (var stack = MemoryStack.stackPush()) {
            var buf = stack.callocInt(1);
            buf.put(0, value);
            
            int err = CL10.nclSetKernelArg(get(), index, Integer.BYTES, MemoryUtil.memAddress(buf));
            checkError(err, "Failed to set int argument " + index);
        }
    }
    
    /**
     * Set an int argument at the next index.
     * 
     * @param value Int value
     */
    public void setArgInt(int value) {
        setArgInt(nextArgIndex++, value);
    }
    
    /**
     * Set a float argument.
     * 
     * @param index Argument index
     * @param value Float value
     */
    public void setArgFloat(int index, float value) {
        if (!isValid()) {
            throw new IllegalStateException("Cannot set argument on closed kernel");
        }
        
        try (var stack = MemoryStack.stackPush()) {
            var buf = stack.callocFloat(1);
            buf.put(0, value);
            
            int err = CL10.nclSetKernelArg(get(), index, Float.BYTES, MemoryUtil.memAddress(buf));
            checkError(err, "Failed to set float argument " + index);
        }
    }
    
    /**
     * Set a float argument at the next index.
     * 
     * @param value Float value
     */
    public void setArgFloat(float value) {
        setArgFloat(nextArgIndex++, value);
    }
    
    /**
     * Set a long argument.
     * 
     * @param index Argument index
     * @param value Long value
     */
    public void setArgLong(int index, long value) {
        if (!isValid()) {
            throw new IllegalStateException("Cannot set argument on closed kernel");
        }
        
        try (var stack = MemoryStack.stackPush()) {
            var buf = stack.callocLong(1);
            buf.put(0, value);
            
            int err = CL10.nclSetKernelArg(get(), index, Long.BYTES, MemoryUtil.memAddress(buf));
            checkError(err, "Failed to set long argument " + index);
        }
    }
    
    /**
     * Set a long argument at the next index.
     * 
     * @param value Long value
     */
    public void setArgLong(long value) {
        setArgLong(nextArgIndex++, value);
    }
    
    /**
     * Set local memory size for an argument.
     * 
     * @param index Argument index
     * @param size Local memory size in bytes
     */
    public void setArgLocalMemory(int index, long size) {
        if (!isValid()) {
            throw new IllegalStateException("Cannot set argument on closed kernel");
        }
        
        int err = CL10.nclSetKernelArg(get(), index, size, 0);
        checkError(err, "Failed to set local memory argument " + index);
    }
    
    /**
     * Set local memory size at the next index.
     * 
     * @param size Local memory size in bytes
     */
    public void setArgLocalMemory(long size) {
        setArgLocalMemory(nextArgIndex++, size);
    }
    
    /**
     * Reset the argument index counter.
     */
    public void resetArgIndex() {
        nextArgIndex = 0;
    }
    
    /**
     * Enqueue kernel execution (1D).
     * 
     * @param queue Command queue
     * @param globalWorkSize Global work size
     * @param localWorkSize Local work size (can be null for auto)
     * @param events Wait events (can be null)
     * @param event Event to signal on completion (can be null)
     */
    public void enqueueNDRange1D(long queue, long globalWorkSize, Long localWorkSize,
                                 long[] events, long event) {
        if (!isValid()) {
            throw new IllegalStateException("Cannot enqueue closed kernel");
        }
        
        try (var stack = MemoryStack.stackPush()) {
            var global = stack.callocPointer(1);
            global.put(0, globalWorkSize);
            
            PointerBuffer local = null;
            if (localWorkSize != null) {
                local = stack.callocPointer(1);
                local.put(0, localWorkSize);
            }
            
            PointerBuffer eventBuffer = null;
            PointerBuffer eventOut = null;
            if (events != null && events.length > 0) {
                eventBuffer = stack.mallocPointer(events.length);
                for (long e : events) eventBuffer.put(e);
                eventBuffer.flip();
            }
            if (event != 0) {
                eventOut = stack.mallocPointer(1);
            }
            
            int err = CL10.clEnqueueNDRangeKernel(queue, get(), 1, null, global, local, eventBuffer, eventOut);
            
            if (eventOut != null && event != 0) {
                // Store the generated event
                eventOut.put(0, event);
            }
            checkError(err, "Failed to enqueue kernel");
        }
    }
    
    /**
     * Enqueue kernel execution (2D).
     * 
     * @param queue Command queue
     * @param globalWorkSizeX Global work size X
     * @param globalWorkSizeY Global work size Y
     * @param localWorkSizeX Local work size X (use null for auto)
     * @param localWorkSizeY Local work size Y (use null for auto)
     * @param events Wait events (can be null)
     * @param event Event to signal on completion (can be null)
     */
    public void enqueueNDRange2D(long queue, 
                                 long globalWorkSizeX, long globalWorkSizeY,
                                 Long localWorkSizeX, Long localWorkSizeY,
                                 long[] events, long event) {
        if (!isValid()) {
            throw new IllegalStateException("Cannot enqueue closed kernel");
        }
        
        try (var stack = MemoryStack.stackPush()) {
            var global = stack.callocPointer(2);
            global.put(0, globalWorkSizeX);
            global.put(1, globalWorkSizeY);
            
            PointerBuffer local = null;
            if (localWorkSizeX != null && localWorkSizeY != null) {
                local = stack.callocPointer(2);
                local.put(0, localWorkSizeX);
                local.put(1, localWorkSizeY);
            }
            
            PointerBuffer eventBuffer = null;
            PointerBuffer eventOut = null;
            if (events != null && events.length > 0) {
                eventBuffer = stack.mallocPointer(events.length);
                for (long e : events) eventBuffer.put(e);
                eventBuffer.flip();
            }
            if (event != 0) {
                eventOut = stack.mallocPointer(1);
            }
            
            int err = CL10.clEnqueueNDRangeKernel(queue, get(), 2, null, global, local, eventBuffer, eventOut);
            
            if (eventOut != null && event != 0) {
                // Store the generated event
                eventOut.put(0, event);
            }
            checkError(err, "Failed to enqueue kernel");
        }
    }
    
    /**
     * Enqueue kernel execution (3D).
     * 
     * @param queue Command queue
     * @param globalWorkSizeX Global work size X
     * @param globalWorkSizeY Global work size Y
     * @param globalWorkSizeZ Global work size Z
     * @param localWorkSizeX Local work size X (use null for auto)
     * @param localWorkSizeY Local work size Y (use null for auto)
     * @param localWorkSizeZ Local work size Z (use null for auto)
     * @param events Wait events (can be null)
     * @param event Event to signal on completion (can be null)
     */
    public void enqueueNDRange3D(long queue,
                                 long globalWorkSizeX, long globalWorkSizeY, long globalWorkSizeZ,
                                 Long localWorkSizeX, Long localWorkSizeY, Long localWorkSizeZ,
                                 long[] events, long event) {
        if (!isValid()) {
            throw new IllegalStateException("Cannot enqueue closed kernel");
        }
        
        try (var stack = MemoryStack.stackPush()) {
            var global = stack.callocPointer(3);
            global.put(0, globalWorkSizeX);
            global.put(1, globalWorkSizeY);
            global.put(2, globalWorkSizeZ);
            
            PointerBuffer local = null;
            if (localWorkSizeX != null && localWorkSizeY != null && localWorkSizeZ != null) {
                local = stack.callocPointer(3);
                local.put(0, localWorkSizeX);
                local.put(1, localWorkSizeY);
                local.put(2, localWorkSizeZ);
            }
            
            PointerBuffer eventBuffer = null;
            PointerBuffer eventOut = null;
            if (events != null && events.length > 0) {
                eventBuffer = stack.mallocPointer(events.length);
                for (long e : events) eventBuffer.put(e);
                eventBuffer.flip();
            }
            if (event != 0) {
                eventOut = stack.mallocPointer(1);
            }
            
            int err = CL10.clEnqueueNDRangeKernel(queue, get(), 3, null, global, local, eventBuffer, eventOut);
            
            if (eventOut != null && event != 0) {
                // Store the generated event
                eventOut.put(0, event);
            }
            checkError(err, "Failed to enqueue kernel");
        }
    }
    
    /**
     * Get the preferred work group size multiple for this kernel.
     * 
     * @param device OpenCL device
     * @return Preferred work group size multiple
     */
    public long getPreferredWorkGroupSizeMultiple(long device) {
        if (!isValid()) {
            throw new IllegalStateException("Kernel is closed");
        }
        
        try (var stack = MemoryStack.stackPush()) {
            var size = stack.callocPointer(1);
            
            int err = CL10.clGetKernelWorkGroupInfo(get(), device, 
                CL11.CL_KERNEL_PREFERRED_WORK_GROUP_SIZE_MULTIPLE, size, null);
            checkError(err, "Failed to get preferred work group size");
            
            return size.get(0);
        }
    }
    
    /**
     * Get the maximum work group size for this kernel.
     * 
     * @param device OpenCL device
     * @return Maximum work group size
     */
    public long getMaxWorkGroupSize(long device) {
        if (!isValid()) {
            throw new IllegalStateException("Kernel is closed");
        }
        
        try (var stack = MemoryStack.stackPush()) {
            var size = stack.callocPointer(1);
            
            int err = CL10.clGetKernelWorkGroupInfo(get(), device,
                CL10.CL_KERNEL_WORK_GROUP_SIZE, size, null);
            checkError(err, "Failed to get max work group size");
            
            return size.get(0);
        }
    }
    
    /**
     * Get the local memory size used by this kernel.
     * 
     * @param device OpenCL device
     * @return Local memory size in bytes
     */
    public long getLocalMemorySize(long device) {
        if (!isValid()) {
            throw new IllegalStateException("Kernel is closed");
        }
        
        try (var stack = MemoryStack.stackPush()) {
            var size = stack.callocLong(1);
            
            int err = CL10.clGetKernelWorkGroupInfo(get(), device,
                CL10.CL_KERNEL_LOCAL_MEM_SIZE, size, null);
            checkError(err, "Failed to get local memory size");
            
            return size.get(0);
        }
    }
    
    @Override
    protected void doCleanup(Long kernel) {
        int err = CL10.clReleaseKernel(kernel);
        if (err != CL10.CL_SUCCESS) {
            log.error("Failed to release CL kernel {}: error {}", kernel, err);
        } else {
            log.trace("Released CL kernel {} ({})", kernel, name);
        }
    }
    
    private static void checkError(int err, String message) {
        if (err != CL10.CL_SUCCESS) {
            throw new RuntimeException(message + " (error: " + err + ")");
        }
    }
    
    @Override
    public String toString() {
        return String.format("CLKernelHandle[id=%d, name=%s, state=%s]",
            get(), name, getState());
    }
}