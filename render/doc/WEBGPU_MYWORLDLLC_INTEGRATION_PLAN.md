# WebGPU Integration using MyWorldLLC WebGPU-Java

## Overview

This document outlines the updated Phase 2 implementation plan using the MyWorldLLC WebGPU-Java binding instead of LWJGL. This binding provides a pure Java API for WebGPU with automatic native library management.

## Why MyWorldLLC WebGPU-Java?

1. **Pure Java API** - No manual native code handling required
2. **Automatic Native Library Management** - Handles platform-specific library loading
3. **Object-Oriented Design** - More idiomatic Java than raw bindings
4. **Active Development** - Regular updates and WebGPU spec compliance
5. **Zero Dependencies on LWJGL** - Standalone WebGPU solution

## Updated Dependencies

```xml
<!-- render/pom.xml -->
<dependencies>
    <!-- WebGPU Java Binding -->
    <dependency>
        <groupId>com.myworldllc</groupId>
        <artifactId>webgpu-java</artifactId>
        <version>0.1.0</version> <!-- Check for latest version -->
    </dependency>
    
    <!-- Existing dependencies remain unchanged -->
    <dependency>
        <groupId>javax.vecmath</groupId>
        <artifactId>vecmath</artifactId>
        <version>1.5.2</version>
    </dependency>
</dependencies>
```

## Phase 2 Architecture Updates

### Package Structure

```
render/src/main/java/com/hellblazer/luciferase/render/
├── voxel/
│   ├── core/           # Existing Phase 1 structures
│   ├── memory/         # Existing Phase 1 memory management
│   └── gpu/           # NEW: WebGPU integration
│       ├── WebGPUContext.java
│       ├── WebGPUDevice.java
│       ├── ComputeShaderManager.java
│       ├── GPUBufferManager.java
│       └── shaders/
│           └── OctreeTraversal.wgsl
```

## Implementation Plan

### 1. WebGPU Context Initialization

```java
package com.hellblazer.luciferase.render.voxel.gpu;

import com.myworldllc.webgpu.*;

public class WebGPUContext {
    private static final Logger log = LoggerFactory.getLogger(WebGPUContext.class);
    
    private Instance instance;
    private Adapter adapter;
    private Device device;
    private Queue queue;
    
    public void initialize() {
        // Create WebGPU instance
        instance = WebGPU.createInstance(new InstanceDescriptor());
        
        // Request adapter with high performance preference
        AdapterOptions options = new AdapterOptions();
        options.setPowerPreference(PowerPreference.HIGH_PERFORMANCE);
        
        adapter = instance.requestAdapter(options);
        if (adapter == null) {
            throw new RuntimeException("No suitable WebGPU adapter found");
        }
        
        // Log adapter info
        AdapterProperties props = adapter.getProperties();
        log.info("WebGPU Adapter: {} ({}) - Driver: {}", 
                 props.getName(), 
                 props.getBackendType(), 
                 props.getDriverDescription());
        
        // Request device with required features
        DeviceDescriptor deviceDesc = new DeviceDescriptor();
        deviceDesc.setLabel("ESVO Compute Device");
        
        // Request features we need
        deviceDesc.addRequiredFeature(FeatureName.FLOAT32_FILTERABLE);
        deviceDesc.addRequiredFeature(FeatureName.TEXTURE_COMPRESSION_BC);
        
        // Set limits
        RequiredLimits limits = new RequiredLimits();
        limits.setMaxBufferSize(1024 * 1024 * 1024); // 1GB
        limits.setMaxStorageBufferBindingSize(256 * 1024 * 1024); // 256MB
        limits.setMaxComputeWorkgroupSizeX(256);
        deviceDesc.setRequiredLimits(limits);
        
        device = adapter.requestDevice(deviceDesc);
        queue = device.getQueue();
        
        // Set up error handling
        device.setUncapturedErrorCallback((errorType, message) -> {
            log.error("WebGPU Error [{}]: {}", errorType, message);
        });
        
        log.info("WebGPU device initialized successfully");
    }
    
    public void shutdown() {
        if (queue != null) queue.release();
        if (device != null) device.release();
        if (adapter != null) adapter.release();
        if (instance != null) instance.release();
    }
}
```

