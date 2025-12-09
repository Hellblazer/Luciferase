# Detailed Resource Lifecycle Management Execution Plan

## Project Overview

**Goal**: Implement proper RAII patterns and resource lifecycle management to eliminate memory leaks, race conditions, and resource exhaustion issues in render and gpu-test-framework modules.

**Timeline**: 10 weeks (70 days)
**Modules Affected**: render (83 files), gpu-test-framework (18 files)
**Risk Level**: High (production stability critical)
**Rollback Strategy**: Git branches per phase with feature flags

---

## PHASE 1: Foundation Infrastructure (Days 1-14)

### Day 1-3: Project Setup & Package Structure

#### Tasks

```text

□ Create feature branch: resource-mgmt-phase-1
□ Create package: com.hellblazer.luciferase.resource
□ Create package: com.hellblazer.luciferase.resource.gpu
□ Create package: com.hellblazer.luciferase.resource.opencl
□ Create package: com.hellblazer.luciferase.resource.memory
□ Set up Maven dependencies for resource module
□ Configure logging for resource tracking

```text

#### Deliverables:

- Package structure created
- Maven configuration updated
- Initial commit with structure

### Day 4-7: Core Resource Management Classes

#### Task 1.1: ResourceHandle Base Class

```java

// File: ResourceHandle.java
package com.hellblazer.luciferase.resource;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class ResourceHandle<T> implements AutoCloseable {
    private final T resource;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final String resourceType;
    private final long creationTime;
    private final StackTraceElement[] creationStack;
    
    protected ResourceHandle(T resource, String resourceType) {
        this.resource = Objects.requireNonNull(resource, "Resource cannot be null");
        this.resourceType = Objects.requireNonNull(resourceType, "Resource type cannot be null");
        this.creationTime = System.nanoTime();
        this.creationStack = Thread.currentThread().getStackTrace();
        ResourceTracker.register(this);
    }
    
    public final T get() {
        ensureNotClosed();
        return resource;
    }
    
    public final boolean isClosed() {
        return closed.get();
    }
    
    @Override
    public final void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                cleanup(resource);
            } catch (Exception e) {
                throw new ResourceLifecycleException(
                    "Failed to cleanup " + resourceType, e);
            } finally {
                ResourceTracker.unregister(this);
            }
        }
    }
    
    protected abstract void cleanup(T resource) throws Exception;
    
    private void ensureNotClosed() {
        if (closed.get()) {
            throw new IllegalStateException(resourceType + " has been closed");
        }
    }
    
    @Override
    protected void finalize() throws Throwable {
        if (!closed.get()) {
            ResourceTracker.reportLeak(this, creationStack);
        }
    }
}

```text

#### Task 1.2: CompositeResourceManager

```java

// File: CompositeResourceManager.java
package com.hellblazer.luciferase.resource;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CompositeResourceManager implements AutoCloseable {
    private final List<AutoCloseable> resources = new ArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final String name;
    
    public CompositeResourceManager(String name) {
        this.name = name;
    }
    
    public <T extends AutoCloseable> T manage(T resource) {
        if (closed.get()) {
            closeQuietly(resource);
            throw new IllegalStateException("Manager " + name + " is closed");
        }
        
        synchronized (resources) {
            resources.add(resource);
        }
        return resource;
    }
    
    public <T extends AutoCloseable> T manage(
            Supplier<T> allocator, String resourceName) {
        try {
            return manage(allocator.get());
        } catch (Exception e) {
            throw new ResourceLifecycleException(
                "Failed to allocate " + resourceName, e);
        }
    }
    
    public List<AutoCloseable> detach() {
        synchronized (resources) {
            List<AutoCloseable> detached = new ArrayList<>(resources);
            resources.clear();
            return detached;
        }
    }
    
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            List<Exception> exceptions = new ArrayList<>();
            
            synchronized (resources) {
                // Close in reverse order (LIFO)
                Collections.reverse(resources);
                for (AutoCloseable resource : resources) {
                    try {
                        resource.close();
                    } catch (Exception e) {
                        exceptions.add(e);
                    }
                }
                resources.clear();
            }
            
            if (!exceptions.isEmpty()) {
                ResourceLifecycleException rle = new ResourceLifecycleException(
                    "Failed to close " + exceptions.size() + " resources in " + name);
                exceptions.forEach(rle::addSuppressed);
                throw rle;
            }
        }
    }
    
    private static void closeQuietly(AutoCloseable resource) {
        try {
            if (resource != null) {
                resource.close();
            }
        } catch (Exception ignored) {
            // Quietly close during error conditions
        }
    }
}

```text

