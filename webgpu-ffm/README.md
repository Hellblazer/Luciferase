# WebGPU FFM Module

Java bindings for WebGPU using the Foreign Function & Memory (FFM) API, providing high-performance GPU compute and graphics operations.

## Overview

This module provides type-safe Java bindings to WebGPU via Dawn (Google's WebGPU implementation), enabling GPU compute and graphics operations. It uses Java 24's FFM API for zero-copy native interop with full Dawn integration.

## Status: Production Ready ✅

The WebGPU FFM integration is now **production ready** with:
- ✅ **Complete Dawn Integration** - Full WebGPU Dawn implementation
- ✅ **Buffer Operations** - Synchronous and asynchronous buffer mapping
- ✅ **Compute Pipeline** - Shader execution and GPU dispatch
- ✅ **Multi-Platform Support** - macOS (ARM64/x86_64), Linux, Windows  
- ✅ **Memory Safety** - AutoCloseable resources with proper cleanup
- ✅ **Surface Rendering** - Surface creation and presentation
- ✅ **No Mock Fallbacks** - All operations use native WebGPU implementation

## Key Features

### Core WebGPU Operations
- **Device Management** - Instance, adapter, and device lifecycle
- **Buffer Operations** - Create, write, read, and map buffers with proper usage flags
- **Compute Shaders** - WGSL shader compilation and execution
- **Command Recording** - Command encoders and submission
- **Resource Management** - Automatic cleanup and memory management

### Advanced Features
- **Async Buffer Mapping** - Non-blocking buffer operations with callback-based API
- **Surface Integration** - Native window surface creation and presentation
- **Multi-Threading** - Thread-safe operations with proper synchronization
- **Error Handling** - Comprehensive error reporting and recovery
- **Next-Generation APIs** - Future-based async operations (wgpuBufferMapAsyncF, wgpuInstanceWaitAny)

## Quick Start

```java
// Initialize WebGPU context
var context = new WebGPUContext();
var initFuture = context.initialize();
initFuture.get(5, TimeUnit.SECONDS);

if (context.isInitialized()) {
    // Create buffers
    var inputBuffer = context.createBuffer(1024, 
        BUFFER_USAGE_STORAGE | BUFFER_USAGE_COPY_DST);
    var outputBuffer = context.createBuffer(1024, 
        BUFFER_USAGE_STORAGE | BUFFER_USAGE_COPY_SRC);
    
    // Write data
    context.writeBuffer(inputBuffer, data, 0);
    
    // Create and run compute shader
    var shader = context.createComputeShader(wgslCode);
    var pipeline = context.createComputePipeline(shader, "main");
    context.dispatchCompute(pipeline, workgroupsX, workgroupsY, workgroupsZ);
    
    // Read results
    var results = context.readBuffer(outputBuffer, 1024, 0);
    
    // Cleanup
    context.shutdown();
}
```

## Buffer Usage Patterns

### Storage Buffers (GPU Read/Write)
```java
// For GPU storage operations
var buffer = context.createBuffer(size, 
    BUFFER_USAGE_STORAGE | BUFFER_USAGE_COPY_DST | BUFFER_USAGE_COPY_SRC);
```

### Readback Buffers (CPU Read)
```java
// For reading GPU results on CPU
var readbackBuffer = context.createBuffer(size, 
    BUFFER_USAGE_MAP_READ | BUFFER_USAGE_COPY_DST);

// Use direct mapping for MAP_READ buffers
var mappedSegment = readbackBuffer.mapAsync(Buffer.MapMode.READ, 0, size).get();
var data = mappedSegment.asByteBuffer();
readbackBuffer.unmap();
```

## Next-Generation Future-Based APIs

This module includes support for the next-generation WebGPU APIs that use futures instead of callbacks for async operations. These APIs are currently loaded but not used in production code, which uses the stable callback-based APIs.

### Available Future-Based APIs
- **wgpuBufferMapAsyncF** - Future-based buffer mapping
- **wgpuInstanceWaitAny** - Future waiting and completion

### Usage Example
```java
// Check API availability
boolean futureAPIAvailable = CallbackInfoHelper.isBufferMapAsyncFAvailable() && 
                             FutureWaitHelper.isInstanceWaitAnyAvailable();

if (futureAPIAvailable) {
    // Use future-based buffer mapping
    var future = CallbackInfoHelper.mapBufferAsyncF(
        buffer.getHandle(), Buffer.MapMode.READ.getValue(), 0, size, callbackInfo);
    
    // Wait for completion
    int status = FutureWaitHelper.waitForFuture(instance.getHandle(), future, timeoutNanos);
    
    if (status == FutureWaitHelper.WGPU_WAIT_STATUS_SUCCESS) {
        // Buffer is ready for use
        var mappedRange = buffer.getMappedRange(0, size);
        // ... use mapped data ...
        buffer.unmap();
    }
}
```

### Migration Path
These APIs represent the future direction of WebGPU async operations. Current production code uses callback-based APIs for maximum stability, but can be migrated to future-based APIs when they become the recommended approach in WebGPU specifications.

## Architecture

### Core Components
- **WebGPUNative** - FFM bindings to Dawn native library
- **Wrapper Classes** - Type-safe Java objects (Instance, Device, Buffer, etc.)
- **WebGPUContext** - High-level API for common operations
- **Resource Management** - Automatic cleanup and lifecycle management

### Integration Points
- **Render Module** - Used by voxel rendering pipeline
- **Compute Operations** - GPU-accelerated algorithms
- **Surface Presentation** - Native window integration

## Documentation

### Current Documentation
- [Surface Usage Guide](doc/SURFACE_USAGE.md) - Surface creation and presentation
- [LWJGL Integration](doc/LWJGL_INTEGRATION.md) - Integration with LWJGL
- [JavaFX Integration Demo](doc/JAVAFX_INTEGRATION_DEMO.md) - JavaFX surface demo
- [IntelliJ Run Configs](doc/INTELLIJ_RUN_CONFIGS.md) - Development setup

### Archived Documentation
See `doc/archived/` for historical development documentation, phase completion summaries, and experimental results.

## Requirements

- **Java 24** - FFM API support
- **Native Libraries** - WebGPU Dawn binaries (included)
- **GPU Support** - Metal (macOS), Vulkan (Linux), D3D12 (Windows)

## Building

```bash
mvn clean compile    # Compile module
mvn test             # Run tests  
mvn clean install    # Install to local repository
```

## Platform Support

| Platform | Architecture | Status | Backend |
|----------|--------------|--------|---------|
| macOS    | ARM64        | ✅ Full | Metal   |
| macOS    | x86_64       | ✅ Full | Metal   |
| Linux    | x86_64       | ✅ Full | Vulkan  |
| Windows  | x86_64       | ✅ Full | D3D12   |

## License

Licensed under AGPL v3.0 - see LICENSE file for details.