# ESVO Architectural Guardrail Tests

## ⚠️ INTENTIONALLY FAILING TESTS ⚠️

The tests in `ESVOReferenceValidationTest` are **designed to fail** until critical architectural issues in the ESVO implementation are resolved. These are not bugs to be "fixed" by changing test assertions - they are protective guardrails that block development until the underlying architecture is corrected.

## Purpose

These tests serve as **architectural guardrails** that:
- Block development progression until critical issues are resolved
- Document exactly what needs to be fixed
- Prevent accidental deployment of incompatible implementations
- Ensure compliance with the CUDA reference implementation

## Critical Issue: Three Incompatible Node Structures

Currently, the ESVO implementation has **three different node structures** that are completely incompatible:

### 1. ESVONode.java (8 bytes)
```java
private int childDescriptor;    // 4 bytes
private int contourDescriptor;  // 4 bytes
```

### 2. ESVODataStructures.OctreeNode (32 bytes)
```java
public int childDescriptor;     // 4 bytes
public int contourPointer;      // 4 bytes  
public float minValue;          // 4 bytes
public float maxValue;          // 4 bytes
public int attributes;          // 4 bytes
public int padding1, padding2, padding3; // 12 bytes
```

### 3. ESVOKernels GLSL/OpenCL (16 bytes)
```glsl
struct OctreeNode {
    uint parent;      // 4 bytes
    uint childMask;   // 4 bytes
    uint childPtr;    // 4 bytes
    uint voxelData;   // 4 bytes
};
```

## CUDA Reference Implementation (Target)

According to Laine & Karras 2010, the reference uses:
- **Total size**: 8 bytes (int2)
- **child_descriptor.x**: [valid(1)|far(1)|dummy(1)|childptr(14)|childmask(8)|leafmask(8)]
- **child_descriptor.y**: [contour_ptr(24)|contour_mask(8)]
- **Child indexing**: parent_ptr + popc8(child_masks & 0x7F)
- **Coordinate system**: [1, 2] space (not [0, 1])
- **Stack depth**: 23 levels

## Required Actions Before Tests Pass

1. **Unify Node Structures**: Choose ONE structure that matches CUDA reference
2. **Fix Child Indexing**: Implement proper sparse indexing algorithm
3. **Align Coordinate System**: Use [1, 2] space with mirroring optimization
4. **Stack Management**: Implement 23-level stack with proper push optimization

## DO NOT

- ❌ Change test assertions to make them pass
- ❌ Skip or disable these tests
- ❌ Treat these as "broken tests" to be fixed

## DO

- ✅ Use these tests as a specification for what needs to be implemented
- ✅ Fix the underlying architectural inconsistencies
- ✅ Keep these tests as red until the architecture is correct
- ✅ Reference these tests when planning ESVO refactoring

## Test Status

These tests will remain **red** (failing) until the fundamental ESVO architecture is corrected. This is the intended behavior and protects the codebase from proceeding with incompatible implementations.

When properly implemented, these tests will turn **green** and serve as validation that the ESVO implementation matches the CUDA reference.