#### Task 1.3: ResourceTracker

```java

// File: ResourceTracker.java
package com.hellblazer.luciferase.resource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class ResourceTracker {
    private static final Logger log = LoggerFactory.getLogger(ResourceTracker.class);
    private static final boolean ENABLED = Boolean.getBoolean("gpu.resource.tracking");
    private static final boolean VERBOSE = Boolean.getBoolean("gpu.resource.tracking.verbose");
    
    private static final Set<ResourceInfo> activeResources = 
        Collections.newSetFromMap(new ConcurrentHashMap<>());
    
    private static final AtomicLong totalAllocated = new AtomicLong();
    private static final AtomicLong totalFreed = new AtomicLong();
    private static final AtomicLong peakActive = new AtomicLong();
    
    private record ResourceInfo(
        String type,
        long creationTime,
        Thread creationThread,
        StackTraceElement[] stackTrace,
        WeakReference<Object> reference
    ) {}
    
    public static void register(Object resource) {
        if (!ENABLED) return;
        
        long allocated = totalAllocated.incrementAndGet();
        
        ResourceInfo info = new ResourceInfo(
            resource.getClass().getSimpleName(),
            System.nanoTime(),
            Thread.currentThread(),
            Thread.currentThread().getStackTrace(),
            new WeakReference<>(resource)
        );
        
        activeResources.add(info);
        
        long active = activeResources.size();
        updatePeak(active);
        
        if (VERBOSE) {
            log.debug("Resource allocated: {} (total: {}, active: {})",
                     info.type, allocated, active);
        }
    }
    
    public static void unregister(Object resource) {
        if (!ENABLED) return;
        
        totalFreed.incrementAndGet();
        activeResources.removeIf(info -> {
            Object ref = info.reference.get();
            return ref == null || ref == resource;
        });
        
        if (VERBOSE) {
            log.debug("Resource freed: {} (active: {})",
                     resource.getClass().getSimpleName(), 
                     activeResources.size());
        }
    }
    
    public static void reportLeak(Object resource, StackTraceElement[] creationStack) {
        log.error("RESOURCE LEAK DETECTED: {} was not properly closed", 
                 resource.getClass().getName());
        
        if (ENABLED && creationStack != null) {
            log.error("Resource created at:");
            for (int i = 0; i < Math.min(10, creationStack.length); i++) {
                log.error("  at {}", creationStack[i]);
            }
        }
    }
    
    public static ResourceStats getStats() {
        return new ResourceStats(
            totalAllocated.get(),
            totalFreed.get(),
            activeResources.size(),
            peakActive.get()
        );
    }
    
    public record ResourceStats(
        long totalAllocated,
        long totalFreed,
        long currentlyActive,
        long peakActive
    ) {
        public double leakRate() {
            return totalAllocated > 0 ? 
                (double)(totalAllocated - totalFreed) / totalAllocated : 0;
        }
    }
    
    private static void updatePeak(long active) {
        long peak;
        do {
            peak = peakActive.get();
        } while (active > peak && !peakActive.compareAndSet(peak, active));
    }
    
    static {
        if (ENABLED) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (!activeResources.isEmpty()) {
                    log.error("=== RESOURCE LEAK REPORT ===");
                    log.error("Shutdown with {} unclosed resources", activeResources.size());
                    
                    Map<String, Integer> leaksByType = new HashMap<>();
                    for (ResourceInfo info : activeResources) {
                        leaksByType.merge(info.type, 1, Integer::sum);
                    }
                    
                    log.error("Leaks by type:");
                    leaksByType.forEach((type, count) -> 
                        log.error("  {}: {} instances", type, count));
                    
                    log.error("Statistics: {}", getStats());
                }
            }, "ResourceTracker-Shutdown"));
        }
    }
}

```text

