/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.sparse.gpu;

import com.hellblazer.luciferase.resource.compute.ComputeKernel;
import com.hellblazer.luciferase.resource.compute.opencl.OpenCLContext;
import org.lwjgl.PointerBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.lwjgl.opencl.CL10.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * Enhanced OpenCL Kernel with Build Options Support
 *
 * <p>Extends the base gpu-support OpenCLKernel functionality to support:
 * <ul>
 *   <li>Compile-time parameter override via build options</li>
 *   <li>Kernel recompilation for dynamic tuning</li>
 *   <li>GPU-specific optimization through preprocessor defines</li>
 * </ul>
 *
 * <p><b>Usage Example:</b>
 * <pre>{@code
 * try (var kernel = EnhancedOpenCLKernel.create("myKernel")) {
 *     // Compile with GPU-specific parameters
 *     String buildOptions = "-D MAX_TRAVERSAL_DEPTH=16 -D WORKGROUP_SIZE=64";
 *     kernel.compile(kernelSource, "rayTrace", buildOptions);
 *
 *     // Later, recompile with different parameters
 *     kernel.recompile(kernelSource, "rayTrace", "-D MAX_TRAVERSAL_DEPTH=24");
 * }
 * }</pre>
 *
 * <p><b>P4 Integration:</b> This class enables runtime GPU-specific kernel optimization
 * by allowing parameters like MAX_TRAVERSAL_DEPTH and WORKGROUP_SIZE to be tuned
 * per-device without modifying kernel source code.
 *
 * @author hal.hildebrand
 */
