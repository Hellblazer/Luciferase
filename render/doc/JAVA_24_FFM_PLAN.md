# Java 24 FFM Implementation Plan for ESVO

## Overview

With Java 24, the Foreign Function & Memory (FFM) API is now stable and production-ready. This document outlines how we leverage Java 24's FFM capabilities for the ESVO rendering implementation, specifically for WebGPU integration.

## Java 24 FFM Features

### Stable APIs (No Longer Preview)
- `java.lang.foreign.*` - Fully stable API
- `MemorySegment` - Native memory abstraction
- `Arena` - Memory lifecycle management
- `MemoryLayout` - Structured memory layouts
- `ValueLayout` - Primitive type layouts
- `Linker` - Foreign function invocation

### Key Advantages for ESVO
1. **Zero-copy GPU transfers** - Direct memory mapping between Java and GPU
2. **Native performance** - No JNI overhead
3. **Memory safety** - Bounded access with automatic cleanup
4. **Structured data** - C-compatible struct layouts for GPU interop

## WebGPU Integration Architecture

### Current State
The `webgpu-java` library (com.myworldvw:webgpu-java:v22.1.0.1) provides low-level FFM bindings to WebGPU. These are generated bindings that expose the raw WebGPU C API through FFM.

### Implementation Strategy

#### Layer 1: Low-Level FFM Bindings (webgpu-java)
```java
// Raw FFM bindings from webgpu-java
package com.myworldvw.webgpu;

public class webgpu_h {
    // Auto-generated FFM bindings
    public static MemorySegment wgpuCreateInstance(MemorySegment descriptor);
    public static MemorySegment wgpuDeviceCreateBuffer(MemorySegment device, MemorySegment descriptor);
    // ... hundreds of low-level functions
}
```

#### Layer 2: High-Level Java API (Our Implementation)
```java
// Our abstraction layer
package com.hellblazer.luciferase.render.voxel.gpu;

public class WebGPUDevice {
    private final MemorySegment nativeDevice;
    private final Arena arena;
    
    public GPUBuffer createBuffer(long size, BufferUsage usage) {
        try (var localArena = Arena.ofConfined()) {
            // Create descriptor using FFM
            var desc = WGPUBufferDescriptor.allocate(localArena);
            WGPUBufferDescriptor.size$set(desc, size);
            WGPUBufferDescriptor.usage$set(desc, usage.getValue());
            
            // Call native function
            var bufferHandle = webgpu_h.wgpuDeviceCreateBuffer(nativeDevice, desc);
            return new GPUBuffer(bufferHandle, size, arena);
        }
    }
}
```

#### Layer 3: ESVO-Specific Operations
```java
public class VoxelGPUManager {
    private final WebGPUDevice device;
    
    public void uploadOctree(VoxelOctreeNode root) {
        // Pack octree data using FFM
        try (var arena = Arena.ofConfined()) {
            var octreeLayout = MemoryLayout.structLayout(
                ValueLayout.JAVA_LONG.withName("packed"),
                ValueLayout.JAVA_INT.withName("childPointer"),
                ValueLayout.JAVA_INT.withName("padding")
            );
            
            var nodeCount = countNodes(root);
            var octreeData = arena.allocate(octreeLayout, nodeCount);
            
            // Pack nodes into native memory
            packNodes(root, octreeData);
            
            // Upload to GPU with zero-copy
            var gpuBuffer = device.createBuffer(
                octreeData.byteSize(),
                BufferUsage.STORAGE | BufferUsage.COPY_DST
            );
            device.writeBuffer(gpuBuffer, octreeData);
        }
    }
}
```

## Implementation Phases

### Phase 1: FFM Foundation (Current)
- [x] Basic stub implementation for compilation
- [ ] FFM memory layouts for voxel data structures
- [ ] Arena-based memory management

### Phase 2: WebGPU Binding Layer
- [ ] Wrapper classes for WebGPU objects
- [ ] Safe handle management with Arena lifecycle
- [ ] Error handling and validation

### Phase 3: GPU Memory Management
- [ ] Zero-copy buffer uploads
- [ ] Mapped buffer operations
- [ ] Staging buffer optimization

### Phase 4: Compute Pipeline
- [ ] Shader compilation via FFM
- [ ] Pipeline state objects
- [ ] Bind group management

## Memory Layout Examples

### Voxel Node Layout (GPU-Compatible)
```java
public static final StructLayout VOXEL_NODE_LAYOUT = MemoryLayout.structLayout(
    ValueLayout.JAVA_BYTE.withName("validMask"),
    ValueLayout.JAVA_BYTE.withName("leafMask"),
    ValueLayout.JAVA_SHORT.withName("padding"),
    ValueLayout.JAVA_INT.withName("childPointer"),
    ValueLayout.JAVA_LONG.withName("attachmentData")
).withByteAlignment(16); // GPU alignment requirement
```

### Ray Structure for GPU
```java
public static final StructLayout RAY_LAYOUT = MemoryLayout.structLayout(
    MemoryLayout.sequenceLayout(3, ValueLayout.JAVA_FLOAT).withName("origin"),
    ValueLayout.JAVA_FLOAT.withName("tMin"),
    MemoryLayout.sequenceLayout(3, ValueLayout.JAVA_FLOAT).withName("direction"),
    ValueLayout.JAVA_FLOAT.withName("tMax")
).withByteAlignment(16);
```

