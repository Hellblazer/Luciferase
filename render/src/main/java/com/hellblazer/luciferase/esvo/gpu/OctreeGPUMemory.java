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
package com.hellblazer.luciferase.esvo.gpu;

import com.hellblazer.luciferase.resource.UnifiedResourceManager;
import com.hellblazer.luciferase.resource.opengl.BufferResource;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.Cleaner;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

import static org.lwjgl.opengl.GL43.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * LWJGL GPU Memory Management for ESVO Octrees
 *
 * CRITICAL: Uses LWJGL MemoryUtil.memAlignedAlloc() - NO DirectByteBuffer or Unsafe!
 * This is essential for proper GPU memory management and performance.
 *
 * Key Requirements:
 * - 64-byte alignment for optimal GPU cache performance
 * - Explicit lifecycle management (must call dispose() / close())
 * - Thread-safe operations (ReadWriteLock guards all buffer access vs. dispose)
 * - Stack allocation for temporary data
 *
 * <p>Concurrency contract: {@link #writeNode}, {@link #readNode}, {@link #getNodeBuffer},
 * {@link #uploadToGPU}, {@link #bindToShader}, and {@link #getSSBO} acquire the <em>read</em>
 * (shared) lock — multiple threads may
 * access the buffer concurrently.  {@link #dispose()} acquires the <em>write</em> (exclusive)
 * lock; it sets {@code disposed = true} <em>before</em> freeing the native buffer, so any
 * accessor that holds the read lock will see {@code disposed == true} and throw
 * {@link IllegalStateException} before touching freed memory.  An accessor that already
 * passed the guard and holds the read lock will complete normally (the native memory is still
 * live at that point because the write lock cannot be obtained until all readers exit).
 */
public final class OctreeGPUMemory implements AutoCloseable {
    private static final Logger  log     = LoggerFactory.getLogger(OctreeGPUMemory.class);
    private static final Cleaner CLEANER = Cleaner.create();

    // GPU memory alignment requirements
    private static final int GPU_ALIGNMENT  = 64; // Cache line alignment
    private static final int NODE_SIZE_BYTES = 8; // OctreeNode = 8 bytes

    private ByteBuffer nodeBuffer;
    private final long bufferSize;
    private final int  nodeCount;

    // disposed is read under the read lock (accessors) and written under the write lock
    // (dispose). volatile ensures visibility without requiring an extra lock acquisition
    // for the fast-path check inside the read-lock section.
    private volatile boolean disposed = false;

    // Guards all buffer accesses vs. dispose(). Accessors take the read (shared) lock;
    // dispose() takes the write (exclusive) lock. This closes the TOCTOU: dispose() cannot
    // free the buffer while any accessor holds the read lock, and once disposed=true is set
    // inside the write lock, subsequent accessors see it immediately.
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    // Guard shared between dispose() and the Cleaner action — ensures the native
    // buffer is freed exactly once regardless of which path runs first.
    private final AtomicBoolean freed;

    // Cleaner action — frees only the native ByteBuffer; never touches GL resources
    // (GL calls off the GL thread are unsafe and must not be registered here).
    private final Cleaner.Cleanable cleanable;

    // Resource manager for GPU resources
    private final UnifiedResourceManager resourceManager = UnifiedResourceManager.getInstance();

    // Managed GPU buffer
    private BufferResource nodeSSBO; // Shader Storage Buffer Object

    /**
     * Create GPU memory for octree nodes
     *
     * @param nodeCount Number of octree nodes to allocate
     */
    public OctreeGPUMemory(int nodeCount) {
        if (nodeCount <= 0) {
            throw new IllegalArgumentException("Node count must be positive");
        }

        this.nodeCount = nodeCount;
        this.bufferSize = (long) nodeCount * NODE_SIZE_BYTES;

        // CRITICAL: Handle large allocations that may exceed int range
        if (bufferSize > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                String.format("Octree too large for single allocation: %d bytes (max: %d)",
                              bufferSize, Integer.MAX_VALUE));
        }

        // CRITICAL: Use LWJGL aligned allocation for GPU performance
        nodeBuffer = memAlignedAlloc(GPU_ALIGNMENT, (int) bufferSize);
        if (nodeBuffer == null) {
            throw new OutOfMemoryError(
                String.format("Failed to allocate GPU buffer of size: %d bytes", bufferSize));
        }

        // Initialize buffer to zero for consistent state
        memSet(nodeBuffer, (byte) 0);

        // Shared one-shot guard: whichever path runs first (dispose() or Cleaner at GC) wins.
        // The guard and buffer reference are captured by value — must NOT capture 'this'.
        this.freed = new AtomicBoolean(false);
        ByteBuffer bufferRef = nodeBuffer;
        AtomicBoolean freedRef = this.freed;
        this.cleanable = CLEANER.register(this, () -> {
            if (freedRef.compareAndSet(false, true)) {
                log.warn("OctreeGPUMemory was not closed — native buffer freed by Cleaner (GL resources may leak)");
                memAlignedFree(bufferRef);
            }
            // else: dispose() already freed — no-op, no double-free
        });

        log.debug("Allocated octree GPU memory: {} nodes, {} bytes, {}-byte aligned",
                  nodeCount, bufferSize, GPU_ALIGNMENT);
    }

    /**
     * Upload node data to GPU as Shader Storage Buffer Object.
     *
     * <p>Acquires the read lock around both the disposed-check and the GL calls.
     */
    public void uploadToGPU() {
        lock.readLock().lock();
        try {
            if (disposed) {
                throw new IllegalStateException("GPU memory has been disposed");
            }

            if (nodeSSBO == null) {
                // Create storage buffer using resource manager
                nodeSSBO = resourceManager.createStorageBuffer((int) bufferSize, "OctreeNodeSSBO");
            }

            // Upload data to the buffer
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, nodeSSBO.getOpenGLId());
            glBufferData(GL_SHADER_STORAGE_BUFFER, nodeBuffer, GL_STATIC_DRAW);

            // Check for OpenGL errors
            int error = glGetError();
            if (error != GL_NO_ERROR) {
                throw new RuntimeException(
                    String.format("OpenGL error during buffer upload: 0x%X", error));
            }

            log.debug("Uploaded {} bytes to GPU SSBO {}", bufferSize, nodeSSBO.getOpenGLId());
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Bind the node buffer to a shader storage binding point.
     *
     * <p>Acquires the read lock around both the disposed-check and the {@code nodeSSBO} access,
     * consistent with the rest of the accessors.  This closes the race with {@link #dispose()}
     * that nulls {@code nodeSSBO} under the write lock: without the read lock, a concurrent
     * dispose between the null-check and {@code getOpenGLId()} would produce an NPE.
     *
     * @param bindingPoint Binding point index (matches shader layout binding)
     */
    public void bindToShader(int bindingPoint) {
        lock.readLock().lock();
        try {
            if (disposed) {
                throw new IllegalStateException("GPU memory has been disposed");
            }

            if (nodeSSBO == null) {
                throw new IllegalStateException("Buffer not uploaded to GPU yet");
            }

            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, bindingPoint, nodeSSBO.getOpenGLId());
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Write node data directly to CPU buffer.
     *
     * <p>Acquires the read lock around both the disposed-check and the buffer write.
     *
     * @param nodeIndex          Index of the node to write
     * @param childDescriptor    First 32 bits of node
     * @param contourDescriptor  Second 32 bits of node
     */
    public void writeNode(int nodeIndex, int childDescriptor, int contourDescriptor) {
        lock.readLock().lock();
        try {
            if (disposed) {
                throw new IllegalStateException("GPU memory has been disposed");
            }

            if (nodeIndex < 0 || nodeIndex >= nodeCount) {
                throw new IndexOutOfBoundsException(
                    String.format("Node index %d out of range [0, %d)", nodeIndex, nodeCount));
            }

            int offset = nodeIndex * NODE_SIZE_BYTES;
            nodeBuffer.putInt(offset, childDescriptor);
            nodeBuffer.putInt(offset + 4, contourDescriptor);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Read node data from CPU buffer.
     *
     * <p>Acquires the read lock around both the disposed-check and the buffer read.
     *
     * @param nodeIndex Index of the node to read
     * @return Array of [childDescriptor, contourDescriptor]
     */
    public int[] readNode(int nodeIndex) {
        lock.readLock().lock();
        try {
            if (disposed) {
                throw new IllegalStateException("GPU memory has been disposed");
            }

            if (nodeIndex < 0 || nodeIndex >= nodeCount) {
                throw new IndexOutOfBoundsException(
                    String.format("Node index %d out of range [0, %d)", nodeIndex, nodeCount));
            }

            int offset = nodeIndex * NODE_SIZE_BYTES;
            int childDescriptor = nodeBuffer.getInt(offset);
            int contourDescriptor = nodeBuffer.getInt(offset + 4);

            return new int[]{ childDescriptor, contourDescriptor };
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get direct access to the underlying ByteBuffer (read-only view).
     *
     * <p>Acquires the read lock around both the disposed-check and the snapshot.
     * WARNING: The returned buffer is a snapshot; do not retain beyond the calling frame.
     */
    public ByteBuffer getNodeBuffer() {
        lock.readLock().lock();
        try {
            if (disposed) {
                throw new IllegalStateException("GPU memory has been disposed");
            }
            return nodeBuffer.asReadOnlyBuffer();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get the GPU buffer object ID (for direct OpenGL use).
     *
     * <p>Acquires the read lock around both the disposed-check and the {@code nodeSSBO} access,
     * consistent with the rest of the accessors.  Without the read lock, a concurrent
     * {@link #dispose()} between the null-check and {@code getOpenGLId()} would produce an NPE.
     * Returns 0 if the buffer has not been uploaded yet.
     */
    public int getSSBO() {
        lock.readLock().lock();
        try {
            if (disposed) {
                throw new IllegalStateException("GPU memory has been disposed");
            }
            return nodeSSBO != null ? nodeSSBO.getOpenGLId() : 0;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get number of nodes allocated
     */
    public int getNodeCount() {
        return nodeCount;
    }

    /**
     * Get total buffer size in bytes
     */
    public long getBufferSize() {
        return bufferSize;
    }

    /**
     * Check if memory has been disposed
     */
    public boolean isDisposed() {
        return disposed;
    }

    /**
     * Dispose GPU memory — MUST be called to avoid memory leaks.
     *
     * <p>Idempotent and thread-safe. Acquires the write (exclusive) lock, sets
     * {@code disposed = true} <em>before</em> freeing the native buffer, then releases
     * the lock. This ensures no accessor can touch freed memory: an accessor that has
     * not yet acquired the read lock will observe {@code disposed == true} and throw;
     * an accessor already inside the read-lock critical section will finish normally
     * because the write lock blocks until all readers exit (at which point the native
     * memory is still live).
     */
    public void dispose() {
        lock.writeLock().lock();
        try {
            if (disposed) {
                return;
            }

            // Set disposed=true FIRST, inside the write lock, before any free.
            // Any accessor waiting for the read lock after this point will see disposed==true.
            disposed = true;

            try {
                // Delete GPU buffer using resource manager
                if (nodeSSBO != null) {
                    nodeSSBO.close();
                    nodeSSBO = null;
                }
            } catch (Exception e) {
                log.error("Error disposing GPU buffer", e);
            }

            // Free native buffer exactly once — shared guard prevents double-free with Cleaner.
            if (freed.compareAndSet(false, true)) {
                memAlignedFree(nodeBuffer);
            }
            nodeBuffer = null;

            // Deregister the Cleaner. The action is a no-op at this point (freed==true),
            // but calling clean() removes the phantom reference from the Cleaner queue.
            cleanable.clean();

            log.debug("Disposed octree GPU memory: {} nodes, {} bytes", nodeCount, bufferSize);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Implements {@link AutoCloseable}. Delegates to {@link #dispose()}.
     * Idempotent — double-close is a no-op.
     */
    @Override
    public void close() {
        dispose();
    }

    // === Static Utility Methods ===

    /**
     * Execute an operation with stack-allocated temporary memory
     * Perfect for short-lived GPU operations and uniform uploads
     *
     * @param operation Operation to execute with memory stack
     */
    public static void withStackAllocation(Consumer<MemoryStack> operation) {
        try (MemoryStack stack = stackPush()) {
            operation.accept(stack);
        }
    }

    /**
     * Calculate optimal buffer size with alignment
     *
     * @param requestedSize Requested size in bytes
     * @param alignment     Required alignment (must be power of 2)
     * @return Aligned size >= requestedSize
     */
    public static long calculateAlignedSize(long requestedSize, int alignment) {
        if (alignment <= 0 || (alignment & (alignment - 1)) != 0) {
            throw new IllegalArgumentException("Alignment must be positive power of 2");
        }
        return (requestedSize + alignment - 1) & ~(alignment - 1);
    }

    /**
     * Validate that an address is properly aligned
     *
     * @param address   Memory address to check
     * @param alignment Required alignment
     * @return true if address is aligned
     */
    public static boolean isAligned(long address, int alignment) {
        return (address & (alignment - 1)) == 0;
    }

    /**
     * Create a memory pool for managing multiple GPU buffers
     * Useful for dynamic octree operations with frequent allocation/deallocation
     */
    public static final class MemoryPool {
        private static final Logger log = LoggerFactory.getLogger(MemoryPool.class);

        // Memory pool implementation would go here
        // This is a placeholder for future optimization if needed

        public OctreeGPUMemory acquire(int nodeCount) {
            // For now, just create new memory - can optimize later with pooling
            return new OctreeGPUMemory(nodeCount);
        }

        public void release(OctreeGPUMemory memory) {
            if (memory != null && !memory.isDisposed()) {
                memory.dispose();
            }
        }
    }
}
