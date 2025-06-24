# Spatial Key Refactoring Plan

## Overview
The Luciferase spatial indexing system is undergoing a major refactoring to introduce a generic `SpatialKey` interface. This refactoring replaces the previous `long`-based index system with type-safe key implementations for both Octree (MortonKey) and Tetree (TetreeKey) structures.

## Current State Analysis

### Key Components
1. **SpatialKey Interface**: Base interface with `getLevel()`, `isValid()`, `root()`, and `Comparable<K>` contract
2. **MortonKey**: Wraps existing Morton code (long) for Octree operations
3. **TetreeKey**: Combines level + BigInteger tm-index for Tetree operations

### Major Issues Identified (52 compilation errors)

#### 1. Type Parameter Propagation Issues
- AbstractSpatialIndex now requires `Key extends SpatialKey<Key>` as first type parameter
- Many classes still reference old 2-parameter versions (missing Key parameter)
- Static methods cannot reference non-static type variable Key

#### 2. Type Conversion Issues
- Direct conversions between Key and long/int no longer valid
- Arithmetic operations on Key types not supported
- Bitwise operations (>>, &) not applicable to Key types

#### 3. Collection Type Mismatches
- NavigableSet<Key> vs NavigableSet<Long>
- Map<Key, NodeType> vs Map<Long, NodeType>
- Stream operations expecting long but receiving Key

#### 4. Method Signature Changes
- getLevelFromIndex(Key) method missing
- tetrahedron() methods expecting long, not TetreeKey
- Override annotations failing due to signature mismatches

## Refactoring Plan

### Phase 1: Core Infrastructure (High Priority)

#### 1.1 AbstractSpatialIndex Fixes
- Add abstract method: `protected abstract byte getLevelFromIndex(Key key);`
- Fix static context issues in utility methods
- Update all generic bounds to include Key parameter
- Fix Map.Entry<Key,NodeType> conversions

#### 1.2 BulkOperationProcessor Fixes
- Replace arithmetic/bitwise operations with Key-specific methods
- Update lambda expressions to work with Key types
- Fix SfcEntity generic parameters

#### 1.3 StackBasedTreeBuilder Fixes
- Update BuildStackFrame to accept proper Key bounds
- Fix type inference issues

### Phase 2: Visitor Pattern Updates (High Priority)

#### 2.1 Update Visitor Interfaces
- Add Key type parameter to TreeVisitor interface
- Update AbstractTreeVisitor with Key parameter
- Fix NodeCountVisitor and EntityCollectorVisitor

### Phase 3: Octree-Specific Fixes (High Priority)

#### 3.1 MortonKey Operations
- Add utility methods for level-based operations
- Implement parent/child key calculations
- Add conversion methods where needed

#### 3.2 OctreeSubdivisionStrategy
- Update to work with MortonKey instead of long
- Fix calculateChildIndex implementations

### Phase 4: Tetree-Specific Fixes (High Priority)

#### 4.1 TetreeKey Arithmetic Operations
- Implement comparison operators (compareTo already exists)
- Add methods for parent/child relationships
- Create utility class for TetreeKey arithmetic

#### 4.2 TetreeIterator Updates
- Update constructor parameters to use NavigableSet<TetreeKey>
- Fix Map<TetreeKey, TetreeNodeImpl<ID>> usage

#### 4.3 Tetree Class Fixes
- Update tetrahedron() calls to accept TetreeKey
- Fix range iteration logic
- Update override annotations

### Phase 5: Test Updates (Medium Priority)
- Update all test classes to use MortonKey/TetreeKey
- Fix test assertions expecting long values
- Update performance benchmarks

### Phase 6: Bulk Operations (Medium Priority)
Create scripts for common refactoring patterns:
- Long to Key conversions
- Method signature updates
- Generic parameter additions

### Phase 7: Validation (Medium Priority)
- Run full test suite
- Fix remaining edge cases
- Performance validation

## Implementation Strategy

### Principles
1. **No Adapter Patterns**: Direct implementation without wrapper classes
2. **Maintain SpatialKey Contract**: Respect the interface boundaries
3. **Type Safety**: Leverage generics for compile-time safety
4. **Performance**: Minimize overhead from abstraction

### Key Design Decisions

#### 1. Arithmetic Operations
Since SpatialKey doesn't support arithmetic operations, we need alternative approaches:
- For range iterations: Use NavigableSet operations (subSet, headSet, tailSet)
- For parent/child calculations: Add specific methods to Key implementations
- For level-based operations: Use the getLevel() method

#### 2. Conversion Strategy
- MortonKey: Expose getMortonCode() for algorithms requiring long values
- TetreeKey: Expose getTmIndex() and getLevel() separately
- Create utility methods for common conversions

#### 3. Collection Updates
- Replace Set<Long> with Set<Key> throughout
- Update all Map<Long, NodeType> to Map<Key, NodeType>
- Fix Stream operations to work with Key types

## Script Templates

### 1. Basic Type Replacement
```bash
# Replace Map<Long, NodeType> with Map<Key, NodeType>
find . -name "*.java" -type f -exec sed -i '' 's/Map<Long,\s*\([^>]*\)>/Map<Key, \1>/g' {} +

# Replace NavigableSet<Long> with NavigableSet<Key>
find . -name "*.java" -type f -exec sed -i '' 's/NavigableSet<Long>/NavigableSet<Key>/g' {} +
```

### 2. Method Signature Updates
```bash
# Update visitor interfaces
find . -name "*Visitor.java" -type f -exec sed -i '' 's/TreeVisitor<ID>/TreeVisitor<Key, ID>/g' {} +
```

## Risk Mitigation

1. **Backup Strategy**: Ensure all changes are in version control
2. **Incremental Testing**: Test each phase before proceeding
3. **Performance Monitoring**: Compare before/after benchmarks
4. **Rollback Plan**: Tag current state for easy reversion

## Success Criteria

1. Zero compilation errors
2. All tests passing
3. Performance within 5% of original
4. Type safety enforced throughout
5. No runtime ClassCastExceptions

## Next Steps

1. Review and approve this plan
2. Create feature branch for refactoring
3. Implement Phase 1 fixes
4. Validate with subset of tests
5. Continue with subsequent phases

This refactoring will improve type safety, maintainability, and extensibility of the spatial indexing system while preserving performance characteristics.