### Day 8-10: GPU Resource Handles

#### Task 1.4: OpenGL Resource Handles

```java

// File: GLBufferHandle.java
package com.hellblazer.luciferase.resource.gpu;

import static org.lwjgl.opengl.GL43.*;

public final class GLBufferHandle extends ResourceHandle<Integer> {
    private final int target;
    private final long size;
    
    public static GLBufferHandle create(int target, long size, int usage) {
        int buffer = glGenBuffers();
        if (buffer == 0) {
            throw new ResourceAllocationException("Failed to generate GL buffer");
        }
        
        glBindBuffer(target, buffer);
        glBufferData(target, size, usage);
        
        int error = glGetError();
        if (error != GL_NO_ERROR) {
            glDeleteBuffers(buffer);
            throw new ResourceAllocationException(
                "Failed to allocate GL buffer: error 0x" + Integer.toHexString(error));
        }
        
        return new GLBufferHandle(buffer, target, size);
    }
    
    private GLBufferHandle(int buffer, int target, long size) {
        super(buffer, "GLBuffer");
        this.target = target;
        this.size = size;
    }
    
    @Override
    protected void cleanup(Integer buffer) {
        glDeleteBuffers(buffer);
    }
    
    public void bind() {
        glBindBuffer(target, get());
    }
    
    public long size() {
        return size;
    }
}

// File: GLTextureHandle.java
package com.hellblazer.luciferase.resource.gpu;

public final class GLTextureHandle extends ResourceHandle<Integer> {
    private final int target;
    private final int width;
    private final int height;
    
    public static GLTextureHandle create2D(int width, int height, 
                                          int internalFormat, int format, int type) {
        int texture = glGenTextures();
        if (texture == 0) {
            throw new ResourceAllocationException("Failed to generate GL texture");
        }
        
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexImage2D(GL_TEXTURE_2D, 0, internalFormat, 
                    width, height, 0, format, type, (ByteBuffer)null);
        
        int error = glGetError();
        if (error != GL_NO_ERROR) {
            glDeleteTextures(texture);
            throw new ResourceAllocationException(
                "Failed to create texture: error 0x" + Integer.toHexString(error));
        }
        
        return new GLTextureHandle(texture, GL_TEXTURE_2D, width, height);
    }
    
    private GLTextureHandle(int texture, int target, int width, int height) {
        super(texture, "GLTexture");
        this.target = target;
        this.width = width;
        this.height = height;
    }
    
    @Override
    protected void cleanup(Integer texture) {
        glDeleteTextures(texture);
    }
    
    public void bind() {
        glBindTexture(target, get());
    }
    
    public void bindImage(int unit, int access, int format) {
        glBindImageTexture(unit, get(), 0, false, 0, access, format);
    }
}

// File: GLProgramHandle.java
package com.hellblazer.luciferase.resource.gpu;

public final class GLProgramHandle extends ResourceHandle<Integer> {
    
    public static class Builder {
        private final CompositeResourceManager resources = new CompositeResourceManager("ShaderBuilder");
        private final int program;
        
        public Builder() {
            this.program = glCreateProgram();
            if (program == 0) {
                throw new ResourceAllocationException("Failed to create GL program");
            }
        }
        
        public Builder attachShader(int type, String source) {
            int shader = resources.manage(new ShaderHandle(type, source)).get();
            glAttachShader(program, shader);
            return this;
        }
        
        public GLProgramHandle build() {
            glLinkProgram(program);
            
            int[] status = new int[1];
            glGetProgramiv(program, GL_LINK_STATUS, status);
            
            if (status[0] == GL_FALSE) {
                String log = glGetProgramInfoLog(program);
                glDeleteProgram(program);
                resources.close();
                throw new ResourceAllocationException("Failed to link program: " + log);
            }
            
            // Detach and delete shaders after successful link
            resources.close();
            
            return new GLProgramHandle(program);
        }
    }
    
    private static class ShaderHandle extends ResourceHandle<Integer> {
        ShaderHandle(int type, String source) {
            super(compileShader(type, source), "GLShader");
        }
        
        private static int compileShader(int type, String source) {
            int shader = glCreateShader(type);
            if (shader == 0) {
                throw new ResourceAllocationException("Failed to create shader");
            }
            
            glShaderSource(shader, source);
            glCompileShader(shader);
            
            int[] status = new int[1];
            glGetShaderiv(shader, GL_COMPILE_STATUS, status);
            
            if (status[0] == GL_FALSE) {
                String log = glGetShaderInfoLog(shader);
                glDeleteShader(shader);
                throw new ResourceAllocationException("Failed to compile shader: " + log);
            }
            
            return shader;
        }
        
        @Override
        protected void cleanup(Integer shader) {
            glDeleteShader(shader);
        }
    }
    
    private GLProgramHandle(int program) {
        super(program, "GLProgram");
    }
    
    @Override
    protected void cleanup(Integer program) {
        glDeleteProgram(program);
    }
    
    public void use() {
        glUseProgram(get());
    }
}

```text

