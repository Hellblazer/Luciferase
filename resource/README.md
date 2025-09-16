# Resource Lifecycle Management Module

## Overview

The Resource Lifecycle Management module provides a unified, type-safe system for managing GPU resources across OpenGL and OpenCL APIs in the Luciferase project. It implements RAII (Resource Acquisition Is Initialization) patterns in Java to ensure deterministic resource cleanup and prevent memory leaks.

## Key Features

- **Unified Resource Management**: Single API for managing both OpenGL and OpenCL resources
- **RAII Pattern**: Automatic resource cleanup using AutoCloseable interfaces
- **Memory Pooling**: Efficient ByteBuffer pooling with configurable eviction policies
- **Leak Detection**: Built-in resource leak tracking and reporting
- **Thread-Safe**: Concurrent access support with fine-grained synchronization
- **Performance Monitoring**: Resource usage statistics and performance metrics

## Architecture

### Core Components

#### UnifiedResourceManager
Central manager for all GPU resources with memory pooling and lifecycle management.

```java
// Create manager with default configuration
var manager = new UnifiedResourceManager();

// Allocate memory from pool
ByteBuffer buffer = manager.allocateMemory(1024);

// Use buffer...

// Return to pool for reuse
manager.releaseMemory(buffer);

// Cleanup old resources
manager.performMaintenance();
```

#### ResourceHandle<T>
Base class implementing RAII pattern for native resources:

```java
public abstract class ResourceHandle<T> implements AutoCloseable {
    protected abstract void doCleanup(T resource);
    // Automatic cleanup on close()
}
```

#### GPUResource Interface
Common interface for all GPU resources:

```java
public interface GPUResource extends AutoCloseable {
    String getId();
    GPUResourceType getType();
    long getSizeBytes();
    ResourceStatistics getStatistics();
}
```

#### Memory Pool
Efficient ByteBuffer pooling with statistics:

```java
var pool = new MemoryPool(maxSizeBytes, maxIdleTime);
ByteBuffer buffer = pool.allocate(size);
pool.returnToPool(buffer);
```

### Resource Types

- `BUFFER`: OpenGL/OpenCL buffer objects
- `TEXTURE`: OpenGL texture objects  
- `SHADER`: OpenGL shader programs
- `KERNEL`: OpenCL kernel objects
- `QUEUE`: OpenCL command queues
- `MEMORY_POOL`: Pooled ByteBuffers

## Usage Examples

### Basic Resource Management

```java
// Initialize manager
var config = ResourceConfiguration.builder()
    .maxPoolSizeBytes(100 * 1024 * 1024) // 100MB pool
    .maxIdleTime(Duration.ofMinutes(5))
    .enableLeakDetection(true)
    .build();

var manager = new UnifiedResourceManager(config);

// Allocate resources
ByteBuffer buffer = manager.allocateMemory(4096);

// Use buffer for GPU operations...

// Release when done
manager.releaseMemory(buffer);

// Periodic maintenance
manager.performMaintenance();

// Shutdown
manager.close();
```

### OpenGL Integration

```java
public class GLBufferResource extends ResourceHandle<Long> implements GPUResource {
    private final long bufferId;
    
    public GLBufferResource(long bufferId) {
        super(bufferId, tracker);
        this.bufferId = bufferId;
    }
    
    @Override
    protected void doCleanup(Long resource) {
        GL15.glDeleteBuffers(resource.intValue());
    }
    
    public void bind() {
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, bufferId);
    }
}
```

### OpenCL Integration

```java
public class CLBufferResource extends ResourceHandle<Long> implements GPUResource {
    private final long clBuffer;
    
    @Override
    protected void doCleanup(Long resource) {
        CL10.clReleaseMemObject(resource);
    }
}
```

### Resource Statistics

```java
// Get statistics
var stats = manager.getStatistics();
System.out.println("Total resources: " + stats.getTotalResources());
System.out.println("Memory usage: " + stats.getTotalAllocatedBytes());

// Per-type statistics
var typeStats = stats.getTypeStatistics();
for (var entry : typeStats.entrySet()) {
    System.out.println(entry.getKey() + ": " + entry.getValue());
}

// Pool statistics
var poolStats = manager.getMemoryPool().getPoolStatistics();
System.out.println("Pool hit rate: " + poolStats.getHitRate());
```

## Configuration

### ResourceConfiguration Options

- `maxPoolSizeBytes`: Maximum memory pool size (default: 50MB)
- `maxIdleTime`: Maximum resource idle time (default: 5 minutes)
- `enableLeakDetection`: Enable leak tracking (default: true)
- `evictionPolicy`: Pool eviction policy (LRU, FIFO, LFU)

### Example Configuration

```java
var config = ResourceConfiguration.builder()
    .maxPoolSizeBytes(200 * 1024 * 1024) // 200MB
    .maxIdleTime(Duration.ofMinutes(10))
    .enableLeakDetection(true)
    .evictionPolicy(EvictionPolicy.LRU)
    .build();
```

## Thread Safety

The module is designed for concurrent access:

- `UnifiedResourceManager`: Thread-safe with concurrent collections
- `MemoryPool`: Read-write locks for pool operations
- `ResourceTracker`: Thread-safe resource registration
- `BufferResource`: Identity-based mapping prevents collision

## Performance Considerations

### Memory Pooling Benefits
- Reduces allocation overhead
- Minimizes GC pressure  
- Improves cache locality
- Configurable pool size and eviction

### Benchmarks
See `ResourceBenchmark` class for performance metrics:
- Allocation: ~100ns per buffer from pool
- Release: ~50ns to return to pool
- Overhead: <5% vs direct allocation

## Testing

The module includes comprehensive tests:

```bash
# Run all tests
mvn test -pl resource

# Run specific test
mvn test -pl resource -Dtest=UnifiedResourceManagerTest

# Run benchmarks
mvn test -pl resource -Dtest=ResourceBenchmark
```

## Integration with Luciferase

The resource module integrates with:
- **render**: OpenGL resource management
- **opencl**: OpenCL resource management  
- **portal**: JavaFX visualization resources
- **lucien**: Spatial index memory management

## Best Practices

1. **Always use try-with-resources** for automatic cleanup
2. **Call performMaintenance() periodically** to clean up old resources
3. **Configure pool size** based on application needs
4. **Enable leak detection** in development/testing
5. **Monitor statistics** for performance tuning

## Troubleshooting

### Common Issues

**Memory Leaks**: Enable leak detection and check logs for leaked resource IDs

**Pool Exhaustion**: Increase maxPoolSizeBytes or reduce resource retention

**Performance**: Monitor hit rate and adjust pool configuration

### Debug Logging

Enable debug logging for detailed resource tracking:

```xml
<logger name="com.hellblazer.luciferase.resource" level="DEBUG"/>
```

## Future Enhancements

- [ ] Vulkan API support
- [ ] Distributed resource management
- [ ] Advanced eviction policies
- [ ] Resource usage prediction
- [ ] Automatic pool sizing

## License

Licensed under AGPL v3.0