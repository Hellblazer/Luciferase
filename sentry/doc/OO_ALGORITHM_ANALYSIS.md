# Object-Oriented Delaunay Algorithm Analysis

## Core Data Structures

### 1. Vertex

- **Purpose**: Represents a 3D point in the tetrahedralization
- **Key Fields**:
  - `x, y, z`: Coordinates (extends Vector3f)
  - `adjacent`: One tetrahedron that contains this vertex
  - `next`: Linked list pointer for vertex iteration
- **Key Methods**:
  - `getStar()`: Returns all tetrahedra containing this vertex
  - `locate()`: Find tetrahedron containing a point starting from this vertex

### 2. Tetrahedron

- **Purpose**: Represents a tetrahedron in the mesh
- **Key Fields**:
  - `a, b, c, d`: Four vertices (ordered for positive orientation)
  - `nA, nB, nC, nD`: Four neighboring tetrahedra (opposite each vertex)
- **Key Methods**:
  - `orientationWrt()`: Test which side of a face a point lies on
  - `locate()`: Walk through mesh to find containing tetrahedron
  - `flip1to4()`: Split tetrahedron by inserting vertex
  - `patch()`: Update neighbor relationships

### 3. OrientedFace

- **Purpose**: Represents a face of a tetrahedron with orientation
- **Key Information**:
  - Which tetrahedron it belongs to
  - Which vertex it's opposite to
  - Methods to check convexity/reflexivity for flips

### 4. Grid/MutableGrid

- **Purpose**: Container for the entire tetrahedralization
- **Key Fields**:
  - `fourCorners`: The 4 vertices of the encompassing tetrahedron
  - `head`: Linked list of tracked vertices
  - `last`: Last accessed tetrahedron (optimization)
  - `size`: Number of tracked vertices

## Algorithm Flow

### Initialization

1. Create 4 corner vertices at ±SCALE (2^24) to form encompassing tetrahedron
2. Create initial tetrahedron from these 4 corners
3. This ensures all reasonable points fall inside the mesh

### Point Insertion (track)

1. **Check bounds**: Verify point is inside fourCorners tetrahedron
2. **Locate**: Find tetrahedron containing the point
   - Start from `last` tetrahedron (spatial locality optimization)
   - Walk through mesh following faces the point is "outside" of
3. **Insert**: Add vertex to mesh
   - Perform flip1to4 on containing tetrahedron
   - Process "ears" - faces that might violate Delaunay property
   - Cascade flips until Delaunay property restored

### Locate Algorithm

```text

locate(point, startTet):
  current = startTet
  loop:
    for each face of current:
      if point is on negative side of face:
        neighbor = current.getNeighbor(face)
        if neighbor is null:
          return null  // outside convex hull
        current = neighbor
        continue loop
    return current  // point is inside current

```text

### Flip Operations

#### flip1to4 (Point in Tetrahedron)

- Split one tetrahedron into four by adding vertex at center
- Creates 4 new tetrahedra sharing the new vertex
- Original tetrahedron is deleted
- Returns list of potentially non-Delaunay faces ("ears")

#### flip2to3 (Edge Flip)

- Two tetrahedra sharing a face become three sharing an edge
- Used when vertex is on a face

#### flip3to2 (Inverse of 2to3)

- Three tetrahedra sharing an edge become two sharing a face
- Used to restore Delaunay property

## Critical Invariants

### 1. Mesh Connectivity

- Every tetrahedron has exactly 4 neighbors (or null at convex hull boundary)
- Neighbor relationships are bidirectional: if A neighbors B, then B neighbors A
- No dangling references to deleted tetrahedra

### 2. Delaunay Property

- No vertex lies inside the circumsphere of any tetrahedron
- Maintained by flip operations after insertion

### 3. Orientation Consistency

- Vertices {a,b,c} are positively oriented with respect to d
- Neighbor patching must maintain consistent orientations

### 4. Encompassing Property

- The fourCorners tetrahedron logically contains all tracked points
- Even after flips, the mesh covers the original space

### 5. Vertex-Tetrahedron Adjacency

- Every vertex maintains a reference to at least one containing tetrahedron
- This enables efficient local searches

## Key Insights

### Memory Management

- Tetrahedra are pooled for efficiency
- Deleted tetrahedra are returned to pool
- Vertices are linked in a list for iteration

### Optimization Strategies

- `last` tetrahedron provides spatial locality for searches
- Random walk in locate() avoids worst-case behavior
- Vertex adjacency enables local operations

### Edge Cases

- Points exactly on faces/edges require special handling
- Coplanar points can create degenerate tetrahedra
- Numerical precision issues near boundaries

## Translation Challenges for SoA

1. **Object Identity**: OO uses object references; SoA must use indices
2. **Bidirectional Updates**: Updating neighbors requires finding reverse references
3. **Deletion Handling**: Must maintain referential integrity with indices
4. **Face Representations**: OrientedFace objects must become index+face pairs
5. **Memory Locality**: Must organize data for cache efficiency
6. **Thread Safety**: Must handle concurrent access appropriately
