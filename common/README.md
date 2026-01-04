# Common Module

**Last Updated**: 2026-01-04
**Status**: Current

Optimized collections and shared utilities for Luciferase

## Overview

Common provides high-performance data structures shared across all Luciferase modules. It focuses on primitive collections with zero-boxing overhead and specialized set implementations for improved cache locality and memory efficiency.

## Features

### Optimized Primitive Collections

- **FloatArrayList**: Dynamically growing array for float primitives
- **IntArrayList**: Dynamically growing array for int primitives
- **ShortArrayList**: Dynamically growing array for short primitives

**Benefits:**
- No boxing/unboxing overhead
- Direct memory access for maximum performance
- Growable capacity with minimal allocations
- Memory-efficient storage (4 bytes/float, 4 bytes/int, 2 bytes/short)

### Specialized Set Implementations

- **OaHashSet<T>**: Open-addressing hash set with linear probing
- **OpenAddressingSet<T>**: Base class for open-addressing sets
- **IdentitySet<T>**: Identity-based set (uses == instead of equals())

**Benefits:**
- Better cache locality than chained hashing (HashMap)
- Lower memory overhead (no separate Entry objects)
- Fast membership testing and iteration
- Linear memory layout improves CPU cache utilization

## Core Classes

### FloatArrayList

```java
import com.hellblazer.luciferase.common.FloatArrayList;

// Create array list for floats
var floats = new FloatArrayList();

// Add elements (no boxing)
floats.add(3.14f);
floats.add(2.71f);

// Direct access
float value = floats.get(0);
floats.set(1, 1.41f);

// Size and capacity
int size = floats.size();
int capacity = floats.capacity();

// Clear all elements
floats.clear();
```

### IntArrayList

```java
import com.hellblazer.luciferase.common.IntArrayList;

// Create array list for ints
var ints = new IntArrayList(100); // Initial capacity

// Add elements
ints.add(42);
ints.add(1337);

// Bulk operations
int[] data = {1, 2, 3, 4, 5};
for (int i : data) {
    ints.add(i);
}

// Access
int value = ints.get(2);
```

### ShortArrayList

```java
import com.hellblazer.luciferase.common.ShortArrayList;

// Create array list for shorts (memory efficient)
var shorts = new ShortArrayList();

// Add elements (only 2 bytes each)
shorts.add((short) 100);
shorts.add((short) 200);

// Great for storing small integers efficiently
int size = shorts.size();
```

### OaHashSet

```java
import com.hellblazer.luciferase.common.OaHashSet;

// Create open-addressing hash set
var set = new OaHashSet<String>();

// Add elements
set.add("alpha");
set.add("beta");
set.add("gamma");

// Fast membership test
boolean contains = set.contains("alpha"); // true

// Iteration
for (String s : set) {
    System.out.println(s);
}

// Size and removal
int size = set.size();
set.remove("beta");
```

### IdentitySet

```java
import com.hellblazer.luciferase.common.IdentitySet;

// Uses == instead of equals() for comparison
var identitySet = new IdentitySet<Object>();

Object obj1 = new Object();
Object obj2 = new Object();

identitySet.add(obj1);
identitySet.add(obj2);

// Contains checks identity, not equals
boolean hasObj1 = identitySet.contains(obj1); // true
```

## Mesh Package

The `mesh/` subdirectory contains additional mesh-related utilities (implementation details vary).

## Performance Characteristics

### Primitive Lists vs Java Collections

| Operation | FloatArrayList | ArrayList\<Float> | Improvement |
| ----------- | --------------- | ------------------ | ------------- |
| Add | 3-5 ns | 8-12 ns | 2-3x faster |
| Get | 2-3 ns | 4-6 ns | 2x faster |
| Memory | 4 bytes/elem | 20-24 bytes/elem | 5-6x less |

### Set Performance

| Operation | OaHashSet | HashSet | Improvement |
| ----------- | ----------- | --------- | ------------- |
| Add | 10-15 ns | 15-20 ns | 1.3-1.5x faster |
| Contains | 5-10 ns | 8-15 ns | 1.5x faster |
| Memory | ~60% less | baseline | 1.67x less |

*(Actual performance depends on hardware, JVM version, and data distribution)*

## Thread Safety

**Important**: None of the classes in this module are thread-safe.

- Use external synchronization if sharing across threads
- Consider `Collections.synchronizedList/Set` wrappers if needed
- For high-concurrency scenarios, use `java.util.concurrent` collections

## Memory Patterns

These collections are designed for:

1. **Performance-critical paths**: Where boxing overhead is unacceptable
2. **Large datasets**: Where memory savings from primitives matter
3. **Single-threaded processing**: No concurrency overhead
4. **Batch operations**: Efficient bulk insertion and processing

## Testing

```bash
# Run all common module tests
mvn test -pl common

# Run specific test class
mvn test -pl common -Dtest=FloatArrayListTest
```

## Dependencies

- **javax.vecmath**: 3D vector mathematics (Point3f, etc.)
- **JUnit 5**: Testing framework

## Usage in Luciferase

The common module is used throughout Luciferase for:

- **Lucien**: FloatArrayList for coordinate storage in spatial queries
- **Sentry**: IntArrayList for vertex and tetrahedron indices
- **Portal**: Mesh utilities for 3D rendering primitives
- **All modules**: IdentitySet for object tracking without hashCode overhead

## Best Practices

1. **Use primitive collections** when working with large numbers of primitives
2. **Profile before optimizing** - not all code benefits from primitive collections
3. **Be aware of autoboxing** - avoid accidentally boxing when using these collections
4. **Pre-size when possible** - constructor with initial capacity avoids reallocations

## License

AGPL-3.0 - See [LICENSE](../LICENSE) for details