### 2. Compute Shader Management

```java
package com.hellblazer.luciferase.render.voxel.gpu;

import com.myworldllc.webgpu.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class ComputeShaderManager {
    private final Device device;
    private final Map<String, ShaderModule> shaderCache = new HashMap<>();
    private final Map<String, ComputePipeline> pipelineCache = new HashMap<>();
    
    public ComputeShaderManager(Device device) {
        this.device = device;
    }
    
    public ShaderModule loadShader(String name, String wgslCode) {
        return shaderCache.computeIfAbsent(name, k -> {
            ShaderModuleDescriptor desc = new ShaderModuleDescriptor();
            desc.setLabel(name);
            desc.setCode(wgslCode);
            
            ShaderModule module = device.createShaderModule(desc);
            
            // Validate compilation
            module.getCompilationInfo(info -> {
                for (CompilationMessage msg : info.getMessages()) {
                    if (msg.getType() == CompilationMessageType.ERROR) {
                        throw new RuntimeException("Shader compilation failed: " + msg.getMessage());
                    }
                }
            });
            
            return module;
        });
    }
    
    public ComputePipeline createComputePipeline(String name, 
                                                ShaderModule shader,
                                                String entryPoint,
                                                PipelineLayout layout) {
        return pipelineCache.computeIfAbsent(name, k -> {
            ComputePipelineDescriptor desc = new ComputePipelineDescriptor();
            desc.setLabel(name);
            desc.setLayout(layout);
            
            ProgrammableStage compute = new ProgrammableStage();
            compute.setModule(shader);
            compute.setEntryPoint(entryPoint);
            desc.setCompute(compute);
            
            return device.createComputePipeline(desc);
        });
    }
    
    // Load built-in ESVO shaders
    public void loadESVOShaders() throws Exception {
        // Octree traversal shader
        String traversalShader = Files.readString(
            Path.of(getClass().getResource("/shaders/octree_traversal.wgsl").toURI())
        );
        loadShader("octree_traversal", traversalShader);
        
        // Voxelization shader
        String voxelizeShader = Files.readString(
            Path.of(getClass().getResource("/shaders/voxelize.wgsl").toURI())
        );
        loadShader("voxelize", voxelizeShader);
    }
}
```

### 3. GPU Buffer Integration with FFM

