- The MortonCurve is being calculated correctly. it is an index representing the hierarchical decomposition of a Cube
  and maintains spatial locality between index values "close" to each other - i.e. indexes that are close to each other
  in value are close to each other in space
- Morton codes are unique in level. Morton codes represent cells, not points. Cells have a unique level
- In the context of continued work, the primary focus is to proceed with the current implementation and explore further
  optimizations or applications of the Morton curve encoding
- Points in an octree are quantized by level. each level's cell is a different volume, and contains the points on the
  interior of the cell
- The C++ Octree implementation in /Octree is the reference implementation with full search capabilities
- The /t8code directory contains the C reference implementation of Tetree functionality
- As of December 2025, the Java lucien module has been simplified to 24 core classes
- The architecture uses AbstractSpatialIndex as the base class for both Octree and Tetree
- EntityManager provides centralized entity lifecycle management
- No specialized search classes exist - only TetrahedralSearchBase and VisibilitySearch
- The Tetree is a hierarchical decomposition of space based on the tetrahedron, not the cube.
- The Tetree and Tea define a space filling curve that maps every cell in the spatial decomposition.
- The Tetrahedron space filling curve (SFC) is very similar to the Morton Code index. However, it is not using morton
  code, rather it is a bijective function with the Morton code for tetrahedral decomposition.
- The indexes of the Tet SFC define cells rather than points. Each of these cells are located on a unique level.
  Multiple points can therefore map to a single cell based on the level.
- index 0 is the largest, top level tetrahedron and represents level 0. We have 21 levels of decomposition, like the
  Octree
- Like the Morton curve, the Tet SFC indices preserve spatial locality - indexes "close" to each other represent cells
  that are also close to each other.
- Points in an tetree are quantized by level. each level's cell is a different volume, and contains the points on the
  interior of the cell
- there are a lot of similarities between the Octree and Tetree, but be careful to keep in mind that the tetrahedron is
  not the cube and while the spatial decomposition is similar, the result is quite different.
- The cube is decomposed into six characteristic tetrahedrons, S0 - S6. The tetree uses S0 as the reference tetrahedron
  for the tree and represents the encompassing tetrahedron.
- All indices in the Tet SFC are contained within the top level tetrahedron. No exceptions.
- Coordinates within either the octree or tetree must be positive and negative coordinates are not valid for entities
- Ray origins can be negative in 3D space - only entities in the spatial index must have positive coordinates (June 2025)
- The lucien module uses a unified architecture with SpatialIndex<ID, Content> interface
- Both Octree and Tetree extend AbstractSpatialIndex for code reuse
- Multi-entity support is built into the core architecture, not added via adapters
- Package structure: core abstractions, octree/, tetree/, entity/, and geometry utilities
- The Tet SFC has many-to-one mapping: multiple coordinate-based tetrahedra can map to the same canonical SFC index
- Tetree implementation includes: TetreeConnectivity, TetreeIterator, TetreeNeighborFinder, TetreeFamily, TetreeBits, TetreeSFCRayTraversal, TetreeValidator
- ~90% t8code parity achieved for single-node tetrahedral operations (June 2025)
- Save enough context to remember where you are, I'm restarting the IDE