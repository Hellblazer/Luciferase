# Memory Usage Discrepancy Analysis - June 28, 2025

## Executive Summary

**UPDATE: Issue Resolved!** The memory discrepancy was caused by a bug in Tetree's subdivision logic. After fixing the subdivision behavior, Tetree now uses similar memory to Octree (93-103%).

**Original Issue**:
- **OctreeVsTetreeBenchmark**: Showed Tetree using 20% of Octree memory
- **Root Cause**: Tetree was creating only 2 nodes vs Octree's 6,430 nodes (3,215:1 ratio)
- **Solution**: Fixed Tetree's `insertAtPosition` to properly subdivide like Octree does

See `TETREE_SUBDIVISION_FIX_JUNE_28_2025.md` for details of the fix.

## Detailed Analysis

### 1. What OctreeVsTetreeBenchmark Measures

```java
// From OctreeVsTetreeBenchmark.java
private long getUsedMemory() {
    Runtime runtime = Runtime.getRuntime();
    return runtime.totalMemory() - runtime.freeMemory();
}

// Measurement process:
System.gc();
baseMemory = getUsedMemory();
tetree = new Tetree<...>(...);
// Insert all entities
System.gc();
tetreeMemory = getUsedMemory() - baseMemory;
```

**Key characteristics:**
- Uses `Runtime.getRuntime()` for memory measurement
- Measures **steady-state memory** after all insertions
- Runs garbage collection before measurement
- Benefits from **lazy evaluation** - many TetreeKeys haven't computed tmIndex() yet
- Measures only **retained heap** after GC
- No active operations during measurement

### 2. What Other Memory Tests Measure

The SpatialIndexMemoryPerformanceTest and derived tests use different methodology:

```java
// From SpatialIndexMemoryPerformanceTest.java
private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();

private long getHeapUsage() {
    MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
    return heapUsage.getUsed();
}
```

**Key differences:**
- Uses `MemoryMXBean` for more precise heap measurement
- Tracks memory **during operations**
- Calculates **bytes per entity** after operations complete
- May trigger tmIndex() computation through subsequent operations
- More accurate reflection of JVM heap usage

### 3. Key Differences in Memory Usage

#### A. Data Structure Sizes

**MortonKey (Octree):**
```java
public class MortonKey {
    private final long mortonCode;  // 8 bytes
    private final byte level;       // 1 byte
    // Total: ~9 bytes + object overhead
}
```

**TetreeKey (Tetree):**
```java
public class TetreeKey {
    private final long lowBits;     // 8 bytes
    private final long highBits;    // 8 bytes  
    private final byte level;       // 1 byte
    // Total: ~17 bytes + object overhead
}
```

#### B. Lazy Evaluation Impact (Not the Cause!)

**Important Discovery**: Lazy evaluation is **disabled by default** in Tetree:
```java
private boolean useLazyEvaluation = false;  // From Tetree.java line 112
```

The Tetree implementation has `LazyTetreeKey` capability but it's not used in the benchmarks:
- OctreeVsTetreeBenchmark doesn't enable lazy evaluation
- All tmIndex() computations happen during insertion
- This is NOT the cause of the memory discrepancy

#### C. Operation Memory Overhead

**During tmIndex() computation:**
- Walks up parent chain (O(level) operations)
- Creates temporary Tet objects at each level
- Performs tetrahedral geometry calculations
- All this happens during queries, not during insertion

### 4. Why Both Measurements Are Valid

#### OctreeVsTetreeBenchmark (20% usage) measures:
- ✅ Post-insertion steady state
- ✅ Minimal memory footprint
- ✅ Benefits of lazy evaluation
- ❌ Doesn't reflect operational memory usage
- ❌ Doesn't force index computation

#### Other tests (193-407x more) measure:
- ✅ Peak operational memory
- ✅ Realistic query workload memory
- ✅ Full index computation costs
- ❌ May include transient allocations
- ❌ May overstate steady-state usage

### 5. The Real Cause: Node Count Difference

**Critical Discovery**: The memory difference is due to **vastly different node counts**:

For the same dataset at level 10:
- **Octree**: Creates ~1000 nodes (one per entity in typical case)
- **Tetree**: Creates only ~2 nodes (many entities map to same cell)
- **Ratio**: 500:1 difference in node count!

**Why this happens:**
- At level 10, tetrahedral cells cover larger spatial volumes than cubic cells
- Multiple entities that would be in different Octree cells end up in the same Tetree cell
- Fewer nodes = less memory usage
- This is a fundamental geometric property, not an optimization

**Trade-offs:**
- **Memory**: Tetree uses less memory due to fewer nodes
- **Spatial Resolution**: Tetree has coarser partitioning
- **Query Performance**: Larger nodes mean more entities to check during queries

### 6. Reconciling the Results

Both measurements are telling the truth about different aspects:

1. **Storage Efficiency**: Tetree may store the tree structure more compactly
2. **Operational Overhead**: Tetree has massive overhead during operations
3. **Lazy Evaluation**: Masks true memory cost until operations force computation

### 7. Recommendations

For accurate memory comparison, we should measure:

1. **Steady-State Memory** (like OctreeVsTetreeBenchmark)
   - After all insertions
   - After GC
   - No active operations

2. **Operational Memory** (like other tests)
   - During queries
   - Peak usage
   - With all indices computed

3. **Forced Computation Memory**
   - After insertion but before queries
   - Force all lazy computations: `tree.forceComputeAllIndices()`
   - Measure true steady-state with all data structures built

### 8. Conclusion

The memory discrepancy is not a bug or measurement error. It reflects fundamental geometric differences:

**The Real Story:**
- **OctreeVsTetreeBenchmark (20% usage)**: Tetree creates 500x fewer nodes due to coarser spatial partitioning at level 10
- **Other tests (193-407x more)**: Measure different scenarios where Tetree's larger key size and O(level) operations dominate
- **Not due to lazy evaluation**: Lazy evaluation is disabled by default

**Why both are correct:**
1. At level 10, tetrahedral cells are much larger than cubic cells
2. Fewer, larger nodes = less memory but coarser spatial resolution
3. When measuring per-entity overhead in dense trees, Tetree uses more memory
4. When measuring sparse trees with few nodes, Tetree uses less memory

For documentation, we should:
1. Explain the node count difference
2. Note that memory usage depends heavily on spatial distribution and level
3. Clarify that Tetree trades spatial resolution for memory efficiency
4. Recommend testing with your specific use case to determine actual memory usage