```java
package com.hellblazer.luciferase.render.voxel.gpu;

import com.myworldllc.webgpu.*;
import java.lang.foreign.*;
import java.util.concurrent.ConcurrentHashMap;

public class GPUBufferManager {
    private final Device device;
    private final Queue queue;
    private final Map<MemorySegment, Buffer> bufferMap = new ConcurrentHashMap<>();
    
    public GPUBufferManager(Device device, Queue queue) {
        this.device = device;
        this.queue = queue;
    }
    
    /**
     * Creates a GPU buffer from an FFM MemorySegment with zero-copy when possible
     */
    public Buffer createBuffer(MemorySegment segment, BufferUsage usage) {
        // Check if buffer already exists
        Buffer existing = bufferMap.get(segment);
        if (existing != null) {
            return existing;
        }
        
        BufferDescriptor desc = new BufferDescriptor();
        desc.setLabel("ESVO Buffer");
        desc.setSize(segment.byteSize());
        desc.setUsage(usage.or(BufferUsage.COPY_DST));
        desc.setMappedAtCreation(false);
        
        Buffer buffer = device.createBuffer(desc);
        
        // Upload data from FFM segment
        uploadToBuffer(buffer, segment);
        
        // Cache for reuse
        bufferMap.put(segment, buffer);
        
        return buffer;
    }
    
    /**
     * Efficient upload from FFM MemorySegment to GPU Buffer
     */
    public void uploadToBuffer(Buffer buffer, MemorySegment data) {
        // For small data, use queue write
        if (data.byteSize() <= 256 * 1024) { // 256KB threshold
            queue.writeBuffer(buffer, 0, data.toArray(ValueLayout.JAVA_BYTE));
        } else {
            // For large data, use staging buffer
            uploadViaStaging(buffer, data);
        }
    }
    
    private void uploadViaStaging(Buffer dst, MemorySegment data) {
        // Create staging buffer
        BufferDescriptor stagingDesc = new BufferDescriptor();
        stagingDesc.setSize(data.byteSize());
        stagingDesc.setUsage(BufferUsage.MAP_WRITE.or(BufferUsage.COPY_SRC));
        stagingDesc.setMappedAtCreation(true);
        
        Buffer staging = device.createBuffer(stagingDesc);
        
        // Get mapped range and copy data
        MappedRange range = staging.getMappedRange(0, data.byteSize());
        byte[] bytes = data.toArray(ValueLayout.JAVA_BYTE);
        range.setBytes(0, bytes);
        staging.unmap();
        
        // Copy staging to destination
        CommandEncoder encoder = device.createCommandEncoder();
        encoder.copyBufferToBuffer(staging, 0, dst, 0, data.byteSize());
        
        CommandBuffer commands = encoder.finish();
        queue.submit(commands);
        
        // Cleanup staging buffer after copy
        staging.destroy();
    }
    
    /**
     * Creates a GPU buffer for VoxelOctreeNode data
     */
    public Buffer createOctreeBuffer(MemorySegment octreeData) {
        return createBuffer(octreeData, 
            BufferUsage.STORAGE.or(BufferUsage.COPY_SRC));
    }
    
    /**
     * Creates a GPU buffer for ray data
     */
    public Buffer createRayBuffer(int maxRays) {
        long size = maxRays * 32L; // 32 bytes per ray (origin + direction)
        
        BufferDescriptor desc = new BufferDescriptor();
        desc.setLabel("Ray Buffer");
        desc.setSize(size);
        desc.setUsage(BufferUsage.STORAGE.or(BufferUsage.COPY_DST));
        
        return device.createBuffer(desc);
    }
    
    public void cleanup() {
        bufferMap.values().forEach(Buffer::destroy);
        bufferMap.clear();
    }
}
```

### 4. WGSL Shader for Octree Traversal

