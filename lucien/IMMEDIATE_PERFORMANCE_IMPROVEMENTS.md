# Immediate Performance Improvements for Bulk Operations

## Issues Identified

### 1. Inefficient Subdivision at Deep Levels
- **Problem**: Starting at level 15 with randomly distributed data causes each entity to get its own node
- **Evidence**: StackBasedTreeBuilder creates 9,999 child groups with 1 entity each
- **Impact**: Massive overhead from node creation and management

### 2. Morton Sorting Performance Degradation
- **Problem**: Pre-sorting by Morton code is slower than no sorting (1.03x vs 1.04x speedup)
- **Evidence**: Test results show "Bulk without Morton sort" outperforms "Optimized bulk"
- **Impact**: Wasted CPU cycles on sorting that doesn't improve locality

## Proposed Solutions

### Solution 1: Dynamic Level Selection
Instead of starting at a fixed deep level (15), dynamically choose the starting level based on:
- Data distribution
- Number of entities
- Spatial extent of data

### Solution 2: Batch Node Creation
When subdividing creates many single-entity nodes:
- Detect this pattern
- Create leaf nodes in batch without further subdivision attempts
- Skip intermediate node creation

### Solution 3: Adaptive Subdivision Threshold
- Increase `minEntitiesForSubdivision` for deeper levels
- Use formula like: `threshold = baseThreshold * (1 << (level - 10))`
- Prevents wasteful subdivision at deep levels

### Solution 4: Optimize Morton Sorting
- Only sort when it improves cache locality
- Use partial sorting for large datasets
- Consider Z-order curve properties at different levels

## Implementation Priority
1. **Dynamic level selection** (biggest impact)
2. **Adaptive subdivision threshold** (prevents pathological cases)
3. **Batch node creation** (reduces overhead)
4. **Morton sort optimization** (fine-tuning)