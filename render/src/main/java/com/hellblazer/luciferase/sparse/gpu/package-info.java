/**
 * GPU utilities for sparse voxel rendering.
 *
 * <p>This package provides shared GPU infrastructure for both ESVO and ESVT
 * sparse voxel renderers, including matrix utilities and GPU configuration constants.
 *
 * <h2>Thread Safety Guidelines</h2>
 *
 * <h3>Immutable Classes (Thread-Safe)</h3>
 * <ul>
 *   <li>{@link GPUConstants} - All static final fields, always safe</li>
 *   <li>{@link MatrixUtils} - All static methods with no shared state</li>
 * </ul>
 *
 * <h3>GPU Resource Management</h3>
 * <p>GPU resources (OpenCL buffers, compute shader buffers) are NOT thread-safe:
 * <ul>
 *   <li>Each rendering context should have its own GPU resources</li>
 *   <li>Buffer uploads must complete before kernel execution</li>
 *   <li>Use synchronization barriers between upload and compute</li>
 * </ul>
 *
 * <h3>OpenCL Considerations</h3>
 * <pre>{@code
 * // OpenCL command queue operations are serialized per queue
 * // Safe pattern: one queue per thread
 * var queue = clCreateCommandQueue(context, device, 0);
 *
 * // Upload, execute, download in sequence
 * clEnqueueWriteBuffer(queue, nodeBuffer, ...);
 * clEnqueueNDRangeKernel(queue, kernel, ...);
 * clEnqueueReadBuffer(queue, outputBuffer, ...);
 * clFinish(queue);  // Wait for completion
 * }</pre>
 *
 * <h3>Memory Alignment</h3>
 * <p>Use {@link GPUConstants#GPU_ALIGNMENT} (64 bytes) for buffer allocation
 * to ensure optimal cache line utilization across GPU architectures.
 *
 * @author hal.hildebrand
 * @see com.hellblazer.luciferase.esvo.gpu
 * @see com.hellblazer.luciferase.esvt.gpu
 */
package com.hellblazer.luciferase.sparse.gpu;
