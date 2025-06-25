# TODO Items

## Code TODOs

### Core Functionality
- `AbstractSpatialIndex.java:1352` - Optimize spatial range query for Tetree
- `Tet.java:719` - The type of the root tet is hardcoded to 0
- `TetreeBits.java:243` - In t8code, they also check if the types are different at this level

### Collision System
- `CollisionSystem.java:258,276` - Proper level handling (currently hardcoded to level 10)
- `BoxShape.java:170` - Implement proper SAT (Separating Axis Theorem) test

### Tree Building
- `StackBasedTreeBuilder.java:132` - Implement bottom-up construction
- `StackBasedTreeBuilder.java:142` - Implement hybrid construction

## Cleanup Tasks Completed (June 24, 2025)

- ✅ Removed unused classes: PluckerCoordinate.java, SimpleTMIndex.java, TmIndex.java
- ✅ Removed debug test files: TetreeRegionQueryDebugTest.java, TetreeSpatialRangeDebugTest.java
- ✅ Fixed wildcard imports in core classes: AbstractSpatialIndex, Octree, Tetree, EntityManager, SpatialIndexSet
- ✅ Updated READMEs with straightforward, humble tone
- ✅ Archived completed documentation (19 files)