## Performance Optimizations

### 1. Memory Pooling
```java
public class FFMMemoryPool {
    private final Arena arena = Arena.ofShared();
    private final Queue<MemorySegment> available = new ConcurrentLinkedQueue<>();
    private final long segmentSize;
    
    public MemorySegment acquire() {
        var segment = available.poll();
        if (segment == null) {
            segment = arena.allocate(segmentSize, 256); // 256-byte alignment
        }
        return segment;
    }
    
    public void release(MemorySegment segment) {
        segment.fill((byte) 0); // Clear for reuse
        available.offer(segment);
    }
}
```

### 2. Batch Operations
```java
public void uploadBatch(List<VoxelOctreeNode> nodes) {
    try (var arena = Arena.ofConfined()) {
        // Allocate single large buffer for entire batch
        var totalSize = nodes.size() * VOXEL_NODE_LAYOUT.byteSize();
        var batchBuffer = arena.allocate(totalSize, 256);
        
        // Pack all nodes sequentially
        long offset = 0;
        for (var node : nodes) {
            packNode(node, batchBuffer.asSlice(offset));
            offset += VOXEL_NODE_LAYOUT.byteSize();
        }
        
        // Single GPU upload
        device.writeBuffer(gpuBuffer, batchBuffer);
    }
}
```

### 3. Direct GPU Mapping
```java
public void directGPUWrite(GPUBuffer buffer, Consumer<MemorySegment> writer) {
    // Map GPU buffer to CPU memory
    var mapped = device.mapBuffer(buffer, MapMode.WRITE);
    try {
        // Direct write to GPU memory
        writer.accept(mapped);
    } finally {
        device.unmapBuffer(buffer);
    }
}
```

## Testing Strategy

### Unit Tests
```java
@Test
public void testFFMMemoryLayout() {
    try (var arena = Arena.ofConfined()) {
        var node = arena.allocate(VOXEL_NODE_LAYOUT);
        
        // Test field access
        VOXEL_NODE_LAYOUT.varHandle("validMask").set(node, (byte) 0xFF);
        VOXEL_NODE_LAYOUT.varHandle("childPointer").set(node, 12345);
        
        // Verify layout
        assertEquals(16, VOXEL_NODE_LAYOUT.byteSize());
        assertEquals(0xFF, (byte) VOXEL_NODE_LAYOUT.varHandle("validMask").get(node));
    }
}
```

### Integration Tests
```java
@Test
@EnabledIf("hasWebGPUSupport")
public void testGPUUpload() {
    var manager = new VoxelGPUManager(device);
    var octree = createTestOctree();
    
    manager.uploadOctree(octree);
    
    // Verify GPU buffer contains correct data
    var downloaded = manager.downloadOctree();
    assertOctreeEquals(octree, downloaded);
}
```

## Migration from Stubs

### Current Stub
```java
public class WebGPUStubs {
    public static class Device {
        public Buffer createBuffer(BufferDescriptor desc) {
            throw new UnsupportedOperationException("Stub implementation");
        }
    }
}
```

### Target FFM Implementation
```java
public class WebGPUDeviceFFM implements Device {
    private final MemorySegment nativeHandle;
    
    @Override
    public Buffer createBuffer(BufferDescriptor desc) {
        try (var arena = Arena.ofConfined()) {
            var nativeDesc = allocateDescriptor(arena, desc);
            var bufferHandle = webgpu_h.wgpuDeviceCreateBuffer(nativeHandle, nativeDesc);
            return new BufferFFM(bufferHandle);
        }
    }
}
```

## Benefits of Java 24

### 1. No Preview Flags
- Production-ready FFM API
- No `--enable-preview` required
- Stable API guarantees

### 2. Performance Improvements
- Optimized MemorySegment operations
- Better JIT compilation for FFM code
- Reduced overhead vs Java 22/23

### 3. Enhanced Safety
- Improved bounds checking
- Better error messages
- More compile-time validation

### 4. Vector API Integration
```java
// Java 24 allows combining FFM with Vector API
public void simdProcessNodes(MemorySegment nodes, int count) {
    var species = FloatVector.SPECIES_256;
    var step = species.length();
    
    for (int i = 0; i < count; i += step) {
        var vector = FloatVector.fromMemorySegment(
            species, nodes, i * Float.BYTES, ByteOrder.nativeOrder());
        
        // SIMD operations on node data
        vector = vector.mul(2.0f).add(1.0f);
        
        vector.intoMemorySegment(nodes, i * Float.BYTES, ByteOrder.nativeOrder());
    }
}
```

## Next Steps

1. **Immediate**: Complete FFM wrapper for webgpu-java bindings
2. **Week 1**: Implement GPU buffer management with FFM
3. **Week 2**: Create compute pipeline abstraction
4. **Week 3**: Integrate with ESVO voxelization pipeline
5. **Week 4**: Performance optimization and testing

## Conclusion

Java 24's stable FFM API provides the perfect foundation for high-performance GPU computing in ESVO. By leveraging FFM's zero-copy capabilities and structured memory layouts, we can achieve native performance while maintaining Java's safety and productivity benefits.