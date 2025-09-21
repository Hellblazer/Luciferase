package com.hellblazer.luciferase.resource;

import com.hellblazer.luciferase.resource.gpu.*;
import com.hellblazer.luciferase.resource.opencl.*;
import com.hellblazer.luciferase.resource.memory.*;
import com.hellblazer.luciferase.resource.opengl.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Unified resource manager for all GPU resources across OpenGL and OpenCL
 */
public class UnifiedResourceManager implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(UnifiedResourceManager.class);
    
    // Singleton instance
    private static volatile UnifiedResourceManager instance;
    private static final Object instanceLock = new Object();
    
    private final Map<UUID, GPUResource> resources = new ConcurrentHashMap<>();
    private final Map<ByteBuffer, UUID> bufferToIdMap = Collections.synchronizedMap(new IdentityHashMap<>()); // Identity-based ByteBuffer to UUID mapping
    private final Map<GPUResourceType, AtomicLong> allocatedBytesPerType = new ConcurrentHashMap<>();
    private final ResourceTracker tracker;
    private final MemoryPool memoryPool;
    private final ResourceConfiguration config;
    private final AtomicLong totalAllocatedBytes = new AtomicLong(0);
    private final AtomicInteger activeResourceCount = new AtomicInteger(0);
    private final AtomicInteger allocationCount = new AtomicInteger(0); // Debug counter
    private final AtomicInteger releaseCount = new AtomicInteger(0); // Debug counter
    private volatile boolean closed = false;
    
    /**
     * Create a unified resource manager with default configuration
     */
    public UnifiedResourceManager() {
        this(ResourceConfiguration.defaultConfig());
    }
    
    /**
     * Create a unified resource manager with custom configuration
     */
    public UnifiedResourceManager(ResourceConfiguration config) {
        this.config = config;
        this.tracker = new ResourceTracker(config.getMaxIdleTime().toMillis(), config.isLeakDetectionEnabled());
        this.memoryPool = new MemoryPool(
            config.getMaxPoolSizeBytes(),
            config.getMaxIdleTime()
        );
        
        // Initialize per-type counters
        for (GPUResourceType type : GPUResourceType.values()) {
            allocatedBytesPerType.put(type, new AtomicLong(0));
        }
        
        log.info("Unified resource manager initialized with config: {}", config);
    }
    
    /**
     * Get the singleton instance with default configuration
     */
    public static UnifiedResourceManager getInstance() {
        if (instance == null) {
            synchronized (instanceLock) {
                if (instance == null) {
                    instance = new UnifiedResourceManager();
                }
            }
        }
        return instance;
    }
    
    /**
     * Get the singleton instance with custom configuration
     * Only works if no instance exists yet
     */
    public static UnifiedResourceManager getInstance(ResourceConfiguration config) {
        if (instance == null) {
            synchronized (instanceLock) {
                if (instance == null) {
                    instance = new UnifiedResourceManager(config);
                }
            }
        }
        return instance;
    }
    
    /**
     * Reset the singleton instance (for testing)
     */
    public static void resetInstance() {
        synchronized (instanceLock) {
            if (instance != null) {
                try {
                    instance.close();
                } catch (Exception e) {
                    log.warn("Error closing previous instance", e);
                }
                instance = null;
            }
        }
    }
    
    /**
     * Register a resource with the manager
     */
    public void register(GPUResource resource) {
        ensureNotClosed();
        
        UUID id = UUID.fromString(resource.getId());
        if (resources.putIfAbsent(id, resource) == null) {
            GPUResourceType type = resource.getType();
            long size = resource.getSizeBytes();
            allocatedBytesPerType.get(type).addAndGet(size);
            
            log.debug("Registered {} resource: {} ({} bytes)", type, id, size);
        }
    }
    
    /**
     * Unregister a resource from the manager
     */
    public void unregister(GPUResource resource) {
        UUID id = UUID.fromString(resource.getId());
        if (resources.remove(id) != null) {
            GPUResourceType type = resource.getType();
            long size = resource.getSizeBytes();
            allocatedBytesPerType.get(type).addAndGet(-size);
            
            log.debug("Unregistered {} resource: {} ({} bytes)", type, id, size);
        }
    }
    
    /**
     * Get a resource by ID
     */
    public GPUResource getResource(String id) {
        return resources.get(UUID.fromString(id));
    }
    
    /**
     * Get all resources of a specific type
     */
    public List<GPUResource> getResourcesByType(GPUResourceType type) {
        return resources.values().stream()
            .filter(r -> r.getType() == type)
            .collect(Collectors.toList());
    }
    
    /**
     * Get total allocated bytes for a resource type
     */
    public long getAllocatedBytes(GPUResourceType type) {
        return allocatedBytesPerType.get(type).get();
    }
    
    /**
     * Get total allocated bytes across all resources
     */
    public long getTotalAllocatedBytes() {
        return allocatedBytesPerType.values().stream()
            .mapToLong(AtomicLong::get)
            .sum();
    }
    
    /**
     * Get resource statistics
     */
    public ResourceManagerStatistics getStatistics() {
        Map<GPUResourceType, TypeStatistics> typeStats = new HashMap<>();
        
        for (GPUResourceType type : GPUResourceType.values()) {
            List<GPUResource> typeResources = getResourcesByType(type);
            if (!typeResources.isEmpty()) {
                long totalAllocated = getAllocatedBytes(type);
                long totalUsed = typeResources.stream()
                    .mapToLong(r -> r.getStatistics().getUsedBytes())
                    .sum();
                int count = typeResources.size();
                double avgUtilization = typeResources.stream()
                    .mapToDouble(r -> r.getStatistics().getUtilizationPercent())
                    .average()
                    .orElse(0.0);
                
                typeStats.put(type, new TypeStatistics(
                    count, totalAllocated, totalUsed, avgUtilization
                ));
            }
        }
        
        return new ResourceManagerStatistics(
            resources.size(),
            getTotalAllocatedBytes(),
            typeStats,
            memoryPool.getPoolStatistics(),
            tracker.getActiveCount()
        );
    }
    
    /**
     * Reset the resource manager state
     */
    public void reset() {
        synchronized (resources) {
            // Close all resources
            for (GPUResource resource : resources.values()) {
                try {
                    resource.close();
                } catch (Exception e) {
                    log.error("Error closing resource during reset: {}", resource.getId(), e);
                }
            }
            resources.clear();
            bufferToIdMap.clear();
            totalAllocatedBytes.set(0);
            activeResourceCount.set(0);
        }
    }
    
    /**
     * Get resource statistics
     */
    public ResourceStatistics getResourceStats() {
        return new ResourceStatistics(
            resources.size(),
            getTotalAllocatedBytes(),
            activeResourceCount.get(),
            memoryPool != null ? memoryPool.getPoolStatistics().getTotalAllocated() : 0,
            memoryPool != null ? memoryPool.getPoolStatistics().getUsedMemory() : 0
        );
    }
    
    /**
     * Get resource ID for a buffer
     * Workaround: Reset position to 0 during lookup to ensure consistent behavior
     */
    public UUID getResourceId(ByteBuffer buffer) {
        synchronized (bufferToIdMap) {
            // Store current position
            int originalPosition = buffer.position();
            try {
                // Reset to position 0 for consistent lookup
                buffer.position(0);
                return bufferToIdMap.get(buffer);
            } finally {
                // Restore original position
                buffer.position(originalPosition);
            }
        }
    }
    
    /**
     * Check for resource leaks
     */
    public List<String> checkForLeaks() {
        List<String> leaks = new ArrayList<>();
        synchronized (resources) {
            for (GPUResource resource : resources.values()) {
                if (!resource.isClosed() && resource.getAgeMillis() > 60000) {
                    leaks.add(String.format("Potential leak: Resource %s (type: %s, age: %d ms)",
                        resource.getId(), 
                        resource.getClass().getSimpleName(),
                        resource.getAgeMillis()));
                }
            }
        }
        return leaks;
    }
    
    /**
     * Check if a resource is active
     */
    public boolean isResourceActive(UUID resourceId) {
        GPUResource resource = resources.get(resourceId);
        return resource != null && !resource.isClosed();
    }
    
    /**
     * Clean up old or unused resources
     */
    public int cleanupUnused(long maxAgeMillis) {
        ensureNotClosed();
        
        // Note: ByteBufferResources are immediately removed on releaseMemory(),
        // so this mainly cleans up other resource types that might be leaked
        List<GPUResource> toRemove = resources.values().stream()
            .filter(r -> r.getAgeMillis() > maxAgeMillis)
            .collect(Collectors.toList());
        
        int removed = 0;
        for (GPUResource resource : toRemove) {
            try {
                resource.close();
                resources.remove(resource.getId());
                
                // Also clean up buffer mapping if it's a ByteBufferResource
                if (resource instanceof ByteBufferResource) {
                    var bufferResource = (ByteBufferResource) resource;
                    var buffer = bufferResource.getBuffer();
                    if (buffer != null) {
                        bufferToIdMap.remove(buffer);
                    }
                }
                
                removed++;
            } catch (Exception e) {
                log.error("Failed to cleanup resource: {}", resource.getId(), e);
            }
        }
        
        if (removed > 0) {
            log.info("Cleaned up {} unused resources", removed);
        }
        
        return removed;
    }
    
    /**
     * Force close all resources
     */
    public void closeAll() {
        log.warn("Force closing {} resources", resources.size());
        
        List<GPUResource> toClose = new ArrayList<>(resources.values());
        for (GPUResource resource : toClose) {
            try {
                resource.close();
            } catch (Exception e) {
                log.error("Failed to close resource: {}", resource.getId(), e);
            }
        }
        
        resources.clear();
    }
    
    @Override
    public void close() {
        if (closed) {
            return;
        }
        
        closed = true;
        
        // Close all resources
        closeAll();
        
        // Clear memory pool
        try {
            memoryPool.clear();
        } catch (Exception e) {
            log.error("Failed to clear memory pool", e);
        }
        
        // Shutdown tracker
        tracker.shutdown();
        
        log.info("Unified resource manager closed");
    }
    
    private void ensureNotClosed() {
        if (closed) {
            throw new IllegalStateException("Resource manager is closed");
        }
    }
    
    /**
     * Get the memory pool
     */
    public MemoryPool getMemoryPool() {
        return memoryPool;
    }
    
    /**
     * Get the resource tracker
     */
    public ResourceTracker getTracker() {
        return tracker;
    }
    
    /**
     * Get the configuration
     */
    public ResourceConfiguration getConfiguration() {
        return config;
    }
    
    // === OpenGL Resource Factory Methods ===
    
    /**
     * Create an OpenGL buffer resource (VBO, UBO, SSBO)
     */
    public BufferResource createBuffer(int target, int usage, ByteBuffer data, String debugName) {
        ensureNotClosed();
        
        try {
            var buffer = BufferResource.Factory.createSSBO(data, debugName);
            register(buffer);
            return buffer;
        } catch (Exception e) {
            log.error("Failed to create OpenGL buffer: {}", debugName, e);
            throw new RuntimeException("Failed to create OpenGL buffer: " + debugName, e);
        }
    }
    
    /**
     * Create a Shader Storage Buffer Object (SSBO)
     */
    public BufferResource createSSBO(ByteBuffer data, String debugName) {
        ensureNotClosed();
        
        try {
            var buffer = BufferResource.Factory.createSSBO(data, debugName);
            register(buffer);
            return buffer;
        } catch (Exception e) {
            log.error("Failed to create SSBO: {}", debugName, e);
            throw new RuntimeException("Failed to create SSBO: " + debugName, e);
        }
    }
    
    /**
     * Create a Uniform Buffer Object (UBO)
     */
    public BufferResource createUBO(long sizeBytes, String debugName) {
        ensureNotClosed();
        
        try {
            var buffer = BufferResource.Factory.createUBO(sizeBytes, debugName);
            register(buffer);
            return buffer;
        } catch (Exception e) {
            log.error("Failed to create UBO: {}", debugName, e);
            throw new RuntimeException("Failed to create UBO: " + debugName, e);
        }
    }
    
    /**
     * Create a Uniform Buffer Object (UBO) - alternative method name
     */
    public BufferResource createUniformBuffer(long sizeBytes, String debugName) {
        return createUBO(sizeBytes, debugName);
    }
    
    /**
     * Create a Storage Buffer Object (SSBO) - alternative method name
     */
    public BufferResource createStorageBuffer(long sizeBytes, String debugName) {
        ensureNotClosed();
        
        try {
            var buffer = BufferResource.Factory.createSSBO(sizeBytes, debugName);
            register(buffer);
            return buffer;
        } catch (Exception e) {
            log.error("Failed to create storage buffer: {}", debugName, e);
            throw new RuntimeException("Failed to create storage buffer: " + debugName, e);
        }
    }
    
    /**
     * Create a Vertex Buffer Object (VBO)
     */
    public BufferResource createVBO(ByteBuffer data, String debugName) {
        ensureNotClosed();
        
        try {
            var buffer = BufferResource.Factory.createVBO(data, debugName);
            register(buffer);
            return buffer;
        } catch (Exception e) {
            log.error("Failed to create VBO: {}", debugName, e);
            throw new RuntimeException("Failed to create VBO: " + debugName, e);
        }
    }
    
    /**
     * Create a 2D texture
     */
    public TextureResource createTexture2D(int width, int height, int internalFormat, String debugName) {
        ensureNotClosed();
        
        try {
            var texture = TextureResource.Factory.create2D(width, height, internalFormat, debugName);
            register(texture);
            return texture;
        } catch (Exception e) {
            log.error("Failed to create 2D texture: {}", debugName, e);
            throw new RuntimeException("Failed to create 2D texture: " + debugName, e);
        }
    }
    
    /**
     * Create a 2D texture with initial data
     */
    public TextureResource createTexture2D(int width, int height, int internalFormat, 
                                         int format, int type, ByteBuffer data, String debugName) {
        ensureNotClosed();
        
        try {
            var texture = TextureResource.Factory.create2D(width, height, internalFormat, format, type, data, debugName);
            register(texture);
            return texture;
        } catch (Exception e) {
            log.error("Failed to create 2D texture with data: {}", debugName, e);
            throw new RuntimeException("Failed to create 2D texture with data: " + debugName, e);
        }
    }
    
    /**
     * Create a 3D texture
     */
    public TextureResource createTexture3D(int width, int height, int depth, int internalFormat, String debugName) {
        ensureNotClosed();
        
        try {
            var texture = TextureResource.Factory.create3D(width, height, depth, internalFormat, debugName);
            register(texture);
            return texture;
        } catch (Exception e) {
            log.error("Failed to create 3D texture: {}", debugName, e);
            throw new RuntimeException("Failed to create 3D texture: " + debugName, e);
        }
    }
    
    /**
     * Create a render target texture
     */
    public TextureResource createRenderTarget(int width, int height, String debugName) {
        ensureNotClosed();
        
        try {
            var texture = TextureResource.Factory.createRenderTarget(width, height, debugName);
            register(texture);
            return texture;
        } catch (Exception e) {
            log.error("Failed to create render target: {}", debugName, e);
            throw new RuntimeException("Failed to create render target: " + debugName, e);
        }
    }
    
    /**
     * Create and compile a shader
     */
    public ShaderResource createShader(int shaderType, String source, String debugName) {
        ensureNotClosed();
        
        try {
            var shader = ShaderResource.Factory.createAndCompile(shaderType, source, debugName);
            register(shader);
            return shader;
        } catch (Exception e) {
            log.error("Failed to create shader: {}", debugName, e);
            throw new RuntimeException("Failed to create shader: " + debugName, e);
        }
    }
    
    /**
     * Create a compute shader
     */
    public ShaderResource createComputeShader(String source, String debugName) {
        ensureNotClosed();
        
        try {
            var shader = ShaderResource.Factory.createComputeShader(source, debugName);
            register(shader);
            return shader;
        } catch (Exception e) {
            log.error("Failed to create compute shader: {}", debugName, e);
            throw new RuntimeException("Failed to create compute shader: " + debugName, e);
        }
    }
    
    /**
     * Create a shader from classpath resource
     */
    public ShaderResource createShaderFromResource(int shaderType, String resourcePath, String debugName) {
        ensureNotClosed();
        
        try {
            var shader = ShaderResource.Factory.createFromResource(shaderType, resourcePath, debugName);
            register(shader);
            return shader;
        } catch (Exception e) {
            log.error("Failed to create shader from resource: {} ({})", debugName, resourcePath, e);
            throw new RuntimeException("Failed to create shader from resource: " + debugName, e);
        }
    }
    
    /**
     * Create and link a shader program
     */
    public ShaderProgramResource createShaderProgram(String debugName, ShaderResource... shaders) {
        ensureNotClosed();
        
        try {
            var program = ShaderProgramResource.Factory.createAndLink(debugName, shaders);
            register(program);
            return program;
        } catch (Exception e) {
            log.error("Failed to create shader program: {}", debugName, e);
            throw new RuntimeException("Failed to create shader program: " + debugName, e);
        }
    }
    
    /**
     * Create a compute shader program
     */
    public ShaderProgramResource createComputeProgram(ShaderResource computeShader, String debugName) {
        ensureNotClosed();
        
        try {
            var program = ShaderProgramResource.Factory.createComputeProgram(computeShader, debugName);
            register(program);
            return program;
        } catch (Exception e) {
            log.error("Failed to create compute program: {}", debugName, e);
            throw new RuntimeException("Failed to create compute program: " + debugName, e);
        }
    }
    
    /**
     * Create a compute shader program from source
     */
    public ShaderProgramResource createComputeProgram(String computeSource, String debugName) {
        ensureNotClosed();
        
        try {
            var program = ShaderProgramResource.Factory.createComputeProgram(computeSource, debugName);
            register(program);
            return program;
        } catch (Exception e) {
            log.error("Failed to create compute program from source: {}", debugName, e);
            throw new RuntimeException("Failed to create compute program from source: " + debugName, e);
        }
    }
    
    /**
     * Create a render pipeline program (vertex + fragment)
     */
    public ShaderProgramResource createRenderProgram(ShaderResource vertexShader, 
                                                    ShaderResource fragmentShader, 
                                                    String debugName) {
        ensureNotClosed();
        
        try {
            var program = ShaderProgramResource.Factory.createRenderProgram(vertexShader, fragmentShader, debugName);
            register(program);
            return program;
        } catch (Exception e) {
            log.error("Failed to create render program: {}", debugName, e);
            throw new RuntimeException("Failed to create render program: " + debugName, e);
        }
    }
    
    /**
     * Create a render pipeline program from sources
     */
    public ShaderProgramResource createRenderProgram(String vertexSource, 
                                                    String fragmentSource, 
                                                    String debugName) {
        ensureNotClosed();
        
        try {
            var program = ShaderProgramResource.Factory.createRenderProgram(vertexSource, fragmentSource, debugName);
            register(program);
            return program;
        } catch (Exception e) {
            log.error("Failed to create render program from sources: {}", debugName, e);
            throw new RuntimeException("Failed to create render program from sources: " + debugName, e);
        }
    }
    
    // === Additional OpenGL Convenience Methods ===
    
    /**
     * Create a depth texture
     */
    public TextureResource createDepthTexture(int width, int height, String debugName) {
        ensureNotClosed();
        
        try {
            var texture = TextureResource.Factory.createDepthTexture(width, height, debugName);
            register(texture);
            return texture;
        } catch (Exception e) {
            log.error("Failed to create depth texture: {}", debugName, e);
            throw new RuntimeException("Failed to create depth texture: " + debugName, e);
        }
    }
    
    /**
     * Create a vertex shader
     */
    public ShaderResource createVertexShader(String source, String debugName) {
        ensureNotClosed();
        
        try {
            var shader = ShaderResource.Factory.createVertexShader(source, debugName);
            register(shader);
            return shader;
        } catch (Exception e) {
            log.error("Failed to create vertex shader: {}", debugName, e);
            throw new RuntimeException("Failed to create vertex shader: " + debugName, e);
        }
    }
    
    /**
     * Create a fragment shader
     */
    public ShaderResource createFragmentShader(String source, String debugName) {
        ensureNotClosed();
        
        try {
            var shader = ShaderResource.Factory.createFragmentShader(source, debugName);
            register(shader);
            return shader;
        } catch (Exception e) {
            log.error("Failed to create fragment shader: {}", debugName, e);
            throw new RuntimeException("Failed to create fragment shader: " + debugName, e);
        }
    }
    
    /**
     * Create a geometry shader
     */
    public ShaderResource createGeometryShader(String source, String debugName) {
        ensureNotClosed();
        
        try {
            var shader = ShaderResource.Factory.createGeometryShader(source, debugName);
            register(shader);
            return shader;
        } catch (Exception e) {
            log.error("Failed to create geometry shader: {}", debugName, e);
            throw new RuntimeException("Failed to create geometry shader: " + debugName, e);
        }
    }
    
    // === Memory Pool Methods ===
    
    /**
     * Allocate memory from the pool
     */
    public ByteBuffer allocateMemory(int size) {
        ensureNotClosed();
        if (size <= 0) {
            throw new IllegalArgumentException("Size must be positive: " + size);
        }
        
        var buffer = memoryPool.allocate(size);
        if (buffer != null) {
            // Create tracking info first
            var resourceId = UUID.randomUUID();
            var handle = new ByteBufferResource(resourceId, buffer, tracker);
            
            boolean wasAlreadyTracked = false;
            
            // Synchronize buffer tracking to prevent race conditions
            synchronized (bufferToIdMap) {
                // Check if buffer is already tracked
                var existingId = bufferToIdMap.get(buffer);
                if (existingId != null) {
                    // This happens when pool returns a buffer that wasn't properly untracked
                    wasAlreadyTracked = true;
                    log.debug("Buffer {} reused from pool, was tracked as {}", 
                             System.identityHashCode(buffer), existingId);
                    // Remove the old resource
                    var oldResource = resources.remove(existingId);
                    if (oldResource != null) {
                        try {
                            oldResource.close();
                        } catch (Exception e) {
                            log.debug("Error closing old resource", e);
                        }
                    }
                }
                
                // Track the new allocation - store buffer at position 0 for consistency
                int originalPosition = buffer.position();
                try {
                    buffer.position(0);
                    bufferToIdMap.put(buffer, resourceId);
                } finally {
                    buffer.position(originalPosition);
                }
                try (java.io.FileWriter debugFile = new java.io.FileWriter("/tmp/debug_allocation.txt", true)) {
                    debugFile.write("DEBUG ALLOCATION: Added buffer " + System.identityHashCode(buffer) + " -> " + resourceId + "\n");
                    debugFile.write("DEBUG ALLOCATION: bufferToIdMap size after put: " + bufferToIdMap.size() + "\n");
                    debugFile.flush();
                } catch (java.io.IOException e) {
                    System.out.println("DEBUG ALLOCATION: Added buffer " + System.identityHashCode(buffer) + " -> " + resourceId);
                }
            }
            
            // Add to resources map and register - MUST be synchronized like releaseMemory
            synchronized (resources) {
                resources.put(resourceId, handle);
            }
            tracker.register(handle);
            allocatedBytesPerType.get(GPUResourceType.MEMORY_POOL).addAndGet(size);
            totalAllocatedBytes.addAndGet(size); // Track in total allocated bytes
            
            // Only increment counter if this is a truly new allocation
            if (!wasAlreadyTracked) {
                int countBefore = activeResourceCount.get();
                activeResourceCount.incrementAndGet(); // Track as active
                int countAfter = activeResourceCount.get();
                log.debug("Incremented activeResourceCount from {} to {} for new buffer {}", 
                        countBefore, countAfter, System.identityHashCode(buffer));
            } else {
                log.debug("Buffer {} was already tracked, not incrementing activeResourceCount (current: {})", 
                        System.identityHashCode(buffer), activeResourceCount.get());
            }
            allocationCount.incrementAndGet(); // Debug counter
            
            log.debug("Allocated buffer {} with resourceId {}, resources.size() = {}, activeCount = {}", 
                     System.identityHashCode(buffer), resourceId, resources.size(), activeResourceCount.get());
        }
        return buffer;
    }
    
    /**
     * Return memory to the pool
     */
    public void releaseMemory(ByteBuffer buffer) {
        if (buffer == null) {
            return;
        }
        
        UUID resourceId = null;
        boolean wasTracked = false;
        
        // Synchronize buffer tracking removal to prevent race conditions
        synchronized (bufferToIdMap) {
            try (java.io.FileWriter debugFile = new java.io.FileWriter("/tmp/debug_unified_resource_manager.txt", true)) {
                debugFile.write("DEBUG: Attempting to remove buffer " + System.identityHashCode(buffer) + " from bufferToIdMap\n");
                debugFile.write("DEBUG: bufferToIdMap size before remove: " + bufferToIdMap.size() + "\n");
                debugFile.write("DEBUG: bufferToIdMap keys: " + bufferToIdMap.keySet().stream()
                        .map(System::identityHashCode).toList() + "\n");
                debugFile.flush();
                
                // Apply position workaround for consistent lookup during removal
                int originalPosition = buffer.position();
                try {
                    buffer.position(0);
                    resourceId = bufferToIdMap.remove(buffer);
                } finally {
                    buffer.position(originalPosition);
                }
                wasTracked = (resourceId != null);
                
                debugFile.write("DEBUG: wasTracked = " + wasTracked + ", resourceId = " + resourceId + "\n");
                debugFile.flush();
            } catch (java.io.IOException e) {
                // Fallback to console
                System.out.println("DEBUG: Attempting to remove buffer " + System.identityHashCode(buffer) + " from bufferToIdMap");
                System.out.println("DEBUG: bufferToIdMap size before remove: " + bufferToIdMap.size());
                System.out.println("DEBUG: bufferToIdMap keys: " + bufferToIdMap.keySet().stream()
                        .map(System::identityHashCode).toList());
                // Apply position workaround for consistent lookup during removal
                int originalPosition2 = buffer.position();
                try {
                    buffer.position(0);
                    resourceId = bufferToIdMap.remove(buffer);
                } finally {
                    buffer.position(originalPosition2);
                }
                wasTracked = (resourceId != null);
                System.out.println("DEBUG: wasTracked = " + wasTracked + ", resourceId = " + resourceId);
            }
        }
        
        if (wasTracked) {
            releaseCount.incrementAndGet(); // Debug counter - count all buffer releases
            int countBefore = activeResourceCount.get();
            activeResourceCount.decrementAndGet(); // Always decrement when buffer was tracked
            int countAfter = activeResourceCount.get();
            log.debug("Decremented activeResourceCount from {} to {} for buffer {}", 
                    countBefore, countAfter, System.identityHashCode(buffer));
            allocatedBytesPerType.get(GPUResourceType.MEMORY_POOL).addAndGet(-buffer.capacity()); // Always update bytes
            totalAllocatedBytes.addAndGet(-buffer.capacity()); // Also update total allocated bytes
            
            // Synchronize resources map access
            synchronized (resources) {
                var resource = resources.remove(resourceId);
                if (resource != null) {
                    // Close the resource handle (which will unregister from tracker)
                    try {
                        resource.close();
                    } catch (Exception e) {
                        log.debug("Error closing buffer resource", e);
                    }
                    log.debug("Released buffer {} with resourceId {}, resources.size() = {}, activeCount = {}", 
                             System.identityHashCode(buffer), resourceId, resources.size(), activeResourceCount.get());
                } else {
                    log.warn("Buffer {} found in bufferToIdMap but resource {} not found in resources map", 
                             System.identityHashCode(buffer), resourceId);
                }
            }
        } else {
            // This can happen in concurrent scenarios where the same buffer is returned multiple times
            // or if the buffer was never tracked (shouldn't happen with correct usage)
            log.trace("Buffer {} not tracked. This may be expected in concurrent scenarios.", 
                     System.identityHashCode(buffer));
        }
        
        // Always return to pool, even if not tracked (defensive programming)
        memoryPool.returnToPool(buffer);
    }
    
    /**
     * Get the count of active resources
     */
    public int getActiveResourceCount() {
        ensureNotClosed();
        return activeResourceCount.get();
    }
    
    /**
     * Get total memory allocated by this manager
     */
    public long getTotalMemoryAllocated() {
        ensureNotClosed();
        return totalAllocatedBytes.get();
    }
    
    /**
     * Add a GPU resource to be managed
     */
    public <T extends GPUResource> ResourceHandle<T> add(T resource, Object attachment) {
        ensureNotClosed();
        UUID id = UUID.randomUUID();
        resources.put(id, resource);
        activeResourceCount.incrementAndGet();
        
        // Track memory usage
        allocatedBytesPerType.computeIfAbsent(resource.getType(), k -> new AtomicLong(0))
                            .addAndGet(resource.getSizeBytes());
        totalAllocatedBytes.addAndGet(resource.getSizeBytes());
        
        log.debug("Added {} resource {} with {} bytes", resource.getType(), id, resource.getSizeBytes());
        
        return new ResourceHandleImpl<>(id, resource, tracker, attachment);
    }
    
    /**
     * Enable or disable debug mode for leak detection
     */
    public void setDebugMode(boolean enabled) {
        ensureNotClosed();
        // For now, just log the debug mode change
        log.info("Debug mode {}", enabled ? "enabled" : "disabled");
    }
    
    
    /**
     * Get the total memory usage
     */
    public long getTotalMemoryUsage() {
        ensureNotClosed();
        return getTotalAllocatedBytes();
    }
    
    /**
     * Perform maintenance operations
     */
    public void performMaintenance() {
        ensureNotClosed();
        memoryPool.evictExpired();
        cleanupUnused(config.getMaxIdleTime().toMillis());
        
        // Debug output for tracking allocation/release mismatch
        log.debug("Maintenance complete - Allocations: {}, Releases: {}, Active: {}, Resources size: {}, BufferMap size: {}", 
                 allocationCount.get(), releaseCount.get(), activeResourceCount.get(), 
                 resources.size(), bufferToIdMap.size());
    }
    
    /**
     * Shutdown the resource manager
     */
    public void shutdown() {
        close();
    }
    
    /**
     * Statistics for the resource manager
     */
    public static class ResourceManagerStatistics {
        private final int totalResources;
        private final long totalAllocatedBytes;
        private final Map<GPUResourceType, TypeStatistics> typeStatistics;
        private final MemoryPool.PoolStatistics poolStatistics;
        private final int trackedResources;
        
        public ResourceManagerStatistics(int totalResources, long totalAllocatedBytes,
                                        Map<GPUResourceType, TypeStatistics> typeStatistics,
                                        MemoryPool.PoolStatistics poolStatistics,
                                        int trackedResources) {
            this.totalResources = totalResources;
            this.totalAllocatedBytes = totalAllocatedBytes;
            this.typeStatistics = typeStatistics;
            this.poolStatistics = poolStatistics;
            this.trackedResources = trackedResources;
        }
        
        public int getTotalResources() {
            return totalResources;
        }
        
        public long getTotalAllocatedBytes() {
            return totalAllocatedBytes;
        }
        
        public Map<GPUResourceType, TypeStatistics> getTypeStatistics() {
            return typeStatistics;
        }
        
        public MemoryPool.PoolStatistics getPoolStatistics() {
            return poolStatistics;
        }
        
        public int getTrackedResources() {
            return trackedResources;
        }
        
        @Override
        public String toString() {
            var sb = new StringBuilder();
            sb.append("Resource Manager Statistics\n");
            sb.append("===========================\n");
            sb.append(String.format("Total Resources: %d\n", totalResources));
            sb.append(String.format("Total Allocated: %d bytes\n", totalAllocatedBytes));
            sb.append(String.format("Tracked Resources: %d\n", trackedResources));
            
            if (!typeStatistics.isEmpty()) {
                sb.append("\nPer-Type Statistics:\n");
                for (var entry : typeStatistics.entrySet()) {
                    sb.append(String.format("  %s: %s\n", entry.getKey(), entry.getValue()));
                }
            }
            
            if (poolStatistics != null) {
                sb.append(String.format("\nMemory Pool: %s\n", poolStatistics));
            }
            
            return sb.toString();
        }
    }
    
    /**
     * Statistics for a resource type
     */
    public static class TypeStatistics {
        private final int count;
        private final long allocatedBytes;
        private final long usedBytes;
        private final double avgUtilization;
        
        public TypeStatistics(int count, long allocatedBytes, long usedBytes, double avgUtilization) {
            this.count = count;
            this.allocatedBytes = allocatedBytes;
            this.usedBytes = usedBytes;
            this.avgUtilization = avgUtilization;
        }
        
        public int getCount() {
            return count;
        }
        
        public long getAllocatedBytes() {
            return allocatedBytes;
        }
        
        public long getUsedBytes() {
            return usedBytes;
        }
        
        public double getAvgUtilization() {
            return avgUtilization;
        }
        
        @Override
        public String toString() {
            return String.format("count=%d, allocated=%d, used=%d, utilization=%.1f%%",
                count, allocatedBytes, usedBytes, avgUtilization);
        }
    }
    
    /**
     * Simple ResourceHandle implementation for GPU resources
     */
    private static class ResourceHandleImpl<T extends GPUResource> extends ResourceHandle<T> {
        private final UUID id;
        private final Object attachment;
        private final UnifiedResourceManager manager;
        
        public ResourceHandleImpl(UUID id, T resource, ResourceTracker tracker, Object attachment) {
            super(resource, tracker);
            this.id = id;
            this.attachment = attachment;
            this.manager = null; // Not used in this simple implementation
        }
        
        @Override
        protected void doCleanup(T resource) {
            try {
                resource.close();
            } catch (Exception e) {
                log.error("Error closing GPU resource {} during cleanup", resource.getId(), e);
            }
        }
        
        public Object getAttachment() {
            return attachment;
        }
    }
}