```wgsl
// resources/shaders/octree_traversal.wgsl

struct Ray {
    origin: vec3<f32>,
    direction: vec3<f32>,
    tMin: f32,
    tMax: f32
}

struct VoxelNode {
    // Matches VoxelOctreeNode.java layout
    packedData: u64  // validMask, leafMask, childPointer, etc.
}

struct HitResult {
    hit: u32,
    t: f32,
    nodeIndex: u32,
    normal: vec3<f32>
}

@group(0) @binding(0) var<storage, read> octreeNodes: array<VoxelNode>;
@group(0) @binding(1) var<storage, read> rays: array<Ray>;
@group(0) @binding(2) var<storage, write> results: array<HitResult>;
@group(0) @binding(3) var<uniform> octreeInfo: OctreeInfo;

struct OctreeInfo {
    rootNodeIndex: u32,
    maxDepth: u32,
    worldSize: f32,
    _padding: u32
}

fn unpackNode(packed: u64) -> UnpackedNode {
    var node: UnpackedNode;
    node.validMask = u8(packed & 0xFFu);
    node.leafMask = u8((packed >> 8u) & 0xFFu);
    node.childPointer = u32((packed >> 16u) & 0xFFFFFFFFu);
    node.contourPointer = u32((packed >> 48u) & 0xFFFFu);
    return node;
}

@compute @workgroup_size(64)
fn traverseOctree(@builtin(global_invocation_id) id: vec3<u32>) {
    let rayIdx = id.x;
    if (rayIdx >= arrayLength(&rays)) {
        return;
    }
    
    let ray = rays[rayIdx];
    var result = HitResult(0u, 1e10, 0u, vec3<f32>(0.0));
    
    // Stack for traversal
    var stack: array<StackEntry, 23>; // max depth + 1
    var stackPtr = 0u;
    
    // Initialize with root
    stack[0] = StackEntry(octreeInfo.rootNodeIndex, 0u, vec3<f32>(0.0));
    stackPtr = 1u;
    
    while (stackPtr > 0u) {
        stackPtr -= 1u;
        let entry = stack[stackPtr];
        
        // Ray-box intersection for current node
        let nodeSize = octreeInfo.worldSize / f32(1u << entry.level);
        let tInt = rayBoxIntersection(ray, entry.origin, nodeSize);
        
        if (!tInt.hit || tInt.tMin >= result.t) {
            continue;
        }
        
        let node = unpackNode(octreeNodes[entry.nodeIndex].packedData);
        
        // Check if leaf
        if (node.validMask == 0xFFu && node.leafMask == 0xFFu) {
            if (tInt.tMin < result.t) {
                result.hit = 1u;
                result.t = tInt.tMin;
                result.nodeIndex = entry.nodeIndex;
                result.normal = computeNormal(ray, tInt.tMin, entry.origin, nodeSize);
            }
        } else if (node.validMask != 0u) {
            // Push children in front-to-back order
            pushChildren(&stack, &stackPtr, node, entry, ray);
        }
    }
    
    results[rayIdx] = result;
}
```

### 5. Integration with Existing VoxelOctreeNode

```java
package com.hellblazer.luciferase.render.voxel.gpu;

import com.hellblazer.luciferase.render.voxel.core.VoxelOctreeNode;
import com.hellblazer.luciferase.render.voxel.memory.PageAllocator;
import com.myworldllc.webgpu.*;

public class GPUVoxelOctree {
    private final WebGPUContext context;
    private final GPUBufferManager bufferManager;
    private final ComputeShaderManager shaderManager;
    private final PageAllocator pageAllocator;
    
    private ComputePipeline traversalPipeline;
    private BindGroup traversalBindGroup;
    private Buffer octreeBuffer;
    
    public GPUVoxelOctree(WebGPUContext context, PageAllocator pageAllocator) {
        this.context = context;
        this.pageAllocator = pageAllocator;
        this.bufferManager = new GPUBufferManager(context.getDevice(), context.getQueue());
        this.shaderManager = new ComputeShaderManager(context.getDevice());
    }
    
    public void uploadOctree(MemorySegment octreeData, int nodeCount) {
        // Create GPU buffer from FFM memory segment
        octreeBuffer = bufferManager.createOctreeBuffer(octreeData);
        
        // Create bind group layout
        BindGroupLayoutDescriptor layoutDesc = new BindGroupLayoutDescriptor();
        layoutDesc.setLabel("Octree Traversal Layout");
        
        // Octree nodes binding
        BindGroupLayoutEntry octreeEntry = new BindGroupLayoutEntry();
        octreeEntry.setBinding(0);
        octreeEntry.setVisibility(ShaderStage.COMPUTE);
        octreeEntry.setBuffer(new BufferBindingLayout(
            BufferBindingType.READ_ONLY_STORAGE, false, 0
        ));
        
        // Create pipeline if not exists
        if (traversalPipeline == null) {
            createTraversalPipeline();
        }
        
        updateBindGroup();
    }
    
    public CompletableFuture<HitResult[]> traceRays(Ray[] rays) {
        CompletableFuture<HitResult[]> future = new CompletableFuture<>();
        
        try {
            // Upload rays
            Buffer rayBuffer = uploadRays(rays);
            
            // Create result buffer
            Buffer resultBuffer = device.createBuffer(new BufferDescriptor()
                .setSize(rays.length * 32) // 32 bytes per HitResult
                .setUsage(BufferUsage.STORAGE.or(BufferUsage.COPY_SRC))
            );
            
            // Record commands
            CommandEncoder encoder = device.createCommandEncoder();
            ComputePassEncoder computePass = encoder.beginComputePass();
            
            computePass.setPipeline(traversalPipeline);
            computePass.setBindGroup(0, traversalBindGroup);
            
            // Dispatch with 64 rays per workgroup
            int workgroups = (rays.length + 63) / 64;
            computePass.dispatchWorkgroups(workgroups, 1, 1);
            
            computePass.end();
            
            // Read back results
            readBackResults(encoder, resultBuffer, rays.length, future);
            
            // Submit
            queue.submit(encoder.finish());
            
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
        
        return future;
    }
}
```