### Day 11-14: Memory Management Utilities

#### Task 1.5: Native Memory Handles

```java

// File: NativeMemoryHandle.java
package com.hellblazer.luciferase.resource.memory;

import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;

public final class NativeMemoryHandle extends ResourceHandle<ByteBuffer> {
    private final long size;
    private final int alignment;
    
    public static NativeMemoryHandle allocate(long size) {
        ByteBuffer buffer = MemoryUtil.memAlloc(Math.toIntExact(size));
        if (buffer == null) {
            throw new OutOfMemoryError("Failed to allocate " + size + " bytes");
        }
        return new NativeMemoryHandle(buffer, size, 0);
    }
    
    public static NativeMemoryHandle allocateAligned(int alignment, long size) {
        if (alignment <= 0 || (alignment & (alignment - 1)) != 0) {
            throw new IllegalArgumentException(
                "Alignment must be positive power of 2: " + alignment);
        }
        
        ByteBuffer buffer = MemoryUtil.memAlignedAlloc(alignment, Math.toIntExact(size));
        if (buffer == null) {
            throw new OutOfMemoryError(
                "Failed to allocate " + size + " bytes with alignment " + alignment);
        }
        
        return new NativeMemoryHandle(buffer, size, alignment);
    }
    
    private NativeMemoryHandle(ByteBuffer buffer, long size, int alignment) {
        super(buffer, "NativeMemory[" + size + " bytes]");
        this.size = size;
        this.alignment = alignment;
    }
    
    @Override
    protected void cleanup(ByteBuffer buffer) {
        if (alignment > 0) {
            MemoryUtil.memAlignedFree(buffer);
        } else {
            MemoryUtil.memFree(buffer);
        }
    }
    
    public long size() {
        return size;
    }
    
    public int alignment() {
        return alignment;
    }
}

// File: MemoryPool.java
package com.hellblazer.luciferase.resource.memory;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public final class MemoryPool implements AutoCloseable {
    private final int blockSize;
    private final int maxBlocks;
    private final int alignment;
    private final Queue<NativeMemoryHandle> available = new ConcurrentLinkedQueue<>();
    private final Set<NativeMemoryHandle> inUse = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final AtomicInteger totalBlocks = new AtomicInteger(0);
    private volatile boolean closed = false;
    
    public MemoryPool(int blockSize, int maxBlocks, int alignment) {
        this.blockSize = blockSize;
        this.maxBlocks = maxBlocks;
        this.alignment = alignment;
    }
    
    public PooledMemory acquire() {
        if (closed) {
            throw new IllegalStateException("Pool is closed");
        }
        
        NativeMemoryHandle handle = available.poll();
        if (handle == null) {
            if (totalBlocks.get() >= maxBlocks) {
                throw new OutOfMemoryError(
                    "Memory pool exhausted: " + totalBlocks.get() + "/" + maxBlocks);
            }
            
            handle = NativeMemoryHandle.allocateAligned(alignment, blockSize);
            totalBlocks.incrementAndGet();
        }
        
        inUse.add(handle);
        return new PooledMemory(handle);
    }
    
    public class PooledMemory implements AutoCloseable {
        private final NativeMemoryHandle handle;
        private boolean released = false;
        
        private PooledMemory(NativeMemoryHandle handle) {
            this.handle = handle;
            handle.get().clear(); // Clear for reuse
        }
        
        public ByteBuffer buffer() {
            if (released) {
                throw new IllegalStateException("Memory already released");
            }
            return handle.get();
        }
        
        @Override
        public void close() {
            if (!released) {
                released = true;
                release(handle);
            }
        }
    }
    
    private void release(NativeMemoryHandle handle) {
        if (inUse.remove(handle)) {
            if (!closed && available.size() < maxBlocks) {
                available.offer(handle);
            } else {
                handle.close();
                totalBlocks.decrementAndGet();
            }
        }
    }
    
    @Override
    public void close() {
        closed = true;
        
        // Close all available blocks
        NativeMemoryHandle handle;
        while ((handle = available.poll()) != null) {
            handle.close();
        }
        
        // Close all in-use blocks (leak detection)
        if (!inUse.isEmpty()) {
            log.warn("Memory pool closed with {} blocks still in use", inUse.size());
            for (NativeMemoryHandle leaked : inUse) {
                leaked.close();
            }
            inUse.clear();
        }
    }
    
    public PoolStats getStats() {
        return new PoolStats(
            totalBlocks.get(),
            available.size(),
            inUse.size(),
            blockSize,
            maxBlocks
        );
    }
    
    public record PoolStats(
        int totalBlocks,
        int availableBlocks,
        int inUseBlocks,
        int blockSize,
        int maxBlocks
    ) {
        public double utilizationRate() {
            return totalBlocks > 0 ? (double)inUseBlocks / totalBlocks : 0;
        }
        
        public long totalMemory() {
            return (long)totalBlocks * blockSize;
        }
    }
}

```text