public class EnhancedOpenCLKernel implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EnhancedOpenCLKernel.class);

    private final String name;
    private final long context;
    private final long commandQueue;
    private final long device;
    private long program = NULL;
    private long kernel = NULL;
    private final AtomicBoolean compiled = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Create a new enhanced OpenCL kernel
     *
     * @param name Descriptive name for the kernel (for logging)
     * @return New enhanced OpenCL kernel
     * @throws IllegalStateException if OpenCL is not available
     */
    public static EnhancedOpenCLKernel create(String name) {
        var ctx = OpenCLContext.getInstance();
        if (!ctx.isInitialized()) {
            ctx.acquire();
        }

        return new EnhancedOpenCLKernel(
            name,
            ctx.getContext(),
            ctx.getCommandQueue(),
            ctx.getDevice()
        );
    }

    private EnhancedOpenCLKernel(String name, long context, long commandQueue, long device) {
        this.name = name;
        this.context = context;
        this.commandQueue = commandQueue;
        this.device = device;
        log.debug("Created enhanced OpenCL kernel: {}", name);
    }

    /**
     * Compile kernel from source code WITHOUT build options (default behavior)
     *
     * @param source     Kernel source code
     * @param entryPoint Kernel entry point function name
     * @throws ComputeKernel.KernelCompilationException if compilation fails
     */
    public void compile(String source, String entryPoint) throws ComputeKernel.KernelCompilationException {
        compile(source, entryPoint, "");
    }

    /**
     * Compile kernel from source code WITH build options (enhanced)
     *
     * <p>Build options are passed directly to clBuildProgram as compiler flags.
     * Use preprocessor defines to override kernel parameters at compile time.
     *
     * <p><b>Common Build Options:</b>
     * <ul>
     *   <li><b>-D SYMBOL=value</b>: Define preprocessor symbol</li>
     *   <li><b>-O2</b>: Optimization level 2</li>
     *   <li><b>-cl-fast-relaxed-math</b>: Enable fast math optimizations</li>
     *   <li><b>-cl-mad-enable</b>: Enable multiply-add optimizations</li>
     * </ul>
     *
     * <p><b>Example:</b>
     * <pre>{@code
     * String buildOptions = "-D MAX_TRAVERSAL_DEPTH=16 -D WORKGROUP_SIZE=64 -O2";
     * kernel.compile(kernelSource, "rayTrace", buildOptions);
     * }</pre>
     *
     * @param source       Kernel source code
     * @param entryPoint   Kernel entry point function name
     * @param buildOptions OpenCL compiler build options
     * @throws ComputeKernel.KernelCompilationException if compilation fails
     */
    public void compile(String source, String entryPoint, String buildOptions)
            throws ComputeKernel.KernelCompilationException {
        checkNotClosed();
        if (compiled.get()) {
            throw new ComputeKernel.KernelCompilationException("Kernel already compiled. Use recompile() instead.");
        }

        compileInternal(source, entryPoint, buildOptions);
    }

    /**
     * Recompile kernel with new build options
     *
     * <p>Releases the existing compiled kernel and recompiles with new parameters.
     * Useful for runtime GPU tuning without creating a new kernel instance.
     *
     * <p><b>Use Case:</b> GPU auto-tuner discovers that MAX_TRAVERSAL_DEPTH=16 is
     * optimal for this device, so recompile the kernel with that parameter.
     *
     * @param source       Kernel source code (must match original)
     * @param entryPoint   Kernel entry point function name (must match original)
     * @param buildOptions New OpenCL compiler build options
     * @throws ComputeKernel.KernelCompilationException if recompilation fails
     */
    public void recompile(String source, String entryPoint, String buildOptions)
            throws ComputeKernel.KernelCompilationException {
        checkNotClosed();

        // Release existing kernel/program
        if (compiled.get()) {
            releaseKernel();
            compiled.set(false);
            log.debug("Released kernel {} for recompilation", name);
        }

        // Compile with new build options
        compileInternal(source, entryPoint, buildOptions);
    }

    /**
     * Internal compilation logic shared by compile() and recompile()
     */
    private void compileInternal(String source, String entryPoint, String buildOptions)
            throws ComputeKernel.KernelCompilationException {
        try (var stack = stackPush()) {
            // Create program from source
            var errcode = stack.mallocInt(1);
            program = clCreateProgramWithSource(context, source, errcode);
            checkCLError(errcode.get(0), "Failed to create OpenCL program");

            // Build program with options
            var devices = stack.mallocPointer(1);
            devices.put(0, device);

            // Key enhancement: Pass buildOptions to compiler (handle null as empty string)
            var safeBuildOptions = (buildOptions != null) ? buildOptions : "";
            var buildStatus = clBuildProgram(program, devices, safeBuildOptions, null, NULL);

            if (buildStatus != CL_SUCCESS) {
                // Get build log for debugging
                var logSize = stack.mallocPointer(1);
                clGetProgramBuildInfo(program, device, CL_PROGRAM_BUILD_LOG, (PointerBuffer) null, logSize);

                if (logSize.get(0) > 0) {
                    var logBuffer = stack.malloc((int) logSize.get(0));
                    clGetProgramBuildInfo(program, device, CL_PROGRAM_BUILD_LOG, logBuffer, null);

                    var buildLog = memUTF8(logBuffer);
                    throw new ComputeKernel.KernelCompilationException(
                        String.format("OpenCL kernel compilation failed with build options '%s':\n%s",
                            buildOptions, buildLog)
                    );
                } else {
                    throw new ComputeKernel.KernelCompilationException(
                        String.format("OpenCL kernel compilation failed with build options '%s' (no build log)",
                            buildOptions)
                    );
                }
            }

            // Create kernel
            kernel = clCreateKernel(program, entryPoint, errcode);
            checkCLError(errcode.get(0), "Failed to create OpenCL kernel: " + entryPoint);

            compiled.set(true);

            if (safeBuildOptions != null && !safeBuildOptions.isEmpty()) {
                log.info("Compiled OpenCL kernel: {} (entry: {}, options: {})", name, entryPoint, safeBuildOptions);
            } else {
                log.debug("Compiled OpenCL kernel: {} (entry: {}, no options)", name, entryPoint);
            }

        } catch (Exception e) {
            releaseKernel();
            if (e instanceof ComputeKernel.KernelCompilationException kce) {
                throw kce;
            }
            throw new ComputeKernel.KernelCompilationException("OpenCL kernel compilation failed: " + name, e);
        }
    }

    /**
     * Get the raw OpenCL kernel handle
     *
     * <p>Allows setting kernel arguments via raw OpenCL API (clSetKernelArg).
     * Used by AbstractOpenCLRenderer for buffer and scalar argument binding.
     *
     * @return OpenCL kernel handle (cl_kernel)
     * @throws IllegalStateException if kernel not compiled
     */
    public long getKernelHandle() {
        checkNotClosed();
        checkCompiled();
        return kernel;
    }

    /**
     * Get the OpenCL command queue
     *
     * <p>Used by AbstractOpenCLRenderer for kernel execution (clEnqueueNDRangeKernel).
     *
     * @return OpenCL command queue handle (cl_command_queue)
     */
    public long getCommandQueue() {
        return commandQueue;
    }

    /**
     * Get the kernel name
     *
     * @return Descriptive kernel name
     */
    public String getName() {
        return name;
    }

    /**
     * Check if kernel is compiled and ready
     *
     * @return true if compiled
     */
    public boolean isCompiled() {
        return compiled.get();
    }

    /**
     * Check if kernel is still valid (not closed)
     *
     * @return true if valid
     */
    public boolean isValid() {
        return !closed.get();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            releaseKernel();
            log.debug("Closed enhanced OpenCL kernel: {}", name);
        }
    }

    private void releaseKernel() {
        if (kernel != NULL) {
            clReleaseKernel(kernel);
            kernel = NULL;
        }
        if (program != NULL) {
            clReleaseProgram(program);
            program = NULL;
        }
    }

    private void checkNotClosed() {
        if (closed.get()) {
            throw new IllegalStateException("Kernel has been closed");
        }
    }

    private void checkCompiled() {
        if (!compiled.get()) {
            throw new IllegalStateException("Kernel not compiled");
        }
    }

    private void checkCLError(int errcode, String message) throws ComputeKernel.KernelCompilationException {
        if (errcode != CL_SUCCESS) {
            throw new ComputeKernel.KernelCompilationException(
                String.format("%s (error code: %d)", message, errcode)
            );
        }
    }
}
