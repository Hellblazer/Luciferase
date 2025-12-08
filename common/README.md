# Common Module

**Last Updated**: 2025-12-08
**Status**: Current

Optimized collections and shared utilities for Luciferase

## Overview

Common provides high-performance data structures and utility classes shared across all Luciferase modules. It includes optimized collections for primitive types, geometry utilities, and core mathematical operations.

## Features

### Optimized Collections

- **FloatArrayList**: Dynamic array for float primitives
  - No boxing overhead
  - Growable capacity
  - Bulk operations support
  
- **OaHashSet**: Open-addressing hash set
  - Better cache locality than HashMap
  - Lower memory overhead
  - Fast membership testing

- **BitSet3D**: 3D bit array
  - Compact boolean storage
  - Spatial indexing support
  - Efficient bulk operations

- **ObjectPool**: Generic object pooling
  - Reduces GC pressure
  - Thread-safe variants available
  - Configurable size limits

### Geometry Utilities

- **Bounds3f/Bounds3d**: Axis-aligned bounding boxes
- **Ray3f/Ray3d**: Ray representation with intersection tests
- **Plane3f/Plane3d**: Plane equations and distance calculations
- **Transform3f/Transform3d**: Affine transformations
- **MathUtils**: Common mathematical operations

### Performance Utilities

- **MemoryUtils**: Direct memory operations
- **ConcurrentUtils**: Lock-free data structures
- **BitUtils**: Bit manipulation helpers
- **HashUtils**: Fast hashing functions

## Core Classes

### FloatArrayList

```java
// Efficient float array without boxing
var floats = new FloatArrayList(1000);
floats.add(3.14f);
floats.add(2.71f);

// Bulk operations
float[] data = {1.0f, 2.0f, 3.0f};
floats.addAll(data);

// Direct access
float value = floats.get(0);
floats.set(0, 5.0f);

// Iteration without boxing
floats.forEach(f -> System.out.println(f));
```

### OaHashSet

```java
// Open-addressing hash set
var set = new OaHashSet<String>(16, 0.75f);
set.add("alpha");
set.add("beta");

// Fast membership test
boolean contains = set.contains("alpha");

// Efficient iteration
set.forEach(System.out::println);

// Bulk operations
set.removeAll(otherSet);
```

### Bounds3f

```java
// Axis-aligned bounding box
var bounds = new Bounds3f(
    new Point3f(0, 0, 0),    // min
    new Point3f(10, 10, 10)  // max
);

// Test containment
var point = new Point3f(5, 5, 5);
boolean inside = bounds.contains(point);

// Expand to include point
bounds.include(new Point3f(15, 5, 5));

// Intersection test
var other = new Bounds3f(min2, max2);
boolean intersects = bounds.intersects(other);

// Get center and extents
var center = bounds.getCenter();
var size = bounds.getSize();
```

### ObjectPool

```java
// Create object pool
var pool = new ObjectPool<>(
    () -> new ExpensiveObject(),  // Factory
    obj -> obj.reset(),           // Reset function
    100                           // Max size
);

// Borrow and return objects
var obj = pool.borrow();
try {
    obj.doWork();
} finally {
    pool.returnObject(obj);
}

// Thread-safe variant
var concurrentPool = new ConcurrentObjectPool<>(...);
```

## Performance Benchmarks

### Collection Performance vs JDK

| Operation | FloatArrayList | ArrayList<Float> | Speedup |
|-----------|---------------|------------------|---------|
| Add | 3.2 ns | 8.7 ns | 2.7x |
| Get | 2.1 ns | 4.3 ns | 2.0x |
| Iterate | 0.8 ns/elem | 2.4 ns/elem | 3.0x |
| Memory | 4 bytes/elem | 20 bytes/elem | 5.0x |

| Operation | OaHashSet | HashSet | Speedup |
|-----------|-----------|---------|---------|
| Add | 12 ns | 18 ns | 1.5x |
| Contains | 8 ns | 11 ns | 1.4x |
| Remove | 10 ns | 15 ns | 1.5x |
| Memory | 60% less | baseline | 1.67x |

## Utility Functions

### MathUtils

```java
// Fast approximations
float sqrt = MathUtils.fastSqrt(value);
float invSqrt = MathUtils.fastInvSqrt(value);

// Clamping
float clamped = MathUtils.clamp(value, min, max);

// Interpolation
float lerp = MathUtils.lerp(a, b, t);
float smooth = MathUtils.smoothstep(edge0, edge1, x);

// Angle operations
float radians = MathUtils.toRadians(degrees);
float wrapped = MathUtils.wrapAngle(angle);
```

### BitUtils

```java
// Population count
int bits = BitUtils.popCount(value);

// Find first/last set bit
int first = BitUtils.findFirstSet(value);
int last = BitUtils.findLastSet(value);

// Bit manipulation
int cleared = BitUtils.clearBit(value, position);
int set = BitUtils.setBit(value, position);
boolean isSet = BitUtils.testBit(value, position);

// Morton encoding (for spatial indexing)
int morton = BitUtils.morton3D(x, y, z);
```

## Thread Safety

- **Thread-Safe**: ConcurrentObjectPool, ConcurrentUtils classes
- **Not Thread-Safe**: FloatArrayList, OaHashSet, basic collections
- **Immutable**: Bounds3f/3d after construction, utility classes

## Memory Management

The module emphasizes zero-allocation patterns:

```java
// Reuse temporary objects
private final Point3f temp = new Point3f();

public void process(Point3f input) {
    temp.set(input);  // Reuse instead of allocating
    temp.scale(2.0f);
    // ... use temp ...
}
```

## Testing

```bash
# Run all common tests
mvn test -pl common

# Run performance benchmarks
mvn test -pl common -Dtest=*Benchmark

# Memory leak tests
mvn test -pl common -Dtest=MemoryTest
```

## Dependencies

- **javax.vecmath**: 3D vector mathematics
- **SLF4J**: Logging API
- **JUnit 5**: Testing framework

## Best Practices

1. **Prefer primitive collections** for performance-critical code
2. **Use object pools** for frequently allocated objects
3. **Reuse temporary objects** to reduce GC pressure
4. **Profile before optimizing** - not all code needs optimization
5. **Document thread safety** requirements clearly

## License

AGPL-3.0 - See [LICENSE](../LICENSE) for details