---

## PHASE 2: Critical Fixes (Days 15-28)

### Day 15-17: Remove Deprecated Patterns

#### Task 2.1: Fix OctreeGPUMemory

```java

// BEFORE (with finalize):
public final class OctreeGPUMemory {
    @Override
    protected void finalize() throws Throwable {
        if (!disposed) {
            log.warn("OctreeGPUMemory was not properly disposed - memory leak detected!");
            dispose();
        }
        super.finalize();
    }
}

// AFTER (with AutoCloseable):
public final class OctreeGPUMemory implements AutoCloseable {
    private final CompositeResourceManager resources = new CompositeResourceManager("OctreeGPU");
    private final NativeMemoryHandle nodeBuffer;
    private final GLBufferHandle ssbo;
    private final int nodeCount;
    
    private OctreeGPUMemory(Builder builder) {
        this.nodeCount = builder.nodeCount;
        
        // All-or-nothing allocation with automatic rollback
        this.nodeBuffer = resources.manage(
            NativeMemoryHandle.allocateAligned(GPU_ALIGNMENT, 
                                              (long)nodeCount * NODE_SIZE_BYTES));
        
        // Clear buffer for consistent state
        nodeBuffer.get().clear();
        while (nodeBuffer.get().hasRemaining()) {
            nodeBuffer.get().put((byte)0);
        }
        nodeBuffer.get().flip();
        
        // Create GPU buffer
        this.ssbo = resources.manage(
            GLBufferHandle.create(GL_SHADER_STORAGE_BUFFER, 
                                 nodeBuffer.size(), 
                                 GL_STATIC_DRAW));
        
        log.debug("Allocated octree GPU memory: {} nodes, {} bytes", 
                 nodeCount, nodeBuffer.size());
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private int nodeCount;
        
        public Builder nodeCount(int nodeCount) {
            if (nodeCount <= 0) {
                throw new IllegalArgumentException("Node count must be positive");
            }
            this.nodeCount = nodeCount;
            return this;
        }
        
        public OctreeGPUMemory build() {
            return new OctreeGPUMemory(this);
        }
    }
    
    public void uploadToGPU() {
        ssbo.bind();
        glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, nodeBuffer.get());
        
        int error = glGetError();
        if (error != GL_NO_ERROR) {
            throw new RuntimeException(
                "OpenGL error during buffer upload: 0x" + Integer.toHexString(error));
        }
    }
    
    public void bindToShader(int bindingPoint) {
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, bindingPoint, ssbo.get());
    }
    
    @Override
    public void close() {
        resources.close();
    }
}

```text