## Testing Strategy

### Unit Tests

```java
@Test
public void testWebGPUInitialization() {
    WebGPUContext context = new WebGPUContext();
    assertDoesNotThrow(() -> context.initialize());
    
    assertNotNull(context.getDevice());
    assertNotNull(context.getQueue());
    
    // Test feature support
    assertTrue(context.getDevice().hasFeature(FeatureName.FLOAT32_FILTERABLE));
    
    context.shutdown();
}

@Test
public void testGPUBufferCreation() {
    // Create test data in FFM
    try (Arena arena = Arena.ofConfined()) {
        MemorySegment data = arena.allocate(1024);
        
        GPUBufferManager manager = new GPUBufferManager(device, queue);
        Buffer buffer = manager.createBuffer(data, BufferUsage.STORAGE);
        
        assertNotNull(buffer);
        assertEquals(1024, buffer.getSize());
        
        buffer.destroy();
    }
}
```

## Performance Benchmarks

```java
@State(Scope.Benchmark)
public class WebGPUTraversalBenchmark {
    private GPUVoxelOctree gpuOctree;
    private Ray[] testRays;
    
    @Setup
    public void setup() {
        // Initialize WebGPU and create test octree
        WebGPUContext context = new WebGPUContext();
        context.initialize();
        
        gpuOctree = new GPUVoxelOctree(context, new PageAllocator());
        
        // Generate test rays
        testRays = generateTestRays(10000);
    }
    
    @Benchmark
    public HitResult[] benchmarkGPUTraversal() throws Exception {
        return gpuOctree.traceRays(testRays).get();
    }
}
```

## Migration Notes

### Key Differences from LWJGL

1. **Object-Oriented API**: MyWorldLLC uses Java objects instead of handles
2. **Automatic Resource Management**: Built-in reference counting
3. **Callback Handling**: Lambda-friendly callback interfaces
4. **Error Handling**: Exceptions instead of error codes

### Platform Support

- **Windows**: wgpu-native via Dawn
- **Linux**: wgpu-native or Dawn
- **macOS**: wgpu-native via Metal
- **Web**: Future WebAssembly support planned

## Next Steps

1. Set up MyWorldLLC WebGPU-Java dependency
2. Implement WebGPUContext initialization
3. Create shader management system
4. Build GPU buffer integration with FFM
5. Implement octree traversal compute pipeline
6. Add comprehensive testing
7. Benchmark GPU vs CPU performance

## Risks and Mitigation

1. **Library Maturity**: MyWorldLLC is newer than LWJGL
   - Mitigation: Active community, regular updates
   
2. **Documentation**: Less extensive than LWJGL
   - Mitigation: Source code is clear, examples available
   
3. **Native Library Loading**: Automatic but less control
   - Mitigation: Library handles platform detection well

## Conclusion

Using MyWorldLLC WebGPU-Java provides a cleaner, more maintainable WebGPU integration without the overhead of LWJGL. The object-oriented API aligns better with Java idioms while still providing full WebGPU capabilities.