### Day 18-20: Fix Static Mutable State

#### Task 2.2: Fix CICompatibleGPUTest

```java

// BEFORE (with static mutable state):
public abstract class CICompatibleGPUTest {
    private static Boolean isOpenCLAvailable = null; // Race condition!
    
    protected static boolean checkOpenCLAvailability() {
        if (isOpenCLAvailable == null) {
            // Race condition here!
            isOpenCLAvailable = detectOpenCL();
        }
        return isOpenCLAvailable;
    }
}

// AFTER (thread-safe):
public abstract class CICompatibleGPUTest {
    private static final class OpenCLAvailability {
        private static final boolean AVAILABLE = detectOpenCL();
        
        private static boolean detectOpenCL() {
            try {
                Configuration.OPENCL_EXPLICIT_INIT.set(true);
                CL.create();
                CL.destroy();
                return true;
            } catch (Throwable t) {
                log.info("OpenCL not available: {}", t.getMessage());
                return false;
            }
        }
    }
    
    protected static boolean isOpenCLAvailable() {
        return OpenCLAvailability.AVAILABLE;
    }
    
    // Per-test resource management
    private CompositeResourceManager testResources;
    
    @BeforeEach
    void setupGPUTest() {
        testResources = new CompositeResourceManager("Test-" + getClass().getSimpleName());
    }
    
    @AfterEach
    void teardownGPUTest() {
        if (testResources != null) {
            testResources.close();
            testResources = null;
        }
        
        // Verify no leaks in debug mode
        if (Boolean.getBoolean("gpu.resource.tracking")) {
            ResourceTracker.ResourceStats stats = ResourceTracker.getStats();
            if (stats.currentlyActive() > 0) {
                log.warn("Test completed with {} active resources", stats.currentlyActive());
            }
        }
    }
    
    protected <T extends AutoCloseable> T allocateTestResource(Supplier<T> allocator) {
        return testResources.manage(allocator.get());
    }
}

```text

### Day 21-24: Native Memory Safety

#### Task 2.3: Wrap Memory Allocations

```java

// Create SafeMemoryAllocator.java
package com.hellblazer.luciferase.resource.memory;

public final class SafeMemoryAllocator {
    
    public static ByteBuffer allocateDirect(int capacity) {
        try {
            return ByteBuffer.allocateDirect(capacity);
        } catch (OutOfMemoryError e) {
            logMemoryState();
            throw new ResourceAllocationException(
                "Failed to allocate direct buffer of size " + capacity, e);
        }
    }
    
    public static NativeMemoryHandle allocateNative(long size) {
        try {
            return NativeMemoryHandle.allocate(size);
        } catch (OutOfMemoryError e) {
            logMemoryState();
            throw new ResourceAllocationException(
                "Failed to allocate native memory of size " + size, e);
        }
    }
    
    public static NativeMemoryHandle allocateAligned(int alignment, long size) {
        try {
            return NativeMemoryHandle.allocateAligned(alignment, size);
        } catch (OutOfMemoryError e) {
            logMemoryState();
            throw new ResourceAllocationException(
                "Failed to allocate aligned memory: size=" + size + 
                ", alignment=" + alignment, e);
        }
    }
    
    private static void logMemoryState() {
        Runtime rt = Runtime.getRuntime();
        long maxMemory = rt.maxMemory();
        long totalMemory = rt.totalMemory();
        long freeMemory = rt.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        
        log.error("Memory allocation failed. Current state:");
        log.error("  Max memory: {} MB", maxMemory / (1024 * 1024));
        log.error("  Total memory: {} MB", totalMemory / (1024 * 1024));
        log.error("  Used memory: {} MB", usedMemory / (1024 * 1024));
        log.error("  Free memory: {} MB", freeMemory / (1024 * 1024));
        
        if (ResourceTracker.ENABLED) {
            ResourceStats stats = ResourceTracker.getStats();
            log.error("  Active resources: {}", stats.currentlyActive());
            log.error("  Peak resources: {}", stats.peakActive());
        }
    }
}

```text

### Day 25-28: Exception Safety Patterns

#### Task 2.4: Exception-Safe Initialization

```java

// Template for exception-safe initialization
public final class ComputeShaderRenderer implements AutoCloseable {
    private final CompositeResourceManager resources = new CompositeResourceManager("ComputeShader");
    private final GLProgramHandle program;
    private final GLBufferHandle cameraUBO;
    private final GLTextureHandle outputTexture;
    
    private ComputeShaderRenderer(Builder builder) {
        boolean success = false;
        try {
            // Step 1: Compile shaders (may throw)
            this.program = resources.manage(compileProgram(builder.shaderSource));
            
            // Step 2: Create uniform buffer (may throw)
            this.cameraUBO = resources.manage(
                GLBufferHandle.create(GL_UNIFORM_BUFFER, CAMERA_UBO_SIZE, GL_DYNAMIC_DRAW));
            
            // Step 3: Create output texture (may throw)
            this.outputTexture = resources.manage(
                GLTextureHandle.create2D(builder.width, builder.height, 
                                        GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE));
            
            // All resources allocated successfully
            success = true;
            
        } finally {
            if (!success) {
                // Automatic cleanup on failure
                resources.close();
            }
        }
    }
    
    @Override
    public void close() {
        resources.close();
    }
}

```text

---

## PHASE 3: Render Module Refactoring (Days 29-42)

### Day 29-35: Core Render Classes

#### Task 3.1: Refactor Critical Classes

- OctreeGPUMemory.java - Complete AutoCloseable conversion
- ComputeShaderRenderer.java - Builder pattern with resources
- ESVOCPUBuilder.java - Thread pool management
- OctreeBuilder.java - Resource tracking
- AdvancedRayTraversal.java - Memory safety

### Day 36-42: I/O and Optimization Classes

#### Task 3.2: I/O Resource Management

- ESVOSerializer.java - Try-with-resources
- ESVODeserializer.java - Channel cleanup
- ESVOStreamWriter.java - Buffer management
- ESVOMemoryMappedWriter.java - MappedByteBuffer safety

---

## PHASE 4: GPU Test Framework (Days 43-49)

### Day 43-46: Test Base Classes

#### Task 4.1: Base Class Refactoring

- LWJGLHeadlessTest.java - Resource lifecycle
- OpenCLHeadlessTest.java - CL resource management
- GPUComputeHeadlessTest.java - Platform cleanup
- CICompatibleGPUTest.java - Test isolation

### Day 47-49: Mock and Support Classes

#### Task 4.2: Mock Platform Updates

- MockPlatform.java - Resource simulation
- PlatformTestSupport.java - Cleanup verification
- TestSupportMatrix.java - Resource tracking

---

## PHASE 5: Testing & Validation (Days 50-63)

### Day 50-56: Test Suite Development

#### Task 5.1: Resource Lifecycle Tests

```java

@Test
class ResourceLifecycleTest {
    
    @Test
    void testResourceHandleLifecycle() {
        AtomicBoolean cleaned = new AtomicBoolean(false);
        
        try (ResourceHandle<String> handle = new ResourceHandle<>("test", "TestResource") {
            @Override
            protected void cleanup(String resource) {
                cleaned.set(true);
            }
        }) {
            assertEquals("test", handle.get());
            assertFalse(handle.isClosed());
        }
        
        assertTrue(cleaned.get());
    }
    
    @Test
    void testCompositeManagerRollback() {
        AtomicInteger allocations = new AtomicInteger(0);
        AtomicInteger cleanups = new AtomicInteger(0);
        
        assertThrows(ResourceAllocationException.class, () -> {
            try (CompositeResourceManager manager = new CompositeResourceManager("test")) {
                manager.manage(new CountingResource(allocations, cleanups));
                manager.manage(new CountingResource(allocations, cleanups));
                
                // This allocation fails
                manager.manage(() -> {
                    throw new ResourceAllocationException("Simulated failure");
                }, "failing-resource");
            }
        });
        
        assertEquals(2, allocations.get());
        assertEquals(2, cleanups.get()); // Both cleaned up
    }
    
    @Test
    void testMemoryPoolExhaustion() {
        try (MemoryPool pool = new MemoryPool(1024, 2, 64)) {
            PooledMemory mem1 = pool.acquire();
            PooledMemory mem2 = pool.acquire();
            
            // Pool exhausted
            assertThrows(OutOfMemoryError.class, () -> pool.acquire());
            
            mem1.close();
            
            // Can acquire again after release
            PooledMemory mem3 = pool.acquire();
            assertNotNull(mem3);
        }
    }
}

```text

### Day 57-63: Stress Testing

#### Task 5.2: Memory Leak Detection

- 24-hour continuous allocation/deallocation test
- Multi-threaded resource contention test
- Exception injection during cleanup
- Platform-specific resource tests

---

## PHASE 6: Documentation & Rollout (Days 64-70)

### Day 64-66: Documentation

#### Task 6.1: Developer Documentation

- Resource Management Guide
- Migration Guide for existing code
- Best Practices document
- API reference documentation

### Day 67-70: Production Rollout

#### Task 6.2: Staged Deployment

1. Enable in development environment
2. Monitor resource metrics
3. Progressive rollout to staging
4. Production deployment with monitoring

---

## Validation Checklist

### Pre-Phase Validation

- [ ] Git branch created
- [ ] Dependencies updated
- [ ] CI/CD pipeline ready
- [ ] Rollback plan documented

### Per-Phase Validation

- [ ] All tests passing
- [ ] No new memory leaks detected
- [ ] Performance benchmarks acceptable
- [ ] Code review completed
- [ ] Documentation updated

### Post-Phase Validation

- [ ] Integration tests passing
- [ ] Stress tests completed
- [ ] Resource metrics within limits
- [ ] No production incidents

---

## Risk Matrix

| Risk | Probability | Impact | Mitigation |
| ------ | ------------ | -------- | ------------ |
| Breaking changes | Medium | High | Feature flags, gradual rollout |
| Performance regression | Low | Medium | Benchmarking, profiling |
| Platform incompatibility | Low | High | Multi-platform testing |
| Incomplete refactoring | Medium | High | Phased approach, validation |
| Resource tracking overhead | Low | Low | Optional debug mode |

---

## Success Metrics

### Quantitative Metrics

- **Memory Leaks**: 0 in 24-hour test
- **Resource Cleanup Rate**: 100%
- **Test Pass Rate**: 100%
- **Performance Overhead**: <1ms
- **Thread Safety Issues**: 0

### Qualitative Metrics

- Code maintainability improved
- Developer confidence increased
- Production stability enhanced
- Technical debt reduced

---

## Command Reference

### Build Commands

```bash

# Run with resource tracking

mvn test -Dgpu.resource.tracking=true -Dgpu.resource.tracking.verbose=true

# Memory leak detection

mvn test -Dtest=ResourceLifecycleTest -Dgpu.resource.tracking=true

# Stress testing

mvn test -Dtest=ResourceStressTest -DforkCount=0 -DreuseForks=false

# Performance profiling

mvn test -Dtest=ResourcePerformanceTest -Djava.compiler=NONE

```text

### Monitoring Commands

```bash

# Check resource stats

jcmd <pid> VM.native_memory summary

# Heap dump for leak analysis

jcmd <pid> GC.heap_dump /tmp/heap.hprof

# Thread dump for deadlock detection

jcmd <pid> Thread.print

```text

---

## Conclusion

This detailed execution plan provides a systematic approach to implementing proper resource lifecycle management. The phased approach minimizes risk while ensuring comprehensive coverage of all resource management issues. Each phase builds on the previous one, with clear validation criteria and rollback procedures.

The plan addresses all critical issues identified in the analysis:

- Eliminates deprecated finalize() patterns
- Implements RAII with AutoCloseable
- Ensures exception safety
- Provides thread-safe resource management
- Enables leak detection and monitoring

Following this plan will transform the modules from having critical resource management failures to having robust, production-ready lifecycle management.
